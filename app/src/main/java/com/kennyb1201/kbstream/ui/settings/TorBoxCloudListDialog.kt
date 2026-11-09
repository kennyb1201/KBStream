package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kennyb1201.kbstream.FormFactor
import com.kennyb1201.kbstream.data.debrid.TorBoxClient
import com.kennyb1201.kbstream.data.debrid.TorBoxCloudCache
import com.kennyb1201.kbstream.data.debrid.TorBoxCloudPicker
import com.kennyb1201.kbstream.data.debrid.TorBoxCloudRules
import com.kennyb1201.kbstream.ui.components.KBDialogPanel as TvKBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBTextField as TvKBTextField
import com.kennyb1201.kbstream.ui.components.touch.KBButton as TouchKBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel as TouchKBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField as TouchKBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.launch

/**
 * What is actually in the viewer's TorBox cloud, file by file.
 *
 * The picker can only offer a cloud file on a title it can place the file under
 * (see [TorBoxCloudPicker]); this is the surface for everything else - what the
 * account holds, which title and episode each file says it is, and a way from
 * that file to the title it belongs to (spec F2).
 *
 * Deliberately one list rather than a screen with tabs: the account is the
 * viewer's own library, and the only question this answers is "is my file
 * here, and where does it play". A file that names nothing is shown with its
 * raw name and no action - visible, never guessed at (T5).
 *
 * The same dialog is opened from both settings panes, so the interactive
 * surfaces are chosen by form factor: the TV card is the focus surface a remote
 * drives with Select and takes no tap at all on a phone (see
 * TorBoxReviewDialogMobile), while the touch button is what a finger uses. The
 * body, the rows and the tap's own answer are shared, so the two cannot drift.
 */
