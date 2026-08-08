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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.KBCard
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
    onBack: () -> Unit = {},
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: ActorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current

    LaunchedEffect(personId) {
        viewModel.load(personId)
    }

    val person by viewModel.person.collectAsState()
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val resolvedCreditIds by viewModel.resolvedCreditIds.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    when {
        isLoading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text("Loading...")
        }

        error != null && person == null -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text("Error: $error")
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
    viewModel.sortedCredits(p).take(100)
}

    fun TmdbPersonCredit.displayDate(): String =
        when (mediaType) {
            "movie" -> releaseDate ?: ""
            "tv" -> firstAirDate ?: ""
            else -> ""
        }

    fun TmdbPersonCredit.isLikelyTalkOrVariety(): Boolean {
        val text = buildString {
            append(displayTitle())
            append(' ')
            append(character.orEmpty())
        }.lowercase()

        val keywords = listOf(
            "talk show", "late night", "tonight show", "daily show",
            "news", "guest", "host", "interview", "panel", "variety",
            "reality", "game show"
        )

        return mediaType == "tv" && keywords.any { it in text }
    }

    fun TmdbPersonCredit.rankScore(): Int {
        var score = 0

        if (mediaType == "movie") score += 500
        if (mediaType == "tv") score += 250
        if (isLikelyTalkOrVariety()) score -= 500 else score += 220
        if (!posterPath.isNullOrBlank()) score += 100

        val votes = voteCount ?: 0
        if (votes >= 25) score += 40
        if (votes >= 100) score += 80
        if (votes >= 500) score += 120

        score += ((popularity ?: 0.0) * 8).toInt().coerceAtMost(320)
        score += ((voteAverage ?: 0.0) * 18).toInt().coerceAtMost(180)

        val year = displayDate().take(4).toIntOrNull() ?: 0
        if (year >= 1900) score += (year - 1900).coerceAtMost(140)

        return score
    }

    p.combinedCredits?.cast
        .orEmpty()
        .filter { credit ->
            credit.id > 0 &&
                !credit.mediaType.isNullOrBlank() &&
                (credit.mediaType == "movie" || credit.mediaType == "tv")
        }
        .distinctBy { "${it.mediaType}:${it.id}" }
        .sortedWith(
            compareByDescending<TmdbPersonCredit> { it.rankScore() }
                .thenByDescending { it.voteCount ?: 0 }
                .thenByDescending { it.popularity ?: 0.0 }
                .thenByDescending { it.voteAverage ?: 0.0 }
                .thenByDescending { it.displayDate() }
                .thenBy { it.displayTitle() }
        )
        .take(100)
}

            CompositionLocalProvider(LocalBringIntoViewSpec provides LocalTvBringIntoViewSpec) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = remember(profileUrl, context) {
                            ImageRequest.Builder(context)
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
                        item(key = "top_actions") {
                            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                                KBCard(
                                    onClick = onBack,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                ) {
                                    Text(
                                        text = "BACK",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 10.dp
                                        )
                                    )
                                }

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

                                if (!error.isNullOrBlank() && credits.isEmpty()) {
                                    Text(
                                        text = error!!,
                                        color = KBTextLo,
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
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 24.dp,
                                        bottom = 10.dp
                                    )
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
                                        val mediaType = credit.mediaType ?: return@items
                                        val normalizedType =
                                            if (mediaType == "tv") "series" else "movie"
                                        val imdbId = resolvedCreditIds[
                                            viewModel.creditLookupKey(credit.id, normalizedType)
                                        ]
                                        val isWatched =
                                            imdbId?.let {
                                                viewModel.watchedKey(it, normalizedType) in watchedKeys
                                            } == true

                                        ActorCreditCard(
                                            credit = credit,
                                            isWatched = isWatched,
                                            onClick = {
                                                viewModel.resolveAndNavigate(
                                                    tmdbId = credit.id,
                                                    mediaType = mediaType,
                                                    onNavigateDetail = onNavigateDetail
                                                )
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
                                    modifier = Modifier.padding(
                                        horizontal = 24.dp,
                                        vertical = 24.dp
                                    )
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
    isWatched: Boolean,
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
            isWatched = isWatched,
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
