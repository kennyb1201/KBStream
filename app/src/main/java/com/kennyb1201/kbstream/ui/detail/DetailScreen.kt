package com.kennyb1201.kbstream.ui.detail

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
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
    val episodesRowState = rememberLazyListState()

    LaunchedEffect(type, id) {
        selectedSeason = null
        viewModel.load(type, id)
    }

    val meta by viewModel.meta.collectAsState()
    val tmdbDetail by viewModel.tmdbDetail.collectAsState()
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

    LaunchedEffect(type, id, initialTarget?.season, initialTarget?.episode, seasons) {
        if (type == "series") {
            val desiredSeason = initialTarget?.season?.takeIf { it in seasons }
            if (desiredSeason != null && selectedSeason != desiredSeason) {
                selectedSeason = desiredSeason
            }
        }
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

    LaunchedEffect(type, effectiveSeason, episodesLoading, targetEpisodeIndex) {
        if (type == "series" && effectiveSeason != null && !episodesLoading && targetEpisodeIndex >= 0) {
            episodesRowState.scrollToItem(targetEpisodeIndex)
        }
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
                val targetSeason = effectiveSeason ?: selectedSeason ?: seasons.firstOrNull() ?: 1
                val targetEpisode = resolvedTargetEpisode?.episodeNumber
                    ?: if (hasSeriesResume && targetSeason == resumeSeason) resumeEpisode!! else 1
                val targetStreamId = resolvedTargetEpisode?.streamId
                    ?: (if (hasSeriesResume && targetSeason == resumeSeason) resumeStreamId else null)
                    ?: "$id:$targetSeason:$targetEpisode"

                val playLabelPrefix = if (hasSeriesResume && targetSeason == resumeSeason) "RESUME" else "PLAY"
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

            CompositionLocalProvider(LocalBringIntoViewSpec provides LocalTvBringIntoViewSpec) {
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
                            Text(
                                displayName,
                                style = MaterialTheme.typography.headlineLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
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
                                        KBCard(
                                            onClick = { onNavigateTag(genre.id, genre.name, false, type) },
                                            modifier = Modifier.padding(end = 8.dp)
                                        ) {
                                            Text(
                                                genre.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
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
                                        modifier = Modifier.padding(bottom = 16.dp).focusGroup().focusRestorer()
                                    ) {
                                        items(keywords, key = { it.id }) { kw ->
                                            KBCard(
                                                onClick = { onNavigateTag(kw.id, kw.name, true, type) },
                                                modifier = Modifier.padding(end = 8.dp)
                                            ) {
                                                Text(
                                                    kw.name,
                                                    color = KBTextLo,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                )
                                            }
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
                                        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 10.dp)
                                    )
                                }

                                item(key = "seasonrow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
                                        modifier = Modifier.padding(bottom = 14.dp).focusGroup().focusRestorer()
                                    ) {
                                        items(seasons, key = { it }) { season ->
                                            val selected = season == effectiveSeason
                                            KBCard(
                                                onClick = { selectedSeason = season },
                                                modifier = Modifier.padding(end = 10.dp)
                                            ) {
                                                Text(
                                                    if (season == 0) "SPECIALS" else "SEASON $season",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = if (selected) KBAccent else KBTextHi,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                when {
                                    episodesLoading -> {
                                        item(key = "episodesloading") {
                                            Text(
                                                "Loading episodes...",
                                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                                            )
                                        }
                                    }
                                    episodeError != null -> {
                                        item(key = "episodeserror") {
                                            Text(
                                                "Couldn't load episodes: $episodeError",
                                                color = KBTextLo,
                                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                                            )
                                        }
                                    }
                                    episodes.isEmpty() -> {
                                        item(key = "episodesempty") {
                                            Text(
                                                "No episodes found for this season.",
                                                color = KBTextLo,
                                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                                            )
                                        }
                                    }
                                    else -> {
                                        item(key = "episodesrow") {
                                            LazyRow(
                                                state = episodesRowState,
                                                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                                modifier = Modifier.padding(bottom = 20.dp).focusGroup().focusRestorer()
                                            ) {
                                                items(items = episodes, key = { it.streamId }) { ep ->
                                                    val episodeKey = remember(id, effectiveSeason, ep.episodeNumber) {
                                                        effectiveSeason?.let { season -> "$id:$season:${ep.episodeNumber}" }
                                                    }
                                                    val isEpisodeWatched = remember(ep.episodeNumber, watchedEpisodesForSeason, episodeKey, watchedEpisodeKeys) {
                                                        ep.episodeNumber in watchedEpisodesForSeason ||
                                                            (episodeKey != null && episodeKey in watchedEpisodeKeys)
                                                    }
                                                    val focusRequester = remember { FocusRequester() }
                                                    val shouldFocusThisCard = when {
                                                        resolvedTargetEpisode?.streamId != null -> ep.streamId == resolvedTargetEpisode.streamId
                                                        targetEpisodeNumber != null -> ep.episodeNumber == targetEpisodeNumber
                                                        else -> false
                                                    }

                                                    LaunchedEffect(shouldFocusThisCard, episodesLoading, effectiveSeason, episodes.size) {
                                                        if (shouldFocusThisCard && !episodesLoading && episodes.isNotEmpty()) {
                                                            focusRequester.requestFocus()
                                                        }
                                                    }

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
                                                        modifier = Modifier.focusRequester(focusRequester)
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
                                        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "peoplerow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier.padding(bottom = 12.dp).focusGroup().focusRestorer()
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
                            if (companies.isNotEmpty() || networks.isNotEmpty()) {
                                item(key = "studioheader") {
                                    Text(
                                        if (type == "series") "NETWORKS" else "PRODUCTION",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "studiorow") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier.padding(bottom = 12.dp).focusGroup().focusRestorer()
                                    ) {
                                        items(networks, key = { it.id }) { n ->
                                            StudioCard(name = n.name, logoPath = n.logoPath, onClick = { onNavigateStudio(n.id, n.name, true) })
                                        }
                                        items(companies, key = { it.id }) { c ->
                                            StudioCard(name = c.name, logoPath = c.logoPath, onClick = { onNavigateStudio(c.id, c.name, false) })
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
                                        modifier = Modifier.padding(bottom = 20.dp).focusGroup().focusRestorer()
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
                                        modifier = Modifier.padding(bottom = 32.dp).focusGroup().focusRestorer()
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
                                        modifier = Modifier.padding(bottom = 32.dp).focusGroup().focusRestorer()
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
private fun CastCard(member: TmdbCastMember, onClick: () -> Unit) {
    KBCard(onClick = onClick, modifier = Modifier.width(120.dp).padding(end = 12.dp)) {
        Column(modifier = Modifier.width(120.dp)) {
            AsyncImage(
                model = remember(member.profilePath) { member.profilePath?.let { TmdbRepository.PROFILE_BASE + it } },
                contentDescription = member.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(130.dp)
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                member.character?.let {
                    Text(it, color = KBTextLo, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun PeopleSeparatorCard() {
    Box(modifier = Modifier.width(32.dp).height(130.dp).padding(end = 12.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.width(8.dp).height(8.dp).background(KBTextLo.copy(alpha = 0.7f), CircleShape))
    }
}

@Composable
private fun StudioCard(
    name: String,
    logoPath: String?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = Color(0xFF2A2D33),
            contentColor = Color.White
        ),
        modifier = Modifier
            .width(220.dp)
            .height(110.dp)
            .padding(end = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF343943),
                            Color(0xFF23272E)
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (logoPath != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.96f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = remember(logoPath) { TmdbRepository.PROFILE_BASE + logoPath },
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    )
                }
            } else {
                Text(
                    text = name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
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
    KBCard(
        onClick = onClick,
        modifier = modifier
            .width(260.dp)
            .padding(end = 12.dp)
    ) {
        Column(modifier = Modifier.width(260.dp)) {
            PosterCard(
                posterUrl = ep.thumbnail,
                contentDescription = ep.name ?: "",
                isWatched = isWatched,
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(146.dp)
            )

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                val runtimeText = remember(ep.runtimeMinutes) {
                    ep.runtimeMinutes?.let { " • ${it}m" } ?: ""
                }

                Text(
                    text = "E${ep.episodeNumber}$runtimeText",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                ep.airDate?.takeIf { it.isNotBlank() }?.let { airDate ->
                    Text(
                        text = formatEpisodeAirDate(airDate),
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                ep.name?.let {
                    Text(
                        text = it,
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                ep.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
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
    Card(
        onClick = { onClick(review) },
        colors = CardDefaults.colors(containerColor = KBSurfaceRaised, contentColor = KBTextHi),
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
    Box(modifier = Modifier.fillMaxSize().background(KBVoid.copy(alpha = 0.82f))) {
        Card(
            onClick = {},
            colors = CardDefaults.colors(containerColor = KBSurfaceRaised, contentColor = KBTextHi),
            modifier = Modifier.padding(48.dp).fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = review.author, style = MaterialTheme.typography.headlineMedium)
                LazyColumn(modifier = Modifier.padding(top = 16.dp).height(420.dp)) {
                    item { Text(text = review.content, color = KBTextLo) }
                }
                KBCard(onClick = onDismiss, modifier = Modifier.padding(top = 20.dp)) {
                    Text("CLOSE", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
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
