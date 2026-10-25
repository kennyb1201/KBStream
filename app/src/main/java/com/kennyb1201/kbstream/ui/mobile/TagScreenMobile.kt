package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.ui.browse.tagBrowseScreenKey
import com.kennyb1201.kbstream.ui.tag.TagViewModel
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * A genre or keyword page, for a finger.
 *
 * The same activity-scoped [TagViewModel] as the TV page: the rails it streams
 * in, its paging states, its watched and resolved-id sets, and the same
 * `resolveAndNavigate` / mark-as-watched calls. The layout, the header fan, the
 * hidden-title filter, the menu and the return record all come from
 * [SectionsBrowseMobile], so this page differs from a studio or a decade only
 * in the words on its header.
 *
 * The TV page's rail-scrolling focus restore has no touch twin: the record is
 * honoured by scrolling the grid to the card instead.
 *
 * @param type the media type the page browses ("movie" / "tv"), part of the
 *   return-screen key so one genre's movie page cannot answer for its own
 *   series page.
 */
@Composable
fun TagScreenMobile(
    id: Int,
    name: String,
    isKeyword: Boolean,
    type: String,
    onBack: () -> Unit,
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: TagViewModel = viewModel()
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val pagingStates by viewModel.pagingStates.collectAsStateWithLifecycle()

    LaunchedEffect(id, isKeyword, type) {
        viewModel.load(id, isKeyword, type)
    }

    MobileTheme {
        SectionsBrowseMobile(
            header = SectionsHeader(
                eyebrow = if (isKeyword) "Keyword" else "Genre",
                title = name
            ),
            onBack = onBack,
            sectionsRaw = sections,
            isLoading = isLoading,
            error = error,
            // The TV page's own key, built from the same three route fields.
            screenKey = remember(id, isKeyword, type) {
                tagBrowseScreenKey(id, isKeyword, type)
            },
            emptyText = "Nothing found for $name",
            resolvedIds = resolvedIds,
            watchedKeys = watchedKeys,
            partialWatchedKeys = partialWatchedKeys,
            lookupKey = viewModel::lookupKey,
            watchedKey = viewModel::watchedKey,
            paging = { title ->
                val state = pagingStates[title]
                RailPaging(
                    hasMore = state?.hasMore == true,
                    isLoadingMore = state?.isLoadingMore == true
                )
            },
            onLoadMore = viewModel::loadMoreSection,
            onRetry = { viewModel.load(id, isKeyword, type) },
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
