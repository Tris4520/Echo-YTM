package dev.brahmkshatriya.echo.extension.auth

import dev.brahmkshatriya.echo.common.helpers.WebViewClient
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthTest {
    private val video = "dQw4w9WgXcQ"
    private val otherVideo = "abcdefghijk"
    private val visitor = "visitor-data"
    private val cookie = "SAPISID=fake"

    private fun result(
        visitorId: String = visitor,
        ttl: Long = 3600,
    ) = buildJsonObject {
        put("playerRequestToken", "visitor_token_123456789")
        put("streamingDataToken", "video_token_123456789")
        put("visitorData", visitorId)
        put("expiresInSeconds", ttl)
    }.toString()

    private fun client(block: suspend (WebViewRequest<String>) -> Result<String?>) =
        object : WebViewClient {
            override suspend fun await(
                showWebView: Boolean,
                reason: String,
                request: WebViewRequest<String>,
            ): Result<String?> {
                assertFalse(showWebView)
                return block(request)
            }
        }

    @Test fun loginCapturesCookieAndVisitorFromSameAccountRequest() =
        runTest {
            val request = YouTubeMusicWebViewLogin()
            val url = "https://music.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false"
            assertTrue(request.stopUrlRegex.matches("https://music.youtube.com/"))
            request.onStop(
                listOf(
                    NetworkRequest(
                        NetworkRequest.Method.POST,
                        url,
                        mapOf("X-Goog-AuthUser" to "2"),
                        """{"context":{"client":{"visitorData":"page-visitor"}}}""".toByteArray(),
                    ),
                ),
            )
            val user = request.onStop(NetworkRequest("https://music.youtube.com/"), "HSID=x;__Secure-3PAPISID=abc=def").orEmpty().single()
            assertEquals("2", user.id)
            assertEquals("page-visitor", user.extras["visitorData"])
            assertTrue(user.extras["cookie"]!!.contains("abc=def"))
        }

    @Test fun loginRejectsLookalikeHostsAndUnauthenticatedCookies() =
        runTest {
            val request = YouTubeMusicWebViewLogin()
            assertFalse(YouTubeMusicWebViewLogin.isMusicUrl("https://music.youtube.com.attacker.invalid/"))
            assertNull(request.onStop(NetworkRequest("https://music.youtube.com/youtubei/v1/account/account_menu"), "NOTSAPISID=fake"))
            assertNull(request.onStop(NetworkRequest("https://attacker.invalid/"), cookie))
        }

    @Test fun supportsWrappedWebViewResults() {
        assertEquals("value", parseWebViewObject(JsonPrimitive("""{"key":"value"}""").toString())?.string("key"))
        assertNull(parseWebViewObject("invalid"))
    }

    @Test fun demandDrivenAndCachesConcurrentIdenticalRequests() =
        runTest {
            var calls = 0
            val provider = WebViewPoTokenProvider(nowMs = { testScheduler.currentTime })
            provider.setWebViewClient(
                client {
                    calls++
                    Result.success(result())
                },
            )
            provider.prewarm()
            assertEquals(0, calls)
            val requests = List(8) { async { provider.getPoToken(video, visitor, null) } }
            requests.forEach { assertNotNull(it.await()) }
            assertEquals(1, calls)
            testScheduler.advanceTimeBy(300_001)
            assertNotNull(provider.getPoToken(video, visitor, null))
            assertEquals(2, calls)
        }

    @Test fun failuresHaveSharedCooldownAndDoNotRetryForEachVideo() =
        runTest {
            var calls = 0
            val provider = WebViewPoTokenProvider(nowMs = { testScheduler.currentTime })
            provider.setWebViewClient(
                client {
                    calls++
                    Result.failure(IllegalStateException("fake failure"))
                },
            )
            assertNull(provider.getPoToken(video, visitor, null))
            assertNull(provider.getPoToken(otherVideo, visitor, null))
            assertEquals(1, calls)
            testScheduler.advanceTimeBy(60_001)
            assertNull(provider.getPoToken(video, visitor, null))
            assertEquals(2, calls)
        }

    @Test fun rejectsWrongVisitorMalformedAndExpiredTokens() =
        runTest {
            val provider = WebViewPoTokenProvider()
            for (response in listOf(result("wrong-visitor"), result(ttl = 30), """{"playerRequestToken":{},"expiresInSeconds":{}}""")) {
                provider.setWebViewClient(client { Result.success(response) })
                assertNull(provider.getPoToken(video, visitor, null))
            }
        }

    @Test fun timeoutFallsBackAndParentCancellationPropagates() =
        runTest {
            val provider = WebViewPoTokenProvider(timeoutMs = 100)
            provider.setWebViewClient(client { awaitCancellation() })
            assertNull(provider.getPoToken(video, visitor, null))
            provider.invalidateAttestation()
            val job = async { provider.getPoToken(video, visitor, null) }
            runCurrent()
            job.cancel()
            assertTrue(job.isCancelled)
        }

    @Test fun accountChangeDiscardsInFlightAndQueuedResults() =
        runTest {
            val response = CompletableDeferred<String?>()
            var calls = 0
            val provider = WebViewPoTokenProvider()
            provider.setWebViewClient(
                client {
                    calls++
                    Result.success(response.await())
                },
            )
            provider.updateSession(cookie, visitor)
            val active = async { provider.getPoToken(video, visitor, cookie) }
            val queued = async { provider.getPoToken(otherVideo, visitor, cookie) }
            runCurrent()
            provider.updateSession("SAPISID=second-account", visitor)
            response.complete(result())
            assertNull(active.await())
            assertNull(queued.await())
            assertEquals(1, calls)
        }

    @Test fun cachedTokenDoesNotWaitForOtherVideoAndClientReinjectionKeepsCache() =
        runTest {
            val response = CompletableDeferred<String?>()
            var calls = 0
            val webView =
                client {
                    calls++
                    Result.success(if (calls == 1) result() else response.await())
                }
            val provider = WebViewPoTokenProvider()
            provider.setWebViewClient(webView)
            assertNotNull(provider.getPoToken(video, visitor, null))
            provider.setWebViewClient(webView)
            val cold = async { provider.getPoToken(otherVideo, visitor, null) }
            runCurrent()
            val cached = async { provider.getPoToken(video, visitor, null) }
            runCurrent()
            assertTrue(cached.isCompleted)
            assertNotNull(cached.await())
            assertFalse(cold.isCompleted)
            response.complete(result())
            assertNotNull(cold.await())
            assertEquals(2, calls)
        }

    @Test fun preparedChallengesAreSingleUseExpireAndSlowPreparationFallsBack() =
        runTest {
            var calls = 0
            val source = BotGuardChallengeSource(backgroundScope, fetch = { "[${++calls}]" }, nowMs = { testScheduler.currentTime })
            source.warm()
            runCurrent()
            assertEquals("[1]", source.take())
            assertEquals("[2]", source.take())
            source.warm()
            runCurrent()
            testScheduler.advanceTimeBy(60_001)
            assertEquals("[4]", source.take())
            source.clear()

            val pending = CompletableDeferred<String>()
            val slow = BotGuardChallengeSource(backgroundScope, fetch = { pending.await() })
            assertNull(slow.take())
            assertFalse(pending.isCancelled)
            pending.complete("[5]")
            runCurrent()
            assertEquals("[5]", slow.take())
            slow.clear()
        }

    @Test fun accountIndexChangeAndLogoutInvalidateCache() =
        runTest {
            var calls = 0
            val provider = WebViewPoTokenProvider()
            provider.setWebViewClient(
                client {
                    calls++
                    Result.success(result())
                },
            )
            provider.updateSession(cookie, visitor, accountId = "0")
            assertNotNull(provider.getPoToken(video, visitor, cookie))
            provider.updateSession(cookie, visitor, accountId = "1")
            assertNotNull(provider.getPoToken(video, visitor, cookie))
            assertEquals(2, calls)
            provider.updateSession(null, null)
            assertNull(provider.getPoToken(video, visitor, cookie))
            provider.close()
            assertNull(provider.getPoToken(video, visitor, null))
            assertTrue(provider.capabilities.providers.isEmpty())
        }

    @Test fun tokenScriptEscapesIdentifiersAndLoadsOnlyTinyDocument() {
        val request = PoTokenWebViewRequest(video, "visitor'\\\n\"data", 12_000)
        assertTrue(request.javascriptToEvaluate.startsWith("async function"))
        assertTrue(request.javascriptToEvaluate.contains(JsonPrimitive("visitor'\\\n\"data").toString()))
        assertTrue(request.initialUrl.url.startsWith("data:text/html;base64,"))
        assertTrue(request.initialUrl.url.length < 1000)
        assertFalse(request.dontCache)
    }

    @Test fun playerWarmupDoesNotMintADummyVideoToken() =
        runTest {
            var calls = 0
            val provider = WebViewPoTokenProvider()
            provider.setWebViewClient(
                client {
                    calls++
                    Result.success(result())
                },
            )
            provider.warmPlayer { assertNull(provider.getPoToken(video, visitor, null)) }
            assertEquals(0, calls)
            assertNotNull(provider.getPoToken(video, visitor, null))
            assertEquals(1, calls)
        }
}
