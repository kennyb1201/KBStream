package com.kennyb1201.kbstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Surface
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.actor.ActorScreen
import com.kennyb1201.kbstream.ui.addons.AddonsScreen
import com.kennyb1201.kbstream.ui.addons.CatalogManagerScreen
import com.kennyb1201.kbstream.ui.collection.CollectionScreen
import com.kennyb1201.kbstream.ui.detail.DetailScreen
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.home.HomeScreen
import com.kennyb1201.kbstream.ui.iptv.GuideScreen
import com.kennyb1201.kbstream.ui.iptv.IptvViewModel
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.player.PlayerCastMember
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import com.kennyb1201.kbstream.ui.settings.SettingsScreen
import com.kennyb1201.kbstream.ui.search.SearchScreen
import com.kennyb1201.kbstream.ui.simkl.SimklConnectScreen
import com.kennyb1201.kbstream.ui.streams.StreamsScreen
import com.kennyb1201.kbstream.ui.streams.StreamsViewModel
import com.kennyb1201.kbstream.ui.studio.StudioScreen
import com.kennyb1201.kbstream.ui.tag.TagScreen
import com.kennyb1201.kbstream.ui.theme.KBStreamTheme

sealed class Screen {

    object Home : Screen()

    object Addons : Screen()

    object CatalogManager : Screen()

    object Search : Screen()

    object Simkl : Screen()

    object Guide : Screen()

    object Settings : Screen()

    data class Detail(
        val type: String,
        val id: String,
        val pendingTarget: StreamsTarget? = null,
        val itemPoster: String? = null,
        val returnTo: Screen = Home
    ) : Screen()

    data class Actor(
        val personId: Int,
        val returnTo: Screen = Home
    ) : Screen()

    data class Studio(
        val id: Int,
        val name: String,
        val isNetwork: Boolean,
        val returnTo: Screen = Home
    ) : Screen()

    data class Tag(
        val id: Int,
        val name: String,
        val isKeyword: Boolean,
        val mediaType: String,
        val returnTo: Screen = Home
    ) : Screen()

    data class Collection(
        val id: Int,
        val name: String,
        val returnTo: Screen = Home
    ) : Screen()

    data class Streams(
        val target: StreamsTarget,
        val parentId: String,
        val returnTo: Screen = Home,
        val parentType: String,
        val itemPoster: String?,
        val backdropUrl: String? = null,
        val clearLogoUrl: String? = null,
        val overview: String? = null,
        val cast: List<TmdbCastMember> = emptyList()
    ) : Screen()

    data class Player(
        val url: String,
        val audioUrl: String? = null,
        val parentId: String,
        val parentType: String,
        val season: Int?,
        val episode: Int?,
        val episodeStreamId: String?,
        val episodeTitle: String? = null,
        val itemName: String,
        val itemPoster: String?,
        val clearLogoUrl: String? = null,
        val backdropUrl: String? = null,
        val overview: String? = null,
        val cast: List<PlayerCastMember> = emptyList(),
        val startPositionMs: Long,
        val fromActorReturn: Boolean = false,
        val returnTo: Screen = Home,
        val sources: List<Stream> = emptyList(),
        val streamHeaders: Map<String, String> = emptyMap(),
        val totalEpisodesInSeason: Int? = null,
        val drmLicenseUrl: String? = null,
        val drmHeaders: Map<String, String> = emptyMap()
    ) : Screen()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        setContent {
            KBStreamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AppRoot()
                    }
                }
            }
    }
}

