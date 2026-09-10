package com.kennyb1201.kbstream.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionPart
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Collection screen: a fixed (non-scrolling) header — poster, name, count,
 * overview — above a single poster rail. Collections hold 2-5 movies, so
 * everything fits on one screen without the old full-width rows and their
 * wasted space.
 */
@Composable
fun CollectionScreen(
    collectionId: Int,
    collectionName: String,
    onNavigateDetail: (type: String, id: String) -> Unit,
    viewModel: CollectionViewModel = viewModel()
) {
    val collection by viewModel.collection.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()

    // Long-press context menu for collection part posters.
    var menuPart by remember {
        mutableStateOf<TmdbCollectionPart?>(
            null
        )
    }

    var lastPartFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissPartMenu() {
        menuPart = null
        lastPartFocusRequester?.requestFocus()
    }

    LaunchedEffect(collectionId) {
        viewModel.load(collectionId)
    }

    val detail = collection

    // Screen root Box so the long-press context menu's full-screen scrim
    // overlays everything (the menu fills whatever parent it's placed in).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Fixed header: never scrolls with the content.
            CollectionHeader(
                name = detail?.name ?: collectionName,
                overview = detail?.overview,
                posterUrl = detail?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                partCount = detail?.parts?.size
            )

            when {
                isLoading -> {
                    CollectionMessagePanel(
                        title = "Loading collection...",
                        body = "Fetching movies in this collection.",
                        showSpinner = true
                    )
                }

                detail == null -> {
                    CollectionMessagePanel(
                        title = "Collection unavailable",
                        body = "We couldn't load this collection right now."
                    )
                }

                detail.parts.isEmpty() -> {
                    CollectionMessagePanel(
                        title = "No movies found",
                        body = "This collection does not currently list any titles."
                    )
                }

                else -> {
                    // Compact poster rail: release order, watched badges, and
                    // title/year captions — 2-5 titles fill one screen.
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 4.dp)
                    ) {
                        items(
                            items = detail.parts.sortedBy { it.releaseDate ?: "9999-99-99" },
                            key = { part: TmdbCollectionPart -> "collection_part:${part.id}" }
                        ) { part: TmdbCollectionPart ->
                            // Focus requester for restoring focus after the
                            // long-press menu dismisses.
                            val requester = remember(
                                part.id
                            ) {
                                FocusRequester()
                            }

                            val watched =
                                resolvedIds[
                                    viewModel.lookupKey(part.id, "movie")
                                ]?.let { imdbId ->
                                    viewModel.watchedKey(
                                        imdbId,
                                        "movie"
                                    ) in watchedKeys
                                } == true

                            CollectionPosterTile(
                                part = part,
                                isWatched = watched,
                                onClick = {
                                    onNavigateDetail(
                                        "movie",
                                        part.id.toString()
                                    )
                                },
                                onLongClick = {
                                    lastPartFocusRequester =
                                        requester
                                    menuPart = part
                                },
                                modifier = Modifier
                                    .focusRequester(requester)
                            )
                        }
                    }
                }
            }
        }

        // Long-press context menu for collection part posters. Sits inside
        // the root Box so its full-screen scrim covers the whole screen.
        menuPart?.let { part ->
            // Same lookup the poster badge uses, so the toggle matches what
            // the poster currently shows.
            val isWatched =
                resolvedIds[
                    viewModel.lookupKey(
                        part.id,
                        "movie"
                    )
                ]?.let { imdbId ->
                    viewModel.watchedKey(
                        imdbId,
                        "movie"
                    ) in watchedKeys
                } == true

            PosterContextMenu(
                title = part.title
                    ?: part.name
                    ?: "",
                actions = listOf(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this movie's detail page"
                    ) {
                        val selected = part
                        menuPart = null
                        onNavigateDetail(
                            "movie",
                            selected.id.toString()
                        )
                    },
                    PosterContextAction(
                        label = if (isWatched) {
                            "Mark as Unwatched"
                        } else {
                            "Mark as Watched"
                        },
                        description = if (isWatched) {
                            "Clear watched status on this device and Simkl"
                        } else {
                            "Show this movie as watched"
                        }
                    ) {
                        val selected = part
                        menuPart = null
                        if (isWatched) {
                            viewModel.markUnwatched(selected.id)
                        } else {
                            viewModel.markAsWatched(selected.id)
                        }
                        lastPartFocusRequester?.requestFocus()
                    }
                ),
                onDismiss = {
                    dismissPartMenu()
                }
            )
        }
    }
}

/**
 * Fixed screen header: poster, name, accent count line, and the collection
 * overview. No type pill — the screen itself says what it is.
 */
@Composable
private fun CollectionHeader(
    name: String,
    overview: String?,
    posterUrl: String?,
    partCount: Int?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, RoundedCornerShape(20.dp))
            .border(1.dp, KBTextLo.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PosterCard(
            posterUrl = posterUrl,
            contentDescription = name,
            isWatched = false,
            onClick = {},
            modifier = Modifier
                .width(110.dp)
                .height(165.dp)
        )

        Column(
            modifier = Modifier
                .padding(start = 18.dp)
                .weight(1f)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                color = KBTextHi,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            partCount?.let { count ->
                Text(
                    text = if (count == 1) "1 movie" else "$count movies",
                    color = KBAccent,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            overview
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        text = it,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
        }
    }
}

/**
 * Poster tile for a collection part: poster with watched badge plus a small
 * title/year caption — same proportions as search tiles, so a handful of
 * movies fills the rail instead of one stretched row each.
 */
@Composable
private fun CollectionPosterTile(
    part: TmdbCollectionPart,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(124.dp)
    ) {
        PosterCard(
            posterUrl = part.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            contentDescription = part.title
                ?: part.name
                ?: "Collection movie",
            isWatched = isWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .width(124.dp)
                .height(186.dp)
        )

        Text(
            text = part.title ?: part.name ?: "Untitled",
            style = MaterialTheme.typography.bodySmall,
            color = KBTextHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp)
        )

        part.releaseDate
            ?.takeIf { it.isNotBlank() }
            ?.let { releaseDate ->
                Text(
                    text = releaseDate.take(4),
                    style = MaterialTheme.typography.bodySmall,
                    color = KBTextLo,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
    }
}

@Composable
private fun CollectionMessagePanel(
    title: String,
    body: String,
    showSpinner: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, RoundedCornerShape(16.dp))
            .border(1.dp, KBTextLo.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = KBAccent,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = KBTextHi
            )
        }
        Text(
            text = body,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
