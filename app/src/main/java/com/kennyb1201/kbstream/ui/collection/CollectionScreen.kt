package com.kennyb1201.kbstream.ui.collection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionPart
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun CollectionScreen(
    collectionId: Int,
    collectionName: String,
    repository: TmdbRepository,
    onNavigateDetail: (type: String, id: String) -> Unit
) {
    var collection by remember { mutableStateOf<TmdbCollectionDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(collectionId) {
        isLoading = true
        collection = runCatching { repository.getCollection(collectionId) }.getOrNull()
        isLoading = false
    }

    val detail = collection

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "hero") {
                CollectionHero(
                    name = detail?.name ?: collectionName,
                    overview = detail?.overview,
                    posterUrl = detail?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                )
            }

            when {
                isLoading -> {
                    item(key = "loading") {
                        CollectionMessagePanel(
                            title = "Loading collection...",
                            body = "Fetching movies in this collection.",
                            showSpinner = true
                        )
                    }
                }

                detail == null -> {
                    item(key = "error") {
                        CollectionMessagePanel(
                            title = "Collection unavailable",
                            body = "We couldn't load this collection right now."
                        )
                    }
                }

                detail.parts.isEmpty() -> {
                    item(key = "empty") {
                        CollectionMessagePanel(
                            title = "No movies found",
                            body = "This collection does not currently list any titles."
                        )
                    }
                }

                else -> {
                    item(key = "count") {
                        Text(
                            text = "${detail.parts.size} movies",
                            color = KBAccent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    items(
                        items = detail.parts.sortedBy { it.releaseDate ?: "9999-99-99" },
                        key = { part: TmdbCollectionPart -> "collection_part:${part.id}" }
                    ) { part: TmdbCollectionPart ->
                        CollectionMovieRow(
                            part = part,
                            onClick = {
                                onNavigateDetail("movie", part.id.toString())
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionHero(
    name: String,
    overview: String?,
    posterUrl: String?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, RoundedCornerShape(20.dp))
            .border(1.dp, KBTextLo.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PosterCard(
            posterUrl = posterUrl,
            contentDescription = name,
            isWatched = false,
            onClick = {},
            modifier = Modifier
                .width(148.dp)
                .height(222.dp)
        )

        Column(
            modifier = Modifier
                .padding(start = 18.dp)
                .weight(1f)
        ) {
            TypePill("collection")

            Text(
                text = name,
                style = MaterialTheme.typography.headlineMedium,
                color = KBTextHi,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )

            overview
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        text = it,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
        }
    }
}

@Composable
private fun CollectionMovieRow(
    part: TmdbCollectionPart,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBTextHi
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, KBTextLo.copy(alpha = 0.25f))),
            focusedBorder = Border(BorderStroke(2.dp, KBAccent))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PosterCard(
                posterUrl = part.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                contentDescription = part.title ?: part.name ?: "Collection movie",
                isWatched = false,
                onClick = onClick,
                modifier = Modifier
                    .width(92.dp)
                    .height(138.dp)
            )

            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = part.title ?: part.name ?: "Untitled",
                    style = MaterialTheme.typography.titleMedium,
                    color = KBTextHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                part.releaseDate
                    ?.takeIf { it.isNotBlank() }
                    ?.let { releaseDate ->
                        Text(
                            text = releaseDate.take(4),
                            style = MaterialTheme.typography.bodyMedium,
                            color = KBTextLo,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
            }
        }
    }
}

@Composable
private fun CollectionMessagePanel(
    title: String,
    body: String,
    showSpinner: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, RoundedCornerShape(16.dp))
            .border(1.dp, KBTextLo.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = KBAccent,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = KBTextHi
            )
        }
        Text(
            text = body,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun TypePill(type: String) {
    Box(
        modifier = Modifier
            .background(KBSurfaceRaised, RoundedCornerShape(999.dp))
            .border(1.dp, KBAccent.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = type.uppercase(),
            color = KBAccent,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
