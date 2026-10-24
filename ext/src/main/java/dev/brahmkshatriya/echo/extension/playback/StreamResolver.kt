package dev.brahmkshatriya.echo.extension.playback

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.ExtractionCipherService
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.findSabrProtocolException
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream

internal data class PlaybackRequest(
    val videoId: String,
    val quality: AudioQuality,
    val wantVideo: Boolean,
    val maxHeight: Int?,
    val sabr: Boolean,
    val download: Boolean,
)

internal data class PlaybackVariant(
    val id: String,
    val title: String,
    val quality: Int,
    val stream: ExtractedStream,
    val pendingFormat: Format? = null,
    val playerUrl: String? = null,
    val proofUrl: String? = null,
)

internal data class ResolvedPlayback(
    val default: ExtractedStream,
    val variants: List<PlaybackVariant>,
) {
    fun streamables(videoId: String): List<Streamable> {
        val video = default.videoUrl != null
        val language = default.sabrBootstrap?.audioTrackId
        val available =
            variants
                .filter { it.id.startsWith(if (video) "v:" else "a:") }
                .filter { video || language == null || it.stream.sabrBootstrap?.audioTrackId == language }
                .sortedWith(
                    compareBy<PlaybackVariant> { it.quality }
                        .thenBy { it.stream.itag != default.itag }
                        .thenBy { it.stream.videoItag != default.videoItag }
                        .thenBy { it.stream.sabrBootstrap?.isDrc == true },
                ).distinctBy { if (video) it.quality else it.quality / 1000 }
        val choices =
            if (available.size <=
                3
            ) {
                available
            } else {
                listOf(available.first(), available[available.size / 2], available.last())
            }
        val labels =
            when (choices.size) {
                1 -> listOf("High")
                2 -> listOf("Low", "High")
                else -> listOf("Low", "Medium", "High")
            }
        val automaticTitle = "Automatic • ${if (video) "${default.videoHeight ?: 0}p • " else ""}${audioLabel(default)}"
        return listOf(Streamable.server(videoId, Int.MAX_VALUE, automaticTitle, mapOf("videoId" to videoId, "variant" to "auto"))) +
            choices.mapIndexed { index, variant ->
                Streamable.server(
                    "$videoId:${variant.id}",
                    variant.quality,
                    "${labels[index]} • ${if (video) "${variant.stream.videoHeight}p" else "${variant.quality / 1000} kbps"}",
                    mapOf(
                        "videoId" to videoId,
                        "variant" to variant.id,
                    ),
                )
            }
    }
}

