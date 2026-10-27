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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.ui.addons.AddonsViewModel
import com.kennyb1201.kbstream.ui.addons.HomeRailManagerRow
import com.kennyb1201.kbstream.ui.addons.homeRailManagerRows
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBPasteChip
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
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

    // The import block starts open only on a first run, so a new user is not
    // hunting for it; once a profile is imported the arrangement owns the space.
    var importExpanded by remember {
        mutableStateOf(
            collectionsState.profileUrls.isEmpty() &&
                collectionsState.collections.isEmpty()
        )
    }
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
