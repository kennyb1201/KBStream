package com.kennyb1201.kbstream.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
import com.kennyb1201.kbstream.data.tmdb.TmdbNetwork
import com.kennyb1201.kbstream.data.tmdb.TmdbProductionCompany
import com.kennyb1201.kbstream.data.tmdb.TmdbRecommendationItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.launch

data class StreamsTarget(
    val contentType: String,
    val streamId: String,
    val title: String,
    val displayName: String,
    val season: Int?,
    val episode: Int?,
    val resumePositionMs: Long
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
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    onNavigateActor: (Int) -> Unit = {},
    onNavigateStudio: (Int, String, Boolean) -> Unit = { _, _, _ -> },
    onNavigateStreams: (StreamsTarget, String, String, String?) -> Unit = { _, _, _, _ -> },
    viewModel: DetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val scope = rememberCoroutineScope()
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(id) {
        viewModel.load(type, id)
    }

    val meta by viewModel.meta.collectAsState()
    val tmdbDetail by viewModel.tmdbDetail.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val episodesLoading by viewModel.episodesLoading.collectAsState()
    val resumeInfo by viewModel.resumeInfo.collectAsState()
    val collection by viewModel.collection.collectAsState()
    val error by viewModel.error.collectAsState()

    val seasons = remember(tmdbDetail) {
        tmdbDetail?.seasons.orEmpty()
            .map { it.seasonNumber }
            .distinct()
            .sortedWith(compareBy({ it == 0 }, { it }))
    }

    LaunchedEffect(tmdbDetail) {
        if (selectedSeason == null && seasons.isNotEmpty()) {
            selectedSeason = seasons.first()
        }
    }

    LaunchedEffect(selectedSeason) {
        selectedSeason?.let {
            if (type == "series") viewModel.loadEpisodesForSeason(it)
        }
    }

    fun playTrailer(context: android.content.Context) {
        val trailer = tmdbDetail?.videos?.results?.firstOrNull {
            it.site == "YouTube" && it.type == "Trailer"
        } ?: return
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + trailer.key))
        )
    }

    when {
        isLoading -> Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Loading...")
        }

        error != null -> Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Error: " + error)
        }

        meta != null -> {
            val m = meta!!
            val displayName = remember(m, tmdbDetail) {
                m.name.ifBlank { tmdbDetail?.name ?: tmdbDetail?.title ?: m.name }
            }
            val backdropUrl = remember(tmdbDetail, m) {
                tmdbDetail?.backdropPath?.let { TmdbRepository.BACKDROP_BASE + it }
                    ?: m.background
                    ?: m.poster
            }
            val context = androidx.compose.ui.platform.LocalContext.current

            val playLabel: String
            val playTarget: StreamsTarget

            if (type == "movie") {
                val hasResume = resumeInfo != null && resumeInfo!!.positionMs > 0
                playLabel = if (hasResume) "\u25B6 RESUME" else "\u25B6 PLAY"
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
                val resumeSeason = resumeInfo?.season
                val resumeEpisode = resumeInfo?.episode
                val resumeStreamId = resumeInfo?.episodeStreamId
                val hasResume = resumeStreamId != null && resumeSeason != null && resumeEpisode != null
                val targetSeason = resumeSeason ?: 1
                val targetEpisode = resumeEpisode ?: 1
                val targetStreamId = resumeStreamId ?: (id + ":" + targetSeason + ":" + targetEpisode)

                val playLabelPrefix = if (hasResume) "\u25B6 RESUME" else "\u25B6 PLAY"
                playLabel = playLabelPrefix + " S" + targetSeason + "E" + targetEpisode
                playTarget = remember(resumeInfo, id, displayName, targetSeason, targetEpisode) {
                    StreamsTarget(
                        contentType = "series",
                        streamId = targetStreamId,
                        title = displayName + " \u00B7 S" + targetSeason + "E" + targetEpisode,
                        displayName = displayName,
                        season = targetSeason,
                        episode = targetEpisode,
                        resumePositionMs = if (hasResume) resumeInfo!!.positionMs else 0L
                    )
                }
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides LocalTvBringIntoViewSpec) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
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
                                    Brush.verticalGradient(listOf(Color.Transparent, KBVoid))
                                )
                        )
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                displayName,
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.padding(top = 80.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .padding(start = 24.dp, top = 8.dp, bottom = 8.dp)
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

                        if (tmdbDetail?.videos?.results?.any {
                                it.site == "YouTube" && it.type == "Trailer"
                            } == true
                        ) {
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
                        val metaLine = remember(m) {
                            listOfNotNull(
                                m.releaseInfo,
                                m.runtime,
                                m.imdbRating?.let { "IMDb " + it }
                            ).joinToString("  \u2022  ")
                        }

                        if (metaLine.isNotBlank()) {
                            Text(metaLine, color = KBTextLo, modifier = Modifier.padding(top = 2.dp))
                        }

                        m.genres?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" \u00B7 "), color = KBTextLo, modifier = Modifier.padding(top = 2.dp))
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                            .focusGroup()
                    ) {
                        item(key = "info_block") {
                            Column(
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                            ) {
                                m.description?.let {
                                    Text(it, modifier = Modifier.padding(top = 4.dp))
                                }
                                m.country?.let {
                                    Text("Country: " + it, color = KBTextLo, modifier = Modifier.padding(top = 4.dp))
                                }
                                m.language?.let {
                                    Text("Language: " + it, color = KBTextLo, modifier = Modifier.padding(top = 4.dp))
                                }
                                m.awards?.let {
                                    Text("Awards: " + it, color = KBTextLo, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }

                        val tmdbCast = tmdbDetail?.credits?.cast
                        if (!tmdbCast.isNullOrEmpty()) {
                            item(key = "cast_header") {
                                Text(
                                    "CAST",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 10.dp)
                                )
                            }
                            item(key = "cast_row") {
                                LazyRow(
                                    modifier = Modifier
                                        .padding(start = 24.dp, bottom = 12.dp)
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        items = tmdbCast.take(15),
                                        key = { it.id }
                                    ) { member: TmdbCastMember ->
                                        CastCard(member = member, onClick = { onNavigateActor(member.id) })
                                    }
                                }
                            }
                        } else if (!m.cast.isNullOrEmpty()) {
                            item(key = "cast_fallback") {
                                Text(
                                    "Cast: " + m.cast!!.joinToString(", "),
                                    modifier = Modifier.padding(start = 24.dp, top = 12.dp, end = 24.dp)
                                )
                            }
                        }

                        m.director?.takeIf { it.isNotEmpty() }?.let {
                            item(key = "director") {
                                Text(
                                    "Director: " + it.joinToString(", "),
                                    color = KBTextLo,
                                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp)
                                )
                            }
                        }

                        val companies = tmdbDetail?.productionCompanies.orEmpty()
                        val networks = tmdbDetail?.networks.orEmpty()
                        if (companies.isNotEmpty() || networks.isNotEmpty()) {
                            item(key = "studio_header") {
                                Text(
                                    if (type == "series") "NETWORKS" else "PRODUCTION",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                )
                            }
                            item(key = "studio_row") {
                                LazyRow(
                                    modifier = Modifier
                                        .padding(start = 24.dp, bottom = 12.dp)
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(items = networks, key = { "n_" + it.id }) { n: TmdbNetwork ->
                                        StudioCard(
                                            name = n.name,
                                            logoPath = n.logoPath,
                                            onClick = { onNavigateStudio(n.id, n.name, true) }
                                        )
                                    }
                                    items(items = companies, key = { "c_" + it.id }) { c: TmdbProductionCompany ->
                                        StudioCard(
                                            name = c.name,
                                            logoPath = c.logoPath,
                                            onClick = { onNavigateStudio(c.id, c.name, false) }
                                        )
                                    }
                                }
                            }
                        }

                        if (type == "series" && seasons.isNotEmpty()) {
                            item(key = "episodes_header") {
                                Text(
                                    "EPISODES",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                )
                            }

                            item(key = "season_row") {
                                LazyRow(
                                    modifier = Modifier
                                        .padding(start = 24.dp, bottom = 14.dp)
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(items = seasons, key = { it }) { season ->
                                        val selected = season == selectedSeason
                                        KBCard(
                                            onClick = { selectedSeason = season },
                                            modifier = Modifier.padding(end = 10.dp)
                                        ) {
                                            Text(
                                                if (season == 0) "SPECIALS" else "SEASON " + season,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = if (selected) KBAccent else KBTextHi,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            if (episodesLoading) {
                                item(key = "episodes_loading") {
                    
