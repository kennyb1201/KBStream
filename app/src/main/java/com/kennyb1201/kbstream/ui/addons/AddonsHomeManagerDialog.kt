package com.kennyb1201.kbstream.ui.addons

/**
 * The "HOME MANAGER" dialog: one merged list of the rails Home renders —
 * KB collections and addon catalogs interleaved — with the reorder, pin,
 * hide/show and rename controls for each.
 *
 * Split out of AddonsScreen.kt. Both halves are now small enough that an
 * editor can rewrite them; more importantly the cut is at a real seam: this
 * cluster only touches the screen through the three `internal` composables
 * the screen calls ([CatalogManagerDialog], [ConfirmRemoveCollectionDialog],
 * [RenameCatalogDialog]) and the one it borrows back ([UrlField]), so nothing
 * private crosses the boundary.
 */

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBPasteChip
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Per-row focus anchors for the catalog manager dialog. Reordering a row
 * recomposes it under a new item key (often off-screen), which disposes the
 * focused button and throws D-pad focus back at the dialog header. After a
 * move we scroll to the row's new index and re-focus the SAME button there.
 */
private class CatalogRowFocus {
    enum class Slot { TOGGLE, PIN, TOP, UP, DOWN, BOTTOM }

    val toggle = FocusRequester()
    val pin = FocusRequester()
    val top = FocusRequester()
    val up = FocusRequester()
    val down = FocusRequester()
    val bottom = FocusRequester()

    fun of(slot: Slot): FocusRequester? = when (slot) {
        Slot.TOGGLE -> toggle
        Slot.PIN -> pin
        Slot.TOP -> top
        Slot.UP -> up
        Slot.DOWN -> down
        Slot.BOTTOM -> bottom
    }
}


