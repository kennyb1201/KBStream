package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import com.kennyb1201.kbstream.ui.addons.AddonsViewModel
import com.kennyb1201.kbstream.ui.addons.HomeRailManagerRow
import com.kennyb1201.kbstream.ui.addons.homeRailManagerRows
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBPasteChip
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.TouchChoiceChip
import com.kennyb1201.kbstream.ui.home.BILLBOARD_DEFAULT_FEED_LABEL
import com.kennyb1201.kbstream.ui.home.billboardCycleLabel
import com.kennyb1201.kbstream.ui.home.billboardTrailerModeLabel
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/*
 * The Home rail manager, for a finger: one list of every rail Home draws, in
 * the merged order, with the same controls the TV dialog offers.
 *
 * The rows come from [homeRailManagerRows] - the same function the TV dialog
 * calls - so a phone manager lists exactly what a TV manager lists, in the same
 * order, with the same "would this press move it?" answers. What is gone is the
 * focus plumbing: there is no D-pad to restore, and a row that rebuilds after a
 * move simply re-renders where it landed.
 *
 * Actions per row: HIDE/SHOW, PIN (collections and browse rails), the same four
 * reorder stops the TV draws (very top, up, down, very bottom, disabled where a
 * press would change nothing), and RENAME for a built-in or a catalog. A hidden
 * rail shows only SHOW, as on TV.
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeManagerDialogMobile(
    configurations: List<CatalogConfiguration>,
    collectionsState: AddonsViewModel.CollectionUiState,
    homeOrderVersion: Int,
    kidsMaxAge: Int?,
    isGuest: Boolean,
    collectionUrlInput: String,
    onCollectionUrlChange: (String) -> Unit,
    onImportCollectionUrl: () -> Unit,
    onPickCollectionFile: () -> Unit,
    onRemoveCollectionProfile: (String) -> Unit,
    onToggle: (CatalogConfiguration, Boolean) -> Unit,
    onToggleAll: (Boolean) -> Unit,
    onCatalogMove: (CatalogConfiguration, Int) -> Unit,
    onCollectionPin: (String) -> Unit,
    onCollectionHide: (String) -> Unit,
    onCollectionMove: (String, Int) -> Unit,
    onBuiltinHide: (String) -> Unit,
    onBuiltinMove: (String, Int) -> Unit,
    onBuiltinRename: (String) -> Unit,
    onRename: (CatalogConfiguration) -> Unit,
    onDismiss: () -> Unit
) {
    // Same merge the TV dialog draws, from the same function.
    val rows = remember(configurations, collectionsState, homeOrderVersion) {
        homeRailManagerRows(
            configurations = configurations,
            collectionsState = collectionsState,
            kidsMaxAge = kidsMaxAge,
            isGuest = isGuest
        )
    }
    val visibleRows = rows.filter { !it.isHidden }
    val hiddenRows = rows.filter { it.isHidden }
    val allCatalogsVisible = configurations.all { it.catalog.showOnHome }

    // Collapsed on open, every time, first run included - the same rule as the
    // TV dialog: the arrangement owns the space and the URL field is one press
    // away behind IMPORT (field 2026-10-28).
    var importExpanded by remember { mutableStateOf(false) }
    var pendingRemoveUrl by remember { mutableStateOf<String?>(null) }

    fun move(row: HomeRailManagerRow, delta: Int) {
        when {
            row.isBuiltinRail -> onBuiltinMove(row.key, delta)
            row.isCollection -> onCollectionMove(row.key, delta)
            else -> row.config?.let { onCatalogMove(it, delta) }
        }
    }

    fun toggle(row: HomeRailManagerRow) {
        when {
            row.isBuiltinRail -> onBuiltinHide(row.key)
            row.isCollection -> onCollectionHide(row.collectionKey.orEmpty())
            else -> row.config?.let { onToggle(it, !it.catalog.showOnHome) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(
            title = "HOME MANAGER",
            subtitle = "Arrange the rails on Home. Changes are instant.",
            modifier = Modifier.fillMaxHeight(0.92f)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (configurations.isNotEmpty()) {
                    // The same label rule as the TV dialog: this press only ever
                    // touched add-on catalogs, so it says so instead of promising
                    // the whole rail list (see AddonsHomeManagerDialog).
                    KBButton(
                        label = if (allCatalogsVisible) {
                            "HIDE ALL CATALOGS"
                        } else {
                            "SHOW ALL CATALOGS"
                        },
                        onClick = { onToggleAll(!allCatalogsVisible) }
                    )
                }
                KBButton(
                    label = if (importExpanded) "CLOSE IMPORT" else "IMPORT",
                    onClick = { importExpanded = !importExpanded }
                )
                KBButton(label = "DONE", onClick = onDismiss)
            }

            if (importExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KBTextField(
                        value = collectionUrlInput,
                        onValueChange = onCollectionUrlChange,
                        placeholder = "https://…/kb-collections.json",
                        modifier = Modifier.fillMaxWidth(),
                        onDone = onImportCollectionUrl
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        KBPasteChip(onPaste = onCollectionUrlChange)
                        KBButton(label = "ADD", onClick = onImportCollectionUrl)
                        KBButton(label = "FILE", onClick = onPickCollectionFile)
                    }
                    if (collectionsState.profileUrls.isNotEmpty()) {
                        Text(
                            text = "Imported sources — tap one to remove it",
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelSmall
                        )
                        collectionsState.profileUrls.forEach { url ->
                            val isFile = url.startsWith("local:")
                            KBButton(
                                label = (if (isFile) "FILE · " else "") +
                                    url.substringAfterLast('/').ifBlank { url },
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { pendingRemoveUrl = url }
                            )
                        }
                    }
                }
            }

            // Import feedback never hides behind the section that produced it.
            collectionsState.statusMessage?.let { message ->
                Text(
                    text = message,
                    color = if (message.startsWith("Import failed")) KBTextLo else KBAccent,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Text(
                text = buildString {
                    append("ON HOME — ${visibleRows.size} rails")
                    if (hiddenRows.isNotEmpty()) {
                        append(" · ${hiddenRows.size} hidden")
                    }
                },
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                item(key = "billboard") {
                    BillboardSectionMobile(rows = rows)
                }

                itemsIndexed(items = visibleRows, key = { _, row -> row.key }) { index, row ->
                    ManagerRowMobile(
                        row = row,
                        position = index,
                        onToggle = { toggle(row) },
                        onPin = {
                            if (row.isCollection) {
                                onCollectionPin(row.collectionKey.orEmpty())
                            }
                        },
                        onMove = { delta -> move(row, delta) },
                        onRename = {
                            when {
                                row.isBuiltinRail -> onBuiltinRename(row.key)
                                !row.isCollection -> row.config?.let { onRename(it) }
                            }
                        }
                    )
                }

                if (hiddenRows.isNotEmpty()) {
                    item(key = "hidden_header") {
                        Text(
                            text = "HIDDEN FROM HOME — press SHOW to bring one back",
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    items(
                        items = hiddenRows,
                        key = { row -> "hidden:${row.key}" }
                    ) { row ->
                        ManagerRowMobile(
                            row = row,
                            position = -1,
                            isHiddenSection = true,
                            onToggle = { toggle(row) },
                            onPin = {},
                            onMove = {},
                            onRename = {}
                        )
                    }
                }
            }
        }
    }

    pendingRemoveUrl?.let { url ->
        ConfirmRemoveCollectionDialogMobile(
            url = url,
            onDismiss = { pendingRemoveUrl = null },
            onConfirm = {
                pendingRemoveUrl = null
                onRemoveCollectionProfile(url)
            }
        )
    }
}

/**
 * The billboard's own controls, at the top of the Home manager.
 *
 * The billboard is a phone-only surface, so its four prefs are DEVICE-LOCAL
 * (see AppPreferences' billboard section and PrefsPayloadBuilder's exclusion
 * list) and are written straight from here rather than through the manager's
 * rail callbacks - none of them arranges a rail. Every write ticks the display
 * revision, which is how the open Home behind this dialog repaints.
 *
 * Deliberately no delay and no mute control: those are the existing hero-trailer
 * settings in Settings, and the trailer modes below reuse them rather than
 * growing a second pair that could disagree.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillboardSectionMobile(rows: List<HomeRailManagerRow>) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppPreferences.getHomeBillboardEnabled(context)) }
    var feeds by remember { mutableStateOf(AppPreferences.getHomeBillboardFeeds(context)) }
    var cycleMs by remember {
        mutableLongStateOf(AppPreferences.getHomeBillboardCycleMs(context))
    }
    var trailerMode by remember {
        mutableStateOf(AppPreferences.getHomeBillboardTrailerMode(context))
    }

    // Collapsed on open, every time, first run included - the rule the IMPORT
    // block above already follows. The chip list is the long thing in this
    // section (one chip per catalog), and expanded it pushed HOLD and TRAILER
    // below the fold, so much so that the billboard's own settings were
    // reported missing (field 2026-10-30). Dialog-local, not a pref.
    var feedsExpanded by remember { mutableStateOf(false) }

    /**
     * Writes a selection, with the picker's one invariant: it never represents
     * an EMPTY feed, so deselecting everything restores the default.
     */
    fun writeFeeds(next: List<String>) {
        val normalized = next.ifEmpty { AppPreferences.DEFAULT_BILLBOARD_FEEDS }
        feeds = normalized
        AppPreferences.setHomeBillboardFeeds(context, normalized)
    }

    fun toggleFeed(key: String) {
        writeFeeds(if (key in feeds) feeds - key else feeds + key)
    }

    // The catalogs this manager lists that can FEED the billboard: the built-ins
    // it draws itself, the add-on catalogs and the built catalogs. Collections
    // and browse rows are left out because they carry no catalog items - a
    // collection is a shelf of folders and a browse row is a set of shortcuts,
    // so neither can name a title to put in the hero.
    val feedRows = remember(rows) {
        rows
            .filter { row -> row.config != null || row.isBuiltinRail }
            .distinctBy { it.key }
    }

    // What a collapsed FEED says about itself, in the same short names its
    // chips carry, so a single pick is named rather than counted.
    val feedTitles = remember(feedRows) {
        feedRows.associate { row -> row.key to shortFeedLabel(row.title) }
    }
    val feedSummary = billboardFeedSummary(feeds = feeds, feedTitles = feedTitles)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapeCard)
            .background(KBSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "BILLBOARD",
            color = KBTextHi,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "The cycling hero at the top of Home: one page per title, " +
                "with Play and Details.",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TouchChoiceChip(
                label = "On",
                selected = enabled,
                onClick = {
                    enabled = true
                    AppPreferences.setHomeBillboardEnabled(context, true)
                }
            )
            TouchChoiceChip(
                label = "Off",
                selected = !enabled,
                onClick = {
                    enabled = false
                    AppPreferences.setHomeBillboardEnabled(context, false)
                }
            )
        }

        // The header stays, the chips fold away behind it: the summary above
        // says what is merged, and HOLD and TRAILER are the next thing down
        // instead of the far side of the catalog list.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                RowLabelMobile(feedSummary)
                Text(
                    text = "Pick several to merge them, in this order",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            ManagerActionMobile(
                label = if (feedsExpanded) "CLOSE FEEDS" else "PICK FEEDS",
                onClick = { feedsExpanded = !feedsExpanded }
            )
        }
        if (feedsExpanded) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TouchChoiceChip(
                    label = orderLabel(
                        feeds.indexOf(AppPreferences.BILLBOARD_TRENDING_DAY_FEED),
                        BILLBOARD_DEFAULT_FEED_LABEL
                    ),
                    selected = AppPreferences.BILLBOARD_TRENDING_DAY_FEED in feeds,
                    onClick = { toggleFeed(AppPreferences.BILLBOARD_TRENDING_DAY_FEED) }
                )
                feedRows.forEach { row ->
                    TouchChoiceChip(
                        label = orderLabel(feeds.indexOf(row.key), shortFeedLabel(row.title)),
                        selected = row.key in feeds,
                        onClick = { toggleFeed(row.key) }
                    )
                }
            }
        }

        RowLabelMobile("HOLD — how long each page stays up")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppPreferences.BILLBOARD_CYCLE_OPTIONS_MS.forEach { option ->
                TouchChoiceChip(
                    label = billboardCycleLabel(option),
                    selected = cycleMs == option,
                    onClick = {
                        cycleMs = option
                        AppPreferences.setHomeBillboardCycleMs(context, option)
                    }
                )
            }
        }

        RowLabelMobile(
            "TRAILER — auto-play waits the hero-trailer delay; mute is in Settings"
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BillboardTrailerMode.entries.forEach { mode ->
                TouchChoiceChip(
                    label = billboardTrailerModeLabel(mode),
                    selected = trailerMode == mode,
                    onClick = {
                        trailerMode = mode
                        AppPreferences.setHomeBillboardTrailerMode(context, mode)
                    }
                )
            }
        }
    }
}

