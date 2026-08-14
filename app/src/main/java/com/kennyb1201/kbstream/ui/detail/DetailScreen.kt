package com.kennyb1201.kbstream.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionPart
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.data.tmdb.TmdbKeyword
import com.kennyb1201.kbstream.data.tmdb.TmdbNetwork
import com.kennyb1201.kbstream.data.tmdb.TmdbProductionCompany
import com.kennyb1201.kbstream.data.tmdb.TmdbRecommendationItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import com.kennyb1201.kbstream.data.tmdb.bestReleaseDate
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.director
import com.kennyb1201.kbstream.data.tmdb.list
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.tmdb.writers
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.tmdbImageOriginal
import kotlinx.coroutines.launch
import java.text.NumberFormat
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private class TvPivotBringIntoViewSpec(
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
    onNavigateStreams: (StreamsTarget, String, String, String?) -> Unit,
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
    val episodeFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    var episodeTransitionState by remember { mutableStateOf(EpisodeTransitionState()) }

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

    fun clearEpisodeTransitionState() {
        episodeTransitionState = EpisodeTransitionState()
    }

    // Observe reactive stream status and local watch states
    val hasStreamAddons by viewModel.hasStreamAddons.collectAsState()

    val seasons = remember(tmdbDetail) {
        tmdbDetail?.seasons.orEmpty()
            .map { it.seasonNumber }
            .distinct()
            .sortedWith(compareBy({ it == 0 }, { it }))
    }

    val effectiveSeason = remember(type, selectedSeason, seasons, initialTarget?.season, resumeInfo?.season) {
        if (type != "series") null
        else selectedSeason
            ?: initialTarget?.season?.takeIf { it in seasons }
            ?: resumeInfo?.season?.takeIf { it in seasons }
            ?: seasons.firstOrNull()
    }

    LaunchedEffect(type, id, initialTarget?.season, initialTarget?.episode) {
        selectedSeason = initialTarget?.season
        userManuallyChangedSeason = false
        viewModel.load(type, id)
    }

    LaunchedEffect(type, id, effectiveSeason) {
        if (type == "series" && effectiveSeason != null) {
            if (selectedSeason != effectiveSeason) selectedSeason = effectiveSeason
            viewModel.loadEpisodesForSeason(effectiveSeason)
        }
    }

    val resumeSeason = resumeInfo?.season
    val resumeEpisode = resumeInfo?.episode
    val resumeStreamId = resumeInfo?.episodeStreamId
    val hasSeriesResume = type == "series" && (resumeInfo?.positionMs ?: 0L) > 0L && resumeSeason != null && resumeEpisode != null

    val simklSeasonEpisodes = remember(type, effectiveSeason, simklWatchedEpisodes) {
        if (type != "series" || effectiveSeason == null) emptySet<Int>()
        else simklWatchedEpisodes
            .filter { (season, _) -> season == effectiveSeason }
            .map { (_, episode) -> episode }
            .toSet()
    }

    val locallyWatchedSeasonEpisodes = remember(type, id, effectiveSeason, watchedEpisodeKeys) {
        if (type != "series" || effectiveSeason == null) emptySet<Int>()
        else watchedEpisodeKeys.mapNotNull { key ->
            val parts = key.split(":")
            if (parts.size < 3) return@mapNotNull null
            val keyId = parts.dropLast(2).joinToString(":")
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (keyId == id && season == effectiveSeason && episode != null) episode else null
        }.toSet()
    }

    val watchedEpisodesForSeason = remember(simklSeasonEpisodes, locallyWatchedSeasonEpisodes) {
        if (simklSeasonEpisodes.isNotEmpty()) simklSeasonEpisodes else locallyWatchedSeasonEpisodes
    }

    val resolvedTargetEpisode = remember(
        type,
        effectiveSeason,
        episodes,
        initialTarget?.season,
        initialTarget?.episode,
        initialTarget?.streamId,
        resumeSeason,
        resumeEpisode,
        resumeStreamId,
        hasSeriesResume,
        watchedEpisodesForSeason
    ) {
        if (type != "series") {
            null
        } else if (initialTarget?.season == effectiveSeason && initialTarget?.episode != null) {
            episodes.firstOrNull { episode ->
                if (!initialTarget?.streamId.isNullOrBlank() && episode.streamId == initialTarget?.streamId) {
                    true
                } else {
                    episode.episodeNumber == initialTarget?.episode
                }
            } ?: episodes.firstOrNull { episode ->
                episode.episodeNumber == initialTarget?.episode
            } ?: episodes.firstOrNull()
        } else if (hasSeriesResume && effectiveSeason == resumeSeason) {
            episodes.firstOrNull { episode ->
                (resumeStreamId != null && episode.streamId == resumeStreamId) ||
                    episode.episodeNumber == resumeEpisode
            } ?: episodes.firstOrNull { episode ->
                episode.episodeNumber == resumeEpisode
            } ?: episodes.firstOrNull()
        } else {
            episodes.firstOrNull { episode ->
                episode.episodeNumber !in watchedEpisodesForSeason
            } ?: episodes.firstOrNull()
        }
    }

    val targetEpisodeNumber = remember(
        type,
        effectiveSeason,
        initialTarget?.season,
        initialTarget?.episode,
        hasSeriesResume,
        resumeSeason,
        resumeEpisode,
        resolvedTargetEpisode
    ) {
        if (type != "series") null
        else if (initialTarget?.season == effectiveSeason && initialTarget?.episode != null)
            resolvedTargetEpisode?.episodeNumber ?: initialTarget?.episode
        else if (hasSeriesResume && effectiveSeason == resumeSeason)
            resolvedTargetEpisode?.episodeNumber ?: resumeEpisode
        else
            resolvedTargetEpisode?.episodeNumber
    }

    val targetEpisodeIndex = remember(episodes, resolvedTargetEpisode?.streamId, targetEpisodeNumber) {
        when {
            resolvedTargetEpisode?.streamId != null -> episodes.indexOfFirst { it.streamId == resolvedTargetEpisode.streamId }
            targetEpisodeNumber != null -> episodes.indexOfFirst { it.episodeNumber == targetEpisodeNumber }
            else -> -1
        }
    }

    // Only auto-scroll to target episode if the user didn't manually change the season chips
    LaunchedEffect(type, effectiveSeason, episodesLoading, targetEpisodeIndex, userManuallyChangedSeason) {
        if (type == "series" && effectiveSeason != null && !episodesLoading && targetEpisodeIndex >= 0 && !userManuallyChangedSeason) {
            episodesRowState.scrollToItem(targetEpisodeIndex)
        }
    }

    LaunchedEffect(effectiveSeason, episodesLoading, episodes, episodeTransitionState.edge) {
        val edge = episodeTransitionState.edge ?: return@LaunchedEffect
        if (episodesLoading || episodes.isEmpty()) return@LaunchedEffect
        val targetIndex = if (edge == EpisodeFocusEdge.START) 0 else episodes.lastIndex
        val targetEpisode = episodes.getOrNull(targetIndex) ?: return@LaunchedEffect
        episodesRowState.scrollToItem(targetIndex)
        kotlinx.coroutines.delay(90)
        runCatching { episodeFocusRequesters[targetEpisode.episodeNumber]?.requestFocus() }
        clearEpisodeTransitionState()
    }

    fun playTrailer(context: android.content.Context) {
        val trailer = tmdbDetail?.videos?.results?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" } ?: return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${trailer.key}")))
    }

    when {
        isLoading -> Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Loading...") }
        error != null -> Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Error: $error") }
        meta != null -> {
            val m = meta!!
            val displayName = remember(m, tmdbDetail) { m.name.ifBlank { tmdbDetail?.name ?: tmdbDetail?.title ?: m.name } }
            val backdropUrl = remember(tmdbDetail, m) { tmdbDetail?.backdropPath?.let { TmdbRepository.BACKDROP_BASE + it } ?: m.background ?: m.poster ?: initialPoster }
            val context = androidx.compose.ui.platform.LocalContext.current

            val playLabel: String
            val playTarget: StreamsTarget
            if (type == "movie") {
                val hasResume = resumeInfo != null && resumeInfo!!.positionMs > 0
                playLabel = if (hasResume) "RESUME" else "PLAY"
                playTarget = remember(resumeInfo, id, displayName) {
                    StreamsTarget(
                        contentType = "movie",
                        streamId = id,
                        title = displayName,
                        displayName = displayName,
                        season = null,
                        episode = null,
                        resumePositionMs = resumeInfo?.positionMs ?: 0L
                    )
                }
            } else {
                // Play button always reflects the actual continue-watching position,
                // independent of whichever season chip the user is currently browsing.
                val targetSeason = if (hasSeriesResume) resumeSeason!!
                    else initialTarget?.season?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1
                val targetEpisode = if (hasSeriesResume) resumeEpisode!!
                    else initialTarget?.episode ?: 1
                val targetStreamId = if (hasSeriesResume) {
                    resumeStreamId ?: "$id:$targetSeason:$targetEpisode"
                } else {
                    initialTarget?.streamId
                        ?.takeIf { initialTarget.season == targetSeason && initialTarget.episode == targetEpisode }
                        ?: "$id:$targetSeason:$targetEpisode"
                }

                val playLabelPrefix = if (hasSeriesResume) "RESUME" else "PLAY"
                playLabel = "$playLabelPrefix S${targetSeason} E${targetEpisode}"
                playTarget = remember(
                    resumeInfo,
                    id,
                    displayName,
                    targetSeason,
                    targetEpisode,
                    targetStreamId,
                    resolvedTargetEpisode
                ) {
                    StreamsTarget(
                        contentType = "series",
                        streamId = targetStreamId,
                        title = "$displayName S$targetSeason E$targetEpisode",
                        displayName = displayName,
                        season = targetSeason,
                        episode = targetEpisode,
                        resumePositionMs = if (hasSeriesResume && targetSeason == resumeSeason) resumeInfo?.positionMs ?: 0L else 0L
                    )
                }
            }

                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = remember(backdropUrl) {
                            ImageRequest.Builder(context).data(backdropUrl).crossfade(true).build()
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

                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(24.dp))

                        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                            if (!clearLogoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = clearLogoUrl,
                                    contentDescription = displayName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(88.dp)
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
                                .padding(start = 24.dp, top = 12.dp, bottom = 8.dp)
                                .focusGroup()
                                .focusRestorer()
                        ) {
                            KBCard(
                                onClick = { onNavigateStreams(playTarget, id, type, m.poster) },
                                modifier = Modifier.padding(end = 10.dp)
                            ) {
                                Text(
                                    playLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }

                            if (hasSeriesResume) {
                                KBCard(
                                    onClick = { 
                                        val resumeTarget = playTarget.copy(resumePositionMs = resumeInfo?.positionMs ?: 0L)
                                        onNavigateStreams(resumeTarget, id, type, m.poster) 
                                    },
                                    modifier = Modifier.padding(end = 10.dp)
                                ) {
                                    Text(
                                        "RESUME",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }

                            if (tmdbDetail?.videos?.results?.any { it.site == "YouTube" && it.type == "Trailer" } == true) {
                                KBCard(onClick = { playTrailer(context) }) {
                                    Text(
                                        "TRAILER",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                            val metaLine = remember(m, tmdbDetail, type) {
                                listOfNotNull(
                                    tmdbDetail?.certification(type == "movie"),
                                    tmdbDetail?.releaseYear() ?: m.releaseInfo,
                                    m.runtime,
                                    m.imdbRating?.let { "IMDb $it" }
                                ).joinToString(" • ")
                            }
                            if (metaLine.isNotBlank()) {
                                Text(
                                    metaLine,
                                    color = KBTextLo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }

                            val tmdbGenres = tmdbDetail?.genres.orEmpty()
                            if (tmdbGenres.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                                    modifier = Modifier.focusGroup().focusRestorer()
                                ) {
                                    items(tmdbGenres, key = { it.id }) { genre ->
                                        GenreChip(
                                            name = genre.name,
                                            onClick = { onNavigateTag(genre.id, genre.name, false, type) },
                                            modifier = Modifier.padding(end = 10.dp)
                                        )
                                    }
                                }
                            } else {
                                m.genres?.takeIf { it.isNotEmpty() }?.let {
                                    Text(
                                        it.joinToString(", "),
                                        color = KBTextLo,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = true).fillMaxWidth().focusGroup(),
                            contentPadding = PaddingValues(top = 8.dp)
                        ) {
                            item(key = "infoblock") {
                                Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                                    m.description?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
                                    tmdbDetail?.bestReleaseDate()?.let { Text("Release Date $it", color = KBTextLo, modifier = Modifier.padding(top = 4.dp)) }
                                    if (type == "movie") {
                                        tmdbDetail?.budget?.takeIf { it > 0L }?.let { Text("Budget ${formatUsd(it)}", color = KBTextLo, modifier = Modifier.padding(top = 4.dp)) }
                                        tmdbDetail?.revenue?.takeIf { it > 0L }?.let { Text("Revenue ${formatUsd(it)}", color = KBTextLo, modifier = Modifier.padding(top = 4.dp)) }
                                    }
                                    m.country?.let { Text("Country $it", color = KBTextLo, modifier = Modifier.padding(top = 4.dp)) }
                                    m.language?.let { Text("Language $it", color = KBTextLo, modifier = Modifier.padding(top = 4.dp)) }
                                    m.awards?.let { Text("Awards $it", color = KBTextLo, modifier = Modifier.padding(top = 4.dp)) }
                                }
                            }

                            val keywords = tmdbDetail?.keywords?.list().orEmpty()
                            if (keywords.isNotEmpty()) {
                                item(key = "keywordsrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
                                        modifier = Modifier.padding(bottom = 16.dp).focusGroup()
                                    ) {
                                        items(keywords, key = { it.id }) { kw ->
                                            KeywordChip(
                                                name = kw.name,
                                                onClick = { onNavigateTag(kw.id, kw.name, true, type) },
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            if (type == "series" && seasons.isNotEmpty()) {
                                item(key = "episodesheader") {
                                    Text(
                                        "EPISODES",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }

                                item(key = "seasonrow") {
                                    SeasonRow(
                                        seasons = seasons,
                                        currentSelectedSeason = effectiveSeason,
                                        onSeasonSelected = { seasonNum ->
                                            userManuallyChangedSeason = true
                                            clearEpisodeTransitionState()
                                            selectedSeason = seasonNum
                                        },
                                        onSeasonFocused = { seasonNum ->
                                            if (selectedSeason != seasonNum) {
                                                userManuallyChangedSeason = true
                                                clearEpisodeTransitionState()
                                                selectedSeason = seasonNum
                                            }
                                        },
                                        seasonFocusRequesters = seasonFocusRequesters
                                    )
                                }

                                when {
                                    episodesLoading -> {
                                        item(key = "episodesloading") {
                                            EpisodesStatusMessage(icon = "⏳", message = "Loading episodes…")
                                        }
                                    }
                                    episodeError != null -> {
                                        item(key = "episodeserror") {
                                            EpisodesStatusMessage(
                                                icon = "⚠️",
                                                message = "Couldn't load episodes: $episodeError"
                                            )
                                        }
                                    }
                                    episodes.isEmpty() -> {
                                        item(key = "episodesempty") {
                                            EpisodesStatusMessage(
                                                icon = "📭",
                                                message = "No episodes found for this season."
                                            )
                                        }
                                    }
                                    else -> {
                                        item(key = "episodesrow") {
                                            CompositionLocalProvider(LocalBringIntoViewSpec provides LocalTvBringIntoViewSpec) {
                                            LazyRow(
                                                state = episodesRowState,
                                                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                                modifier = Modifier.padding(bottom = 20.dp).focusGroup().focusRestorer()
                                            ) {
                                                items(items = episodes, key = { it.streamId }) { ep ->
                                                    val episodeKey = remember(id, effectiveSeason, ep.episodeNumber) {
                                                        effectiveSeason?.let { season -> "$id:$season:${ep.episodeNumber}" }
                                                    }

                                                    val isWatchedFlow = remember(id, type, episodeKey) {
                                                        viewModel.observeIsWatched(id, type)
                                                    }
                                                    val isWatchedCached by isWatchedFlow.collectAsState()

                                                    val isEpisodeWatched = remember(ep.episodeNumber, watchedEpisodesForSeason, episodeKey, watchedEpisodeKeys, isWatchedCached) {
                                                        isWatchedCached ||
                                                            ep.episodeNumber in watchedEpisodesForSeason ||
                                                            (episodeKey != null && episodeKey in watchedEpisodeKeys)
                                                    }

                                                    val focusRequester = remember { FocusRequester() }
                                                    episodeFocusRequesters[ep.episodeNumber] = focusRequester
                                                    val shouldFocusThisCard = when {
                                                        resolvedTargetEpisode?.streamId != null -> ep.streamId == resolvedTargetEpisode.streamId
                                                        targetEpisodeNumber != null -> ep.episodeNumber == targetEpisodeNumber
                                                        else -> false
                                                    }

                                                    LaunchedEffect(shouldFocusThisCard, episodesLoading, effectiveSeason, episodes.size, userManuallyChangedSeason, episodeTransitionState.edge) {
                                                        if (shouldFocusThisCard && !episodesLoading && episodes.isNotEmpty() && !userManuallyChangedSeason && episodeTransitionState.edge == null) {
                                                            focusRequester.requestFocus()
                                                        }
                                                    }

                                                    val isFirstEpisode = ep.streamId == episodes.firstOrNull()?.streamId
                                                    val isLastEpisode = ep.streamId == episodes.lastOrNull()?.streamId
                                                    val currentSeasonIndex = seasons.indexOf(effectiveSeason)

                                                    EpisodeCard(
                                                        ep = ep,
                                                        isWatched = isEpisodeWatched,
                                                        onClick = {
                                                            val hasResumeHere = resumeInfo?.episodeStreamId == ep.streamId
                                                            val epSuffix = ep.name?.let { " • $it" } ?: ""
                                                            val target = StreamsTarget(
                                                                contentType = "series",
                                                                streamId = ep.streamId,
                                                                title = "$displayName S${effectiveSeason ?: 1} E${ep.episodeNumber}$epSuffix",
                                                                displayName = displayName,
                                                                season = effectiveSeason,
                                                                episode = ep.episodeNumber,
                                                                resumePositionMs = if (hasResumeHere) resumeInfo?.positionMs ?: 0L else 0L
                                                            )
                                                            onNavigateStreams(target, id, type, m.poster)
                                                        },
                                                        modifier = Modifier
                                                            .focusRequester(focusRequester)
                                                            .onKeyEvent { keyEvent ->
                                                                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                                                                when {
                                                                    isFirstEpisode && keyEvent.key == Key.DirectionLeft && currentSeasonIndex > 0 -> {
                                                                        userManuallyChangedSeason = false
                                                                        episodeTransitionState = EpisodeTransitionState(edge = EpisodeFocusEdge.END)
                                                                        selectedSeason = seasons[currentSeasonIndex - 1]
                                                                        true
                                                                    }
                                                                    isLastEpisode && keyEvent.key == Key.DirectionRight && currentSeasonIndex != -1 && currentSeasonIndex < seasons.size - 1 -> {
                                                                        userManuallyChangedSeason = false
                                                                        episodeTransitionState = EpisodeTransitionState(edge = EpisodeFocusEdge.START)
                                                                        selectedSeason = seasons[currentSeasonIndex + 1]
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

                            val peopleItems = buildList<PeopleRowItem> {
                                val tmdbCast = tmdbDetail?.credits?.cast.orEmpty()
                                val tmdbDirector = tmdbDetail?.credits?.director()
                                val tmdbWriters = tmdbDetail?.credits?.writers().orEmpty().distinctBy { it.id }
                                val mainWriter = tmdbWriters.firstOrNull()
                                mainWriter?.let { writer ->
                                    add(PeopleRowItem.Person(TmdbCastMember(writer.id, writer.name, "Writer", writer.profilePath)))
                                }
                                tmdbDirector?.let { director ->
                                    if (director.id != mainWriter?.id) {
                                        add(PeopleRowItem.Person(TmdbCastMember(director.id, director.name, "Director", director.profilePath)))
                                    }
                                }
                                val castItems = tmdbCast.distinctBy { it.id }.take(15).map { PeopleRowItem.Person(it) }
                                if (castItems.isNotEmpty() && isNotEmpty()) add(PeopleRowItem.Separator)
                                addAll(castItems)
                            }

                            if (peopleItems.isNotEmpty()) {
                                item(key = "peopleheader") {
                                    Text(
                                        "PEOPLE",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "peoplerow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier.padding(bottom = 12.dp).focusGroup()
                                    ) {
                                        items(items = peopleItems, key = { person ->
                                            when (person) {
                                                is PeopleRowItem.Person -> "person${person.member.id}${person.member.character.orEmpty()}"
                                                PeopleRowItem.Separator -> "peopleseparator"
                                            }
                                        }) { person ->
                                            when (person) {
                                                is PeopleRowItem.Person -> CastCard(member = person.member, onClick = { onNavigateActor(person.member.id) })
                                                PeopleRowItem.Separator -> PeopleSeparatorCard()
                                            }
                                        }
                                    }
                                }
                            } else if (!m.cast.isNullOrEmpty()) {
                                item(key = "castfallback") {
                                    Text("Cast ${m.cast!!.joinToString(", ")}", modifier = Modifier.padding(start = 24.dp, top = 12.dp, end = 24.dp))
                                }
                            } else {
                                m.director?.takeIf { it.isNotEmpty() }?.let { directors ->
                                    item(key = "directorfallback") {
                                        Text(
                                            "Director ${directors.joinToString(", ")}",
                                            color = KBTextLo,
                                            modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp)
                                        )
                                    }
                                }
                            }

                            val companies = tmdbDetail?.productionCompanies.orEmpty()
                            val networks = tmdbDetail?.networks.orEmpty()

                            if (type == "series" && networks.isNotEmpty()) {
                                item(key = "networkheader") {
                                    Text(
                                        "NETWORK",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }

                                item(key = "networkrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 16.dp,
                                            bottom = 16.dp
                                        ),
                                        modifier = Modifier
                                            .padding(bottom = 12.dp)
                                            .focusGroup()
                                    ) {
                                        items(networks, key = { it.id }) { n ->
                                            StudioCard(
                                                name = n.name,
                                                logoPath = n.logoPath,
                                                onClick = { onNavigateStudio(n.id, n.name, true) }
                                            )
                                        }
                                    }
                                }
                            }

                            if (companies.isNotEmpty()) {
                                item(key = "productionheader") {
                                    Text(
                                        "PRODUCTION",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }

                                item(key = "productionrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 16.dp,
                                            bottom = 16.dp
                                        ),
                                        modifier = Modifier
                                            .padding(bottom = 12.dp)
                                            .focusGroup()
                                    ) {
                                        items(companies, key = { it.id }) { c ->
                                            StudioCard(
                                                name = c.name,
                                                logoPath = c.logoPath,
                                                onClick = { onNavigateStudio(c.id, c.name, false) }
                                            )
                                        }
                                    }
                                }
                            }

                            val reviews = tmdbDetail?.reviews?.results.orEmpty()
                            if (reviews.isNotEmpty()) {
                                item(key = "reviewsheader") {
                                    Text(
                                        "REVIEWS",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "reviewsrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier.padding(bottom = 20.dp).focusGroup()
                                    ) {
                                        items(reviews.take(10), key = { it.id }) { review ->
                                            ReviewCard(review = review, onClick = { selectedReview = it })
                                        }
                                    }
                                }
                            }

                            val collectionParts = collection?.parts.orEmpty().filter { it.id != tmdbDetail?.id }
                            if (collectionParts.isNotEmpty()) {
                                item(key = "collectionheader") {
                                    Text(
                                        collection?.name?.uppercase() ?: "COLLECTION",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "collectionrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier.padding(bottom = 32.dp).focusGroup()
                                    ) {
                                        items(collectionParts, key = { it.id }) { part ->
                                            CollectionCard(
                                                part = part,
                                                isWatched = resolvedPosterIds[viewModel.posterLookupKey(part.id, "movie")]
                                                    ?.let { imdbId -> viewModel.watchedKey(imdbId, "movie") in watchedKeys } == true,
                                                onClick = {
                                                    scope.launch {
                                                        val imdbId = viewModel.resolveImdbId(part.id, "movie")
                                                        if (imdbId != null) onNavigateDetail("movie", imdbId)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            val recs = tmdbDetail?.recommendations?.results.orEmpty()
                            if (recs.isNotEmpty()) {
                                item(key = "recsheader") {
                                    Text(
                                        "MORE LIKE THIS",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "recsrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier.padding(bottom = 32.dp).focusGroup()
                                    ) {
                                        items(recs.take(30), key = { it.id }) { rec ->
                                            RecCard(
                                                rec = rec,
                                                isWatched = resolvedPosterIds[viewModel.posterLookupKey(rec.id, type.lowercase())]
                                                    ?.let { imdbId -> viewModel.watchedKey(imdbId, type.lowercase()) in watchedKeys } == true,
                                                onClick = {
                                                    scope.launch {
                                                        val imdbId = viewModel.resolveImdbId(rec.id, type)
                                                        if (imdbId != null) onNavigateDetail(type, imdbId)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            item(key = "bottomspacer") { Box(modifier = Modifier.height(48.dp)) }
                        }
                    }

                    selectedReview?.let { review ->
                        ReviewOverlay(review = review, onDismiss = { selectedReview = null })
                    }
                }
            }
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = seasonName,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) KBAccent else KBTextHi,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp)
            )
            // Persistent indicator so the selected season stays identifiable
            // even after focus moves elsewhere (KBCard's glow is focus-only).
            Box(
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 8.dp)
                    .width(24.dp)
                    .height(3.dp)
                    .background(
                        if (isSelected) KBAccent else Color.Transparent,
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
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        modifier = Modifier
            .padding(bottom = 14.dp)
            .focusGroup()
    ) {
        items(items = seasons, key = { it }) { season ->
            val selected = season == currentSelectedSeason
            val chipFocusRequester = remember(season) { FocusRequester() }
            seasonFocusRequesters[season] = chipFocusRequester

            SeasonChip(
                seasonNumber = season,
                seasonName = if (season == 0) "SPECIALS" else "SEASON $season",
                isSelected = selected,
                onClick = { onSeasonSelected(season) },
                onFocus = { onSeasonFocused(season) },
                modifier = Modifier
                    .padding(end = 10.dp)
                    .focusRequester(chipFocusRequester)
            )
        }
    }
}

@Composable
private fun CastCard(member: TmdbCastMember, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val imageScale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1f,
        label = "castImageScale"
    )
    val posterUrl = remember(member.profilePath) {
        member.profilePath?.let { TmdbRepository.PROFILE_BASE + it }
    }
    val initials = remember(member.name) {
        member.name.trim().split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .take(2)
            .joinToString("")
    }

    // Smaller, circular headshot -- avoids the tall poster-card look which felt
    // oversized for cast members. Name/role sit below, centered under the avatar.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .padding(end = 16.dp)
    ) {
        androidx.tv.material3.Surface(
            onClick = onClick,
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = KBSurfaceRaised,
                contentColor = KBTextHi,
                focusedContainerColor = KBSurfaceRaised,
                focusedContentColor = KBTextHi,
                pressedContainerColor = KBSurfaceRaised,
                pressedContentColor = KBTextHi
            ),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                border = androidx.tv.material3.Border(
                    border = BorderStroke(1.dp, KBTextLo.copy(alpha = 0.25f)),
                    shape = CircleShape
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = BorderStroke(3.dp, KBAccent),
                    shape = CircleShape
                )
            ),
            glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(
                focusedGlow = androidx.tv.material3.Glow(
                    elevationColor = KBAccent,
                    elevation = 10.dp
                )
            ),
            modifier = Modifier
                .size(88.dp)
                .onFocusChanged { isFocused = it.isFocused }
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().scale(imageScale)
                )
            } else {
                // No profile photo on TMDB -- show initials instead of a blank circle
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleLarge,
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
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
        )
        member.character?.let {
            Text(
                it,
                color = KBTextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp).fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PeopleSeparatorCard() {
    Box(modifier = Modifier.width(24.dp).height(88.dp).padding(end = 8.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.width(6.dp).height(6.dp).background(KBTextLo.copy(alpha = 0.7f), CircleShape))
    }
}

@Composable
private fun StudioCard(
    name: String,
    logoPath: String?,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(12.dp) // matches KBCard's CardShape exactly

    // Logos need a white plate to stay legible, so container color stays fixed
    // across focus states -- but scale/border/glow match KBCard's signature
    // spotlight interaction used everywhere else in the app.
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = cardShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.White,
            contentColor = Color.Black,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                shape = cardShape
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(3.dp, KBAccent),
                shape = cardShape
            )
        ),
        glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(
            focusedGlow = androidx.tv.material3.Glow(
                elevationColor = KBAccent,
                elevation = 12.dp
            )
        ),
        modifier = Modifier
            .width(140.dp)
            .height(64.dp)
            .padding(end = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            if (logoPath != null) {
                AsyncImage(
                    model = remember(logoPath) { TmdbRepository.PROFILE_BASE + logoPath },
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                )
            } else {
                Text(
                    text = name,
                    color = Color.Black,
                    style = MaterialTheme.typography.labelLarge,
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
    modifier: Modifier = Modifier
) {
    val posterUrl = remember(ep.thumbnail) {
        ep.thumbnail?.takeIf { it.isNotBlank() } ?: ""
    }

    PosterCard(
        posterUrl = posterUrl,
        contentDescription = ep.name ?: "",
        isWatched = isWatched,
        onClick = onClick,
        modifier = modifier
            .width(320.dp)
            .height(210.dp)
            .padding(end = 12.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Dark Gradient Overlay for Readability
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

            // Content Layout Layered Over the Background
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                // Top section: Episode Badge & Title
                Column {
                    androidx.tv.material3.Surface(
                        shape = RoundedCornerShape(4.dp),
                        colors = androidx.tv.material3.SurfaceDefaults.colors(
                            containerColor = KBVoid.copy(alpha = 0.75f)
                        ),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(
                                text = "EPISODE ${ep.episodeNumber}",
                                style = MaterialTheme.typography.labelSmall,
                                color = KBTextHi
                            )
                        }
                    }

                    ep.name?.let { episodeName ->
                        Text(
                            text = episodeName,
                            color = KBTextHi,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Middle section: Description Overview
                ep.overview?.takeIf { it.isNotBlank() }?.let { overviewText ->
                    Text(
                        text = overviewText,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Bottom row: Runtime, IMDb Badge, and Air Date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        // Runtime
                        ep.runtimeMinutes?.let { runtime ->
                            Text(
                                text = "🕒 ${runtime}m",
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // IMDb Badge Simulation
                        val rating = ep.voteAverage
                        if (rating != null && rating > 0.0) {
                            androidx.tv.material3.Surface(
                                shape = RoundedCornerShape(3.dp),
                                colors = androidx.tv.material3.SurfaceDefaults.colors(
                                    containerColor = Color(0xFFF5C518) // IMDb Gold
                                )
                            ) {
                                Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) {
                                    Text(
                                        text = "IMDb",
                                        color = Color.Black,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            Text(
                                text = "%.1f".format(rating),
                                color = KBTextHi,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // Air Date
                    ep.airDate?.takeIf { it.isNotBlank() }?.let { airDate ->
                        Text(
                            text = formatEpisodeAirDate(airDate),
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = RoundedCornerShape(50)
            )
        ),
        glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(
            focusedGlow = androidx.tv.material3.Glow(
                elevationColor = KBAccent,
                elevation = 12.dp
            )
        )
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun KeywordChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = KBTextLo,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = BorderStroke(1.dp, KBTextLo.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(50)
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = RoundedCornerShape(50)
            )
        ),
        glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(
            focusedGlow = androidx.tv.material3.Glow(
                elevationColor = KBAccent,
                elevation = 6.dp
            )
        )
    ) {
        Text(
            "#$name",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun EpisodesStatusMessage(icon: String, message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
            .fillMaxWidth()
            .background(KBSurface, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(icon, style = MaterialTheme.typography.bodyMedium)
        Text(
            message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

@Composable
private fun CollectionCard(part: TmdbCollectionPart, isWatched: Boolean, onClick: () -> Unit) {
    PosterCard(
        posterUrl = remember(part.posterPath) { part.posterPath?.let { TmdbRepository.POSTER_BASE + it } },
        contentDescription = part.title ?: "",
        isWatched = isWatched,
        onClick = onClick,
        modifier = Modifier.width(124.dp).height(180.dp).padding(end = 12.dp)
    )
}

@Composable
private fun ReviewCard(review: TmdbReview, onClick: (TmdbReview) -> Unit) {
    KBCard(
        onClick = { onClick(review) },
        modifier = Modifier.width(280.dp).padding(end = 12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = review.author, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = review.content, color = KBTextLo, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ReviewOverlay(review: TmdbReview, onDismiss: () -> Unit) {
    val scrollFocusRequester = remember { FocusRequester() }
    val scrollState = androidx.compose.foundation.ScrollState(0)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { scrollFocusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid.copy(alpha = 0.88f))
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (keyEvent.key) {
                    Key.Back, Key.Escape -> { onDismiss(); true }
                    Key.DirectionDown -> {
    scope.launch { scrollState.scrollTo(scrollState.value + 220) }
    true
}
Key.DirectionUp -> {
    scope.launch { scrollState.scrollTo((scrollState.value - 220).coerceAtLeast(0)) }
    true
}
                    else -> false
                }
            }
    ) {
        Card(
            onClick = {},
            colors = CardDefaults.colors(containerColor = KBSurfaceRaised, contentColor = KBTextHi),
            modifier = Modifier
                .padding(horizontal = 64.dp, vertical = 40.dp)
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = review.author, style = MaterialTheme.typography.headlineMedium)
                        review.authorDetails?.rating?.let { rating ->
                            Text(
                                text = "★ %.1f".format(rating),
                                color = KBAccent,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        review.createdAt?.substringBefore("T")?.takeIf { it.isNotBlank() }?.let { createdAt ->
                            Text(
                                text = createdAt,
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    KBCard(onClick = onDismiss) {
                        Text("CLOSE", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                }

                // Scrollable body -- fills remaining space so long reviews are
                // never clipped; DPAD up/down scrolls when the panel has focus.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .focusRequester(scrollFocusRequester)
                        .focusable()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = review.content,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecCard(rec: TmdbRecommendationItem, isWatched: Boolean, onClick: () -> Unit) {
    PosterCard(
        posterUrl = remember(rec.posterPath) { rec.posterPath?.let { TmdbRepository.POSTER_BASE + it } },
        contentDescription = rec.title ?: rec.name ?: "",
        isWatched = isWatched,
        onClick = onClick,
        modifier = Modifier.width(124.dp).height(180.dp).padding(end = 12.dp)
    )
}

private fun formatEpisodeAirDate(raw: String): String {
    return runCatching {
        java.time.LocalDate.parse(raw).format(
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
        )
    }.getOrDefault(raw)
}

private fun formatUsd(amount: Long): String = NumberFormat.getCurrencyInstance(Locale.US).format(amount)
