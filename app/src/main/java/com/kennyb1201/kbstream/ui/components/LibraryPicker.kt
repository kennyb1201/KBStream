package com.kennyb1201.kbstream.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibraryMirror
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBFocusRow
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The title a long-press "Add to list…" entry point hands to the picker
 * dialog. Carries the same id triple every add pipeline uses.
 */
data class LibraryAddTarget(
    val mediaType: String,
    val imdbId: String?,
    val tmdbId: Int?,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null
)

/**
 * One row the Add-to-list dialog offers: MY LIST (the profile's default
 * local list) plus one row per local personal list, watchlist and
 * personal list on every connected tracker.
 */
data class LibraryPickerRow(
    /** Local My List row (add goes through the full mirror pipeline). */
    val isMyList: Boolean = false,
    /** MDBList watchlist row (remote-only). */
    val isMdbListWatchlist: Boolean = false,
    /** Any other list: local (negative id) or MDBList (positive id). */
    val list: LibraryList? = null
) {
    val label: String
        get() = when {
            isMyList -> "MY LIST"
            isMdbListWatchlist -> "Watchlist · MDBList"
            else -> list?.name ?: ""
        }

    val sublabel: String
        get() = when {
            isMyList -> "This profile · mirrors to connected trackers"
            isMdbListWatchlist -> "${list?.itemCount ?: 0} titles · remote"
            else -> {
                val source = when {
                    list?.source?.name?.startsWith("LOCAL") == true -> "This device"
                    else -> "MDBList"
                }
                "${list?.itemCount ?: 0} titles · $source"
            }
        }
}

/**
 * Shared "Add to list…" dialog behind the long-press entry point on every
 * poster surface. Lists the profile's local My List, local personal
 * lists, the MDBList watchlist and MDBList personal lists; a highlighted
 * ✓ marks lists that already contain the title. Selecting a row adds the
 * title there immediately (My List also runs the Simkl/MDBList watchlist
 * mirror) and shows OK ✓ for feedback. A trailing "+ CREATE LIST" row
 * creates a new local personal list; with a key configured, an
 * identically-named MDBList list is matched first so the add mirrors to
 * the account list instead of staying device-only.
 */
