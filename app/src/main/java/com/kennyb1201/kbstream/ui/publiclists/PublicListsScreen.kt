package com.kennyb1201.kbstream.ui.publiclists

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListRules
import com.kennyb1201.kbstream.data.publiclists.PublicListTitle
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor
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
 * Settings → Trakt → Browse public lists, and Settings → MDBList → the same.
 *
 * One surface for both vendors (spec: "in the exact BingeCat shape"): tabs of
 * directories → list cards → list detail with its titles → Save, with search
 * reachable from the tab row. It is a Settings surface and nothing else - no
 * Home rail is added, no Home rail manager is touched, and no list is ever
 * presented as KBStream's own pick: every card and detail names the vendor and,
 * for a community list, its owner.
 *
 * A separate touch twin exists
 * ([com.kennyb1201.kbstream.ui.mobile.PublicListsScreenMobile]) for the reason
 * the BingeCat browser has one: the cards and buttons here answer the D-pad, so
 * a finger on a phone would find nothing to press.
 *
 * The vendor comes from the route rather than the ViewModel's constructor, so
 * the activity-scoped ViewModel can be shared: opening the other vendor
 * re-derives its state instead of adding a second instance.
 */
@Composable
fun PublicListsScreen(
    vendor: PublicListVendor,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenTitle: (mediaType: String, id: String) -> Unit,
    vm: PublicListsViewModel = viewModel()
) {
    LaunchedEffect(vendor) { vm.open(vendor) }

    // The phone entry: the same ViewModel and level stack, laid out for touch.
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        com.kennyb1201.kbstream.ui.mobile.PublicListsScreenMobile(
            vendor = vendor,
            modifier = modifier,
            onBack = onBack,
            onOpenTitle = onOpenTitle,
            vm = vm
        )
        return
    }

    val uiState by vm.uiState.collectAsStateWithLifecycle()

    // Back walks the surface's own stack; at the root it leaves for Settings,
    // which is where the entry row sits.
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
                        text = uiState.vendor.browseTitle,
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
                    text = uiState.vendor.attributionRule,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )

                uiState.gate?.let { gate -> GatePanel(gate, uiState.vendor) }

                // The two directory vendors keep their tabs and search; an anime
                // vendor has neither (there is no trending-lists directory for
                // anime), so its root is the username box below.
                if (!uiState.vendor.byUsername) {
                    DirectoryTabs(
                        state = uiState,
                        onSelect = vm::selectDirectory,
                        onOpenSearch = vm::openSearch
                    )
                }

                when (val level = uiState.level) {
                    PublicListLevel.Username -> UsernameBody(
                        state = uiState,
                        onQueryChange = vm::setUsernameQuery,
                        onRead = vm::readUsername,
                        onOpenCard = vm::openDetail,
                        onSave = vm::save
                    )

                    is PublicListLevel.Directory -> DirectoryBody(
                        cards = uiState.cards,
                        isLoading = uiState.isLoadingCards,
                        error = uiState.cardsError,
                        vendor = uiState.vendor,
                        savePhases = uiState.savePhases,
                        onOpenCard = vm::openDetail,
                        onSave = vm::save
                    )

                    PublicListLevel.Search -> SearchBody(
                        state = uiState,
                        onQueryChange = vm::setSearchQuery,
                        onSearch = vm::runSearch,
                        onOpenCard = vm::openDetail,
                        onSave = vm::save
                    )

                    is PublicListLevel.Detail -> DetailBody(
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

/** The vendor's directories as tabs, plus the search entry. */
@Composable
private fun DirectoryTabs(
    state: PublicListsUiState,
    onSelect: (com.kennyb1201.kbstream.data.publiclists.PublicListDirectory) -> Unit,
    onOpenSearch: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.directories.forEach { directory ->
            KBButton(
                label = directory.label.uppercase(),
                labelColor = if (directory == state.directory) KBAccent else null
            ) { onSelect(directory) }
        }
        Spacer(modifier = Modifier.width(12.dp))
        KBButton(label = "SEARCH LISTS") { onOpenSearch() }
    }
}

/** The prompt each gate has, instead of a dead Save button. */
@Composable
private fun GatePanel(gate: PublicListGate, vendor: PublicListVendor) {
    val (title, message) = when (gate) {
        PublicListGate.NOT_CONNECTED ->
            "${vendor.label}${PublicListRules.notConnectedTitleSuffix}" to
                PublicListRules.notConnectedMessage(vendor)

        PublicListGate.ALLOWANCE_SPENT ->
            "${vendor.label}'s allowance is spent" to PublicListRules.allowanceText(vendor)
    }
    Panel(title = title, message = message)
}

/**
 * The anime vendors' browse level: a username box and that profile's lists.
 *
 * No tabs and no list-name search: an anime list is discovered by the profile
 * that keeps it. The box opens on the username this profile already reads its
 * own lists with, and the snapshot note under the cards is the one promise a
 * copy makes - it does not follow the owner's later edits.
 */
@Composable
private fun UsernameBody(
    state: PublicListsUiState,
    onQueryChange: (String) -> Unit,
    onRead: () -> Unit,
    onOpenCard: (PublicListCard) -> Unit,
    onSave: (PublicListCard) -> Unit
) {
    KBSectionHeader(title = "Read a ${state.vendor.label} profile")
    Row(verticalAlignment = Alignment.CenterVertically) {
        KBTextField(
            value = state.usernameQuery,
            onValueChange = onQueryChange,
            placeholder = PublicListRules.usernamePlaceholder(state.vendor),
            modifier = Modifier.weight(1f),
            onDone = onRead
        )
        Spacer(modifier = Modifier.width(10.dp))
        KBButton(
            label = if (state.isReadingUsername) "READING…" else "READ LISTS",
            enabled = !state.isReadingUsername &&
                PublicListRules.usernameEnabled(state.usernameQuery)
        ) { onRead() }
    }
    if (!state.vendor.needsConnection) {
        Text(
            text = "Public profiles need no account of yours: type any username. A " +
                "private profile answers with a prompt, never an empty list.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    state.usernameError?.let { ReasonLine(it) }
    if (!state.isReadingUsername && state.usernameError == null &&
        state.usernameLists.isEmpty() && state.username != null
    ) {
        Text(
            text = PublicListRules.emptyUsernameText(
                state.vendor,
                state.username.orEmpty()
            ),
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (state.usernameLists.isNotEmpty()) {
        Text(
            text = PublicListRules.copySnapshotNote(state.vendor),
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )
    }
    state.usernameLists.forEach { card ->
        PublicListCardView(
            card = card,
            phase = state.savePhases[card.key],
            onOpen = { onOpenCard(card) },
            onSave = { onSave(card) }
        )
    }
}

/** One directory's cards. */
@Composable
private fun DirectoryBody(
    cards: List<PublicListCard>,
    isLoading: Boolean,
    error: String?,
    vendor: PublicListVendor,
    savePhases: Map<String, PublicListSavePhase>,
    onOpenCard: (PublicListCard) -> Unit,
    onSave: (PublicListCard) -> Unit
) {
    if (isLoading) {
        Text(
            text = "Reading ${vendor.label}'s lists…",
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
                PublicListRules.emptyDirectoryText(vendor)
            },
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    cards.forEach { card ->
        PublicListCardView(
            card = card,
            phase = savePhases[card.key],
            onOpen = { onOpenCard(card) },
            onSave = { onSave(card) }
        )
    }
}

/** The search row and the cards a query returned. */
@Composable
private fun SearchBody(
    state: PublicListsUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenCard: (PublicListCard) -> Unit,
    onSave: (PublicListCard) -> Unit
) {
    KBSectionHeader(title = "Search public lists")
    Row(verticalAlignment = Alignment.CenterVertically) {
        KBTextField(
            value = state.searchQuery,
            onValueChange = onQueryChange,
            placeholder = "List name, theme, owner…",
            modifier = Modifier.weight(1f),
            onDone = onSearch
        )
        Spacer(modifier = Modifier.width(10.dp))
        KBButton(
            label = if (state.isSearching) "SEARCHING…" else "SEARCH",
            enabled = !state.isSearching && PublicListRules.searchEnabled(state.searchQuery)
        ) { onSearch() }
    }
    if (!PublicListRules.searchEnabled(state.searchQuery) && !state.isSearching) {
        Text(
            text = "Type at least two characters. Search finds LISTS, never individual " +
                "titles.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    state.searchError?.let { ReasonLine(it) }
    if (!state.isSearching && state.searchError == null && state.searchResults.isEmpty() &&
        PublicListRules.searchEnabled(state.searchQuery)
    ) {
        Text(
            text = PublicListRules.NO_SEARCH_RESULTS,
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
    }
    state.searchResults.forEach { card ->
        PublicListCardView(
            card = card,
            phase = state.savePhases[card.key],
            onOpen = { onOpenCard(card) },
            onSave = { onSave(card) }
        )
    }
}

/**
 * One public-list card.
 *
 * The attribution line is not optional: a community list has an owner and the
 * card says so before it says anything about its counts (spec F5).
 */
@Composable
private fun PublicListCardView(
    card: PublicListCard,
    phase: PublicListSavePhase?,
    onOpen: () -> Unit,
    onSave: () -> Unit
) {
    KBCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
                PhaseLine(phase)
            }
            Column(horizontalAlignment = Alignment.End) {
                KBButton(label = "DETAILS") { onOpen() }
                Spacer(modifier = Modifier.height(6.dp))
                KBButton(
                    label = saveLabel(phase, card.liked, card.vendor),
                    enabled = phase !is PublicListSavePhase.Saving && card.liked != true
                ) { onSave() }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** The detail level: attribution, the save action, and the list's titles. */
@Composable
private fun DetailBody(
    card: PublicListCard,
    titles: PublicListTitlesState,
    phase: PublicListSavePhase?,
    onOpenTitle: (mediaType: String, id: String) -> Unit,
    onSave: () -> Unit
) {
    Text(
        text = card.name,
        color = KBTextHi,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = PublicListRules.attribution(card),
        color = KBTextLo,
        style = MaterialTheme.typography.bodySmall
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
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        KBButton(
            label = saveLabel(phase, card.liked, card.vendor),
            enabled = phase !is PublicListSavePhase.Saving && card.liked != true
        ) { onSave() }
        Spacer(modifier = Modifier.width(12.dp))
        PhaseLine(phase)
    }
    if (card.vendor.byUsername) {
        Text(
            text = PublicListRules.copySnapshotNote(card.vendor),
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )
    }
    KBSectionHeader(title = "Titles")

    when (titles) {
        PublicListTitlesState.Loading -> Text(
            text = "Reading this list's titles…",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )

        is PublicListTitlesState.Failed -> ReasonLine(titles.message)

        is PublicListTitlesState.Titles -> {
            // Says the ceiling rule plainly: the list is the provider's, and
            // KBStream (not the provider) is what keeps a title off a strict
            // profile.
            Text(
                text = PublicListRules.CEILING_NOTE,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
            // F6: the header counts the PROVIDER's titles, so a title with no
            // id - skipped below because there is nothing to open - has to be
            // accounted for rather than quietly missing from the rows.
            if (titles.droppedCount > 0) {
                Text(
                    text = publicListDroppedTitlesNote(titles.droppedCount),
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (titles.rows.isEmpty()) {
                Text(
                    text = "This list has no titles ${card.vendor.label} can show.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            titles.rows.forEach { row ->
                val detailId = row.detailId
                if (detailId != null) {
                    TitleRow(row) { onOpenTitle(if (row.isMovie) "movie" else "series", detailId) }
                }
            }
        }
    }
}

/**
 * The line a list's detail shows when some of its titles could not be listed.
 *
 * A row with neither an IMDb nor a TMDB id has nothing for the Detail screen to
 * open, so it is skipped. The header keeps the provider's own count, which is
 * why this exists: it reconciles the two without pretending the list is
 * shorter than the provider says.
 */
internal fun publicListDroppedTitlesNote(count: Int): String =
    if (count == 1) {
        "1 title couldn't be shown - it has no ID."
    } else {
        "$count titles couldn't be shown - they have no IDs."
    }

@Composable
private fun TitleRow(row: PublicListTitle, onClick: () -> Unit) {
    KBCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
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
        }
    }
}

/**
 * SAVE / SAVING… / SAVED for a directory vendor; COPY / SAVING… / COPIED for an
 * anime one, because that leg writes a local copy rather than a like.
 */
private fun saveLabel(
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

/** The card's (and the detail's) save line, when it has one. */
@Composable
private fun PhaseLine(phase: PublicListSavePhase?) {
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
