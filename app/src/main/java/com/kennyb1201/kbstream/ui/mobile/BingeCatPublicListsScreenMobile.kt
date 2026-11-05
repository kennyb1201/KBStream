package com.kennyb1201.kbstream.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.bingecat.BingeCatImportMode
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicCategory
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicListCard
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicListRules
import com.kennyb1201.kbstream.data.bingecat.BingeCatRow
import com.kennyb1201.kbstream.data.bingecat.BingeCatSearchType
import com.kennyb1201.kbstream.ui.bingecat.BingeCatGate
import com.kennyb1201.kbstream.ui.bingecat.BingeCatImportPhase
import com.kennyb1201.kbstream.ui.bingecat.BingeCatPublicLevel
import com.kennyb1201.kbstream.ui.bingecat.BingeCatPublicListsUiState
import com.kennyb1201.kbstream.ui.bingecat.BingeCatPublicListsViewModel
import com.kennyb1201.kbstream.ui.bingecat.BingeCatTitlesState
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.TouchChoiceChip
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * BingeCat public lists, for a finger.
 *
 * The same activity-scoped [BingeCatPublicListsViewModel] and the same level
 * stack as the TV surface - categories, subcategories, a category's lists,
 * search, list detail, the import confirmation - so the browsing and the import
 * are one implementation with two layouts. The TV page's cards and buttons
 * answer the D-pad only, which is why this twin exists: on a phone the page took
 * no press at all (see
 * [com.kennyb1201.kbstream.ui.bingecat.BingeCatPublicListsScreen]).
 *
 * What is laid out differently rather than merely re-skinned: a card's actions
 * sit UNDER its copy instead of to the right of it, because a 360dp phone has no
 * room for a poster strip, four lines of attribution and two buttons in one row;
 * and the search field takes the full width with its button beneath.
 */
@Composable
fun BingeCatPublicListsScreenMobile(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenTitle: (mediaType: String, id: String) -> Unit,
    vm: BingeCatPublicListsViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    // Back walks the surface's own stack; at the root it leaves for the BingeCat
    // connect page (the caller's onBack), which is where the entry sits.
    BackHandler {
        if (uiState.canGoBack) vm.back() else onBack()
    }

    MobileTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MobileBackButton(
                        onBack = { if (uiState.canGoBack) vm.back() else onBack() }
                    )
                    Text(
                        text = "BINGECAT PUBLIC LISTS",
                        color = KBAccent,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KBSurface, KBShapePanel)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Attribution is the surface's standing sentence: these are
                    // community lists, not KBStream's picks, on every level.
                    Text(
                        text = "Community lists on BingeCat. Importing one copies it into " +
                            "your BingeCat profile's lists, where it shows up in Library " +
                            "like any list you made yourself - KBStream does not pick " +
                            "these titles.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall
                    )

                    uiState.gate?.let { gate -> GatePanelMobile(gate) }

                    when (val level = uiState.level) {
                        BingeCatPublicLevel.Categories -> CategoriesBodyMobile(
                            state = uiState,
                            onOpenCategory = vm::openCategory,
                            onOpenSearch = vm::openSearch,
                            onRetry = vm::loadCategories
                        )

                        is BingeCatPublicLevel.Subcategories -> SubcategoriesBodyMobile(
                            level = level,
                            onOpenChild = { child -> vm.openSubcategory(level, child) }
                        )

                        is BingeCatPublicLevel.Lists -> ListsBodyMobile(
                            title = level.title,
                            cards = uiState.cards,
                            isLoading = uiState.isLoadingCards,
                            error = uiState.cardsError,
                            phases = uiState.importPhases,
                            onOpenCard = vm::openDetail,
                            onImport = vm::startImport
                        )

                        BingeCatPublicLevel.Search -> SearchBodyMobile(
                            state = uiState,
                            onQueryChange = vm::setSearchQuery,
                            onTypeChange = vm::setSearchType,
                            onSearch = vm::runSearch,
                            onOpenCard = vm::openDetail,
                            onImport = vm::startImport
                        )

                        is BingeCatPublicLevel.Detail -> DetailBodyMobile(
                            card = level.card,
                            titles = uiState.titles,
                            phase = uiState.importPhases[level.card.importId],
                            onOpenTitle = onOpenTitle,
                            onImport = { vm.startImport(level.card) }
                        )
                    }

                    uiState.importTarget?.let { target ->
                        ImportConfirmPanelMobile(
                            card = target,
                            modes = uiState.importModes,
                            selected = uiState.importMode,
                            onSelect = vm::chooseImportMode,
                            onConfirm = vm::confirmImport,
                            onCancel = vm::cancelImport
                        )
                    }
                }
            }
        }
    }
}

