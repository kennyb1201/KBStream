package com.kennyb1201.kbstream.ui.profiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Create/edit profiles: name field, custom uploaded avatar, 8 generic
 * avatar colors, delete. TV-first: D-pad through controls, on-screen
 * keyboard via KBTextField; opened from the picker's Manage tile or
 * Settings → Profiles (manage-mode chip row picks the target profile).
 */
@Composable
fun ProfileEditScreen(
    editingProfileId: String? = null,
    onDone: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val profiles by ProfileManager.profiles.collectAsState()

    // Manage mode: when opened with a null id (Settings entry point or the
    // picker's Manage tile), an internal chip row switches between editing
    // an existing profile and creating a new one. With no profiles yet the
    // screen stays the plain "New profile" form.
    var effectiveEditId by remember(editingProfileId) {
        mutableStateOf<String?>(editingProfileId)
    }
    val editing = profiles.firstOrNull { it.id == effectiveEditId }

    var name by remember(effectiveEditId) {
        mutableStateOf(editing?.name.orEmpty())
    }
    var avatarIndex by remember(effectiveEditId) {
        mutableStateOf(editing?.avatarIndex ?: 0)
    }
    // Picked-but-not-yet-saved avatar (file:// cache URL). Rendered
    // immediately; bound to the profile on Save via finalizeAvatar().
    var pendingAvatarUrl by remember(effectiveEditId) {
        mutableStateOf<String?>(null)
    }
    // Manually typed remote avatar URL (https://…). Takes precedence on save.
    var avatarUrlInput by remember(effectiveEditId) {
        mutableStateOf("")
    }
    // Tracks whether the custom photo is in effect; selecting a color tile
    // flips this off so a color choice actually replaces the photo on save.
    var useCustomAvatar by remember(effectiveEditId) {
        mutableStateOf(editing?.customAvatarUrl != null || editing?.avatarData != null)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingAvatarUrl = ProfileManager.importAvatarImage(context, uri)
            avatarUrlInput = ""
            useCustomAvatar = true
        }
    }

    val nameRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameRequester.requestFocus() }

    val shownCustomUrl = when {
        avatarUrlInput.isNotBlank() -> avatarUrlInput.trim()
        useCustomAvatar -> pendingAvatarUrl ?: editing?.customAvatarUrl ?: editing?.avatarData
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Manage-mode header row: chips to pick which profile to edit or
        // start a new one. Only shown when opened WITHOUT a specific id
        // (the picker's per-tile edit path passes an id and skips this).
        if (editingProfileId == null && profiles.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .padding(top = 20.dp)
                    .focusGroup()
            ) {
                profiles.forEach { p ->
                    ProfileChip(
                        label = p.name,
                        selected = effectiveEditId == p.id,
                        onClick = {
                            effectiveEditId = p.id
                        }
                    )
                }
                ProfileChip(
                    label = "+ New",
                    selected = effectiveEditId == null,
                    onClick = {
                        effectiveEditId = null
                        name = ""
                        avatarIndex = 0
                        pendingAvatarUrl = null
                        avatarUrlInput = ""
                        useCustomAvatar = false
                    }
                )
            }
        }

        Text(
            text = if (editing == null) "New profile" else "Edit profile",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = KBTextHi
        )

        KBTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Profile name",
            focusRequester = nameRequester,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .padding(top = 24.dp)
        )

        // Avatar row: custom upload tile first, then the 8 generic colors
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(top = 28.dp)
                .focusGroup()
        ) {
            // Custom avatar upload / preview tile
            Surface(
                onClick = {
                    imagePicker.launch("image/*")
                    useCustomAvatar = true
                },
                scale = ClickableSurfaceDefaults.scale(
                    focusedScale = 1.1f
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = androidx.compose.foundation.BorderStroke(
                            3.dp, KBTextHi
                        ),
                        shape = CircleShape
                    )
                )
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (shownCustomUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(shownCustomUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Custom avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 2.dp,
                                    color = KBTextHi,
                                    shape = CircleShape
                                )
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1D2530))
                                .border(
                                    width = 1.dp,
                                    color = KBAccent.copy(alpha = 0.5f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "＋", fontSize = 26.sp, color = KBTextHi)
                        }
                    }
                }
            }

            repeat(ProfileManager.AVATAR_COUNT) { index ->
                val (bg, fg) = ProfileManager.AVATAR_COLORS[index]
                val selected = avatarIndex == index
                Surface(
                    onClick = {
                        avatarIndex = index
                        if (pendingAvatarUrl != null) pendingAvatarUrl = null
                        avatarUrlInput = ""
                        useCustomAvatar = false
                    },
                    scale = ClickableSurfaceDefaults.scale(
                        focusedScale = 1.1f
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = androidx.compose.foundation.BorderStroke(
                                3.dp, if (selected) KBTextHi else KBAccent
                            ),
                            shape = CircleShape
                        )
                    )
                ) {
                    Box(
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

        // Remote avatar URL — type or paste a direct link to an image.
        KBTextField(
            value = avatarUrlInput,
            onValueChange = {
                avatarUrlInput = it
                if (it.isNotBlank()) {
                    pendingAvatarUrl = null
                    useCustomAvatar = true
                }
            },
            placeholder = "Avatar image URL (optional)",
            keyboardType = KeyboardType.Uri,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .padding(top = 16.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 32.dp)
        ) {
            ProfileActionButton(
                label = "Save",
                enabled = name.isNotBlank()
            ) {
                // Precedence: typed URL > uploaded image > color/clear.
                val typedUrl = avatarUrlInput.trim().takeIf { it.startsWith("http") }
                if (editing == null) {
                    val created = ProfileManager.createAndMigrateLegacy(context, name, avatarIndex)
                    when {
                        typedUrl != null ->
                            ProfileManager.finalizeAvatar(context, created.id, typedUrl)
                        pendingAvatarUrl != null ->
                            ProfileManager.finalizeAvatar(context, created.id, pendingAvatarUrl)
                    }
                } else {
                    ProfileManager.rename(context, editing.id, name, avatarIndex)
                    when {
                        typedUrl != null ->
                            ProfileManager.finalizeAvatar(context, editing.id, typedUrl)
                        pendingAvatarUrl != null ->
                            ProfileManager.finalizeAvatar(context, editing.id, pendingAvatarUrl)
                        !useCustomAvatar &&
                            (editing.customAvatarUrl != null || editing.avatarData != null) ->
                            ProfileManager.setCustomAvatar(context, editing.id, null)
                    }
                }
                onDone()
            }

            ProfileActionButton(
                label = "Cancel",
                enabled = true
            ) {
                onDone()
            }

            if (shownCustomUrl != null) {
                ProfileActionButton(
                    label = "Remove photo",
                    enabled = true
                ) {
                    pendingAvatarUrl = null
                    avatarUrlInput = ""
                    useCustomAvatar = false
                    editing?.let {
                        ProfileManager.setCustomAvatar(context, it.id, null)
                    }
                }
            }

            if (editing != null && profiles.size > 1) {
                ProfileActionButton(
                    label = "Delete",
                    enabled = true
                ) {
                    ProfileManager.delete(context, editing.id)
                    onDone()
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

/** Chip-style selector for the manage-mode "which profile" row. */
@Composable
private fun ProfileChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) KBAccent.copy(alpha = 0.25f) else KBSurfaceRaised,
            contentColor = if (selected) KBTextHi else KBTextLo,
            focusedContainerColor = KBAccent.copy(alpha = 0.45f),
            focusedContentColor = KBTextHi
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = RoundedCornerShape(18.dp)
            )
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

/** TV-focusable action button (mirrors SyncSection's SyncActionButton). */
@Composable
private fun ProfileActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (enabled) {
        KBCard(onClick = onClick) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.50f)
            )
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = KBTextLo.copy(alpha = 0.50f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}
