package com.kennyb1201.kbstream.ui.studio

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.toBitmap
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbCompanyDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun StudioScreen(
    id: Int,
    name: String,
    isNetwork: Boolean,
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: StudioViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val pagingStates by viewModel.pagingStates.collectAsStateWithLifecycle()
    val logoUrl by viewModel.logoUrl.collectAsStateWithLifecycle()
    val companyInfo by viewModel.companyInfo.collectAsStateWithLifecycle()

    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(id, isNetwork) {
        viewModel.load(id, isNetwork)
    }

    LaunchedEffect(sections, isLoading) {
        if (!isLoading && sections.any { it.items.isNotEmpty() }) {
            delay(100)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column {
            StudioHeader(
                name = name,
                logoUrl = logoUrl,
                info = companyInfo
            )

            when {
                isLoading -> {
                    CircularProgressIndicator(color = KBAccent, strokeWidth = 3.dp)
                }

                sections.isEmpty() -> {
                    Text("Nothing found for $name")
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = sections,
                            key = { section: StudioSection -> section.title }
                        ) { section ->
                            val pagingState = pagingStates[section.title] ?: StudioRailPagingState()

                            StudioRailRow(
                                section = section,
                                watchedKeys = watchedKeys,
                                resolvedIds = resolvedIds,
                                onNavigateDetail = onNavigateDetail,
                                onLoadMore = { viewModel.loadMoreSection(section.title) },
                                hasMore = pagingState.hasMore,
                                isLoadingMore = pagingState.isLoadingMore,
                                isFirstSection = section == sections.firstOrNull(),
                                firstItemFocusRequester = firstItemFocusRequester,
                                viewModel = viewModel
                            )
                        }

                        item(key = "bottom_spacer") {
                            Box(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioHeader(
    name: String,
    logoUrl: String?,
    info: TmdbCompanyDetail?
) {
    Column(
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        if (!logoUrl.isNullOrBlank()) {
            // No white chip: like Nuvio/Coral-style TV UIs, render the logo
            // straight on the dark surface and recolor dark (black) artwork to
            // white so it reads. Light/colored logos pass through untouched —
            // the tint only kicks in when the sampled artwork is dark.
            TintedBrandLogo(
                url = logoUrl,
                name = name,
                modifier = Modifier
                    .width(480.dp)
                    .height(120.dp)
            )
        } else {
            Text(
                text = name,
                style = MaterialTheme.typography.displayLarge
            )
        }

        // Studio/network blurb + location, shown under the logo (or name).
        val description = info?.description
            ?.takeIf { it.isNotBlank() }
        val location = listOfNotNull(
            info?.headquarters?.takeIf { it.isNotBlank() },
            info?.originCountry?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

        if (!description.isNullOrBlank() || location.isNotBlank()) {
            Column(
                modifier = Modifier.padding(top = 6.dp)
            ) {
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KBTextLo,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 24.dp)
                    )
                }

                if (location.isNotBlank()) {
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KBAccent,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Brand logo rendered for a dark surface. Sample the decoded artwork's
 * luminance; when it is a dark mark (black/gray logo drawn for light
 * backgrounds — the TMDB company/network default), recolor it white via
 * SrcIn tint, which preserves the alpha and turns the mark into a white
 * silhouette — the look Nuvio/Coral TV interfaces use. Light/colored logos
 * pass through unchanged (the Netflix N, Disney castle, etc. stay colored).
 * Public so the detail screen's studio/network chips share the same logic.
 */
@Composable
fun TintedBrandLogo(
    url: String,
    name: String,
    modifier: Modifier = Modifier
) {
    var logoIsDark by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .crossfade(true)
            .build(),
        contentDescription = name,
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
            try {
                val image = state.result.image
                // Requesting software bitmaps above makes pixel sampling safe.
                // Only dark monochrome artwork is recolored; colored logos
                // such as DC Studios remain untouched.
                logoIsDark = isDarkArtwork(image.toBitmap())
            } catch (_: Throwable) {
                // Safe fallback: assume dark monochrome artwork
                logoIsDark = true
            }
        },
        colorFilter = if (logoIsDark) ColorFilter.tint(Color.White) else null,
        modifier = modifier
    )
}

/** Retained for callers that need to classify ordinary software bitmaps. */
fun isDarkArtwork(bitmap: android.graphics.Bitmap): Boolean {
    val sample =
        android.graphics.Bitmap.createScaledBitmap(bitmap, 48, 24, true)
    var sum = 0L
    var saturationSum = 0L
    var count = 0L
    for (y in 0 until sample.height) {
        for (x in 0 until sample.width) {
            val px = sample.getPixel(x, y)
            val alpha = (px ushr 24) and 0xFF
            if (alpha > 20) {
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                // Relative luminance (Rec. 709), 0..255.
                sum += (0.2126f * r + 0.7152f * g + 0.0722f * b).toLong()
                saturationSum += (maxOf(r, g, b) - minOf(r, g, b)).toLong()
                count++
            }
        }
    }
    if (sample !== bitmap) sample.recycle()
    if (count == 0L) return false
    val averageLuminance = sum / count
    val averageSaturation = saturationSum / count
    return averageLuminance < 148L && averageSaturation < 42L
}

@Composable
private fun StudioRailRow(
    section: StudioSection,
    watchedKeys: Set<String>,
    resolvedIds: Map<String, String>,
    onNavigateDetail: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    isFirstSection: Boolean,
    firstItemFocusRequester: FocusRequester,
    viewModel: StudioViewModel
) {
    val rowState = rememberLazyListState()

    InfiniteStudioRailHandler(
        listState = rowState,
        itemCount = section.items.size,
        hasMore = hasMore,
        isLoadingMore = isLoadingMore,
        onLoadMore = onLoadMore
    )

    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            color = KBTextLo,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(end = 8.dp),
            modifier = Modifier.focusGroup()
        ) {
            items(
                items = section.items,
                key = { item: StudioItem ->
                    "${item.mediaType}:${item.item.id}"
                }
            ) { studioItem ->
                val tmdbId = studioItem.item.id
                val rawMediaType = studioItem.mediaType
                val normalizedType = when (rawMediaType.lowercase()) {
                    "tv", "series" -> "series"
                    else -> "movie"
                }

                val imdbId = resolvedIds[
                    viewModel.lookupKey(tmdbId, normalizedType)
                ]

                val watched = imdbId?.let {
                    viewModel.watchedKey(it, normalizedType) in watchedKeys
                } == true

                val isFirstItem =
                    isFirstSection && studioItem == section.items.firstOrNull()

                PosterCard(
                    posterUrl = studioItem.item.posterPath
                        ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                    contentDescription = studioItem.item.title
                        ?: studioItem.item.name,
                    isWatched = watched,
                    onClick = {
                        viewModel.resolveAndNavigate(
                            tmdbId = studioItem.item.id,
                            mediaType = rawMediaType,
                            onNavigateDetail = onNavigateDetail
                        )
                    },
                    modifier = Modifier
                        .width(140.dp)
                        .height(210.dp)
                        .padding(end = 12.dp)
                        .then(
                            if (isFirstItem) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun InfiniteStudioRailHandler(
    listState: LazyListState,
    itemCount: Int,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(listState, itemCount, hasMore, isLoadingMore) {
        snapshotFlow {
            val lastVisibleIndex =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to itemCount
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItems) ->
                val threshold = 6
                val shouldLoadMore =
                    hasMore &&
                    !isLoadingMore &&
                    totalItems > 0 &&
                    lastVisibleIndex >= totalItems - threshold

                if (shouldLoadMore) {
                    onLoadMore()
                }
            }
    }
}