/**
 * The FEED block's state in one line, so a collapsed section still says what is
 * merged without being expanded: several picks are counted, a single pick is
 * named - the trending-day default the way its chip names it, any other feed
 * through its own short chip name, and "1 selected" for a key this manager no
 * longer lists (a feed whose catalog was removed).
 */
internal fun billboardFeedSummary(feeds: List<String>, feedTitles: Map<String, String>): String {
    if (feeds.size != 1) return "FEED — ${feeds.size} selected"
    val only = feeds.first()
    val title = if (only == AppPreferences.BILLBOARD_TRENDING_DAY_FEED) {
        BILLBOARD_DEFAULT_FEED_LABEL
    } else {
        feedTitles[only]
    }
    return "FEED — ${title ?: "1 selected"}"
}

/** A small caption above one row of billboard chips. */
@Composable
private fun RowLabelMobile(text: String) {
    Text(
        text = text,
        color = KBTextLo,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold
    )
}

/**
 * A feed chip's label: the merge position when it is picked, so the order the
 * items will be concatenated in is visible (and changeable - deselect and
 * re-pick to move a feed later).
 */
private fun orderLabel(index: Int, title: String): String =
    if (index < 0) title else "${index + 1} · $title"

/**
 * A rail's name at chip width: long catalog names are cut here rather than
 * pushing the chip past the dialog's edge (the chip does not wrap).
 */
