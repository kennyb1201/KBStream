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
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Size
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.bestReleaseDate
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.movieStatusTag
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.tmdb.director
import com.kennyb1201.kbstream.data.tmdb.list
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.tmdb.tmdbImageOriginal
import com.kennyb1201.kbstream.data.tmdb.writers
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.components.StudioChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.material3.CircularProgressIndicator

data class StreamsTarget(
    val contentType: String,
    val streamId: String,
    val title: String,
    val displayName: String,
    val season: Int?,
    val episode: Int?,
    val resumePositionMs: Long,
    val totalEpisodesInSeason: Int? = null
)

private sealed interface PeopleRowItem {
    data class Person(val member: TmdbCastMember) : PeopleRowItem
    data object Separator : PeopleRowItem
}

private enum class EpisodeFocusEdge { START, END }

private data class EpisodeTransitionState(
    val edge: EpisodeFocusEdge? = null
)

private data class PosterMenuTarget(
    val tmdbId: Int,
    val mediaType: String,
    val name: String
)

private data class SeasonMenuTarget(
    val seasonNumber: Int,
    val seasonName: String,
    val episodeNumbers: List<Int>
)

private data class EpisodeMenuTarget(
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val seasonEpisodeNumbers: List<Int>,
    val streamId: String,
    val overview: String?
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
        String?,
        String?,
        List<TmdbCastMember>
    ) -> Unit,
    initialTarget: StreamsTarget? = null,
    initialPoster: String? = null,
    viewModel: DetailViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var selectedReview by remember { mutableStateOf<TmdbReview?>(null) }

    // Long-press context menu for More Like This / collection rail posters.
    var posterMenu by remember {
        mutableStateOf<PosterMenuTarget?>(
            null
        )
    }

    var lastPosterFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissPosterMenu() {
        posterMenu = null
        lastPosterFocusRequester?.requestFocus()
    }

    // Long-press context menu for the season chips (mark a whole season
    // watched / unwatched).
    var seasonMenu by remember {
        mutableStateOf<SeasonMenuTarget?>(
            null
        )
    }

    var lastSeasonFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissSeasonMenu() {
        seasonMenu = null
        lastSeasonFocusRequester?.requestFocus()
    }

    // Long-press context menu for the episode cards (mark this / previous /
    // whole season, or play manually).
    var episodeMenu by remember {
        mutableStateOf<EpisodeMenuTarget?>(
            null
        )
    }

    var lastEpisodeFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissEpisodeMenu() {
        episodeMenu = null
        lastEpisodeFocusRequester?.requestFocus()
    }

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
    // TMDB clearlogo first (more reliable); add-on logo (fanart.tv etc.) as
    // fallback when TMDB has nothing for this title.
    val clearLogoUrl = tmdbImageOriginal(tmdbDetail?.bestLogoPath())
        ?: meta?.logo?.takeIf { it.isNotBlank() }
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

    // Episode numbers per season, used by the season-chip long-press menu to
    // decide watched state and to mark/unmark the whole season. The loaded
    // season uses the resolved episode list; other seasons fall back to the
    // TMDB episode_count (null when unknown).
    val seasonEpisodeNumbersFor = remember(
        tmdbDetail,
        episodes,
        effectiveSeason
    ) {
        fun numbersFor(seasonNum: Int): List<Int>? {
            if (
                seasonNum == effectiveSeason &&
                episodes.isNotEmpty()
            ) {
                return episodes.map {
                    it.episodeNumber
                }.distinct().sorted()
            }
            val count = tmdbDetail?.seasons
                ?.firstOrNull {
                    it.seasonNumber == seasonNum
                }
                ?.episodeCount
            if (count != null && count > 0) {
                return (1..count).toList()
            }
            return null
        }

        ::numbersFor
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
            // The episode row is lazy, so an off-screen target card isn't
            // composed yet and requestFocus() would silently no-op. Scroll
            // it into view first, then focus after a brief pause.
            val targetIndex = targetEpisodeIndex
            scope.launch {
                if (targetIndex >= 0) {
                    episodesRowState.scrollToItem(targetIndex)
                    delay(90)
                }
                runCatching {
                    episodeRequester.requestFocus()
                }
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

        scope.launch {
            com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher.playTrailer(
                context,
                "https://www.youtube.com/watch?v=${trailer.key}"
            )
        }
    }

    when {
        isLoading -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = KBAccent,
                        strokeWidth = 3.dp
                    )
                }
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

            // Add-on art first (fanart etc. from the meta provider), TMDB
            // backdrop as fallback. When no meta add-on answered, the fallback
            // meta was built from TMDB itself, so this still lands on TMDB art.
            val backdropUrl = remember(tmdbDetail, m) {
                tmdbDetail?.backdropPath?.let {
                    TmdbRepository.BACKDROP_BASE + it
                }
                    ?: m.background
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
                            },
                        totalEpisodesInSeason = episodes.size
                    )
                }
            }

            LaunchedEffect(Unit) {
                runCatching {
                    playButtonFocusRequester.requestFocus()
                }
            }

            // Continue Watching / Up Next deep-links auto-resume playback
            // once the metadata (and episodes, for series) are ready, so the
            // streams screen gets the rich backdrop/clearlogo/overview/cast
            // without the user having to press Play again.
            var autoPlayed by remember { mutableStateOf(false) }
            LaunchedEffect(
                initialTarget,
                isLoading,
                meta,
                tmdbDetail,
                episodes,
                episodesLoading,
                resumeInfo
            ) {
                if (
                    autoPlayed ||
                    initialTarget == null ||
                    isLoading ||
                    meta == null
                ) return@LaunchedEffect
                if (
                    type == "series" &&
                    (episodesLoading || episodes.isEmpty())
                ) return@LaunchedEffect
                if (
                    type != "series" &&
                    resumeInfo == null
                ) return@LaunchedEffect

                autoPlayed = true
                onNavigateStreams(
                    playTarget,
                    id,
                    type,
                    m.poster,
                    backdropUrl,
                    clearLogoUrl,
                    m.description,
                    tmdbDetail?.credits?.cast.orEmpty()
                )
            }

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                AsyncImage(
            model = remember(backdropUrl) {
                ImageRequest.Builder(context)
                    .data(backdropUrl)
                    .size(Size(1280, 720))
                    .allowHardware(true)
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
                                    KBVoid.copy(alpha = 0.32f),
                                    KBVoid.copy(alpha = 0.80f)
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
                                    0f to KBVoid.copy(alpha = 0.55f),
                                    .40f to KBVoid.copy(alpha = 0.36f),
                                    .70f to KBVoid.copy(alpha = 0.10f),
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
                                    clearLogoUrl,
                                    m.description,
                                    tmdbDetail?.credits?.cast.orEmpty()
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
                                when {
                                    type.equals(
                                        "series",
                                        ignoreCase = true
                                    ) ->
                                        seriesStatusTag(
                                            tmdbDetail?.status
                                        )

                                    type.equals(
                                        "movie",
                                        ignoreCase = true
                                    ) ->
                                        tmdbDetail
                                            ?.movieStatusTag()
                                            ?.uppercase()

                                    else -> null
                                },
                                tmdbDetail?.runtime
                                    ?.takeIf { it > 0 }
                                    ?.let { "${it} min" }
                                    ?: m.runtime,
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
                                val descriptionText =
                                    tmdbDetail?.overview
                                        ?.takeIf {
                                            it.isNotBlank()
                                        }
                                        ?: m.description

                                descriptionText?.let {
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
                                        seasonFocusRequesters,
                                    parentId = id,
                                    watchedEpisodeKeys =
                                        watchedEpisodeKeys,
                                    simklWatchedEpisodes =
                                        simklWatchedEpisodes,
                                    seasonEpisodeNumbersFor =
                                        seasonEpisodeNumbersFor,
                                    onSeasonLongPress = {
                                        seasonNum,
                                        seasonName,
                                        episodeNumbers,
                                        focusRequester ->
                                        lastSeasonFocusRequester =
                                            focusRequester
                                        seasonMenu =
                                            SeasonMenuTarget(
                                                seasonNumber =
                                                    seasonNum,
                                                seasonName =
                                                    seasonName,
                                                episodeNumbers =
                                                    episodeNumbers
                                            )
                                    }
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
                                                        progressFraction =
                                                            if (
                                                                resumeInfo?.episodeStreamId == ep.streamId &&
                                                                    (resumeInfo?.durationMs ?: 0L) > 0L
                                                            ) {
                                                                ((resumeInfo?.positionMs ?: 0L).toFloat() /
                                                                    (resumeInfo?.durationMs ?: 0L).toFloat())
                                                                    .coerceIn(0f, 1f)
                                                            } else {
                                                                0f
                                                            },
                                                        fallbackImageUrl =
                                                            backdropUrl
                                                                ?: m.poster,
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
                                                                        },
                                                                    totalEpisodesInSeason = episodes.size
                                                                )

                                                            onNavigateStreams(
                                                                target,
                                                                id,
                                                                type,
                                                                m.poster,
                                                                backdropUrl,
                                                                clearLogoUrl,
                                                                ep.overview
                                                                    ?: m.description,
                                                                tmdbDetail?.credits?.cast.orEmpty()
                                                            )
                                                        },
                                                        onLongClick = {
                                                            val seasonForMenu =
                                                                effectiveSeason
                                                            if (
                                                                seasonForMenu !=
                                                                null
                                                            ) {
                                                                lastEpisodeFocusRequester =
                                                                    focusRequester
                                                                episodeMenu =
                                                                    EpisodeMenuTarget(
                                                                        season =
                                                                            seasonForMenu,
                                                                        episode =
                                                                            ep.episodeNumber,
                                                                        episodeTitle =
                                                                            ep.name,
                                                                        seasonEpisodeNumbers =
                                                                            episodes
                                                                                .map {
                                                                                    it.episodeNumber
                                                                                }
                                                                                .distinct()
                                                                                .sorted(),
                                                                        streamId =
                                                                            ep.streamId,
                                                                        overview =
                                                                            ep.overview
                                                                    )
                                                            }
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
                                        .take(25)
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
                                        StudioChip(
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
                                        StudioChip(
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
                                        // Focus requester for restoring focus
                                        // after the long-press menu dismisses.
                                        val requester = remember(
                                            part.id
                                        ) {
                                            FocusRequester()
                                        }

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
                                            },
                                            onLongClick = {
                                                lastPosterFocusRequester =
                                                    requester
                                                posterMenu =
                                                    PosterMenuTarget(
                                                        tmdbId = part.id,
                                                        mediaType = "movie",
                                                        name = part.title ?: ""
                                                    )
                                            },
                                            modifier = Modifier
                                                .focusRequester(requester)
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
                                        // Focus requester for restoring focus
                                        // after the long-press menu dismisses.
                                        val requester = remember(
                                            rec.id
                                        ) {
                                            FocusRequester()
                                        }

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
                                            },
                                            onLongClick = {
                                                lastPosterFocusRequester =
                                                    requester
                                                posterMenu =
                                                    PosterMenuTarget(
                                                        tmdbId = rec.id,
                                                        mediaType = type,
                                                        name = rec.title
                                                            ?: rec.name
                                                            ?: ""
                                                    )
                                            },
                                            modifier = Modifier
                                                .focusRequester(requester)
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

                posterMenu?.let { target ->
                    // Same key/type the rail badges use, so the toggle always
                    // matches what the poster currently shows.
                    val menuMediaType =
                        target.mediaType.lowercase()

                    val isWatched =
                        resolvedPosterIds[
                            viewModel.posterLookupKey(
                                target.tmdbId,
                                menuMediaType
                            )
                        ]?.let { imdbId ->
                            viewModel.watchedKey(
                                imdbId,
                                menuMediaType
                            ) in watchedKeys
                        } == true

                    PosterContextMenu(
                        title = target.name.ifBlank { "Untitled" },
                        actions = listOf(
                            PosterContextAction(
                                label = "Go to Details",
                                description = "Open this title's detail page"
                            ) {
                                val selected = target
                                posterMenu = null
                                scope.launch {
                                    val imdbId = viewModel
                                        .resolveImdbId(
                                            selected.tmdbId,
                                            selected.mediaType
                                        )

                                    if (imdbId != null) {
                                        onNavigateDetail(
                                            selected.mediaType,
                                            imdbId
                                        )
                                    }
                                }
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
                                val selected = target
                                posterMenu = null
                                if (isWatched) {
                                    viewModel.markPosterUnwatched(
                                        selected.tmdbId,
                                        selected.mediaType
                                    )
                                } else {
                                    viewModel.markPosterWatched(
                                        selected.tmdbId,
                                        selected.mediaType
                                    )
                                }
                                lastPosterFocusRequester?.requestFocus()
                            }
                        ),
                        onDismiss = {
                            dismissPosterMenu()
                        }
                    )
                }

                seasonMenu?.let { target ->
                    val seasonWatchedSet =
                        WatchedEpisodeState
                            .effectiveWatchedEpisodesForSeason(
                                parentId = id,
                                season = target.seasonNumber,
                                simklWatchedEpisodes =
                                    simklWatchedEpisodes,
                                watchedEpisodeKeys =
                                    watchedEpisodeKeys
                            )
                    val isSeasonWatched =
                        target.episodeNumbers.isNotEmpty() &&
                            target.episodeNumbers.all {
                                it in seasonWatchedSet
                            }

                    PosterContextMenu(
                        title = displayName.ifBlank {
                            "Untitled"
                        },
                        subtitle = target.seasonName,
                        actions = listOf(
                            PosterContextAction(
                                label = if (isSeasonWatched) {
                                    "Mark as Unwatched"
                                } else {
                                    "Mark as Watched"
                                }
                            ) {
                                val selected = target
                                seasonMenu = null
                                if (isSeasonWatched) {
                                    viewModel.markSeasonUnwatched(
                                        selected.seasonNumber,
                                        selected.episodeNumbers
                                    )
                                } else {
                                    viewModel.markSeasonWatched(
                                        selected.seasonNumber,
                                        selected.episodeNumbers
                                    )
                                }
                                lastSeasonFocusRequester?.requestFocus()
                            }
                        ),
                        onDismiss = {
                            dismissSeasonMenu()
                        }
                    )
                }

                episodeMenu?.let { target ->
                    val seasonWatchedSet =
                        WatchedEpisodeState
                            .effectiveWatchedEpisodesForSeason(
                                parentId = id,
                                season = target.season,
                                simklWatchedEpisodes =
                                    simklWatchedEpisodes,
                                watchedEpisodeKeys =
                                    watchedEpisodeKeys
                            )
                    val isEpisodeWatched =
                        target.episode in seasonWatchedSet
                    val isSeasonWatched =
                        target.seasonEpisodeNumbers.isNotEmpty() &&
                            target.seasonEpisodeNumbers.all {
                                it in seasonWatchedSet
                            }

                    val menuActions =
                        buildList {
                            add(
                                PosterContextAction(
                                    label = if (isEpisodeWatched) {
                                        "Mark as Unwatched"
                                    } else {
                                        "Mark as Watched"
                                    }
                                ) {
                                    val selected = target
                                    episodeMenu = null
                                    if (isEpisodeWatched) {
                                        viewModel.markEpisodeUnwatched(
                                            selected.season,
                                            selected.episode
                                        )
                                    } else {
                                        viewModel.markEpisodeWatched(
                                            selected.season,
                                            selected.episode
                                        )
                                    }
                                    lastEpisodeFocusRequester?.requestFocus()
                                }
                            )

                            if (target.episode > 1) {
                                add(
                                    PosterContextAction(
                                        label = if (isEpisodeWatched) {
                                            "Mark Previous as Unwatched"
                                        } else {
                                            "Mark Previous as Watched"
                                        }
                                    ) {
                                        val selected = target
                                        episodeMenu = null
                                        if (isEpisodeWatched) {
                                            viewModel.markPreviousUnwatched(
                                                selected.season,
                                                selected.episode
                                            )
                                        } else {
                                            viewModel.markPreviousWatched(
                                                selected.season,
                                                selected.episode
                                            )
                                        }
                                        lastEpisodeFocusRequester?.requestFocus()
                                    }
                                )
                            }

                            add(
                                PosterContextAction(
                                    label = if (isSeasonWatched) {
                                        "Mark Season as Unwatched"
                                    } else {
                                        "Mark Season as Watched"
                                    }
                                ) {
                                    val selected = target
                                    episodeMenu = null
                                    if (isSeasonWatched) {
                                        viewModel.markSeasonUnwatched(
                                            selected.season,
                                            selected.seasonEpisodeNumbers
                                        )
                                    } else {
                                        viewModel.markSeasonWatched(
                                            selected.season,
                                            selected.seasonEpisodeNumbers
                                        )
                                    }
                                    lastEpisodeFocusRequester?.requestFocus()
                                }
                            )

                            add(
                                PosterContextAction(
                                    label = "Play Manually"
                                ) {
                                    val selected = target
                                    episodeMenu = null
                                    val hasResumeHere =
                                        resumeInfo
                                            ?.episodeStreamId ==
                                            selected.streamId

                                    val epSuffix =
                                        selected.episodeTitle?.let {
                                            " • $it"
                                        } ?: ""

                                    val playTarget =
                                        StreamsTarget(
                                            contentType = "series",
                                            streamId =
                                                selected.streamId,
                                            title =
                                                "$displayName S${selected.season} E${selected.episode}$epSuffix",
                                            displayName =
                                                displayName,
                                            season =
                                                selected.season,
                                            episode =
                                                selected.episode,
                                            resumePositionMs =
                                                if (
                                                    hasResumeHere
                                                ) {
                                                    resumeInfo
                                                        ?.positionMs
                                                        ?: 0L
                                                } else {
                                                    0L
                                                },
                                            totalEpisodesInSeason =
                                                selected
                                                    .seasonEpisodeNumbers
                                                    .size
                                        )

                                    onNavigateStreams(
                                        playTarget,
                                        id,
                                        type,
                                        m.poster,
                                        backdropUrl,
                                        clearLogoUrl,
                                        selected.overview
                                            ?: m.description,
                                        tmdbDetail?.credits?.cast
                                            .orEmpty()
                                    )
                                }
                            )
                        }

                    PosterContextMenu(
                        title = target.episodeTitle
                            ?.ifBlank { null }
                            ?: "Episode ${target.episode}",
                        subtitle = "S${target.season.toString().padStart(2, '0')} · E${target.episode.toString().padStart(2, '0')}",
                        actions = menuActions,
                        onDismiss = {
                            dismissEpisodeMenu()
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
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
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
    seasonFocusRequesters: MutableMap<Int, FocusRequester>,
    parentId: String,
    watchedEpisodeKeys: Set<String>,
    simklWatchedEpisodes: Set<Pair<Int, Int>>,
    seasonEpisodeNumbersFor: (Int) -> List<Int>?,
    onSeasonLongPress: (
        Int,
        String,
        List<Int>,
        FocusRequester
    ) -> Unit
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

            val seasonName =
                if (season == 0) {
                    "SPECIALS"
                } else {
                    "SEASON $season"
                }

            val episodeNumbers =
                seasonEpisodeNumbersFor(season)

            val watchedSet =
                if (episodeNumbers == null) {
                    emptySet<Int>()
                } else {
                    WatchedEpisodeState
                        .effectiveWatchedEpisodesForSeason(
                            parentId = parentId,
                            season = season,
                            simklWatchedEpisodes =
                                simklWatchedEpisodes,
                            watchedEpisodeKeys =
                                watchedEpisodeKeys
                        )
                }

            val isSeasonWatched =
                episodeNumbers != null &&
                    episodeNumbers.isNotEmpty() &&
                    episodeNumbers.all {
                        it in watchedSet
                    }

            SeasonChip(
                seasonNumber = season,
                seasonName = seasonName,
                isSelected = selected,
                onClick = {
                    onSeasonSelected(season)
                },
                onFocus = {
                    onSeasonFocused(season)
                },
                onLongClick = {
                    val numbers =
                        episodeNumbers
                            ?: (1..(watchedSet
                                .maxOrNull()
                                ?: 0)
                                .coerceAtLeast(1))
                                .toList()
                    onSeasonLongPress(
                        season,
                        seasonName,
                        numbers,
                        chipFocusRequester
                    )
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
            // w342 gives enough pixels for the 88dp circle + 1.08f focus
            // scale without looking grainy on high-density TV panels.
            "https://image.tmdb.org/t/p/w342$it"
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
                    .padding(top = 2.dp)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PeopleSeparatorCard() {
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(88.dp)
            .padding(end = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(6.dp)
                .background(
                    KBTextLo.copy(alpha = 0.7f),
                    CircleShape
                )
        )
    }
}

@Composable
private fun StudioCard(
    name: String,
    logoPath: String?,
    onClick: () -> Unit
) {
    val cardShape =
        RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(
            shape = cardShape
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White,
            contentColor = Color.Black,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1.08f
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.35f)
                ),
                shape = cardShape
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    3.dp,
                    KBAccent
                ),
                shape = cardShape
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = 12.dp
            )
        ),
        modifier = Modifier
            .width(120.dp)
            .height(54.dp)
            .padding(end = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 10.dp,
                    vertical = 8.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            if (logoPath != null) {
                AsyncImage(
                    model = remember(logoPath) {
                        TmdbRepository.PROFILE_BASE +
                            logoPath
                    },
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                )
            } else {
                Text(
                    text = name,
                    color = Color.Black,
                    style =
                        MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    ep: ResolvedEpisode,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    fallbackImageUrl: String? = null,
    progressFraction: Float = 0f
) {
    val posterUrl = remember(ep.thumbnail, fallbackImageUrl) {
        ep.thumbnail?.takeIf {
            it.isNotBlank()
        } ?: fallbackImageUrl?.takeIf {
            it.isNotBlank()
        } ?: ""
    }

    val isUnavailable = remember(ep.airDate) {
        isEpisodeUnavailable(ep.airDate)
    }

    PosterCard(
        posterUrl = posterUrl,
        contentDescription = ep.name ?: "",
        isWatched = isWatched,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(260.dp)
            .height(170.dp)
            .padding(end = 10.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                KBVoid.copy(alpha = 0.2f),
                                KBVoid.copy(alpha = 0.7f),
                                KBVoid.copy(alpha = 0.95f)
                            )
                        )
                    )
            )

            if (isUnavailable) {
                Surface(
                    shape =
                        RoundedCornerShape(4.dp),
                    colors =
                        SurfaceDefaults.colors(
                            containerColor =
                                KBTextLo.copy(
                                    alpha = 0.35f
                                )
                        ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(
                            horizontal = 5.dp,
                            vertical = 1.dp
                        )
                    ) {
                        Text(
                            text =
                                "UNAVAILABLE",
                            style =
                                MaterialTheme.typography.labelSmall,
                            color = KBTextHi
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement =
                    Arrangement.SpaceBetween
            ) {
                Column {
                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(
                            bottom = 4.dp
                        )
                    ) {
                        Surface(
                            shape =
                                RoundedCornerShape(4.dp),
                            colors =
                                SurfaceDefaults.colors(
                                    containerColor =
                                        KBVoid.copy(
                                            alpha = 0.75f
                                        )
                                )
                        ) {
                            Box(
                                modifier = Modifier.padding(
                                    horizontal = 5.dp,
                                    vertical = 1.dp
                                )
                            ) {
                                Text(
                                    text =
                                        "EPISODE ${ep.episodeNumber}",
                                    style =
                                        MaterialTheme.typography.labelSmall,
                                    color = KBTextHi
                                )
                            }
                        }
                    }

                    ep.name?.let { episodeName ->
                        Text(
                            text = episodeName,
                            color = KBTextHi,
                            style =
                                MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow =
                                TextOverflow.Ellipsis
                        )
                    }
                }

                ep.overview
                    ?.takeIf { it.isNotBlank() }
                    ?.let { overviewText ->
                        Text(
                            text = overviewText,
                            color = KBTextLo,
                            style =
                                MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow =
                                TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                vertical = 3.dp
                            )
                        )
                    }

                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ep.runtimeMinutes?.let {
                        runtime ->
                        Text(
                            text = "🕒 ${runtime}m",
                            color = KBTextLo,
                            style =
                                MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(
                                Alignment.CenterStart
                            )
                        )
                    }

                    val rating =
                        ep.voteAverage

                    if (
                        rating != null &&
                        rating > 0.0
                    ) {
                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(6.dp),
                            verticalAlignment =
                                Alignment.CenterVertically,
                            modifier = Modifier.align(
                                Alignment.Center
                            )
                        ) {
                            Surface(
                                shape =
                                    RoundedCornerShape(3.dp),
                                colors =
                                    SurfaceDefaults.colors(
                                        containerColor =
                                            Color(
                                                0xFFF5C518
                                            )
                                    )
                            ) {
                                Box(
                                    modifier =
                                        Modifier.padding(
                                            horizontal = 3.dp,
                                            vertical = 1.dp
                                        )
                                ) {
                                    Text(
                                        text = "IMDb",
                                        color =
                                            Color.Black,
                                        style =
                                            MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            Text(
                                text =
                                    "%.1f".format(
                                        rating
                                    ),
                                color = KBTextHi,
                                style =
                                    MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    ep.airDate
                        ?.takeIf { it.isNotBlank() }
                        ?.let { airDate ->
                            Text(
                                text =
                                    formatEpisodeAirDate(
                                        airDate
                                    ),
                                color = KBTextLo,
                                style =
                                    MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow =
                                    TextOverflow.Ellipsis,
                                modifier = Modifier.align(
                                    Alignment.CenterEnd
                                )
                            )
                        }
                }

                if (progressFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                KBTextLo.copy(alpha = 0.45f),
                                RoundedCornerShape(2.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(
                                    KBAccent,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(50)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor =
                KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor =
                KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1.08f
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(
                    2.dp,
                    KBAccent
                ),
                shape = RoundedCornerShape(50)
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = 12.dp
            )
        )
    ) {
        Text(
            name,
            style =
                MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 6.dp
            )
        )
    }
}

@Composable
private fun KeywordChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(50)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = KBTextLo,
            focusedContainerColor =
                KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor =
                KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1.05f
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    KBTextLo.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(50)
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    2.dp,
                    KBAccent
                ),
                shape = RoundedCornerShape(50)
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = 6.dp
            )
        )
    ) {
        Text(
            "#$name",
            style =
                MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = 11.dp,
                vertical = 5.dp
            )
        )
    }
}

@Composable
private fun EpisodesStatusMessage(
    icon: String,
    message: String
) {
    Row(
        verticalAlignment =
            Alignment.CenterVertically,
        modifier = Modifier
            .padding(
                start = 24.dp,
                end = 24.dp,
                bottom = 12.dp
            )
            .fillMaxWidth()
            .background(
                KBSurface,
                RoundedCornerShape(10.dp)
            )
            .padding(
                horizontal = 12.dp,
                vertical = 10.dp
            )
    ) {
        if (icon == "⏳") {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = KBAccent,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                icon,
                style =
                    MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            message,
            color = KBTextLo,
            style =
                MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(
                start = 8.dp
            )
        )
    }
}

/**
 * Shared poster tile used by both the collection row and
 * recommendations row.
 */
@Composable
private fun PosterGridCard(
    posterPath: String?,
    contentDescription: String,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    PosterCard(
        posterUrl = remember(posterPath) {
            posterPath?.let {
                TmdbRepository.POSTER_BASE + it
            }
        },
        contentDescription = contentDescription,
        isWatched = isWatched,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(110.dp)
            .height(160.dp)
            .padding(end = 10.dp)
    )
}

@Composable
private fun ReviewCard(
    review: TmdbReview,
    onClick: (TmdbReview) -> Unit
) {
    KBCard(
        onClick = {
            onClick(review)
        },
        modifier = Modifier
            .width(240.dp)
            .padding(end = 10.dp)
    ) {
        Column(
            modifier = Modifier.padding(11.dp)
        ) {
            Text(
                text = review.author,
                style =
                    MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = review.content,
                color = KBTextLo,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    top = 6.dp
                )
            )
        }
    }
}

