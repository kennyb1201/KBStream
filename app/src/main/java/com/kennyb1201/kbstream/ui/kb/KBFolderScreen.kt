package com.kennyb1201.kbstream.ui.kb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.kb.KBContentItem
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * One imported KB folder. Layout comes from the folder's viewMode:
 *  - FOLLOW_LAYOUT (default): hero + rails visually identical to Home —
 *    same clearlogo, gradients, metadata line, inline trailers, and the
 *    global Landscape Cards / Show Catalog Type toggles
 *  - ROWS: plain rail list, no hero
 *  - GRID: poster grid of the merged items
 */
@Composable
fun KBFolderScreen(
    folderId: String,
    onBack: () -> Unit,
    onItemClick: (
        type: String,
        imdbId: String,
        poster: String?,
        backdrop: String?,
        title: String?
    ) -> Unit,
    viewModel: KBFolderViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val selectedSourceId by viewModel.selectedSourceId.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()

    var menuTarget by remember { mutableStateOf<KBContentItem?>(null) }

    // Long-press "Open in Grid": force the folder into its GRID layout for
    // this visit, whatever its manifest viewMode says. Back returns to the
    // folder's own layout first, then exits — same feel as Home's
    // "Open in Grid" returning to the rails.
    var showAsGrid by remember(folderId) { mutableStateOf(false) }

    LaunchedEffect(folderId) {
        viewModel.loadById(folderId)
    }

    val folder = state.folder
    val layoutMode = if (showAsGrid) {
        KBLayoutModes.Mode.GRID
    } else {
        KBLayoutModes.fromViewMode(folder?.viewMode)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        KBFolderBody(
            layoutMode = layoutMode,
            viewModel = viewModel,
            state = state,
            resolvedIds = resolvedIds,
            selectedSourceId = selectedSourceId,
            watchedKeys = watchedKeys,
            partialWatchedKeys = partialWatchedKeys,
            onSelectSource = viewModel::selectSource,
            onOpenItem = { item ->
                viewModel.resolveAndNavigate(item) { type, imdbId ->
                    onItemClick(
                        type,
                        imdbId,
                        item.posterUrl,
                        item.backdropUrl,
                        item.title
                    )
                }
            },
            onLongPressItem = { menuTarget = it }
        )
    }

    menuTarget?.let { item ->
        // Same watched-key normalization the rails use: TMDB items resolve
        // through the ViewModel's tmdbId -> imdb id map, addon items key on
        // their own imdb id.
        val normalizedType = when (item.type.lowercase()) {
            "series", "tv" -> "series"
            else -> "movie"
        }
        val menuWatched = item.id.takeIf { it.startsWith("tt") }?.let { imdbId ->
            viewModel.watchedKey(imdbId, normalizedType) in watchedKeys
        } ?: item.tmdbId?.let { tmdbId ->
            val imdbId = resolvedIds["$normalizedType::$tmdbId"]
            imdbId != null && viewModel.watchedKey(imdbId, normalizedType) in watchedKeys
        } ?: false
        PosterContextMenu(
            title = item.title ?: "Untitled",
            actions = listOf(
                PosterContextAction(
                    label = "Open in Grid",
                    description = "Browse this whole collection as a poster grid"
                ) {
                    menuTarget = null
                    showAsGrid = true
                },
                PosterContextAction(
                    label = "Go to Details",
                    description = "Open this title's detail page"
                ) {
                    menuTarget = null
                    viewModel.resolveAndNavigate(item) { type, imdbId ->
                        onItemClick(
                            type,
                            imdbId,
                            item.posterUrl,
                            item.backdropUrl,
                            item.title
                        )
                    }
                },
                PosterContextAction(
                    label = watchedMenuLabel(
                        isWatched = menuWatched,
                        mediaType = item.type
                    ),
                    description = watchedMenuDescription(
                        isWatched = menuWatched,
                        mediaType = item.type
                    )
                ) {
                    menuTarget = null
                    if (menuWatched) {
                        viewModel.markUnwatched(item)
                    } else {
                        viewModel.markAsWatched(item)
                    }
                }
            ),
            onDismiss = { menuTarget = null }
        )
    }

    BackHandler {
        // Grid override is a visit-level view: Back first restores the
        // folder's own layout, and only a second Back exits the folder.
        if (showAsGrid) {
            showAsGrid = false
        } else {
            onBack()
        }
    }
}
