package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.input.key.key
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
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.library.traktListShelfId
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListEntry
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.trakt.TraktListEntry
import com.kennyb1201.kbstream.data.trakt.TraktListRef
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.kennyb1201.kbstream.ui.browse.landFocusOn
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
    /** Trakt watchlist row (remote-only). */
    val isTraktWatchlist: Boolean = false,
    /** Any other list: local, MDBList or Trakt. */
    val list: LibraryList? = null
) {
    val label: String
        get() = when {
            isMyList -> "MY LIST"
            isMdbListWatchlist -> "Watchlist · MDBList"
            isTraktWatchlist -> "Watchlist · Trakt"
            else -> list?.name ?: ""
        }

    val sublabel: String
        get() = when {
            isMyList -> "This profile · mirrors to connected trackers"
            isMdbListWatchlist -> "${list?.itemCount ?: 0} titles · remote"
            isTraktWatchlist -> "${list?.itemCount ?: 0} titles · remote"
            else -> "${list?.itemCount ?: 0} titles · ${list?.locationLabel ?: "MDBList"}"
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
    var failedLabels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCreateField by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    var loadingLists by remember { mutableStateOf(true) }

    /**
     * The target every row of this dialog actually adds, with the IMDB id
     * resolved from the TMDB one when the calling screen only knew the latter.
     *
     * This is the whole fix for "I picked my list and my watchlist and nothing
     * showed up": a browse screen (actor, studio/network, genre, decade,
     * collection) knows a TMDB id and nothing else, and every tracker mirror
     * keyed off the pair it was handed rather than resolving the other half.
     * The ticks, the adds and the mirrors all read the same pair from here.
     */
    var target by remember {
        mutableStateOf(
            LibraryAddTarget(
                mediaType = mediaType,
                imdbId = imdbId,
                tmdbId = tmdbId,
                title = title,
                year = year,
                posterUrl = posterUrl
            )
        )
    }

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
        // Resolve before anything below reads an id: the ticks, the adds and
        // the tracker mirrors must all key off the same pair.
        val resolved = LibraryAdds.resolve(context, target)
        target = resolved
        val imdb = resolved.imdbId
        val tmdb = resolved.tmdbId

        // Local rows are instant; remote lists load best-effort in the
        // background so the dialog opens immediately.
        val localLists = LocalLibraryStore.userLists(context)
        val initial = mutableListOf<LibraryPickerRow>(LibraryPickerRow(isMyList = true))
        initial += localLists.map { LibraryPickerRow(list = it) }

        val mdbConnected = LibraryMirror.mdbListConnected(context)
        var remoteRows = emptyList<LibraryPickerRow>()
        if (mdbConnected) {
            remoteRows = withContext(Dispatchers.IO) {
                val watchlistCount = runCatchingCancellable {
                    MdbListClient.getWatchlist(context).size
                }.getOrDefault(0)
                val lists = runCatchingCancellable {
                    MdbListClient.getUserLists(context)
                }.getOrDefault(emptyList())
                val rowsOut = mutableListOf<LibraryPickerRow>()
                // Always offered while connected: the row is a destination, not
                // a directory. An EMPTY watchlist is exactly the state where a
                // viewer needs the row - hiding it made an empty watchlist look
                // like an unconnected account.
                rowsOut += LibraryPickerRow(
                    isMdbListWatchlist = true,
                    list = LibraryList(
                        id = -1,
                        name = "Watchlist",
                        itemCount = watchlistCount,
                        source = LibrarySource.MDBLIST_WATCHLIST
                    )
                )
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

        // Trakt, beside MDBList: its watchlist and personal lists are the same
        // kind of remote destination, and the rows carry the same real ids so
        // a press reports the far end's answer rather than a local guess.
        if (LibraryMirror.traktConnected(context)) {
            val trakt = TraktRepository.getInstance(context)
            val traktRows = withContext(Dispatchers.IO) {
                val watchlistCount = runCatchingCancellable {
                    trakt.watchlist().size
                }.getOrDefault(0)
                val lists = runCatchingCancellable {
                    trakt.userLists()
                }.getOrDefault(emptyList())
                val rowsOut = mutableListOf<LibraryPickerRow>()
                // Same rule as MDBList's row: offered while connected even at
                // zero titles, because that is the watchlist someone is trying
                // to put their first title on.
                rowsOut += LibraryPickerRow(
                    isTraktWatchlist = true,
                    list = LibraryList(
                        id = -2,
                        name = "Watchlist",
                        itemCount = watchlistCount,
                        source = LibrarySource.TRAKT_WATCHLIST
                    )
                )
                rowsOut += lists.mapNotNull { user ->
                    val traktId = user.ids?.trakt ?: 0
                    val remoteId = user.ids?.slug
                        ?: traktId.takeIf { it > 0 }?.toString()
                    val name = user.name?.takeIf { it.isNotBlank() }
                    if (remoteId.isNullOrBlank() || name == null) return@mapNotNull null
                    LibraryPickerRow(
                        list = LibraryList(
                            id = traktListShelfId(traktId),
                            name = name,
                            itemCount = user.itemCount ?: 0,
                            source = LibrarySource.TRAKT_LIST,
                            remoteId = remoteId
                        )
                    )
                }
                rowsOut
            }
            remoteRows = remoteRows + traktRows
        }

        rows = initial + remoteRows
        loadingLists = false

        // Membership check: local My List synchronously; MDBList rows via
        // one items fetch per list (capped, fail-soft).
        val member = mutableSetOf<String>()
        if (LocalLibraryStore.isInMyList(context, normalizedType, imdb, tmdb)) {
            member += "MY_LIST"
        }
        localLists.forEach { list ->
            val has = LocalLibraryStore.listItems(context, list.id)
                .any { LocalLibraryStore.matches(it, normalizedType, imdb, tmdb) }
            if (has) member += "local:${list.id}"
        }
        if (mdbConnected) {
            withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    MdbListClient.getWatchlist(context).any { entry ->
                        matchesEntry(entry, normalizedType, imdb, tmdb)
                    }
                }.getOrDefault(false).let { if (it) member += "MDB_WATCHLIST" }

                runCatchingCancellable { MdbListClient.getUserLists(context) }
                    .getOrDefault(emptyList())
                    .forEach { user ->
                        val has = runCatchingCancellable {
                            MdbListClient.getListItems(context, user.id).any { entry ->
                                matchesEntry(entry, normalizedType, imdb, tmdb)
                            }
                        }.getOrDefault(false)
                        if (has) member += "mdb:${user.id}"
                    }
            }
        }
        if (LibraryMirror.traktConnected(context)) {
            val trakt = TraktRepository.getInstance(context)
            withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    trakt.watchlist().any { entry ->
                        matchesTraktEntry(entry, normalizedType, imdb, tmdb)
                    }
                }.getOrDefault(false).let { if (it) member += "TRAKT_WATCHLIST" }

                runCatchingCancellable { trakt.userLists() }
                    .getOrDefault(emptyList())
                    .forEach { user ->
                        val remoteId = user.ids?.slug
                            ?: user.ids?.trakt?.takeIf { it > 0 }?.toString()
                            ?: return@forEach
                        val has = runCatchingCancellable {
                            trakt.listItems(
                                TraktListRef(user = "me", listId = remoteId)
                            ).any { entry ->
                                matchesTraktEntry(entry, normalizedType, imdb, tmdb)
                            }
                        }.getOrDefault(false)
                        if (has) member += "trakt:$remoteId"
                    }
            }
        }
        memberships = member
    }

    // The dialog's first landing, and a VERIFIED one.
    //
    // Two separate faults lived here. `requestFocus()` answers FALSE for a node
    // that is not attached rather than throwing, so `runCatching { requestFocus()
    // }` reported success for a landing that never happened - a dialog that
    // opened with the D-pad nowhere, which on a remote is a dialog the viewer
    // cannot steer - and the fixed 80ms before it was a guess about when the
    // first row composes (the rows are only assigned once the remote list fetch
    // has returned). It is retried across frames instead, on the app's one
    // hand-back rule (see [landFocusOn]).
    //
    // And it lands ONCE per dialog open: the rows arrive in two waves (local
    // instantly, remote after) and [createList] appends another, and re-running
    // the landing on those changes yanked the D-pad back to the top of the list
    // mid-interaction, away from the + CREATE LIST row the viewer had just
    // used. A landing that never succeeded leaves the flag clear, so the next
    // arrival spends another budget instead of stranding the dialog.
    var focusLanded by remember { mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (focusLanded || rows.isEmpty()) return@LaunchedEffect
        focusLanded = landFocusOn(focusRequester)
    }

    fun rowKey(row: LibraryPickerRow): String = when {
        row.isMyList -> "MY_LIST"
        row.isMdbListWatchlist -> "MDB_WATCHLIST"
        row.isTraktWatchlist -> "TRAKT_WATCHLIST"
        row.list?.source == LibrarySource.LOCAL_LIST -> "local:${row.list.id}"
        row.list?.source == LibrarySource.TRAKT_LIST -> "trakt:${row.list.remoteId}"
        row.list != null -> "mdb:${row.list.id}"
        else -> ""
    }

    fun addRow(row: LibraryPickerRow) {
        val key = rowKey(row)
        // Off the dialog's own scope, not on it: the row can be dismissed (or
        // the Back key pressed) while the add is still resolving an id, and an
        // add that dies with the dialog is the one failure this dialog cannot
        // show — the row it happened on is gone.
        //
        // Every outcome is then reported on the row it came from. A row that
        // neither ticks nor says it failed is the shape this dialog used to
        // have for the most common failure of all - a target with no id the
        // far end accepted - and from the sofa that is indistinguishable from
        // the press not registering at all.
        LibraryAdds.runAdd(
            onResult = { ok ->
                if (ok) {
                    doneLabels = doneLabels + key
                    memberships = memberships + key
                    failedLabels = failedLabels - key
                } else {
                    failedLabels = failedLabels + key
                }
            }
        ) {
            LibraryAdds.addToRow(
                context = context,
                row = row,
                target = target
            )
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
                    val existing = runCatchingCancellable {
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
                        runCatchingCancellable {
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
                    val failed = key in failedLabels
                    PickerRow(
                        label = row.label,
                        sublabel = row.sublabel,
                        trailing = when {
                            failed -> "COULDN'T ADD"
                            justDone -> "ADDED ✓"
                            member -> "✓"
                            else -> null
                        },
                        trailingAccent = justDone,
                        trailingError = failed,
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
    trailingError: Boolean = false,
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
                    color = when {
                        trailingError -> KBDanger
                        trailingAccent -> KBAccent
                        else -> KBTextLo
                    },
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
        // Plain (non-clickable) Surface, the same disabled shape SyncSection's
        // SyncActionButton and the Settings rows use. This was a *clickable*
        // Surface with an empty onClick, which left a dead control in the
        // focus order: the D-pad stopped on something that looked disabled,
        // took focus, and did nothing when pressed. A plain Surface is skipped
        // by focus entirely. It also picks up the card shape the enabled
        // branch gets from KBCard, which the clickable default did not match.
        androidx.tv.material3.Surface(
            shape = KBShapeCard,
            colors = androidx.tv.material3.SurfaceDefaults.colors(
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

/** Trakt membership test: same id/type pair, from a list or watchlist row. */
private fun matchesTraktEntry(
    entry: TraktListEntry,
    mediaType: String,
    imdbId: String?,
    tmdbId: Int?
): Boolean {
    val entryType = LocalLibraryStore.normalizedType(
        if (entry.isMovie) "movie" else "series"
    )
    if (entryType != mediaType) return false
    val ids = entry.media.ids
    return (imdbId != null && ids?.imdb == imdbId) ||
        (tmdbId != null && ids?.tmdb == tmdbId)
}

/** MDBList membership test shared by the dialog's row badges. */
private fun matchesEntry(
    entry: MdbListEntry,
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
