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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.VideoEntry
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbNetwork
import com.kennyb1201.kbstream.data.tmdb.TmdbProductionCompany
import com.kennyb1201.kbstream.data.tmdb.TmdbRecommendationItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.player.PlayerActivity
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    type: String,
    id: String,
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    onNavigateActor: (Int) -> Unit = {},
    onNavigateStudio: (Int, String, Boolean) -> Unit = { _, _, _ -> },
    viewModel: DetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedVideoId by remember { mutableStateOf<String?>(null) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(id) {
        viewModel.load(type, id)
    }

    val meta by viewModel.meta.collectAsState()
    val tmdbDetail by viewModel.tmdbDetail.collectAsState()
    val streams by viewModel.streams.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val streamsLoading by viewModel.streamsLoading.collectAsState()
    val streamsRequested by viewModel.streamsRequested.collectAsState()
    val error by viewModel.error.collectAsState()

    fun playStream(stream: Stream) {
        val url = stream.url ?: return
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("stream_url", url)
            putExtra("item_id", selectedVideoId ?: id)
            putExtra("item_type", type)
            putExtra("item_name", meta?.name ?: "")
            putExtra("item_poster", meta?.poster)
        }
        context.startActivity(intent)
    }

    fun streamLabel(stream: Stream): String =
        stream.title?.takeIf { it.isNotBlank() }
            ?: stream.description?.takeIf { it.isNotBlank() }
            ?: stream.name?.takeIf { it.isNotBlank() }
            ?: "Unnamed stream"

    fun playTrailer() {
        val trailer = tmdbDetail?.videos?.results?.firstOrNull {
            it.site == "YouTube" && it.type == "Trailer"
        } ?: return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${trailer.key}")))
    }

    when {
        isLoading -> Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Loading...") }
        error != null -> Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Error: $error") }
        meta != null -> {
            val m = meta!!
            val backdropUrl = tmdbDetail?.backdropPath?.let { "${TmdbRepository.BACKDROP_BASE}$it" } ?: m.background ?: m.poster

            val seasons = m.videos.orEmpty()
                .mapNotNull { it.season }
                .distinct()
                .sortedWith(compareBy({ it == 0 }, { it }))
            if (selectedSeason == null && seasons.isNotEmpty()) {
                selectedSeason = seasons.first()
            }
            val episodesForSeason = m.videos.orEmpty()
                .filter { it.season == selectedSeason }
                .sortedBy { it.episode ?: 0 }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                        AsyncImage(
                            model = backdropUrl,
                            contentDescription = m.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent, KBVoid))
                            )
                        )
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(m.name, style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(top = 200.dp))
                        }
                    }
                    Column(modifier = Modifier.padding(24.dp)) {
                        val metaLine = listOfNotNull(
                            m.releaseInfo,
                            m.runtime,
                            m.imdbRating?.let { "IMDb $it" }
                        ).joinToString("  •  ")
                        if (metaLine.isNotBlank()) {
                            Text(metaLine, color = KBTextLo, modifier = Modifier.padding(top = 4.dp))
                        }
                        m.genres?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · "), color = KBTextLo, modifier = Modifier.padding(top = 4.dp))
                        }
                        m.description?.let {
                            Text(it, modifier = Modifier.padding(top = 12.dp))
                        }
                        m.country?.let { Text("Country: $it", color = KBTextLo, modifier = Modifier.padding(top = 4.dp)) }
                        m.language?.let { Text("Language: $it", color = KBTextLo, modifier = Modifier.padding(top = 4.dp)) }
                        m.awards?.let { Text("Awards: $it", color = KBTextLo, modifier = Modifier.padding(top = 4.dp)) }

                        Row(modifier = Modifier.padding(top = 12.dp)) {
                            if (type == "movie") {
                                KBCard(
                                    onClick = {
                                        selectedVideoId = id
                                        viewModel.loadStreamsFor(id)
                                    },
                                    modifier = Modifier.padding(end = 10.dp)
                                ) {
                                    Text("▶ PLAY", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(10.dp))
                                }
                            }
                            if (tmdbDetail?.videos?.results?.any { it.site == "YouTube" && it.type == "Trailer" } == true) {
                                KBCard(onClick = { playTrailer() }) {
                                    Text("TRAILER", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(10.dp))
                                }
                            }
                        }
                    }
                }

                val tmdbCast = tmdbDetail?.credits?.cast
                if (!tmdbCast.isNullOrEmpty()) {
                    item { Text("CAST", style = MaterialTheme.typography.titleMedium, color = KBTextLo, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp)) }
                    item {
                        LazyRow(modifier = Modifier.padding(start = 24.dp)) {
                            items(tmdbCast.take(15)) { member: TmdbCastMember ->
                                KBCard(onClick = { onNavigateActor(member.id) }, modifier = Modifier.width(90.dp).padding(end = 10.dp)) {
                                    Column {
                                        AsyncImage(
                                            model = member.profilePath?.let { "${TmdbRepository.PROFILE_BASE}$it" },
                                            contentDescription = member.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth().height(120.dp)
                                        )
                                        Text(member.name, modifier = Modifier.padding(4.dp))
                                        member.character?.let { Text(it, color = KBTextLo, modifier = Modifier.padding(horizontal = 4.dp)) }
                                    }
                                }
                            }
                        }
                    }
                } else if (!m.cast.isNullOrEmpty()) {
                    item { Text("Cast: ${m.cast!!.joinToString(", ")}", modifier = Modifier.padding(start = 24.dp, top = 12.dp)) }
                }

                m.director?.takeIf { it.isNotEmpty() }?.let {
                    item { Text("Director: ${it.joinToString(", ")}", color = KBTextLo, modifier = Modifier.padding(start = 24.dp, top = 8.dp)) }
                }

                val companies = tmdbDetail?.productionCompanies.orEmpty()
                val networks = tmdbDetail?.networks.orEmpty()
                if (companies.isNotEmpty() || networks.isNotEmpty()) {
                    item { Text(if (type == "series") "NETWORKS" else "PRODUCTION", style = MaterialTheme.typography.titleMedium, color = KBTextLo, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)) }
                    item {
                        LazyRow(modifier = Modifier.padding(start = 24.dp)) {
                            items(networks) { n: TmdbNetwork ->
                                Card(
                                    onClick = { onNavigateStudio(n.id, n.name, true) },
                                    colors = CardDefaults.colors(containerColor = KBSurfaceRaised, contentColor = KBTextHi),
                                    modifier = Modifier.width(130.dp).height(64.dp).padding(end = 10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        n.logoPath?.let {
                                            AsyncImage(model = "${TmdbRepository.PROFILE_BASE}$it", contentDescription = n.name, contentScale = ContentScale.Fit, modifier = Modifier.height(24.dp))
                                        }
                                        Text(n.name)
                                    }
                                }
                            }
                            items(companies) { c: TmdbProductionCompany ->
                                Card(
                                    onClick = { onNavigateStudio(c.id, c.name, false) },
                                    colors = CardDefaults.colors(containerColor = KBSurfaceRaised, contentColor = KBTextHi),
                                    modifier = Modifier.width(130.dp).height(64.dp).padding(end = 10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        c.logoPath?.let {
                                            AsyncImage(model = "${TmdbRepository.PROFILE_BASE}$it", contentDescription = c.name, contentScale = ContentScale.Fit, modifier = Modifier.height(24.dp))
                                        }
                                        Text(c.name)
                                    }
                                }
                            }
                        }
                    }
                }

                if (type == "series" && seasons.isNotEmpty()) {
                    item { Text("EPISODES", style = MaterialTheme.typography.titleMedium, color = KBTextLo, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp)) }
                    item {
                        LazyRow(modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)) {
                            items(seasons) { season ->
                                val selected = season == selectedSeason
                                KBCard(
                                    onClick = { selectedSeason = season },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text(
                                        if (season == 0) "SPECIALS" else "SEASON $season",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (selected) KBAccent else KBTextHi,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                    items(episodesForSeason) { video: VideoEntry ->
                        KBCard(
                            onClick = {
                                selectedVideoId = video.id
                                viewModel.loadStreamsFor(video.id)
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                        ) {
                            Row(modifier = Modifier.padding(10.dp)) {
                                AsyncImage(
                                    model = video.thumbnail,
                                    contentDescription = video.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(160.dp).height(90.dp)
                                )
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text("E${video.episode ?: 0}  ${video.title ?: ""}", style = MaterialTheme.typography.titleMedium)
                                    video.overview?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, color = KBTextLo, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                if (streamsRequested) {
                    item {
                        Text(
                            when {
                                streamsLoading -> "LOADING STREAMS..."
                                streams.isEmpty() -> "NO STREAMS FOUND"
                                else -> "STREAMS"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = KBTextLo,
                            modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp)
                        )
                    }
                    items(streams) { stream: Stream ->
                        val playable = stream.url != null
                        KBCard(
                            onClick = { if (playable) playStream(stream) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(streamLabel(stream), fontFamily = FontFamily.Monospace, color = if (playable) KBTextHi else KBTextLo)
                                if (!playable) {
                                    Text("Torrent link — direct playback not supported yet", color = KBTextLo)
                                }
                            }
                        }
                    }
                }

                val recs = tmdbDetail?.recommendations?.results.orEmpty()
                if (recs.isNotEmpty()) {
                    item { Text("MORE LIKE THIS", style = MaterialTheme.typography.titleMedium, color = KBTextLo, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp)) }
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
                                    modifier = Modifier.width(120.dp).height(170.dp).padding(end = 10.dp)
                                ) {
                                    AsyncImage(
                                        model = rec.posterPath?.let { "${TmdbRepository.PROFILE_BASE}$it" },
                                        contentDescription = rec.title ?: rec.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
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
