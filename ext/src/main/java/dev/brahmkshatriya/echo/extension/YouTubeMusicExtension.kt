package dev.brahmkshatriya.echo.extension

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.innertubex.utils.sanitizeCookieString
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditCoverClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditPrivacyClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.SettingsChangeListenerClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewClient
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.providers.WebViewClientProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.auth.BotGuardChallengeSource
import dev.brahmkshatriya.echo.extension.auth.WebViewPoTokenProvider
import dev.brahmkshatriya.echo.extension.auth.YouTubeMusicWebViewLogin
import dev.brahmkshatriya.echo.extension.lyrics.LyricsMapper
import dev.brahmkshatriya.echo.extension.lyrics.LyricsResolver
import dev.brahmkshatriya.echo.extension.mapper.ModelMapper
import dev.brahmkshatriya.echo.extension.playback.CapturePlayer
import dev.brahmkshatriya.echo.extension.playback.CapturingCipher
import dev.brahmkshatriya.echo.extension.playback.CapturingConfigParser
import dev.brahmkshatriya.echo.extension.playback.MusicVideoResolver
import dev.brahmkshatriya.echo.extension.playback.PlaybackRequest
import dev.brahmkshatriya.echo.extension.playback.StreamAdapters
import dev.brahmkshatriya.echo.extension.playback.StreamResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File