/** The prompt each gate has, instead of a dead button. */
@Composable
private fun GatePanelMobile(gate: BingeCatGate) {
    val (title, message) = when (gate) {
        BingeCatGate.NOT_CONNECTED -> "BingeCat is not connected" to
            "Connect BingeCat on the BingeCat page first: public lists are read with " +
            "your own BingeCat session."
        BingeCatGate.NEEDS_REAPPROVAL -> "Reconnect to browse public lists" to
            "This connection was approved before KBStream asked to read public lists. " +
            "Reconnect BingeCat to allow it; your profile choice and cached rows are kept."
        BingeCatGate.NEEDS_MAPPING -> "Choose a BingeCat profile first" to
            "Importing a list creates it in a BingeCat profile, and this KBStream profile " +
            "has not picked one yet. Choose it on the BingeCat page, then come back."
    }
    PanelMobile(title = title, message = message)
}

@Composable
private fun CategoriesBodyMobile(
    state: BingeCatPublicListsUiState,
    onOpenCategory: (BingeCatPublicCategory) -> Unit,
    onOpenSearch: () -> Unit,
    onRetry: () -> Unit
) {
    SectionHeaderMobile("Categories")
    KBButton(
        label = "SEARCH LISTS",
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenSearch
    )
    if (state.isLoadingCategories) {
        Text(
            text = "Reading BingeCat's categories…",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    state.categoriesError?.let { ReasonLineMobile(it) }
    val drawable = state.categories.filter { BingeCatPublicListRules.isDrawable(it) }
    if (drawable.isEmpty()) {
        Text(
            text = if (state.categoriesError != null) {
                "No categories to show from the last read."
            } else {
                "BingeCat returned no categories."
            },
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        KBButton(
            label = "TRY AGAIN",
            modifier = Modifier.fillMaxWidth(),
            onClick = onRetry
        )
        return
    }
    drawable.forEach { category ->
        CategoryTileMobile(category = category) { onOpenCategory(category) }
    }
}

@Composable
private fun CategoryTileMobile(category: BingeCatPublicCategory, onClick: () -> Unit) {
    val name = category.name?.takeIf { it.isNotBlank() } ?: return
    val count = BingeCatPublicListRules.categoryCountLabel(category.listCounts)
    val children = category.children.filter { BingeCatPublicListRules.isDrawable(it) }
    val art = category.thumbnailUrl?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapeCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(width = 72.dp, height = 48.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                category.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = listOfNotNull(
                        count,
                        children.takeIf { it.isNotEmpty() }?.let { "${it.size} subcategories" }
                    ).joinToString(" · "),
                    color = KBAccent,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        KBButton(
            label = if (children.isNotEmpty()) "OPEN" else "VIEW LISTS",
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick
        )
    }
}

/** A category's children, read without a request: they came in the tree. */
@Composable
private fun SubcategoriesBodyMobile(
    level: BingeCatPublicLevel.Subcategories,
    onOpenChild: (BingeCatPublicCategory) -> Unit
) {
    SectionHeaderMobile(level.parentName)
    level.children.forEach { child ->
        CategoryTileMobile(category = child) { onOpenChild(child) }
    }
}

@Composable
private fun ListsBodyMobile(
    title: String,
    cards: List<BingeCatPublicListCard>,
    isLoading: Boolean,
    error: String?,
    phases: Map<String, BingeCatImportPhase>,
    onOpenCard: (BingeCatPublicListCard) -> Unit,
    onImport: (BingeCatPublicListCard) -> Unit
) {
    SectionHeaderMobile(title)
    if (isLoading) {
        Text(
            text = "Reading this category's lists…",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    error?.let { ReasonLineMobile(it) }
    if (cards.isEmpty()) {
        Text(
            text = if (error != null) {
                "Nothing to show from the last read."
            } else {
                "This category holds no lists yet."
            },
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    cards.forEach { card ->
        PublicListCardMobile(
            card = card,
            phase = phases[card.importId],
            onOpen = { onOpenCard(card) },
            onImport = { onImport(card) }
        )
    }
}

@Composable
private fun SearchBodyMobile(
    state: BingeCatPublicListsUiState,
    onQueryChange: (String) -> Unit,
    onTypeChange: (BingeCatSearchType) -> Unit,
    onSearch: () -> Unit,
    onOpenCard: (BingeCatPublicListCard) -> Unit,
    onImport: (BingeCatPublicListCard) -> Unit
) {
    SectionHeaderMobile("Search public lists")
    KBTextField(
        value = state.searchQuery,
        onValueChange = onQueryChange,
        placeholder = "List name, theme, owner…",
        modifier = Modifier.fillMaxWidth(),
        onDone = onSearch
    )
    KBButton(
        label = if (state.isSearching) "SEARCHING…" else "SEARCH",
        enabled = !state.isSearching && BingeCatPublicListRules.searchEnabled(state.searchQuery),
        modifier = Modifier.fillMaxWidth(),
        onClick = onSearch
    )
    // The type filter as touch chips, so the chosen one reads as chosen
    // (a plain button has no selected state to draw).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BingeCatSearchType.values().forEach { option ->
            TouchChoiceChip(
                label = option.label,
                selected = option == state.searchType,
                onClick = { onTypeChange(option) }
            )
        }
    }
    if (!BingeCatPublicListRules.searchEnabled(state.searchQuery) && !state.isSearching) {
        Text(
            text = "Type at least two characters. Search finds BingeCat LISTS, never " +
                "individual titles.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    state.searchError?.let { ReasonLineMobile(it) }
    if (!state.isSearching && state.searchError == null && state.searchResults.isEmpty() &&
        BingeCatPublicListRules.searchEnabled(state.searchQuery)
    ) {
        Text(
            text = "No public lists match that search.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    state.searchResults.forEach { card ->
        PublicListCardMobile(
            card = card,
            phase = state.importPhases[card.importId],
            onOpen = { onOpenCard(card) },
            onImport = { onImport(card) }
        )
    }
}

/**
 * One public-list card, stacked for a phone.
 *
 * The attribution line is not optional: a public list is a BingeCat community
 * (or official BingeCat) list with an owner, and the card says so before it says
 * anything about its counts.
 */
@Composable
private fun PublicListCardMobile(
    card: BingeCatPublicListCard,
    phase: BingeCatImportPhase?,
    onOpen: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapeCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (card.isOfficial) "${card.name} · OFFICIAL" else card.name,
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = BingeCatPublicListRules.attribution(
                owner = card.owner,
                isOfficial = card.isOfficial,
                provider = card.provider
            ),
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = listOfNotNull(
                BingeCatPublicListRules.countsLabel(
                    card.movieCount,
                    card.seriesCount,
                    card.itemCount
                ),
                BingeCatPublicListRules.likesLabel(card.likes),
                BingeCatPublicListRules.addedByLabel(card.addedCount)
            ).joinToString(" · "),
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall
        )
        PosterStripMobile(card.coverPosters)
        card.description?.let { description ->
            Text(
                text = description,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
        PhaseLineMobile(phase)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KBButton(
                label = "DETAILS",
                modifier = Modifier.weight(1f),
                onClick = onOpen
            )
            KBButton(
                label = if (phase is BingeCatImportPhase.Importing) "IMPORTING…" else "IMPORT",
                enabled = phase !is BingeCatImportPhase.Importing,
                modifier = Modifier.weight(1f),
                onClick = onImport
            )
        }
    }
}

/** The detail level: metadata, attribution, the import action, and titles if any. */
@Composable
private fun DetailBodyMobile(
    card: BingeCatPublicListCard,
    titles: BingeCatTitlesState,
    phase: BingeCatImportPhase?,
    onOpenTitle: (mediaType: String, id: String) -> Unit,
    onImport: () -> Unit
) {
    Text(
        text = card.name,
        color = KBTextHi,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = BingeCatPublicListRules.attribution(
            owner = card.owner,
            isOfficial = card.isOfficial,
            provider = card.provider
        ),
        color = KBTextLo,
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        text = listOfNotNull(
            BingeCatPublicListRules.countsLabel(card.movieCount, card.seriesCount, card.itemCount),
            BingeCatPublicListRules.likesLabel(card.likes),
            BingeCatPublicListRules.addedByLabel(card.addedCount)
        ).joinToString(" · "),
        color = KBAccent,
        style = MaterialTheme.typography.labelSmall
    )
    card.description?.let { description ->
        Text(
            text = description,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    PosterStripMobile(card.coverPosters)
    KBButton(
        label = "SAVE TO MY BINGECAT",
        enabled = phase !is BingeCatImportPhase.Importing,
        modifier = Modifier.fillMaxWidth(),
        onClick = onImport
    )
    PhaseLineMobile(phase)
    SectionHeaderMobile("Titles")

    when (titles) {
        BingeCatTitlesState.Loading -> Text(
            text = "Reading this list's titles…",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )

        // Q4b: the key is not a list ref. The screen says what is true - the
        // titles are behind the import - instead of dressing covers as a grid.
        BingeCatTitlesState.AfterImport -> Text(
            text = "This list's titles are readable after you import it, through the " +
                "imported list in your Library. BingeCat has no public title read for it.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )

        is BingeCatTitlesState.Failed -> ReasonLineMobile(titles.message)

        is BingeCatTitlesState.Titles -> {
            Text(
                text = "This profile's rating ceiling applies to these titles; " +
                    "BingeCat does not age-screen its public lists.",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
            if (titles.rows.isEmpty()) {
                Text(
                    text = "This list has no titles BingeCat can show.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            titles.rows.forEach { row ->
                val detailId = row.detailId()
                if (detailId != null) {
                    TitleRowMobile(row) { onOpenTitle(row.mediaType, detailId) }
                }
            }
        }
    }
}

/** The id a Detail screen takes: IMDb first, then the typed TMDB form. */
private fun BingeCatRow.detailId(): String? =
    imdbId?.takeIf { it.isNotBlank() } ?: tmdbId?.let { "tmdb:$it" }

@Composable
private fun TitleRowMobile(row: BingeCatRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapeCard)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = listOfNotNull(
                    row.year?.toString(),
                    if (row.isMovie) "MOVIE" else "SERIES"
                ).joinToString(" · "),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
        KBButton(label = "OPEN", onClick = onClick)
    }
}

/**
 * The import confirmation: the mode, what it will do, and the two exits.
 *
 * The offered modes come from the list's own counts and content type (see
 * [BingeCatPublicListRules.modesFor]) - `anime` is HIDDEN for a list that is not
 * anime, never shown disabled without an explanation.
 */
@Composable
private fun ImportConfirmPanelMobile(
    card: BingeCatPublicListCard,
    modes: List<BingeCatImportMode>,
    selected: BingeCatImportMode,
    onSelect: (BingeCatImportMode) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    PanelMobile(
        title = "Import “${card.name}”",
        message = BingeCatPublicListRules.attribution(
            owner = card.owner,
            isOfficial = card.isOfficial,
            provider = card.provider
        ) + " · one list per import, so every result belongs to this list."
    )
    modes.forEach { mode ->
        TouchChoiceChip(
            label = BingeCatPublicListRules.modeLabel(mode),
            selected = mode == selected,
            onClick = { onSelect(mode) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = BingeCatPublicListRules.modeDescription(mode),
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KBButton(
            label = "IMPORT",
            modifier = Modifier.weight(1f),
            onClick = onConfirm
        )
        KBButton(
            label = "CANCEL",
            modifier = Modifier.weight(1f),
            onClick = onCancel
        )
    }
}

/** The card's (and the detail's) import line, when it has one. */
@Composable
private fun PhaseLineMobile(phase: BingeCatImportPhase?) {
    if (phase == null) return
    when (phase) {
        BingeCatImportPhase.Importing -> Text(
            text = "Importing…",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )

        is BingeCatImportPhase.Done -> Text(
            text = phase.message,
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall
        )

        is BingeCatImportPhase.Failed -> Text(
            text = phase.message,
            color = KBTextHi,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** The cover collage: artwork only, never presented as a title list. */
@Composable
private fun PosterStripMobile(posters: List<String>) {
    val shown = posters.filter { it.isNotBlank() }.take(6)
    if (shown.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 42.dp, height = 62.dp)
            )
        }
    }
}

@Composable
private fun SectionHeaderMobile(title: String) {
    Text(
        text = title.uppercase(),
        color = KBAccent,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun PanelMobile(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapeCard)
            .padding(14.dp)
    ) {
        Text(
            text = title,
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun ReasonLineMobile(message: String) {
    Text(
        text = message,
        color = KBTextHi,
        style = MaterialTheme.typography.bodySmall
    )
}
