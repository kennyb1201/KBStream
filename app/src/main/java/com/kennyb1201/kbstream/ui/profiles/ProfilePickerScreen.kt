package com.kennyb1201.kbstream.ui.profiles

import androidx.compose.foundation.Background
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Full-screen profile picker shown at launch when profiles exist. D-pad
 * between avatar circles; OK switches to that profile and enters Home.
 * "Manage" opens creation/editing.
 */
@Composable
fun ProfilePickerScreen(
    onSelect: () -> Unit,
    onManage: () -> Unit
) {
    val pickerContext = androidx.compose.ui.platform.LocalContext.current
    val profiles by ProfileManager.profiles.collectAsState()
    val active by ProfileManager.activeProfile.collectAsState()

    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Who's watching?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = KBTextHi
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .padding(top = 40.dp)
                .focusGroup()
        ) {
            profiles.forEachIndexed { index, profile ->
                val isRequester = index == 0
                ProfileAvatarTile(
                    name = profile.name,
                    avatarIndex = profile.avatarIndex,
                    customAvatarUrl = profile.customAvatarUrl,
                    selected = active?.id == profile.id,
                    focusRequester = if (isRequester) firstRequester else null,
                    onClick = {
                        ProfileManager.setActive(pickerContext, profile)
                        onSelect()
                    }
                )
            }

            ProfileAvatarTile(
                name = "Manage",
                avatarIndex = -1,
                customAvatarUrl = null,
                selected = false,
                focusRequester = null,
                onClick = onManage
            )
        }
    }
}

@Composable
private fun ProfileAvatarTile(
    name: String,
    avatarIndex: Int,
    customAvatarUrl: String?,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            colors = ClickableSurfaceDefaults.colors(
                container = Color.Transparent,
                focusedContainer = Color.Transparent,
                pressedContainer = Color.Transparent
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = BorderStroke(3.dp, KBAccent)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (avatarIndex >= 0) {
                    val (bg, fg) = ProfileManager.AVATAR_COLORS[
                        avatarIndex.coerceIn(0, ProfileManager.AVATAR_COUNT - 1)
                    ]
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(Color(bg))
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = KBTextHi,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.take(1).uppercase(),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(fg)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1D2530)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✎",
                            fontSize = 30.sp,
                            color = KBTextLo
                        )
                    }
                }
            }
        }

        Text(
            text = name,
            color = if (focused) KBTextHi else KBTextLo,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
