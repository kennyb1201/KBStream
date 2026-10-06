package com.kennyb1201.kbstream.ui.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.InstalledAddon
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * Copies add-ons from another profile onto this one.
 *
 * A profile's add-ons live in its own scoped store, so a guest (or any new)
 * profile starts empty and the viewer would otherwise have to re-add every
 * manifest by hand. This lists the other profiles, shows the picked one's
 * add-ons, and merges the chosen ones in - already-installed manifests are
 * skipped by the importer, so re-running it is safe.
 *
 * Selection defaults to the whole source list (the common case is "give me
 * everything this profile has"); tapping a row removes it from the copy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CopyAddonsDialog(
    profiles: List<ProfileManager.Profile>,
    loadAddons: (String) -> List<InstalledAddon>,
    onImport: (List<InstalledAddon>) -> Unit,
    onDismiss: () -> Unit
) {
    // This dialog is taller than a TV screen at its natural content size
    // (heading, blurb, profile chips, list, buttons), and a Dialog CLIPS what
    // does not fit instead of scrolling: the heading lost its top half and the
    // COPY SELECTED / CANCEL row lost its bottom, which is what "the buttons
    // are clipped" was. Bounding the column to the screen and letting the LIST
    // flex (below) keeps every control whole - the list is the only part that
    // can give up space without hiding a control, and it scrolls on its own.
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp - 40.dp

    var sourceId by remember { mutableStateOf(profiles.firstOrNull()?.id) }
    val sourceAddons = remember(sourceId) {
        sourceId?.let(loadAddons).orEmpty()
    }
    var selected by remember(sourceId) {
        mutableStateOf<Set<String>>(sourceAddons.map { it.manifestUrl }.toSet())
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .heightIn(max = maxDialogHeight)
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                .padding(22.dp)
        ) {
            Text(
                text = "COPY ADD-ONS",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Pick a profile, then the add-ons to add to this one. " +
                    "Anything already installed here is skipped.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )

            if (profiles.isEmpty()) {
                Text(
                    text = "There is no other profile to copy from.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // Wraps rather than squeezing: a Row hands each chip after the
                // first whatever width is left, so a profile list longer than
                // the dialog is wide would end in chips half a letter across.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    profiles.forEach { profile ->
                        val isSelected = profile.id == sourceId
                        KBCard(onClick = { sourceId = profile.id }) {
                            Text(
                                text = profile.name,
                                color = if (isSelected) KBAccent else KBTextHi,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else null,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (sourceAddons.isEmpty()) {
                    Text(
                        text = "That profile has no add-ons installed.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            // The row that gives up space when the screen is
                            // short: fill = false so a short list stays short
                            // and the dialog stays compact.
                            .weight(1f, fill = false)
                    ) {
                        items(sourceAddons, key = { it.manifestUrl }) { addon ->
                            val checked = addon.manifestUrl in selected
                            KBCard(
                                onClick = {
                                    selected = if (checked) {
                                        selected - addon.manifestUrl
                                    } else {
                                        selected + addon.manifestUrl
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = if (checked) "[x]" else "[  ]",
                                        color = if (checked) KBAccent else KBTextLo,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = addon.displayName,
                                        color = KBTextHi,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                ActionButton(
                    label = "COPY SELECTED",
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        onImport(sourceAddons.filter { it.manifestUrl in selected })
                    }
                )
                ActionButton(label = "CANCEL", onClick = onDismiss)
            }
        }
    }
}
