package com.kennyb1201.kbstream.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Create/edit profiles: name field, 8 generic avatar colors, delete.
 * TV-first: D-pad through avatars, on-screen keyboard via OutlinedTextField.
 */
@Composable
fun ProfileEditScreen(
    editingProfileId: String? = null,
    onDone: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val profiles by ProfileManager.profiles.collectAsState()
    val editing = profiles.firstOrNull { it.id == editingProfileId }

    var name by remember(editingProfileId) {
        mutableStateOf(editing?.name.orEmpty())
    }
    var avatarIndex by remember(editingProfileId) {
        mutableStateOf(editing?.avatarIndex ?: 0)
    }

    val nameRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (editing == null) "New profile" else "Edit profile",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = KBTextHi
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .padding(top = 24.dp)
                .focusRequester(nameRequester)
        )

        // Avatar color choices
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(top = 28.dp)
                .focusGroup()
        ) {
            repeat(ProfileManager.AVATAR_COUNT) { index ->
                val (bg, fg) = ProfileManager.AVATAR_COLORS[index]
                val selected = avatarIndex == index
                androidx.tv.material3.Surface(
                    onClick = { avatarIndex = index },
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
                        focusedScale = 1.1f
                    ),
                    border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                        focusedBorder = androidx.compose.foundation.BorderStroke(
                            3.dp, if (selected) KBTextHi else KBAccent
                        )
                    )
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(72.dp)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(Color(bg)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.take(1).uppercase().ifBlank { "?" },
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(fg)
                        )
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 32.dp)
        ) {
            Button(
                onClick = {
                    if (editing == null) {
                        ProfileManager.createAndMigrateLegacy(context, name, avatarIndex)
                    } else {
                        ProfileManager.rename(context, editing.id, name, avatarIndex)
                    }
                    onDone()
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = KBAccent)
            ) {
                Text("Save", color = Color.Black)
            }

            OutlinedButton(onClick = onDone) {
                Text("Cancel", color = KBTextHi)
            }

            if (editing != null && profiles.size > 1) {
                OutlinedButton(
                    onClick = {
                        ProfileManager.delete(context, editing.id)
                        onDone()
                    }
                ) {
                    Text("Delete", color = Color(0xFFE57373))
                }
            }
        }

        if (editing == null && profiles.isEmpty()) {
            Text(
                text = "Your existing library (history, add-ons, settings) will be " +
                    "moved into this profile so nothing is lost.",
                color = KBTextLo,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
