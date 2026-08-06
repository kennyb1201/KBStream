package com.kennyb1201.kbstream.ui.actor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private class TvPivotBringIntoViewSpec(
    private val parentFraction: Float = 0.3f,
    private val childFraction: Float = 0f
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float {
        val targetOffset = parentFraction * containerSize
        val childOffset = childFraction * size
        val destination = targetOffset - childOffset
        return offset - destination
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private val LocalTvBringIntoViewSpec = TvPivotBringIntoViewSpec()

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ActorScreen(
    personId: Int,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: ActorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    LaunchedEffect(personId) {
        viewModel.load(personId)
    }

    val person by viewModel.person.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    when {
        isLoading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text("Loading...")
        }

        person == null -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text("Actor not found.")
        }

        else -> {
            val p = person!!
            val profileUrl = remember(p.profilePath) {
                p.profilePath?.let { TmdbRepository.PROFILE_BASE + it }
            }

            val credits = remember(p) {
                p.combinedCredits?.cast
                    .orEmpty()
                    .filter { credit ->
                        credit.id > 0 &&
                            !credit.mediaType.isNullOrBlank() &&
                            (credit.mediaType == "movie" || credit.mediaType == "tv")
                    }
                    .distinctBy { "${it.mediaType}:${it.id}" }
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides LocalTvBringIntoViewSpec) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = remember(profileUrl) {
                            ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(profileUrl)
                                .crossfade(true)
                                .build()
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

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusGroup(),
                        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp)
                    ) {
                        item(key = "header") {
                            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = p.name,
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = KBTextHi,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                p.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                                    Text(
                                        text = bio,
                                        color = KBTextLo,
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 12.dp)
                                    )
                                }
                            }
                        }

                        if (credits.isNotEmpty()) {
                            item(key = "known_for_header") {
                                Text(
                                    text = "KNOWN FOR",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 10.dp)
                                )
                            }

                            item(key = "known_for_row") {
                                LazyRow(
                                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp),
                                    modifier = Modifier
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        items = credits,
                                        key = { "${it.mediaType}:${it.id}" }
                                    ) { credit: TmdbPersonCredit ->
                                        ActorCreditCard(
                                            credit = credit,
                                            onClick = {
                                                val type =
                                                    if (credit.mediaType == "tv") "series" else "movie"

                                                viewModelScopeLaunch(viewModel, credit, type, onNavigateDetail)
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            item(key = "empty") {
                                Text(
                                    text = "No credits found.",
                                    color = KBTextLo,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                                )
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
    onClick: () -> Unit
) {
    val title = credit.title ?: credit.name ?: ""
    val subtitle = credit.character
    val posterUrl = remember(credit.posterPath) {
        credit.posterPath?.let { TmdbRepository.POSTER_BASE + it }
    }

    Column(modifier = Modifier.width(124.dp)) {
        PosterCard(
            posterUrl = posterUrl,
            contentDescription = title,
            isWatched = false,
            onClick = onClick,
            modifier = Modifier
                .width(124.dp)
                .height(180.dp)
                .padding(end = 12.dp)
        )

        Text(
            text = title,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )

        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun viewModelScopeLaunch(
    viewModel: ActorViewModel,
    credit: TmdbPersonCredit,
    type: String,
    onNavigateDetail: (String, String) -> Unit
) {
    viewModel.viewModelScope.launch {
        val imdbId = viewModel.resolveImdbId(credit.id, credit.mediaType ?: return@launch)
        if (!imdbId.isNullOrBlank()) {
            onNavigateDetail(type, imdbId)
        }
    }
}
