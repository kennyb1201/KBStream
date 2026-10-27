package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kennyb1201.kbstream.data.debrid.TorBoxReviewRow
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.settings.UNOWNED_REVIEW_BODY
import com.kennyb1201.kbstream.ui.settings.UNOWNED_REVIEW_REMOVAL
import com.kennyb1201.kbstream.ui.settings.filedCountLine
import com.kennyb1201.kbstream.ui.settings.isSeries
import com.kennyb1201.kbstream.ui.settings.isSyncMark
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The phone's half of the "Review N unowned cloud titles" queue.
 *
 * The TV dialog ([com.kennyb1201.kbstream.ui.settings.TorBoxReviewDialog]) is
 * built out of tv-material3 surfaces: `KBCard` there is the focus surface a
 * remote drives with Select, and a thumb does nothing on it. Rendering that
 * dialog on a phone made the whole queue - the rows AND the pinned KEEP/REMOVE
 * row - a dead panel, which is exactly what "the manage unowned titles screen is
 * unclickable on mobile" was. This is the same dialog with the same data path,
 * copy, pre-selection and pinned count row, drawn from the touch components
 * ([KBCard], [KBButton], [KBDialogPanel]) so a finger can use it.
 *
 * The rules stay shared, not re-derived: the rows come from
 * [com.kennyb1201.kbstream.data.debrid.TorBoxReview], the wording from
 * [UNOWNED_REVIEW_BODY] / [UNOWNED_REVIEW_REMOVAL], the count line from
 * [filedCountLine], and confirming removes through the same
 * [com.kennyb1201.kbstream.data.debrid.TorBoxReview.remove] call the pane
 * already makes - so a title handled here is handled exactly as it would be on
 * TV.
 *
 * The action row is PINNED here too, for the same reason it is on TV: the queue
 * is long by nature, and a confirm button that scrolls with the rows ends up
 * below the fold. The list is capped against the room the dialog actually has,
 * the row sits over its last strip, and the count rides beside the buttons.
 */
@Composable
internal fun TorBoxReviewDialogMobile(
    rows: List<TorBoxReviewRow>,
    filedCount: Int,
    onKeep: () -> Unit,
    onRemove: (List<TorBoxReviewRow>) -> Unit
) {
    var selected by remember(rows) {
        mutableStateOf(rows.filter { it.preSelected }.map { it.key }.toSet())
    }

    Dialog(onDismissRequest = onKeep) {
        KBDialogPanel(title = "Unowned cloud titles") {
            Text(
                text = UNOWNED_REVIEW_BODY,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = filedCountLine(shown = rows.size, filed = filedCount),
                color = KBTextHi,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = UNOWNED_REVIEW_REMOVAL,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val listCap = (maxHeight - PINNED_ROW_HEIGHT).coerceIn(96.dp, 340.dp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = listCap)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        // The pinned row's height, so the last real row can be
                        // scrolled clear of it instead of hiding underneath.
                        contentPadding = PaddingValues(bottom = PINNED_ROW_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(rows, key = { it.key }) { row ->
                            val ticked = row.key in selected
                            KBCard(
                                onClick = {
                                    selected = if (ticked) {
                                        selected - row.key
                                    } else {
                                        selected + row.key
                                    }
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
                                                append(
                                                    if (row.item.mediaType.isSeries()) {
                                                        "Series"
                                                    } else {
                                                        "Movie"
                                                    }
                                                )
                                                if (row.item.origin.isSyncMark()) {
                                                    append(" · added by sync")
                                                }
                                            },
                                            color = KBTextLo,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Pinned: drawn over the list's last strip, on an opaque
                    // plate so scrolling rows pass under it rather than through
                    // it.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(KBSurface, KBShapeSmall)
                            .padding(top = 10.dp)
                    ) {
                        Text(
                            text = if (selected.size == 1) {
                                "1 selected"
                            } else {
                                "${selected.size} selected"
                            },
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        KBButton(label = "KEEP", onClick = onKeep)
                        KBButton(
                            label = if (selected.size == 1) {
                                "REMOVE 1"
                            } else {
                                "REMOVE ${selected.size}"
                            },
                            // No focus glow on a phone, so the shared `danger`
                            // tint is the whole affordance and it stays legible
                            // on the raised plate.
                            danger = true,
                            enabled = selected.isNotEmpty(),
                            onClick = { onRemove(rows.filter { it.key in selected }) }
                        )
                    }
                }
            }
        }
    }
}

/** The pinned action row's height, reserved in the list's own padding. */
private val PINNED_ROW_HEIGHT = 56.dp
