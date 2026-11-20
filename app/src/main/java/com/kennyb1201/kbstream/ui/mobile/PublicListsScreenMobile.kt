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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListDirectory
import com.kennyb1201.kbstream.data.publiclists.PublicListRules
import com.kennyb1201.kbstream.data.publiclists.PublicListTitle
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.TouchChoiceChip
import com.kennyb1201.kbstream.ui.publiclists.PublicListGate
import com.kennyb1201.kbstream.ui.publiclists.PublicListLevel
import com.kennyb1201.kbstream.ui.publiclists.PublicListSavePhase
import com.kennyb1201.kbstream.ui.publiclists.PublicListTitlesState
import com.kennyb1201.kbstream.ui.publiclists.PublicListsUiState
import com.kennyb1201.kbstream.ui.publiclists.PublicListsViewModel
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * Public lists for a finger: the same ViewModel and level stack as
 * [com.kennyb1201.kbstream.ui.publiclists.PublicListsScreen].
 *
 * Laid out differently rather than merely re-skinned, for the reason the
 * BingeCat browser's twin exists: the TV cards and buttons answer the D-pad
 * only. Here a card's actions sit UNDER its copy, because a 360dp phone has no
 * room for four lines of attribution and two buttons in one row, and the search
 * field takes the full width with its button beneath it.
 *
 * Rendering: one [LazyColumn] for the whole page, with the cards and title rows
 * as `items(...)` - the same conversion the TV twin takes, and for the same
 * reason (the Trakt directory is up to 300 cards, and composing all of them in
 * one pass is what a low-memory device cannot afford). Nothing about the touch
 * flow changes: same levels, same buttons, same scroll direction.
 */
@Composable
fun PublicListsScreenMobile(
    vendor: PublicListVendor,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenTitle: (mediaType: String, id: String) -> Unit,
    vm: PublicListsViewModel
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    BackHandler {
        if (uiState.canGoBack) vm.back() else onBack()
    }

    val listState = rememberLazyListState()

    // A new level starts at the top, exactly as on the TV twin.
    LaunchedEffect(uiState.level) {
        listState.scrollToItem(0)
    }

    MobileTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .background(KBSurface, KBShapePanel)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            MobileBackButton(
                                onBack = { if (uiState.canGoBack) vm.back() else onBack() }
                            )
                            Text(
                                text = uiState.vendor.browseTitle,
                                color = KBAccent,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    item {
                        Text(
                            text = uiState.vendor.attributionRule,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    uiState.gate?.let { gate ->
                        item { GatePanelMobile(gate, uiState.vendor) }
                    }

                    // No tabs or list-name search on an anime vendor: it is
                    // browsed by username, and its root level is that box.
                    if (!uiState.vendor.byUsername) {
                        item {
                            DirectoryTabsMobile(
                                state = uiState,
                                onSelect = vm::selectDirectory,
                                onOpenSearch = vm::openSearch
                            )
                        }
                    }

                    when (val level = uiState.level) {
                        PublicListLevel.Username -> usernameBodyItemsMobile(
                            state = uiState,
                            onQueryChange = vm::setUsernameQuery,
                            onRead = vm::readUsername,
                            onOpenCard = vm::openDetail,
                            onSave = vm::save
                        )

                        is PublicListLevel.Directory -> directoryBodyItemsMobile(
                            state = uiState,
                            onOpenCard = vm::openDetail,
                            onSave = vm::save
                        )

                        PublicListLevel.Search -> searchBodyItemsMobile(
                            state = uiState,
                            onQueryChange = vm::setSearchQuery,
                            onSearch = vm::runSearch,
                            onOpenCard = vm::openDetail,
                            onSave = vm::save
                        )

                        is PublicListLevel.Detail -> detailBodyItemsMobile(
                            card = level.card,
                            titles = uiState.titles,
                            phase = uiState.savePhases[level.card.key],
                            onOpenTitle = onOpenTitle,
                            onSave = { vm.save(level.card) }
                        )
                    }
                }
            }
        }
    }
}

/** The vendor's directories as chips, plus the search entry. */
@Composable
private fun DirectoryTabsMobile(
    state: PublicListsUiState,
    onSelect: (PublicListDirectory) -> Unit,
    onOpenSearch: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.directories.forEach { directory ->
                TouchChoiceChip(
                    label = directory.label,
                    selected = directory == state.directory,
                    onClick = { onSelect(directory) }
                )
            }
        }
        KBButton(
            label = "SEARCH LISTS",
            modifier = Modifier.fillMaxWidth()
        ) { onOpenSearch() }
    }
}