@Composable
private fun ReviewOverlay(
    review: TmdbReview,
    onDismiss: () -> Unit
) {
    val scrollFocusRequester =
        remember { FocusRequester() }

    val scrollState = ScrollState(0)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scrollFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                KBVoid.copy(alpha = 0.88f)
            )
            .onKeyEvent { keyEvent ->
                if (
                    keyEvent.type !=
                        KeyEventType.KeyDown
                ) {
                    return@onKeyEvent false
                }

                when (keyEvent.key) {
                    Key.Back,
                    Key.Escape -> {
                        onDismiss()
                        true
                    }

                    Key.DirectionDown -> {
                        scope.launch {
                            scrollState.scrollTo(
                                scrollState.value + 220
                            )
                        }
                        true
                    }

                    Key.DirectionUp -> {
                        scope.launch {
                            scrollState.scrollTo(
                                (
                                    scrollState.value - 220
                                ).coerceAtLeast(0)
                            )
                        }
                        true
                    }

                    else -> false
                }
            }
    ) {
        Card(
            onClick = {},
            colors = CardDefaults.colors(
                containerColor =
                    KBSurfaceRaised,
                contentColor = KBTextHi
            ),
            modifier = Modifier
                .padding(
                    horizontal = 64.dp,
                    vertical = 40.dp
                )
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Column(
                        modifier =
                            Modifier.weight(1f)
                    ) {
                        Text(
                            text = review.author,
                            style =
                                MaterialTheme.typography.headlineMedium
                        )

                        review.authorDetails?.rating?.let {
                            rating ->
                            Text(
                                text =
                                    "%.1f".format(
                                        rating
                                    ),
                                color = KBAccent,
                                style =
                                    MaterialTheme.typography.bodyMedium,
                                modifier =
                                    Modifier.padding(
                                        top = 4.dp
                                    )
                            )
                        }

                        review.createdAt
                            ?.substringBefore("T")
                            ?.let {
                                formatDisplayDate(it)
                            }
                            ?.let { createdAt ->
                                Text(
                                    text = createdAt,
                                    color = KBTextLo,
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                    modifier =
                                        Modifier.padding(
                                            top = 4.dp
                                        )
                                )
                            }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .focusRequester(
                            scrollFocusRequester
                        )
                        .focusable()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(
                                scrollState
                            )
                    ) {
                        Text(
                            text = review.content,
                            color = KBTextLo,
                            style =
                                MaterialTheme.typography.bodyLarge,
                            modifier =
                                Modifier.padding(
                                    bottom = 32.dp
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Parses an ISO-8601 date string (e.g. TMDB's "yyyy-MM-dd")
 * and formats it as "MM/dd/yyyy".
 */
private fun formatDisplayDate(
    raw: String?
): String? {
    val trimmed = raw?.trim()

    if (trimmed.isNullOrBlank()) {
        return null
    }

    return runCatching {
        LocalDate.parse(trimmed)
            .format(
                DateTimeFormatter.ofPattern(
                    "MM/dd/yyyy"
                )
            )
    }.getOrDefault(trimmed)
}

private fun formatEpisodeAirDate(
    raw: String
): String =
    formatDisplayDate(raw) ?: raw

/**
 * True only when the episode has an announced air date that's still
 * in the future. A missing/unparseable air date is NOT treated as
 * unavailable -- TMDB just doesn't always have one for already-aired
 * episodes, and we don't want to mislabel those.
 */
private fun isEpisodeUnavailable(
    airDate: String?
): Boolean {
    val trimmed = airDate?.trim()

    if (trimmed.isNullOrBlank()) {
        return false
    }

    return runCatching {
        LocalDate.parse(trimmed).isAfter(LocalDate.now())
    }.getOrDefault(false)
}

private fun formatUsd(
    amount: Long
): String =
    NumberFormat
        .getCurrencyInstance(Locale.US)
        .format(amount)

private fun extractYear(
    value: String?
): String? =
    value
        ?.takeIf { it.isNotBlank() }
        ?.take(4)
        ?.takeIf {
            it.length == 4 &&
                it.all(Char::isDigit)
        }

private fun formatSeriesYearRange(
    firstAirDate: String?,
    lastAirDate: String?,
    status: String?
): String? {
    val startYear =
        extractYear(firstAirDate)

    val endYear =
        extractYear(lastAirDate)

    val normalizedStatus =
        status?.trim().orEmpty()

    if (startYear == null) {
        return null
    }

    return when {
        normalizedStatus.equals(
            "Returning Series",
            ignoreCase = true
        ) ||
            normalizedStatus.equals(
                "In Production",
                ignoreCase = true
            ) ->
            "$startYear-"

        !endYear.isNullOrBlank() &&
            endYear != startYear ->
            "$startYear-$endYear"

        !endYear.isNullOrBlank() ->
            endYear

        else ->
            "$startYear-"
    }
}

private fun seriesStatusTag(status: String?): String? {
    val normalizedStatus = status?.trim().orEmpty()
    return when {
        normalizedStatus.equals("Returning Series", ignoreCase = true) -> "ONGOING"
        normalizedStatus.equals("In Production", ignoreCase = true) -> "IN PRODUCTION"
        normalizedStatus.equals("Planned", ignoreCase = true) -> "PLANNED"
        normalizedStatus.equals("Canceled", ignoreCase = true) ||
        normalizedStatus.equals("Cancelled", ignoreCase = true) -> "CANCELED"
        normalizedStatus.equals("Ended", ignoreCase = true) -> "ENDED"
        else -> null
    }
}
