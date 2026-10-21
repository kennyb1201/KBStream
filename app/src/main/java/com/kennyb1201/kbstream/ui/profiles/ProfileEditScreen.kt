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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.sync.KidsGate
import com.kennyb1201.kbstream.data.sync.KidsMode
import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.browse.landFocusOn
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.mobile.ProfileEditScreenMobile
import com.kennyb1201.kbstream.ui.components.rememberKBFeedback
import com.kennyb1201.kbstream.ui.components.KBPasteChip
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBFocusTile
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

// ── Bedtime chip domain ─────────────────────────────────────────────────
//
// The row and the stored value used to disagree about their units: the chips
// were PM hours 1..11 while the stored value is minutes-after-midnight, so a
// saved 9 PM (1260) loaded back as the chip value 21, which matches no chip.
// Re-saving from that state produced 9:00 AM, and KidsTimeGuard locks from the
// cutoff until 4 AM - so the profile went from locking 9 PM-4 AM to locking
// 9 AM-4 AM. Every conversion now goes through the two functions below, in one
// domain: the chip.
internal const val BEDTIME_CHIP_OFF = 0
internal const val BEDTIME_CHIP_MIDNIGHT = 12
internal const val BEDTIME_CHIP_DEFAULT = 9

/** Stored minutes-after-midnight -> the chip the row shows as selected. */
internal fun bedtimeChipFor(minutes: Int): Int {
    // 0 is the "no bedtime" value, so it is also the Off chip.
    if (minutes <= 0) return BEDTIME_CHIP_OFF
    val hour = (minutes / 60) % 24
    return when (hour) {
        0 -> BEDTIME_CHIP_MIDNIGHT
        // A noon cutoff is not a bedtime this row can express (it would lock
        // noon-4 AM, most of the day), and it only reaches here from an older
        // bad save. Show Off so the parent picks a real one rather than keep a
        // lock they cannot see.
        12 -> BEDTIME_CHIP_OFF
        // 1..11 AM are the same corruption as the 9 AM field report; they map
        // to their PM twin, which is what the parent originally chose.
        else -> hour % 12
    }
}

/** Chip + the profile's preserved minute -> stored minutes-after-midnight. */
internal fun bedtimeMinutesFor(chip: Int, minute: Int): Int = when (chip) {
    BEDTIME_CHIP_OFF -> 0
    // 24h, not 0: 0 is the Off value and cannot also mean midnight.
    BEDTIME_CHIP_MIDNIGHT -> 24 * 60 + minute.coerceIn(0, 59)
    else -> (chip + 12) * 60 + minute.coerceIn(0, 59)
}

/** The row's labels, in the same domain [bedtimeChipFor] returns. */
internal fun bedtimeChipLabel(chip: Int): String = when (chip) {
    BEDTIME_CHIP_OFF -> "Off"
    BEDTIME_CHIP_MIDNIGHT -> "12 AM"
    else -> "$chip PM"
}

/**
 * Create/edit profiles: name field, custom uploaded avatar, 8 generic
 * avatar colors, delete. TV-first: D-pad through controls, on-screen
 * keyboard via KBTextField; opened from the picker's Manage tile or
 * Settings → Profiles (manage-mode chip row picks the target profile).
 */
