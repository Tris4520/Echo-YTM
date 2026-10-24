package dev.brahmkshatriya.echo.extension.auth

import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import dev.brahmkshatriya.echo.common.helpers.WebViewClient
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.toByteString
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private object NativePlayerWarmup : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<NativePlayerWarmup>
}

/** A stable provider is shared with the extractor throughout account changes. */
class WebViewPoTokenProvider(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMs: Long = 12_000,
) : TokenProvider {
    private data class Entry(
        val result: PoTokenResult,
        val expiresAt: Long,
    )

    private val stateLock = Any()
    private val requestMutex = Mutex()
    private val cache = linkedMapOf<String, Entry>()
    private var webViewClient: WebViewClient? = null
    private var enabled = true
    private var closed = false
    private var generation = 0L
    private var sessionCookie: String? = null
    private var sessionVisitor: String? = null
    private var accountId: String? = null
    private var retryAfter = 0L
    private var challengeSource: BotGuardChallengeSource? = null

    override val capabilities: TokenProviderCapabilities
        get() =
            synchronized(stateLock) {
                if (webViewClient == null || !enabled || closed) {
                    TokenProviderCapabilities()
                } else {
                    TokenProviderCapabilities(setOf(PoTokenProviderKind.WEB_BOTGUARD), usesWebView = true)
                }
            }

    fun setWebViewClient(client: WebViewClient) =
        synchronized(stateLock) {
            if (webViewClient !== client) {
                webViewClient = client
                clearLocked()
            }
            warmLocked()
        }

    internal fun setChallengeSource(source: BotGuardChallengeSource) =
        synchronized(stateLock) {
            challengeSource?.clear()
            challengeSource = source
            warmLocked()
        }

    private fun warmLocked() {
        if (webViewClient != null && enabled && !closed && retryAfter <= nowMs()) challengeSource?.warm()
    }

    fun updateSession(
        cookie: String?,
        visitorData: String?,
        useWebView: Boolean = true,
        accountId: String? = null,
    ) = synchronized(stateLock) {
        if (cookie != sessionCookie || visitorData != sessionVisitor || useWebView != enabled || accountId != this.accountId) {
            sessionCookie = cookie
            sessionVisitor = visitorData
            enabled = useWebView
            this.accountId = accountId
            clearLocked()
            warmLocked()
        }
    }

    private fun clearLocked() {
        generation++
        cache.clear()
        retryAfter = 0L
        challengeSource?.clear()
    }

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? {
        // InnerTubeX prewarm asks for a dummy video's proof. Warm native resources without
        // opening a WebView that would hold the token mutex ahead of real playback.
        if (currentCoroutineContext()[NativePlayerWarmup.Key] != null) {
            prewarm(cookie)
            return null
        }
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid video ID" }
        require(visitorData.isNotBlank() && visitorData.length <= 4096) { "Invalid visitor data" }
        val key = "$videoId:$visitorData:${cookie.orEmpty().toByteArray().toByteString().sha256().hex()}"
        val callGeneration =
            synchronized(stateLock) {
                if (closed || !enabled || webViewClient == null || (cookie != null && cookie != sessionCookie)) return null
                // Hits must not wait for another video's cold attestation.
                cache[key]?.takeIf { it.expiresAt > nowMs() }?.let { return it.result }
                if (retryAfter > nowMs()) return null
                generation
            }
        return requestMutex.withLock {
            val client: WebViewClient
            val requestGeneration: Long
            synchronized(stateLock) {
                if (closed || !enabled || generation != callGeneration || (cookie != null && cookie != sessionCookie)) return@withLock null
                client = webViewClient ?: return@withLock null
                cache[key]?.takeIf { it.expiresAt > nowMs() }?.let { return@withLock it.result }
                if (retryAfter > nowMs()) return@withLock null
                requestGeneration = generation
            }
            val response =
                try {
                    withTimeoutOrNull(timeoutMs) {
                        val source = synchronized(stateLock) { challengeSource }
                        val prepared = source?.take()
                        synchronized(stateLock) {
                            if (generation != requestGeneration || closed) return@withTimeoutOrNull null
                            // Prepare the next challenge while this WebView computes its proof.
                            warmLocked()
                        }
                        client
                            .await(
                                false,
                                "Generate YouTube playback token",
                                PoTokenWebViewRequest(videoId, visitorData, timeoutMs, prepared),
                            ).getOrThrow()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null // No retries; InnerTubeX can select a client that does not require tokens.
                }
            val data = parseWebViewObject(response)
            val player = data?.string("playerRequestToken")?.takeIf(::validToken)
            val streaming = data?.string("streamingDataToken")?.takeIf(::validToken)
            val ttl = (data?.get("expiresInSeconds") as? JsonPrimitive)?.longOrNull
            synchronized(stateLock) {
                if (generation != requestGeneration || closed) return@withLock null
                if (player == null || streaming == null || ttl == null || ttl <= 60 || data.string("visitorData") != visitorData) {
                    retryAfter = nowMs() + 60_000
                    challengeSource?.clear()
                    return@withLock null
                }
                val result = PoTokenResult(player, streaming, visitorData)
                cache[key] = Entry(result, nowMs() + minOf(ttl - 60, 300) * 1000)
                while (cache.size > 64) cache.remove(cache.keys.first())
                result
            }
        }
    }

    override suspend fun prewarm(cookie: String?) =
        synchronized(stateLock) {
            if (cookie == null || cookie == sessionCookie) warmLocked()
        }

    internal suspend fun warmPlayer(block: suspend () -> Unit) = withContext(NativePlayerWarmup) { block() }

    override suspend fun invalidateAttestation() = synchronized(stateLock) { clearLocked() }

    override suspend fun close() =
        synchronized(stateLock) {
            closed = true
            webViewClient = null
            sessionCookie = null
            sessionVisitor = null
            clearLocked()
        }

    private fun validToken(token: String) = token.length in 16..8192 && token.matches(Regex("[A-Za-z0-9_=-]+"))
}

internal class PoTokenWebViewRequest(
    videoId: String,
    visitorData: String,
    override val maxTimeout: Long,
    preparedChallenge: String? = null,
) : WebViewRequest.Evaluate<String> {
    private val marker = "https://www.youtube.com/generate_204?echo_po_ready=${UUID.randomUUID()}"

    // Trigger interception after a JS context exists, without loading YouTube's homepage/assets.
    private val html = "<!doctype html><html><head><meta charset='utf-8'></head><body><img src='$marker'></body></html>"
    override val initialUrl =
        NetworkRequest(
            "data:text/html;base64," + html.toByteArray(Charsets.UTF_8).toByteString().base64(),
            mapOf("User-Agent" to YouTubeMusicWebViewLogin.DESKTOP_USER_AGENT),
        )
    override val stopUrlRegex = Regex("^${Regex.escape(marker)}$")

    // RPCs are POSTs; allow normal HTTP caching for the hash-addressed interpreter.
    override val dontCache = false

    // Echo's Android interface can lack the JVM default body. Keep this getter local
    // so onPageStarted cannot dispatch to an abstract method in the host.
    override val javascriptToEvaluateOnPageStart: String? = null
    override val javascriptToEvaluate =
        "async function() {\n" +
            "const videoId = ${JsonPrimitive(videoId)};\nconst visitorData = ${JsonPrimitive(visitorData)};\n" +
            "const preparedChallenge = ${preparedChallenge?.let { "JSON.parse(${JsonPrimitive(it)})" } ?: "null"};\n" +
            script + "\n}"

    private companion object {
        val script by lazy {
            PoTokenWebViewRequest::class.java
                .getResourceAsStream("/botguard.js")!!
                .bufferedReader()
                .use { it.readText() }
        }
    }

    override suspend fun onStop(
        url: NetworkRequest,
        data: String?,
    ): String? = data
}
