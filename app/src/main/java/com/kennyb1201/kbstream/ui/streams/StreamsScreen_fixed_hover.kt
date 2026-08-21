package com.kennyb1201.kbstream.ui.streams

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun StreamsScreen(
    title: String,
    displayName: String,
    season: Int?,
    episode: Int?,
    backdropUrl: String?,
    clearLogoUrl: String?,
    onStreamSelected: (selected: Stream, allSources: List<Stream>) -> Unit,
    viewModel: StreamsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // DetailScreen passes episode titles in the form:
    // "Show Name S1 E2 • Episode Name". Extract everything after the
    // season/episode marker so the guide can show the real episode name.
    val episodeTitle = if (season != null && episode != null) {
        val episodeMarker = Regex("S\\s*${season}\\s*E\\s*${episode}\\b", RegexOption.IGNORE_CASE)
        episodeMarker.find(title)?.let { match ->
            title.substring(match.range.last + 1)
                .removePrefix("•")
                .removePrefix("-")
                .trim()
                .takeIf { it.isNotBlank() }
        }
    } else {
        null
    }

    val episodeLabel = if (season != null && episode != null) {
        "S%02d · E%02d".format(season, episode)
    } else {
        null
    }

    val sourceLabel = when {
        isLoading -> "Finding sources"
        streams.isNotEmpty() -> "${streams.size} sources found"
        else -> "No sources found"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(backdropUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            KBVoid.copy(alpha = 0.30f),
                            KBVoid.copy(alpha = 0.74f),
                            KBVoid
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            StreamsHeader(
                displayName = displayName,
                clearLogoUrl = clearLogoUrl,
                episodeLabel = episodeLabel,
                episodeTitle = episodeTitle,
                sourceLabel = sourceLabel
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 22.dp,
                    bottom = 28.dp
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusGroup()
            ) {
                when {
                    isLoading -> {
                        item {
                            StreamsHeroState(
                                title = "FINDING SOURCES",
                                message = "Checking installed stream add-ons."
                            )
                        }
                    }

                    streams.isEmpty() -> {
                        item {
                            StreamsHeroState(
                                title = "NO SOURCES FOUND",
                                message = "Try another add-on or check that the provider is online."
                            )
                        }
                    }

                    else -> {
                        items(
                            items = streams,
                            key = { stream ->
                                listOf(
                                    stream.url.orEmpty(),
                                    stream.title.orEmpty(),
                                    stream.name.orEmpty(),
                                    stream.infoHash.orEmpty(),
                                    stream.fileIdx?.toString().orEmpty()
                                ).joinToString("|")
                            }
                        ) { stream ->
                            StreamCard(
                                stream = stream,
                                onClick = {
                                    onStreamSelected(stream, streams)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamsHeader(
    displayName: String,
    clearLogoUrl: String?,
    episodeLabel: String?,
    episodeTitle: String?,
    sourceLabel: String
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        if (!clearLogoUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(clearLogoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
            )
        } else {
            Text(
                text = displayName,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        episodeLabel?.let {
            Text(
                text = if (episodeTitle != null) "$it · $episodeTitle" else it,
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Text(
            text = sourceLabel,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun StreamCard(
    stream: Stream,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.012f else 1f,
        animationSpec = tween(140),
        label = "streamCardScale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.97f,
        animationSpec = tween(140),
        label = "streamCardAlpha"
    )

    KBCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isFocused) {
                        KBSurfaceRaised.copy(alpha = 0.96f)
                    } else {
                        KBSurface
                    }
                )
                .border(
                    width = if (isFocused) 1.dp else 0.dp,
                    color = if (isFocused) {
                        KBAccent.copy(alpha = 0.28f)
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 18.dp, vertical = 15.dp)
        ) {
            stream.name
                ?.takeIf { it.isNotBlank() }
                ?.let { name ->
                    Text(
                        text = name,
                        color = if (isFocused) KBAccent else KBTextLo,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

            Text(
                text = stream.displayText(),
                color = if (isFocused) KBAccent else KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    top = if (stream.name.isNullOrBlank()) 0.dp else 5.dp
                )
            )
        }
    }
}

private fun Stream.displayText(): String {
    return listOfNotNull(
        title?.takeIf { it.isNotBlank() },
        description?.takeIf { it.isNotBlank() }
    )
        .distinct()
        .joinToString(separator = "\n")
        .ifBlank { "Stream details unavailable" }
}

@Composable
private fun StreamsHeroState(
    title: String,
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 44.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    KBSurface.copy(alpha = 0.94f),
                    RoundedCornerShape(18.dp)
                )
                .border(
                    1.dp,
                    KBTextLo.copy(alpha = 0.14f),
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 26.dp, vertical = 22.dp)
        ) {
            Text(
                text = title,
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = message,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
