package com.kennyb1201.kbstream.ui.actor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay

private sealed interface ActorRailItem {
    data class Credit(
        val credit: TmdbPersonCredit,
        val mediaType: String,
        val subtitle: String?
    ) : ActorRailItem

    data object Separator : ActorRailItem
}

@Composable
fun ActorScreen(
    personId: Int,
    onBack: () -> Unit,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: ActorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val person by viewModel.person.collectAsState()
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val resolvedCreditIds by viewModel.resolvedCreditIds.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val firstCreditFocusRequester = remember { FocusRequester() }

    LaunchedEffect(personId) {
        viewModel.load(personId)
    }

    val railItems = remember(person) {
        val featuredCrew = person?.combinedCredits?.crew
            .orEmpty()
            .filter { credit ->
                credit.job?.trim()?.lowercase() in setOf(
                    "director",
                    "writer",
                    "screenplay",
                    "story",
                    "teleplay",
                    "author",
                    "novel",
                    "characters"
                )
            }
            .mapNotNull { credit ->
                val mediaType = normalizeMediaType(credit.mediaType) ?: return@mapNotNull null
                ActorRailItem.Credit(
                    credit = credit,
                    mediaType = mediaType,
                    subtitle = credit.job
                )
            }
            .distinctBy { Triple(it.credit.id, it.mediaType, it.subtitle?.trim()?.lowercase()) }
            .sortedBy { featuredCrewRank(it.subtitle) }

        val cast = person?.combinedCredits?.cast
            .orEmpty()
            .mapNotNull { credit ->
                val mediaType = normalizeMediaType(credit.mediaType) ?: return@mapNotNull null
                ActorRailItem.Credit(
                    credit = credit,
                    mediaType = mediaType,
                    subtitle = credit.character
                )
            }
            .distinctBy { it.credit.id to it.mediaType }

        buildList {
            addAll(featuredCrew)
            if (featuredCrew.isNotEmpty() && cast.isNotEmpty()) add(ActorRailItem.Separator)
            addAll(cast)
        }
    }

    LaunchedEffect(railItems, isLoading) {
        if (!isLoading && railItems.any { it is ActorRailItem.Credit }) {
            delay(150)
            runCatching { firstCreditFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        when {
            isLoading -> Text("Loading...")
            person == null -> Text("Not found${error?.let { " — $it" } ?: ""}")
            else -> {
                val p = person!!
                val bioScrollState = rememberScrollState()

                Column(modifier = Modifier.fillMaxSize()) {
                    Row {
                        AsyncImage(
                            model = p.profilePath?.let { TmdbRepository.PROFILE_BASE + it },
                            contentDescription = p.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(140.dp)
                                .height(190.dp)
                        )

                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(p.name)

                            p.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                                LaunchedEffect(bio) {
                                    delay(1800)
                                    if (bioScrollState.maxValue > 0) {
                                        val durationMs = (bio.length * 45).coerceIn(4000, 25000)
                                        bioScrollState.animateScrollTo(
                                            bioScrollState.maxValue,
                                            animationSpec = tween(
                                                durationMillis = durationMs,
                                                easing = LinearEasing
                                            )
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .height(150.dp)
                                        .padding(top = 6.dp)
                                        .verticalScroll(bioScrollState)
                                ) {
                                    Text(bio)
                                }
                            }
                        }
                    }

                    Text(
                        text = "Credits (${railItems.count { it is ActorRailItem.Credit }})",
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )

                    error?.let {
                        Text(it, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(
                            items = railItems,
                            key = { index, item ->
                                when (item) {
                                    is ActorRailItem.Credit ->
                                        "${item.mediaType}:${item.credit.id}:${item.subtitle ?: ""}:$index"
                                    ActorRailItem.Separator -> "separator:$index"
                                }
                            }
                        ) { index, item ->
                            when (item) {
                                is ActorRailItem.Credit -> {
                                    val imdbId = resolvedCreditIds[
                                        viewModel.creditLookupKey(
                                            tmdbId = item.credit.id,
                                            mediaType = item.mediaType
                                        )
                                    ]

                                    val isWatched = imdbId?.let {
                                        viewModel.watchedKey(it, item.mediaType) in watchedKeys
                                    } == true

                                    CreditCard(
                                        credit = item.credit,
                                        subtitle = item.subtitle,
                                        isWatched = isWatched,
                                        onClick = {
                                            viewModel.resolveAndNavigate(
                                                tmdbId = item.credit.id,
                                                mediaType = item.mediaType,
                                                onNavigateDetail = onNavigateDetail
                                            )
                                        },
                                        modifier = if (index == 0) {
                                            Modifier.focusRequester(firstCreditFocusRequester)
                                        } else {
                                            Modifier
                                        }
                                    )
                                }

                                ActorRailItem.Separator -> {
                                    SeparatorCard()
                                }
                            }
                        }
                    }

                    Card(
                        onClick = onBack,
                        colors = CardDefaults.colors(
                            containerColor = Color(0xFF1B3A57),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Back", modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

private fun normalizeMediaType(mediaType: String?): String? =
    when (mediaType?.lowercase()) {
        "movie" -> "movie"
        "tv", "series" -> "series"
        else -> null
    }

private fun featuredCrewRank(job: String?): Int =
    when (job?.trim()?.lowercase()) {
        "director" -> 0
        "writer" -> 1
        "screenplay" -> 2
        "story" -> 3
        "teleplay" -> 4
        "author" -> 5
        "novel" -> 6
        "characters" -> 7
        else -> 100
    }

@Composable
private fun CreditCard(
    credit: TmdbPersonCredit,
    subtitle: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    KBCard(
        onClick = onClick,
        modifier = modifier.width(118.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
            ) {
                if (credit.posterPath != null) {
                    AsyncImage(
                        model = TmdbRepository.POSTER_BASE + credit.posterPath,
                        contentDescription = credit.title ?: credit.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(KBSurfaceRaised, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = credit.title ?: credit.name.orEmpty(),
                            color = KBTextHi,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (isWatched) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC111111))
                            .border(1.dp, Color.White.copy(alpha = 0.95f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✓",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = credit.title ?: credit.name.orEmpty(),
                    maxLines = 2
                )

                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = KBTextLo,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SeparatorCard() {
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(210.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "│",
            color = KBTextLo
        )
    }
}
