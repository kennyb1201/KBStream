package com.kennyb1201.kbstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.tv.material3.Surface
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.actor.ActorScreen
import com.kennyb1201.kbstream.ui.addons.AddonsScreen
import com.kennyb1201.kbstream.ui.detail.DetailScreen
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.home.HomeScreen
import com.kennyb1201.kbstream.ui.search.SearchScreen
import com.kennyb1201.kbstream.ui.simkl.SimklConnectScreen
import com.kennyb1201.kbstream.ui.streams.StreamsScreen
import com.kennyb1201.kbstream.ui.studio.StudioScreen
import com.kennyb1201.kbstream.ui.tag.TagScreen
import com.kennyb1201.kbstream.ui.theme.KBStreamTheme

sealed class Screen {
    object Home : Screen()
    object Addons : Screen()
    object Search : Screen()
    object Simkl : Screen()
    data class Detail(
        val type: String,
        val id: String,
        val pendingTarget: StreamsTarget? = null,
        val itemPoster: String? = null
    ) : Screen()
    data class Actor(val personId: Int) : Screen()
    data class Studio(val id: Int, val name: String, val isNetwork: Boolean) : Screen()
    data class Tag(val id: Int, val name: String, val isKeyword: Boolean, val mediaType: String) : Screen()
    data class Streams(
        val target: StreamsTarget,
        val parentId: String,
        val parentType: String,
        val itemPoster: String?
    ) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            KBStreamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppRoot()
                    }
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = screen != Screen.Home) {
        screen = when (val current = screen) {
            is Screen.Streams -> Screen.Detail(
                type = current.parentType,
                id = current.parentId,
                pendingTarget = current.target,
                itemPoster = current.itemPoster
            )
            else -> Screen.Home
        }
    }

    when (val current = screen) {
        is Screen.Home -> HomeScreen(
            onItemClick = { meta: MetaPreview ->
                screen = Screen.Detail(meta.type, meta.id)
            },
            onOpenDetailTarget = { meta, target, poster ->
                screen = Screen.Detail(
                    type = meta.type,
                    id = meta.id,
                    pendingTarget = target,
                    itemPoster = poster ?: meta.poster
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
            }
        )

        is Screen.Addons -> AddonsScreen(
            onBack = {
                screen = Screen.Home
            }
        )

        is Screen.Search -> SearchScreen(
            onItemClick = { meta: MetaPreview ->
                screen = Screen.Detail(meta.type, meta.id)
            }
        )

        is Screen.Simkl -> SimklConnectScreen(
            onBackToHome = {
                screen = Screen.Home
            }
        )

        is Screen.Detail -> DetailScreen(
            type = current.type,
            id = current.id,
            initialTarget = current.pendingTarget,
            initialPoster = current.itemPoster,
            onNavigateDetail = { type, id ->
                screen = Screen.Detail(type, id)
            },
            onNavigateActor = { personId ->
                screen = Screen.Actor(personId)
            },
            onNavigateStudio = { id, name, isNetwork ->
                screen = Screen.Studio(id, name, isNetwork)
            },
                        onNavigateTag = { id, name, isKeyword, type ->
                screen = Screen.Tag(id = id, name = name, isKeyword = isKeyword, mediaType = type)
            },

            onNavigateStreams = { target, parentId, parentType, poster ->
                screen = Screen.Streams(target, parentId, parentType, poster)
            }
        )

        is Screen.Actor -> ActorScreen(
            personId = current.personId,
            onBack = {
                screen = Screen.Home
            },
            onNavigateDetail = { type, id ->
                screen = Screen.Detail(type, id)
            }
        )

        is Screen.Studio -> StudioScreen(
            id = current.id,
            name = current.name,
            isNetwork = current.isNetwork,
            onBack = {
                screen = Screen.Home
            },
            onNavigateDetail = { type, id ->
                screen = Screen.Detail(type, id)
            }
        )

        is Screen.Tag -> TagScreen(
            id = current.id,
            name = current.name,
            isKeyword = current.isKeyword,
            type = current.mediaType,
            onBack = {
                screen = Screen.Home
            },
            onNavigateDetail = { type, id ->
                screen = Screen.Detail(type, id)
            }
        )

        is Screen.Streams -> StreamsScreen(
            contentType = current.target.contentType,
            streamId = current.target.streamId,
            title = current.target.title,
            parentId = current.parentId,
            parentType = current.parentType,
            season = current.target.season,
            episode = current.target.episode,
            displayName = current.target.displayName,
            itemPoster = current.itemPoster,
            resumePositionMs = current.target.resumePositionMs
        )
    }
}