@OptIn(ExperimentalSabrApi::class)
internal class StreamResolver(
    private val extractor: StreamExtractor,
    private val cipher: ExtractionCipherService,
    private val adapters: StreamAdapters,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val preloadScope: CoroutineScope? = null,
    private val nativeWarmup: suspend (suspend () -> Unit) -> Unit = { it() },
    private val invalidateAttestation: suspend () -> Unit = {},
) {
    private val cache = ResolutionCache<PlaybackRequest, ResolvedPlayback>()
    private val selectedCache = ResolutionCache<Pair<PlaybackRequest, String>, ExtractedStream>()
    private val preloadLock = Any()
    private var speculation: Pair<PlaybackRequest, Job>? = null
    private var foregroundCount = 0
    private val failedSabr = linkedMapOf<String, Long>()

    fun clear() {
        synchronized(preloadLock) {
            speculation?.second?.cancel()
            speculation = null
            failedSabr.clear()
        }
        cache.clear()
        selectedCache.clear()
        adapters.clearStartup()
    }

    fun prefetch(request: PlaybackRequest) {
        val scope = preloadScope ?: return
        synchronized(preloadLock) {
            if (foregroundCount > 0 || speculation?.let { it.first == request && it.second.isActive } == true) return
            speculation?.second?.cancel()
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        nativeWarmup { resolveCached(request) }
                    } catch (
                        error: CancellationException,
                    ) {
                        throw error
                    } catch (_: Exception) {
                        // A tap gets the full foreground extraction path.
                    }
                }
            speculation = request to job
            job.start()
        }
    }

    suspend fun resolve(request: PlaybackRequest): ResolvedPlayback {
        val background =
            synchronized(preloadLock) {
                foregroundCount++
                speculation.also { speculation = null }
            }
        try {
            background?.let { (prefetched, job) ->
                if (prefetched != request || withTimeoutOrNull(100) {
                        job.join()
                        true
                    } != true
                ) {
                    job.cancel()
                }
                // Let a cancelled speculative cache owner remove its pending entry before retrying.
                job.join()
            }
            return resolveCached(request)
        } finally {
            synchronized(preloadLock) { foregroundCount-- }
        }
    }

    private suspend fun resolveCached(request: PlaybackRequest): ResolvedPlayback {
        val effective = if (request.sabr && sabrBlocked(request.videoId)) directRequest(request) else request
        return cache.getOrLoad(effective) {
            val capture = PlayerCapture()
            val extracted = withContext(capture) { extractWithFallback(effective) }
            val stream = enrichMetadata(extracted, capture)
            val discovered = discoverVariants(stream, capture)
            val preferredVideo =
                if (request.wantVideo && stream.videoUrl == null) {
                    discovered.variants
                        .filter {
                            it.id.startsWith(
                                "v:",
                            ) && (it.stream.videoHeight ?: 0) <= (request.maxHeight ?: Int.MAX_VALUE)
                        }.maxWithOrNull(compareBy<PlaybackVariant> { it.stream.videoHeight ?: 0 }.thenBy { it.stream.videoBitrate ?: 0 })
                } else {
                    null
                }
            val playback = if (preferredVideo != null) discovered.copy(default = preferredVideo.stream) else discovered
            playback to cacheTtl(playback)
        }
    }

    private fun directRequest(request: PlaybackRequest) = request.copy(sabr = false, download = true)

    private fun sabrBlocked(videoId: String): Boolean =
        synchronized(preloadLock) {
            failedSabr.entries.removeAll { it.value <= nowEpochMs() }
            videoId in failedSabr
        }

    private suspend fun recordSabrFailure(
        request: PlaybackRequest,
        error: Throwable,
    ) {
        val first =
            synchronized(preloadLock) {
                val first = (failedSabr[request.videoId] ?: 0) <= nowEpochMs()
                failedSabr[request.videoId] = nowEpochMs() + 60_000
                while (failedSabr.size > 64) failedSabr.remove(failedSabr.keys.first())
                first
            }
        cache.invalidate(request)
        if (first && error.findSabrProtocolException()?.kind == SabrFailureKind.ATTESTATION_REQUIRED) invalidateAttestation()
    }

    fun preload(stream: ExtractedStream) {
        if (preloadScope == null || sabrBlocked(stream.videoId)) return
        stream.sabrBootstrap?.let { adapters.preloadSabr(it, false) }
        stream.sabrVideoBootstrap?.let { adapters.preloadSabr(it, true) }
    }

    private fun enrichMetadata(
        stream: ExtractedStream,
        capture: PlayerCapture,
    ): ExtractedStream {
        val response = capture.response(stream.clientName) ?: return stream
        val formats = response.streamingData?.let { it.formats.orEmpty() + it.adaptiveFormats }.orEmpty()
        val bootstrap = stream.sabrBootstrap
        val audio =
            formats.firstOrNull {
                it.itag == stream.itag &&
                    (bootstrap == null || (it.audioTrack?.id == bootstrap.audioTrackId && it.isDrc == bootstrap.isDrc))
            }
        val video = formats.firstOrNull { it.itag == stream.videoItag }
        return stream.copy(
            bitrate = audio?.averageBitrate?.takeIf { it > 0 } ?: audio?.bitrate?.takeIf { it > 0 } ?: stream.bitrate,
            videoBitrate = video?.averageBitrate?.takeIf { it > 0 } ?: video?.bitrate?.takeIf { it > 0 } ?: stream.videoBitrate,
            sampleRate = audio?.audioSampleRate ?: stream.sampleRate,
            codecs = stream.codecs ?: audio?.mimeType?.let(::codecs),
        )
    }

    private suspend fun extractWithFallback(request: PlaybackRequest): ExtractedStream {
        val attempts =
            buildList {
                add(request)
                if (request.sabr) add(request.copy(sabr = false))
                if (request.wantVideo) add(request.copy(wantVideo = false, maxHeight = null, sabr = false))
            }.distinct()
        var firstFailure: Exception? = null
        for (attempt in attempts) {
            try {
                val hints =
                    ContentHints(
                        wantVideo = attempt.wantVideo,
                        maxVideoHeight = attempt.maxHeight ?: Int.MAX_VALUE,
                        sabrFirst = attempt.sabr,
                    ).withStreamCapabilities(
                        allowHls = !attempt.download,
                        allowSabr = attempt.sabr,
                        allowBoundedRange = true,
                    )
                val stream =
                    extractor.extract(
                        attempt.videoId,
                        hints,
                        audioQuality = attempt.quality,
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    )
                if (stream != null && stream.audioUrl.isNotBlank()) return stream
                throw IOException("Extractor returned no playable stream")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstFailure == null) {
                    firstFailure = error
                } else if (firstFailure !== error) {
                    firstFailure.addSuppressed(error)
                }
            }
        }
        throw IOException("No playable stream was found for ${request.videoId}", firstFailure)
    }

    private suspend fun discoverVariants(
        stream: ExtractedStream,
        capture: PlayerCapture,
    ): ResolvedPlayback {
        val variants = linkedMapOf<String, PlaybackVariant>()
        val formatLabels = capture.formatLabels(stream.clientName)

        fun audio(
            candidate: ExtractedStream,
            id: String = "a:${candidate.itag}",
            detail: String? = null,
            pendingFormat: Format? = null,
            playerUrl: String? = null,
        ) {
            variants[id] =
                PlaybackVariant(
                    id,
                    listOfNotNull(audioLabel(candidate), detail).joinToString(" • "),
                    candidate.bitrate ?: 0,
                    candidate.copy(videoUrl = null, sabrVideoBootstrap = null),
                    pendingFormat,
                    playerUrl,
                    stream.audioUrl,
                )
        }

        fun video(
            candidate: ExtractedStream,
            id: String = "v:${candidate.videoItag}",
            pendingFormat: Format? = null,
            playerUrl: String? = null,
        ) {
            if (candidate.videoUrl == null) return
            val label =
                listOfNotNull(
                    formatLabels[candidate.videoItag] ?: candidate.videoHeight?.let {
                        "${it}p"
                    },
                    candidate.videoCodecs,
                    candidate.videoBitrate?.let { "${it / 1000} kbps" },
                ).joinToString(" • ")
            variants[id] =
                PlaybackVariant(
                    id,
                    "Video • $label",
                    candidate.videoHeight ?: 0,
                    candidate,
                    pendingFormat,
                    playerUrl,
                    stream.videoUrl ?: stream.audioUrl,
                )
        }
        audio(stream)
        video(stream)
        stream.sabrBootstrap?.let { bootstrap ->
            val formats =
                capture
                    .response(stream.clientName)
                    ?.streamingData
                    ?.adaptiveFormats
                    .orEmpty()
            // SABR format identities need no URL deciphering: expose the returned codecs,
            // bitrates, languages and DRC variants directly from this playback session.
            formats.filter { it.itag > 0 && (it.lastModified?.toLongOrNull() ?: 0) > 0 }.forEach { format ->
                val identity = SabrFormatId(format.itag, checkNotNull(format.lastModified).toLong(), format.xtags)
                if (format.isAudio) {
                    val selected =
                        bootstrap.copy(
                            audioFormat = identity,
                            contentLengthBytes = format.contentLength,
                            mimeType = format.mimeType.substringBefore(';'),
                            audioTrackId = format.audioTrack?.id,
                            isDrc = format.isDrc,
                            durationMs = format.approxDurationMs?.toLongOrNull()?.takeIf { it > 0 } ?: bootstrap.durationMs,
                        )
                    if (format.itag == stream.itag) variants.remove("a:${stream.itag}")
                    audio(
                        stream.copy(
                            itag = format.itag,
                            mimeType = selected.mimeType,
                            codecs = codecs(format.mimeType),
                            bitrate = format.averageBitrate ?: format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLengthBytes = format.contentLength,
                            sabrBootstrap = selected,
                        ),
                        "a:${format.itag}:${format.audioTrack?.id.orEmpty()}:${format.isDrc}",
                        listOfNotNull(format.audioTrack?.displayName, "DRC".takeIf { format.isDrc })
                            .joinToString(" • ")
                            .takeIf { it.isNotBlank() },
                    )
                } else if ((format.height ?: 0) > 0) {
                    val selected =
                        bootstrap.copy(
                            selectedVideoFormat = identity,
                            selectedVideoWidth = format.width,
                            selectedVideoHeight = format.height,
                            selectedVideoContentLengthBytes = format.contentLength,
                            selectedVideoMimeType = format.mimeType.substringBefore(';'),
                            selectedVideoBitrate = format.averageBitrate ?: format.bitrate,
                        )
                    video(
                        stream.copy(
                            videoUrl = "sabr-video://${stream.videoId}",
                            videoItag = format.itag,
                            videoHeight = format.height,
                            videoWidth = format.width,
                            videoBitrate = selected.selectedVideoBitrate,
                            videoMimeType = selected.selectedVideoMimeType,
                            videoCodecs = codecs(format.mimeType),
                            videoContentLengthBytes = format.contentLength,
                            sabrVideoBootstrap = selected,
                        ),
                    )
                }
            }
            bootstrap.videoVariants.filter { "v:${it.format.itag}" !in variants }.forEach { variant ->
                val selected = bootstrap.withVideoVariant(variant)
                video(
                    stream.copy(
                        videoUrl = "sabr-video://${stream.videoId}",
                        videoItag = variant.format.itag,
                        videoHeight = variant.height,
                        videoWidth = variant.width,
                        videoBitrate = variant.bitrate,
                        videoMimeType = variant.mimeType,
                        videoCodecs = codecs(variant.mimeType),
                        videoContentLengthBytes = variant.contentLengthBytes,
                        sabrVideoBootstrap = selected,
                    ),
                )
            }
            return ResolvedPlayback(stream, variants.values.toList())
        }
        if (sourceType(stream.audioUrl, stream.mimeType) !=
            Streamable.SourceType.Progressive
        ) {
            return ResolvedPlayback(stream, variants.values.toList())
        }
        val data = capture.response(stream.clientName)?.streamingData ?: return ResolvedPlayback(stream, variants.values.toList())
        val formats = (data.formats.orEmpty() + data.adaptiveFormats).distinctBy { Triple(it.itag, it.audioTrack?.id, it.isDrc) }
        val playerUrl = capture.playerUrl ?: capture.config?.playerUrl
        val pending =
            formats.filter { format ->
                format.url?.let { runCatching { Url(it).parameters["n"] != null }.getOrDefault(true) }
                    ?: true
            }
        // Describe all formats now; decipher only the rendition the user selects.
        // The extractor's selected default is already playable.
        for (format in formats) {
            val candidate = format
            val lazy = candidate in pending
            if (lazy && playerUrl == null) continue
            val url =
                if (lazy) {
                    if (candidate.isAudio) stream.audioUrl else stream.videoUrl ?: "pending-video://${stream.videoId}"
                } else {
                    candidate.url?.let { playbackUrl(it, if (candidate.isAudio) stream.audioUrl else stream.videoUrl ?: stream.audioUrl) }
                        ?: continue
                }
            if ((stream.requireBoundedRange || stream.useRangeChunks) && candidate.contentLength == null) continue
            if (candidate.isAudio) {
                val id = "a:${candidate.itag}:${candidate.audioTrack?.id.orEmpty()}:${candidate.isDrc}"
                // Replace the selected format's shorthand entry, keeping alternate language/DRC formats distinct.
                if (candidate.itag == stream.itag) variants.remove("a:${stream.itag}")
                audio(
                    stream.copy(
                        audioUrl = url,
                        itag = candidate.itag,
                        mimeType = candidate.mimeType.substringBefore(';'),
                        codecs = codecs(candidate.mimeType),
                        bitrate = candidate.averageBitrate ?: candidate.bitrate,
                        sampleRate = candidate.audioSampleRate,
                        contentLengthBytes = candidate.contentLength,
                    ),
                    id,
                    listOfNotNull(
                        candidate.audioTrack?.displayName,
                        "DRC".takeIf { candidate.isDrc },
                    ).joinToString(" • ").takeIf { it.isNotBlank() },
                    candidate.takeIf { lazy },
                    playerUrl,
                )
            } else {
                video(
                    stream.copy(
                        videoUrl = url,
                        videoItag = candidate.itag,
                        videoHeight = candidate.height,
                        videoWidth = candidate.width,
                        videoMimeType = candidate.mimeType.substringBefore(';'),
                        videoCodecs = codecs(candidate.mimeType),
                        videoBitrate = candidate.averageBitrate ?: candidate.bitrate,
                        videoContentLengthBytes = candidate.contentLength,
                    ),
                    pendingFormat = candidate.takeIf { lazy },
                    playerUrl = playerUrl,
                )
            }
        }
        return ResolvedPlayback(stream, variants.values.toList())
    }

    suspend fun media(
        request: PlaybackRequest,
        variantId: String,
    ): Streamable.Media.Server {
        val playback = resolve(request)
        val selected = select(request, playback, variantId)
        if (!request.download) preload(selected)
        val videoUrl = selected.videoUrl?.takeIf { it.isNotBlank() }
        val audio = source(selected, false, request, variantId)
        // HLS and muxed formats contain both tracks; adaptive video requires an explicit audio source.
        if (videoUrl == null || videoUrl == selected.audioUrl) return Streamable.Media.Server(listOf(audio), false)
        return Streamable.Media.Server(listOf(audio, source(selected, true, request, variantId)), true)
    }

    private suspend fun select(
        request: PlaybackRequest,
        playback: ResolvedPlayback,
        variantId: String,
    ): ExtractedStream {
        val variant =
            if (variantId == "auto") {
                playback.variants.firstOrNull { it.stream === playback.default }
            } else {
                playback.variants.firstOrNull { it.id == variantId }
            }
        if (variant ==
            null
        ) {
            return if (variantId.startsWith(
                    "a:",
                )
            ) {
                playback.default.copy(videoUrl = null, sabrVideoBootstrap = null)
            } else {
                playback.default
            }
        }
        val format = variant.pendingFormat ?: return variant.stream
        return selectedCache.getOrLoad(request to variant.id) {
            val processed =
                withTimeoutOrNull(5_000) {
                    cipher.processFormats(checkNotNull(variant.playerUrl), listOf(format))
                }?.firstOrNull() ?: throw IOException("Could not resolve the selected quality")
            val url =
                processed.url?.let { playbackUrl(it, checkNotNull(variant.proofUrl)) }
                    ?: throw IOException("Selected quality has no playable URL")
            val selected = if (format.isAudio) variant.stream.copy(audioUrl = url) else variant.stream.copy(videoUrl = url)
            selected to cacheTtl(ResolvedPlayback(selected, emptyList()))
        }
    }

    private fun source(
        stream: ExtractedStream,
        video: Boolean,
        request: PlaybackRequest,
        variantId: String,
    ): Streamable.Source {
        val url = if (video) checkNotNull(stream.videoUrl) else stream.audioUrl
        val mime = if (video) stream.videoMimeType else stream.mimeType
        val title =
            if (video) {
                listOfNotNull(
                    "${stream.videoHeight ?: 0}p",
                    stream.videoBitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
                ).joinToString(" • ")
            } else {
                "Audio • ${audioLabel(stream)}"
            }
        val bitrate = (if (video) stream.videoBitrate else stream.bitrate) ?: 0
        val bootstrap = if (video) stream.sabrVideoBootstrap else stream.sabrBootstrap
        if (bootstrap != null) {
            return Streamable.Source.Raw(
                Streamable.InputProvider { position, length -> openRefreshing(request, variantId, video, position, length) },
                id = "$url:${if (video) stream.videoItag else stream.itag}:${stream.playbackTracking?.clientPlaybackNonce}",
                quality = bitrate,
                title = title,
                isVideo = video,
            )
        }
        val boundedProgressive = sourceType(url, mime) == Streamable.SourceType.Progressive
        if (stream.requireBoundedRange || stream.useRangeChunks || boundedProgressive) {
            val total = contentLength(stream, video)
            if (total != null) {
                return Streamable.Source.Raw(
                    Streamable.InputProvider { position, length -> openRefreshing(request, variantId, video, position, length) },
                    id = url,
                    quality = bitrate,
                    title = title,
                    isVideo = video,
                )
            }
            check(!stream.requireBoundedRange) { "Bounded stream has no content length" }
        }
        return Streamable.Source.Http(
            NetworkRequest(url, stream.headers),
            type = sourceType(url, mime),
            quality = bitrate,
            title = title,
            isVideo = video || stream.videoUrl == stream.audioUrl,
            isLive = stream.mediaMetadata?.isLive ?: false,
        )
    }

    private suspend fun openRefreshing(
        request: PlaybackRequest,
        variantId: String,
        video: Boolean,
        position: Long,
        length: Long,
    ): Pair<InputStream, Long> {
        var selected: ExtractedStream? = null
        try {
            val stream = select(request, resolve(request), variantId)
            selected = stream
            val opened = open(stream, video, position, length, request.download)
            val bootstrap = if (video) stream.sabrVideoBootstrap else stream.sabrBootstrap
            if (bootstrap == null) return opened
            return RecoveringInputStream(opened.first, opened.second) { error ->
                // A SABR denial can arrive after initialization or several media segments.
                recordSabrFailure(request, error)
                val fallback = directRequest(request)
                val playback = resolve(fallback)
                val matching =
                    matchingDirectVariant(playback, stream, video)
                        ?: throw IOException("No byte-compatible direct format is available")
                val replacement = select(fallback, playback, matching.id)
                open(replacement, video, position, length, request.download)
            } to opened.second
        } catch (error: CancellationException) {
            throw error
        } catch (first: Exception) {
            // Refresh once at open, before any bytes reach the player. SABR failures drop to direct transport.
            cache.invalidate(request)
            selectedCache.invalidate(request to variantId)
            val bootstrap = if (video) selected?.sabrVideoBootstrap else selected?.sabrBootstrap
            if (bootstrap != null) recordSabrFailure(request, first)
            val fallback = directRequest(request)
            cache.invalidate(fallback)
            try {
                return open(select(fallback, resolve(fallback), variantId), video, position, length, request.download)
            } catch (error: CancellationException) {
                throw error
            } catch (second: Exception) {
                first.addSuppressed(second)
                throw IOException("Could not open playback stream", first)
            }
        }
    }

    private fun matchingDirectVariant(
        playback: ResolvedPlayback,
        original: ExtractedStream,
        video: Boolean,
    ): PlaybackVariant? {
        val itag = if (video) original.videoItag else original.itag
        val mime = if (video) original.videoMimeType else original.mimeType
        val bootstrap = original.sabrBootstrap
        val audioId = "a:$itag:${bootstrap?.audioTrackId.orEmpty()}:${bootstrap?.isDrc ?: false}"
        return playback.variants.firstOrNull { variant ->
            val candidate = variant.stream
            val sameIdentity =
                if (video) {
                    variant.id == "v:$itag"
                } else {
                    variant.id == audioId ||
                        (variant.id == "a:$itag" && bootstrap?.audioTrackId == null && bootstrap?.isDrc != true)
                }
            sameIdentity &&
                (if (video) candidate.sabrVideoBootstrap else candidate.sabrBootstrap) == null &&
                (if (video) candidate.videoMimeType else candidate.mimeType) == mime &&
                contentLength(candidate, video) == contentLength(original, video)
        }
    }

    private suspend fun open(
        stream: ExtractedStream,
        video: Boolean,
        position: Long,
        length: Long,
        download: Boolean,
    ): Pair<InputStream, Long> {
        val bootstrap = if (video) stream.sabrVideoBootstrap else stream.sabrBootstrap
        if (bootstrap != null) return adapters.sabr(bootstrap, video, position, length)
        val url = if (video) checkNotNull(stream.videoUrl) else stream.audioUrl
        val total = checkNotNull(contentLength(stream, video)) { "Stream has no byte length" }
        return adapters.ranged(url, stream.headers, total, stream.rangeChunkSizeBytes, position, length, download)
    }

    private fun contentLength(
        stream: ExtractedStream,
        video: Boolean,
    ): Long? {
        val declared = if (video) stream.videoContentLengthBytes else stream.contentLengthBytes
        if (declared != null && declared > 0) return declared
        val url = if (video) stream.videoUrl else stream.audioUrl
        return url?.let { runCatching { Url(it).parameters["clen"]?.toLongOrNull()?.takeIf { size -> size > 0 } }.getOrNull() }
    }

    private fun cacheTtl(playback: ResolvedPlayback): Long {
        val streams = listOf(playback.default) + playback.variants.map { it.stream }
        if (streams.any { it.mediaMetadata?.isLive == true }) return 0
        val expiry =
            streams
                .flatMap { stream ->
                    listOfNotNull(stream.expiresAt?.toEpochMilliseconds(), urlExpiry(stream.audioUrl), stream.videoUrl?.let(::urlExpiry))
                }.minOrNull()
        return if (expiry == null) 60_000 else (expiry - nowEpochMs() - 30_000).coerceIn(0, 5 * 60_000)
    }
}

