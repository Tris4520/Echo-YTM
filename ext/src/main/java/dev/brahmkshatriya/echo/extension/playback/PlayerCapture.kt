package dev.brahmkshatriya.echo.extension.playback

import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.extraction.ExtractionCipherService
import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.models.response.PlayerResponse
import io.ktor.client.call.replaceResponse
import io.ktor.client.call.save
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Each extraction sees only its own responses, including parallel client probes. */
internal class PlayerCapture : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PlayerCapture>

    private val responses = mutableMapOf<String, PlayerResponse>()
    private val labels = mutableMapOf<String, Map<Int, String>>()
    var config: PlayerConfig? = null
    var playerUrl: String? = null

    @Synchronized fun record(
        client: String,
        response: PlayerResponse,
        formatLabels: Map<Int, String> = emptyMap(),
    ) {
        responses[client] = response
        labels[client] = formatLabels
    }

    @Synchronized fun response(client: String): PlayerResponse? = responses[client]

    @Synchronized fun formatLabels(client: String): Map<Int, String> = labels[client].orEmpty()
}

internal class CapturingCipher(
    private val delegate: ExtractionCipherService,
) : ExtractionCipherService by delegate {
    override suspend fun processFormats(
        playerUrl: String,
        formats: List<PlayerResponse.StreamingData.Format>,
    ): List<PlayerResponse.StreamingData.Format> {
        currentCoroutineContext()[PlayerCapture]?.playerUrl = playerUrl
        return delegate.processFormats(playerUrl, formats)
    }
}

private val captureJson = Json { ignoreUnknownKeys = true }

internal val CapturePlayer =
    createClientPlugin("EchoYtmPlayerCapture") {
        on(Send) { request ->
            val capture = currentCoroutineContext()[PlayerCapture]
            val isPlayer =
                request.url
                    .build()
                    .encodedPath
                    .substringAfterLast('/') == "player"
            if (capture == null || !isPlayer) return@on proceed(request)
            request.headers["X-Goog-FieldMask"]?.let { mask ->
                request.headers["X-Goog-FieldMask"] =
                    mask.replace("quality,audioQuality", "quality,qualityLabel,fps,colorInfo,audioQuality")
            }
            val call = proceed(request)
            if (call.response.status.value != 200) return@on call
            // Preserve InnerTubeX's bounded response handling and replay the same bytes to its decoder.
            val body = call.response.bodyAsTextLimited(4 * 1024 * 1024)
            val client =
                (request.body as? TextContent)?.text?.let { text ->
                    runCatching {
                        captureJson
                            .parseToJsonElement(text)
                            .jsonObject["context"]
                            ?.jsonObject
                            ?.get("client")
                            ?.jsonObject
                            ?.get("clientName")
                            ?.jsonPrimitive
                            ?.content
                    }.getOrNull()
                }
            if (client != null) {
                runCatching {
                    val node = captureJson.parseToJsonElement(body).jsonObject
                    val data = node["streamingData"] as? JsonObject
                    val formats = (data?.get("formats") as? JsonArray).orEmpty() + (data?.get("adaptiveFormats") as? JsonArray).orEmpty()
                    val labels =
                        formats
                            .mapNotNull { format ->
                                val obj = format as? JsonObject ?: return@mapNotNull null
                                val itag = obj["itag"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                                val label = obj["qualityLabel"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                                itag to label
                            }.toMap()
                    capture.record(client, captureJson.decodeFromJsonElement<PlayerResponse>(node), labels)
                }
            }
            call.replaceResponse { ByteReadChannel(body.toByteArray()) }.save()
        }
    }

internal class CapturingConfigParser(
    private val delegate: YtConfigParser,
) : YtConfigParser {
    override suspend fun fetchConfig(
        videoId: String,
        useLoginCookies: Boolean,
    ): PlayerConfig = delegate.fetchConfig(videoId, useLoginCookies).also { currentCoroutineContext()[PlayerCapture]?.config = it }

    override suspend fun fetchEmbeddedConfig(
        videoId: String,
        useLoginCookies: Boolean,
    ): PlayerConfig = delegate.fetchEmbeddedConfig(videoId, useLoginCookies).also { currentCoroutineContext()[PlayerCapture]?.config = it }
}
