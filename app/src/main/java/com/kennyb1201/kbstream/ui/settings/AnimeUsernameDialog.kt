package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The connect row for one anime tracker (spec 2026-11-13, F2).
 *
 * v1 has no OAuth: a username is the whole connection, and the list it names has
 * to be public. The dialog says so instead of pretending a private profile is an
 * empty list - the honest-state rule the Settings row already carries.
 *
 * One field, three answers: SAVE with a name, CLEAR to disconnect, CANCEL to
 * leave it as it was. SAVE takes focus, so a D-pad press that lands in the dialog
 * cannot wipe a saved username by itself.
 */
@Composable
internal fun AnimeUsernameDialog(
    service: String,
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var value by remember(service) { mutableStateOf(current.orEmpty()) }
    val saveFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        var landed = false
        var attempts = 0
        while (!landed && attempts < 12) {
            androidx.compose.runtime.withFrameNanos { }
            landed = runCatching { saveFocusRequester.requestFocus() }.getOrDefault(false)
            attempts++
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "$service username") {
            Text(
                text = "KBStream reads this profile's PUBLIC anime list. Nothing is " +
                    "sent to $service and no password is stored - connecting an " +
                    "account properly (private lists and progress writes) is the " +
                    "next step.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(10.dp))

            KBTextField(
                value = value,
                onValueChange = { entered -> value = entered },
                placeholder = "$service username",
                modifier = Modifier.fillMaxWidth(),
                onDone = {
                    keyboard?.hide()
                    onSave(value.trim().takeIf { it.isNotEmpty() })
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KBCard(
                    onClick = {
                        keyboard?.hide()
                        onSave(value.trim().takeIf { it.isNotEmpty() })
                    },
                    modifier = Modifier
                        .focusRequester(saveFocusRequester)
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "SAVE",
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }

                KBCard(
                    onClick = {
                        keyboard?.hide()
                        onSave(null)
                    }
                ) {
                    Text(
                        text = "CLEAR",
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                KBCard(onClick = onDismiss) {
                    Text(
                        text = "CANCEL",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "A private profile reads nothing until OAuth lands.",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * The Settings row's description for one anime tracker.
 *
 * Three states, and they are deliberately different sentences: not connected
 * (nothing is read, and nothing on the rails changes), connected (that public
 * list feeds Continue Watching and Upcoming), and the private-profile caveat that
 * applies to the connected case. Pure so the wording is testable - the honest
 * state is the requirement (S5), not decoration.
 */
internal fun animeTrackerDescription(service: String, username: String?): String {
    val name = username?.trim().orEmpty()
    if (name.isEmpty()) {
        return "Not connected. Enter your $service username to read your public " +
            "anime list: in-progress anime join Continue Watching and finished ones " +
            "feed Upcoming. No password is needed, and nothing is written to $service."
    }
    return "Reading the public anime list of '$name'. In-progress anime join " +
        "Continue Watching and finished ones feed Upcoming. A private profile " +
        "reads nothing until signing in is added; CLEAR to disconnect."
}

/** The row label: the service, and which username is connected. */
internal fun animeTrackerRowLabel(service: String, username: String?): String {
    val name = username?.trim().orEmpty()
    return if (name.isEmpty()) service else "$service \u2014 $name"
}