/** The prompt each gate has, instead of a dead Save button. */
@Composable
private fun GatePanelMobile(gate: PublicListGate, vendor: PublicListVendor) {
    val (title, message) = when (gate) {
        PublicListGate.NOT_CONNECTED ->
            "${vendor.label}${PublicListRules.notConnectedTitleSuffix}" to
                PublicListRules.notConnectedMessage(vendor)

        PublicListGate.ALLOWANCE_SPENT ->
            "${vendor.label}'s allowance is spent" to PublicListRules.allowanceText(vendor)
    }
    PanelMobile(title = title, message = message)
}

/**
 * The anime vendors' browse level for a finger: username box, then that
 * profile's lists. The copy note is the same promise the TV draws.
 */
private fun LazyListScope.usernameBodyItemsMobile(
    state: PublicListsUiState,
    onQueryChange: (String) -> Unit,
    onRead: () -> Unit,
    onOpenCard: (PublicListCard) -> Unit,
    onSave: (PublicListCard) -> Unit
) {
    item {
        Text(
            text = "Read a ${state.vendor.label} profile",
            color = KBTextHi,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
    item {
        KBTextField(
            value = state.usernameQuery,
            onValueChange = onQueryChange,
            placeholder = PublicListRules.usernamePlaceholder(state.vendor),
            modifier = Modifier.fillMaxWidth(),
            onDone = onRead
        )
    }
    item {
        KBButton(
            label = if (state.isReadingUsername) "READING…" else "READ LISTS",
            enabled = !state.isReadingUsername &&
                PublicListRules.usernameEnabled(state.usernameQuery),
            modifier = Modifier.fillMaxWidth()
        ) { onRead() }
    }
    if (!state.vendor.needsConnection) {
        item {
            Text(
                text = "Public profiles need no account of yours: type any username. A " +
                    "private profile answers with a prompt, never an empty list.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    state.usernameError?.let { message ->
        item { ReasonLineMobile(message) }
    }
    if (!state.isReadingUsername && state.usernameError == null &&
        state.usernameLists.isEmpty() && state.username != null
    ) {
        item {
            Text(
                text = PublicListRules.emptyUsernameText(
                    state.vendor,
                    state.username.orEmpty()
                ),
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (state.usernameLists.isNotEmpty()) {
        item {
            Text(
                text = PublicListRules.copySnapshotNote(state.vendor),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
    items(state.usernameLists, key = { it.key }) { card ->
        PublicListCardMobile(
            card = card,
            phase = state.savePhases[card.key],
            onOpen = { onOpenCard(card) },
            onSave = { onSave(card) }
        )
    }
}

private fun LazyListScope.directoryBodyItemsMobile(
    state: PublicListsUiState,
    onOpenCard: (PublicListCard) -> Unit,
    onSave: (PublicListCard) -> Unit
) {
    if (state.isLoadingCards) {
        item {
            Text(
                text = "Reading ${state.vendor.label}'s lists…",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }
    state.cardsError?.let { message ->
        item { ReasonLineMobile(message) }
    }
    if (state.cards.isEmpty()) {
        item {
            Text(
                text = if (state.cardsError != null) {
                    "Nothing to show from the last read."
                } else {
                    PublicListRules.emptyDirectoryText(state.vendor)
                },
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }
    // Lazy: a Trakt directory is up to 300 cards, and only the visible ones are
    // composed now.
    items(state.cards, key = { it.key }) { card ->
        PublicListCardMobile(
            card = card,
            phase = state.savePhases[card.key],
            onOpen = { onOpenCard(card) },
            onSave = { onSave(card) }
        )
    }
}

private fun LazyListScope.searchBodyItemsMobile(
    state: PublicListsUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenCard: (PublicListCard) -> Unit,
    onSave: (PublicListCard) -> Unit
) {
    item {
        Text(
            text = "Search public lists",
            color = KBTextHi,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
    item {
        KBTextField(
            value = state.searchQuery,
            onValueChange = onQueryChange,
            placeholder = "List name, theme, owner…",
            modifier = Modifier.fillMaxWidth(),
            onDone = onSearch
        )
    }
    item {
        KBButton(
            label = if (state.isSearching) "SEARCHING…" else "SEARCH",
            enabled = !state.isSearching && PublicListRules.searchEnabled(state.searchQuery),
            modifier = Modifier.fillMaxWidth()
        ) { onSearch() }
    }
    if (!PublicListRules.searchEnabled(state.searchQuery) && !state.isSearching) {
        item {
            Text(
                text = "Type at least two characters. Search finds LISTS, never individual " +
                    "titles.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    state.searchError?.let { message ->
        item { ReasonLineMobile(message) }
    }
    if (!state.isSearching && state.searchError == null && state.searchResults.isEmpty() &&
        PublicListRules.searchEnabled(state.searchQuery)
    ) {
        item {
            Text(
                text = PublicListRules.NO_SEARCH_RESULTS,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    items(state.searchResults, key = { it.key }) { card ->
        PublicListCardMobile(
            card = card,
            phase = state.savePhases[card.key],
            onOpen = { onOpenCard(card) },
            onSave = { onSave(card) }
        )
    }
}

/**
 * One card, actions beneath the copy.
 *
 * The attribution line is not optional: a community list has an owner and the
 * card says so before it says anything about its counts (spec F5).
 */
@Composable
private fun PublicListCardMobile(
    card: PublicListCard,
    phase: PublicListSavePhase?,
    onOpen: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, KBShapePanel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (card.official) "${card.name} · OFFICIAL" else card.name,
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = PublicListRules.attribution(card),
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = PublicListRules.countsLabel(card.itemCount, card.likes),
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
        SavePhaseLineMobile(phase)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KBButton(label = "DETAILS", modifier = Modifier.weight(1f)) { onOpen() }
            KBButton(
                label = saveLabelMobile(phase, card.liked, card.vendor),
                enabled = phase !is PublicListSavePhase.Saving && card.liked != true,
                modifier = Modifier.weight(1f)
            ) { onSave() }
        }
    }
}

private fun LazyListScope.detailBodyItemsMobile(
    card: PublicListCard,
    titles: PublicListTitlesState,
    phase: PublicListSavePhase?,
    onOpenTitle: (mediaType: String, id: String) -> Unit,
    onSave: () -> Unit
) {
    item {
        Text(
            text = card.name,
            color = KBTextHi,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
    item {
        Text(
            text = PublicListRules.attribution(card),
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    item {
        Text(
            text = PublicListRules.countsLabel(card.itemCount, card.likes),
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall
        )
    }
    card.description?.let { description ->
        item {
            Text(
                text = description,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    item {
        KBButton(
            label = saveLabelMobile(phase, card.liked, card.vendor),
            enabled = phase !is PublicListSavePhase.Saving && card.liked != true,
            modifier = Modifier.fillMaxWidth()
        ) { onSave() }
    }
    item { SavePhaseLineMobile(phase) }
    if (card.vendor.byUsername) {
        item {
            Text(
                text = PublicListRules.copySnapshotNote(card.vendor),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    item {
        Text(
            text = "Titles",
            color = KBTextHi,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
    when (titles) {
        PublicListTitlesState.Loading -> item {
            Text(
                text = "Reading this list's titles…",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }

        is PublicListTitlesState.Failed -> item { ReasonLineMobile(titles.message) }

        is PublicListTitlesState.Titles -> {
            item {
                Text(
                    text = PublicListRules.CEILING_NOTE,
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (titles.rows.isEmpty()) {
                item {
                    Text(
                        text = "This list has no titles ${card.vendor.label} can show.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            // Lazy, and keyed by position: the detail read is uncapped, and a
            // provider's list may name the same title twice - duplicate keys
            // would fail the list outright.
            val openable = titles.rows.mapNotNull { row ->
                row.detailId?.let { detailId -> row to detailId }
            }
            items(openable.size) { index ->
                val (row, detailId) = openable[index]
                TitleRowMobile(row) {
                    onOpenTitle(if (row.isMovie) "movie" else "series", detailId)
                }
            }
        }
    }
}

@Composable
private fun TitleRowMobile(row: PublicListTitle, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, KBShapePanel)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 6.dp)
    ) {
        Text(
            text = row.title.orEmpty(),
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
        KBButton(label = "OPEN") { onClick() }
    }
}

/** The phone twin of the TV's label rule (see PublicListsScreen.saveLabel). */
private fun saveLabelMobile(
    phase: PublicListSavePhase?,
    liked: Boolean?,
    vendor: PublicListVendor
): String = when {
    phase is PublicListSavePhase.Saving -> "SAVING…"
    phase is PublicListSavePhase.Done -> if (vendor.byUsername) "COPIED" else "SAVED"
    liked == true -> if (vendor.byUsername) "COPIED" else "SAVED"
    vendor.byUsername -> "COPY"
    else -> "SAVE"
}

@Composable
private fun SavePhaseLineMobile(phase: PublicListSavePhase?) {
    if (phase == null) return
    when (phase) {
        PublicListSavePhase.Saving -> Text(
            text = "Saving…",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )

        is PublicListSavePhase.Done -> Text(
            text = phase.message,
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall
        )

        is PublicListSavePhase.Failed -> Text(
            text = phase.message,
            color = KBTextHi,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun PanelMobile(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            color = KBTextHi,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
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