@Composable
fun LibraryAddToListDialog(
    mediaType: String,
    imdbId: String?,
    tmdbId: Int?,
    title: String,
    year: Int?,
    posterUrl: String?,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    var rows by remember { mutableStateOf<List<LibraryPickerRow>>(emptyList()) }
    var memberships by remember { mutableStateOf<Set<String>>(emptySet()) }
    var doneLabels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCreateField by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    var loadingLists by remember { mutableStateOf(true) }

    val normalizedType = when (mediaType.lowercase()) {
        "tv", "series" -> "series"
        else -> "movie"
    }

    // Back on the dialog (no text field focused) dismisses it, matching
    // the app's other long-press menus.
    androidx.activity.compose.BackHandler {
        if (showCreateField) {
            showCreateField = false
            newListName = ""
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        // Local rows are instant; remote lists load best-effort in the
        // background so the dialog opens immediately.
        val localLists = LocalLibraryStore.userLists(context)
        val initial = mutableListOf<LibraryPickerRow>(LibraryPickerRow(isMyList = true))
        initial += localLists.map { LibraryPickerRow(list = it) }

        val mdbConnected = LibraryMirror.mdbListConnected(context)
        var remoteRows = emptyList<LibraryPickerRow>()
        if (mdbConnected) {
            remoteRows = withContext(Dispatchers.IO) {
                val watchlistCount = runCatching {
                    MdbListClient.getWatchlist(context).size
                }.getOrDefault(0)
                val lists = runCatching {
                    MdbListClient.getUserLists(context)
                }.getOrDefault(emptyList())
                val rowsOut = mutableListOf<LibraryPickerRow>()
                if (watchlistCount > 0 || lists.isNotEmpty()) {
                    rowsOut += LibraryPickerRow(
                        isMdbListWatchlist = true,
                        list = LibraryList(
                            id = -1,
                            name = "Watchlist",
                            itemCount = watchlistCount,
                            source = com.kennyb1201.kbstream.data.library.LibrarySource.MDBLIST_WATCHLIST
                        )
                    )
                }
                rowsOut += lists.map { user ->
                    LibraryPickerRow(
                        list = LibraryList(
                            id = user.id,
                            name = user.name,
                            itemCount = user.itemCount,
                            source = com.kennyb1201.kbstream.data.library.LibrarySource.MDBLIST_LIST
                        )
                    )
                }
                rowsOut
            }
        }

        rows = initial + remoteRows
        loadingLists = false

        // Membership check: local My List synchronously; MDBList rows via
        // one items fetch per list (capped, fail-soft).
        val member = mutableSetOf<String>()
        if (LocalLibraryStore.isInMyList(context, normalizedType, imdbId, tmdbId)) {
            member += "MY_LIST"
        }
        localLists.forEach { list ->
            val has = LocalLibraryStore.listItems(context, list.id)
                .any { LocalLibraryStore.matches(it, normalizedType, imdbId, tmdbId) }
            if (has) member += "local:${list.id}"
        }
        if (mdbConnected) {
            withContext(Dispatchers.IO) {
                runCatching {
                    MdbListClient.getWatchlist(context).any { entry ->
                        matchesEntry(entry, normalizedType, imdbId, tmdbId)
                    }
                }.getOrDefault(false).let { if (it) member += "MDB_WATCHLIST" }

                runCatching { MdbListClient.getUserLists(context) }
                    .getOrDefault(emptyList())
                    .forEach { user ->
                        val has = runCatching {
                            MdbListClient.getListItems(context, user.id).any { entry ->
                                matchesEntry(entry, normalizedType, imdbId, tmdbId)
                            }
                        }.getOrDefault(false)
                        if (has) member += "mdb:${user.id}"
                    }
            }
        }
        memberships = member
    }

    LaunchedEffect(rows) {
        if (rows.isNotEmpty()) {
            kotlinx.coroutines.delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun rowKey(row: LibraryPickerRow): String = when {
        row.isMyList -> "MY_LIST"
        row.isMdbListWatchlist -> "MDB_WATCHLIST"
        row.list != null && row.list.id < 0 -> "local:${row.list.id}"
        row.list != null -> "mdb:${row.list.id}"
        else -> ""
    }

    fun addRow(row: LibraryPickerRow) {
        val key = rowKey(row)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                when {
                    row.isMyList -> LibraryMirror.addToLibrary(
                        context = context,
                        scope = scope,
                        mediaType = normalizedType,
                        imdbId = imdbId,
                        tmdbId = tmdbId,
                        title = title,
                        year = year,
                        posterUrl = posterUrl
                    )

                    row.isMdbListWatchlist -> {
                        if (!LibraryMirror.mdbListConnected(context)) {
                            false
                        } else {
                            runCatching {
                                com.kennyb1201.kbstream.data.mdblist.MdbListClient.addToWatchlist(
                                    context,
                                    listOf(
                                        com.kennyb1201.kbstream.data.mdblist.MdbListEntry(
                                            title = title,
                                            mediaType = normalizedType,
                                            year = year,
                                            poster = posterUrl,
                                            imdbId = imdbId?.takeIf { it.startsWith("tt") },
                                            tmdbId = tmdbId
                                        )
                                    )
                                )
                            }.getOrDefault(false)
                        }
                    }

                    row.list != null && row.list.id > 0 -> LibraryMirror.addToList(
                        context = context,
                        scope = scope,
                        list = row.list,
                        mediaType = normalizedType,
                        imdbId = imdbId,
                        tmdbId = tmdbId,
                        title = title,
                        year = year,
                        posterUrl = posterUrl
                    )

                    row.list != null -> LocalLibraryStore.addToLocalList(
                        context,
                        listId = row.list.id,
                        mediaType = normalizedType,
                        imdbId = imdbId,
                        tmdbId = tmdbId,
                        title = title,
                        year = year,
                        posterUrl = posterUrl
                    )

                    else -> false
                }
            }
            if (ok) {
                doneLabels = doneLabels + key
                memberships = memberships + key
            }
        }
    }

    fun createList() {
        val name = newListName.trim()
        if (name.isEmpty()) return
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                // With a key set, reuse an identically-named MDBList list so
                // the add mirrors to the account list; otherwise local-only.
                if (LibraryMirror.mdbListConnected(context)) {
                    val existing = runCatching {
                        MdbListClient.getUserLists(context)
                    }.getOrDefault(emptyList())
                        .firstOrNull { it.name.equals(name, ignoreCase = true) }
                    if (existing != null) {
                        LibraryList(
                            id = existing.id,
                            name = existing.name,
                            itemCount = existing.itemCount,
                            source = com.kennyb1201.kbstream.data.library.LibrarySource.MDBLIST_LIST
                        )
                    } else {
                        runCatching {
                            MdbListClient.createList(context, name)
                        }.getOrNull()?.let {
                            LibraryList(
                                id = it.id,
                                name = it.name,
                                itemCount = 0,
                                source = com.kennyb1201.kbstream.data.library.LibrarySource.MDBLIST_LIST
                            )
                        }
                    } ?: LocalLibraryStore.createList(context, name)
                } else {
                    LocalLibraryStore.createList(context, name)
                }
            }
            if (created != null) {
                rows = rows + LibraryPickerRow(list = created)
                newListName = ""
                showCreateField = false
                addRow(LibraryPickerRow(list = created))
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                .padding(22.dp)
        ) {
            Text(
                text = "ADD TO LIST",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = KBAccent
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            ) {
                if (loadingLists && rows.isEmpty()) {
                    item {
                        Text(
                            text = "Loading lists…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = KBTextLo,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }

                itemsIndexed(rows, key = { _, row -> rowKey(row) }) { _, row ->
                    val key = rowKey(row)
                    val member = key in memberships
                    val justDone = key in doneLabels
                    PickerRow(
                        label = row.label,
                        sublabel = row.sublabel,
                        trailing = when {
                            justDone -> "ADDED ✓"
                            member -> "✓"
                            else -> null
                        },
                        trailingAccent = justDone,
                        onClick = { addRow(row) },
                        modifier = if (rows.indexOf(row) == 0) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (showCreateField) {
                KBTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    placeholder = "New list name",
                    modifier = Modifier.fillMaxWidth(),
                    onDone = { createList() },
                    keepFocusOnDone = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PickerAction(label = "CREATE", enabled = newListName.isNotBlank()) { createList() }
                    PickerAction(label = "CANCEL") {
                        showCreateField = false
                        newListName = ""
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PickerAction(label = "+ CREATE LIST") { showCreateField = true }
                    PickerAction(label = "CLOSE") { onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    sublabel: String,
    trailing: String?,
    trailingAccent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = KBShapeCard

    // One TV clickable Surface, not `.focusable().clickable()`: that stack is
    // two focus targets, so the first D-pad press only landed focus and the
    // row had to be pressed a second time to actually pick the list.
    //
    // The focus treatment lives ON the Surface (border, scale, glow) exactly
    // as AddonListCard and the poster context-menu rows do it — same accent
    // ring, same raised fill, same small lift. It used to be a hand-rolled
    // `.clip(shape).border(...)` on the caller's `modifier`, which wraps the
    // whole Surface: that clip swallowed the focus-scale animation at the
    // row's layout bounds, so the bottom half of the two-line body (the
    // description) was sliced off, and the Material default scale was left in
    // charge of the growth, which was far more than any other row's.
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        // AddonListCard's value. The row keeps its layout height on focus
        // (see the clip note above), so the description cannot be pushed past
        // the list item it lives in.
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusRow,
            pressedScale = KBFocusPressed
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, KBTextLo.copy(alpha = 0.25f)),
                shape = shape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = shape
            )
        ),
        glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = KBFocusGlowSmall
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) KBAccent else KBTextHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = KBTextLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailing?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (trailingAccent) KBAccent else KBTextLo,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun PickerAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    if (enabled) {
        KBCard(onClick = onClick) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = KBTextHi
                )
            }
        }
    } else {
        androidx.tv.material3.Surface(
            onClick = {},
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.50f)
            )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = KBTextLo.copy(alpha = 0.50f)
                )
            }
        }
    }
}

/** MDBList membership test shared by the dialog's row badges. */
private fun matchesEntry(
    entry: com.kennyb1201.kbstream.data.mdblist.MdbListEntry,
    mediaType: String,
    imdbId: String?,
    tmdbId: Int?
): Boolean {
    val entryType = when (entry.mediaType.lowercase()) {
        "tv", "series" -> "series"
        else -> "movie"
    }
    val typeMatch = entryType == mediaType
    val idMatch =
        (imdbId != null && entry.imdbId == imdbId) ||
            (tmdbId != null && entry.tmdbId == tmdbId)
    return typeMatch && idMatch
}