@Composable
fun AppRoot() {

    var screen by remember {
        mutableStateOf<Screen>(Screen.Home)
    }

    // Guard against the autoplay loop: StreamsScreen auto-selects the top
    // stream once, which navigates to the player. When the user backs OUT of
    // the player, the return-to-streams navigation re-runs the streams loader,
    // whose autoplay would otherwise relaunch the player immediately — trapping
    // the user in player -> back -> loading -> player. Remember the target that
    // has already auto-played and skip autoplay for it from then on (a manual
    // source pick still works; a fresh target still autoplays).
    var autoPlayedStreamKeys by rememberSaveable {
        mutableStateOf(listOf<String>())
    }

    val context = LocalContext.current

    val tmdbRepository = remember {
        TmdbRepository(context)
    }

    val iptvViewModel: IptvViewModel = viewModel()

    BackHandler(
        enabled = screen != Screen.Home
    ) {
        screen = when (val current = screen) {

            is Screen.CatalogManager ->
                Screen.Addons

            is Screen.Addons ->
                Screen.Settings

            is Screen.Player ->
                if (current.parentType == "channel") {
                    Screen.Guide
                } else {
                    current.returnTo
                }

            is Screen.Streams ->
                if (current.parentType == "channel") {
                    Screen.Guide
                } else {
                    current.returnTo
                }

            is Screen.Actor ->
                current.returnTo

            is Screen.Studio ->
                current.returnTo

            is Screen.Tag ->
                current.returnTo

            is Screen.Collection ->
                current.returnTo

            else ->
                Screen.Home
        }
    }

    when (val current = screen) {

        is Screen.Home -> {

            HomeScreen(
                onItemClick = { meta: MetaPreview ->
                    screen = Screen.Detail(
                        meta.type,
                        meta.id,
                        returnTo = Screen.Home
                    )
                },

                onOpenDetailTarget = {
                        meta,
                        target,
                        poster ->

                    screen = Screen.Detail(
                        meta.type,
                        meta.id,
                        target,
                        poster ?: meta.poster,
                        Screen.Home
                    )
                },

                onSearch = {
                    screen = Screen.Search
                },

                onOpenGuide = {
                    screen = Screen.Guide
                },

                onOpenSettings = {
                    screen = Screen.Settings
                }
            )
        }

        is Screen.Settings -> {
            SettingsScreen(
                onBack = { screen = Screen.Home },
                onOpenAddons = { screen = Screen.Addons },
                onOpenSimkl = { screen = Screen.Simkl }
            )
        }

        is Screen.Addons -> {

            AddonsScreen(
                onBack = {
                    screen = Screen.Home
                },

                onOpenCatalogManager = {
                    screen = Screen.CatalogManager
                }
            )
        }

        is Screen.CatalogManager -> {

            CatalogManagerScreen(
                onBack = {
                    screen = Screen.Addons
                }
            )
        }

        is Screen.Search -> {

            SearchScreen(
                onItemClick = {
                        meta: MetaPreview ->

                    screen = Screen.Detail(
                        meta.type,
                        meta.id,
                        returnTo = Screen.Search
                    )
                },

                onPersonClick = { person ->
                    screen = Screen.Actor(
                        person.id,
                        Screen.Search
                    )
                },

                onStudioClick = { studio ->
                    screen = Screen.Studio(
                        studio.id,
                        studio.name,
                        false,
                        Screen.Search
                    )
                },

                onCollectionClick = { collection ->
                    screen = Screen.Collection(
                        collection.id,
                        collection.name,
                        Screen.Search
                    )
                }
            )
        }

        is Screen.Simkl -> {

            SimklConnectScreen(
                onBackToHome = {
                    screen = Screen.Settings
                }
            )
        }

        is Screen.Guide -> {

            GuideScreen(
                viewModel = iptvViewModel,
                defaultPlaylistUrl = "",
                defaultEpgUrl = "",
                defaultPlaylistName = "Live TV",

                onPlayChannel = {
                        channelWithEpg ->

                    val channel =
                        channelWithEpg.channel

                    val channelName =
                        channel.displayName
                            .ifBlank {
                                "Live Channel"
                            }

                    val channelId =
                        channel.id
                            .ifBlank {
                                channel.streamUrl
                            }

                    val poster =
                        channel.logoUrl
                            ?: channelWithEpg
                                .epgChannel
                                ?.iconUrl

                    val directSource =
                        Stream(
                            name = channelName,
                            title = channelName,
                            url = channel.streamUrl
                        )

                    screen = Screen.Player(
                        url = channel.streamUrl,
                        audioUrl = directSource.audioUrl,
                        parentId = channelId,
                        parentType = "channel",
                        season = null,
                        episode = null,
                        episodeStreamId = channel.id,
                        itemName = channelName,
                        itemPoster = poster,
                        startPositionMs = 0L,
                        sources = listOf(
                            directSource
                        ),
                        streamHeaders = channel.headers,
                        returnTo = Screen.Guide
                    )
                }
            )
        }

        is Screen.Detail -> {

            DetailScreen(
                type = current.type,
                id = current.id,
                initialTarget = current.pendingTarget,
                initialPoster = current.itemPoster,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                },

                onNavigateActor = { personId ->

                    screen = Screen.Actor(
                        personId,
                        Screen.Detail(current.type, current.id, current.pendingTarget, current.itemPoster)
                    )
                },

                onNavigateStudio = {
                        id,
                        name,
                        isNetwork ->

                    screen = Screen.Studio(
                        id,
                        name,
                        isNetwork,
                        Screen.Detail(current.type, current.id, current.pendingTarget, current.itemPoster)
                    )
                },

                onNavigateTag = {
                        id,
                        name,
                        isKeyword,
                        type ->

                    screen = Screen.Tag(
                        id,
                        name,
                        isKeyword,
                        type,
                        Screen.Detail(current.type, current.id, current.pendingTarget, current.itemPoster)
                    )
                },

                onNavigateStreams = {
                        target,
                        parentId,
                        parentType,
                        poster,
                        backdropUrl,
                        clearLogoUrl,
                        overview,
                        cast ->

                    screen = Screen.Streams(
                        target = target,
                        parentId = parentId,
                        returnTo = current,
                        parentType = parentType,
                        itemPoster = poster,
                        backdropUrl = backdropUrl,
                        clearLogoUrl = clearLogoUrl,
                        overview = overview,
                        cast = cast
                    )
                }
            )
        }

        is Screen.Actor -> {

            ActorScreen(
                actorId = current.personId,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                }
            )
        }

        is Screen.Studio -> {

            StudioScreen(
                current.id,
                current.name,
                current.isNetwork,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                }
            )
        }

        is Screen.Tag -> {

            TagScreen(
                current.id,
                current.name,
                current.isKeyword,
                current.mediaType,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                }
            )
        }

        is Screen.Collection -> {

            CollectionScreen(
                current.id,
                current.name,
                tmdbRepository,

                onNavigateDetail = {
                        type,
                        id ->                        screen = Screen.Detail(
                            type,
                            id,
                            returnTo = current
                        )
                }
            )
        }

        is Screen.Streams -> {

            val streamsViewModel: StreamsViewModel =
                viewModel()

            LaunchedEffect(
                current.target.contentType,
                current.target.streamId
            ) {
                streamsViewModel.load(
                    current.target.contentType,
                    current.target.streamId
                )
            }

            val streamKey =
                "${current.target.contentType}:" +
                    current.target.streamId

            StreamsScreen(
                title = current.target.title,
                displayName = current.target.displayName,
                season = current.target.season,
                episode = current.target.episode,
                backdropUrl = current.backdropUrl,
                clearLogoUrl = current.clearLogoUrl,
                suppressAutoSelect =
                    streamKey in autoPlayedStreamKeys,

                onStreamSelected = {
                        stream,
                        allSources ->

                    val streamUrl =
                        stream.url.orEmpty()

                    if (streamUrl.isNotBlank()) {

                        screen = Screen.Player(
                            url = streamUrl,
                            audioUrl = stream.audioUrl,
                            parentId = current.parentId,
                            parentType = current.parentType,
                            season = current.target.season,
                            episode = current.target.episode,
                            episodeStreamId =
                                current.target.streamId,
                            episodeTitle = current.target.title
                                .substringAfterLast("•", "")
                                .trim()
                                .takeIf { it.isNotBlank() && it != current.target.title },
                            itemName =
                                current.target.displayName,
                            itemPoster = current.itemPoster,
                            clearLogoUrl = current.clearLogoUrl,
                            backdropUrl = current.backdropUrl,
                            overview = current.overview,
                            cast = current.cast.map { member ->
                                PlayerCastMember(
                                    id = member.id,
                                    name = member.name,
                                    character = member.character,
                                    profilePath =
                                        member.profilePath?.let {
                                            TmdbRepository.PROFILE_BASE + it
                                        }
                                )
                            },
                            startPositionMs =
                                current.target.resumePositionMs,
                            returnTo = current.returnTo,
                            sources = allSources,
                            totalEpisodesInSeason =
                                current.target.totalEpisodesInSeason,
                            drmLicenseUrl = stream.drm?.licenseUrl,
                            drmHeaders = stream.drm?.headers.orEmpty()
                        )
                    }
                },

                viewModel = streamsViewModel
            )
        }

        is Screen.Player -> {
            val context = LocalContext.current
            val playerResultLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { result ->
                // Check shared state first (more reliable than activity results)
                val next = com.kennyb1201.kbstream.ui.player.NextEpisodeResult.consume()
                if (next != null) {                        screen = Screen.Streams(
                            target = StreamsTarget(
                                contentType = current.parentType,
                                streamId = next.streamId,
                            title = next.title,
                            displayName = current.itemName,
                            season = next.season,
                            episode = next.episode,
                            resumePositionMs = 0L,
                            totalEpisodesInSeason = current.totalEpisodesInSeason
                        ),
                        parentId = current.parentId,
                        returnTo = current.returnTo,
                        parentType = current.parentType,
                        itemPoster = current.itemPoster,
                        backdropUrl = current.backdropUrl,
                        clearLogoUrl = current.clearLogoUrl,
                        overview = current.overview,
                        cast = current.cast.map { member ->
                            TmdbCastMember(
                                id = member.id,
                                name = member.name,
                                character = member.character,
                                profilePath = member.profilePath?.removePrefix(TmdbRepository.PROFILE_BASE)
                            )
                        }
                    )
                } else {
                    val data = result.data
                    val action = data?.getStringExtra("player_result_action")
                    when (action) {
                        "next_episode" -> {
                            val nextEpisode = data.getIntExtra("next_episode", -1).takeIf { it >= 0 }
                            val nextSeason = data.getIntExtra("next_season", -1).takeIf { it >= 0 }
                            val nextTitle = data.getStringExtra("next_title")
                            val nextStreamId = data.getStringExtra("next_stream_id")
                            screen = Screen.Streams(
                                target = StreamsTarget(
                                    contentType = current.parentType,
                                    streamId = nextStreamId.orEmpty(),
                                    title = nextTitle.orEmpty(),
                                    displayName = current.itemName,
                                    season = nextSeason,
                                    episode = nextEpisode,
                                    resumePositionMs = 0L,
                                    totalEpisodesInSeason = current.totalEpisodesInSeason
                                ),
                            parentId = current.parentId,
                            returnTo = current.returnTo,
                            parentType = current.parentType,
                            itemPoster = current.itemPoster,
                                backdropUrl = current.backdropUrl,
                                clearLogoUrl = current.clearLogoUrl,
                                overview = current.overview,
                                cast = current.cast.map { member ->
                                    TmdbCastMember(
                                        id = member.id,
                                        name = member.name,
                                        character = member.character,
                                        profilePath = member.profilePath?.removePrefix(TmdbRepository.PROFILE_BASE)
                                    )
                                }
                            )
                        }
                    "navigate_actor" -> {
                        val personId = data.getIntExtra("actor_person_id", -1)
                        val resumePos = data.getLongExtra("actor_resume_position_ms", 0L)
                        if (personId > 0) {
                            screen = Screen.Actor(
                                personId = personId,
                                returnTo = current.copy(
                                    startPositionMs = resumePos,
                                    fromActorReturn = true
                                )
                            )
                        }
                    }
                    else -> {
                        // Normal BACK exit from player. Mark this target so the
                        // streams screen never re-auto-selects it.
                        val exitedKey =
                            "${current.parentType}:${current.episodeStreamId}"
                        autoPlayedStreamKeys =
                            (autoPlayedStreamKeys + exitedKey).distinct()
                        if (current.parentType == "channel") {
                            screen = Screen.Guide
                        } else if (AppPreferences.getAutoSelectStream(context)) {
                            screen = current.returnTo
                        } else {
                            // Auto-select is off: the user chose a source manually,
                            // so return to the picker for that target.
                            screen = Screen.Streams(
                                target = StreamsTarget(
                                    contentType = current.parentType,
                                    streamId = current.episodeStreamId.orEmpty(),
                                    title = current.episodeTitle.orEmpty().ifBlank { current.itemName },
                                    displayName = current.itemName,
                                    season = current.season,
                                    episode = current.episode,
                                    resumePositionMs = current.startPositionMs,
                                    totalEpisodesInSeason = current.totalEpisodesInSeason
                                ),
                            parentId = current.parentId,
                            returnTo = current.returnTo,
                            parentType = current.parentType,
                            itemPoster = current.itemPoster,
                                clearLogoUrl = current.clearLogoUrl,
                                overview = current.overview,
                                cast = current.cast.map { member ->
                                    TmdbCastMember(
                                        id = member.id,
                                        name = member.name,
                                        character = member.character,
                                        profilePath = member.profilePath?.removePrefix(TmdbRepository.PROFILE_BASE)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            }

            LaunchedEffect(current.url) {
                val intent = Intent(context, NativePlayerActivity::class.java).apply {
                    putExtra("stream_url", current.url)
                    current.audioUrl?.let { putExtra("audio_url", it) }
                    putExtra("parent_id", current.parentId)
                    putExtra("parent_type", current.parentType)
                    putExtra("season", current.season ?: -1)
                    putExtra("episode", current.episode ?: -1)
                    current.episodeStreamId?.let { putExtra("episode_stream_id", it) }
                    current.episodeTitle?.let { putExtra("episode_title", it) }
                    putExtra("item_name", current.itemName)
                    putExtra("display_name", current.itemName)
                    current.itemPoster?.let { putExtra("item_poster", it) }
                    current.clearLogoUrl?.let { putExtra("clear_logo_url", it) }
                    current.backdropUrl?.let { putExtra("backdrop_url", it) }
                    current.overview?.let { putExtra("item_overview", it) }
                    putExtra("start_position_ms", current.startPositionMs)
                    putExtra("from_actor_return", current.fromActorReturn)
                    if (current.streamHeaders.isNotEmpty()) {
                        putExtra("stream_headers", current.streamHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    }
                    current.drmLicenseUrl?.let { putExtra("drm_license_url", it) }
                    if (current.drmHeaders.isNotEmpty()) {
                        putExtra("drm_headers", current.drmHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                    }
                    // Pass sources as JSON array
                    val sourcesArray = JSONArray()
                    current.sources.forEach { stream ->
                        val obj = JSONObject().apply {
                            put("name", stream.name)
                            put("title", stream.title)
                            put("description", stream.description)
                            put("url", stream.url)
                            put("audioUrl", stream.audioUrl)
                            put("infoHash", stream.infoHash)
                            put("fileIdx", stream.fileIdx)
                        }
                        sourcesArray.put(obj)
                    }
                    putExtra("sources_json", sourcesArray.toString())
                    // Pass cast as JSON array
                    val castArray = JSONArray()
                    current.cast.forEach { member ->
                        val obj = JSONObject().apply {
                            put("id", member.id)
                            put("name", member.name)
                            put("character", member.character)
                            put("profilePath", member.profilePath)
                        }
                        castArray.put(obj)
                    }
                    putExtra("cast_json", castArray.toString())
                    putExtra("season_episode_label", listOfNotNull(current.season?.let { "Season $it" }, current.episode?.let { "Episode $it" }).joinToString(" • "))
                    // Pass total episodes
                    current.totalEpisodesInSeason?.let { putExtra("total_episodes_in_season", it) }
                }
                playerResultLauncher.launch(intent)
            }
        }
    }
    }
}
