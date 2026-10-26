package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.notifications.AirReminderRules
import com.kennyb1201.kbstream.data.notifications.AirReminderStore
import com.kennyb1201.kbstream.ui.components.HideTarget
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.LibraryAddToListDialog
import com.kennyb1201.kbstream.ui.components.LibraryAdds
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.components.PlayFromBeginningSelection
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.contextMenuSectionRank
import com.kennyb1201.kbstream.ui.components.isSeriesType
import com.kennyb1201.kbstream.ui.components.rememberKBFeedback
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.work.NewEpisodeWorker

/**
 * The touch twin of `ui/components/PosterContextMenu.kt`: what a long press on
 * a poster raises on a phone.
 *
 * It is a [ModalBottomSheet] instead of the TV's centred panel because that is
 * what a thumb can reach and what the platform already knows how to dismiss
 * (swipe down, tap the scrim, Back). Everything that decides *what* the menu
 * offers is kept, in the same order:
 *
 *  - the library rows ("Add to Library" / "In list ✓", "Add to list…") from the
 *    shared [LibraryAdds] pipeline, so an add resolves the missing IMDB id and
 *    reports its outcome exactly as it does on a TV;
 *  - the air-reminder row for a series;
 *  - "Play Manually" and "Play from Beginning", derived from the caller's own
 *    "Go to Details" row, so those actions exist wherever they do on TV;
 *  - the destructive "Hide everywhere" row, with the same undo feedback;
 *  - the canonical section order, from the shared [contextMenuSectionRank].
 *
 * What is gone is the D-pad plumbing - the focus anchor, the retry loops, the
 * edge-key consumption and the focus trap. A sheet is its own window layer: it
 * takes every tap, and system Back closes it through `ModalBottomSheet`'s own
 * handler, so none of that has anything left to do.
 *
 * Rows are label-only, exactly as the TV menu draws them. The phone used to
 * print each action's description under its label because there was vertical
 * room for it, but the sheet is a list of things to do, not an explanation of
 * them - the label already says what the row does (``Mark Entire Series as
 * Unwatched`` needs no gloss), and the extra lines doubled the height of every
 * menu for text nobody reads before tapping. The description strings are still
 * carried by [PosterContextAction] for the surfaces that spell the consequence
 * out elsewhere; this sheet simply stops drawing them.
 *
 * The one exception is a row the caller marked `isEnabled = false`: it is drawn
 * muted, does not act, and DOES print its description - because there the words
 * are the point, not a gloss (a protected title's download says why it cannot
 * happen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosterContextMenu(
    title: String,
    subtitle: String? = null,
    actions: List<PosterContextAction>,
    onDismiss: () -> Unit,
    hideTarget: HideTarget? = null,
    libraryTarget: LibraryAddTarget? = null,
    onBeforeHide: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // Every screen's poster menu shares this one channel, so an add or a hide
    // confirms in the same place app-wide (and a screen-reader user hears it).
    val feedback = rememberKBFeedback()

    val librarySaved = libraryTarget != null && LibraryAdds.inMyList(context, libraryTarget)
    var libraryPickTarget by remember { mutableStateOf<LibraryAddTarget?>(null) }

    val reminderShowId = libraryTarget
        ?.takeIf { isSeriesType(it.mediaType) }
        ?.let { AirReminderRules.showIdFor(it.imdbId, it.tmdbId) }
    var reminderOn by remember(reminderShowId) {
        mutableStateOf(
            reminderShowId != null && AirReminderStore(context).isFlagged(reminderShowId)
        )
    }

    val rows = buildList {
        libraryTarget?.let { target ->
            add(
                PosterContextAction(
                    label = if (librarySaved) "In Library ✓" else "Add to Library",
                    description = if (librarySaved) {
                        "Already on this profile's My List"
                    } else {
                        "Save to My List" + LibraryAdds.mirrorSuffix(context)
                    }
                ) {
                    LibraryAdds.add(context, target) { added ->
                        feedback.show(
                            text = if (added) {
                                "Added to My List: ${target.title}"
                            } else {
                                "Couldn't add ${target.title}"
                            },
                            isError = !added
                        )
                    }
                    onDismiss()
                }
            )
            add(
                PosterContextAction(
                    label = "Add to list…",
                    description = "Pick a personal list or watchlist"
                ) {
                    libraryPickTarget = target
                }
            )
        }

        if (reminderShowId != null) {
            add(
                PosterContextAction(
                    label = if (reminderOn) "Reminder on ✓" else "Remind me when it airs",
                    description = if (reminderOn) {
                        "You'll be alerted on the day a new episode airs"
                    } else {
                        "Get a notification on the day a new episode airs"
                    }
                ) {
                    val nowOn = AirReminderStore(context).toggle(reminderShowId)
                    reminderOn = nowOn
                    runCatching {
                        NewEpisodeWorker.syncScheduleForPrefs(context, runImmediate = nowOn)
                    }
                    feedback.show(
                        text = if (nowOn) {
                            "Reminder on: ${libraryTarget.title}"
                        } else {
                            "Reminder off: ${libraryTarget.title}"
                        }
                    )
                }
            )
        }

        val alreadyOffered = actions.any { it.label == "Play Manually" }
        val alreadyOfferedFromBeginning = actions.any { it.label == "Play from Beginning" }
        actions.forEach { action ->
            add(action)
            if (action.label == "Go to Details") {
                if (!alreadyOffered) {
                    add(
                        PosterContextAction(
                            label = "Play Manually",
                            description = "Pick a source instead of auto-selecting"
                        ) {
                            action.onClick()
                        }
                    )
                }
                if (!alreadyOfferedFromBeginning) {
                    add(
                        PosterContextAction(
                            label = "Play from Beginning",
                            description = "Start this title over, ignoring saved progress"
                        ) {
                            PlayFromBeginningSelection.request()
                            action.onClick()
                        }
                    )
                }
            }
        }

        hideTarget?.let { target ->
            add(
                PosterContextAction(
                    label = "Hide everywhere",
                    description = "Remove this title from every screen",
                    isDestructive = true
                ) {
                    onBeforeHide?.invoke()
                    val hiddenKeys = HiddenTitles.keysFor(target.mediaType, target.ids)
                    val stored = HiddenTitles.hide(
                        context = context,
                        title = target.title,
                        mediaType = target.mediaType,
                        posterUrl = target.posterUrl,
                        ids = target.ids,
                        year = target.year
                    )
                    if (stored) {
                        feedback.show(
                            text = "Hidden: ${target.title}",
                            actionLabel = "UNDO",
                            onAction = { HiddenTitles.unhide(context, hiddenKeys) }
                        )
                    }
                    onDismiss()
                }
            )
        }
    }.sortedBy { contextMenuSectionRank(it) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = KBShapePanel,
        containerColor = KBSurfaceRaised,
        contentColor = KBTextHi,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = KBTextLo.copy(alpha = 0.6f))
        }
    ) {
        KBSheetContent(
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            KBPanelHeading(title = title, subtitle = subtitle)
            Spacer(modifier = Modifier.height(2.dp))
            rows.forEach { action ->
                SheetActionRow(
                    label = action.label,
                    // Kept off enabled rows (see the class comment); a disabled
                    // row needs it to say why it cannot be pressed.
                    description = if (action.isEnabled) null else action.description,
                    isDestructive = action.isDestructive,
                    isEnabled = action.isEnabled,
                    onClick = {
                        if (action.label == "Play Manually") {
                            ManualSourceSelection.request()
                        }
                        action.onClick()
                    }
                )
            }
            // Room below the last row, so the panel's corner and the last row
            // are not the same line on a tall menu.
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // The picker behind "Add to list…", hosted here so a menu can never offer
    // that row without it - the same single-owner rule as the TV menu.
    libraryPickTarget?.let { target ->
        LibraryAddToListDialog(
            mediaType = target.mediaType,
            imdbId = target.imdbId,
            tmdbId = target.tmdbId,
            title = target.title,
            year = target.year,
            posterUrl = target.posterUrl,
            onDismiss = {
                libraryPickTarget = null
                onDismiss()
            }
        )
    }
}

/**
 * One row of the sheet: the label and a full-width tap target.
 *
 * A destructive row is drawn in [KBDanger] rather than filled red; the same
 * affordance the TV row and [KBButton] use, because hiding a title is a
 * deliberate act, not an alarm.
 *
 * A disabled row ([isEnabled] false) is drawn in [KBTextLo], takes no press, and
 * prints its [description] under the label - the reason it is offering nothing
 * is the reason it is on screen at all.
 */
@Composable
private fun SheetActionRow(
    label: String,
    description: String? = null,
    isDestructive: Boolean,
    isEnabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(KBShapeCard)
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            color = when {
                !isEnabled -> KBTextLo
                isDestructive -> KBDanger
                else -> KBTextHi
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        description?.takeIf { it.isNotBlank() }?.let { line ->
            Text(
                text = line,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
