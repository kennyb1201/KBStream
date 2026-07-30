package com.kennyb1201.kbstream.ui.actor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.theme.CardShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ActorScreen(
    personId: Int,
    onBack: () -> Unit,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: ActorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val scope = rememberCoroutineScope()
    val person by viewModel.person.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val firstCreditFocusRequester = remember { FocusRequester() }

    LaunchedEffect(personId) {
        viewModel.load(personId)
    }

    LaunchedEffect(person) {
        if (!person?.combinedCredits?.cast.isNullOrEmpty()) {
            delay(150)
            runCatching { firstCreditFocusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when {
            isLoading -> Text("Loading...")
            person == null -> Text("Not found${error?.let { " — $it" } ?: ""}")
            else -> {
                val p = person!!
                val credits = p.combinedCredits?.cast.orEmpty()
                val bioScrollState = rememberScrollState()

                // The whole screen is a fixed, non-scrolling Column -- every section
                // has a bounded height, so nothing here can trigger the framework's
                // automatic "scroll to bring focused item into view" behavior that
                // was cropping the photo / hiding the filmography row before.
                Column(modifier = Modifier.fillMaxSize()) {
                    Row {
                        AsyncImage(
                            model = p.profilePath?.let { "${TmdbRepository.PROFILE_BASE}$it" },
                            contentDescription = p.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(140.dp).height(190.dp)
                        )
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(p.name)
                            p.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                                LaunchedEffect(bio) {
                                    delay(1800) // give the reader a moment before it starts moving
                                    if (bioScrollState.maxValue > 0) {
                                        val durationMs = (bio.length * 45).coerceIn(4000, 25000)
                                        bioScrollState.animateScrollTo(
                                            bioScrollState.maxValue,
                                            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
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

                    Text("Filmography (${credits.size})", modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    error?.let { Text(it, modifier = Modifier.padding(bottom = 8.dp)) }

                    LazyRow {
                        itemsIndexed(credits) { index, credit: TmdbPersonCredit ->
                            Card(
                                onClick = {
                                    val mediaType = if (credit.mediaType == "tv") "series" else "movie"
                                    scope.launch {
                                        val imdbId = viewModel.resolveImdbId(credit.id, mediaType)
                                        if (imdbId != null) onNavigateDetail(mediaType, imdbId)
                                    }
                                },
                                shape = CardDefaults.shape(shape = CardShape),
                                modifier = Modifier
                                    .width(110.dp)
                                    .height(160.dp)
                                    .padding(end = 10.dp)
                                    .let { if (index == 0) it.focusRequester(firstCreditFocusRequester) else it }
                            ) {
                                AsyncImage(
                                    model = credit.posterPath?.let { "${TmdbRepository.PROFILE_BASE}$it" },
                                    contentDescription = credit.title ?: credit.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    Card(
                        onClick = onBack,
                        colors = CardDefaults.colors(containerColor = Color(0xFF1B3A57), contentColor = Color.White),
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Back", modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
