package com.kennyb1201.kbstream.ui.actor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.metaLine
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import com.kennyb1201.kbstream.ui.theme.KBAccent

@OptIn(ExperimentalFoundationApi::class)
private class ActorBringIntoViewSpec(
    private val parentFraction: Float = 0.3f,
    private val childFraction: Float = 0f
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val targetOffset = parentFraction * containerSize
        val childOffset = childFraction * size
        val destination = targetOffset - childOffset
        return offset - destination
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val LocalActorBringIntoViewSpec = ActorBringIntoViewSpec()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActorScreen(
    actorId: Int,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: ActorViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val person by viewModel.person.collectAsState()
    val resolvedCreditIds by viewModel.resolvedCreditIds.collectAsState()
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val topWorkBackdropUrl by viewModel.topWorkBackdropUrl.collectAsState()
    val topWorkCredit by viewModel.topWorkCredit.collectAsState()

    LaunchedEffect(actorId) {
        viewModel.load(actorId)
    }

    // Long-press context menu for credit posters.
    var menuCredit by remember {
        mutableStateOf<TmdbPersonCredit?>(
            null
        )
    }

    var lastCreditFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissCreditMenu() {
        menuCredit = null
        lastCreditFocusRequester?.requestFocus()
    }

    when {
        isLoading -> ActorStatusMessage(icon = "⏳", message = "Loading…")
        error != null -> ActorStatusMessage(icon = "⚠️", message = "Error: $error")
        person != null -> {
            val p = person!!
            val context = androidx.compose.ui.platform.LocalContext.current
            val sortedCredits = remember(p) { viewModel.sortedCredits(p) }
            val backdropUrl = topWorkBackdropUrl
            val headerPortraitUrl = remember(p) {
                p.profilePath?.let { TmdbRepository.PROFILE_BASE + it }
            }

            val movies = remember(sortedCredits) {
                sortedCredits.filter { it.mediaType == "movie" }
            }
            val tvShows = remember(sortedCredits) {
                sortedCredits.filter { it.mediaType == "tv" }
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides LocalActorBringIntoViewSpec) {
                Box(modifier = Modifier.fillMaxSize()) {
                    backdropUrl?.let {
                        AsyncImage(
                            model = remember(backdropUrl) {
                                ImageRequest.Builder(context).data(backdropUrl).crossfade(true).build()
                            },
                            contentDescription = topWorkCredit?.title ?: topWorkCredit?.name ?: p.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        KBVoid.copy(alpha = 0.65f),
                                        KBVoid
                                    )
                                )
                            )
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        KBVoid.copy(alpha = 0.72f),
                                        KBVoid.copy(alpha = 0.28f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusGroup()
                            .focusRestorer()
                    ) {
                        Spacer(modifier = Modifier.height(36.dp))

                        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                val initials = remember(p.name) {
                                    p.name.trim().split(" ")
                                        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                                        .take(2)
                                        .joinToString("")
                                }
                                Box(
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(144.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(KBSurfaceRaised)
                                ) {
                                    if (headerPortraitUrl != null) {
                                        AsyncImage(
                                            model = headerPortraitUrl,
                                            contentDescription = p.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                initials,
                                                style = MaterialTheme.typography.headlineLarge,
                                                color = KBTextLo
                                            )
                                        }
                                    }
                                }

                                Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                                    Text(
                                        p.name,
                                        style = MaterialTheme.typography.headlineLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    val bioMeta = remember(p) { p.metaLine() }
                                    if (bioMeta.isNotBlank()) {
                                        Text(
                                            bioMeta,
                                            color = KBTextLo,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    topWorkCredit?.let { credit ->
                                        Text(
                                            text = "Known for ${credit.title ?: credit.name ?: "Top work"}",
                                            color = KBTextLo,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth()
                                .focusGroup()
                                .focusRestorer(),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
                        ) {
                            if (!p.biography.isNullOrBlank()) {
                                item(key = "biography") {
                                    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 20.dp)) {
                                        var bioExpanded by remember { mutableStateOf(false) }
                                        Text(
                                            p.biography!!,
                                            color = KBTextHi,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = if (bioExpanded) Int.MAX_VALUE else 4,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = if (bioExpanded) "View less" else "View more",
                                            color = KBTextLo,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier
                                                .padding(top = 8.dp)
                                                .focusable()
                                                .clickable(
                                                    onClick = { bioExpanded = !bioExpanded },
                                                    indication = null,
                                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                                )
                                        )
                                    }
                                }
                            }

                            if (movies.isNotEmpty()) {
                                item(key = "moviesheader") {
                                    Text(
                                        "MOVIES",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "moviesrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
                                        modifier = Modifier.padding(bottom = 16.dp).focusGroup().focusRestorer()
                                    ) {
                                        items(movies, key = { it.id }) { credit ->
                                            val requester = remember(
                                                credit.id
                                            ) {
                                                FocusRequester()
                                            }

                                            ActorCreditCard(
                                                credit = credit,
                                                isWatched = resolvedCreditIds[viewModel.creditLookupKey(credit.id, "movie")]
                                                    ?.let { imdbId -> viewModel.watchedKey(imdbId, "movie") in watchedKeys } == true,
                                                onClick = {
                                                    viewModel.resolveAndNavigate(credit.id, "movie", onNavigateDetail)
                                                },
                                                onLongClick = {
                                                    lastCreditFocusRequester =
                                                        requester
                                                    menuCredit = credit
                                                },
                                                focusRequester = requester
                                            )
                                        }
                                    }
                                }
                            }

                            if (tvShows.isNotEmpty()) {
                                item(key = "tvheader") {
                                    Text(
                                        "TV SHOWS",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "tvrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
                                        modifier = Modifier.padding(bottom = 32.dp).focusGroup().focusRestorer()
                                    ) {
                                        items(tvShows, key = { it.id }) { credit ->
                                            val requester = remember(
                                                credit.id
                                            ) {
                                                FocusRequester()
                                            }

                                            ActorCreditCard(
                                                credit = credit,
                                                isWatched = resolvedCreditIds[viewModel.creditLookupKey(credit.id, "series")]
                                                    ?.let { imdbId -> viewModel.watchedKey(imdbId, "series") in watchedKeys } == true,
                                                onClick = {
                                                    viewModel.resolveAndNavigate(credit.id, "tv", onNavigateDetail)
                                                },
                                                onLongClick = {
                                                    lastCreditFocusRequester =
                                                        requester
                                                    menuCredit = credit
                                                },
                                                focusRequester = requester
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    menuCredit?.let { credit ->
                        // Same normalized type/key the credit rails use for the
                        // badge, so the toggle matches the poster's current state.
                        val menuMediaType =
                            when (credit.mediaType?.lowercase()) {
                                "movie" -> "movie"
                                "tv", "series" -> "series"
                                else -> null
                            }

                        val isWatched =
                            if (menuMediaType != null) {
                                resolvedCreditIds[
                                    viewModel.creditLookupKey(
                                        credit.id,
                                        menuMediaType
                                    )
                                ]?.let { imdbId ->
                                    viewModel.watchedKey(
                                        imdbId,
                                        menuMediaType
                                    ) in watchedKeys
                                } == true
                            } else {
                                false
                            }

                        PosterContextMenu(
                            title = credit.title
                                ?: credit.name
                                ?: "",
                            actions = listOf(
                                PosterContextAction(
                                    label = "Go to Details",
                                    description = "Open this title's detail page"
                                ) {
                                    menuCredit = null
                                    viewModel.resolveAndNavigate(
                                        credit.id,
                                        credit.mediaType
                                            ?: "movie",
                                        onNavigateDetail
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
                                        "Show this title as watched"
                                    }
                                ) {
                                    menuCredit = null
                                    if (isWatched) {
                                        viewModel.markUnwatched(
                                            credit.id,
                                            credit.mediaType
                                                ?: "movie"
                                        )
                                    } else {
                                        viewModel.markAsWatched(
                                            credit.id,
                                            credit.mediaType
                                                ?: "movie"
                                        )
                                    }
                                    lastCreditFocusRequester?.requestFocus()
                                }
                            ),
                            onDismiss = {
                                dismissCreditMenu()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActorStatusMessage(icon: String, message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(KBSurface, RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            if (icon == "⏳") {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = KBAccent,
                    strokeWidth = 2.dp
                )
            } else {
                Text(icon, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                message,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

@Composable
private fun ActorCreditCard(
    credit: TmdbPersonCredit,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    PosterCard(
        posterUrl = remember(credit.posterPath) { credit.posterPath?.let { TmdbRepository.POSTER_BASE + it } },
        contentDescription = credit.title ?: credit.name ?: "",
        isWatched = isWatched,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .width(124.dp)
            .height(180.dp)
            .padding(end = 12.dp)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
    )
}
