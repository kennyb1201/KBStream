package com.kennyb1201.kbstream.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
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
    val error by viewModel.error.collectAsState()

    val seasons = tmdbDetail?.seasons.orEmpty()
        .map { it.seasonNumber }
        .distinct()
        .sortedWith(compareBy({ it == 0 }, { it }))

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
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${trailer.key}"))
        )
    }

    when {
        isLoading -> Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Loading...")
        }

        error != null -> Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Error: $error")
        }

        meta != null -> {
            val m = meta!!
            val displayName = m.name.ifBlank { tmdbDetail?.name ?: tmdbDetail?.title ?: m.name }
            val backdropUrl = tmdbDetail?.backdropPath?.let { "${TmdbRepository.BACKDROP_BASE}$it" }
                ?: m.background
                ?: m.poster
            val context = androidx.compose.ui.platform.LocalContext.current

            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    AsyncImage(
                        model = backdropUrl,
                        contentDescription = displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, KBVoid)
                                )
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
                    modifier = Modifier.padding(
                        start = 24.dp,
                        top = 8.dp,
                        bottom = 8.dp
                    )
                ) {
                    val playLabel: String
                    val playTarget: StreamsTarget

                    if (type == "movie") {
                        val hasResume = resumeInfo != null && resumeInfo!!.positionMs > 0
                        playLabel = if (hasResume) "▶ RESUME" else "▶ PLAY"
                        playTarget = StreamsTarget(
                            contentType = "movie",
                            streamId = id,
                            title = displayName,
                            displayName = displayName,
                            season = null,
                            episode = null,
                            resumePositionMs = resumeInfo?.positionMs ?: 0L
                        )
                    } else {
                        val resumeSeason = resumeInfo?.season
                        val resumeEpisode = resumeInfo?.episode
                        val resumeStreamId = resumeInfo?.episodeStreamId
                        val hasResume =
                            resumeStreamId != null && resumeSeason != null && resumeEpisode != null
                        val targetSeason = resumeSeason ?: 1
                        val targetEpisode = resumeEpisode ?: 1
                        val targetStreamId = resumeStreamId ?: "$id:$targetSeason:$targetEpisode"

                        playLabel = "${if (hasResume) "▶ RESUME" else "▶ PLAY"} S${targetSeason}E$targetEpisode"
                        playTarget = StreamsTarget(
                            contentType = "series",
                            streamId = targetStreamId,
                            title = "$displayName · S${targetSeason}E$targetEpisode",
                            displayName = displayName,
                            season = targetSeason,
                            episode = targetEpisode,
                            resumePositionMs = if (hasResume) resumeInfo!!.positionMs else 0L
                        )
                    }

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

                Column(
                    modifier = Modifier.padding(
                        start = 24.dp,
                        end = 24.dp,
                        bottom = 8.dp
                    )
                ) {
                    val metaLine = listOfNotNull(
                        m.releaseInfo,
                        m.runtime,
                        m.imdbRating?.let { "IMDb $it" }
                    ).joinToString("  •  ")

                    if (metaLine.isNotBlank()) {
                        Text(metaLine, color = KBTextLo, modifier = Modifier.padding(top = 2.dp))
                    }

                    m.genres?.takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), color = KBTextLo, modifier = Modifier.padding(top = 2.dp))
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .fillMaxWidth()
                ) {
                    item {
                        Column(
                            modifier = Modifier.padding(
                                start = 24.dp,
                                end = 24.dp,
                                bottom = 16.dp
                            )
                        ) {
                            m.description?.let {
                                Text(it, modifier = Modifier.padding(top = 4.dp))
                            }
                            m.country?.let {
                                Text(
                                    "Country: $it",
                                    color = KBTextLo,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            m.language?.let {
                                Text(
                                    "Language: $it",
                                    color = KBTextLo,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            m.awards?.let {
                                Text(
                                    "Awards: $it",
                                    color = KBTextLo,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    val tmdbCast = tmdbDetail?.credits?.cast
                    if (!tmdbCast.isNullOrEmpty()) {
                        item {
                            Text(
                                "CAST",
                                style = MaterialTheme.typography.titleMedium,
                                color = KBTextLo,
                                modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 10.dp)
                            )
                        }
                        item {
                            LazyRow(modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)) {
                                items(tmdbCast.take(15)) { member: TmdbCastMember ->
                                    KBCard(
                                        onClick = { onNavigateActor(member.id) },
                                        modifier = Modifier
                                            .width(100.dp)
                                            .padding(end = 12.dp)
                                    ) {
                                        Column {
                                            AsyncImage(
                                                model = member.profilePath?.let {
                                                    "${TmdbRepository.PROFILE_BASE}$it"
                                                },
                                                contentDescription = member.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(130.dp)
                                            )
                                            Text(member.name, modifier = Modifier.padding(6.dp))
                                            member.character?.let {
                                                Text(
                                                    it,
                                                    color = KBTextLo,
                                                    modifier = Modifier.padding(horizontal = 6.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (!m.cast.isNullOrEmpty()) {
                        item {
                            Text(
                                "Cast: ${m.cast!!.joinToString(", ")}",
                                modifier = Modifier.padding(start = 24.dp, top = 12.dp, end = 24.dp)
                            )
                        }
                    }

                    m.director?.takeIf { it.isNotEmpty() }?.let {
                        item {
                            Text(
                                "Director: ${it.joinToString(", ")}",
                                color = KBTextLo,
                                modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp)
                            )
                        }
                    }

                    val companies = tmdbDetail?.productionCompanies.orEmpty()
                    val networks = tmdbDetail?.networks.orEmpty()
                    if (companies.isNotEmpty() || networks.isNotEmpty()) {
                        item {
                            Text(
                                if (type == "series") "NETWORKS" else "PRODUCTION",
                                style = MaterialTheme.typography.titleMedium,
                                color = KBTextLo,
                                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                            )
                        }
                        item {
                            LazyRow(modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)) {
                                items(networks) { n: TmdbNetwork ->
                                    Card(
                                        onClick = { onNavigateStudio(n.id, n.name, true) },
                                        colors = CardDefaults.colors(
                                            containerColor = KBSurfaceRaised,
                                            contentColor = KBTextHi
                                        ),
                                        modifier = Modifier
                                            .width(140.dp)
                                            .height(72.dp)
                                            .padding(end = 12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            n.logoPath?.let {
                                                AsyncImage(
                                                    model = "${TmdbRepository.PROFILE_BASE}$it",
                                                    contentDescription = n.name,
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier.height(24.dp)
                                                )
                                            }
                                            Text(n.name)
                                        }
                                    }
                                }

                                items(companies) { c: TmdbProductionCompany ->
                                    Card(
                                        onClick = { onNavigateStudio(c.id, c.name, false) },
                                        colors = CardDefaults.colors(
                                            containerColor = KBSurfaceRaised,
                                            contentColor = KBTextHi
                                        ),
                                        modifier = Modifier
                                            .width(140.dp)
                                            .height(72.dp)
                                            .padding(end = 12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            c.logoPath?.let {
                                                AsyncImage(
                                                    model = "${TmdbRepository.PROFILE_BASE}$it",
                                                    contentDescription = c.name,
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier.height(24.dp)
                                                )
                                            }
                                            Text(c.name)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (type == "series" && seasons.isNotEmpty()) {
                        item {
                            Text(
                                "EPISODES",
                                style = MaterialTheme.typography.titleMedium,
                                color = KBTextLo,
                                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                            )
                        }

                        item {
                            LazyRow(modifier = Modifier.padding(start = 24.dp, bottom = 14.dp)) {
                                items(seasons) { season ->
                                    val selected = season == selectedSeason
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

                        if (episodesLoading) {
                            item {
                                Text(
                                    "Loading episodes...",
                                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                                )
                            }
                        }

                        items(episodes) { ep: ResolvedEpisode ->
                            KBCard(
                                onClick = {
                                    val hasResumeHere = resumeInfo?.episodeStreamId == ep.streamId
                                    val target = StreamsTarget(
                                        contentType = "series",
                                        streamId = ep.streamId,
                                        title = "$displayName · E${ep.episodeNumber}${ep.name?.let { " - $it" } ?: ""}",
                                        displayName = displayName,
                                        season = selectedSeason,
                                        episode = ep.episodeNumber,
                                        resumePositionMs = if (hasResumeHere) resumeInfo!!.positionMs else 0L
                                    )
                                    onNavigateStreams(target, id, type, m.poster)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 6.dp)
                            ) {
                                Row(modifier = Modifier.padding(12.dp)) {
                                    AsyncImage(
                                        model = ep.thumbnail,
                                        contentDescription = ep.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .width(180.dp)
                                            .height(100.dp)
                                    )
                                    Column(modifier = Modifier.padding(start = 14.dp)) {
                                        val runtimeText = ep.runtimeMinutes?.let { " · ${it}m" } ?: ""
                                        Text(
                                            "E${ep.episodeNumber}$runtimeText  ${ep.name ?: ""}",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        ep.overview?.takeIf { it.isNotBlank() }?.let {
                                            Text(
                                                it,
                                                color = KBTextLo,
                                                modifier = Modifier.padding(top = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val recs = tmdbDetail?.recommendations?.results.orEmpty()
                    if (recs.isNotEmpty()) {
                        item {
                            Text(
                                "MORE LIKE THIS",
                                style = MaterialTheme.typography.titleMedium,
                                color = KBTextLo,
                                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                            )
                        }
                        item {
                            LazyRow(modifier = Modifier.padding(start = 24.dp, bottom = 24.dp)) {
                                items(recs.take(15)) { rec: TmdbRecommendationItem ->
                                    KBCard(
                                        onClick = {
                                            scope.launch {
                                                val imdbId = viewModel.resolveImdbId(rec.id, type)
                                                if (imdbId != null) onNavigateDetail(type, imdbId)
                                            }
                                        },
                                        modifier = Modifier
                                            .width(124.dp)
                                            .height(180.dp)
                                            .padding(end = 12.dp)
                                    ) {
                                        AsyncImage(
                                            model = rec.posterPath?.let {
                                                "${TmdbRepository.PROFILE_BASE}$it"
                                            },
                                            contentDescription = rec.title ?: rec.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Box(modifier = Modifier.height(96.dp))
                    }
                }
            }
        }
    }
}
