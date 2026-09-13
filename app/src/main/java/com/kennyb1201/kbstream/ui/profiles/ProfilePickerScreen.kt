package com.kennyb1201.kbstream.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Full-screen profile picker shown at launch when profiles exist. D-pad
 * between avatar circles; OK switches to that profile and enters Home.
 * "Manage" opens creation/editing. Tiles wrap into rows of 5 so a large
 * number of profiles still fits on screen.
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

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 40.dp)
                .verticalScroll(rememberScrollState())
                .focusGroup()
        ) {
            val tiles = profiles.map { profile ->
                PickerTile(
                    name = profile.name,
                    avatarIndex = profile.avatarIndex,
                    customAvatarUrl = profile.customAvatarUrl ?: profile.avatarData,
                    selected = active?.id == profile.id,
                    onClick = {
                        ProfileManager.setActive(pickerContext, profile)
                        onSelect()
                    }
                )
            } + PickerTile(
                name = "Manage",
                avatarIndex = -1,
                customAvatarUrl = null,
                selected = false,
                onClick = onManage
            )

            tiles.chunked(PROFILES_PER_ROW).forEachIndexed { rowIndex, rowTiles ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    rowTiles.forEachIndexed { colIndex, tile ->
                        ProfileAvatarTile(
                            name = tile.name,
                            avatarIndex = tile.avatarIndex,
                            customAvatarUrl = tile.customAvatarUrl,
                            selected = tile.selected,
                            focusRequester = if (rowIndex == 0 && colIndex == 0) firstRequester else null,
                            onClick = tile.onClick
                        )
                    }
                }
            }
        }
    }
}

private const val PROFILES_PER_ROW = 5

/** Render spec for one picker tile — plain data, invoked inline per row. */
private data class PickerTile(
    val name: String,
    val avatarIndex: Int,
    val customAvatarUrl: String?,
    val selected: Boolean,
    val onClick: () -> Unit
)

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
    val tileContext = androidx.compose.ui.platform.LocalContext.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(3.dp, KBAccent),
                    shape = CircleShape
                )
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
                if (customAvatarUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(tileContext)
                            .data(customAvatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = KBTextHi,
                                shape = CircleShape
                            )
                    )
                } else if (avatarIndex >= 0) {
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
