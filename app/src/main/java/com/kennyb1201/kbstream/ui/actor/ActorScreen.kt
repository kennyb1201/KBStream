
package com.kennyb1201.kbstream.ui.actor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.launch

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
    val combinedCredits by viewModel.combinedCredits.collectAsState()
    val resolvedPosterIds by viewModel.resolvedPosterIds.collectAsState()
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(actorId) {
        viewModel.load(actorId)
    }

    when {
        isLoading -> Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Loading...") }
        error != null -> Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Error: $error") }
        person != null -> {
            val p = person!!
            val context = androidx.compose.ui.platform.LocalContext.current

            val credits = combinedCredits?.cast.orEmpty()
            val backdropUrl = remember(credits) {
                credits.firstNotNullOfOrNull { it.backdropPath }?.let { TmdbRepository.BACKDROP_BASE + it }
            }

            val movies = remember(credits) {
                credits.filter { it.mediaType == "movie" }.sortedByDescending { it.voteCount ?: 0 }
            }
            val tvShows = remember(credits) {
                credits.filter { it.mediaType == "tv" }.sortedByDescending { it.voteCount ?: 0 }
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides LocalActorBringIntoViewSpec) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = remember(backdropUrl, p.profilePath) {
                            val url = backdropUrl ?: p.profilePath?.let { TmdbRepository.PROFILE_BASE + it }
                            ImageRequest.Builder(context).data(url).crossfade(true).build()
                        },
                        contentDescription = p.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        KBVoid.copy(alpha = 0.55f),
                                        KBVoid
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
                            Text(
                                p.name,
                                style = MaterialTheme.typography.headlineLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            val bioMeta = remember(p) {
                                listOfNotNull(
                                    p.birthday?.let { "Born $it" },
                                    p.placeOfBirth
                                ).joinToString(" • ")
                            }
                            if (bioMeta.isNotBlank()) {
                                Text(
                                    bioMeta,
                                    color = KBTextLo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
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
                                    Text(
                                        p.biography!!,
                                        color = KBTextLo,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                                    )
                                }
                            }

                            if (movies.isNotEmpty()) {
                                item(key = "moviesheader") {
                                    Text(
                                        "MOVIES",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "moviesrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
                                        modifier = Modifier.padding(bottom = 16.dp).focusGroup().focusRestorer()
                                    ) {
                                        items(movies, key = { it.id }) { credit ->
                                            ActorCreditCard(
                                                credit = credit,
                                                isWatched = resolvedPosterIds[viewModel.posterLookupKey(credit.id, "movie")]
                                                    ?.let { imdbId -> viewModel.watchedKey(imdbId, "movie") in watchedKeys } == true,
                                                onClick = {
                                                    scope.launch {
                                                        val imdbId = viewModel.resolveImdbId(credit.id, "movie")
                                                        if (imdbId != null) onNavigateDetail("movie", imdbId)
                                                    }
                                                }
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
                                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "tvrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
                                        modifier = Modifier.padding(bottom = 32.dp).focusGroup().focusRestorer()
                                    ) {
                                        items(tvShows, key = { it.id }) { credit ->
                                            ActorCreditCard(
                                                credit = credit,
                                                isWatched = resolvedPosterIds[viewModel.posterLookupKey(credit.id, "series")]
                                                    ?.let { imdbId -> viewModel.watchedKey(imdbId, "series") in watchedKeys } == true,
                                                onClick = {
                                                    scope.launch {
                                                        val imdbId = viewModel.resolveImdbId(credit.id, "tv")
                                                        if (imdbId != null) onNavigateDetail("series", imdbId)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActorCreditCard(
    credit: TmdbPersonCredit,
    isWatched: Boolean,
    onClick: () -> Unit
) {
    PosterCard(
        posterUrl = remember(credit.posterPath) { credit.posterPath?.let { TmdbRepository.POSTER_BASE + it } },
        contentDescription = credit.title ?: credit.name ?: "",
        isWatched = isWatched,
        onClick = onClick,
        modifier = Modifier.width(124.dp).height(180.dp).padding(end = 12.dp)
    )
}
