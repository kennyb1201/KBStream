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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.sync.KidsMode
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.rememberKBFeedback
import com.kennyb1201.kbstream.ui.components.KBPasteChip
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBFocusTile
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
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
    val feedback = rememberKBFeedback()
    val profiles by ProfileManager.profiles.collectAsStateWithLifecycle()

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
    // PIN state: current entry (for changing an existing PIN), new PIN, and
    // a confirm step. Blank everywhere = no PIN. Saved with the profile.
    var currentPinInput by remember(effectiveEditId) { mutableStateOf("") }
    var newPinInput by remember(effectiveEditId) { mutableStateOf("") }
    var confirmPinInput by remember(effectiveEditId) { mutableStateOf("") }
    // Kids Mode: on/off toggle plus the chosen rating ceiling. The stored
    // value only ever carries one of the three legal ceilings (PG-13 / PG
    // / G "or lower"); OFF is null on the profile.
    var kidsModeOn by remember(effectiveEditId) {
        mutableStateOf(editing?.kidsMaxAge != null)
    }
    var kidsLevel by remember(effectiveEditId) {
        mutableStateOf(
            editing?.kidsMaxAge.let(KidsMode::normalize)
                ?: ProfileManager.KIDS_DEFAULT_MAX_AGE
        )
    }
    // Kids Mode option toggles (each only read when kids mode is on).
    var kidsHideAddons by remember(effectiveEditId) {
        mutableStateOf(editing?.kidsHideAddons ?: true)
    }
    var kidsLockLiveTv by remember(effectiveEditId) {
        mutableStateOf(editing?.kidsLockLiveTv ?: true)
    }
    var kidsRequirePinToExit by remember(effectiveEditId) {
        mutableStateOf(editing?.kidsRequirePinToExit ?: false)
    }
    var kidsDailyLimitHours by remember(effectiveEditId) {
        // Stored as minutes; edited in whole hours (0 = no limit).
        mutableStateOf((editing?.kidsDailyLimitMinutes ?: 0) / 60)
    }
    var kidsBedtime by remember(effectiveEditId) {
        // Stored as minutes-after-midnight; edited as (hour, minute) on a
        // 12h clock. 0 = no bedtime.
        val mins = editing?.kidsBedtimeMinutes ?: 0
        mutableStateOf(if (mins == 0) 21 else mins / 60)   // default 9 PM
    }
    var kidsBedtimeMinute by remember(effectiveEditId) {
        val mins = editing?.kidsBedtimeMinutes ?: 0
        mutableStateOf(if (mins == 0) 0 else mins % 60)
    }
    var kidsBedtimeOn by remember(effectiveEditId) {
        mutableStateOf((editing?.kidsBedtimeMinutes ?: 0) > 0)
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
            .padding(48.dp)
            .verticalScroll(rememberScrollState())
            // Breathing room for the clip edge. Spacing placed BEFORE
            // verticalScroll() leaves the viewport's clip edge sitting
            // exactly on the last row, and the Save/Cancel cards scale
            // 1.03x with a focused glow that draws outside their bounds —
            // so the bottom of the focused button gets sheared off at max
            // scroll. Padding INSIDE the viewport gives the ring somewhere
            // to grow (same fix as the profile picker's tile row).
            .padding(bottom = 32.dp)
            .focusGroup(),
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
            // Fire TV's keyboard is a full-screen overlay that takes the D-pad,
            // so a field that opens it on focus makes everything below it
            // unreachable. On this screen focus stays silent and OK opens it.
            openKeyboardOnFocus = false,
            focusRequester = nameRequester,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .padding(top = 24.dp)
        )

        Text(
            text = "Press OK on a field to type in it.",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp)
        )

        // ── Parental PIN ─────────────────────────────────────────────
        Text(
            text = "Parental lock",
            style = MaterialTheme.typography.titleMedium,
            color = KBTextHi,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(
            text = if (editing?.pinHash != null)
                "A PIN is set. Enter the current PIN to change or remove it."
            else
                "Require a 4-digit PIN to open this profile from Who's Watching.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (editing?.pinHash != null) {
            KBTextField(
                value = currentPinInput,
                onValueChange = { currentPinInput = it.filter(Char::isDigit).take(4) },
                placeholder = "Current PIN",
                openKeyboardOnFocus = false,
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .padding(top = 8.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.6f)
        ) {
            KBTextField(
                value = newPinInput,
                onValueChange = { newPinInput = it.filter(Char::isDigit).take(4) },
                placeholder = "New PIN (4 digits)",
                openKeyboardOnFocus = false,
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            KBTextField(
                value = confirmPinInput,
                onValueChange = { confirmPinInput = it.filter(Char::isDigit).take(4) },
                placeholder = "Confirm PIN",
                openKeyboardOnFocus = false,
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
        }

        // ── Kids Mode ──────────────────────────────────────────────
        Text(
            text = "Kids Mode",
            style = MaterialTheme.typography.titleMedium,
            color = KBTextHi,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(
            text = if (kidsModeOn)
                "Search, discover and home rails only show titles rated at or " +
                    "below the chosen rating for this profile."
            else
                "Turn on to filter search and discover to kid-friendly content " +
                    "with a maximum rating.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(top = 10.dp)
                .focusGroup()
        ) {
            ProfileChip(
                label = "Off",
                selected = !kidsModeOn,
                onClick = { kidsModeOn = false }
            )
            ProfileChip(
                label = "PG-13 & under",
                selected = kidsModeOn && kidsLevel == KidsMode.CEIL_PG13,
                onClick = {
                    kidsModeOn = true
                    kidsLevel = KidsMode.CEIL_PG13
                }
            )
            ProfileChip(
                label = "PG & under",
                selected = kidsModeOn && kidsLevel == KidsMode.CEIL_PG,
                onClick = {
                    kidsModeOn = true
                    kidsLevel = KidsMode.CEIL_PG
                }
            )
            ProfileChip(
                label = "G & under",
                selected = kidsModeOn && kidsLevel == KidsMode.CEIL_G,
                onClick = {
                    kidsModeOn = true
                    kidsLevel = KidsMode.CEIL_G
                }
            )
        }

        // ── Kids Mode options (visible only while Kids Mode is on) ──
        if (kidsModeOn) {
            Text(
                text = "Kids Mode options",
                style = MaterialTheme.typography.titleSmall,
                color = KBTextLo,
                modifier = Modifier.padding(top = 18.dp)
            )

            KidsOptionChipRow(
                title = "Lock add-ons",
                hint = "Hides the Add-ons screen for this profile so new " +
                    "catalogs can't be installed.",
                options = listOf("On" to true, "Off" to false),
                selected = kidsHideAddons,
                onSelect = { kidsHideAddons = it }
            )
            KidsOptionChipRow(
                title = "Kid-safe Live TV",
                hint = "Hides live TV channel groups that don't look " +
                    "kid-focused (cartoons, family, kids).",
                options = listOf("On" to true, "Off" to false),
                selected = kidsLockLiveTv,
                onSelect = { kidsLockLiveTv = it }
            )
            KidsOptionChipRow(
                title = "PIN to leave profile",
                hint = "Asks for this profile's PIN before switching to a " +
                    "different profile. Requires a PIN below.",
                options = listOf("On" to true, "Off" to false),
                selected = kidsRequirePinToExit,
                onSelect = { kidsRequirePinToExit = it }
            )
            KidsOptionChipRow(
                title = "Daily watch limit",
                hint = "Locks this profile once the daily watch time is " +
                    "used up. A PIN unlocks it early.",
                options = listOf(
                    "Off" to 0,
                    "1h" to 1,
                    "2h" to 2,
                    "3h" to 3,
                    "4h" to 4
                ),
                selected = kidsDailyLimitHours,
                onSelect = { kidsDailyLimitHours = it }
            )
            KidsOptionChipRow(
                title = "Bedtime lock",
                hint = "Locks this profile from bedtime until 4:00 AM. " +
                    "A PIN unlocks it early.",
                options = (0..11).map { h ->
                    val label = when (h) {
                        0 -> "Off"
                        9 -> "9 PM"
                        else -> "$h PM"
                    }
                    label to h
                },
                selected = if (kidsBedtimeOn) kidsBedtime else -1,
                onSelect = { picked ->
                    if (picked < 0) {
                        kidsBedtimeOn = false
                    } else {
                        kidsBedtimeOn = true
                        kidsBedtime = picked
                    }
                }
            )
        }

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
                    focusedScale = KBFocusTile,
                    pressedScale = KBFocusPressed
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
                                .background(KBSurfaceRaised)
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
                        focusedScale = KBFocusTile,
                        pressedScale = KBFocusPressed
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .padding(top = 16.dp)
        ) {
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
                // Editable as soon as it takes focus, unlike the rest of this
                // form. A remote keyboard app (ATV Tools) delivers text through
                // the TV's IME connection, and a field held read-only until OK
                // is pressed has no connection at all — so phone-typed or
                // phone-pasted text went nowhere. This field exists to have a
                // URL put in it, so it takes one as soon as it is focused.
                openKeyboardOnFocus = true,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Second path, independent of the IME: reads THIS device's
            // clipboard, so a URL copied over from the phone (ATV Tools has a
            // clipboard sender) can be dropped straight in with one press.
            KBPasteChip(
                onPaste = { pasted ->
                    val clean = pasted.trim()
                    if (clean.isNotEmpty()) {
                        avatarUrlInput = clean
                        pendingAvatarUrl = null
                        useCustomAvatar = true
                    }
                }
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 32.dp)
        ) {
            ProfileActionButton(
                label = "Save",
                enabled = name.isNotBlank()
            ) {
                // PIN validation before anything else — on failure keep the
                // screen open so the user can correct the entry.
                val pinChanged = newPinInput.isNotBlank() || confirmPinInput.isNotBlank()
                val wantsClear = newPinInput.isBlank() && confirmPinInput.isBlank() &&
                    currentPinInput.isNotBlank()
                val pinTargetId = editing?.id
                if (editing != null && editing.pinHash != null) {
                    val currentOk = newPinInput.isBlank() ||
                        ProfileManager.verifyPin(editing, currentPinInput)
                    val clearOk = !wantsClear || currentPinInput.isNotBlank()
                    val matchOk = newPinInput.isBlank() || newPinInput == confirmPinInput
                    if (!currentOk || !clearOk || !matchOk) {
                        // Through the app's own channel rather than a system
                        // Toast: same wording, but it is styled like the rest
                        // of the app, readable from across the room, and read
                        // aloud by TalkBack.
                        feedback.show(
                            text = when {
                                !currentOk -> "Current PIN is incorrect"
                                !clearOk -> "Enter the current PIN to remove the lock"
                                else -> "PINs don't match"
                            },
                            isError = true
                        )
                        return@ProfileActionButton
                    }
                }
                // Precedence: typed URL > uploaded image > color/clear.
                val typedUrl = avatarUrlInput.trim().takeIf { it.startsWith("http") }
                val kidsTargetId: String?
                if (editing == null) {
                    val created = ProfileManager.createAndMigrateLegacy(context, name, avatarIndex)
                    kidsTargetId = created.id
                    when {
                        typedUrl != null ->
                            ProfileManager.finalizeAvatar(context, created.id, typedUrl)
                        pendingAvatarUrl != null ->
                            ProfileManager.finalizeAvatar(context, created.id, pendingAvatarUrl)
                    }
                } else {
                    kidsTargetId = editing.id
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
                // Apply Kids Mode after the profile itself is saved.
                kidsTargetId?.let { id ->
                    ProfileManager.setKidsMaxAge(
                        context,
                        id,
                        if (kidsModeOn) kidsLevel else null
                    )
                    ProfileManager.setKidsOptions(
                        context,
                        id,
                        hideAddons = kidsHideAddons,
                        lockLiveTv = kidsLockLiveTv,
                        requirePinToExit = kidsRequirePinToExit,
                        dailyLimitMinutes = kidsDailyLimitHours * 60,
                        bedtimeMinutes = if (kidsBedtimeOn) {
                            val h = if (kidsBedtime == 0) 12 else kidsBedtime
                            ((h + 12) % 24) * 60 + kidsBedtimeMinute
                        } else 0
                    )
                }
                // Apply the PIN after the profile itself is saved.
                when {
                    pinTargetId != null && pinChanged ->
                        ProfileManager.setPin(context, pinTargetId, newPinInput)
                    pinTargetId != null && wantsClear ->
                        ProfileManager.setPin(context, pinTargetId, null)
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
                    enabled = true,
                    danger = true
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

/**
 * One Kids Mode option: bold title, hint line, and a single-select chip
 * row (generic over the option value type — booleans for toggles, Ints
 * for the hour pickers).
 */
@Composable
private fun <T> KidsOptionChipRow(
    title: String,
    hint: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = KBTextHi,
        modifier = Modifier.padding(top = 12.dp)
    )
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = KBTextLo
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(top = 6.dp)
            .focusGroup()
    ) {
        options.forEach { (label, value) ->
            ProfileChip(
                label = label,
                selected = selected == value,
                onClick = { onSelect(value) }
            )
        }
    }
}

/** Chip-style selector used across the profile editor. */
@Composable
private fun ProfileChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        // tv-material3 clickable Surface wants a ClickableSurfaceShape,
        // not a foundation Shape (non-clickable Surfaces do take Shape —
        // hence the disabled-pill usages elsewhere compile fine).
        shape = ClickableSurfaceDefaults.shape(shape = KBShapePanel),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusChip,
            pressedScale = KBFocusPressed
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) KBAccent.copy(alpha = 0.25f) else KBSurfaceRaised,
            contentColor = if (selected) KBTextHi else KBTextLo,
            focusedContainerColor = KBAccent.copy(alpha = 0.45f),
            focusedContentColor = KBTextHi
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = KBShapePanel
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

/** TV-focusable action button (mirrors SyncSection's SyncActionButton).
 *  [danger] tints the label KBDanger (destructive-action affordance, same
 *  convention as PosterContextMenu's destructive rows). */
@Composable
private fun ProfileActionButton(
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    if (enabled) {
        KBCard(onClick = onClick) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (danger) KBDanger else Color.Unspecified,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    } else {
        Surface(
            shape = KBShapeCard,
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
