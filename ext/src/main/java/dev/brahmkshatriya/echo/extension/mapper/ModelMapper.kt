package dev.brahmkshatriya.echo.extension.mapper

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Traverse only known renderer boundaries so nested menus do not become duplicate media. */
class ModelMapper {
    enum class Layout { Browse, Search, Library }

    private val mediaKeys = setOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "playlistPanelVideoRenderer")
    private val shelfKeys =
        setOf(
            "musicShelfRenderer",
            "musicCarouselShelfRenderer",
            "musicPlaylistShelfRenderer",
            "gridRenderer",
            "musicImmersiveCarouselShelfRenderer",
            "musicMultiRowShelfRenderer",
            "itemSectionRenderer",
        )
    private val continuationKeys =
        setOf("nextContinuationData", "nextRadioContinuationData", "reloadContinuationData", "continuationCommand")

    fun shelves(
        root: JsonObject,
        layout: Layout = Layout.Browse,
        browseMore: ((String, String?) -> Feed<Shelf>)? = null,
        fallbackTitle: String = "Unknown",
        more: (String) -> Feed<Shelf>,
    ): Page<Shelf> {
        if (layout == Layout.Library) return itemShelves(root)
        if (layout == Layout.Search) {
            val items = media(root)
            val shelf =
                if (items.all { it is Track }) {
                    Shelf.Lists.Tracks(fallbackTitle.hashCode().toString(), fallbackTitle, items.filterIsInstance<Track>())
                } else {
                    Shelf.Lists.Items(fallbackTitle.hashCode().toString(), fallbackTitle, items)
                }
            return Page(if (items.isEmpty()) emptyList() else listOf(shelf), continuation(root))
        }
        val shelves = mutableListOf<Shelf>()

        fun visit(
            node: JsonElement,
            depth: Int = 0,
        ) {
            if (depth > 40) return
            when (node) {
                is JsonArray -> {
                    node.forEach { visit(it, depth + 1) }
                }

                is JsonObject -> {
                    val shelfKey = shelfKeys.firstOrNull { node[it] is JsonObject }
                    val mediaKey = mediaKeys.firstOrNull { node[it] is JsonObject }
                    if (shelfKey != null) {
                        val shelf = node[shelfKey]!!.jsonObject
                        val items = media(shelf)
                        if (items.isNotEmpty()) {
                            val header = shelf["header"]
                            val title = text(shelf["title"]).ifBlank { text(find(header, "title")) }.ifBlank { fallbackTitle }
                            val subtitle =
                                text(find(header, "subtitle") ?: find(header, "strapline"))
                                    .takeIf { it.isNotBlank() }
                                    ?.lowercase()
                                    ?.replaceFirstChar { it.titlecase() }
                            val token = continuation(shelf)
                            val endpoint =
                                (
                                    find(
                                        header,
                                        "browseEndpoint",
                                    ) ?: find(shelf["bottomEndpoint"], "browseEndpoint")
                                ) as? JsonObject
                            val browseId = endpoint?.get("browseId")?.jsonPrimitive?.contentOrNull
                            val params = endpoint?.get("params")?.jsonPrimitive?.contentOrNull
                            val moreFeed =
                                browseId?.takeUnless { it.startsWith("FEmusic_") }?.let { browseMore?.invoke(it, params) }
                                    ?: token?.let(more)
                            shelves +=
                                Shelf.Lists.Items(
                                    id = title.hashCode().toString(),
                                    title = title,
                                    list = items,
                                    subtitle = subtitle,
                                    more = moreFeed,
                                )
                        }
                    } else if (mediaKey != null) {
                        mapItem(node[mediaKey]!!.jsonObject)?.let { shelves += Shelf.Item(it) }
                    } else {
                        node
                            .filterKeys { it !in setOf("header", "menu", "navigationEndpoint", "trackingParams", "counterpart") }
                            .values
                            .forEach { visit(it, depth + 1) }
                    }
                }

                else -> {
                    Unit
                }
            }
        }
        visit(root)
        return Page(shelves, continuation(root, skipShelves = true))
    }

    fun itemShelves(root: JsonObject): Page<Shelf> = Page(media(root).map { Shelf.Item(it) }, continuation(root))

    fun filterTabs(root: JsonObject): List<Tab> {
        val chips = find(root["header"] ?: root, "chipCloudRenderer") as? JsonObject ?: return emptyList()
        return (chips["chips"] as? JsonArray)
            .orEmpty()
            .mapNotNull { item ->
                val chip = (item as? JsonObject)?.get("chipCloudChipRenderer") as? JsonObject ?: return@mapNotNull null
                val selected = (chip["isSelected"] as? JsonPrimitive)?.contentOrNull == "true"
                val endpoint = find(chip["navigationEndpoint"], "browseEndpoint") as? JsonObject
                val params =
                    endpoint?.get("params")?.jsonPrimitive?.contentOrNull
                        ?: if (selected ||
                            (endpoint?.get("browseId") as? JsonPrimitive)?.contentOrNull == "FEmusic_home"
                        ) {
                            ""
                        } else {
                            return@mapNotNull null
                        }
                val title = text(chip["text"]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Tab(
                    params.ifBlank { "all" },
                    title,
                    extras =
                        mapOf(
                            "browseId" to params,
                            "params" to params,
                            "category" to title,
                            "isFilterChip" to "true",
                            "isHomeFeedTab" to "true",
                            "isSelected" to ((chip["isSelected"] as? JsonPrimitive)?.contentOrNull ?: "false"),
                        ),
                )
            }.distinctBy { it.id }
    }

    fun tracks(
        root: JsonObject,
        excludeId: String? = null,
        fallbackCover: ImageHolder? = null,
    ): Page<Track> =
        Page(
            media(root, fallbackCover = fallbackCover).filterIsInstance<Track>().filter {
                it.id != excludeId
            },
            continuation(root),
        )

    private fun media(
        root: JsonElement,
        fallbackCover: ImageHolder? = null,
    ): List<EchoMediaItem> {
        val result = mutableListOf<EchoMediaItem>()

        fun visit(
            node: JsonElement,
            depth: Int = 0,
        ) {
            if (depth > 40) return
            when (node) {
                is JsonArray -> {
                    node.forEach { visit(it, depth + 1) }
                }

                is JsonObject -> {
                    val key = mediaKeys.firstOrNull { node[it] is JsonObject }
                    if (key != null) {
                        mapItem(node[key]!!.jsonObject, fallbackCover = fallbackCover)?.let(result::add)
                    } else {
                        node.filterKeys { it !in setOf("menu", "header", "navigationEndpoint", "counterpart") }.values.forEach {
                            visit(it, depth + 1)
                        }
                    }
                }

                else -> {
                    Unit
                }
            }
        }
        visit(root)
        return result
    }

    fun mapItem(
        renderer: JsonObject,
        fallbackCover: ImageHolder? = null,
    ): EchoMediaItem? {
        val columns =
            (renderer["flexColumns"] as? JsonArray).orEmpty().mapNotNull {
                find(it, "musicResponsiveListItemFlexColumnRenderer")?.jsonObject?.get("text")
            }
        val titleNode = renderer["title"] ?: columns.firstOrNull()
        val title = text(titleNode).takeIf { it.isNotBlank() } ?: return null
        val endpoint = renderer["navigationEndpoint"] ?: find(titleNode, "navigationEndpoint")
        val watch =
            find(endpoint, "watchEndpoint") ?: (find(renderer["playlistItemData"], "videoId") ?: renderer["videoId"])
                ?.let { buildJsonObject { put("videoId", it) } }
        val videoId = (watch as? JsonObject)?.get("videoId")?.jsonPrimitive?.contentOrNull
        val browseEndpoint = find(endpoint, "browseEndpoint") as? JsonObject
        val browseId = browseEndpoint?.get("browseId")?.jsonPrimitive?.contentOrNull
        val cover = thumbnail(renderer["thumbnailRenderer"] ?: renderer["thumbnail"]) ?: fallbackCover
        // Queue items (playlistPanelVideoRenderer) keep artist/album runs in longBylineText, not subtitle/flexColumns.
        val bylineNode = renderer["subtitle"] ?: columns.getOrNull(1) ?: renderer["longBylineText"] ?: renderer["shortBylineText"]
        val subtitle = text(bylineNode)
        val artistRuns =
            runs(bylineNode).mapNotNull { run ->
                val artistId = (find(run["navigationEndpoint"], "browseId") as? JsonPrimitive)?.contentOrNull
                if (artistId?.startsWith("UC") ==
                    true
                ) {
                    Artist(artistId, text(run), cover = null, extras = mapOf("browseId" to artistId))
                } else {
                    null
                }
            }
        if (!videoId.isNullOrBlank()) {
            val durationText =
                text(renderer["lengthText"])
                    .ifBlank { text(find(renderer["fixedColumns"], "text")) }
                    .ifBlank { runs(bylineNode).map(::text).firstOrNull { parseDuration(it) != null }.orEmpty() }
                    .ifBlank { columns.lastOrNull()?.let(::text).orEmpty() }
            val albumRun =
                runs(bylineNode).firstOrNull {
                    (find(it["navigationEndpoint"], "browseId") as? JsonPrimitive)?.contentOrNull?.startsWith("MPRE") == true
                }
            val albumId = (find(albumRun?.get("navigationEndpoint"), "browseId") as? JsonPrimitive)?.contentOrNull
            val videoType = (find(watch, "musicVideoType") ?: find(renderer["overlay"], "musicVideoType")) as? JsonPrimitive
            val counterpart = find(renderer["counterpart"], "playlistPanelVideoRenderer") as? JsonObject
            val counterpartId = counterpart?.get("videoId") as? JsonPrimitive
            val counterpartType = find(counterpart?.get("navigationEndpoint"), "musicVideoType") as? JsonPrimitive
            val extras =
                buildMap {
                    put("videoId", videoId)
                    videoType?.contentOrNull?.let { type ->
                        put("musicVideoType", type)
                        if (type == "MUSIC_VIDEO_TYPE_OMV" || type == "MUSIC_VIDEO_TYPE_UGC") put("wantVideo", "true")
                    }
                    if (counterpartType?.contentOrNull ==
                        "MUSIC_VIDEO_TYPE_OMV"
                    ) {
                        counterpartId?.contentOrNull?.let { put("musicVideoId", it) }
                    }
                }
            return Track(
                videoId,
                title,
                cover = (cover as? ImageHolder.NetworkRequestImageHolder)?.copy(crop = true) ?: cover,
                artists = artistRuns,
                album = albumId?.let { Album(it, text(albumRun), cover = cover, extras = mapOf("browseId" to it)) },
                duration = parseDuration(durationText),
                subtitle = null,
                extras = extras,
                streamables = listOf(Streamable.server(videoId, 0)),
                isRadioSupported = true,
                isLikeable = true,
                isSaveable = true,
                isHideable = false,
            )
        }
        if (browseId.isNullOrBlank()) return null
        val extras = mapOf("browseId" to browseId)
        val pageType = (find(browseEndpoint, "pageType") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            browseId.startsWith("MPRE") || pageType == "MUSIC_PAGE_TYPE_ALBUM" -> {
                Album(
                    browseId,
                    title,
                    cover = cover,
                    artists = artistRuns,
                    extras = extras,
                    isLikeable = true,
                    isSaveable = true,
                    isShareable = true,
                )
            }

            browseId.startsWith("VL") || pageType == "MUSIC_PAGE_TYPE_PLAYLIST" -> {
                Playlist(
                    browseId.removePrefix("VL"),
                    title,
                    isEditable = false,
                    isPrivate = false,
                    cover = cover,
                    authors = artistRuns,
                    extras = extras,
                    isLikeable = true,
                    isSaveable = true,
                    isShareable = true,
                )
            }

            browseId.startsWith("UC") || pageType == "MUSIC_PAGE_TYPE_ARTIST" -> {
                Artist(browseId, title, cover = cover, extras = extras, isFollowable = true, isShareable = true)
            }

            else -> {
                null
            }
        }
    }

    fun albumHeader(
        root: JsonObject,
        album: Album,
    ): Album {
        val header = root["header"] as? JsonObject ?: return album
        val headerObj = find(header, "musicDetailHeaderRenderer") ?: find(header, "musicResponsiveHeaderRenderer") ?: header
        val title = text(find(headerObj, "title")).ifBlank { album.title }
        val cover = thumbnail(headerObj) ?: album.cover
        val subtitleNode = find(headerObj, "subtitle") ?: find(headerObj, "secondSubtitle")
        val subtitleText = text(subtitleNode)
        val artistRuns =
            runs(subtitleNode)
                .mapNotNull { run ->
                    val artistId = (find(run["navigationEndpoint"], "browseId") as? JsonPrimitive)?.contentOrNull
                    if (artistId?.startsWith("UC") ==
                        true
                    ) {
                        Artist(artistId, text(run), cover = null, extras = mapOf("browseId" to artistId))
                    } else {
                        null
                    }
                }.ifEmpty { album.artists }
        val trackCount = parseTrackCount(subtitleText)
        val year = parseYear(subtitleText)
        return album.copy(
            title = title,
            cover = cover,
            artists = artistRuns,
            trackCount = trackCount ?: album.trackCount,
            releaseDate = year?.let { Date(it) } ?: album.releaseDate,
            subtitle = subtitleText.ifBlank { album.subtitle },
            isLikeable = true,
            isSaveable = true,
            isShareable = true,
        )
    }

    fun artistHeader(
        root: JsonObject,
        artist: Artist,
    ): Artist {
        val header = root["header"] as? JsonObject ?: return artist
        val headerObj = find(header, "musicImmersiveHeaderRenderer") ?: find(header, "musicVisualHeaderRenderer") ?: header
        val name = text(find(headerObj, "title")).ifBlank { artist.name }
        val cover = thumbnail(headerObj) ?: artist.cover
        val description = text(find(headerObj, "description"))
        return artist.copy(
            name = name,
            cover = cover,
            subtitle = description.ifBlank { artist.subtitle },
            isFollowable = true,
            isShareable = true,
        )
    }

    fun playlistHeader(
        root: JsonObject,
        playlist: Playlist,
    ): Playlist {
        val header = root["header"] as? JsonObject ?: return playlist
        val headerObj =
            find(header, "musicDetailHeaderRenderer") ?: find(header, "musicResponsiveHeaderRenderer")
                ?: find(header, "musicEditablePlaylistDetailHeaderRenderer") ?: header
        val title = text(find(headerObj, "title")).ifBlank { playlist.title }
        val cover = thumbnail(headerObj) ?: playlist.cover
        val subtitleNode = find(headerObj, "subtitle") ?: find(headerObj, "secondSubtitle")
        val subtitleText = text(subtitleNode)
        val authors =
            runs(subtitleNode)
                .mapNotNull { run ->
                    val artistId = (find(run["navigationEndpoint"], "browseId") as? JsonPrimitive)?.contentOrNull
                    if (artistId?.startsWith("UC") ==
                        true
                    ) {
                        Artist(artistId, text(run), cover = null, extras = mapOf("browseId" to artistId))
                    } else {
                        null
                    }
                }.ifEmpty { playlist.authors }
        val trackCount = parseTrackCount(subtitleText)
        val isEditable = find(header, "musicEditablePlaylistDetailHeaderRenderer") != null || playlist.isEditable
        val privacy = (find(headerObj, "privacy") as? JsonPrimitive)?.contentOrNull
        val isPrivate = if (privacy == "PUBLIC") false else (privacy == "PRIVATE" || playlist.isPrivate)
        return playlist.copy(
            title = title,
            cover = cover,
            authors = authors,
            trackCount = trackCount ?: playlist.trackCount,
            subtitle = subtitleText.ifBlank { playlist.subtitle },
            isEditable = isEditable,
            isPrivate = isPrivate,
            isLikeable = true,
            isSaveable = true,
            isShareable = true,
        )
    }

    /** First playlistId found in a browse response, e.g. an album's OLAK5uy... id used to start its radio. */
    fun playlistId(root: JsonObject): String? = (find(root, "playlistId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun continuation(
        root: JsonElement,
        skipShelves: Boolean = false,
    ): String? {
        fun visit(
            node: JsonElement,
            depth: Int,
        ): String? {
            if (depth > 40) return null
            return when (node) {
                is JsonArray -> {
                    node.firstNotNullOfOrNull { visit(it, depth + 1) }
                }

                is JsonObject -> {
                    val command = continuationKeys.firstNotNullOfOrNull { node[it] as? JsonObject }
                    val direct =
                        command?.get("continuation")?.jsonPrimitive?.contentOrNull ?: command?.get("token")?.jsonPrimitive?.contentOrNull
                    direct
                        ?: node.filterKeys { it != "menu" && (!skipShelves || it !in shelfKeys) }.values.firstNotNullOfOrNull {
                            visit(
                                it,
                                depth + 1,
                            )
                        }
                }

                else -> {
                    null
                }
            }
        }
        return visit(root, 0)
    }

    private fun find(
        node: JsonElement?,
        key: String,
        depth: Int = 0,
    ): JsonElement? {
        if (depth > 40) return null
        return when (node) {
            is JsonObject -> node[key] ?: node.values.firstNotNullOfOrNull { find(it, key, depth + 1) }
            is JsonArray -> node.firstNotNullOfOrNull { find(it, key, depth + 1) }
            else -> null
        }
    }

    private fun runs(node: JsonElement?): List<JsonObject> =
        ((node as? JsonObject)?.get("runs") as? JsonArray).orEmpty().mapNotNull {
            it as? JsonObject
        }

    fun text(node: JsonElement?): String =
        when (node) {
            is JsonPrimitive -> {
                node.contentOrNull.orEmpty()
            }

            is JsonObject -> {
                node["simpleText"]?.jsonPrimitive?.contentOrNull ?: node["text"]?.jsonPrimitive?.contentOrNull
                    ?: runs(node).joinToString("") { text(it) }
            }

            else -> {
                ""
            }
        }

    private fun thumbnail(node: JsonElement?): ImageHolder? =
        (find(node, "thumbnails") as? JsonArray)
            ?.lastOrNull()
            ?.jsonObject
            ?.get("url")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { fixThumbnailUrl(it) }
            ?.toImageHolder()

    private fun fixThumbnailUrl(url: String): String {
        var fixed = if (url.startsWith("//")) "https:$url" else url
        fixed =
            fixed
                .replace(Regex("=w\\d+(-h\\d+)?(-[a-zA-Z0-9]+)*"), "=w1024-h1024-l90-rj")
                .replace(Regex("=s\\d+"), "=s1024")
        return fixed
    }

    private fun parseDuration(text: String): Long? {
        if (!text.matches(Regex("[0-9]+(?::[0-9]{2}){1,2}"))) return null
        return text.split(':').fold(0L) { total, part -> total * 60 + part.toLong() } * 1000
    }

    private fun parseTrackCount(text: String): Long? {
        val match = Regex("([0-9,]+)\\s*(?:songs?|tracks?)", RegexOption.IGNORE_CASE).find(text)
        return match
            ?.groupValues
            ?.get(1)
            ?.replace(",", "")
            ?.toLongOrNull()
    }

    private fun parseYear(text: String): Int? {
        val match = Regex("\\b(19[5-9]\\d|20[0-2]\\d)\\b").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}
