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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.debrid.TorBoxReviewRow
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The "Review N unowned cloud titles" confirmation, shared by the TV pane and
 * the phone pane so both resolve the same rows, pre-select the same ones and
 * spell the same words.
 *
 * What it is for: a cloud title in this profile's list that no profile has
 * watched and no sibling profile lists cannot be told apart from one of the
 * viewer's own unwatched cloud titles by any stored fact (see
 * [com.kennyb1201.kbstream.data.debrid.torboxUnattributableRows]). The round
 * refuses to guess and files the rows here instead.
 *
 * Pre-selected: rows the sync itself stamped, because the sync definitely wrote
 * them. Unticked: unmarked rows, which may be hand-adds. Confirming removes the
 * ticked rows through the same tombstoning path as the cleanup, so a later
 * round cannot put them back; KEEP leaves every row, queued, exactly as it was.
 */
@Composable
internal fun TorBoxReviewDialog(
    rows: List<TorBoxReviewRow>,
    onKeep: () -> Unit,
    onRemove: (List<TorBoxReviewRow>) -> Unit
) {
    var selected by remember(rows) {
        mutableStateOf(rows.filter { it.preSelected }.map { it.key }.toSet())
    }
    // KEEP is the safe answer, so it takes focus: a D-pad press that lands in
    // the dialog must not be able to remove a row by itself.
    val keepFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        var landed = false
        var attempts = 0
        while (!landed && attempts < 12) {
            withFrameNanos { }
            landed = runCatching { keepFocusRequester.requestFocus() }.getOrDefault(false)
            attempts++
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onKeep) {
        KBDialogPanel(title = "Unowned cloud titles") {
            Text(
                text = "These titles are in your TorBox cloud and in this profile's " +
                    "list, but nothing says whose they are: no profile has watched " +
                    "them and no other profile lists them. The selected ones were " +
                    "added by the sync. Untick anything that is yours, then remove " +
                    "the rest.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(rows, key = { it.key }) { row ->
                    val ticked = row.key in selected
                    KBCard(
                        onClick = {
                            selected = if (ticked) selected - row.key else selected + row.key
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(KBSurfaceRaised, KBShapeSmall)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .border(
                                        1.dp,
                                        if (ticked) KBAccent else KBTextLo,
                                        KBShapeSmall
                                    )
                                    .background(
                                        if (ticked) KBAccent else Color.Transparent,
                                        KBShapeSmall
                                    )
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.item.title,
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        row.item.year?.let { append("$it · ") }
                                        append(if (row.item.mediaType.isSeries()) "Series" else "Movie")
                                        if (row.item.origin.isSyncMark()) append(" · added by sync")
                                    },
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                KBButton(
                    label = "KEEP",
                    modifier = Modifier.focusRequester(keepFocusRequester),
                    onClick = onKeep
                )
                KBButton(
                    label = if (selected.size == 1) "REMOVE 1" else "REMOVE ${selected.size}",
                    danger = true,
                    enabled = selected.isNotEmpty(),
                    onClick = { onRemove(rows.filter { it.key in selected }) }
                )
            }
        }
    }
}

private fun String.isSeries(): Boolean = lowercase() == "tv" || lowercase() == "series"

private fun com.kennyb1201.kbstream.data.library.LibraryOrigin.isSyncMark(): Boolean =
    this == com.kennyb1201.kbstream.data.library.LibraryOrigin.TORBOX_SYNC
