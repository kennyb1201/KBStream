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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.data.tmdb.TmdbKeyword
import com.kennyb1201.kbstream.data.tmdb.TmdbNetwork
import com.kennyb1201.kbstream.data.tmdb.TmdbProductionCompany
import com.kennyb1201.kbstream.data.tmdb.TmdbRecommendationItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.director
import com.kennyb1201.kbstream.data.tmdb.list
import com.kennyb1201.kbstream.data.tmdb.writers
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import androidx.compose.ui.Alignment
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

private sealed interface PeopleRowItem {
        data class Person(val member: TmdbCastMember) : PeopleRowItem
            data object Separator : PeopleRowItem
            }


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
    onNavigateTag: (Int, String, Boolean, String) -> Unit = { _, _, _, _ -> },
    onNavigateStreams: (StreamsTarget, String, String, String?) -> Unit = { _, _, _, _ -> },
    viewModel: DetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val scope = rememberCoroutineScope()
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var selectedReview by remember { mutableStateOf<TmdbReview?>(null) }

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
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val resolvedPosterIds by viewModel.resolvedPosterIds.collectAsState()
    val completedEpisodeIds by viewModel.completedEpisodeIds.collectAsState()
    val watchedEpisodeKeys by viewModel.watchedEpisodeKeys.collectAsState()
    val simklSeriesWatched by viewModel.simklSeriesWatched.collectAsState()
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

    LaunchedEffect(type, selectedSeason, tmdbDetail?.id) {
    val season = selectedSeason
    val tvId = tmdbDetail?.id
    if (type == "series" && season != null && tvId != null) {
        viewModel.loadEpisodesForSeason(season)
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
                playLabel = if (hasResume) "▶ RESUME" else "▶ PLAY"
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

                val playLabelPrefix = if (hasResume) "▶ RESUME" else "▶ PLAY"
                playLabel = playLabelPrefix + " S" + targetSeason + "E" + targetEpisode
                playTarget = remember(resumeInfo, id, displayName, targetSeason, targetEpisode) {
                    StreamsTarget(
                        contentType = "series",
                        streamId = targetStreamId,
                        title = displayName + " · S" + targetSeason + "E" + targetEpisode,
                        displayName = displayName,
                        season = targetSeason,
                        episode = targetEpisode,
                        resumePositionMs = if (hasResume) resumeInfo!!.positionMs else 0L
                    )
                }
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides LocalTvBringIntoViewSpec) {
                Box(modifier = Modifier.fillMaxSize()) {

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
                            val metaLine = remember(m, tmdbDetail) {
                                listOfNotNull(
                                    tmdbDetail?.certification(type == "movie"),
                                    m.releaseInfo,
                                    m.runtime,
                                    m.imdbRating?.let { "IMDb " + it }
                                ).joinToString("  •  ")
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
                                    modifier = Modifier
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(items = tmdbGenres, key = { it.id }) { genre: TmdbGenre ->
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
                                        it.joinToString(" · "),
                                        color = KBTextLo,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth()
                                .focusGroup(),
                            contentPadding = PaddingValues(top = 8.dp)
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

                            val keywords = tmdbDetail?.keywords.list()
                            if (keywords.isNotEmpty()) {
                                item(key = "keywords_row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
                                        modifier = Modifier
                                            .padding(bottom = 16.dp)
                                            .focusGroup()
                                            .focusRestorer()
                                    ) {
                                        items(items = keywords, key = { it.id }) { kw: TmdbKeyword ->
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
                                item(key = "episodes_header") {
                                    Text(
                                        "EPISODES",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 10.dp)
                                    )
                                }

                                item(key = "season_row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
                                        modifier = Modifier
                                            .padding(bottom = 14.dp)
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
                                        Text(
                                            "Loading episodes...",
                                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                                        )
                                    }
                                }

                                item(key = "episodes_row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier
                                            .padding(bottom = 20.dp)
                                            .focusGroup()
                                            .focusRestorer()
                                    ) {
                                        items(
    items = episodes,
    key = { it.streamId }
) { ep: ResolvedEpisode ->
    val episodeKey = remember(id, selectedSeason, ep.episodeNumber) {
        selectedSeason?.let { season -> "$id:$season:${ep.episodeNumber}" }
    }

    val isEpisodeWatched = remember(
    episodeKey,
    watchedEpisodeKeys
) {
    episodeKey != null && episodeKey in watchedEpisodeKeys
}

    EpisodeCard(
        ep = ep,
        isWatched = isEpisodeWatched,
        onClick = {
            val hasResumeHere = resumeInfo?.episodeStreamId == ep.streamId
            val epSuffix = ep.name?.let { " - " + it } ?: ""
            val target = StreamsTarget(
                contentType = "series",
                streamId = ep.streamId,
                title = displayName + " · E" + ep.episodeNumber + epSuffix,
                displayName = displayName,
                season = selectedSeason,
                episode = ep.episodeNumber,
                resumePositionMs = if (hasResumeHere) resumeInfo!!.positionMs else 0L
            )
            onNavigateStreams(target, id, type, m.poster)
        }
    )
}
                                    }
                                }
                            }

                                                                                                                                      val tmdbCast = tmdbDetail?.credits?.cast.orEmpty()
                            val tmdbDirector = tmdbDetail?.credits.director()
                            val tmdbWriters = tmdbDetail?.credits.writers().distinctBy { it.id }

                            val peopleItems = buildList<PeopleRowItem> {
                                val mainWriter = tmdbWriters.firstOrNull()

                                mainWriter?.let { writer ->
                                    add(
                                        PeopleRowItem.Person(
                                            TmdbCastMember(
                                                id = writer.id,
                                                name = writer.name,
                                                character = "Writer",
                                                profilePath = writer.profilePath
                                            )
                                        )
                                    )
                                }

                                tmdbDirector?.let { director ->
                                    if (director.id != mainWriter?.id) {
                                        add(
                                            PeopleRowItem.Person(
                                                TmdbCastMember(
                                                    id = director.id,
                                                    name = director.name,
                                                    character = "Director",
                                                    profilePath = director.profilePath
                                                )
                                            )
                                        )
                                    }
                                }

                                val castItems = tmdbCast
                                    .distinctBy { it.id }
                                    .take(15)
                                    .map { PeopleRowItem.Person(it) }

                                if (castItems.isNotEmpty() && isNotEmpty()) {
                                    add(PeopleRowItem.Separator)
                                }

                                addAll(castItems)
                            }

                            if (peopleItems.isNotEmpty()) {
                                item(key = "people_header") {
                                    Text(
                                        "PEOPLE",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "people_row") {
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
                                            .focusRestorer()
                                    ) {
                                        items(
                                            items = peopleItems,
                                            key = { person ->
                                                when (person) {
                                                    is PeopleRowItem.Person ->
                                                        "person_${person.member.id}_${person.member.character.orEmpty()}"
                                                    PeopleRowItem.Separator ->
                                                        "people_separator"
                                                }
                                            }
                                        ) { person ->
                                            when (person) {
                                                is PeopleRowItem.Person -> {
                                                    CastCard(
                                                        member = person.member,
                                                        onClick = { onNavigateActor(person.member.id) }
                                                    )
                                                }

                                                PeopleRowItem.Separator -> {
                                                    PeopleSeparatorCard()
                                                }
                                            }
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
                            } else {
                                m.director?.takeIf { it.isNotEmpty() }?.let {
                                    item(key = "director_fallback") {
                                        Text(
                                            "Director: " + it.joinToString(", "),
                                            color = KBTextLo,
                                            modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp)
                                        )
                                    }
                                }
                            }   

                            val reviews = tmdbDetail?.reviews?.results.orEmpty()
                            if (reviews.isNotEmpty()) {
                                item(key = "reviews_header") {
                                    Text(
                                        "REVIEWS",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "reviews_row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier
                                            .padding(bottom = 20.dp)
                                            .focusGroup()
                                            .focusRestorer()
                                    ) {
                                        items(items = reviews.take(10), key = { it.id }) { review: TmdbReview ->
                                            ReviewCard(
                                                review = review,
                                                onClick = { selectedReview = it }
                                            )
                                        }
                                    }
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
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier
                                            .padding(bottom = 12.dp)
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

                            val collectionParts = collection?.parts.orEmpty()
                                .filter { it.id != tmdbDetail?.id }
                            if (collectionParts.isNotEmpty()) {
                                item(key = "collection_header") {
                                    Text(
                                        collection?.name?.uppercase() ?: "COLLECTION",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "collection_row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier
                                            .padding(bottom = 32.dp)
                                            .focusGroup()
                                            .focusRestorer()
                                     ) {
                                        items(items = collectionParts, key = { it.id }) { part: TmdbCollectionPart ->
                                            CollectionCard(
                                                part = part,
                                                isWatched = resolvedPosterIds[viewModel.posterLookupKey(part.id, "movie")]
                                                ?.let { imdbId -> viewModel.watchedKey(imdbId, "movie") in watchedKeys }
                                                == true,
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
                                item(key = "recs_header") {
                                    Text(
                                        "MORE LIKE THIS",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)
                                    )
                                }
                                item(key = "recs_row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                                        modifier = Modifier
                                            .padding(bottom = 32.dp)
                                            .focusGroup()
                                            .focusRestorer()
                                    ) {
                                        items(items = recs.take(30), key = { it.id }) { rec: TmdbRecommendationItem ->
                                            RecCard(
                                                rec = rec,
                                                isWatched = resolvedPosterIds[viewModel.posterLookupKey(rec.id, type.lowercase())]
                                                ?.let { imdbId -> viewModel.watchedKey(imdbId, type.lowercase()) in watchedKeys }
                                                == true,
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

                            item(key = "bottom_spacer") {
                                Box(modifier = Modifier.height(48.dp))
                            }
                        }
                    }

                    selectedReview?.let { review ->
                        ReviewOverlay(
                            review = review,
                            onDismiss = { selectedReview = null }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastCard(member: TmdbCastMember, onClick: () -> Unit) {
    KBCard(
        onClick = onClick,
        modifier = Modifier.width(120.dp).padding(end = 12.dp)
    ) {
        Column(modifier = Modifier.width(120.dp)) {
            AsyncImage(
                model = remember(member.profilePath) {
                    member.profilePath?.let { TmdbRepository.PROFILE_BASE + it }
                },
                contentDescription = member.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(130.dp)
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    member.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                member.character?.let {
                    Text(
                        it,
                        color = KBTextLo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CrewCard(name: String, role: String, profilePath: String?, onClick: () -> Unit) {
    KBCard(
        onClick = onClick,
        modifier = Modifier.width(120.dp).padding(end = 12.dp)
    ) {
        Column(modifier = Modifier.width(120.dp)) {
            AsyncImage(
                model = remember(profilePath) {
                    profilePath?.let { TmdbRepository.PROFILE_BASE + it }
                },
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(130.dp)
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    role,
                    color = KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun PeopleSeparatorCard() {
    Box(
        modifier = Modifier
            .width(32.dp)
            .height(130.dp)
            .padding(end = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "/",
            color = KBTextLo,
            style = MaterialTheme.typography.titleMedium
        )
    }
}


@Composable
private fun StudioCard(name: String, logoPath: String?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(containerColor = KBSurfaceRaised, contentColor = KBTextHi),
        modifier = Modifier.width(140.dp).height(72.dp).padding(end = 12.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            logoPath?.let {
                AsyncImage(
                    model = remember(it) { TmdbRepository.PROFILE_BASE + it },
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(24.dp)
                )
            }
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    ep: ResolvedEpisode,
    isWatched: Boolean,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier.width(220.dp).padding(end = 12.dp)
    ) {
        Column(modifier = Modifier.width(220.dp)) {
            PosterCard(
                posterUrl = ep.thumbnail,
                contentDescription = ep.name ?: "",
                isWatched = isWatched,
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(124.dp)
            )

            Column(modifier = Modifier.padding(10.dp)) {
                val runtimeText = remember(ep.runtimeMinutes) {
                    ep.runtimeMinutes?.let { " • ${it}m" } ?: ""
                }

                Text(
                    text = "E" + ep.episodeNumber + runtimeText,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                ep.name?.let {
                    Text(
                        text = it,
                        color = KBTextHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                ep.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = KBTextLo,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(
    part: TmdbCollectionPart,
    isWatched: Boolean,
    onClick: () -> Unit
) {
    PosterCard(
        posterUrl = remember(part.posterPath) {
            part.posterPath?.let { TmdbRepository.POSTER_BASE + it }
        },
        contentDescription = part.title ?: "",
        isWatched = isWatched,
        onClick = onClick,
        modifier = Modifier
            .width(124.dp)
            .height(180.dp)
            .padding(end = 12.dp)
    )
}

@Composable
private fun ReviewCard(
    review: TmdbReview,
    onClick: (TmdbReview) -> Unit
) {
    Card(
        onClick = { onClick(review) },
        colors = CardDefaults.colors(containerColor = KBSurfaceRaised, contentColor = KBTextHi),
        modifier = Modifier.width(280.dp).padding(end = 12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = review.author ?: "Anonymous",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = review.content ?: "",
                color = KBTextLo,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ReviewOverlay(
    review: TmdbReview,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid.copy(alpha = 0.82f))
    ) {
        Card(
            onClick = {},
            colors = CardDefaults.colors(
                containerColor = KBSurfaceRaised,
                contentColor = KBTextHi
            ),
            modifier = Modifier
                .padding(48.dp)
                .fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = review.author ?: "Anonymous",
                    style = MaterialTheme.typography.headlineMedium
                )

                LazyColumn(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .height(420.dp)
                ) {
                    item {
                        Text(
                            text = review.content ?: "",
                            color = KBTextLo
                        )
                    }
                }

                KBCard(
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 20.dp)
                ) {
                    Text(
                        "CLOSE",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecCard(
    rec: TmdbRecommendationItem,
    isWatched: Boolean,
    onClick: () -> Unit
) {
    PosterCard(
        posterUrl = remember(rec.posterPath) {
            rec.posterPath?.let { TmdbRepository.POSTER_BASE + it }
        },
        contentDescription = rec.title ?: rec.name ?: "",
        isWatched = isWatched,
        onClick = onClick,
        modifier = Modifier
            .width(124.dp)
            .height(180.dp)
            .padding(end = 12.dp)
    )
}
