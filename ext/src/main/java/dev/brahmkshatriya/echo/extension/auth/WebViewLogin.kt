package dev.brahmkshatriya.echo.extension.auth

import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/** Echo owns the WebView, cookie jar, UI thread and login cancellation. */
class YouTubeMusicWebViewLogin :
    WebViewRequest.Cookie<List<User>>,
    WebViewRequest.Headers<List<User>> {
    // Implement defaults locally for compatibility with Echo's Android interface.
    override val maxTimeout = 15_000L
    override val dontCache = false

    override val initialUrl =
        NetworkRequest(
            LOGIN_URL,
            mapOf(
                "User-Agent" to DESKTOP_USER_AGENT,
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        )

    // Finish when the Google sign-in redirect lands back on YouTube Music (cookies are checked in onStop).
    override val stopUrlRegex = Regex("^https://music\\.youtube\\.com/?(?:[?#].*)?$")
    override val interceptUrlRegex = Regex("^https://music\\.youtube\\.com/youtubei/v1/.*")
    private var visitorData: String? = null
    private var authUser = "0"

    override suspend fun onStop(requests: List<NetworkRequest>): List<User>? {
        // Echo calls Headers before Cookie. Pick up visitor identity if the page already made API calls;
        // it is optional, InnerTubeX can fetch fresh visitor data when it is missing.
        visitorData = null
        authUser = "0"
        val request =
            requests.lastOrNull {
                it.lowerCaseHeaders.containsKey(
                    "x-goog-visitor-id",
                ) || it.lowerCaseHeaders.containsKey("x-goog-authuser")
            }
                ?: return null
        visitorData = request.lowerCaseHeaders["x-goog-visitor-id"]?.takeIf { it.isNotBlank() }
            ?: runCatching {
                parseWebViewObject(request.body?.decodeToString())?.jsonObject("context")?.jsonObject("client")?.string("visitorData")
            }.getOrNull()
        authUser = request.lowerCaseHeaders["x-goog-authuser"]?.takeIf { it.matches(Regex("[0-9]+")) } ?: "0"
        return null
    }

    override suspend fun onStop(
        url: NetworkRequest,
        cookie: String,
    ): List<User>? {
        if (!isMusicUrl(url.url) || !stopUrlRegex.matches(url.url) || !hasLoginCookie(cookie)) return null
        val extras =
            buildMap {
                put("cookie", cookie)
                put("authUser", authUser)
                visitorData?.takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
            }
        // Echo persists this private login User and restores it via setLoginUser.
        return listOf(User(authUser, "YouTube Music", extras = extras))
    }

    companion object {
        const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube" +
                "&continue=https%3A%2F%2Fmusic.youtube.com%2F"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        fun hasLoginCookie(cookie: String): Boolean =
            cookie.split(';').any { pair ->
                val parts = pair.trim().split('=', limit = 2)
                parts.size == 2 && parts[0] in setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID") && parts[1].isNotBlank()
            }

        fun isMusicUrl(url: String): Boolean =
            runCatching {
                URI(url).let { it.scheme == "https" && it.host == "music.youtube.com" && (it.port == -1 || it.port == 443) }
            }.getOrDefault(false)
    }
}

internal fun parseWebViewObject(data: String?): JsonObject? =
    runCatching {
        if (data.isNullOrBlank() || data.length > 64 * 1024) return null
        val element = Json.parseToJsonElement(data)
        (element as? JsonObject) ?: (Json.parseToJsonElement(element.jsonPrimitive.content) as? JsonObject)
    }.getOrNull()

internal fun JsonObject.jsonObject(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