private fun shortFeedLabel(title: String): String =
    if (title.length <= 24) title else title.take(23).trimEnd() + "…"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManagerRowMobile(
    row: HomeRailManagerRow,
    position: Int,
    isHiddenSection: Boolean = false,
    onToggle: () -> Unit,
    onPin: () -> Unit,
    onMove: (Int) -> Unit,
    onRename: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapeCard)
            .background(KBSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (position >= 0) (position + 1).toString() else "—",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(22.dp)
            )
            RailKindChipMobile(
                isCollection = row.isCollection,
                isBrowseRow = row.isBrowseRail,
                isBuiltinRow = row.isBuiltinRail,
                isCustomCatalogRow = row.isCustomCatalog
            )
            if (row.isPinned) {
                Text(
                    text = "PINNED",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .background(KBAccent.copy(alpha = 0.16f), KBShapeChip)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Text(
                text = row.title,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 6.dp)
            )
            ManagerActionMobile(
                label = if (isHiddenSection) "SHOW" else "HIDE",
                accent = isHiddenSection,
                onClick = onToggle
            )
        }

        Text(
            text = row.subtitle,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 22.dp, top = 3.dp)
        )

        // A rail that is not on Home has no slot to reorder, no name in the
        // rails list to rename and no placement to pin, so it shows none of
        // these - the same rule the TV row follows.
        if (!isHiddenSection) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 22.dp, top = 6.dp)
            ) {
                if (row.isCollection) {
                    ManagerActionMobile(
                        label = if (row.isPinned) "UNPIN" else "PIN",
                        accent = row.isPinned,
                        onClick = onPin
                    )
                } else if (row.isBuiltinRail) {
                    ManagerActionMobile(label = "RENAME", onClick = onRename)
                } else if (row.config != null) {
                    ManagerActionMobile(label = "RENAME", onClick = onRename)
                }
                // The TV row's icons, at a finger's size: the same glyphs
                // mean the same four stops, so a viewer who knows one screen
                // knows the other.
                val pinned = row.isCollection
                ManagerActionMobile(
                    icon = Icons.Filled.KeyboardDoubleArrowUp,
                    contentDescription = if (pinned) {
                        "Pin and move to the top"
                    } else {
                        "Move to the top of the list"
                    },
                    enabled = row.canMoveTop,
                    onClick = { onMove(Int.MIN_VALUE) }
                )
                ManagerActionMobile(
                    icon = Icons.Filled.ArrowUpward,
                    contentDescription = "Move up one",
                    enabled = row.canMoveUp,
                    onClick = { onMove(-1) }
                )
                ManagerActionMobile(
                    icon = Icons.Filled.ArrowDownward,
                    contentDescription = "Move down one",
                    enabled = row.canMoveDown,
                    onClick = { onMove(+1) }
                )
                ManagerActionMobile(
                    icon = Icons.Filled.KeyboardDoubleArrowDown,
                    contentDescription = if (pinned) {
                        "Unpin and move to the bottom"
                    } else {
                        "Move to the bottom of the list"
                    },
                    enabled = row.canMoveBottom,
                    onClick = { onMove(Int.MAX_VALUE) }
                )
            }
        }
    }
}

