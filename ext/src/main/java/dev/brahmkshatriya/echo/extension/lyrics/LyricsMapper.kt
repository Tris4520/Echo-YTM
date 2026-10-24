package dev.brahmkshatriya.echo.extension.lyrics

import dev.brahmkshatriya.echo.common.models.Lyrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object LyricsMapper {
    fun endpoint(node: JsonElement): Pair<String, String?>? {
        val endpoint = objects(node).firstOrNull { it.string("browseId")?.startsWith("MPLY") == true } ?: return null
        return checkNotNull(endpoint.string("browseId")) to endpoint.string("params")
    }

    fun youtube(node: JsonElement): Lyrics.Lyric? {
        val data = objects(node).firstOrNull { it["timedLyricsData"] is JsonArray }
        val lines = (data?.get("timedLyricsData") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (lines.isNotEmpty()) {
            val timed =
                lines.mapNotNull { line ->
                    val cue = line["cueRange"] as? JsonObject ?: return@mapNotNull null
                    val start = cue.string("startTimeMilliseconds")?.toLongOrNull() ?: return@mapNotNull null
                    val end = cue.string("endTimeMilliseconds")?.toLongOrNull() ?: return@mapNotNull null
                    val text = line.string("lyricLine") ?: return@mapNotNull null
                    if (start < 0 || end <= start) null else Lyrics.Item(text, start, end)
                }
            if (timed.size == lines.size) return Lyrics.Timed(timed.sortedBy { it.startTime }, fillTimeGaps = false)
            lines
                .mapNotNull { it.string("lyricLine") }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
                ?.let { return Lyrics.Simple(it) }
        }
        val shelf =
            objects(node)
                .firstOrNull { it["musicDescriptionShelfRenderer"] is JsonObject }
                ?.get("musicDescriptionShelfRenderer") as? JsonObject ?: return null
        val text = text(shelf["description"])
        return text.takeIf { it.isNotBlank() }?.let(Lyrics::Simple)
    }

    fun lrc(
        raw: String,
        durationMs: Long?,
    ): Lyrics.Timed? {
        val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val entries =
            raw
                .lineSequence()
                .flatMap { line ->
                    val times = timestamp.findAll(line).toList()
                    val text = if (times.isEmpty()) "" else line.substring(times.last().range.last + 1).trim()
                    times.asSequence().mapNotNull { match ->
                        val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                        val seconds = match.groupValues[2].toLongOrNull()?.takeIf { it < 60 } ?: return@mapNotNull null
                        val fraction = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0
                        (minutes * 60_000 + seconds * 1000 + fraction) to text
                    }
                }.toList()
                .groupBy { it.first }
                .toSortedMap()
                .map { (time, lines) ->
                    time to lines.map { it.second }.distinct().joinToString("\n")
                }
        if (entries.none { it.second.isNotBlank() }) return null
        val items =
            entries.mapIndexed { index, (start, text) ->
                val end = entries.getOrNull(index + 1)?.first ?: durationMs?.takeIf { it > start } ?: (start + 5000)
                Lyrics.Item(text, start, end)
            }
        return Lyrics.Timed(items, fillTimeGaps = false)
    }

    private fun text(node: JsonElement?): String =
        when (node) {
            is JsonPrimitive -> {
                node.contentOrNull.orEmpty()
            }

            is JsonObject -> {
                node.string("simpleText") ?: (node["runs"] as? JsonArray)
                    .orEmpty()
                    .joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }
            }

            else -> {
                ""
            }
        }

    private fun objects(
        node: JsonElement,
        depth: Int = 0,
    ): Sequence<JsonObject> =
        sequence {
            if (depth > 30) return@sequence
            when (node) {
                is JsonObject -> {
                    yield(node)
                    node.values.forEach { yieldAll(objects(it, depth + 1)) }
                }

                is JsonArray -> {
                    node.forEach { yieldAll(objects(it, depth + 1)) }
                }

                else -> {
                    Unit
                }
            }
        }

    internal fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
