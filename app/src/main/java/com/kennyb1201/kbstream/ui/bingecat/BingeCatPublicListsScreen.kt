package com.kennyb1201.kbstream.ui.bingecat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.bingecat.BingeCatImportMode
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicCategory
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicListCard
import com.kennyb1201.kbstream.data.bingecat.BingeCatPublicListRules
import com.kennyb1201.kbstream.data.bingecat.BingeCatRow
import com.kennyb1201.kbstream.data.bingecat.BingeCatSearchType
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Settings → BingeCat → Browse public lists.
 *
 * The management surface the spec asks for: categories → (subcategories) → list
 * cards → list detail → import, with search reachable from the categories
 * screen. It is a Settings surface and nothing else - no Home rail is added, no
 * Home rail manager is touched, and a public list is never presented as
 * KBStream's own pick: every card and detail names its BingeCat owner.
 *
 * One composable for both form factors, like the connect page beside it: the
 * content is cards and one field, and a separate touch layout would be the same
 * content with different padding.
 */
@Composable
fun BingeCatPublicListsScreen(
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            KBSurface.copy(alpha = 0.92f),
                            KBVoid.copy(alpha = 0.98f),
                            KBVoid
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 1080.dp)
                    .focusGroup()
                    .background(KBSurface, KBShapePanel)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    KBPageTitle(
                        text = "BINGECAT PUBLIC LISTS",
                        color = KBAccent,
                        modifier = Modifier.weight(1f)
                    )
                    KBButton(label = "BACK") {
                        if (uiState.canGoBack) vm.back() else onBack()
                    }
                }

                // Attribution is the surface's standing sentence: these are
                // community lists, not KBStream's picks, on every level.
                Text(
                    text = "Community lists on BingeCat. Importing one copies it into your " +
                        "BingeCat profile's lists, where it shows up in Library like any list " +
                        "you made yourself - KBStream does not pick these titles.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )

                uiState.gate?.let { gate -> GatePanel(gate) }

                when (val level = uiState.level) {
                    BingeCatPublicLevel.Categories -> CategoriesBody(
                        categories = uiState.categories,
                        isLoading = uiState.isLoadingCategories,
                        error = uiState.categoriesError,
                        onOpenCategory = vm::openCategory,
                        onOpenSearch = vm::openSearch,
                        onRetry = vm::loadCategories
                    )

                    is BingeCatPublicLevel.Subcategories -> SubcategoriesBody(
                        level = level,
                        onOpenChild = { child -> vm.openSubcategory(level, child) }
                    )

                    is BingeCatPublicLevel.Lists -> ListsBody(
                        title = level.title,
                        cards = uiState.cards,
                        isLoading = uiState.isLoadingCards,
                        error = uiState.cardsError,
                        phases = uiState.importPhases,
                        onOpenCard = vm::openDetail,
                        onImport = vm::startImport
                    )

                    BingeCatPublicLevel.Search -> SearchBody(
                        query = uiState.searchQuery,
                        type = uiState.searchType,
                        results = uiState.searchResults,
                        isSearching = uiState.isSearching,
                        error = uiState.searchError,
                        phases = uiState.importPhases,
                        onQueryChange = vm::setSearchQuery,
                        onTypeChange = vm::setSearchType,
                        onSearch = vm::runSearch,
                        onOpenCard = vm::openDetail,
                        onImport = vm::startImport
                    )

                    is BingeCatPublicLevel.Detail -> DetailBody(
                        card = level.card,
                        titles = uiState.titles,
                        phase = uiState.importPhases[level.card.importId],
                        onOpenTitle = onOpenTitle,
                        onImport = { vm.startImport(level.card) }
                    )
                }

                // The mode confirmation. Inline rather than a dialog: the whole
                // surface is one focus group, and a remote can reach the modes
                // without a focus trap to escape from.
                uiState.importTarget?.let { target ->
                    ImportConfirmPanel(
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

/** The prompt each gate has, instead of a dead button. */
@Composable
private fun GatePanel(gate: BingeCatGate) {
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
    Panel(title = title, message = message)
}

/** The category tree: tiles that drill down, and the search entry beside them. */
@Composable
private fun CategoriesBody(
    categories: List<BingeCatPublicCategory>,
    isLoading: Boolean,
    error: String?,
    onOpenCategory: (BingeCatPublicCategory) -> Unit,
    onOpenSearch: () -> Unit,
    onRetry: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KBSectionHeader(title = "Categories")
        Spacer(modifier = Modifier.width(12.dp))
        KBButton(label = "SEARCH LISTS") { onOpenSearch() }
    }
    if (isLoading) {
        Text(
            text = "Reading BingeCat's categories…",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    error?.let { ReasonLine(it) }
    val drawable = categories.filter { BingeCatPublicListRules.isDrawable(it) }
    if (drawable.isEmpty()) {
        Text(
            text = if (error != null) {
                "No categories to show from the last read."
            } else {
                "BingeCat returned no categories."
            },
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        KBButton(label = "TRY AGAIN") { onRetry() }
        return
    }
    drawable.forEach { category ->
        CategoryTile(category = category) { onOpenCategory(category) }
    }
}

@Composable
private fun CategoryTile(category: BingeCatPublicCategory, onClick: () -> Unit) {
    val name = category.name?.takeIf { it.isNotBlank() } ?: return
    val count = BingeCatPublicListRules.categoryCountLabel(category.listCounts)
    val children = category.children.filter { BingeCatPublicListRules.isDrawable(it) }
    KBCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            val art = category.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(end = 14.dp)
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
            Text(
                text = if (children.isNotEmpty()) "OPEN ›" else "LISTS ›",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/** A category's children, read without a request: they came in the tree. */
@Composable
private fun SubcategoriesBody(
    level: BingeCatPublicLevel.Subcategories,
    onOpenChild: (BingeCatPublicCategory) -> Unit
) {
    KBSectionHeader(title = level.parentName)
    level.children.forEach { child ->
        CategoryTile(category = child) { onOpenChild(child) }
    }
}

/** One category's list cards, drawn the same way search results are. */
@Composable
private fun ListsBody(
    title: String,
    cards: List<BingeCatPublicListCard>,
    isLoading: Boolean,
    error: String?,
    phases: Map<String, BingeCatImportPhase>,
    onOpenCard: (BingeCatPublicListCard) -> Unit,
    onImport: (BingeCatPublicListCard) -> Unit
) {
    KBSectionHeader(title = title)
    if (isLoading) {
        Text(
            text = "Reading this category's lists…",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    error?.let { ReasonLine(it) }
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
        PublicListCardView(
            card = card,
            phase = phases[card.importId],
            onOpen = { onOpenCard(card) },
            onImport = { onImport(card) }
        )
    }
}

/** The search row, its type filter, and the cards a query returned. */
@Composable
private fun SearchBody(
    query: String,
    type: BingeCatSearchType,
    results: List<BingeCatPublicListCard>,
    isSearching: Boolean,
    error: String?,
    phases: Map<String, BingeCatImportPhase>,
    onQueryChange: (String) -> Unit,
    onTypeChange: (BingeCatSearchType) -> Unit,
    onSearch: () -> Unit,
    onOpenCard: (BingeCatPublicListCard) -> Unit,
    onImport: (BingeCatPublicListCard) -> Unit
) {
    KBSectionHeader(title = "Search public lists")
    Row(verticalAlignment = Alignment.CenterVertically) {
        KBTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "List name, theme, owner…",
            modifier = Modifier.weight(1f),
            onDone = onSearch
        )
        Spacer(modifier = Modifier.width(10.dp))
        KBButton(
            label = if (isSearching) "SEARCHING…" else "SEARCH",
            enabled = !isSearching && BingeCatPublicListRules.searchEnabled(query)
        ) { onSearch() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BingeCatSearchType.values().forEach { option ->
            KBButton(
                label = option.label,
                labelColor = if (option == type) KBAccent else null
            ) { onTypeChange(option) }
        }
    }
    if (!BingeCatPublicListRules.searchEnabled(query) && !isSearching) {
        Text(
            text = "Type at least two characters. Search finds BingeCat LISTS, never " +
                "individual titles.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    error?.let { ReasonLine(it) }
    if (!isSearching && error == null && results.isEmpty() &&
        BingeCatPublicListRules.searchEnabled(query)
    ) {
        Text(
            text = "No public lists match that search.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    results.forEach { card ->
        PublicListCardView(
            card = card,
            phase = phases[card.importId],
            onOpen = { onOpenCard(card) },
            onImport = { onImport(card) }
        )
    }
}

/**
 * One public-list card.
 *
 * The attribution line is not optional: a public list is a BingeCat community
 * (or official BingeCat) list with an owner, and the card says so before it
 * says anything about its counts.
 */
@Composable
private fun PublicListCardView(
    card: BingeCatPublicListCard,
    phase: BingeCatImportPhase?,
    onOpen: () -> Unit,
    onImport: () -> Unit
) {
    KBCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            PosterStrip(card.coverPosters)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (card.coverPosters.isEmpty()) 0.dp else 12.dp)
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
                card.description?.let { description ->
                    Text(
                        text = description,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                PhaseLine(phase)
            }
            Column(horizontalAlignment = Alignment.End) {
                KBButton(label = "DETAILS") { onOpen() }
                Spacer(modifier = Modifier.height(6.dp))
                KBButton(
                    label = if (phase is BingeCatImportPhase.Importing) "IMPORTING…" else "IMPORT",
                    enabled = phase !is BingeCatImportPhase.Importing
                ) { onImport() }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** The detail level: metadata, attribution, the import action, and titles if any. */
@Composable
private fun DetailBody(
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
    PosterStrip(card.coverPosters)
    Row(verticalAlignment = Alignment.CenterVertically) {
        KBButton(
            label = "SAVE TO MY BINGECAT",
            enabled = phase !is BingeCatImportPhase.Importing
        ) { onImport() }
        Spacer(modifier = Modifier.width(12.dp))
        PhaseLine(phase)
    }
    KBSectionHeader(title = "Titles")

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

        is BingeCatTitlesState.Failed -> ReasonLine(titles.message)

        is BingeCatTitlesState.Titles -> {
            // Says the ceiling rule plainly: the lists are BingeCat's, and
            // KBStream (not BingeCat) is what keeps a title off a strict profile.
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
                    TitleRow(row) { onOpenTitle(row.mediaType, detailId) }
                }
            }
        }
    }
}

/** The id a Detail screen takes: IMDb first, then the typed TMDB form. */
private fun BingeCatRow.detailId(): String? =
    imdbId?.takeIf { it.isNotBlank() } ?: tmdbId?.let { "tmdb:$it" }

@Composable
private fun TitleRow(row: BingeCatRow, onClick: () -> Unit) {
    KBCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = row.title,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
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
    }
}

/**
 * The import confirmation: the mode, what it will do, and the two exits.
 *
 * The offered modes come from the list's own counts and content type (see
 * [BingeCatPublicListRules.modesFor]) - `anime` is HIDDEN for a list that is
 * not anime, never shown disabled without an explanation.
 */
@Composable
private fun ImportConfirmPanel(
    card: BingeCatPublicListCard,
    modes: List<BingeCatImportMode>,
    selected: BingeCatImportMode,
    onSelect: (BingeCatImportMode) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Panel(
        title = "Import “${card.name}”",
        message = BingeCatPublicListRules.attribution(
            owner = card.owner,
            isOfficial = card.isOfficial,
            provider = card.provider
        ) + " · one list per import, so every result belongs to this list."
    )
    modes.forEach { mode ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            KBButton(
                label = BingeCatPublicListRules.modeLabel(mode),
                labelColor = if (mode == selected) KBAccent else null
            ) { onSelect(mode) }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = BingeCatPublicListRules.modeDescription(mode),
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KBButton(label = "IMPORT") { onConfirm() }
        KBButton(label = "CANCEL") { onCancel() }
    }
}

/** The card's (and the detail's) import line, when it has one. */
@Composable
private fun PhaseLine(phase: BingeCatImportPhase?) {
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
private fun PosterStrip(posters: List<String>) {
    val shown = posters.filter { it.isNotBlank() }.take(6)
    if (shown.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 46.dp, height = 68.dp)
            )
        }
    }
}

@Composable
private fun Panel(title: String, message: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
            modifier = Modifier.padding(top = 3.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun ReasonLine(message: String) {
    Text(
        text = message,
        color = KBTextHi,
        style = MaterialTheme.typography.bodySmall
    )
}
