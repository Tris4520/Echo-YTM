package dev.brahmkshatriya.echo.extension.playback

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.ExtractionCipherService
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.SabrProtocolException
import dev.brahmkshatriya.echo.common.models.Streamable
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.time.Instant

class PlaybackTest {
    @Test fun sabrAttestationRecoversAtOpenAndAfterBytesForAudioAndVideo() =
        runBlocking {
            for (video in listOf(false, true)) {
                for (partial in listOf(false, true)) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val http =
                        HttpClient(
                            MockEngine {
                                respond("abcdef", HttpStatusCode.PartialContent, headersOf("Content-Range", "bytes 0-5/6"))
                            },
                        )
                    val bootstrap =
                        SabrBootstrap(
                            videoId = request.videoId,
                            serverAbrStreamingUrl = stream().audioUrl,
                            videoPlaybackUstreamerConfig = byteArrayOf(1),
                            clientName = 28,
                            clientVersion = "test",
                            audioFormat = SabrFormatId(251, 1),
                            discardVideoFormat = SabrFormatId(160, 1),
                            discardVideoHeight = 144,
                            selectedVideoFormat = SabrFormatId(137, 1),
                            selectedVideoContentLengthBytes = 6,
                            durationMs = 120_000,
                            contentLengthBytes = 6,
                            mimeType = "audio/webm",
                        )
                    val attempts = mutableListOf<Boolean>()
                    var invalidations = 0
                    var sabrOpens = 0
                    val resolver =
                        StreamResolver(
                            extractor { hints, _ ->
                                attempts += hints.allowSabr
                                val direct = stream(video)
                                if (!hints.allowSabr) {
                                    direct
                                } else {
                                    direct.copy(
                                        sabrBootstrap = bootstrap,
                                        sabrVideoBootstrap = bootstrap.takeIf { video },
                                    )
                                }
                            },
                            cipher,
                            StreamAdapters(http, scope, sabrBytes = { _, _ ->
                                flow {
                                    sabrOpens++
                                    if (partial) emit("abc".toByteArray())
                                    throw SabrProtocolException("Attestation required", SabrFailureKind.ATTESTATION_REQUIRED)
                                }
                            }),
                            invalidateAttestation = { invalidations++ },
                        )
                    try {
                        val playback = request.copy(sabr = true, wantVideo = video)
                        val source = resolver.media(playback, "auto").sources.first { it.isVideo == video } as Streamable.Source.Raw
                        repeat(2) {
                            val (bytes, size) = source.streamProvider!!.provide(0, -1)
                            assertEquals(6L, size)
                            bytes.use { assertEquals("abcdef", it.readBytes().decodeToString()) }
                        }
                        assertEquals(listOf(true, false), attempts)
                        assertEquals(1, invalidations)
                        assertEquals(1, sabrOpens)
                    } finally {
                        scope.cancel()
                        http.close()
                    }
                }
            }
        }

    @Test fun recoveryRefusesDifferentBytesAndClosingCancelsRefresh() =
        runBlocking {
            val broken =
                object : java.io.ByteArrayInputStream("abc".toByteArray()) {
                    override fun read(
                        buffer: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        if (available() == 0) throw IOException("SABR failed")
                        return super.read(buffer, off, len)
                    }
                }
            RecoveringInputStream(broken, 6) { "xyzdef".byteInputStream() to 6L }.use { bytes ->
                val buffer = ByteArray(3)
                assertEquals(3, bytes.read(buffer))
                try {
                    bytes.read(buffer)
                    fail("A different container must not be spliced into playback")
                } catch (error: IOException) {
                    assertEquals("SABR failed", error.cause!!.message)
                    assertTrue(
                        error.cause!!
                            .suppressed
                            .single()
                            .message!!
                            .contains("differs"),
                    )
                }
            }
            val entered = CompletableDeferred<Unit>()
            val bytes =
                RecoveringInputStream(
                    object : java.io.ByteArrayInputStream(byteArrayOf()) {
                        override fun read(
                            buffer: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int = throw IOException("SABR failed")
                    },
                    6,
                ) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            val reading =
                async(Dispatchers.IO) {
                    try {
                        bytes.read()
                    } catch (_: IOException) {
                        -1
                    }
                }
            entered.await()
            bytes.close()
            assertEquals(-1, withTimeout(1000) { reading.await() })
        }

    @Test fun automaticAndSourceLabelsUseReportedAverageBitrate() =
        runTest {
            val http = HttpClient(MockEngine { error("No media network expected") })
            try {
                val resolver =
                    StreamResolver(
                        extractor { _, _ ->
                            currentCoroutineContext()[PlayerCapture]!!.record(
                                "ANDROID_VR",
                                PlayerResponse(
                                    PlayerResponse.PlayabilityStatus("OK"),
                                    PlayerResponse.StreamingData(
                                        adaptiveFormats =
                                            listOf(
                                                Format(
                                                    itag = 251,
                                                    mimeType = "audio/webm; codecs=\"opus\"",
                                                    averageBitrate = 128_000,
                                                ),
                                            ),
                                    ),
                                ),
                            )
                            stream().copy(bitrate = null)
                        },
                        cipher,
                        StreamAdapters(http, backgroundScope),
                    )
                val playback = resolver.resolve(request)
                assertEquals(128_000, playback.default.bitrate)
                assertTrue(
                    playback
                        .streamables(request.videoId)
                        .first()
                        .title!!
                        .contains("128 kbps"),
                )
                val source = resolver.media(request, "auto").sources.single()
                assertTrue(source.title!!.contains("128 kbps"))
                assertEquals(128_000, source.quality)
            } finally {
                http.close()
            }
        }

    @Test fun qualityMenuHasFourDistinctChoicesForAudioOrVideo() {
        for (video in listOf(false, true)) {
            val streams =
                (1..9).map { level ->
                    val extracted = stream(video).copy(bitrate = level * 16000, videoHeight = level * 240)
                    PlaybackVariant("${if (video) "v" else "a"}:$level", "detail", if (video) level * 240 else level * 16000, extracted)
                }
            val options = ResolvedPlayback(stream(video), streams + streams).streamables(request.videoId)
            assertEquals(4, options.size)
            assertEquals(listOf("Automatic", "Low", "Medium", "High"), options.map { it.title!!.substringBefore(" • ") })
            assertEquals(4, options.map { it.id }.distinct().size)
        }
    }

    @Test fun foregroundReusesReadyPrefetchAndCancelsDifferentSpeculation() =
        runTest {
            var calls = 0
            var wait = false
            var cancelled = false
            val http = HttpClient(MockEngine { error("No media network expected") })
            val player =
                object : StreamExtractor {
                    override suspend fun prewarm() = Unit

                    override suspend fun extract(
                        videoId: String,
                        hints: ContentHints,
                        excludedClients: Set<String>,
                        audioQuality: AudioQuality,
                        clientPlaybackNonce: String,
                    ): ExtractedStream {
                        calls++
                        if (wait && videoId == request.videoId) {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled = true
                            }
                        }
                        return stream().copy(videoId = videoId)
                    }
                }
            try {
                val resolver = StreamResolver(player, cipher, StreamAdapters(http, backgroundScope), preloadScope = backgroundScope)
                resolver.prefetch(request)
                runCurrent()
                resolver.resolve(request)
                assertEquals(1, calls)
                resolver.clear()
                wait = true
                resolver.prefetch(request)
                runCurrent()
                val other = request.copy(videoId = "abcdefghijk")
                assertEquals(other.videoId, resolver.resolve(other).default.videoId)
                assertTrue(cancelled)
                resolver.clear()
            } finally {
                http.close()
            }
        }

    @Test fun playbackReleasesStartupChunkBeforeWaitingForRemainingMedia() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val http =
                HttpClient(
                    MockEngine { req ->
                        assertNull(req.headers["Range"])
                        if (req.url.parameters["range"] == "0-65535") respond(ByteArray(65536) { 7 }) else awaitCancellation()
                    },
                )
            try {
                val (input, size) =
                    withTimeout(1000) {
                        StreamAdapters(http, scope).ranged(
                            "https://rr1.googlevideo.com/videoplayback",
                            emptyMap(),
                            2 * 1024 * 1024,
                            1024 * 1024,
                            0,
                            -1,
                            download = false,
                        )
                    }
                input.use {
                    assertEquals(2 * 1024 * 1024L, size)
                    assertEquals(7, it.read())
                }
            } finally {
                scope.cancel()
                http.close()
            }
        }

    @Test fun qualityMenuDoesNotDecipherAlternateFormatsUntilSelected() =
        runTest {
            var cipherCalls = 0
            val video =
                Format(
                    itag = 137,
                    url = "https://rr1.googlevideo.com/videoplayback?itag=137&n=raw",
                    mimeType = "video/mp4",
                    width = 1920,
                    height = 1080,
                    contentLength = 6,
                )
            val solver =
                object : ExtractionCipherService by cipher {
                    override suspend fun processFormats(
                        playerUrl: String,
                        formats: List<Format>,
                    ): List<Format> {
                        cipherCalls++
                        assertEquals(listOf(137), formats.map { it.itag })
                        return formats.map { it.copy(url = it.url!!.replace("n=raw", "n=solved")) }
                    }
                }
            val http = HttpClient(MockEngine { error("No network expected") })
            try {
                val resolver =
                    StreamResolver(
                        extractor { _, _ ->
                            val capture = currentCoroutineContext()[PlayerCapture]!!
                            capture.playerUrl = "https://www.youtube.com/player.js"
                            capture.record(
                                "ANDROID_VR",
                                PlayerResponse(
                                    PlayerResponse.PlayabilityStatus("OK"),
                                    PlayerResponse.StreamingData(adaptiveFormats = listOf(video)),
                                ),
                            )
                            stream()
                        },
                        solver,
                        StreamAdapters(http, backgroundScope),
                    )
                assertTrue(resolver.resolve(request).variants.any { it.id == "v:137" })
                resolver.media(request, "auto")
                assertEquals(0, cipherCalls)
                val media = resolver.media(request, "v:137")
                assertTrue((media.sources.last() as Streamable.Source.Raw).id.contains("n=solved"))
                resolver.media(request, "v:137")
                assertEquals(1, cipherCalls)
            } finally {
                http.close()
            }
        }

    @Test fun sabrDownloadsKeepTransportAndExposeAudioAndVideoFormatsWithoutCipherWork() =
        runTest {
            val bootstrap =
                com.metrolist.innertubex.sabr.SabrBootstrap(
                    videoId = request.videoId,
                    serverAbrStreamingUrl = "https://rr1.googlevideo.com/videoplayback",
                    videoPlaybackUstreamerConfig = byteArrayOf(1),
                    clientName = 28,
                    clientVersion = "test",
                    audioFormat =
                        com.metrolist.innertubex.sabr
                            .SabrFormatId(251, 1),
                    discardVideoFormat =
                        com.metrolist.innertubex.sabr
                            .SabrFormatId(160, 1),
                    discardVideoHeight = 144,
                    durationMs = 120_000,
                    contentLengthBytes = 6,
                    mimeType = "audio/webm",
                )
            val formats =
                listOf(
                    Format(itag = 140, mimeType = "audio/mp4", bitrate = 128_000, lastModified = "1", contentLength = 6),
                    Format(itag = 251, mimeType = "audio/webm", bitrate = 160_000, lastModified = "1", contentLength = 6),
                    Format(itag = 137, mimeType = "video/mp4", width = 1920, height = 1080, lastModified = "1", contentLength = 6),
                )
            val http = HttpClient(MockEngine { error("No network expected") })
            try {
                val resolver =
                    StreamResolver(
                        extractor { hints, _ ->
                            assertTrue(hints.sabrFirst && hints.allowSabr)
                            currentCoroutineContext()[PlayerCapture]!!.record(
                                "ANDROID_VR",
                                PlayerResponse(
                                    PlayerResponse.PlayabilityStatus("OK"),
                                    PlayerResponse.StreamingData(adaptiveFormats = formats),
                                ),
                            )
                            stream().copy(audioUrl = "sabr://${request.videoId}", sabrBootstrap = bootstrap)
                        },
                        cipher,
                        StreamAdapters(http, backgroundScope),
                    )
                val download = request.copy(sabr = true, download = true)
                val playback = resolver.resolve(download)
                assertEquals(2, playback.variants.count { it.id.startsWith("a:") })
                assertTrue(resolver.media(download, "a:140::false").sources.single() is Streamable.Source.Raw)
                assertTrue(resolver.media(download, "v:137").merged)
            } finally {
                http.close()
            }
        }

    @Test fun downloadResumeAcceptsRelativeContentRange() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val http =
                HttpClient(
                    MockEngine { req ->
                        assertEquals("2-4", req.url.parameters["range"])
                        assertNull(req.headers["Range"])
                        respond("cde", HttpStatusCode.PartialContent, headersOf("Content-Range", "bytes 0-2/3"))
                    },
                )
            try {
                val (input, size) =
                    StreamAdapters(
                        http,
                        scope,
                    ).ranged("https://rr1.googlevideo.com/videoplayback", emptyMap(), 6, 65536, 2, 3, download = true)
                assertEquals(3L, size)
                input.use { assertEquals("cde", it.readBytes().decodeToString()) }
            } finally {
                scope.cancel()
                http.close()
            }
        }

    @Test fun downloadRangePreservesProofAndReplacesOldRangeOnResume() {
        val url =
            io.ktor.http.Url(
                downloadRangeUrl("https://rr1.googlevideo.com/videoplayback?n=solved&pot=proof&cpn=nonce&range=0-99", 100, 199),
            )
        assertEquals("100-199", url.parameters["range"])
        assertEquals("solved", url.parameters["n"])
        assertEquals("proof", url.parameters["pot"])
        assertEquals("nonce", url.parameters["cpn"])
    }

    @Test fun downloadsUseQueryRangesAndValidateHttp200ChunkBytes() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val http =
                HttpClient(
                    MockEngine { req ->
                        assertEquals("2-4", req.url.parameters["range"])
                        assertNull(req.headers["Range"])
                        respond("cde", HttpStatusCode.OK)
                    },
                )
            try {
                val (input, size) =
                    StreamAdapters(
                        http,
                        scope,
                    ).ranged("https://rr1.googlevideo.com/videoplayback", emptyMap(), 6, 65536, 2, 3, download = true)
                assertEquals(3L, size)
                input.use { assertEquals("cde", it.readBytes().decodeToString()) }
            } finally {
                scope.cancel()
                http.close()
            }
        }

    private val request = PlaybackRequest("dQw4w9WgXcQ", AudioQuality.HIGH, false, 1080, false, false)
    private val cipher =
        object : ExtractionCipherService {
            override suspend fun initialize() = Unit

            override suspend fun preloadPlayerCode(playerUrl: String) = Unit

            override suspend fun prewarmEjs() = Unit

            override suspend fun processFormats(
                playerUrl: String,
                formats: List<Format>,
            ) = formats
        }

    private fun stream(
        video: Boolean = false,
        bounded: Boolean = false,
        expiry: Long = System.currentTimeMillis() + 300_000,
    ) = ExtractedStream(
        videoId = request.videoId,
        audioUrl = "https://rr1.googlevideo.com/videoplayback?itag=251&cpn=nonce&pot=proof",
        headers = emptyMap(),
        loudnessDb = null,
        expiresAt = Instant.fromEpochMilliseconds(expiry),
        contentLengthBytes = 6,
        itag = 251,
        mimeType = "audio/webm",
        codecs = "opus",
        bitrate = 160_000,
        sampleRate = 48000,
        clientName = "ANDROID_VR",
        profileId = "test",
        requireBoundedRange = bounded,
        rangeChunkSizeBytes = 65536,
        videoUrl = "https://rr1.googlevideo.com/videoplayback?itag=137".takeIf { video },
        videoHeight = 1080.takeIf { video },
        videoItag = 137.takeIf { video },
        videoContentLengthBytes = 6.takeIf { video }?.toLong(),
    )

    private fun extractor(block: suspend (ContentHints, String) -> ExtractedStream?) =
        object : StreamExtractor {
            override suspend fun prewarm() = Unit

            override suspend fun extract(
                videoId: String,
                hints: ContentHints,
                excludedClients: Set<String>,
                audioQuality: AudioQuality,
                clientPlaybackNonce: String,
            ) = block(hints, clientPlaybackNonce)
        }

    @Test fun captureKeepsResponseReadableAndIsolatesConcurrentRequests() =
        runTest {
            val http =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"mimeType":"audio/webm"}]}}""",
                        )
                    },
                ) {
                    install(CapturePlayer)
                }
            try {
                val captures = List(2) { PlayerCapture() }
                val jobs =
                    captures.mapIndexed { index, capture ->
                        async(capture) {
                            val response =
                                http.post("https://www.youtube.com/youtubei/v1/player") {
                                    setBody(
                                        TextContent(
                                            """{"context":{"client":{"clientName":"CLIENT_$index"}}}""",
                                            ContentType.Application.Json,
                                        ),
                                    )
                                }
                            assertEquals(
                                "OK",
                                Json { ignoreUnknownKeys = true }
                                    .decodeFromString<PlayerResponse>(
                                        response.bodyAsText(),
                                    ).playabilityStatus.status,
                            )
                        }
                    }
                jobs.forEach { it.await() }
                assertEquals(
                    251,
                    captures[0]
                        .response("CLIENT_0")!!
                        .streamingData!!
                        .adaptiveFormats
                        .single()
                        .itag,
                )
                assertNull(captures[0].response("CLIENT_1"))
                assertNull(captures[1].response("CLIENT_0"))
            } finally {
                http.close()
            }
        }

    @Test fun videoFallbackDropsVideoAndUsesFreshNonce() =
        runTest {
            val attempts = mutableListOf<Pair<Boolean, String>>()
            val http = HttpClient(MockEngine { error("No network expected") })
            val resolver =
                StreamResolver(
                    extractor { hints, nonce ->
                        attempts += hints.wantVideo to nonce
                        if (hints.wantVideo) throw IOException("No video")
                        stream()
                    },
                    cipher,
                    StreamAdapters(http, backgroundScope),
                )
            try {
                resolver.resolve(request.copy(wantVideo = true))
                assertEquals(listOf(true, false), attempts.map { it.first })
                assertNotEquals(attempts[0].second, attempts[1].second)
            } finally {
                http.close()
            }
        }

    @Test fun cancellationIsNotRetriedAndFirstErrorIsRetained() =
        runTest {
            val http = HttpClient(MockEngine { error("No network expected") })
            var calls = 0
            try {
                val cancelled =
                    StreamResolver(
                        extractor {
                            _,
                            _,
                            ->
                            calls++
                            throw CancellationException("cancelled")
                        },
                        cipher,
                        StreamAdapters(http, backgroundScope),
                    )
                try {
                    cancelled.resolve(request.copy(wantVideo = true))
                    fail()
                } catch (_: CancellationException) {
                }
                assertEquals(1, calls)
                val first = IOException("original player failure")
                val failed =
                    StreamResolver(
                        extractor {
                            hints,
                            _,
                            ->
                            throw if (hints.wantVideo) first else IOException("audio failure")
                        },
                        cipher,
                        StreamAdapters(http, backgroundScope),
                    )
                try {
                    failed.resolve(request.copy(wantVideo = true))
                    fail()
                } catch (error: IOException) {
                    assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === first })
                    assertEquals("audio failure", first.suppressed.single().message)
                }
            } finally {
                http.close()
            }
        }

    @Test fun allResponseQualitiesRemainResolvableAndVideoAlwaysHasAudio() =
        runTest {
            val formats =
                listOf(
                    Format(
                        itag = 140,
                        url = "https://rr1.googlevideo.com/videoplayback?itag=140",
                        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                        bitrate = 128_000,
                        contentLength = 6,
                    ),
                    Format(
                        itag = 251,
                        url = stream().audioUrl,
                        mimeType = "audio/webm; codecs=\"opus\"",
                        bitrate = 160_000,
                        contentLength = 6,
                    ),
                    Format(
                        itag = 137,
                        url = "https://rr1.googlevideo.com/videoplayback?itag=137",
                        mimeType = "video/mp4",
                        width = 1920,
                        height = 1080,
                        contentLength = 6,
                    ),
                    Format(
                        itag = 402,
                        url = "https://rr1.googlevideo.com/videoplayback?itag=402",
                        mimeType = "video/webm",
                        width = 7680,
                        height = 4320,
                        contentLength = 6,
                    ),
                )
            var calls = 0
            val http = HttpClient(MockEngine { error("No network expected") })
            val resolver =
                StreamResolver(
                    extractor { _, _ ->
                        calls++
                        currentCoroutineContext()[PlayerCapture]!!.record(
                            "ANDROID_VR",
                            PlayerResponse(PlayerResponse.PlayabilityStatus("OK"), PlayerResponse.StreamingData(adaptiveFormats = formats)),
                        )
                        stream()
                    },
                    cipher,
                    StreamAdapters(http, backgroundScope),
                )
            try {
                val playback = resolver.resolve(request)
                assertEquals(2, playback.variants.count { it.id.startsWith("a:") })
                val videoPlayback = playback.copy(default = playback.variants.first { it.id == "v:402" }.stream)
                assertTrue(videoPlayback.streamables(request.videoId).any { it.title?.contains("4320p") == true })
                val media = resolver.media(request, "v:402")
                assertTrue(media.merged)
                assertEquals(listOf(false, true), media.sources.map { it.isVideo })
                assertEquals(1, calls)
                val video = media.sources.last() as Streamable.Source.Raw
                assertTrue(video.id.contains("cpn=nonce"))
                assertTrue(video.id.contains("pot=proof"))
            } finally {
                http.close()
            }
        }

    @Test fun cacheSharesWorkExpiresAndRejectsOldSessionResults() =
        runTest {
            var now = 0L
            var loads = 0
            val cache = ResolutionCache<String, Int>(2) { now }
            val ready = CompletableDeferred<Unit>()

            suspend fun load(): Pair<Int, Long> {
                loads++
                ready.await()
                return loads to 100L
            }
            val first = async { cache.getOrLoad("track", ::load) }
            val second = async { cache.getOrLoad("track", ::load) }
            runCurrent()
            assertEquals(1, loads)
            ready.complete(Unit)
            assertEquals(first.await(), second.await())
            assertEquals(1, cache.getOrLoad("track", ::load))
            now = 101
            assertEquals(2, cache.getOrLoad("track", ::load))
            val gate = CompletableDeferred<Unit>()
            val old =
                async {
                    cache.getOrLoad("old") {
                        gate.await()
                        9 to 100L
                    }
                }
            runCurrent()
            cache.clear()
            gate.complete(Unit)
            try {
                old.await()
                fail()
            } catch (_: CancellationException) {
            }
            assertEquals(3, cache.getOrLoad("track", ::load))
        }

    @Test fun expiredUrlsAreResolvedAgain() =
        runTest {
            var calls = 0
            val http = HttpClient(MockEngine { error("No network expected") })
            val resolver =
                StreamResolver(
                    extractor {
                        _,
                        _,
                        ->
                        calls++
                        stream(expiry = 10_000)
                    },
                    cipher,
                    StreamAdapters(http, backgroundScope),
                    nowEpochMs = { 20_000 },
                )
            try {
                resolver.resolve(request)
                resolver.resolve(request)
                assertEquals(2, calls)
            } finally {
                http.close()
            }
        }

    @Test fun byteBridgeSeeksExactlyAndPropagatesProducerFailure() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                FlowInputStream(scope, flowOf("abc".toByteArray(), "def".toByteArray()), 2, 3).use {
                    assertEquals("cde", it.readBytes().decodeToString())
                }
                val failure =
                    FlowInputStream(
                        scope,
                        flow {
                            emit("x".toByteArray())
                            throw IOException("network died")
                        },
                    )
                failure.use {
                    assertEquals('x'.code, it.read())
                    try {
                        it.read()
                        fail()
                    } catch (error: IOException) {
                        assertEquals("network died", error.cause!!.message)
                    }
                }
                val finished = CompletableDeferred<Unit>()
                val endless =
                    FlowInputStream(
                        scope,
                        flow {
                            try {
                                while (true) emit(ByteArray(8))
                            } finally {
                                finished.complete(Unit)
                            }
                        },
                    )
                endless.prepare()
                endless.close()
                withTimeout(1000) { finished.await() }
            } finally {
                scope.cancel()
            }
        }

    @Test fun boundedStreamRetriesTransientErrorsAndHonorsByteSeek() =
        runBlocking {
            var calls = 0
            val http =
                HttpClient(
                    MockEngine { req ->
                        calls++
                        assertEquals("2-4", req.url.parameters["range"])
                        assertNull(req.headers["Range"])
                        if (calls == 1) {
                            respond("", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond("cde", HttpStatusCode.PartialContent, headersOf("Content-Range", "bytes 2-4/6"))
                        }
                    },
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val (bytes, size) = StreamAdapters(http, scope).ranged(stream().audioUrl, emptyMap(), 6, 65536, 2, 3)
                assertEquals(3L, size)
                bytes.use { assertEquals("cde", it.readBytes().decodeToString()) }
                assertEquals(2, calls)
            } finally {
                scope.cancel()
                http.close()
            }
        }

    @Test fun rejectedSignedUrlRefreshesBeforeReturningBytes() =
        runBlocking {
            var calls = 0
            val http =
                HttpClient(
                    MockEngine {
                        calls++
                        if (calls == 1) {
                            respond("", HttpStatusCode.Forbidden)
                        } else {
                            respond("abcdef", HttpStatusCode.PartialContent, headersOf("Content-Range", "bytes 0-5/6"))
                        }
                    },
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var extractions = 0
            val resolver =
                StreamResolver(
                    extractor { _, _ ->
                        extractions++
                        stream(bounded = true)
                    },
                    cipher,
                    StreamAdapters(http, scope),
                )
            try {
                val source = resolver.media(request, "auto").sources.single() as Streamable.Source.Raw
                val (bytes, _) = source.streamProvider!!.provide(0, -1)
                bytes.use { assertEquals("abcdef", it.readBytes().decodeToString()) }
                assertEquals(2, extractions)
                assertEquals(2, calls)
            } finally {
                scope.cancel()
                http.close()
            }
        }
}
