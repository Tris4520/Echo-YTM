package dev.brahmkshatriya.echo.extension.lyrics

import com.metrolist.innertubex.bodyAsTextLimited
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.lyrics.LyricsMapper.string
import dev.brahmkshatriya.echo.extension.playback.ResolutionCache
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

internal class LyricsResolver(
    private val http: HttpClient,
    private val native: suspend (Track) -> Lyrics.Lyric?,
) {
    private data class Key(
        val video: String,
        val title: String,
        val artist: String?,
        val duration: Long?,
    )

    private val cache = ResolutionCache<Key, Lyrics?>()

    fun clear() = cache.clear()

    suspend fun resolve(track: Track): Lyrics? {
        val key =
            Key(
                track.extras["playbackVideoId"] ?: track.extras["videoId"] ?: track.id,
                track.title,
                track.artists.firstOrNull()?.name,
                track.duration,
            )
        return cache.getOrLoad(key) {
            val result =
                coroutineScope {
                    val synced = async { quietly(4_000) { lrclib(track) } }
                    val youtube = quietly(8_000) { native(track) }
                    if (youtube is Lyrics.Timed || youtube is Lyrics.WordByWord) {
                        synced.cancel()
                        Lyrics(key.video, track.title, subtitle = "YouTube Music • Synced", lyrics = youtube)
                    } else {
                        synced.await()?.let { Lyrics(key.video, track.title, subtitle = "LRCLIB • Synced", lyrics = it) }
                            ?: youtube?.let { Lyrics(key.video, track.title, subtitle = "YouTube Music", lyrics = it) }
                    }
                }
            result to if (result == null) 60_000L else 30 * 60_000L
        }
    }

    private suspend fun lrclib(track: Track): Lyrics.Timed? {
        val artist =
            track.artists
                .firstOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() } ?: return null
        val duration = track.duration?.takeIf { it > 0 } ?: return null
        val title = track.title.trim()

        suspend fun request(search: Boolean): List<JsonObject> {
            val response =
                http.get("https://lrclib.net/api/${if (search) "search" else "get"}") {
                    header(HttpHeaders.UserAgent, "Echo-YouTubeMusic/1.2.0")
                    parameter("track_name", title)
                    parameter("artist_name", artist)
                    if (!search) {
                        parameter("duration", duration / 1000)
                        track.album?.title?.let { parameter("album_name", it) }
                    }
                }
            if (response.status.value !in 200..299) return emptyList()
            return when (val json = Json.parseToJsonElement(response.bodyAsTextLimited(1024 * 1024))) {
                is JsonObject -> listOf(json)
                is JsonArray -> json.filterIsInstance<JsonObject>()
                else -> emptyList()
            }
        }

        fun match(candidate: JsonObject): Boolean {
            val length = candidate.string("duration")?.toDoubleOrNull() ?: return false
            return candidate.string("trackName")?.trim()?.equals(title, true) == true &&
                candidate.string("artistName")?.trim()?.equals(artist.trim(), true) == true &&
                abs(length * 1000 - duration) <= 3000
        }
        for (search in listOf(false, true)) {
            request(search)
                .asSequence()
                .filter(::match)
                .sortedBy { abs(checkNotNull(it.string("duration")).toDouble() * 1000 - duration) }
                .mapNotNull { it.string("syncedLyrics") }
                .mapNotNull { LyricsMapper.lrc(it, duration) }
                .filter { it.list.count { line -> line.text.isNotBlank() } >= 2 }
                .firstOrNull()
                ?.let { return it }
        }
        return null
    }

    private suspend fun <T> quietly(
        timeout: Long,
        block: suspend () -> T,
    ): T? =
        try {
            withTimeoutOrNull(timeout) { block() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
}