internal fun sourceType(
    url: String,
    mime: String?,
): Streamable.SourceType {
    val hls = mime?.contains("mpegurl", true) == true || url.contains(".m3u8", true) || url.contains("/manifest/hls_")
    val dash = mime?.contains("dash+xml", true) == true || url.contains(".mpd", true)
    return when {
        hls -> Streamable.SourceType.HLS
        dash -> Streamable.SourceType.DASH
        else -> Streamable.SourceType.Progressive
    }
}

private fun codecs(mime: String): String? = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1)

private fun audioLabel(stream: ExtractedStream): String {
    val codec =
        when {
            stream.codecs?.contains("opus", true) == true -> "Opus"
            stream.codecs?.contains("mp4a", true) == true -> "AAC"
            else -> stream.codecs ?: "Audio"
        }
    return listOfNotNull(
        codec,
        stream.bitrate?.let {
            "${it / 1000} kbps"
        },
        stream.sampleRate?.let { "${it / 1000.0} kHz" },
    ).joinToString(" \u2022 ")
}

private fun urlExpiry(url: String): Long? = runCatching { Url(url).parameters["expire"]?.toLongOrNull()?.times(1000) }.getOrNull()

/** Keep the selected client's proof and playback nonce when exposing another rendition from its response. */
private fun playbackUrl(
    url: String,
    selectedUrl: String,
): String? =
    runCatching {
        val parsed = Url(url)
        if (parsed.protocol.name != "https" || parsed.port != 443 || parsed.encodedPath != "/videoplayback" ||
            !parsed.host.endsWith(".googlevideo.com") || parsed.user != null || parsed.password != null
        ) {
            return null
        }
        URLBuilder(parsed)
            .apply {
                val selected = Url(selectedUrl)
                listOf("cpn", "pot").forEach { key -> selected.parameters[key]?.let { parameters[key] = it } }
            }.buildString()
    }.getOrNull()
