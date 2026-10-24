package dev.brahmkshatriya.echo.extension.mapper

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelMapperTest {
    private val mapper = ModelMapper()
    private val item = """{"musicResponsiveListItemRenderer":{
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song","navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}}]}}},
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}]}}}],
        "playlistItemData":{"videoId":"abcdefghijk"},"lengthText":{"simpleText":"3:25"},
        "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.invalid/cover.jpg"}]}}}}}"""

    private val itemNoThumbnail = """{"musicResponsiveListItemRenderer":{
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album Song","navigationEndpoint":{"watchEndpoint":{"videoId":"xyz12345678"}}}]}}},
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist Name","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}]}}}],
        "playlistItemData":{"videoId":"xyz12345678"},"lengthText":{"simpleText":"4:00"}}}"""

    @Test fun matchesEcoBrowseSearchAndLibraryShelfLayouts() {
        val root =
            Json
                .parseToJsonElement(
                    """{"contents":[
            {"gridRenderer":{"title":{"simpleText":"Mixes"},"items":[$item]}},
            {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{
                "title":{"runs":[{"text":"Quick picks","navigationEndpoint":{"browseEndpoint":{"browseId":"VLmore","params":"browse-params"}}}]},
                "strapline":{"simpleText":"FOR YOU"}}},"contents":[$item]}}
        ]}""",
                ).jsonObject
        var moreId: String? = null
        var moreParams: String? = null
        val browse =
            mapper.shelves(root, browseMore = { id, params ->
                moreId = id
                moreParams = params
                emptyList<Shelf>().toFeed()
            }) { emptyList<Shelf>().toFeed() }
        assertEquals(listOf("Mixes", "Quick picks"), browse.data.map { it.title })
        browse.data.forEach { assertEquals(Shelf.Lists.Type.Linear, (it as Shelf.Lists.Items).type) }
        assertEquals("For you", (browse.data.last() as Shelf.Lists.Items).subtitle)
        assertEquals("VLmore", moreId)
        assertEquals("browse-params", moreParams)
        val search = mapper.shelves(root, ModelMapper.Layout.Search) { emptyList<Shelf>().toFeed() }
        search.data.forEach { assertEquals(Shelf.Lists.Type.Linear, (it as Shelf.Lists.Tracks).type) }
        val library = mapper.itemShelves(root)
        assertEquals(2, library.data.size)
        library.data.forEach { assertEquals("Song", (it as Shelf.Item).media.title) }
    }

    @Test fun mapsTrackDurationArtistAndPlayableServer() {
        val root = Json.parseToJsonElement("""{"contents":[$item]}""").jsonObject
        val track = mapper.tracks(root).data.single()
        assertEquals("Song", track.title)
        assertEquals(205_000L, track.duration)
        assertEquals("Artist", track.artists.single().name)
        assertEquals(track.id, track.servers.single().id)
        assertNotNull(track.cover)
    }

    @Test fun appliesFallbackCoverWhenTrackHasNoThumbnail() {
        val root = Json.parseToJsonElement("""{"contents":[$itemNoThumbnail]}""").jsonObject
        val albumCover = "https://example.invalid/album_cover.jpg".toImageHolder()
        val track = mapper.tracks(root, fallbackCover = albumCover).data.single()
        assertEquals("Album Song", track.title)
        assertEquals(albumCover.copy(crop = true), track.cover)
    }

    @Test fun parsesAlbumHeaderDetails() {
        val headerJson = """{"header":{"musicDetailHeaderRenderer":{
            "title":{"runs":[{"text":"Test Album"}]},
            "subtitle":{"runs":[{"text":"Test Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}},{"text":" • 2024 • 10 songs"}]},
            "thumbnail":{"croppedSquareThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.invalid/header_cover.jpg"}]}}}
        }}}"""
        val root = Json.parseToJsonElement(headerJson).jsonObject
        val album = mapper.albumHeader(root, Album("MPRE123", "Fallback Title"))
        assertEquals("Test Album", album.title)
        assertEquals("https://example.invalid/header_cover.jpg".toImageHolder(), album.cover)
        assertEquals(10L, album.trackCount)
        assertEquals(2024, album.releaseDate?.year)
    }

    @Test fun parsesPlaylistHeaderDetails() {
        val headerJson = """{"header":{"musicDetailHeaderRenderer":{
            "title":{"runs":[{"text":"My Playlist"}]},
            "subtitle":{"runs":[{"text":"User Name","navigationEndpoint":{"browseEndpoint":{"browseId":"UCuser"}}},{"text":" • 25 songs"}]},
            "thumbnail":{"croppedSquareThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.invalid/playlist_cover.jpg"}]}}}
        }}}"""
        val root = Json.parseToJsonElement(headerJson).jsonObject
        val playlist = mapper.playlistHeader(root, Playlist("PL123", "Fallback Title", isEditable = false))
        assertEquals("My Playlist", playlist.title)
        assertEquals("https://example.invalid/playlist_cover.jpg".toImageHolder(), playlist.cover)
        assertEquals(25L, playlist.trackCount)
    }

    @Test fun keepsShelfAndPageContinuationSeparateAndAvoidsDuplicateItems() {
        val root =
            Json
                .parseToJsonElement(
                    """{"contents":{"sectionListRenderer":{
            "contents":[{"musicShelfRenderer":{"title":{"simpleText":"Songs"},"contents":[$item],
                "continuations":[{"nextContinuationData":{"continuation":"shelf-next"}}]}}],
            "continuations":[{"nextContinuationData":{"continuation":"page-next"}}]}}}""",
                ).jsonObject
        var moreToken: String? = null
        val page =
            mapper.shelves(root) {
                moreToken = it
                emptyList<Shelf>().toFeed()
            }
        assertEquals("page-next", page.continuation)
        assertEquals("shelf-next", moreToken)
        assertEquals(1, page.data.size)
        assertEquals(1, (page.data.single() as Shelf.Lists.Items).list.size)
    }

    @Test fun readsContinuationCommandOnTrackPages() {
        val root =
            Json
                .parseToJsonElement(
                    """{"continuationContents":{"musicPlaylistShelfContinuation":{
            "contents":[$item,{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}}}""",
                ).jsonObject
        val page = mapper.tracks(root)
        assertEquals("next", page.continuation)
        assertEquals(1, page.data.size)
    }
}