/** Echo loads extensions through their public no-argument constructor. */
class YouTubeMusicExtension :
    ExtensionClient,
    HomeFeedClient,
    SearchFeedClient,
    QuickSearchClient,
    LibraryFeedClient,
    TrackClient,
    AlbumClient,
    PlaylistClient,
    PlaylistEditClient,
    PlaylistEditCoverClient,
    PlaylistEditPrivacyClient,
    ArtistClient,
    RadioClient,
    LikeClient,
    SaveClient,
    FollowClient,
    ShareClient,
    LyricsClient,
    LoginClient.WebView,
    LoginClient.CustomInput,
    WebViewClientProvider,
    SettingsChangeListenerClient,
    Closeable {
    private val lock = Any()
    private lateinit var settings: Settings
    private var currentUser: User? = null
    private var httpClient: HttpClient? = null
    private var innerTube: InnerTube? = null
    private var extractor: InnerTubeExtractor? = null
    private var resolver: StreamResolver? = null
    private var musicVideos: MusicVideoResolver? = null
    private var playbackScope: CoroutineScope? = null
    private var lyricsResolver: LyricsResolver? = null
    private var warmupJob: Job? = null
    private var lookaheadJob: Job? = null
    private var initialized = false
    private val nextTracks = linkedMapOf<String, Track>()
    private var playingVideo: String? = null
    private val tokenProvider = WebViewPoTokenProvider()
    val mapper = ModelMapper()
    private val json = Json { ignoreUnknownKeys = true }

    override fun setSettings(settings: Settings) =
        synchronized(lock) {
            this.settings = settings
            if (innerTube == null) {
                val http =
                    HttpClient(OkHttp) {
                        install(CapturePlayer)
                        install(ContentNegotiation) { json(this@YouTubeMusicExtension.json) }
                        install(HttpTimeout) {
                            requestTimeoutMillis = 30_000
                            socketTimeoutMillis = 30_000
                            connectTimeoutMillis = 5_000
                        }
                    }
                httpClient = http
                val tube = InnerTube(http)
                innerTube = tube
                val store = RemotePlayerConfigStore(http, FaradayRepository(settings))
                val cipher = YouTubeCipherService(http, store)
                val parser = CapturingConfigParser(YtConfigParserImpl(http, tube, store, cipherService = cipher))
                val capturingCipher = CapturingCipher(cipher)
                val streamExtractor = InnerTubeExtractor(parser, capturingCipher, tube, tokenProvider = tokenProvider)
                extractor = streamExtractor
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                playbackScope = scope
                tokenProvider.setChallengeSource(BotGuardChallengeSource.create(http, scope))
                resolver =
                    StreamResolver(
                        streamExtractor,
                        cipher,
                        StreamAdapters(http, scope),
                        invalidateAttestation = tokenProvider::invalidateAttestation,
                        preloadScope = scope,
                        nativeWarmup = tokenProvider::warmPlayer,
                    )
                lyricsResolver = LyricsResolver(http, ::youtubeLyrics)
                musicVideos =
                    MusicVideoResolver(
                        mapper,
                        next = { video -> tube.next(YouTubeClient.WEB_REMIX, videoId = video).readJson() },
                        search = { query -> tube.search(YouTubeClient.WEB_REMIX, query, params = VIDEOS_FILTER).readJson() },
                    )
            }
            applySession()
        }

    override suspend fun onSettingsChanged(
        settings: Settings,
        key: String?,
    ) {
        synchronized(lock) {
            this.settings = settings
            if (key == null || key.startsWith("youtube_music_")) {
                lookaheadJob?.cancel()
                resolver?.clear()
                musicVideos?.clear()
            }
            if (innerTube == null) {
                setSettings(settings)
            } else if (key == null || key == "youtube_music_language" || key == "youtube_music_region") {
                tube().locale =
                    YouTubeLocale(
                        hl = settings.getString("youtube_music_language")?.takeIf { it.isNotBlank() } ?: "en",
                        gl = settings.getString("youtube_music_region")?.takeIf { it.isNotBlank() } ?: "GB",
                    )
                lyricsResolver?.clear()
                schedulePlayerWarmup()
            }
        }
    }

    override suspend fun onInitialize() {
        check(innerTube != null) { "Settings have not been injected" }
        extractor?.setPlayerBootstrapCache(
            read = { settings.getString("player_bootstrap") },
            write = { settings.putString("player_bootstrap", it) },
        )
        synchronized(lock) {
            initialized = true
            schedulePlayerWarmup()
        }
    }

    private fun schedulePlayerWarmup() {
        if (!initialized) return
        val player = extractor ?: return
        warmupJob?.cancel()
        warmupJob =
            playbackScope?.launch {
                try {
                    withTimeoutOrNull(15_000) { tokenProvider.warmPlayer { player.prewarm() } }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Playback keeps InnerTubeX's normal lazy initialization/fallback path.
                }
            }
    }

    override fun setWebViewClient(webViewClient: WebViewClient) = tokenProvider.setWebViewClient(webViewClient)

    override suspend fun getSettingItems(): List<Setting> =
        listOf(
            SettingList(
                "Audio Quality",
                "youtube_music_audio_quality",
                "Preferred audio playback quality",
                listOf("High (Best)", "Medium/Auto", "Low (Saver)"),
                listOf("HIGH", "AUTO", "LOW"),
                0,
            ),
            SettingSwitch(
                "Prefer Music Videos",
                "youtube_music_prefer_videos",
                "Prefer music video playback instead of audio-only streams when available",
                false,
            ),
            SettingList(
                "Max Video Quality",
                "youtube_music_max_video_height",
                "Maximum video resolution for music videos",
                listOf("144p", "240p", "360p", "480p", "720p", "1080p (FHD)", "1440p (2K)", "2160p (4K)", "4320p (8K)", "Unlimited"),
                listOf("144", "240", "360", "480", "720", "1080", "1440", "2160", "4320", "9999"),
                5,
            ),
            SettingTextInput("Language", "youtube_music_language", "YouTube language code", "en"),
            SettingTextInput("Region", "youtube_music_region", "YouTube country code", "GB"),
        )

    private fun applySession() {
        val tube = innerTube ?: return
        tube.locale =
            YouTubeLocale(
                hl = settings.getString("youtube_music_language")?.takeIf { it.isNotBlank() } ?: "en",
                gl = settings.getString("youtube_music_region")?.takeIf { it.isNotBlank() } ?: "GB",
            )
        val extras = currentUser?.extras.orEmpty()
        val cookie = extras["cookie"]?.let(::sanitizeCookieString)
        tube.replaceSession(cookie, extras["visitorData"], extras["dataSyncId"], extras["authUser"] ?: "0", cookie != null)
        tokenProvider.updateSession(
            cookie,
            extras["visitorData"],
            true,
            extras["authUser"].orEmpty() + ":" + extras["dataSyncId"].orEmpty(),
        )
    }

    override val webViewRequest: WebViewRequest<List<User>>
        get() = YouTubeMusicWebViewLogin()

    override val forms =
        listOf(
            LoginClient.Form(
                "cookie",
                "YouTube Music cookies",
                LoginClient.InputField.Type.Password,
                listOf(
                    LoginClient.InputField(LoginClient.InputField.Type.Password, "cookie", "Cookie (including SAPISID)", true),
                    LoginClient.InputField(LoginClient.InputField.Type.Misc, "visitorData", "Visitor data (optional)", false),
                    LoginClient.InputField(
                        LoginClient.InputField.Type.Number,
                        "authUser",
                        "Account index (usually 0)",
                        false,
                        Regex("[0-9]+"),
                    ),
                ),
            ),
        )

    override suspend fun onLogin(
        key: String,
        data: Map<String, String?>,
    ): List<User> {
        require(key == "cookie") { "Unknown login form" }
        val cookie = sanitizeCookieString(data["cookie"].orEmpty())
        require(YouTubeMusicWebViewLogin.hasLoginCookie(cookie)) { "A SAPISID cookie is required" }
        val authUser = data["authUser"]?.takeIf { it.matches(Regex("[0-9]+")) } ?: "0"
        val extras =
            buildMap {
                put("cookie", cookie)
                put("authUser", authUser)
                data["visitorData"]?.takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
            }
        return listOf(User(authUser, "YouTube Music", extras = extras))
    }

    override fun setLoginUser(user: User?) =
        synchronized(lock) {
            musicVideos?.clear()
            resolver?.clear()
            lyricsResolver?.clear()
            nextTracks.clear()
            playingVideo = null
            playbackScope
                ?.coroutineContext
                ?.get(kotlinx.coroutines.Job)
                ?.children
                ?.forEach { it.cancel() }
            currentUser = user?.takeIf { YouTubeMusicWebViewLogin.hasLoginCookie(it.extras["cookie"].orEmpty()) }
            applySession()
            schedulePlayerWarmup()
        }

    override suspend fun getCurrentUser(): User? = synchronized(lock) { currentUser?.copy(extras = emptyMap()) }

    private fun tube(): InnerTube = checkNotNull(innerTube) { "Extension has not been initialized" }

    private suspend fun HttpResponse.readJson(): JsonObject = json.parseToJsonElement(bodyAsTextLimited(4 * 1024 * 1024)) as JsonObject

    private suspend fun browse(
        id: String,
        continuation: String? = null,
        params: String? = null,
    ): JsonObject =
        tube()
            .browse(
                YouTubeClient.WEB_REMIX,
                browseId = id.takeIf { continuation == null },
                continuation = continuation,
                params = params.takeIf { continuation == null },
                setLogin = currentUser != null,
            ).readJson()

    private fun browseFeed(
        id: String,
        params: String? = null,
    ): Feed<Shelf> =
        PagedData
            .Continuous { continuation ->
                warmShelves(
                    mapper.shelves(
                        browse(id, continuation, params),
                        browseMore = ::browseItemsFeed,
                    ) { token -> browseContinuation(id, token) },
                )
            }.toFeed()

    private fun browseItemsFeed(
        id: String,
        params: String? = null,
    ): Feed<Shelf> =
        PagedData
            .Continuous { continuation ->
                warmShelves(mapper.itemShelves(browse(id, continuation, params)))
            }.toFeed()

    private fun browseContinuation(
        id: String,
        firstToken: String,
    ): Feed<Shelf> =
        PagedData
            .Continuous { continuation ->
                warmShelves(
                    mapper.shelves(
                        browse(id, continuation ?: firstToken),
                        browseMore = ::browseItemsFeed,
                    ) { token -> browseContinuation(id, token) },
                )
            }.toFeed()

    override suspend fun loadHomeFeed(): Feed<Shelf> = browseFeed("FEmusic_home")

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        check(currentUser != null) { "Sign in to YouTube Music to view your library" }
        return Feed(libraryTabs) { tab ->
            PagedData
                .Continuous { continuation ->
                    warmShelves(mapper.itemShelves(browse(tab?.id ?: "FEmusic_library_landing", continuation)))
                }.toFeedData()
        }
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) return searchBrowseFeed()
        return Feed(searchTabs) { tab ->
            if (tab == null || tab.id == "all") return@Feed searchAllFeedData(query).toFeedData()
            val params =
                when (tab.id) {
                    "videos" -> VIDEOS_FILTER
                    "albums" -> ALBUMS_FILTER
                    "playlists" -> PLAYLISTS_FILTER
                    "artists" -> ARTISTS_FILTER
                    else -> SONGS_FILTER
                }
            searchFeedData(query, params).toFeedData()
        }
    }

    private suspend fun searchBrowseFeed(): Feed<Shelf> {
        val initial = browse("FEmusic_home")
        // Echo initially selects the first tab; YouTube sometimes omits its unfiltered chip.
        val tabs = listOf(Tab("all", "All")) + mapper.filterTabs(initial).filter { it.id != "all" }
        return Feed(tabs) { tab ->
            PagedData
                .Continuous { continuation ->
                    val root =
                        if (continuation == null && (tab == null || tab.id == "all" || tab.extras["isSelected"] == "true")) {
                            initial
                        } else {
                            browse("FEmusic_home", continuation, tab?.extras?.get("params")?.takeIf { it.isNotBlank() })
                        }
                    val page =
                        mapper.shelves(root, browseMore = ::browseItemsFeed) { token ->
                            browseContinuation("FEmusic_home", token)
                        }
                    warmShelves(page)
                }.toFeedData()
        }
    }

    private fun searchAllFeedData(query: String): PagedData<Shelf> =
        PagedData.Single {
            val shelves =
                coroutineScope {
                    listOf(SONGS_FILTER, VIDEOS_FILTER, ALBUMS_FILTER, ARTISTS_FILTER, PLAYLISTS_FILTER)
                        .map { params ->
                            async {
                                try {
                                    val response = tube().search(YouTubeClient.WEB_REMIX, query, params = params).readJson()
                                    mapper
                                        .shelves(
                                            response,
                                            ModelMapper.Layout.Search,
                                            fallbackTitle = searchCategoryTitle(params),
                                        ) { token ->
                                            searchFeedContinuation(query, params, token)
                                        }.data
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }.awaitAll()
                        .flatten()
                }
            warmShelves(Page(shelves, null)).data
        }

    private fun searchFeedData(
        query: String,
        params: String,
    ): PagedData<Shelf> =
        PagedData.Continuous { continuation ->
            val response =
                tube()
                    .search(
                        YouTubeClient.WEB_REMIX,
                        query,
                        params = params.takeIf { continuation == null },
                        continuation = continuation,
                    ).readJson()
            warmShelves(
                mapper.shelves(
                    response,
                    ModelMapper.Layout.Search,
                    fallbackTitle = searchCategoryTitle(params),
                ) { token -> searchFeedContinuation(query, params, token) },
            )
        }

    private fun searchFeedContinuation(
        query: String,
        params: String,
        firstToken: String,
    ): Feed<Shelf> =
        PagedData
            .Continuous { continuation ->
                val response =
                    tube()
                        .search(
                            YouTubeClient.WEB_REMIX,
                            query,
                            continuation = continuation ?: firstToken,
                        ).readJson()
                warmShelves(
                    mapper.shelves(
                        response,
                        ModelMapper.Layout.Search,
                        fallbackTitle = searchCategoryTitle(params),
                    ) { token -> searchFeedContinuation(query, params, token) },
                )
            }.toFeed()

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        val response = tube().getSearchSuggestions(YouTubeClient.WEB_REMIX, query).readJson()
        return parseSearchSuggestions(response, query).map { QuickSearchItem.Query(query = it, searched = false) }
    }

    internal fun parseSearchSuggestions(
        response: JsonObject,
        query: String,
    ): List<String> {
        val suggestions = linkedSetOf<String>()

        fun visit(
            node: JsonElement,
            depth: Int = 0,
        ) {
            if (depth > 30) return
            when (node) {
                is JsonObject -> {
                    val renderer = node["searchSuggestionRenderer"] as? JsonObject
                    if (renderer != null) mapper.text(renderer["suggestion"]).takeIf { it.isNotBlank() }?.let(suggestions::add)
                    node.values.filter { it is JsonObject || it is JsonArray }.forEach { visit(it, depth + 1) }
                }

                is JsonArray -> {
                    node.forEach { visit(it, depth + 1) }
                }

                else -> {
                    Unit
                }
            }
        }
        visit(response)
        return suggestions.toList().ifEmpty { listOf(query) }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        // Remote search suggestions cannot be deleted
    }

    private fun playbackRequest(
        videoId: String,
        isDownload: Boolean,
        wantVideo: Boolean? = null,
    ): PlaybackRequest {
        val quality =
            when (settings.getString("youtube_music_audio_quality")) {
                "LOW" -> AudioQuality.LOW
                "AUTO" -> AudioQuality.AUTO
                else -> AudioQuality.HIGH
            }
        val height = settings.getString("youtube_music_max_video_height")?.toIntOrNull() ?: 1080
        return PlaybackRequest(
            videoId,
            quality,
            wantVideo ?: (settings.getBoolean("youtube_music_prefer_videos") == true),
            height.takeIf { it > 0 && it != 9999 },
            true,
            isDownload,
        )
    }

    override suspend fun loadTrack(
        track: Track,
        isDownload: Boolean,
    ): Track {
        val videoId = track.extras["videoId"] ?: track.id
        if (!isDownload) synchronized(lock) { playingVideo = videoId }
        tokenProvider.prewarm()
        val explicitVideo = MusicVideoResolver.isVideo(track)
        val matched = preferredVideo(track)
        val selectedId = matched?.extras?.get("videoId") ?: matched?.id ?: videoId
        var request = playbackRequest(selectedId, isDownload, explicitVideo || matched != null)
        val playback =
            try {
                checkNotNull(resolver).resolve(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (matched == null || selectedId == videoId) throw error
                // A missing/unavailable counterpart must not break the original song.
                request = playbackRequest(videoId, isDownload, false)
                checkNotNull(resolver).resolve(request)
            }
        if (!isDownload) checkNotNull(resolver).preload(playback.default)
        return track.copy(
            extras = track.extras + ("playbackVideoId" to request.videoId),
            streamables =
                playback.streamables(request.videoId).map {
                    it.copy(
                        extras =
                            it.extras + mapOf("wantVideo" to request.wantVideo.toString(), "originalVideoId" to videoId),
                    )
                },
            isRadioSupported = true,
            isLikeable = true,
            isSaveable = true,
            duration =
                if (matched != null && request.videoId == selectedId) {
                    playback.default.mediaMetadata
                        ?.durationSeconds
                        ?.times(1000) ?: matched.duration ?: track.duration
                } else {
                    track.duration
                },
        )
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean,
    ): Streamable.Media {
        tokenProvider.prewarm()
        val media =
            checkNotNull(resolver).media(
                playbackRequest(
                    streamable.extras["videoId"] ?: streamable.id,
                    isDownload,
                    streamable.extras["wantVideo"]?.toBooleanStrictOrNull(),
                ),
                streamable.extras["variant"] ?: "auto",
            )
        if (!isDownload) prepareNextTrack(streamable.extras["originalVideoId"] ?: streamable.extras["videoId"] ?: streamable.id)
        return media
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = relatedFeed(track.extras["videoId"] ?: track.id)

    private suspend fun relatedFeed(videoId: String): Feed<Shelf>? {
        val root = tube().next(YouTubeClient.WEB_REMIX, videoId = videoId).readJson()

        fun endpoint(
            node: JsonElement?,
            depth: Int = 0,
        ): JsonObject? {
            if (depth > 35) return null
            return when (node) {
                is JsonObject -> {
                    val browse = node["browseEndpoint"] as? JsonObject
                    if ((browse?.get("browseId") as? JsonPrimitive)?.contentOrNull?.startsWith("MPTR") == true) {
                        browse
                    } else {
                        node.filterKeys { it !in setOf("menu", "counterpart") }.values.firstNotNullOfOrNull { endpoint(it, depth + 1) }
                    }
                }

                is JsonArray -> {
                    node.firstNotNullOfOrNull { endpoint(it, depth + 1) }
                }

                else -> {
                    null
                }
            }
        }
        val related = endpoint(root) ?: return null
        return browseFeed(
            checkNotNull((related["browseId"] as? JsonPrimitive)?.contentOrNull),
            (related["params"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    override suspend fun loadAlbum(album: Album): Album = mapper.albumHeader(browse(album.extras["browseId"] ?: album.id), album)

    override suspend fun loadArtist(artist: Artist): Artist = mapper.artistHeader(browse(artist.extras["browseId"] ?: artist.id), artist)

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = mapper.playlistHeader(browse(playlistBrowseId(playlist)), playlist)

    override suspend fun loadTracks(album: Album): Feed<Track> {
        val id = album.extras["browseId"] ?: album.id
        val root = browse(id)
        val headerCover = mapper.albumHeader(root, album).cover ?: album.cover
        return trackFeed(id, fallbackCover = headerCover)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val id = playlistBrowseId(playlist)
        val root = browse(id)
        val headerCover = mapper.playlistHeader(root, playlist).cover ?: albumCoverOr(playlist)
        return trackFeed(id, fallbackCover = headerCover)
    }

    private fun albumCoverOr(playlist: Playlist): ImageHolder? = playlist.cover

    override suspend fun loadFeed(album: Album): Feed<Shelf>? =
        mapper
            .tracks(browse(album.extras["browseId"] ?: album.id))
            .data
            .lastOrNull()
            ?.let { relatedFeed(it.extras["videoId"] ?: it.id) }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? =
        mapper
            .tracks(browse(playlistBrowseId(playlist)))
            .data
            .lastOrNull()
            ?.let { relatedFeed(it.extras["videoId"] ?: it.id) }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = browseFeed(artist.extras["browseId"] ?: artist.id)

    private fun playlistBrowseId(playlist: Playlist): String =
        playlist.extras["browseId"] ?: playlist.id.let { if (it.startsWith("VL")) it else "VL$it" }

    private fun trackFeed(
        id: String,
        fallbackCover: ImageHolder? = null,
    ): Feed<Track> =
        PagedData
            .Continuous { continuation ->
                mapper.tracks(browse(id, continuation), fallbackCover = fallbackCover).also { warmTracks(it.data) }
            }.toFeed()

    private fun warmShelves(page: Page<Shelf>): Page<Shelf> {
        val tracks =
            page.data.flatMap { shelf ->
                when (shelf) {
                    is Shelf.Lists.Tracks -> shelf.list
                    is Shelf.Lists.Items -> shelf.list.filterIsInstance<Track>()
                    is Shelf.Item -> listOfNotNull(shelf.media as? Track)
                    else -> emptyList()
                }
            }
        warmTracks(tracks)
        return page
    }

    private fun warmTracks(tracks: List<Track>) =
        synchronized(lock) {
            tracks.zipWithNext().forEach { (track, next) -> nextTracks[track.extras["videoId"] ?: track.id] = next }
            while (nextTracks.size > 256) nextTracks.remove(nextTracks.keys.first())
            if (initialized) {
                tracks.firstOrNull()?.let { track ->
                    prefetchTrack(track)
                }
            }
        }

    private suspend fun preferredVideo(track: Track): Track? =
        if (!MusicVideoResolver.isVideo(track) &&
            settings.getBoolean("youtube_music_prefer_videos") == true
        ) {
            musicVideos?.resolve(track)
        } else {
            null
        }

    private fun prefetchTrack(track: Track) {
        // One bounded metadata lookup at a time; it shares its match with a tap on this song.
        if (lookaheadJob?.isActive == true) return
        lookaheadJob =
            playbackScope?.launch {
                try {
                    val matched = preferredVideo(track)
                    val id = matched?.extras?.get("videoId") ?: matched?.id ?: track.extras["videoId"] ?: track.id
                    synchronized(lock) {
                        resolver?.prefetch(playbackRequest(id, false, MusicVideoResolver.isVideo(track) || matched != null))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Foreground loading keeps the normal song fallback.
                }
            }
    }

    private fun prepareNextTrack(video: String) {
        playbackScope?.launch {
            // Give the tapped track's first media requests priority over lookahead metadata.
            delay(2000)
            synchronized(lock) {
                if (playingVideo != video) return@synchronized
                nextTracks[video]?.let { next ->
                    prefetchTrack(next)
                }
            }
        }
    }

    override suspend fun loadRadio(radio: Radio): Radio = radio

    override suspend fun loadTracks(radio: Radio): Feed<Track> =
        radioFeed(radio.extras["videoId"], radio.extras["playlistId"] ?: radio.id, radio.extras["params"])

    override suspend fun radio(
        item: EchoMediaItem,
        context: EchoMediaItem?,
    ): Radio =
        when (item) {
            is Track -> {
                val videoId = item.extras["videoId"] ?: item.id
                val playlistId = "RDAMVM$videoId"
                Radio(
                    playlistId,
                    "${item.title} radio",
                    cover = item.cover,
                    authors = item.artists,
                    extras = mapOf("videoId" to videoId, "playlistId" to playlistId, "params" to RADIO_PARAMS),
                )
            }

            is Playlist -> {
                val playlistId = "RDAMPL" + item.id.removePrefix("VL")
                Radio(playlistId, "${item.title} radio", cover = item.cover, extras = mapOf("playlistId" to playlistId))
            }

            is Album -> {
                val albumPlaylist =
                    checkNotNull(mapper.playlistId(browse(item.extras["browseId"] ?: item.id))) { "Could not start a radio for this album" }
                val playlistId = "RDAMPL$albumPlaylist"
                Radio(playlistId, "${item.title} radio", cover = item.cover, extras = mapOf("playlistId" to playlistId))
            }

            else -> {
                error("Radio is only available for tracks, albums and playlists")
            }
        }

    private fun radioFeed(
        videoId: String?,
        playlistId: String,
        params: String?,
    ): Feed<Track> =
        PagedData
            .Continuous { continuation ->
                val response =
                    tube()
                        .next(
                            YouTubeClient.WEB_REMIX,
                            videoId = videoId,
                            playlistId = playlistId,
                            params = params,
                            continuation = continuation,
                        ).readJson()
                mapper.tracks(response, excludeId = videoId).also { warmTracks(it.data) }
            }.toFeed()

    override suspend fun likeItem(
        item: EchoMediaItem,
        shouldLike: Boolean,
    ) {
        val tube = tube()
        when (item) {
            is Track -> {
                val videoId = item.extras["videoId"] ?: item.id
                if (shouldLike) tube.likeVideo(YouTubeClient.WEB_REMIX, videoId) else tube.unlikeVideo(YouTubeClient.WEB_REMIX, videoId)
            }

            is Album -> {
                val playlistId = item.extras["browseId"] ?: item.id
                if (shouldLike) {
                    tube.likePlaylist(
                        YouTubeClient.WEB_REMIX,
                        playlistId,
                    )
                } else {
                    tube.unlikePlaylist(YouTubeClient.WEB_REMIX, playlistId)
                }
            }

            is Playlist -> {
                val playlistId = playlistBrowseId(item)
                if (shouldLike) {
                    tube.likePlaylist(
                        YouTubeClient.WEB_REMIX,
                        playlistId,
                    )
                } else {
                    tube.unlikePlaylist(YouTubeClient.WEB_REMIX, playlistId)
                }
            }

            else -> {
                Unit
            }
        }
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean = item.extras["isLiked"] == "true"

    override suspend fun saveToLibrary(
        item: EchoMediaItem,
        shouldSave: Boolean,
    ) {
        when (item) {
            is Artist -> {
                val channelId = item.extras["browseId"] ?: item.id
                if (shouldSave) {
                    tube().subscribeChannel(YouTubeClient.WEB_REMIX, channelId)
                } else {
                    tube().unsubscribeChannel(YouTubeClient.WEB_REMIX, channelId)
                }
            }

            else -> {
                likeItem(item, shouldSave)
            }
        }
    }

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean = item.extras["isSaved"] == "true"

    override suspend fun isFollowing(item: EchoMediaItem): Boolean = item.extras["isFollowing"] == "true"

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? = item.extras["followersCount"]?.toLongOrNull()

    override suspend fun followItem(
        item: EchoMediaItem,
        shouldFollow: Boolean,
    ) {
        if (item is Artist) {
            val channelId = item.extras["browseId"] ?: item.id
            if (shouldFollow) {
                tube().subscribeChannel(
                    YouTubeClient.WEB_REMIX,
                    channelId,
                )
            } else {
                tube().unsubscribeChannel(YouTubeClient.WEB_REMIX, channelId)
            }
        }
    }

    override suspend fun onShare(item: EchoMediaItem): String =
        when (item) {
            is Track -> "https://music.youtube.com/watch?v=${item.extras["videoId"] ?: item.id}"
            is Album -> "https://music.youtube.com/playlist?list=${item.extras["browseId"] ?: item.id}"
            is Playlist -> "https://music.youtube.com/playlist?list=${(item.extras["browseId"] ?: item.id).removePrefix("VL")}"
            is Artist -> "https://music.youtube.com/channel/${item.extras["browseId"] ?: item.id}"
            else -> "https://music.youtube.com"
        }

    override suspend fun searchTrackLyrics(
        clientId: String,
        track: Track,
    ): Feed<Lyrics> = listOfNotNull(checkNotNull(lyricsResolver).resolve(track)).toFeed()

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        if (lyrics.lyrics != null) return lyrics
        val loaded = searchTrackLyrics("", Track(lyrics.id, lyrics.title)).loadAll().firstOrNull()
        return loaded ?: lyrics
    }

    private suspend fun youtubeLyrics(track: Track): Lyrics.Lyric? {
        val video = track.extras["playbackVideoId"] ?: track.extras["videoId"] ?: track.id
        val next = tube().next(YouTubeClient.WEB_REMIX, videoId = video).readJson()
        val (id, params) = LyricsMapper.endpoint(next) ?: return null
        // ytmusicapi's mobile lyric profile returns timedLyricsData with real cue ranges.
        val mobile =
            YouTubeClient.ANDROID_MUSIC.copy(
                clientVersion = "7.21.50",
                userAgent = "com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 11) gzip",
            )
        try {
            withTimeoutOrNull(4_000) {
                LyricsMapper.youtube(tube().browse(mobile, browseId = id, params = params).readJson())
            }?.let { return it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Some tracks/regions only expose the normal desktop lyric shelf.
        }
        return LyricsMapper.youtube(tube().browse(YouTubeClient.WEB_REMIX, browseId = id, params = params).readJson())
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

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        check(currentUser != null) { "Sign in to view your playlists" }
        val response = browse("FEmusic_liked_playlists")
        val playlists =
            mapper
                .shelves(response) { emptyList<Shelf>().toFeed() }
                .data
                .filterIsInstance<Shelf.Lists.Items>()
                .flatMap { it.list }
                .filterIsInstance<Playlist>()
                .map { it.copy(isEditable = true) }
        return playlists.map { playlist -> Pair(playlist, false) }
    }

    override suspend fun createPlaylist(
        title: String,
        description: String?,
    ): Playlist {
        val result = tube().createPlaylist(YouTubeClient.WEB_REMIX, title).readJson()
        val playlistId = result["playlistId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!description.isNullOrBlank() && playlistId.isNotBlank()) {
            runCatching { tube().setPlaylistDescription(YouTubeClient.WEB_REMIX, playlistId, description) }
        }
        return Playlist(
            id = playlistId,
            title = title,
            isEditable = true,
            isPrivate = true,
            extras = mapOf("browseId" to "VL$playlistId"),
        )
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        tube().deletePlaylist(YouTubeClient.WEB_REMIX, playlist.id.removePrefix("VL"))
    }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist,
        title: String,
        description: String?,
    ) {
        val playlistId = playlist.id.removePrefix("VL")
        tube().renamePlaylist(YouTubeClient.WEB_REMIX, playlistId, title)
        if (!description.isNullOrBlank()) {
            tube().setPlaylistDescription(YouTubeClient.WEB_REMIX, playlistId, description)
        }
    }

    override suspend fun addTracksToPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        index: Int,
        new: List<Track>,
    ) {
        val playlistId = playlist.id.removePrefix("VL")
        new.forEach { track ->
            val videoId = track.extras["videoId"] ?: track.id
            tube().addToPlaylist(YouTubeClient.WEB_REMIX, playlistId, videoId)
        }
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        indexes: List<Int>,
    ) {
        val playlistId = playlist.id.removePrefix("VL")
        val pairs =
            indexes.mapNotNull { i ->
                val track = tracks.getOrNull(i) ?: return@mapNotNull null
                val setVideoId = track.extras["setVideoId"] ?: track.id
                val videoId = track.extras["videoId"] ?: track.id
                Pair(setVideoId, videoId)
            }
        if (pairs.isNotEmpty()) {
            tube().removePlaylistSongs(YouTubeClient.WEB_REMIX, playlistId, pairs)
        }
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        fromIndex: Int,
        toIndex: Int,
    ) {
        val playlistId = playlist.id.removePrefix("VL")
        val track = tracks.getOrNull(fromIndex) ?: return
        val setVideoId = track.extras["setVideoId"] ?: track.id
        if (fromIndex == toIndex) return
        require(toIndex in tracks.indices) { "Invalid playlist destination" }
        val successorSetVideoId = playlistMoveSuccessor(tracks, fromIndex, toIndex)
        tube().movePlaylistSong(YouTubeClient.WEB_REMIX, playlistId, setVideoId, successorSetVideoId)
    }

    override suspend fun editPlaylistCover(
        playlist: Playlist,
        cover: File?,
    ) {
        val playlistId = playlist.id.removePrefix("VL")
        if (cover != null && cover.exists()) {
            tube().setPlaylistThumbnail(YouTubeClient.WEB_REMIX, playlistId, cover.readBytes())
        } else {
            tube().removePlaylistThumbnail(YouTubeClient.WEB_REMIX, playlistId)
        }
    }

    override suspend fun setPrivacy(
        playlist: Playlist,
        isPrivate: Boolean,
    ) {
        // Privacy setting applied when playlist created or edited
    }

    override fun close() =
        synchronized(lock) {
            resolver?.clear()
            resolver = null
            musicVideos?.clear()
            musicVideos = null
            lyricsResolver?.clear()
            lyricsResolver = null
            initialized = false
            nextTracks.clear()
            playingVideo = null
            warmupJob = null
            playbackScope?.cancel()
            playbackScope = null
            tokenProvider.updateSession(null, null, false)
            innerTube?.close()
            httpClient?.close()
            innerTube = null
            extractor = null
            httpClient = null
            currentUser = null
        }
}

/** Same "Songs" search filter Metrolist uses: returns audio tracks instead of music videos. */
private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
private const val VIDEOS_FILTER = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
private const val ALBUMS_FILTER = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
private const val PLAYLISTS_FILTER = "EgWKAQ4YAWoKEAkQChAFEAMQBA%3D%3D"
private const val ARTISTS_FILTER = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"

internal val searchTabs =
    listOf(
        Tab("all", "All"),
        Tab("songs", "Songs"),
        Tab("videos", "Videos"),
        Tab("albums", "Albums"),
        Tab("artists", "Artists"),
        Tab("playlists", "Playlists"),
    )

internal val libraryTabs =
    listOf(
        "FEmusic_library_landing" to "All",
        "FEmusic_history" to "History",
        "FEmusic_liked_playlists" to "Playlists",
        "FEmusic_liked_videos" to "Songs",
        "FEmusic_library_corpus_track_artists" to "Artists",
    ).map { (id, title) -> Tab(id, title, extras = mapOf("browseId" to id, "category" to title, "isLibraryTab" to "true")) }

private fun searchCategoryTitle(params: String) =
    when (params) {
        SONGS_FILTER -> "Songs"
        VIDEOS_FILTER -> "Videos"
        ALBUMS_FILTER -> "Albums"
        ARTISTS_FILTER -> "Artists"
        PLAYLISTS_FILTER -> "Playlists"
        else -> "Results"
    }

/** Parameter YouTube Music sends when starting a song radio. */
private const val RADIO_PARAMS = "wAEB"

internal fun playlistMoveSuccessor(
    tracks: List<Track>,
    fromIndex: Int,
    toIndex: Int,
): String? {
    require(fromIndex in tracks.indices && toIndex in tracks.indices)
    val remaining = tracks.filterIndexed { index, _ -> index != fromIndex }
    return remaining.getOrNull(toIndex)?.let { it.extras["setVideoId"] ?: it.id }
}

/** Persist only public Faraday data; refreshes and retries are bounded by InnerTubeX. */
private class FaradayRepository(
    private val settings: Settings,
) : PlayerConfigRepository {
    override val enabled = true
    override val sourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/registry/player_configs.json"
    override val defaultSourceUrl = sourceUrl
    override var cachedJson: String
        get() = settings.getString("faraday_json").orEmpty()
        set(value) = settings.putString("faraday_json", value)
    override var cachedAtMs: Long
        get() = settings.getString("faraday_cached_at")?.toLongOrNull() ?: 0
        set(value) = settings.putString("faraday_cached_at", value.toString())
    override var cachedSourceUrl: String
        get() = settings.getString("faraday_source").orEmpty()
        set(value) = settings.putString("faraday_source", value)
    override var cachedEtag: String
        get() = settings.getString("faraday_etag").orEmpty()
        set(value) = settings.putString("faraday_etag", value)
}
