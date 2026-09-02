package com.kennyb1201.kbstream.ui.collection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionPart
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.WatchedCheckBadge
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

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

    // Long-press context menu for collection part rows.
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "hero") {
                CollectionHero(
                    name = detail?.name ?: collectionName,
                    overview = detail?.overview,
                    posterUrl = detail?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                )
            }

            when {
                isLoading -> {
                    item(key = "loading") {
                        CollectionMessagePanel(
                            title = "Loading collection...",
                            body = "Fetching movies in this collection.",
                            showSpinner = true
                        )
                    }
                }

                detail == null -> {
                    item(key = "error") {
                        CollectionMessagePanel(
                            title = "Collection unavailable",
                            body = "We couldn't load this collection right now."
                        )
                    }
                }

                detail.parts.isEmpty() -> {
                    item(key = "empty") {
                        CollectionMessagePanel(
                            title = "No movies found",
                            body = "This collection does not currently list any titles."
                        )
                    }
                }

                else -> {
                    item(key = "count") {
                        Text(
                            text = "${detail.parts.size} movies",
                            color = KBAccent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

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

                        CollectionMovieRow(
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

        // Long-press context menu for collection part rows.
        menuPart?.let { part ->
            // Same lookup the row badge uses, so the toggle matches what the
            // poster currently shows.
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

@Composable
private fun CollectionHero(
    name: String,
    overview: String?,
    posterUrl: String?
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
                .width(148.dp)
                .height(222.dp)
        )

        Column(
            modifier = Modifier
                .padding(start = 18.dp)
                .weight(1f)
        ) {
            TypePill("collection")

            Text(
                text = name,
                style = MaterialTheme.typography.headlineMedium,
                color = KBTextHi,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )

            overview
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        text = it,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
        }
    }
}

@Composable
private fun CollectionMovieRow(
    part: TmdbCollectionPart,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // KBCard is the row's single focusable/clickable surface so the D-pad
    // always lands on one node - long-press (hold Select) opens the context
    // menu, short press navigates. The poster is drawn inline (not wrapped
    // in its own PosterCard) to avoid nested focusables in the same row.
    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .height(138.dp)
                    .background(KBSurface, RoundedCornerShape(8.dp))
            ) {
                val posterUrl = part.posterPath
                    ?.let { "https://image.tmdb.org/t/p/w500$it" }

                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = part.title
                            ?: part.name
                            ?: "Collection movie",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isWatched) {
                    WatchedCheckBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = part.title ?: part.name ?: "Untitled",
                    style = MaterialTheme.typography.titleMedium,
                    color = KBTextHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                part.releaseDate
                    ?.takeIf { it.isNotBlank() }
                    ?.let { releaseDate ->
                        Text(
                            text = releaseDate.take(4),
                            style = MaterialTheme.typography.bodyMedium,
                            color = KBTextLo,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
            }
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

@Composable
private fun TypePill(type: String) {
    Box(
        modifier = Modifier
            .background(KBSurfaceRaised, RoundedCornerShape(999.dp))
            .border(1.dp, KBAccent.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = type.uppercase(),
            color = KBAccent,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
