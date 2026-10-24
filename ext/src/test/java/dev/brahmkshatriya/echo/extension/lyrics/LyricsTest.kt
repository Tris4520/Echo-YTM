package dev.brahmkshatriya.echo.extension.lyrics

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test fun nativeCueRangesPreserveRealTimingAndEndpointParams() {
        val endpoint = Json.parseToJsonElement("""{"browseEndpoint":{"browseId":"MPLYt_song","params":"mobile"}}""")
        assertEquals("MPLYt_song" to "mobile", LyricsMapper.endpoint(endpoint))
        val response =
            Json.parseToJsonElement(
                """{"lyricsData":{"timedLyricsData":[
            {"lyricLine":"First","cueRange":{"startTimeMilliseconds":"9200","endTimeMilliseconds":"10630"}},
            {"lyricLine":"Second","cueRange":{"startTimeMilliseconds":"10680","endTimeMilliseconds":"12540"}}
        ]}}""",
            )
        val lyrics = LyricsMapper.youtube(response) as Lyrics.Timed
        assertEquals(listOf(Lyrics.Item("First", 9200, 10630), Lyrics.Item("Second", 10680, 12540)), lyrics.list)
        assertTrue(!lyrics.fillTimeGaps)
        val plain = Json.parseToJsonElement("""{"timedLyricsData":[{"lyricLine":"No timestamps"}]}""")
        assertEquals(Lyrics.Simple("No timestamps"), LyricsMapper.youtube(plain))
    }

    @Test fun lrcSupportsFractionalAndRepeatedTimestampsWithoutTreatingCreditsAsLyrics() {
        val lyrics = checkNotNull(LyricsMapper.lrc("[ar:Artist]\n[00:01.5][00:03.050]Repeat\n[00:05.00]Last", 9000))
        assertEquals(
            listOf(Lyrics.Item("Repeat", 1500, 3050), Lyrics.Item("Repeat", 3050, 5000), Lyrics.Item("Last", 5000, 9000)),
            lyrics.list,
        )
    }

    @Test fun syncedFallbackRequiresMatchingRecordingAndCachesResult() =
        runBlocking {
            var calls = 0
            val http =
                HttpClient(
                    MockEngine {
                        calls++
                        respond(
                            """{"trackName":"Song","artistName":"Artist","duration":120,"syncedLyrics":"[00:01.00]Hello\n[00:03.00]Next line"}""",
                        )
                    },
                )
            try {
                val resolver = LyricsResolver(http) { Lyrics.Simple("Plain text") }
                val track = Track("abcdefghijk", "Song", artists = listOf(Artist("artist", "Artist")), duration = 120_000)
                assertTrue(resolver.resolve(track)?.lyrics is Lyrics.Timed)
                assertTrue(resolver.resolve(track)?.lyrics is Lyrics.Timed)
                assertEquals(1, calls)
                val differentVersion = track.copy(duration = 160_000)
                assertEquals(Lyrics.Simple("Plain text"), resolver.resolve(differentVersion)?.lyrics)
            } finally {
                http.close()
            }
        }
}
