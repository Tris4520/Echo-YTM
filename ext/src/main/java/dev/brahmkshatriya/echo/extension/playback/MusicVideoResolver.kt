package dev.brahmkshatriya.echo.extension.playback

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.mapper.ModelMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Prefer YouTube's explicit song/video link; otherwise require an official title/artist match. */
internal class MusicVideoResolver(
    private val mapper: ModelMapper,
    private val next: suspend (String) -> JsonObject,
    private val search: suspend (String) -> JsonObject,
) {
    private val cache = ResolutionCache<String, Track?>(128)

    fun clear() = cache.clear()

    suspend fun resolve(track: Track): Track? {
        if (isVideo(track)) return track
        val id = track.extras["videoId"] ?: track.id
        track.extras["musicVideoId"]?.takeIf(::validId)?.let {
            return track.copy(extras = track.extras + mapOf("videoId" to it, "wantVideo" to "true"))
        }
        return cache.getOrLoad(id) {
            val matched =
                try {
                    withTimeoutOrNull(3_000) {
                        var song = track
                        // Only read the selected queue item. Other recommendations are not counterparts.
                        val selected = quietly { selected(next(id), id) }
                        selected?.let { renderer ->
                            mapper.mapItem(renderer)?.let { if (it is Track) song = it }
                            if (isVideo(song)) return@withTimeoutOrNull song
                            counterpart(renderer)?.let { return@withTimeoutOrNull it }
                        }
                        if (song.artists.isEmpty()) song = track
                        if (song.artists.isEmpty()) return@withTimeoutOrNull null
                        val response = quietly { search("${song.artists.first().name} ${song.title}") } ?: return@withTimeoutOrNull null
                        match(song, mapper.tracks(response).data)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            matched to if (matched == null) 60_000L else 30 * 60_000L
        }
    }

    private fun counterpart(renderer: JsonObject): Track? =
        objects(renderer["counterpart"])
            .mapNotNull { it["playlistPanelVideoRenderer"] as? JsonObject }
            .mapNotNull { mapper.mapItem(it) as? Track }
            .firstOrNull { it.extras["musicVideoType"] == "MUSIC_VIDEO_TYPE_OMV" && validId(it.id) }

    internal fun match(
        song: Track,
        candidates: List<Track>,
    ): Track? =
        candidates
            .filter { candidate ->
                val title = normalizedTitle(candidate.title, song.artists.map { it.name })
                val sameArtist = candidate.artists.any { artist -> song.artists.any { normalized(it.name) == normalized(artist.name) } }
                candidate.id != song.id && validId(candidate.id) && candidate.extras["musicVideoType"] == "MUSIC_VIDEO_TYPE_OMV" &&
                    sameArtist && title == normalizedTitle(song.title, song.artists.map { it.name })
            }.firstOrNull()

    private fun selected(
        root: JsonObject,
        id: String,
    ): JsonObject? =
        objects(root)
            .mapNotNull { it["playlistPanelVideoRenderer"] as? JsonObject }
            .firstOrNull { (it["videoId"] as? JsonPrimitive)?.contentOrNull == id }

    private fun objects(
        node: JsonElement?,
        depth: Int = 0,
    ): Sequence<JsonObject> =
        sequence {
            if (depth > 35) return@sequence
            when (node) {
                is JsonObject -> {
                    yield(node)
                    node
                        .filterKeys {
                            it !in
                                setOf(
                                    "counterpart",
                                    "menu",
                                    "trackingParams",
                                )
                        }.values
                        .forEach { yieldAll(objects(it, depth + 1)) }
                }

                is JsonArray -> {
                    node.forEach { yieldAll(objects(it, depth + 1)) }
                }

                else -> {
                    Unit
                }
            }
        }

    private suspend fun <T> quietly(block: suspend () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    companion object {
        fun isVideo(track: Track): Boolean =
            track.extras["wantVideo"] == "true" ||
                track.extras["musicVideoType"] in setOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC")

        private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{11}"))

        private fun normalized(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        private fun normalizedTitle(
            value: String,
            artists: List<String>,
        ): String {
            var title =
                value.replace(
                    Regex("(?i)\\s*[\\[(](?:official(?: music)? video|official audio|music video|hd|4k(?: remaster)?|remastered)[\\])]"),
                    "",
                )
            for (artist in artists) title = title.replace(Regex("(?i)^${Regex.escape(artist)}\\s*[-–:]\\s*"), "")
            return normalized(title)
        }
    }
}