@Composable
internal fun CatalogManagerDialog(
    configurations: List<CatalogConfiguration>,
    collectionsState: com.kennyb1201.kbstream.ui.addons.AddonsViewModel.CollectionUiState,
    homeOrderVersion: Int,
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
    onRename: (CatalogConfiguration) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    // Focus pinning for reorder/hide/show: any action that rebuilds the row
    // list disposes the focused button and throws D-pad focus at the dialog
    // header. The action handlers pin a (row key, button) target first; the
    // LaunchedEffect below scrolls to wherever that row landed (visible or
    // hidden section) and re-focuses the SAME button, so repeated presses
    // just keep working. If that
    // button is disabled at its new position, fall back to the row's
    // toggle card.
    val listState = rememberLazyListState()
    val rowRequesters = remember { mutableMapOf<String, CatalogRowFocus>() }
    // Focus restore target: the ROW KEY (not index — keys survive the list
    // rebuilds that moves/hides/shows cause) plus which button in the row.
    var pendingFocus by remember {
        mutableStateOf<Pair<String, CatalogRowFocus.Slot>?>(null)
    }
    // Retry bookkeeping: a target row can land a state-change late (the
    // collection slice reloads asynchronously). Keep the pending target and
    // retry on the next state change instead of dropping focus to the top.
    var pendingFocusAttempts by remember { mutableStateOf(0) }

    // The import block starts open ONLY on a first run (nothing imported yet)
    // so a new user is not hunting for it. Once a profile is imported it
    // collapses to one button and the arrangement — the reason this dialog
    // exists — owns the space.
    var importExpanded by remember {
        mutableStateOf(
            collectionsState.profileUrls.isEmpty() &&
                collectionsState.collections.isEmpty()
        )
    }

    // One flat list: addon catalog rows first-class alongside collection
    // rows, arranged by the merged home order the ViewModel owns.
    val rows: List<CatalogManagerDialogRow> =
        remember(configurations, collectionsState, homeOrderVersion) {
        val collectionByKey = collectionsState.collections.associateBy { it.key }
        val addonByKey = configurations.associateBy {
            KBHomeOrderPrefs.addonKeyFromManifest(
                it.addonManifestUrl,
                it.catalog.type,
                it.catalog.id
            )
        }
        val known = addonByKey.keys + collectionsState.collections.map { it.key }
        // Best-effort merged order read (same prefs the ViewModel writes);
        // keys not found keep their default slot at the end.
        val prefs = KBHomeOrderPrefs.readOrder()
        val orderedKeys = buildList {
            prefs.pinned.filter { it in known }.forEach { add(it) }
            prefs.order.filter { it in known && it !in prefs.pinned }.forEach { add(it) }
            known.forEach { if (it !in this) add(it) }
        }

        orderedKeys.mapNotNull { key ->
            if (key.startsWith("kb:")) {
                val collection = collectionByKey[key] ?: return@mapNotNull null
                // Hidden/pinned derive from the FRESH home-order prefs (the
                // same rule Home and the ViewModel use: hidden-set membership,
                // or never-arranged counts as hidden) — not the async
                // collectionsState snapshot. That snapshot reloads a beat
                // after a toggle, which left the row in its old section for
                // one frame and dumped D-pad focus at the top of the dialog.
                val arranged = prefs.pinned.toSet() + prefs.order.toSet() + prefs.hiddenSet
                CatalogManagerDialogRow(
                    key = key,
                    isCollection = true,
                    config = null,
                    collectionKey = key,
                    title = collection.title,
                    subtitle = "Collection · ${collection.folderCount} folders",
                    isPinned = key in prefs.pinned,
                    isHidden = key in prefs.hiddenSet || key !in arranged
                )
            } else {
                val config = addonByKey[key] ?: return@mapNotNull null
                CatalogManagerDialogRow(
                    key = key,
                    isCollection = false,
                    config = config,
                    collectionKey = null,
                    title = config.catalog.displayName.ifBlank { config.catalog.id },
                    subtitle = "${config.catalog.type} · ${config.addonName}",
                    isPinned = key in prefs.pinned.toSet(),
                    isHidden = !config.catalog.showOnHome
                )
            }
        }
    }

    val visibleRows = rows.filter { !it.isHidden }
    val hiddenRows = rows.filter { it.isHidden }
    val allCatalogsVisible = configurations.all { it.catalog.showOnHome }

    fun moveRow(row: CatalogManagerDialogRow, slot: CatalogRowFocus.Slot, delta: Int) {
        val fromIndex = visibleRows.indexOfFirst { it.key == row.key }
        if (fromIndex < 0) return
        // Arrange focus restore FIRST: the ViewModel write rebuilds the row
        // list. Restore targets the moved row itself (it lands at a new
        // index; the key-based restore finds it there).
        pendingFocus = row.key to slot
        if (row.isCollection) {
            onCollectionMove(row.key, delta)
        } else {
            row.config?.let { onCatalogMove(it, delta) }
        }
    }

    /**
     * Hide a row while keeping focus where the user's attention already is:
     * hiding removes the row from the visible list, disposes the focused
     * toggle button, and D-pad focus escapes to the dialog header (the
     * SHOW ALL / DONE row). Pin the toggle of the neighbor row (next, or
     * previous when hiding the last) so focus stays in the list instead.
     */
    fun hideRowKeepFocus(row: CatalogManagerDialogRow) {
        val index = visibleRows.indexOfFirst { it.key == row.key }
        val neighborKey = when {
            visibleRows.size <= 1 -> null // list empties; let focus rest
            index < 0 -> null
            else ->
                visibleRows.getOrNull(index + 1)?.key
                    ?: visibleRows.getOrNull(index - 1)?.key
        }
        // Park focus on the neighbor's toggle SYNCHRONOUSLY, before the
        // state change lands. The toggled row's item key flips
        // ("key" <-> "hidden:key"), which disposes the focused button and
        // would otherwise drop the ring on the dialog header for a frame
        // until the restore effect below runs — the visible jump the user
        // sees on every toggle. The neighbor keeps its key through the
        // rebuild, so parking on it first means focus is simply NEVER
        // cleared and the header is never touched.
        if (neighborKey != null) {
            rowRequesters[neighborKey]?.toggle?.let { requester ->
                runCatching { requester.requestFocus() }
            }
        }
        pendingFocus = neighborKey?.let { it to CatalogRowFocus.Slot.TOGGLE }
        if (row.isCollection) {
            onCollectionHide(row.collectionKey.orEmpty())
        } else {
            row.config?.let { onToggle(it, !it.catalog.showOnHome) }
        }
    }

    /**
     * Show a hidden row. The row itself LEAVES the hidden section on this
     * action — a catalog returns to its home-order slot (often far up the
     * list) and a collection re-lands at the end of the arrangement — so
     * pinning focus to the shown row dragged it up the whole dialog and
     * the user had to travel back down to reach the next hidden row.
     * Instead pin the NEXT hidden row's toggle (previous when this was
     * the last), mirroring hideRowKeepFocus, so successive toggles work
     * without any focus travel. Nothing to pin when this empties the
     * hidden section — focus rests (dialog header), same as hide.
     */
    fun showRowKeepFocus(row: CatalogManagerDialogRow) {
        val index = hiddenRows.indexOfFirst { it.key == row.key }
        val neighborKey = when {
            hiddenRows.size <= 1 -> null
            index < 0 -> null
            else ->
                hiddenRows.getOrNull(index + 1)?.key
                    ?: hiddenRows.getOrNull(index - 1)?.key
        }
        // Same synchronous park as hideRowKeepFocus: the shown row's item
        // key flips and disposes the focused toggle, so focus the next
        // hidden row's toggle BEFORE the rebuild instead of letting the
        // ring flash on the dialog header first.
        if (neighborKey != null) {
            rowRequesters[neighborKey]?.toggle?.let { requester ->
                runCatching { requester.requestFocus() }
            }
        }
        pendingFocus = neighborKey?.let { it to CatalogRowFocus.Slot.TOGGLE }
        if (row.isCollection) {
            onCollectionHide(row.collectionKey.orEmpty())
        } else {
            row.config?.let { onToggle(it, !it.catalog.showOnHome) }
        }
    }

    LaunchedEffect(configurations, collectionsState, homeOrderVersion) {
        val target = pendingFocus ?: return@LaunchedEffect
        val (key, slot) = target
        // The row may sit in the visible section or the hidden section —
        // compute its LazyColumn index from wherever it landed. If it hasn't
        // landed anywhere yet (late collection reload), keep the target and
        // retry on the next state change rather than consuming it and
        // stranding focus at the dialog header.
        val visibleIndex = visibleRows.indexOfFirst { it.key == key }
        val lazyIndex = if (visibleIndex >= 0) {
            visibleIndex
        } else {
            val hiddenIndex = hiddenRows.indexOfFirst { it.key == key }
            if (hiddenIndex >= 0) {
                visibleRows.size + 1 + hiddenIndex
            } else {
                val attempts = pendingFocusAttempts + 1
                if (attempts > 4) {
                    // Row vanished entirely (e.g. a failed collection reload
                    // emptied the list) — give up so a stale target can't
                    // hijack focus forever.
                    pendingFocus = null
                    pendingFocusAttempts = 0
                } else {
                    pendingFocusAttempts = attempts
                }
                return@LaunchedEffect
            }
        }
        pendingFocus = null
        pendingFocusAttempts = 0
        runCatching { listState.animateScrollToItem(lazyIndex) }
        val focus = rowRequesters[key] ?: return@LaunchedEffect
        focus.of(slot)?.let { requester ->
            runCatching { requester.requestFocus() }
        } ?: run { runCatching { focus.toggle.requestFocus() } }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(860.dp)
                .fillMaxHeight(0.88f)
                .background(KBVoid, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                .padding(18.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HOME MANAGER",
                        color = KBAccent,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Arrange the rails on Home. Changes are instant.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Text(
                        text = "Collections pin above your other rails · a " +
                            "catalog moves to the top of the list · ⏬ unpins " +
                            "and drops it last",
                        color = KBTextLo.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
                if (configurations.isNotEmpty()) {
                    ActionButton(
                        label = if (allCatalogsVisible) "HIDE ALL" else "SHOW ALL",
                        onClick = { onToggleAll(!allCatalogsVisible) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                ActionButton(label = "DONE", onClick = onDismiss)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Import section: one button plus how many sources are in play.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                ActionButton(
                    label = if (importExpanded) "CLOSE IMPORT" else "IMPORT COLLECTIONS",
                    onClick = { importExpanded = !importExpanded }
                )
                if (collectionsState.profileUrls.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (collectionsState.profileUrls.size) {
                            1 -> "1 profile source"
                            else -> "${collectionsState.profileUrls.size} profile sources"
                        },
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (importExpanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                ) {
                    KBTextField(
                        value = collectionUrlInput,
                        onValueChange = onCollectionUrlChange,
                        placeholder = "https://…/kb-collections.json",
                        modifier = Modifier.weight(1f),
                        onDone = onImportCollectionUrl
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    KBPasteChip(onPaste = onCollectionUrlChange)
                    Spacer(modifier = Modifier.width(6.dp))
                    ActionButton(label = "ADD", onClick = onImportCollectionUrl)
                    Spacer(modifier = Modifier.width(6.dp))
                    ActionButton(label = "FILE", onClick = onPickCollectionFile)
                }

                // Imported profile sources (removable)
                if (collectionsState.profileUrls.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                    ) {
                        collectionsState.profileUrls.forEach { url ->
                            val isFile = url.startsWith("local:")
                            ActionButton(
                                // The paperclip emoji rendered differently on
                                // every device; a word does not.
                                label = (if (isFile) "FILE · " else "") +
                                    url.substringAfterLast('/').ifBlank { url },
                                onClick = { onRemoveCollectionProfile(url) }
                            )
                        }
                    }
                    Text(
                        text = "Press OK on a source, then confirm, to remove it",
                        color = KBTextLo.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            // Kept outside the collapse: import feedback must never be hidden
            // behind the section that produced it.
            collectionsState.statusMessage?.let { message ->
                Text(
                    text = message,
                    color = if (message.startsWith("Import failed")) KBTextLo else KBAccent,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusGroup()
            ) {
                itemsIndexed(
                    items = visibleRows,
                    key = { _, row -> row.key }
                ) { index, row ->
                    val rowFocus = remember(row.key) { CatalogRowFocus() }
                    rowRequesters[row.key] = rowFocus
                    UnifiedManagerRow(
                        row = row,
                        position = index,
                        total = visibleRows.size,
                        rowFocus = rowFocus,
                        // A catalog's TOP means "head of the list", and pinned
                        // collections render above that — so with one pinned
                        // and no catalog before it, the press would do
                        // nothing. Show it as unavailable instead.
                        topEnabled = if (row.isCollection) {
                            index > 0
                        } else {
                            visibleRows.take(index).any { !it.isCollection }
                        },
                        // Hide keeps focus in the list (next row's toggle)
                        // instead of dumping it on the dialog header.
                        onToggle = { hideRowKeepFocus(row) },
                        onPin = {
                            if (row.isCollection) {
                                // A pin/unpin moves the row to (or out of) the
                                // top of the list, so it is rebuilt at a new
                                // index — restore focus onto its own pin button
                                // there, the same way a move does.
                                pendingFocus = row.key to CatalogRowFocus.Slot.PIN
                                onCollectionPin(row.collectionKey.orEmpty())
                            }
                        },
                        onMove = { slot, delta -> moveRow(row, slot, delta) },
                        onRename = {
                            if (!row.isCollection) row.config?.let { onRename(it) }
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
                    itemsIndexed(
                        items = hiddenRows,
                        key = { _, row -> "hidden:${row.key}" }
                    ) { _, row ->
                        val rowFocus = remember(row.key) { CatalogRowFocus() }
                        rowRequesters[row.key] = rowFocus
                        UnifiedManagerRow(
                            row = row,
                            position = -1,
                            total = -1,
                            rowFocus = rowFocus,
                            // A hidden rail has no Home slot to reorder, rename
                            // or pin, so it shows none of those controls instead
                            // of a row of buttons that do nothing.
                            isHiddenSection = true,
                            onToggle = { showRowKeepFocus(row) },
                            onPin = {},
                            onMove = { _, _ -> },
                            onRename = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConfirmRemoveCollectionDialog(
    url: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDismiss()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        // Initial focus lands on CANCEL (first requestable node) so a
        // panicked double-OK cannot confirm the deletion.
        val cancelRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelRequester.requestFocus() }

        Column(
            modifier = Modifier
                .width(620.dp)
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBDanger.copy(alpha = 0.45f), KBShapePanel)
                .padding(22.dp)
        ) {
            Text(
                text = "REMOVE COLLECTION SOURCE?",
                color = KBDanger,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Remove ${url.substringAfterLast('/').ifBlank { url }}? " +
                    "Its collection rail disappears from Home until re-added.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 18.dp)
            ) {
                ActionButton(
                    label = "CANCEL",
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelRequester)
                )
                ActionButton(label = "REMOVE", onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun UnifiedManagerRow(
    row: CatalogManagerDialogRow,
    position: Int,
    total: Int,
    rowFocus: CatalogRowFocus,
    isHiddenSection: Boolean = false,
    topEnabled: Boolean = true,
    onToggle: () -> Unit,
    onPin: () -> Unit,
    onMove: (CatalogRowFocus.Slot, Int) -> Unit,
    onRename: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapeCard)
            .background(KBSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Line 1: position + kind + name + pin state + show/hide
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (position >= 0) (position + 1).toString() else "—",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(24.dp)
            )
            RailKindChip(isCollection = row.isCollection)
            if (row.isPinned) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "Pinned above your other rails",
                    tint = KBAccent,
                    modifier = Modifier.padding(start = 6.dp).size(13.dp)
                )
            }
            Text(
                text = row.title,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp, end = 6.dp)
            )
            if (isHiddenSection) {
                // A toggle reading "off" on a rail that is absent from Home
                // says nothing about it; SHOW says what the press will do.
                KBCard(
                    onClick = onToggle,
                    modifier = Modifier.focusRequester(rowFocus.toggle)
                ) {
                    Text(
                        text = "SHOW",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else {
                CatalogToggle(
                    checked = !row.isHidden,
                    onClick = onToggle,
                    modifier = Modifier.focusRequester(rowFocus.toggle)
                )
            }
        }

        // Line 2: subtitle + actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Text(
                text = row.subtitle,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )

            // A rail that is not on Home has no slot to reorder, no name in
            // the rails list to rename and no placement to pin, so it shows
            // none of these. Dimmed buttons that do nothing were most of the
            // clutter — the only live control is the SHOW beside the title.
            if (!isHiddenSection) {
                // Rename is catalogs-only, pin is collections-only and the
                // OTHER kind's slot is reserved, so each row's arrow cluster
                // starts in the same column instead of shifting by a button.
                if (row.isCollection) {
                    Spacer(modifier = Modifier.width(38.dp))
                } else {
                    CatalogIconButton(
                        icon = Icons.Filled.Edit,
                        contentDescription = "Rename catalog",
                        onClick = onRename,
                        modifier = Modifier.size(38.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                if (row.isCollection) {
                    CatalogIconButton(
                        icon = Icons.Filled.PushPin,
                        contentDescription = if (row.isPinned) {
                            "Unpin — back into the list"
                        } else {
                            "Pin above your other rails"
                        },
                        tint = if (row.isPinned) KBAccent else KBTextHi,
                        onClick = onPin,
                        modifier = Modifier.size(38.dp).focusRequester(rowFocus.pin)
                    )
                } else {
                    Spacer(modifier = Modifier.width(38.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                CatalogIconButton(
                    icon = Icons.Filled.KeyboardDoubleArrowUp,
                    contentDescription = if (row.isCollection) {
                        "Pin to the very top of your rails"
                    } else {
                        "Move to the top of the list"
                    },
                    tint = KBTextHi,
                    enabled = topEnabled,
                    onClick = { onMove(CatalogRowFocus.Slot.TOP, Int.MIN_VALUE) },
                    modifier = Modifier.size(38.dp).focusRequester(rowFocus.top)
                )
                Spacer(modifier = Modifier.width(4.dp))
                CatalogIconButton(
                    icon = Icons.Filled.ArrowUpward,
                    contentDescription = "Move up one",
                    tint = KBTextHi,
                    enabled = position > 0,
                    onClick = { onMove(CatalogRowFocus.Slot.UP, -1) },
                    modifier = Modifier.size(38.dp).focusRequester(rowFocus.up)
                )
                Spacer(modifier = Modifier.width(4.dp))
                CatalogIconButton(
                    icon = Icons.Filled.ArrowDownward,
                    contentDescription = "Move down one",
                    tint = KBTextHi,
                    enabled = position in 0 until (total - 1),
                    onClick = { onMove(CatalogRowFocus.Slot.DOWN, +1) },
                    modifier = Modifier.size(38.dp).focusRequester(rowFocus.down)
                )
                Spacer(modifier = Modifier.width(4.dp))
                CatalogIconButton(
                    icon = Icons.Filled.KeyboardDoubleArrowDown,
                    contentDescription = if (row.isCollection) {
                        "Unpin and move to the bottom"
                    } else {
                        "Move to the bottom of the list"
                    },
                    tint = KBTextHi,
                    enabled = position in 0 until (total - 1),
                    onClick = { onMove(CatalogRowFocus.Slot.BOTTOM, Int.MAX_VALUE) },
                    modifier = Modifier.size(38.dp).focusRequester(rowFocus.bottom)
                )
            }
        }
    }
}

/** Plain data holder for a unified manager row. */
private data class CatalogManagerDialogRow(
    val key: String,
    val isCollection: Boolean,
    val config: CatalogConfiguration?,
    val collectionKey: String?,
    val title: String,
    val subtitle: String,
    val isPinned: Boolean,
    val isHidden: Boolean
)

/** Tiny kind tag: collections and catalogs live in one list now. */
@Composable
private fun RailKindChip(isCollection: Boolean) {
    Surface(
        shape = KBShapeSmall,
        colors = SurfaceDefaults.colors(
            containerColor = if (isCollection) {
                KBAccent.copy(alpha = 0.22f)
            } else {
                KBSurface.copy(alpha = 0.60f)
            },
            contentColor = if (isCollection) KBAccent else KBTextLo
        )
    ) {
        Text(
            text = if (isCollection) "COLLECTION" else "CATALOG",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun CatalogIconButton(
    icon: ImageVector,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (enabled) {
        KBCard(onClick = onClick, modifier = modifier) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    // Icons render in the app text color, not the dark
                    // default - black glyphs on the raised card were
                    // nearly invisible.
                    tint = tint.takeIf { it != Color.Unspecified } ?: KBTextHi,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    } else {
        Surface(
            shape = KBShapeCard,
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.50f)
            ),
            modifier = modifier
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = KBTextLo.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CatalogToggle(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // One D-pad target (the whole capsule) — the state is read from the
    // track color/knob position instead of ON/OFF text.
    KBCard(onClick = onClick, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            // Track
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(26.dp)
                    .clip(KBShapeCard)
                    .background(
                        if (checked) {
                            KBAccent.copy(alpha = 0.30f)
                        } else {
                            KBSurfaceRaised.copy(alpha = 0.60f)
                        }
                    )
                    .border(
                        1.dp,
                        if (checked) {
                            KBAccent
                        } else {
                            KBTextLo.copy(alpha = 0.45f)
                        },
                        KBShapeCard
                    )
            ) {
                // Knob
                Box(
                    modifier = Modifier
                        .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(3.dp)
                        .size(20.dp)
                        .clip(KBShapeChip)
                        .background(if (checked) KBAccent else KBTextLo)
                )
            }
        }
    }
}

@Composable
internal fun RenameCatalogDialog(
    currentName: String,
    hasCustomName: Boolean,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Same escape hatch as the rename/ADD dialogs: BACK clears focus and
    // hides the IME before dismissing so the leanback keyboard can never
    // trap the user.
    BackHandler {
        focusManager.clearFocus()
        keyboardController?.hide()
        onDismiss()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                .padding(22.dp)
        ) {
            Text(
                text = "RENAME CATALOG",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Change the rail name shown on Home.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            UrlField(currentName, onNameChange, "Catalog name")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                ActionButton(
                    label = "SAVE",
                    enabled = currentName.trim().isNotEmpty(),
                    onClick = onSave
                )
                if (hasCustomName) {
                    ActionButton(label = "RESET NAME", onClick = onReset)
                }
                ActionButton(label = "CANCEL", onClick = onDismiss)
            }
        }
    }
}