/**
 * One chip-sized action. 48dp tall because this is a finger target, unlike
 * the TV row's 38dp remote targets; the label chips are wider so RENAME and
 * SHOW are never clipped.
 */
@Composable
private fun ManagerActionMobile(
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> KBTextLo.copy(alpha = 0.45f)
        accent -> KBAccent
        else -> KBTextHi
    }
    KBCard(
        onClick = {
            if (enabled) onClick()
        },
        shape = KBShapeChip,
        modifier = Modifier.height(48.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(48.dp)
                .then(
                    if (icon != null) Modifier.width(48.dp)
                    else Modifier.padding(horizontal = 12.dp)
                )
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text(
                    text = label.orEmpty(),
                    color = tint,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun RailKindChipMobile(
    isCollection: Boolean,
    isBrowseRow: Boolean = false,
    isBuiltinRow: Boolean = false,
    isCustomCatalogRow: Boolean = false
) {
    Text(
        text = when {
            isCustomCatalogRow -> "CUSTOM"
            isBuiltinRow -> "BUILT-IN"
            isBrowseRow -> "BROWSE"
            isCollection -> "COLLECTION"
            else -> "CATALOG"
        },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (isCollection) KBAccent else KBTextLo,
        modifier = Modifier
            .background(
                if (isCollection) KBAccent.copy(alpha = 0.22f) else KBSurfaceRaised,
                KBShapeChip
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * The rename form, used for both a built-in rail's title override and an
 * add-on catalog's custom name - the same two things the TV dialog's one form
 * edits, so the wording of the buttons is the same.
 */
@Composable
internal fun RenameRailDialogMobile(
    heading: String,
    currentName: String,
    hasCustomName: Boolean,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(
            title = heading,
            subtitle = "Change the rail name shown on Home."
        ) {
            KBTextField(
                value = currentName,
                onValueChange = onNameChange,
                placeholder = "Rail name",
                modifier = Modifier.fillMaxWidth(),
                onDone = onSave
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KBButton(
                    label = "SAVE",
                    enabled = currentName.trim().isNotEmpty(),
                    onClick = onSave
                )
                if (hasCustomName) {
                    KBButton(label = "RESET NAME", onClick = onReset)
                }
                KBButton(label = "CANCEL", onClick = onDismiss)
            }
        }
    }
}

/** Removing an imported collection source is destructive, so it is confirmed. */
@Composable
internal fun ConfirmRemoveCollectionDialogMobile(
    url: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "REMOVE COLLECTION SOURCE?") {
            Text(
                text = "Remove ${url.substringAfterLast('/').ifBlank { url }}? Its " +
                    "collection rail disappears from Home until re-added.",
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                KBButton(
                    label = "CANCEL",
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                KBButton(
                    label = "REMOVE",
                    danger = true,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm
                )
            }
        }
    }
}
