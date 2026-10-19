package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.ui.browse.studioBrowseScreenKey
import com.kennyb1201.kbstream.ui.studio.StudioViewModel
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * A studio / network / streaming-service page, for a finger.
 *
 * Same [StudioViewModel], same genre chips (the chips re-run the rails with the
 * genre ANDed onto the brand, through the view model's own `onGenreSelected`),
 * same brand mark at the top, same streaming-vs-studio wording. The sections,
 * poster grid, context sheet, paging and return record come from
 * [SectionsBrowseMobile].
 *
 * The logo is drawn by the TV page's own `BrandLogo`, so the three-way brand
 * treatment (whiten / as-is / unusable) and its candidate walk are not
 * duplicated here - only the plate it sits on is the phone's.
 */
@Composable
fun StudioScreenMobile(
    id: Int,
    name: String,
    isNetwork: Boolean,
    providerId: Int? = null,
    networkOrCompanyId: Int? = null,
    networkIsCompany: Boolean = false,
    originalsCompanyId: Int? = null,
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: StudioViewModel = viewModel()
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val resolvedIds by viewModel.resolvedIds.collectAsStateWithLifecycle()
    val pagingStates by viewModel.pagingStates.collectAsStateWithLifecycle()
    val logoUrls by viewModel.logoUrls.collectAsStateWithLifecycle()
    val companyInfo by viewModel.companyInfo.collectAsStateWithLifecycle()
    val isService by viewModel.isService.collectAsStateWithLifecycle()
    val genres by viewModel.browseGenres.collectAsStateWithLifecycle()
    val selectedGenreId by viewModel.selectedGenreId.collectAsStateWithLifecycle()

    // Re-pass the current chip selection, exactly as the TV page does, so the
    // view model's same-route guard treats a Back-restore as a no-op instead of
    // resetting the filter.
    LaunchedEffect(id, isNetwork, providerId) {
        viewModel.load(
            id,
            isNetwork,
            providerId,
            networkOrCompanyId,
            networkIsCompany,
            originalsCompanyId,
            name,
            viewModel.selectedGenreId.value
        )
    }

    fun reload() {
        viewModel.load(
            id,
            isNetwork,
            providerId,
            networkOrCompanyId,
            networkIsCompany,
            originalsCompanyId,
            name,
            viewModel.selectedGenreId.value
        )
    }

    MobileTheme {
        SectionsBrowseMobile(
            header = SectionsHeader(
                eyebrow = when {
                    isService -> "Streaming Service"
                    companyInfo?.description != null -> "Production Company"
                    else -> "Network"
                },
                title = companyInfo?.name?.takeIf { it.isNotBlank() } ?: name,
                meta = listOfNotNull(
                    companyInfo?.originCountry?.takeIf { it.isNotBlank() },
                    companyInfo?.headquarters?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").takeIf { it.isNotBlank() },
                description = companyInfo?.description,
                logoUrls = logoUrls
            ),
            sectionsRaw = sections,
            isLoading = isLoading,
            error = error,
            screenKey = remember(id, isNetwork, providerId) {
                studioBrowseScreenKey(id, isNetwork, providerId)
            },
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
            onRetry = { reload() },
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