@Composable
fun ProfileEditScreen(
    editingProfileId: String? = null,
    // The phone's header arrow: a CANCEL that pops exactly as system back does
    // (no save, no onDone). The TV page draws no back control.
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    // The phone entry: the same profile writes, PIN validation and parent-PIN
    // gates, laid out as a touch form. One branch; the TV page below is
    // unchanged (see ProfileEditScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        ProfileEditScreenMobile(
            editingProfileId = editingProfileId,
            onBack = onBack,
            onDone = onDone
        )
        return
    }

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
    // Guest profile: Home leads with a fixed set of built-in TMDB rails so
    // the profile needs no add-ons. Independent of Kids Mode (a profile can
    // be a kids profile, a guest profile, both, or neither).
    var guestProfile by remember(effectiveEditId) {
        mutableStateOf(editing?.guest ?: false)
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
        // Chip domain (see bedtimeChipFor): 0 = Off, 1..11 = 1 PM..11 PM,
        // 12 = 12 AM. "Off" still needs an hour to fall back on when the row is
        // turned on, so it starts at the 9 PM default.
        val mins = editing?.kidsBedtimeMinutes ?: 0
        mutableStateOf(
            bedtimeChipFor(mins).takeIf { it != BEDTIME_CHIP_OFF }
                ?: BEDTIME_CHIP_DEFAULT
        )
    }
    var kidsBedtimeMinute by remember(effectiveEditId) {
        val mins = editing?.kidsBedtimeMinutes ?: 0
        mutableStateOf(if (mins == 0) 0 else mins % 60)
    }
    var kidsBedtimeOn by remember(effectiveEditId) {
        mutableStateOf((editing?.kidsBedtimeMinutes ?: 0) > 0)
    }
    // The app-level PARENT PIN (see [KidsTimeGuard.setParentPin]), managed here
    // beside the locks it unlocks - and the credential that gates every
    // protective action the editor can take (audit C2/C3/C5/M6/M7).
    //
    // `parentPinPrompt` is true while the dialog is up; `parentPinPromptGated`
    // says the prompt is standing in FRONT of an action (backing out refuses it)
    // rather than being the Parent PIN row's own set/change panel;
    // `parentPinPromptWhy` is the one-line reason shown under the heading; and
    // `parentPinAfterVerify` is the action that runs once the PIN is accepted -
    // the save, the delete, or the switch to another profile's editor.
    var parentPinPrompt by remember(effectiveEditId) { mutableStateOf(false) }
    var parentPinPromptGated by remember(effectiveEditId) { mutableStateOf(false) }
    var parentPinPromptWhy by remember(effectiveEditId) { mutableStateOf<String?>(null) }
    var parentPinAfterVerify by remember(effectiveEditId) {
        mutableStateOf<(() -> Unit)?>(null)
    }
    // Whose own PIN may answer the prompt: the profile being edited, unless the
    // action targets ANOTHER one (the manage-mode chip row, which opens another
    // profile's editor - M7). Null means "the profile on screen".
    var parentPinGateTarget by remember(effectiveEditId) {
        mutableStateOf<ProfileManager.Profile?>(null)
    }
    var parentPinCurrent by remember(effectiveEditId) { mutableStateOf("") }
    var parentPinNew by remember(effectiveEditId) { mutableStateOf("") }
    var parentPinConfirm by remember(effectiveEditId) { mutableStateOf("") }
    var parentPinError by remember(effectiveEditId) { mutableStateOf<String?>(null) }
    // Bumped whenever the parent PIN is written, so the row's own label re-reads
    // it: KidsTimeGuard reads the prefs file directly and is not observable
    // state, so a remember keyed on nothing would keep saying "SET".
    var parentPinRevision by remember(effectiveEditId) { mutableStateOf(0) }
    val parentPinFieldRequester = remember { FocusRequester() }
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

    // Closing the parent-PIN prompt. `refused` is the GATED case: the editor
    // asked for a credential because a protective action was about to happen
    // without one, and the parent backed out - so the action is refused outright
    // rather than applied with its protection quietly dropped (see the gates).
    val closeParentPinPrompt: (Boolean) -> Unit = { refused ->
        val wasGated = parentPinPromptGated
        parentPinPrompt = false
        parentPinPromptGated = false
        parentPinPromptWhy = null
        parentPinAfterVerify = null
        parentPinCurrent = ""
        parentPinNew = ""
        parentPinConfirm = ""
        parentPinError = null
        if (refused && wasGated) {
            feedback.show(
                text = "A parent PIN is needed for that",
                isError = true
            )
        }
    }

    // Raising the prompt for a GATED action. The action runs only after the PIN
    // is accepted ([parentPinAfterVerify]), and `why` is what the parent reads.
    val gateWithParentPin: (String, ProfileManager.Profile?, () -> Unit) -> Unit =
        { why, target, action ->
            parentPinPromptGated = true
            parentPinPromptWhy = why
            parentPinGateTarget = target
            parentPinAfterVerify = action
            parentPinPrompt = true
        }

    // The save itself, as its own lambda rather than inline in the SAVE button:
    // the parent-PIN prompt has to run it too, once the PIN it asked for is set.
    // The labelled return is the validation's early exit - on failure the screen
    // stays open so the entry can be corrected.
    val saveProfile: () -> Unit = save@{
        // PIN validation before anything else.
        val pinChanged = newPinInput.isNotBlank() || confirmPinInput.isNotBlank()
        val wantsClear = newPinInput.isBlank() && confirmPinInput.isBlank() &&
            currentPinInput.isNotBlank()
        // Null until the profile exists: a NEW profile's PIN is set on the
        // created row below, which is what makes the fields work at all on the
        // create form. They used to be validated against `editing` (null) and
        // dropped silently on the floor, so a parent could type a PIN on a new
        // profile, save, and believe it was protected.
        var pinTargetId = editing?.id
        val hasExistingPin = editing?.pinHash != null
        if (pinChanged || wantsClear) {
            // Removing a PIN is a credentialed action like setting one: the
            // CURRENT PIN has to be right either way. This read
            // `clearOk = !wantsClear || currentPinInput.isNotBlank()`, and
            // `wantsClear` already implies a non-blank entry - so the clear path
            // never called verifyPin at all, and typing ANY four digits into
            // "Current PIN" and saving stripped the lock.
            val currentOk = !hasExistingPin ||
                ProfileManager.verifyPin(requireNotNull(editing), currentPinInput)
            // Validation runs whenever a PIN field is non-blank, whether or not
            // the profile already has one. Gating it on `pinHash != null` meant a
            // mismatch on a profile with no PIN yet was written straight through
            // (the mistyped PIN became the PIN), and a blank-new + filled-confirm
            // pair passed and called setPin("") - which CLEARS the lock,
            // unverified.
            val newPinOk = !pinChanged ||
                (newPinInput.length == 4 && newPinInput == confirmPinInput)
            if (!currentOk || !newPinOk) {
                // Through the app's own channel rather than a system Toast: same
                // wording, but it is styled like the rest of the app, readable
                // from across the room, and read aloud by TalkBack.
                feedback.show(
                    text = when {
                        !currentOk -> "Current PIN is incorrect"
                        pinChanged && newPinInput.length != 4 ->
                            "The new PIN must be 4 digits"
                        else -> "PINs don't match"
                    },
                    isError = true
                )
                return@save
            }
        }
        // Precedence: typed URL > uploaded image > color/clear.
        val typedUrl = avatarUrlInput.trim().takeIf { it.startsWith("http") }
        val kidsTargetId: String?
        if (editing == null) {
            val created = ProfileManager.createAndMigrateLegacy(context, name, avatarIndex)
            kidsTargetId = created.id
            // The PIN applies to the row that now exists (see the validation
            // block: it used to be silently dropped here).
            pinTargetId = created.id
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
        // Apply the guest flag after the profile itself is saved.
        kidsTargetId?.let { id ->
            ProfileManager.setGuest(context, id, guestProfile)
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
                    bedtimeMinutesFor(kidsBedtime, kidsBedtimeMinute)
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

    // The prompt's own field takes the D-pad when it opens, through the app's
    // verified retry: the dialog enters the tree this same frame and a fresh
    // FocusRequester answers false until it is attached.
    LaunchedEffect(parentPinPrompt) {
        if (parentPinPrompt) landFocusOn(parentPinFieldRequester)
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
                            // M7: this chip row is how a kid reaches ANOTHER
                            // profile's editor - the enabler for clearing the
                            // parent's PIN, deleting their profile or renaming
                            // it to confuse the gates. A target that carries a
                            // PIN, or that IS a kids sandbox, opens only for the
                            // parent: its own PIN answers, or the parent PIN.
                            if (KidsGate.hasOwnPin(p) || KidsGate.isKids(p)) {
                                gateWithParentPin("Required to edit ${p.name}", p) {
                                    effectiveEditId = p.id
                                }
                            } else {
                                effectiveEditId = p.id
                            }
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

        // The screen's name, through the shared page title (Batch F item 38).
        KBPageTitle(text = if (editing == null) "New profile" else "Edit profile")

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

        // ── Guest profile ──────────────────────────────────────────
        Text(
            text = "Guest profile",
            style = MaterialTheme.typography.titleMedium,
            color = KBTextHi,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(
            text = if (guestProfile)
                "Home leads with a fixed set of built-in rails - the two Top 10 " +
                    "rows, new releases, airing now, trending, popular and top " +
                    "rated - so this profile needs no add-ons."
            else
                "Turn on for a profile that needs no add-ons: Home leads with " +
                    "built-in TMDB rails for movies and shows.",
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
                selected = !guestProfile,
                onClick = { guestProfile = false }
            )
            ProfileChip(
                label = "On",
                selected = guestProfile,
                onClick = { guestProfile = true }
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
                options = (BEDTIME_CHIP_OFF..BEDTIME_CHIP_MIDNIGHT).map { h ->
                    // The stored MINUTE is preserved across an edit (nothing
                    // clobbers it), so a boundary set as ":30" elsewhere or
                    // synced in from another device used to be invisible here:
                    // the row said "9 PM" while the guard actually locked at
                    // 9:30. Shown on the chip it belongs to, and only there.
                    val minute = kidsBedtimeMinute.coerceIn(0, 59)
                    val label = when {
                        !kidsBedtimeOn || h != kidsBedtime || minute == 0 ->
                            bedtimeChipLabel(h)
                        h == BEDTIME_CHIP_MIDNIGHT ->
                            "12:${minute.toString().padStart(2, '0')} AM"
                        h == BEDTIME_CHIP_OFF -> bedtimeChipLabel(h)
                        else -> "$h:${minute.toString().padStart(2, '0')} PM"
                    }
                    label to h
                },
                selected = if (kidsBedtimeOn) kidsBedtime else BEDTIME_CHIP_OFF,
                onSelect = { picked ->
                    if (picked == BEDTIME_CHIP_OFF) {
                        kidsBedtimeOn = false
                    } else {
                        kidsBedtimeOn = true
                        kidsBedtime = picked
                    }
                }
            )
            // m3 (audit): the hour chips above only ever wrote :00, while the
            // guard locks on the stored MINUTE - so a 9:30 boundary (synced in
            // from another device, or set in an older build) locked at 9:30
            // while this row said "9 PM", and there was no way to pick a half
            // hour here at all. Shown only with a bedtime on, because "Off" has
            // no minute to set.
            if (kidsBedtimeOn) {
                KidsOptionChipRow(
                    title = "Bedtime minute",
                    hint = "The exact boundary the lock uses, on top of the hour " +
                        "above. A half hour here is what a 9:30 bedtime means.",
                    options = listOf(":00" to 0, ":30" to 30),
                    selected = if (kidsBedtimeMinute >= 30) 30 else 0,
                    onSelect = { kidsBedtimeMinute = it }
                )
            }

            // ── The app-level parent PIN ─────────────────────────────
            // Beside the locks it unlocks, because this is the ONE credential
            // that lifts a time limit on a profile with no PIN of its own - and
            // therefore the only exit that veil has (see
            // [KidsTimeGuard.setParentPin]). Device-local, hashed, asked for
            // only at the veil: daily profile switching stays PIN-free. Also the
            // recovery path for a lock saved before this existed.
            val parentPinSet = remember(parentPinRevision) { KidsTimeGuard.hasParentPin() }
            Text(
                text = "Parent PIN",
                style = MaterialTheme.typography.bodyLarge,
                color = KBTextHi,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = if (parentPinSet) {
                    "Set on this TV, and asked for only when a time limit has " +
                        "locked a profile. It unlocks that session - and " +
                        "re-engages when the app restarts."
                } else {
                    "One PIN for this TV, asked for only when a time limit has " +
                        "locked a profile. A lock needs one, so kids profiles can " +
                        "stay PIN-free for daily use."
                },
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .padding(top = 10.dp)
                    .focusGroup()
            ) {
                KBButton(
                    label = if (parentPinSet) "CHANGE" else "SET",
                    enabled = true
                ) {
                    // Not a gated action: backing out of THIS one changes
                    // nothing, it just closes (see closeParentPinPrompt).
                    parentPinPromptGated = false
                    parentPinPromptWhy = null
                    parentPinGateTarget = null
                    parentPinAfterVerify = null
                    parentPinPrompt = true
                }
            }
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
                                .crossfade(false)
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
                            // ASCII plus, like the Library's "+ NEW LIST" tile:
                            // the full-width form was the app's only U+FF0B, and
                            // it renders differently from the ASCII plus beside
                            // it depending on the font fallback that wins.
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.headlineMedium,
                                color = KBTextHi
                            )
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
                            .background(Color(bg))
                            // The chosen color keeps its ring whether or not
                            // focus is on it - the picker's tiles already do
                            // this, and here the ring only ever appeared while
                            // the D-pad was sitting on the tile.
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = KBTextHi,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.take(1).uppercase().ifBlank { "?" },
                            // Oswald via the type scale, not the platform font.
                            style = MaterialTheme.typography.headlineMedium,
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
            KBButton(
                label = "SAVE",
                enabled = name.isNotBlank()
            ) {
                // ── The editor's gates (audit C2/C5/M6 + M11) ───────
                //
                // M6 first: a FIRST PIN on a profile that has none is how a kid
                // locks the parent OUT of it, so it is refused unless a parent
                // PIN already exists - deliberately no create-the-PIN fallback
                // here (see KidsGate.firstPinNeedsParentPin).
                val anyPinField = newPinInput.isNotBlank() ||
                    confirmPinInput.isNotBlank() || currentPinInput.isNotBlank()
                if (anyPinField && KidsGate.firstPinNeedsParentPin(editing)) {
                    feedback.show(
                        text = "Set a parent PIN first",
                        isError = true
                    )
                    return@KBButton
                }
                // C2/C5: what this save would change. A kids profile's own
                // configuration - its ceiling, its options, its locks - and the
                // act of creating a profile once the device already holds a
                // kids profile are the parent's to change, because the kid
                // reaches this very screen from Settings. m3 is why the bedtime
                // is compared through bedtimeMinutesFor: the :30 minute is part
                // of the stored value, so a form that cannot express it must not
                // read as an edit of it.
                val storedBedtime = editing?.kidsBedtimeMinutes ?: 0
                val formBedtime = if (kidsBedtimeOn) {
                    bedtimeMinutesFor(kidsBedtime, kidsBedtimeMinute)
                } else 0
                val kidsChanged = (editing?.kidsMaxAge != null) != kidsModeOn ||
                    (
                        kidsModeOn && (
                            kidsLevel != editing?.kidsMaxAge ||
                                kidsHideAddons != (editing?.kidsHideAddons ?: true) ||
                                kidsLockLiveTv != (editing?.kidsLockLiveTv ?: true) ||
                                kidsRequirePinToExit != (editing?.kidsRequirePinToExit ?: false) ||
                                kidsDailyLimitHours * 60 != (editing?.kidsDailyLimitMinutes ?: 0) ||
                                formBedtime != storedBedtime
                            )
                        )
                val lockBeingSet = kidsModeOn &&
                    (kidsDailyLimitHours > 0 || kidsBedtimeOn)
                if (
                    KidsGate.saveNeedsPin(
                        kidsChanged = kidsChanged,
                        creatingProfile = editing == null,
                        pinChanging = anyPinField,
                        anyKidsProfileExists = profiles.any { it.kidsMaxAge != null }
                    )
                ) {
                    // The parent PIN is asked for FIRST, and a parent who backs
                    // out gets the save REFUSED - nothing is applied without it
                    // (see closeParentPinPrompt). When a lock is what is being
                    // set, the copy says so: a lock also needs a credential that
                    // can lift it, which is the brick the parent PIN closes.
                    gateWithParentPin(
                        if (lockBeingSet) "Required to set this limit"
                        else "Required to change this profile",
                        editing
                    ) { saveProfile() }
                    return@KBButton
                }
                saveProfile()
            }

            KBButton(
                label = "CANCEL",
                enabled = true
            ) {
                onDone()
            }

            if (shownCustomUrl != null) {
                KBButton(
                    label = "REMOVE PHOTO",
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
                KBButton(
                    label = "DELETE",
                    enabled = true,
                    danger = true
                ) {
                    // C3: deleting a profile is a parental act whenever the
                    // target carries a PIN of its own, the target IS the
                    // sandbox, or a kid is the one holding the remote.
                    val target = editing
                    if (KidsGate.deleteNeedsPin(target, ProfileManager.activeProfile.value)) {
                        gateWithParentPin("Required to delete this profile", target) {
                            ProfileManager.delete(context, target.id)
                            onDone()
                        }
                    } else {
                        ProfileManager.delete(context, target.id)
                        onDone()
                    }
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

    // ── The parent-PIN prompt (audit C2/C3/C5/M6/M7 + M11) ─────────
    // One dialog for every caller: the Parent PIN row above (set/change), and
    // each GATED action - a kids configuration about to change, a profile about
    // to be created, deleted, or opened in the editor. When any credential
    // exists the CURRENT one is required first (the same contract the
    // profile-PIN fields follow) and the action runs only once the new parent
    // PIN is written; a gated action has no other way through.
    if (parentPinPrompt) {
        val gateTarget = parentPinGateTarget
        val credentialExists = remember(parentPinRevision, gateTarget?.id, effectiveEditId) {
            KidsGate.hasCredential(gateTarget)
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { closeParentPinPrompt(true) }
        ) {
            KBDialogPanel(
                title = "Parent PIN",
                subtitle = parentPinPromptWhy,
                width = 480.dp
            ) {
                Text(
                    text = when {
                        parentPinPromptGated && !credentialExists ->
                            "This TV has no PIN yet, and the profile's settings " +
                                "are the parent's to change. Set one now - it " +
                                "stays with the device, not the profile."
                        parentPinPromptGated ->
                            "Enter the profile's PIN, or the parent PIN, to " +
                                "continue. Without it nothing is changed."
                        else ->
                            "Asked for at the lock screen when a time limit has " +
                                "locked a profile, and to change profile settings."
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (credentialExists) {
                    KBTextField(
                        value = parentPinCurrent,
                        onValueChange = {
                            parentPinCurrent = it.filter(Char::isDigit).take(4)
                            parentPinError = null
                        },
                        placeholder = "Current PIN",
                        openKeyboardOnFocus = false,
                        keyboardType = KeyboardType.NumberPassword,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                KBTextField(
                    value = parentPinNew,
                    onValueChange = {
                        parentPinNew = it.filter(Char::isDigit).take(4)
                        parentPinError = null
                    },
                    placeholder = "New PIN (4 digits)",
                    focusRequester = parentPinFieldRequester,
                    openKeyboardOnFocus = false,
                    keyboardType = KeyboardType.NumberPassword,
                    visualTransformation = PasswordVisualTransformation()
                )
                KBTextField(
                    value = parentPinConfirm,
                    onValueChange = {
                        parentPinConfirm = it.filter(Char::isDigit).take(4)
                        parentPinError = null
                    },
                    placeholder = "Confirm PIN",
                    openKeyboardOnFocus = false,
                    keyboardType = KeyboardType.NumberPassword,
                    visualTransformation = PasswordVisualTransformation()
                )
                parentPinError?.let { message ->
                    Text(
                        text = message,
                        color = KBDanger,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    KBButton(
                        label = "SAVE",
                        enabled = true
                    ) {
                        val failure = when {
                            !ProfileManager.isValidPin(parentPinNew) ->
                                "The PIN must be 4 digits"
                            parentPinNew != parentPinConfirm -> "PINs don't match"
                            // Either credential answers whosever gate this is:
                            // the target's own PIN, or the parent PIN (see
                            // KidsGate.verifies).
                            credentialExists && !KidsGate.verifies(gateTarget, parentPinCurrent) ->
                                "Current PIN is incorrect"
                            else -> null
                        }
                        if (failure != null) {
                            parentPinError = failure
                        } else {
                            // Written before the action that was waiting on it,
                            // so the gate it already passed stays passed.
                            KidsTimeGuard.setParentPin(parentPinNew)
                            parentPinRevision += 1
                            val after = parentPinAfterVerify
                            closeParentPinPrompt(false)
                            after?.invoke()
                        }
                    }
                    KBButton(
                        label = "CANCEL",
                        enabled = true
                    ) {
                        closeParentPinPrompt(true)
                    }
                }
            }
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

