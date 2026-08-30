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
import com.kennyb1201.kbstream.ui.player.PlayerCastMember
import com.kennyb1201.kbstream.ui.player.PlayerScreen
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

    data class Detail(
        val type: String,
        val id: String,
        val pendingTarget: StreamsTarget? = null,
        val itemPoster: String? = null
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
        val overview: String? = null,
        val cast: List<PlayerCastMember> = emptyList(),
        val startPositionMs: Long,
        val sources: List<Stream> = emptyList(),
        val streamHeaders: Map<String, String> = emptyMap()
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
}

@Composable
fun AppRoot() {

    var screen by remember {
        mutableStateOf<Screen>(Screen.Home)
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
                Screen.Home

            is Screen.Player ->
                if (current.parentType == "channel") {
                    Screen.Guide
                } else {
                    Screen.Detail(
                        current.parentType,
                        current.parentId,
                        itemPoster = current.itemPoster
                    )
                }

            is Screen.Streams ->
                if (current.parentType == "channel") {
                    Screen.Guide
                } else {
                    Screen.Detail(
                        current.parentType,
                        current.parentId,
                        current.target,
                        current.itemPoster
                    )
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
                        meta.id
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
                        poster ?: meta.poster
                    )
                },

                onManageAddons = {
                    screen = Screen.Addons
                },

                onSearch = {
                    screen = Screen.Search
                },

                onOpenSimkl = {
                    screen = Screen.Simkl
                },

                onOpenGuide = {
                    screen = Screen.Guide
                }
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
                        meta.id
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
                    screen = Screen.Home
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
                        streamHeaders = channel.headers
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
                        id ->

                    screen = Screen.Detail(
                        type,
                        id
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
                        id ->

                    screen = Screen.Detail(
                        type,
                        id
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
                        id ->

                    screen = Screen.Detail(
                        type,
                        id
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
                        id ->

                    screen = Screen.Detail(
                        type,
                        id
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
                        id ->

                    screen = Screen.Detail(
                        type,
                        id
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

            StreamsScreen(
                title = current.target.title,
                displayName = current.target.displayName,
                season = current.target.season,
                episode = current.target.episode,
                backdropUrl = current.backdropUrl,
                clearLogoUrl = current.clearLogoUrl,

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
                            itemPoster =
                                current.itemPoster,
                            clearLogoUrl =
                                current.clearLogoUrl,
                            overview =
                                current.overview,
                            cast =
                                current.cast.map { member ->
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
                            sources = allSources
                        )
                    }
                },

                viewModel = streamsViewModel
            )
        }

        is Screen.Player -> {

            PlayerScreen(
                url = current.url,
                audioUrl = current.audioUrl,
                parentId = current.parentId,
                parentType = current.parentType,
                season = current.season,
                episode = current.episode,
                episodeStreamId =
                    current.episodeStreamId,
                episodeTitle = current.episodeTitle,
                itemName = current.itemName,
                itemPoster = current.itemPoster,
                clearLogoUrl = current.clearLogoUrl,
                overview = current.overview,
                cast = current.cast,
                startPositionMs =
                    current.startPositionMs,
                sources = current.sources,
                streamHeaders =
                    current.streamHeaders,
                onNavigateActor = { personId ->
                    screen = Screen.Actor(
                        personId,
                        Screen.Player(
                            url = current.url,
                            audioUrl = current.audioUrl,
                            parentId = current.parentId,
                            parentType = current.parentType,
                            season = current.season,
                            episode = current.episode,
                            episodeStreamId = current.episodeStreamId,
                            episodeTitle = current.episodeTitle,
                            itemName = current.itemName,
                            itemPoster = current.itemPoster,
                            clearLogoUrl = current.clearLogoUrl,
                            overview = current.overview,
                            cast = current.cast,
                            startPositionMs = current.startPositionMs,
                            sources = current.sources,
                            streamHeaders = current.streamHeaders
                        )
                    )
                },
                onNextEpisode = {
                    val nextEp = (current.episode ?: 0) + 1
                    screen = Screen.Detail(
                        type = current.parentType,
                        id = current.parentId,
                        pendingTarget = StreamsTarget(
                            contentType = "series",
                            streamId = "${current.parentId}:${current.season ?: 0}:$nextEp",
                            title = "${current.itemName} S${current.season ?: 0} E$nextEp",
                            displayName = current.itemName,
                            season = current.season,
                            episode = nextEp,
                            resumePositionMs = 0L
                        ),
                        itemPoster = current.itemPoster
                    )
                }
            )
        }
    }
}
