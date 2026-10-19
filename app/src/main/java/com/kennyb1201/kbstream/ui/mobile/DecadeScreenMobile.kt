package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.ui.browse.decadeBrowseScreenKey
import com.kennyb1201.kbstream.ui.decade.DecadeViewModel
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * A decade page, for a finger.
 *
 * Same [DecadeViewModel], same genre chips (which re-run the decade's rails
 * through `onGenreSelected`), same poster-fan header built from the decade's
 * top titles. The sections and everything below them are
 * [SectionsBrowseMobile].
 */
@Composable
fun DecadeScreenMobile(
    decadeStart: Int,
    name: String,
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: DecadeViewModel = viewModel()
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val pagingStates by viewModel.pagingStates.collectAsStateWithLifecycle()
    val genres by viewModel.browseGenres.collectAsStateWithLifecycle()
    val selectedGenreId by viewModel.selectedGenreId.collectAsStateWithLifecycle()

    // Same argument list as the TV page, so the same-route guard behaves the
    // same way when Back restores the screen.
    LaunchedEffect(decadeStart) {
        viewModel.load(decadeStart, viewModel.selectedGenreId.value)
    }

    MobileTheme {
        SectionsBrowseMobile(
            header = SectionsHeader(
                eyebrow = "Decade",
                title = name
            ),
            sectionsRaw = sections,
            isLoading = isLoading,
            error = error,
            screenKey = remember(decadeStart) { decadeBrowseScreenKey(decadeStart) },
            emptyText = "Nothing found for $name",
            resolvedIds = resolvedIds,
            watchedKeys = watchedKeys,
            partialWatchedKeys = partialWatchedKeys,
            lookupKey = viewModel::lookupKey,
            watchedKey = viewModel::watchedKey,
            genres = genres,
            selectedGenreId = selectedGenreId,
            onSelectGenre = viewModel::onGenreSelected,
            paging = { title ->
                val state = pagingStates[title]
                RailPaging(
                    hasMore = state?.hasMore == true,
                    isLoadingMore = state?.isLoadingMore == true
                )
            },
            onLoadMore = viewModel::loadMoreSection,
            onRetry = { viewModel.load(decadeStart, viewModel.selectedGenreId.value) },
            onOpenItem = { item ->
                viewModel.resolveAndNavigate(
                    tmdbId = item.item.id,
                    mediaType = item.mediaType,
                    onNavigateDetail = onNavigateDetail
                )
            },
            onToggleWatched = { item, isWatched ->
                if (isWatched) {
                    viewModel.markUnwatched(item.item.id, item.mediaType)
                } else {
                    viewModel.markAsWatched(item.item.id, item.mediaType)
                }
            }
        )
    }
}
