package dev.brahmkshatriya.echo.extension.auth

import com.metrolist.innertubex.bodyAsTextLimited
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** One fresh, single-use challenge; network preparation never opens an Echo WebView. */
internal class BotGuardChallengeSource(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> String,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Prepared(
        val json: String,
        val expiresAt: Long,
    )

    private val lock = Any()
    private var pending: Deferred<Prepared?>? = null
    private var startedAt = 0L

    fun warm() =
        synchronized(lock) {
            if (pending != null && nowMs() - startedAt >= 60_000) {
                pending?.cancel()
                pending = null
            }
            if (pending == null) {
                startedAt = nowMs()
                pending =
                    scope.async {
                        try {
                            val json = fetch()
                            require(json.length <= 2 * 1024 * 1024 && Json.parseToJsonElement(json) is JsonArray)
                            Prepared(json, nowMs() + 60_000)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null // The original WebView RPC remains the fallback.
                        }
                    }
            }
        }

    suspend fun take(): String? {
        warm()
        val candidate = synchronized(lock) { pending } ?: return null
        // A cold/slow prefetch must not hold up the working WebView path.
        val prepared =
            try {
                withTimeoutOrNull(250) { candidate.await() }
            } catch (error: CancellationException) {
                // Session invalidation cancels the prefetch, not its playback caller.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                null
            }
        return synchronized(lock) {
            if (pending !== candidate) return@synchronized null
            if (candidate.isCompleted) pending = null
            prepared?.takeIf { it.expiresAt > nowMs() }?.json
        }
    }

    fun clear() =
        synchronized(lock) {
            pending?.cancel()
            pending = null
        }

    companion object {
        fun create(
            http: HttpClient,
            scope: CoroutineScope,
        ) = BotGuardChallengeSource(scope, fetch = {
            val response =
                http.post("https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa/Create") {
                    header(HttpHeaders.UserAgent, YouTubeMusicWebViewLogin.DESKTOP_USER_AGENT)
                    header(HttpHeaders.Accept, "application/json")
                    header(HttpHeaders.ContentType, "application/json+protobuf")
                    header("x-goog-api-key", "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw")
                    header("x-user-agent", "grpc-web-javascript/0.1")
                    setBody("[\"O43z0dpjhgX20SCx4KAo\"]")
                    timeout { requestTimeoutMillis = 4_000 }
                }
            require(response.status.value in 200..299) { "Challenge preparation failed" }
            response.bodyAsTextLimited(2 * 1024 * 1024)
        })
    }
}