@Composable
fun TorBoxCloudListDialog(
    onDismiss: () -> Unit,
    /**
     * Opens the title a tapped file belongs to. `id` is the app's own route id
     * ("tmdb:123"); `season`/`episode` are null when the file named a title but
     * no episode, which opens the title rather than a picker.
     */
    onOpen: (type: String, id: String, title: String, season: Int?, episode: Int?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<CloudFileRow>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // force = the viewer opened the list to see it as it stands, so TorBox's own
    // short-lived answer is not reused past its point (bypassCache is on the
    // client's request already).
    LaunchedEffect(Unit) {
        val torrents = TorBoxCloudCache.fresh(context, force = true)
        rows = cloudFileRows(torrents)
        loading = false
    }

    val open: (CloudFileRow) -> Unit = { row ->
        val title = row.title ?: row.torrentName
        busy = true
        message = null
        scope.launch {
            val resolved = TorBoxCloudPicker.resolveTitle(
                context = context,
                torrentName = row.torrentName
            )
            busy = false
            if (resolved == null) {
                // The tap's own answer, said out loud: a name TMDB cannot place
                // has nowhere to go, and a dead press would read as a broken
                // row.
                message = "TMDB could not match “${row.torrentName}” to a title, " +
                    "so there is nowhere to open."
                return@launch
            }
            val episode = row.season?.let { season -> row.episode?.let { season to it } }
            onOpen(
                resolved.mediaType,
                "tmdb:${resolved.tmdbId}",
                title,
                episode?.first,
                episode?.second
            )
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        if (FormFactor.isTv) {
            TvKBDialogPanel(
                title = "TorBox cloud",
                subtitle = PANEL_SUBTITLE,
                modifier = Modifier.heightIn(max = 560.dp)
            ) {
                CloudListBody(
                    touch = false,
                    loading = loading,
                    rows = rows,
                    busy = busy,
                    message = message,
                    onRowClick = open,
                    onDismiss = onDismiss
                )
            }
        } else {
            TouchKBDialogPanel(
                title = "TorBox cloud",
                subtitle = PANEL_SUBTITLE,
                modifier = Modifier.heightIn(max = 560.dp)
            ) {
                CloudListBody(
                    touch = true,
                    loading = loading,
                    rows = rows,
                    busy = busy,
                    message = message,
                    onRowClick = open,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

private const val PANEL_SUBTITLE =
    "Files in your account. A file that names an episode opens that episode's picker; " +
        "one that names only a title opens the title."

/** The list itself, shared by both form factors. */
@Composable
private fun CloudListBody(
    touch: Boolean,
    loading: Boolean,
    rows: List<CloudFileRow>,
    busy: Boolean,
    message: String?,
    onRowClick: (CloudFileRow) -> Unit,
    onDismiss: () -> Unit
) {
    // Dialog-local, both of them: they reset when the dialog closes, and neither
    // touches the fetch - search and sort are over rows already in memory (F2,
    // F3, §3).
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(CloudFileSort.NEWEST) }
    // What the list actually draws. Keyed on all three so a new answer from
    // TorBox re-filters with the query that is on screen.
    val shown = remember(query, rows, sort) { cloudFileListRows(rows, query, sort) }

    Column {
        // F1: ONE compact controls row above the list, in the shared body so
        // both form factors get it. Unweighted, and above the list the weight
        // fix makes flexible: the panel's 560dp still goes to rows, not chrome.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            if (touch) {
                TouchKBTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search files…",
                    onClear = { query = "" },
                    modifier = Modifier.weight(1f)
                )
            } else {
                TvKBTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search files…",
                    // Manual IME, like the guide's overlay: a modal dialog must
                    // keep the list reachable, so focus alone never opens the
                    // full-screen leanback keyboard - OK starts editing, a
                    // D-pad move ends it (see KBTextField).
                    keepFocusOnDone = true,
                    openKeyboardOnFocus = false,
                    closeKeyboardOnBlur = true,
                    modifier = Modifier.weight(1f)
                )
            }
            if (touch) {
                TouchKBButton(label = "Sort: ${sort.label}") { sort = sort.next() }
            } else {
                SortCard(label = "Sort: ${sort.label}") { sort = sort.next() }
            }
        }

        when {
            loading -> Text(
                text = "Reading your cloud…",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )

            rows.isEmpty() -> Text(
                text = "Nothing in your TorBox cloud yet, or no files have finished " +
                    "downloading. Only finished, playable files are listed.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )

            // F4: the query matched nothing. Distinct from an empty cloud, and
            // the controls and CLOSE stay where they are - a search that comes
            // back empty must not look like a broken dialog.
            shown.isEmpty() -> Text(
                text = "No files match “${query.trim()}”.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )

            // The list is what flexes, so a full list (or a message line, or a
            // wrapped subtitle) shrinks it rather than pushing CLOSE out of the
            // panel - the field photo had the button cut in half at the bottom
            // edge. `fill = false` keeps a short list short; the 400dp cap is
            // unchanged.
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(shown, key = { row -> "${row.torrentId}:${row.fileId}" }) { row ->
                    CloudFileTile(
                        row = row,
                        touch = touch,
                        enabled = row.title != null && !busy,
                        onClick = { onRowClick(row) }
                    )
                }
            }
        }

        message?.let { line ->
            Text(
                text = line,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (touch) {
                TouchKBButton(
                    label = "CLOSE",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss
                )
            } else {
                TvCloseButton(onClick = onDismiss)
            }
        }
    }
}

/**
 * The sort cycle, on the TV's focus surface: the label IS the current mode, so
 * the control says what it is showing without a menu.
 */
@Composable
private fun SortCard(label: String, onClick: () -> Unit) {
    KBCard(onClick = onClick) {
        Text(
            text = label,
            color = KBTextHi,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

/** The TV panel's CLOSE: the same press surface its rows use. */
@Composable
private fun TvCloseButton(onClick: () -> Unit) {
    KBCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "CLOSE",
            color = KBTextHi,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

/** One file the dialog can list, with what its name says about it. */
internal data class CloudFileRow(
    val torrentId: Long,
    val fileId: Int,
    val torrentName: String,
    val fileName: String,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val episodeEnd: Int?,
    /** The file's size in bytes, or 0 when TorBox's answer omitted it. */
    val size: Long = 0L
) {
    /**
     * The episode the file states, in the subtitle's own format, or null when it
     * states none.
     *
     * One spelling shared by the row's second line and the search: a viewer who
     * types "s01e08" is typing what they can see.
     */
    val episodeText: String?
        get() = when {
            season != null && episode != null && episodeEnd != null && episodeEnd > episode ->
                "S%02dE%02d-%02d".format(season, episode, episodeEnd)
            season != null && episode != null -> "S%02dE%02d".format(season, episode)
            episode != null && episodeEnd != null && episodeEnd > episode ->
                "E%02d-%02d".format(episode, episodeEnd)
            episode != null -> "E%02d".format(episode)
            else -> null
        }

    /** The second line: the title and the episode the file states, or its raw state. */
    val subtitle: String
        get() {
            val name = title ?: return "Not recognised — no action"
            return "$name · ${episodeText ?: "title only"} · Ready"
        }
}

/**
 * The order the cloud list can be shown in, cycled by the one sort control
 * (spec F3).
 *
 * [NEWEST] is the order TorBox returns - newest torrent first - so the default
 * view is exactly what it was before sorting existed. Date-added ordering is
 * not here: [TorBoxClient.CloudTorrent] carries no date, so that needs the
 * client (spec F5), not a comparator.
 */
internal enum class CloudFileSort(val label: String) {
    NEWEST("Newest"),
    NAME("Name"),
    EPISODE("Episode"),
    LARGEST("Largest");

    /** The next mode in the cycle: NEWEST → NAME → EPISODE → LARGEST → NEWEST. */
    fun next(): CloudFileSort = entries[(ordinal + 1) % entries.size]
}

/**
 * The rows the list SHOWS: [rows] narrowed by [query], in [sort] order.
 *
 * Pure, in memory, and over rows the dialog already holds (F2, F3): a search
 * never re-fetches, and the default `("", NEWEST)` is the list exactly as it
 * arrived. A query matches when it is a substring - case-insensitive and
 * trimmed - of the title, the file name, the torrent name, or the episode span
 * the subtitle prints.
 */
internal fun cloudFileListRows(
    rows: List<CloudFileRow>,
    query: String,
    sort: CloudFileSort
): List<CloudFileRow> {
    val needle = query.trim().lowercase()
    val matched = if (needle.isEmpty()) {
        rows
    } else {
        rows.filter { row ->
            row.title?.lowercase()?.contains(needle) == true ||
                row.fileName.lowercase().contains(needle) ||
                row.torrentName.lowercase().contains(needle) ||
                row.episodeText?.lowercase()?.contains(needle) == true
        }
    }
    return when (sort) {
        // The order they arrived in: today's default, untouched.
        CloudFileSort.NEWEST -> matched
        // A-Z on what the row calls itself, the same fallback its subtitle uses.
        CloudFileSort.NAME -> matched.sortedBy { (it.title ?: it.torrentName).lowercase() }
        // One episode's files together: title, then season, then episode. An
        // untitled row - and, within a title, a file that names no episode -
        // sorts last, because there is nothing to order it by.
        CloudFileSort.EPISODE -> matched.sortedWith(
            compareBy<CloudFileRow> { it.title == null }
                .thenBy { it.title?.lowercase() ?: "" }
                .thenBy { it.season == null }
                .thenBy { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode == null }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.fileName.lowercase() }
        )
        // Biggest first; a size TorBox did not send (0) has no place in a size
        // order, so it goes last rather than first.
        CloudFileSort.LARGEST -> matched.sortedWith(
            compareBy<CloudFileRow> { it.size == 0L }.thenByDescending { it.size }
        )
    }
}

/**
 * The account's finished, PLAYABLE files, newest torrent first as the API
 * returns them (F2a), with a file that names nothing kept visible and raw (T5).
 *
 * Only containers the picker would offer are listed (the shared
 * [TorBoxCloudRules.isVideo] rule): a screenshot, an .nfo or a .txt can never
 * play, and a tap on one only opens the title its .mkv sibling already opens -
 * so it is noise in a list whose job is "is my file here, and where does it
 * play". Unlike the picker there is no `ifEmpty` fallback here: a torrent with
 * nothing playable contributes no rows rather than a row that cannot play.
 *
 * Pure, so the parse and the "which files are even listed" rule are pinned by a
 * JVM test rather than by a device.
 */
internal fun cloudFileRows(
    torrents: List<TorBoxClient.CloudTorrent>
): List<CloudFileRow> = torrents
    // A file still downloading is hidden here exactly as it is in the picker
    // (spec F0e/T4): this list exists to find what can be played.
    .filter { it.complete }
    .flatMap { torrent ->
        val parsed = TorBoxCloudRules.parseName(torrent.name)
        torrent.files
            .filter { TorBoxCloudRules.isVideo(it.name) }
            .map { file ->
                val span = TorBoxCloudRules.fileSpan(torrent.name, file.name)
                CloudFileRow(
                    torrentId = torrent.id,
                    fileId = file.id,
                    torrentName = torrent.name,
                    fileName = file.name,
                    title = parsed?.title,
                    season = span?.season,
                    episode = span?.start,
                    episodeEnd = span?.end,
                    size = file.size
                )
            }
    }

@Composable
private fun CloudFileTile(
    row: CloudFileRow,
    touch: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (!enabled) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurfaceRaised.copy(alpha = 0.5f), KBShapeCard)
                .border(1.dp, KBTextLo.copy(alpha = 0.16f), KBShapeCard)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                Text(
                    text = row.fileName,
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.subtitle,
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        return
    }
    if (touch) {
        TouchKBButton(
            label = row.fileName,
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick
        )
        Text(
            text = row.subtitle,
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
        return
    }
    KBCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = row.fileName,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.subtitle,
                color = KBAccent,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
