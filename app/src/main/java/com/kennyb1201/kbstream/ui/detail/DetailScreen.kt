package com.kennyb1201.kbstream.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.bestReleaseDate
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.director
import com.kennyb1201.kbstream.data.tmdb.list
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.tmdb.tmdbImageOriginal
import com.kennyb1201.kbstream.data.tmdb.writers
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class StreamsTarget(
    val contentType: String,
    val streamId: String,
    val title: String,
    val displayName: String,
    val season: Int?,
    val episode: Int?,
    val resumePositionMs: Long
)

private sealed interface PeopleRowItem {
    data class Person(val member: TmdbCastMember) : PeopleRowItem
    data object Separator : PeopleRowItem
}

private enum class EpisodeFocusEdge { START, END }

private data class EpisodeTransitionState(
    val edge: EpisodeFocusEdge? = null
)

private data class DetailFactItem(
    val label: String,
    val value: String
)

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
fun DetailScreen(
    type: String,
    id: String,
    onNavigateDetail: (String, String) -> Unit,
    onNavigateActor: (Int) -> Unit,
    onNavigateStudio: (Int, String, Boolean) -> Unit,
    onNavigateTag: (Int, String, Boolean, String) -> Unit,
    onNavigateStreams: (
        StreamsTarget,
        String,
        String,
        String?,
        String?,
        String?
    ) -> Unit,
    initialTarget: StreamsTarget? = null,
    initialPoster: String? = null,
    viewModel: DetailViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var selectedReview by remember { mutableStateOf<TmdbReview?>(null) }
    var userManuallyChangedSeason by remember { mutableStateOf(false) }
    val episodesRowState = rememberLazyListState()
    val seasonFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val episodeFocusRequesters =
        remember { mutableMapOf<Pair<Int, Int>, FocusRequester>() }
    var episodeTransitionState by remember {
        mutableStateOf(EpisodeTransitionState())
    }

    val meta by viewModel.meta.collectAsState()
    val tmdbDetail by viewModel.tmdbDetail.collectAsState()
    val clearLogoUrl = tmdbImageOriginal(tmdbDetail?.bestLogoPath())
    val isLoading by viewModel.isLoading.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val episodesLoading by viewModel.episodesLoading.collectAsState()
    val episodeError by viewModel.episodeError.collectAsState()
    val resumeInfo by viewModel.resumeInfo.collectAsState()
    val collection by viewModel.collection.collectAsState()
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val resolvedPosterIds by viewModel.resolvedPosterIds.collectAsState()
    val completedEpisodeIds by viewModel.completedEpisodeIds.collectAsState()
    val watchedEpisodeKeys by viewModel.watchedEpisodeKeys.collectAsState()
    val simklWatchedEpisodes by viewModel.simklWatchedEpisodes.collectAsState()
    val simklSeriesWatched by viewModel.simklSeriesWatched.collectAsState()
    val error by viewModel.error.collectAsState()
    val vmTargetEpisode by viewModel.targetEpisode.collectAsState()
    val vmLoadedSeason by viewModel.loadedSeason.collectAsState()
    val vmPlayButtonText by viewModel.playButtonText.collectAsState()

    fun clearEpisodeTransitionState() {
        episodeTransitionState = EpisodeTransitionState()
    }

    val hasStreamAddons by viewModel.hasStreamAddons.collectAsState()

    val seasons = remember(tmdbDetail) {
        tmdbDetail?.seasons.orEmpty()
            .map { it.seasonNumber }
            .distinct()
            .sortedWith(compareBy({ it == 0 }, { it }))
    }

    val movieDetailsFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }

    val effectiveSeason = remember(
        type,
        selectedSeason,
        seasons,
        initialTarget?.season,
        resumeInfo?.season,
        vmLoadedSeason,
        isLoading
    ) {
        if (type != "series") {
            null
        } else {
            selectedSeason
                ?: initialTarget?.season?.takeIf { it in seasons }
                ?: resumeInfo?.season?.takeIf { it in seasons }
                ?: vmLoadedSeason?.takeIf { it in seasons }
                ?: seasons.firstOrNull().takeIf { !isLoading }
        }
    }

    val hasExplicitSeasonSource = type == "series" && (
        selectedSeason != null ||
            initialTarget?.season?.takeIf { it in seasons } != null ||
            resumeInfo?.season?.takeIf { it in seasons } != null ||
            vmLoadedSeason?.takeIf { it in seasons } != null
        )

    LaunchedEffect(type, id, initialTarget?.season, initialTarget?.episode) {
        selectedSeason = initialTarget?.season
        userManuallyChangedSeason = false
        seasonFocusRequesters.clear()
        episodeFocusRequesters.clear()
        viewModel.load(type, id)
    }

    LaunchedEffect(type, id, effectiveSeason, hasExplicitSeasonSource) {
        if (
            type == "series" &&
            effectiveSeason != null &&
            hasExplicitSeasonSource
        ) {
            viewModel.loadEpisodesForSeason(effectiveSeason)
        }
    }

    val resumeSeason = resumeInfo?.season
    val resumeEpisode = resumeInfo?.episode
    val resumeStreamId = resumeInfo?.episodeStreamId

    val hasSeriesResume =
        type == "series" &&
            (resumeInfo?.positionMs ?: 0L) > 0L &&
            resumeSeason != null &&
            resumeEpisode != null

    val simklSeasonEpisodes = remember(
        type,
        effectiveSeason,
        simklWatchedEpisodes
    ) {
        if (type != "series" || effectiveSeason == null) {
            emptySet<Int>()
        } else {
            simklWatchedEpisodes
                .filter { (season, _) -> season == effectiveSeason }
                .map { (_, episode) -> episode }
                .toSet()
        }
    }

    val locallyWatchedSeasonEpisodes = remember(
        type,
        id,
        effectiveSeason,
        watchedEpisodeKeys
    ) {
        if (type != "series" || effectiveSeason == null) {
            emptySet<Int>()
        } else {
            watchedEpisodeKeys.mapNotNull { key ->
                val parts = key.split(":")
                if (parts.size < 3) return@mapNotNull null

                val keyId = parts.dropLast(2).joinToString(":")
                val season = parts[parts.size - 2].toIntOrNull()
                val episode = parts[parts.size - 1].toIntOrNull()

                if (
                    keyId == id &&
                    season == effectiveSeason &&
                    episode != null
                ) {
                    episode
                } else {
                    null
                }
            }.toSet()
        }
    }

    val watchedEpisodesForSeason = remember(
        simklSeasonEpisodes,
        locallyWatchedSeasonEpisodes
    ) {
        if (simklSeasonEpisodes.isNotEmpty()) {
            simklSeasonEpisodes
        } else {
            locallyWatchedSeasonEpisodes
        }
    }

    val resolvedTargetEpisode = remember(
        type,
        episodes,
        initialTarget?.season,
        initialTarget?.episode,
        initialTarget?.streamId,
        effectiveSeason,
        vmTargetEpisode
    ) {
        if (type != "series") {
            null
        } else if (
            initialTarget?.season == effectiveSeason &&
            initialTarget?.episode != null
        ) {
            episodes.firstOrNull { episode ->
                if (!initialTarget?.streamId.isNullOrBlank() &&
                    episode.streamId == initialTarget?.streamId
                ) {
                    true
                } else {
                    episode.episodeNumber == initialTarget?.episode
                }
            } ?: episodes.firstOrNull {
                it.episodeNumber == initialTarget?.episode
            } ?: episodes.firstOrNull()
        } else {
            vmTargetEpisode?.takeIf { ep ->
                episodes.any { it.streamId == ep.streamId }
            } ?: episodes.firstOrNull()
        }
    }

    val targetEpisodeNumber = remember(
        type,
        resolvedTargetEpisode
    ) {
        if (type != "series") {
            null
        } else {
            resolvedTargetEpisode?.episodeNumber
        }
    }

    val targetEpisodeIndex = remember(
        episodes,
        resolvedTargetEpisode?.streamId,
        targetEpisodeNumber
    ) {
        when {
            resolvedTargetEpisode?.streamId != null ->
                episodes.indexOfFirst {
                    it.streamId == resolvedTargetEpisode.streamId
                }

            targetEpisodeNumber != null ->
                episodes.indexOfFirst {
                    it.episodeNumber == targetEpisodeNumber
                }

            else -> -1
        }
    }

    LaunchedEffect(
        type,
        effectiveSeason,
        episodesLoading,
        targetEpisodeIndex,
        userManuallyChangedSeason
    ) {
        if (
            type == "series" &&
            effectiveSeason != null &&
            !episodesLoading &&
            targetEpisodeIndex >= 0 &&
            !userManuallyChangedSeason
        ) {
            episodesRowState.scrollToItem(targetEpisodeIndex)
        }
    }

    fun focusSeasonOrEpisode(): Boolean {
        val season = effectiveSeason
        val episodeNum = targetEpisodeNumber

        val episodeRequester =
            if (season != null && episodeNum != null) {
                episodeFocusRequesters[season to episodeNum]
            } else {
                null
            }

        if (episodeRequester != null) {
            runCatching {
                episodeRequester.requestFocus()
            }
            return true
        }

        val seasonRequester = season?.let {
            seasonFocusRequesters[it]
        }

        if (seasonRequester != null) {
            runCatching {
                seasonRequester.requestFocus()
            }
            return true
        }

        return false
    }

    LaunchedEffect(
        effectiveSeason,
        episodesLoading,
        episodes,
        episodeTransitionState.edge
    ) {
        val edge = episodeTransitionState.edge ?: return@LaunchedEffect
        if (episodesLoading || episodes.isEmpty()) return@LaunchedEffect

        val season = effectiveSeason ?: return@LaunchedEffect
        val targetIndex =
            if (edge == EpisodeFocusEdge.START) 0 else episodes.lastIndex

        val targetEpisode =
            episodes.getOrNull(targetIndex) ?: return@LaunchedEffect

        episodesRowState.scrollToItem(targetIndex)
        delay(90)

        runCatching {
            episodeFocusRequesters[
                season to targetEpisode.episodeNumber
            ]?.requestFocus()
        }

        clearEpisodeTransitionState()
    }

    fun playTrailer(context: Context) {
        val trailer = tmdbDetail?.videos?.results?.firstOrNull {
            it.site == "YouTube" && it.type == "Trailer"
        } ?: return

        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/watch?v=${trailer.key}")
            )
        )
    }

    when {
        isLoading -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text("Loading...")
            }
        }

        error != null -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text("Error: $error")
            }
        }

        meta != null -> {
            val m = meta!!

            val displayName = remember(m, tmdbDetail) {
                m.name.ifBlank {
                    tmdbDetail?.name
                        ?: tmdbDetail?.title
                        ?: m.name
                }
            }

            val keywords = remember(tmdbDetail) {
                tmdbDetail?.keywords?.list().orEmpty()
            }

            val backdropUrl = remember(tmdbDetail, m) {
                tmdbDetail?.backdropPath?.let {
                    TmdbRepository.BACKDROP_BASE + it
                } ?: m.background
                    ?: m.poster
                    ?: initialPoster
            }

            val context = LocalContext.current

            val playLabel: String
            val playTarget: StreamsTarget

            if (type == "movie") {
                val hasResume =
                    resumeInfo != null &&
                        resumeInfo!!.positionMs > 0

                playLabel =
                    if (hasResume) "RESUME" else "PLAY"

                playTarget = remember(
                    resumeInfo,
                    id,
                    displayName
                ) {
                    StreamsTarget(
                        contentType = "movie",
                        streamId = id,
                        title = displayName,
                        displayName = displayName,
                        season = null,
                        episode = null,
                        resumePositionMs =
                            resumeInfo?.positionMs ?: 0L
                    )
                }
            } else {
                val targetSeason =
                    effectiveSeason
                        ?: initialTarget?.season?.takeIf {
                            it in seasons
                        }
                        ?: seasons.firstOrNull()
                        ?: 1

                val targetEpisode =
                    resolvedTargetEpisode?.episodeNumber
                        ?: initialTarget?.episode
                        ?: resumeEpisode
                        ?: 1

                val isResumingHere =
                    hasSeriesResume &&
                        targetSeason == resumeSeason &&
                        targetEpisode == resumeEpisode

                val isDeepLinkedHere =
                    initialTarget?.season == effectiveSeason &&
                        initialTarget?.episode != null &&
                        !isResumingHere

                val targetStreamId =
                    resolvedTargetEpisode?.streamId
                        ?: resumeStreamId?.takeIf {
                            isResumingHere
                        }
                        ?: initialTarget?.streamId?.takeIf {
                            initialTarget.season == targetSeason &&
                                initialTarget.episode == targetEpisode
                        }
                        ?: "$id:$targetSeason:$targetEpisode"

                playLabel = when {
                    isDeepLinkedHere ->
                        "PLAY S${targetSeason} E${targetEpisode}"

                    resolvedTargetEpisode != null ->
                        vmPlayButtonText

                    else ->
                        "PLAY"
                }

                playTarget = remember(
                    id,
                    displayName,
                    targetSeason,
                    targetEpisode,
                    targetStreamId,
                    isResumingHere,
                    resumeInfo?.positionMs
                ) {
                    StreamsTarget(
                        contentType = "series",
                        streamId = targetStreamId,
                        title =
                            "$displayName S$targetSeason E$targetEpisode",
                        displayName = displayName,
                        season = targetSeason,
                        episode = targetEpisode,
                        resumePositionMs =
                            if (isResumingHere) {
                                resumeInfo?.positionMs ?: 0L
                            } else {
                                0L
                            }
                    )
                }
            }

            LaunchedEffect(Unit) {
                runCatching {
                    playButtonFocusRequester.requestFocus()
                }
            }

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                AsyncImage(
                    model = remember(backdropUrl) {
                        ImageRequest.Builder(context)
                            .data(backdropUrl)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = displayName,
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

                // FIXED: the title/meta-line/genre header sits in the top
                // portion of the screen, where the vertical gradient above
                // is still mostly transparent -- so on bright backdrops the
                // dim KBTextLo meta line (rating/year/runtime/IMDb) was
                // barely legible. This adds a left-side horizontal scrim
                // behind the whole header block, independent of how bright
                // or dark the backdrop happens to be, without darkening the
                // rest of the image.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to KBVoid.copy(alpha = 0.85f),
                                    .40f to KBVoid.copy(alpha = 0.60f),
                                    .70f to KBVoid.copy(alpha = 0.18f),
                                    1f to Color.Transparent
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Spacer(
                        modifier = Modifier.height(20.dp)
                    )

                    Column(
                        modifier = Modifier.padding(
                            horizontal = 24.dp
                        )
                    ) {
                        if (!clearLogoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = clearLogoUrl,
                                contentDescription = displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(68.dp)
                            )
                        } else {
                            Text(
                                displayName,
                                style = MaterialTheme.typography.headlineLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .padding(
                                start = 24.dp,
                                top = 8.dp,
                                bottom = 6.dp
                            )
                            .focusGroup()
                            .focusRestorer()
                    ) {
                        KBCard(
                            onClick = {
                                onNavigateStreams(
                                    playTarget,
                                    id,
                                    type,
                                    m.poster,
                                    backdropUrl,
                                    clearLogoUrl
                                )
                            },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .focusRequester(
                                    playButtonFocusRequester
                                )
                        ) {
                            Text(
                                playLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(
                                    horizontal = 9.dp,
                                    vertical = 7.dp
                                )
                            )
                        }

                        if (hasSeriesResume) {
                            KBCard(
                                onClick = {
                                    val resumeTarget =
                                        playTarget.copy(
                                            resumePositionMs =
                                                resumeInfo?.positionMs
                                                    ?: 0L
                                        )

                                    onNavigateStreams(
                                        resumeTarget,
                                        id,
                                        type,
                                        m.poster,
                                        backdropUrl,
                                        clearLogoUrl
                                    )
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    "RESUME",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(
                                        horizontal = 9.dp,
                                        vertical = 7.dp
                                    )
                                )
                            }
                        }

                        if (
                            tmdbDetail?.videos?.results?.any {
                                it.site == "YouTube" &&
                                    it.type == "Trailer"
                            } == true
                        ) {
                            KBCard(
                                onClick = {
                                    playTrailer(context)
                                }
                            ) {
                                Text(
                                    "TRAILER",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(
                                        horizontal = 9.dp,
                                        vertical = 7.dp
                                    )
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.padding(
                            start = 24.dp,
                            end = 24.dp,
                            bottom = 6.dp
                        )
                    ) {
                        val metaLine = remember(
                            m,
                            tmdbDetail,
                            type
                        ) {
                            val yearInfo =
                                if (type == "series") {
                                    formatSeriesYearRange(
                                        tmdbDetail?.firstAirDate,
                                        tmdbDetail?.lastEpisodeToAir?.airDate,
                                        tmdbDetail?.status
                                    ) ?: tmdbDetail?.releaseYear()
                                        ?: m.releaseInfo
                                } else {
                                    tmdbDetail?.releaseYear()
                                        ?: m.releaseInfo
                                }

                            listOfNotNull(
                                tmdbDetail?.certification(
                                    type == "movie"
                                ),
                                yearInfo,
                                if (type == "series") {
                                    seriesStatusTag(
                                        tmdbDetail?.status
                                    )
                                } else {
                                    null
                                },
                                m.runtime,
                                m.imdbRating?.let {
                                    "IMDb $it"
                                }
                            ).joinToString(" • ")
                        }

                        if (metaLine.isNotBlank()) {
                            Text(
                                metaLine,
                                color = KBTextHi,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    top = 2.dp
                                )
                            )
                        }

                        val tmdbGenres =
                            tmdbDetail?.genres.orEmpty()

                        if (tmdbGenres.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(
                                    top = 6.dp,
                                    bottom = 6.dp
                                ),
                                modifier = Modifier
                                    .focusGroup()
                                    .focusRestorer()
                                    .onPreviewKeyEvent {
                                        keyEvent: KeyEvent ->
                                        if (
                                            type == "series" &&
                                            keywords.isEmpty() &&
                                            keyEvent.type ==
                                                KeyEventType.KeyDown &&
                                            keyEvent.key ==
                                                Key.DirectionDown
                                        ) {
                                            focusSeasonOrEpisode()
                                        } else {
                                            false
                                        }
                                    }
                            ) {
                                items(
                                    tmdbGenres,
                                    key = { it.id }
                                ) { genre ->
                                    GenreChip(
                                        name = genre.name,
                                        onClick = {
                                            onNavigateTag(
                                                genre.id,
                                                genre.name,
                                                false,
                                                type
                                            )
                                        },
                                        modifier = Modifier.padding(
                                            end = 8.dp
                                        )
                                    )
                                }
                            }
                        } else {
                            m.genres
                                ?.takeIf { it.isNotEmpty() }
                                ?.let {
                                    Text(
                                        it.joinToString(", "),
                                        color = KBTextLo,
                                        maxLines = 1,
                                        overflow =
                                            TextOverflow.Ellipsis,
                                        modifier =
                                            Modifier.padding(top = 2.dp)
                                    )
                                }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                            .focusGroup()
                            .focusRestorer(),
                        contentPadding = PaddingValues(
                            top = 6.dp
                        )
                    ) {
                        item(key = "infoblock") {
                            Column(
                                modifier = Modifier.padding(
                                    start = 24.dp,
                                    end = 24.dp,
                                    bottom = 12.dp
                                )
                            ) {
                                m.description?.let {
                                    Text(
                                        it,
                                        modifier = Modifier.padding(
                                            top = 4.dp
                                        )
                                    )
                                }
                            }
                        }

                        val detailFacts = buildList {
                            tmdbDetail?.bestReleaseDate()
                                ?.let {
                                    formatDisplayDate(it)
                                }
                                ?.let {
                                    add(
                                        DetailFactItem(
                                            "Release Date",
                                            it
                                        )
                                    )
                                }

                            if (type == "movie") {
                                tmdbDetail?.budget
                                    ?.takeIf { it > 0L }
                                    ?.let {
                                        add(
                                            DetailFactItem(
                                                "Budget",
                                                formatUsd(it)
                                            )
                                        )
                                    }

                                tmdbDetail?.revenue
                                    ?.takeIf { it > 0L }
                                    ?.let {
                                        add(
                                            DetailFactItem(
                                                "Revenue",
                                                formatUsd(it)
                                            )
                                        )
                                    }
                            }

                            m.country
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    add(
                                        DetailFactItem(
                                            "Country",
                                            it
                                        )
                                    )
                                }

                            m.language
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    add(
                                        DetailFactItem(
                                            "Language",
                                            it
                                        )
                                    )
                                }

                            m.awards
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    add(
                                        DetailFactItem(
                                            "Awards",
                                            it
                                        )
                                    )
                                }
                        }

                        if (detailFacts.isNotEmpty()) {
                            item(key = "detailfacts") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 2.dp,
                                            bottom = 6.dp
                                        ),
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        detailFacts,
                                        key = { it.label }
                                    ) { fact ->
                                        DetailFactCard(
                                            fact = fact,
                                            modifier =
                                                Modifier.padding(
                                                    end = 8.dp
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (keywords.isNotEmpty()) {
                            item(key = "keywordsrow") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 6.dp,
                                            bottom = 6.dp
                                        ),
                                    modifier = Modifier
                                        .padding(bottom = 10.dp)
                                        .focusGroup()
                                        .focusRestorer()
                                        .onPreviewKeyEvent {
                                            keyEvent: KeyEvent ->
                                            if (
                                                keyEvent.type !=
                                                    KeyEventType.KeyDown ||
                                                keyEvent.key !=
                                                    Key.DirectionDown
                                            ) {
                                                false
                                            } else if (
                                                type == "movie"
                                            ) {
                                                movieDetailsFocusRequester
                                                    .requestFocus()
                                                true
                                            } else if (
                                                type == "series"
                                            ) {
                                                focusSeasonOrEpisode()
                                            } else {
                                                false
                                            }
                                        }
                                ) {
                                    items(
                                        keywords,
                                        key = { it.id }
                                    ) { kw ->
                                        KeywordChip(
                                            name = kw.name,
                                            onClick = {
                                                onNavigateTag(
                                                    kw.id,
                                                    kw.name,
                                                    true,
                                                    type
                                                )
                                            },
                                            modifier =
                                                Modifier.padding(
                                                    end = 6.dp
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (
                            type == "series" &&
                            seasons.isNotEmpty()
                        ) {
                            item(key = "episodesheader") {
                                Text(
                                    "EPISODES",
                                    style =
                                        MaterialTheme.typography.titleSmall,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 14.dp,
                                        bottom = 7.dp
                                    )
                                )
                            }

                            item(key = "seasonrow") {
                                SeasonRow(
                                    seasons = seasons,
                                    currentSelectedSeason =
                                        effectiveSeason,
                                    onSeasonSelected = {
                                        seasonNum ->
                                        userManuallyChangedSeason =
                                            true
                                        clearEpisodeTransitionState()
                                        selectedSeason = seasonNum
                                    },
                                    onSeasonFocused = {
                                        seasonNum ->
                                        if (
                                            selectedSeason !=
                                                seasonNum
                                        ) {
                                            userManuallyChangedSeason =
                                                true
                                            clearEpisodeTransitionState()
                                            selectedSeason =
                                                seasonNum
                                        }
                                    },
                                    seasonFocusRequesters =
                                        seasonFocusRequesters
                                )
                            }

                            when {
                                episodesLoading -> {
                                    item(
                                        key = "episodesloading"
                                    ) {
                                        EpisodesStatusMessage(
                                            icon = "⏳",
                                            message =
                                                "Loading episodes…"
                                        )
                                    }
                                }

                                episodeError != null -> {
                                    item(
                                        key = "episodeserror"
                                    ) {
                                        EpisodesStatusMessage(
                                            icon = "⚠️",
                                            message =
                                                "Couldn't load episodes: $episodeError"
                                        )
                                    }
                                }

                                episodes.isEmpty() -> {
                                    item(
                                        key = "episodesempty"
                                    ) {
                                        EpisodesStatusMessage(
                                            icon = "📭",
                                            message =
                                                "No episodes found for this season."
                                        )
                                    }
                                }

                                else -> {
                                    item(
                                        key = "episodesrow"
                                    ) {
                                        CompositionLocalProvider(
                                            LocalBringIntoViewSpec
                                                provides
                                                LocalTvBringIntoViewSpec
                                        ) {
                                            LazyRow(
                                                state =
                                                    episodesRowState,
                                                contentPadding =
                                                    PaddingValues(
                                                        start = 24.dp,
                                                        end = 24.dp,
                                                        top = 10.dp,
                                                        bottom = 10.dp
                                                    ),
                                                modifier = Modifier
                                                    .padding(
                                                        bottom = 14.dp
                                                    )
                                                    .focusGroup()
                                                    .focusRestorer()
                                            ) {
                                                items(
                                                    items = episodes,
                                                    key = {
                                                        it.streamId
                                                    }
                                                ) { ep ->
                                                    val episodeKey =
                                                        remember(
                                                            id,
                                                            effectiveSeason,
                                                            ep.episodeNumber
                                                        ) {
                                                            effectiveSeason?.let {
                                                                season ->
                                                                "$id:$season:${ep.episodeNumber}"
                                                            }
                                                        }

                                                    val isWatchedFlow =
                                                        remember(
                                                            id,
                                                            type,
                                                            episodeKey
                                                        ) {
                                                            viewModel
                                                                .observeIsWatched(
                                                                    id,
                                                                    type
                                                                )
                                                        }

                                                    val isWatchedCached
                                                            by isWatchedFlow
                                                                .collectAsState()

                                                    val isEpisodeWatched =
                                                        remember(
                                                            ep.episodeNumber,
                                                            watchedEpisodesForSeason,
                                                            episodeKey,
                                                            watchedEpisodeKeys,
                                                            isWatchedCached
                                                        ) {
                                                            isWatchedCached ||
                                                                ep.episodeNumber in
                                                                    watchedEpisodesForSeason ||
                                                                (
                                                                    episodeKey != null &&
                                                                        episodeKey in
                                                                            watchedEpisodeKeys
                                                                    )
                                                        }

                                                    val focusRequester =
                                                        remember {
                                                            FocusRequester()
                                                        }

                                                    effectiveSeason?.let {
                                                        season ->
                                                        episodeFocusRequesters[
                                                            season to ep.episodeNumber
                                                        ] = focusRequester
                                                    }

                                                    val shouldFocusThisCard =
                                                        when {
                                                            resolvedTargetEpisode
                                                                ?.streamId != null ->
                                                                ep.streamId ==
                                                                    resolvedTargetEpisode
                                                                        .streamId

                                                            targetEpisodeNumber != null ->
                                                                ep.episodeNumber ==
                                                                    targetEpisodeNumber

                                                            else -> false
                                                        }

                                                    LaunchedEffect(
                                                        shouldFocusThisCard,
                                                        episodesLoading,
                                                        effectiveSeason,
                                                        episodes.size,
                                                        userManuallyChangedSeason,
                                                        episodeTransitionState.edge
                                                    ) {
                                                        if (
                                                            shouldFocusThisCard &&
                                                            !episodesLoading &&
                                                            episodes.isNotEmpty() &&
                                                            !userManuallyChangedSeason &&
                                                            episodeTransitionState.edge ==
                                                                null
                                                        ) {
                                                            focusRequester
                                                                .requestFocus()
                                                        }
                                                    }

                                                    val isFirstEpisode =
                                                        ep.streamId ==
                                                            episodes
                                                                .firstOrNull()
                                                                ?.streamId

                                                    val isLastEpisode =
                                                        ep.streamId ==
                                                            episodes
                                                                .lastOrNull()
                                                                ?.streamId

                                                    val currentSeasonIndex =
                                                        seasons.indexOf(
                                                            effectiveSeason
                                                        )

                                                    EpisodeCard(
                                                        ep = ep,
                                                        isWatched =
                                                            isEpisodeWatched,
                                                        onClick = {
                                                            val hasResumeHere =
                                                                resumeInfo
                                                                    ?.episodeStreamId ==
                                                                    ep.streamId

                                                            val epSuffix =
                                                                ep.name?.let {
                                                                    " • $it"
                                                                } ?: ""

                                                            val target =
                                                                StreamsTarget(
                                                                    contentType =
                                                                        "series",
                                                                    streamId =
                                                                        ep.streamId,
                                                                    title =
                                                                        "$displayName S${effectiveSeason ?: 1} E${ep.episodeNumber}$epSuffix",
                                                                    displayName =
                                                                        displayName,
                                                                    season =
                                                                        effectiveSeason,
                                                                    episode =
                                                                        ep.episodeNumber,
                                                                    resumePositionMs =
                                                                        if (
                                                                            hasResumeHere
                                                                        ) {
                                                                            resumeInfo
                                                                                ?.positionMs
                                                                                ?: 0L
                                                                        } else {
                                                                            0L
                                                                        }
                                                                )

                                                            onNavigateStreams(
                                                                target,
                                                                id,
                                                                type,
                                                                m.poster,
                                                                backdropUrl,
                                                                clearLogoUrl
                                                            )
                                                        },
                                                        modifier = Modifier
                                                            .focusRequester(
                                                                focusRequester
                                                            )
                                                            .onKeyEvent {
                                                                keyEvent ->
                                                                if (
                                                                    keyEvent.type !=
                                                                        KeyEventType.KeyDown
                                                                ) {
                                                                    return@onKeyEvent false
                                                                }

                                                                when {
                                                                    isFirstEpisode &&
                                                                        keyEvent.key ==
                                                                            Key.DirectionLeft &&
                                                                        currentSeasonIndex >
                                                                            0 -> {
                                                                        userManuallyChangedSeason =
                                                                            false
                                                                        episodeTransitionState =
                                                                            EpisodeTransitionState(
                                                                                edge =
                                                                                    EpisodeFocusEdge.END
                                                                            )
                                                                        selectedSeason =
                                                                            seasons[
                                                                                currentSeasonIndex -
                                                                                    1
                                                                            ]
                                                                        true
                                                                    }

                                                                    isLastEpisode &&
                                                                        keyEvent.key ==
                                                                            Key.DirectionRight &&
                                                                        currentSeasonIndex !=
                                                                            -1 &&
                                                                        currentSeasonIndex <
                                                                            seasons.size -
                                                                                1 -> {
                                                                        userManuallyChangedSeason =
                                                                            false
                                                                        episodeTransitionState =
                                                                            EpisodeTransitionState(
                                                                                edge =
                                                                                    EpisodeFocusEdge.START
                                                                            )
                                                                        selectedSeason =
                                                                            seasons[
                                                                                currentSeasonIndex +
                                                                                    1
                                                                            ]
                                                                        true
                                                                    }

                                                                    else -> false
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

                        val peopleItems =
                            buildList<PeopleRowItem> {
                                val tmdbCast =
                                    tmdbDetail?.credits?.cast
                                        .orEmpty()

                                val tmdbDirector =
                                    tmdbDetail?.credits?.director()

                                val tmdbWriters =
                                    tmdbDetail?.credits?.writers()
                                        .orEmpty()
                                        .distinctBy { it.id }

                                val mainWriter =
                                    tmdbWriters.firstOrNull()

                                mainWriter?.let { writer ->
                                    add(
                                        PeopleRowItem.Person(
                                            TmdbCastMember(
                                                writer.id,
                                                writer.name,
                                                "Writer",
                                                writer.profilePath
                                            )
                                        )
                                    )
                                }

                                tmdbDirector?.let { director ->
                                    if (
                                        director.id !=
                                            mainWriter?.id
                                    ) {
                                        add(
                                            PeopleRowItem.Person(
                                                TmdbCastMember(
                                                    director.id,
                                                    director.name,
                                                    "Director",
                                                    director.profilePath
                                                )
                                            )
                                        )
                                    }
                                }

                                val castItems =
                                    tmdbCast
                                        .distinctBy { it.id }
                                        .take(15)
                                        .map {
                                            PeopleRowItem.Person(it)
                                        }

                                if (
                                    castItems.isNotEmpty() &&
                                    isNotEmpty()
                                ) {
                                    add(
                                        PeopleRowItem.Separator
                                    )
                                }

                                addAll(castItems)
                            }

                        if (peopleItems.isNotEmpty()) {
                            item(key = "peopleheader") {
                                Text(
                                    "PEOPLE",
                                    style =
                                        MaterialTheme.typography.titleSmall,
                                    color = KBTextLo,
                                    modifier = Modifier
                                        .padding(
                                            start = 24.dp,
                                            top = 14.dp,
                                            bottom = 7.dp
                                        )
                                        .focusRequester(
                                            movieDetailsFocusRequester
                                        )
                                        .focusable()
                                )
                            }

                            item(key = "peoplerow") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 16.dp,
                                            bottom = 16.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 12.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        items = peopleItems,
                                        key = { person ->
                                            when (person) {
                                                is PeopleRowItem.Person ->
                                                    "person${person.member.id}${person.member.character.orEmpty()}"

                                                PeopleRowItem.Separator ->
                                                    "peopleseparator"
                                            }
                                        }
                                    ) { person ->
                                        when (person) {
                                            is PeopleRowItem.Person ->
                                                CastCard(
                                                    member =
                                                        person.member,
                                                    onClick = {
                                                        onNavigateActor(
                                                            person.member.id
                                                        )
                                                    }
                                                )

                                            PeopleRowItem.Separator ->
                                                PeopleSeparatorCard()
                                        }
                                    }
                                }
                            }
                        } else if (!m.cast.isNullOrEmpty()) {
                            item(key = "castfallback") {
                                Text(
                                    "Cast ${m.cast.orEmpty().joinToString(", ")}",
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 10.dp,
                                        end = 24.dp
                                    )
                                )
                            }
                        } else {
                            m.director
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { directors ->
                                    item(key = "directorfallback") {
                                        Text(
                                            "Director ${directors.joinToString(", ")}",
                                            color = KBTextLo,
                                            modifier =
                                                Modifier.padding(
                                                    start = 24.dp,
                                                    top = 6.dp,
                                                    end = 24.dp
                                                )
                                        )
                                    }
                                }
                        }

                        val companies =
                            tmdbDetail?.productionCompanies
                                .orEmpty()

                        val networks =
                            tmdbDetail?.networks.orEmpty()

                        if (
                            type == "series" &&
                            networks.isNotEmpty()
                        ) {
                            item(key = "networkheader") {
                                Text(
                                    "NETWORK",
                                    style =
                                        MaterialTheme.typography.titleSmall,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 14.dp,
                                        bottom = 7.dp
                                    )
                                )
                            }

                            item(key = "networkrow") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 8.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        networks,
                                        key = { it.id }
                                    ) { n ->
                                        StudioCard(
                                            name = n.name,
                                            logoPath = n.logoPath,
                                            onClick = {
                                                onNavigateStudio(
                                                    n.id,
                                                    n.name,
                                                    true
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (companies.isNotEmpty()) {
                            item(key = "productionheader") {
                                Text(
                                    "PRODUCTION",
                                    style =
                                        MaterialTheme.typography.titleSmall,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 14.dp,
                                        bottom = 7.dp
                                    )
                                )
                            }

                            item(key = "productionrow") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 8.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        companies,
                                        key = { it.id }
                                    ) { c ->
                                        StudioCard(
                                            name = c.name,
                                            logoPath = c.logoPath,
                                            onClick = {
                                                onNavigateStudio(
                                                    c.id,
                                                    c.name,
                                                    false
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        val reviews =
                            tmdbDetail?.reviews?.results
                                .orEmpty()

                        if (reviews.isNotEmpty()) {
                            item(key = "reviewsheader") {
                                Text(
                                    "REVIEWS",
                                    style =
                                        MaterialTheme.typography.titleSmall,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 14.dp,
                                        bottom = 7.dp
                                    )
                                )
                            }

                            item(key = "reviewsrow") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 12.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        reviews.take(10),
                                        key = { it.id }
                                    ) { review ->
                                        ReviewCard(
                                            review = review,
                                            onClick = {
                                                selectedReview = it
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        val collectionParts =
                            collection?.parts
                                .orEmpty()
                                .filter {
                                    it.id != tmdbDetail?.id
                                }

                        if (collectionParts.isNotEmpty()) {
                            item(key = "collectionheader") {
                                Text(
                                    collection?.name?.uppercase()
                                        ?: "COLLECTION",
                                    style =
                                        MaterialTheme.typography.titleSmall,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 14.dp,
                                        bottom = 7.dp
                                    )
                                )
                            }

                            item(key = "collectionrow") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 14.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        collectionParts,
                                        key = { it.id }
                                    ) { part ->
                                        PosterGridCard(
                                            posterPath =
                                                part.posterPath,
                                            contentDescription =
                                                part.title ?: "",
                                            isWatched =
                                                resolvedPosterIds[
                                                    viewModel
                                                        .posterLookupKey(
                                                            part.id,
                                                            "movie"
                                                        )
                                                ]?.let {
                                                    imdbId ->
                                                    viewModel.watchedKey(
                                                        imdbId,
                                                        "movie"
                                                    ) in watchedKeys
                                                } == true,
                                            onClick = {
                                                scope.launch {
                                                    val imdbId =
                                                        viewModel
                                                            .resolveImdbId(
                                                                part.id,
                                                                "movie"
                                                            )

                                                    if (
                                                        imdbId != null
                                                    ) {
                                                        onNavigateDetail(
                                                            "movie",
                                                            imdbId
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        val recs =
                            tmdbDetail?.recommendations
                                ?.results
                                .orEmpty()

                        if (recs.isNotEmpty()) {
                            item(key = "recsheader") {
                                Text(
                                    "MORE LIKE THIS",
                                    style =
                                        MaterialTheme.typography.titleSmall,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 14.dp,
                                        bottom = 7.dp
                                    )
                                )
                            }

                            item(key = "recsrow") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 14.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        recs.take(30),
                                        key = { it.id }
                                    ) { rec ->
                                        PosterGridCard(
                                            posterPath =
                                                rec.posterPath,
                                            contentDescription =
                                                rec.title
                                                    ?: rec.name
                                                    ?: "",
                                            isWatched =
                                                resolvedPosterIds[
                                                    viewModel
                                                        .posterLookupKey(
                                                            rec.id,
                                                            type.lowercase()
                                                        )
                                                ]?.let {
                                                    imdbId ->
                                                    viewModel.watchedKey(
                                                        imdbId,
                                                        type.lowercase()
                                                    ) in watchedKeys
                                                } == true,
                                            onClick = {
                                                scope.launch {
                                                    val imdbId =
                                                        viewModel
                                                            .resolveImdbId(
                                                                rec.id,
                                                                type
                                                            )

                                                    if (
                                                        imdbId != null
                                                    ) {
                                                        onNavigateDetail(
                                                            type,
                                                            imdbId
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "bottomspacer") {
                            Box(
                                modifier = Modifier.height(40.dp)
                            )
                        }
                    }
                }

                selectedReview?.let { review ->
                    ReviewOverlay(
                        review = review,
                        onDismiss = {
                            selectedReview = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailFactCard(
    fact: DetailFactItem,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(
            containerColor =
                KBSurfaceRaised.copy(alpha = 0.96f),
            contentColor = KBTextHi
        )
    ) {
        Column(
            modifier = Modifier
                .width(150.dp)
                .padding(
                    horizontal = 11.dp,
                    vertical = 9.dp
                )
        ) {
            Text(
                text = fact.label.uppercase(),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = fact.value,
                color = KBTextHi,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SeasonChip(
    seasonNumber: Int,
    seasonName: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    KBCard(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            if (it.isFocused) {
                onFocus()
            }
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = seasonName,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isSelected) KBAccent else KBTextHi,
                fontWeight =
                    if (isSelected) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                modifier = Modifier.padding(
                    start = 12.dp,
                    top = 7.dp,
                    end = 12.dp
                )
            )

            Box(
                modifier = Modifier
                    .padding(
                        top = 3.dp,
                        bottom = 6.dp
                    )
                    .width(20.dp)
                    .height(2.dp)
                    .background(
                        if (isSelected) {
                            KBAccent
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

@Composable
private fun SeasonRow(
    seasons: List<Int>,
    currentSelectedSeason: Int?,
    onSeasonSelected: (Int) -> Unit,
    onSeasonFocused: (Int) -> Unit,
    seasonFocusRequesters: MutableMap<Int, FocusRequester>
) {
    LazyRow(
        contentPadding = PaddingValues(
            start = 24.dp,
            end = 24.dp,
            top = 6.dp,
            bottom = 6.dp
        ),
        modifier = Modifier
            .padding(bottom = 10.dp)
            .focusGroup()
    ) {
        items(
            items = seasons,
            key = { it }
        ) { season ->
            val selected =
                season == currentSelectedSeason

            val chipFocusRequester =
                remember(season) {
                    FocusRequester()
                }

            seasonFocusRequesters[
                season
            ] = chipFocusRequester

            SeasonChip(
                seasonNumber = season,
                seasonName =
                    if (season == 0) {
                        "SPECIALS"
                    } else {
                        "SEASON $season"
                    },
                isSelected = selected,
                onClick = {
                    onSeasonSelected(season)
                },
                onFocus = {
                    onSeasonFocused(season)
                },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .focusRequester(
                        chipFocusRequester
                    )
            )
        }
    }
}

@Composable
private fun CastCard(
    member: TmdbCastMember,
    onClick: () -> Unit
) {
    var isFocused by remember {
        mutableStateOf(false)
    }

    val imageScale by animateFloatAsState(
        targetValue =
            if (isFocused) 1.08f else 1f,
        label = "castImageScale"
    )

    val posterUrl = remember(
        member.profilePath
    ) {
        member.profilePath?.let {
            TmdbRepository.PROFILE_BASE + it
        }
    }

    val initials = remember(member.name) {
        member.name
            .trim()
            .split(" ")
            .mapNotNull {
                it.firstOrNull()?.uppercaseChar()
            }
            .take(2)
            .joinToString("")
    }

    Column(
        horizontalAlignment =
            Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .padding(end = 16.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(
                shape = CircleShape
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = KBSurfaceRaised,
                contentColor = KBTextHi,
                focusedContainerColor =
                    KBSurfaceRaised,
                focusedContentColor = KBTextHi,
                pressedContainerColor =
                    KBSurfaceRaised,
                pressedContentColor = KBTextHi
            ),
            scale = ClickableSurfaceDefaults.scale(
                focusedScale = 1.0f
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(
                        1.dp,
                        KBTextLo.copy(alpha = 0.25f)
                    ),
                    shape = CircleShape
                ),
                focusedBorder = Border(
                    border = BorderStroke(
                        3.dp,
                        KBAccent
                    ),
                    shape = CircleShape
                )
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = KBAccent,
                    elevation = 10.dp
                )
            ),
            modifier = Modifier
                .size(88.dp)
                .onFocusChanged {
                    isFocused = it.isFocused
                }
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(imageScale)
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        style =
                            MaterialTheme.typography.titleLarge,
                        color = KBTextLo
                    )
                }
            }
        }

        Text(
            member.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style =
                MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
        )

        member.character?.let {
            Text(
                it,
                color = KBTextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style =
                    MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .
