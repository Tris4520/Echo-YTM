package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionUnitTest {
    @Test fun tabOrderMatchesEcoExtension() {
        assertEquals(listOf("All", "Songs", "Videos", "Albums", "Artists", "Playlists"), searchTabs.map { it.title })
        assertEquals(listOf("All", "History", "Playlists", "Songs", "Artists"), libraryTabs.map { it.title })
    }

    @Test fun suggestionsOnlyReadSuggestionRuns() {
        val response =
            Json
                .parseToJsonElement(
                    """{
            "contents":[{"searchSuggestionsSectionRenderer":{"contents":[
                {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"hello "},{"text":"world"}]},"icon":{"iconType":"SEARCH"},"trackingParams":"junk"}},
                {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"hello world"}]}}}
            ]}}],"text":"renderer label","params":"junk"
        }""",
                ).jsonObject
        assertEquals(listOf("hello world"), YouTubeMusicExtension().parseSearchSuggestions(response, "hello"))
    }

    @Test fun playlistMoveUsesFinalSuccessorIncludingLastPosition() {
        val tracks = listOf("a", "b", "c", "d").map { Track(it, it, extras = mapOf("setVideoId" to "set-$it")) }
        assertEquals("set-d", playlistMoveSuccessor(tracks, 0, 2))
        assertEquals("set-b", playlistMoveSuccessor(tracks, 3, 1))
        assertEquals("set-a", playlistMoveSuccessor(tracks, 2, 0))
        assertNull(playlistMoveSuccessor(tracks, 0, 3))
    }

    @Test fun publicNoArgumentConstructorAndOfflineInitialization() =
        runTest {
            val extension = YouTubeMusicExtension::class.java.getConstructor().newInstance()
            extension.setSettings(MockedSettings())
            extension.onInitialize()
            assertNull(extension.getCurrentUser())
            extension.close()
        }

    @Test fun loginPersistsInHostUserAndPublicUserContainsNoCredentials() =
        runTest {
            val extension = YouTubeMusicExtension()
            extension.setSettings(MockedSettings())
            val user =
                extension
                    .onLogin(
                        "cookie",
                        mapOf("cookie" to "SAPISID=fake", "visitorData" to "visitor", "authUser" to "2"),
                    ).single()
            assertEquals("2", user.id)
            extension.setLoginUser(user)
            assertTrue(extension.getCurrentUser()!!.extras.isEmpty())
            extension.setLoginUser(null)
            assertNull(extension.getCurrentUser())
            extension.close()
        }

    @Test fun invalidSavedUserDoesNotAuthenticate() =
        runTest {
            val extension = YouTubeMusicExtension()
            extension.setSettings(MockedSettings())
            extension.setLoginUser(User("0", "invalid", extras = mapOf("cookie" to "NOTSAPISID=fake")))
            assertNull(extension.getCurrentUser())
            extension.close()
        }
}
