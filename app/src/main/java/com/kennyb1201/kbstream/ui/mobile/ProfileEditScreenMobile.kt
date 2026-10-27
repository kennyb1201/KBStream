package com.kennyb1201.kbstream.ui.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.sync.KidsGate
import com.kennyb1201.kbstream.data.sync.KidsMode
import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.rememberKBFeedback
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBPasteChip
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.TouchChoiceChip
import com.kennyb1201.kbstream.ui.profiles.BEDTIME_CHIP_DEFAULT
import com.kennyb1201.kbstream.ui.profiles.BEDTIME_CHIP_MIDNIGHT
import com.kennyb1201.kbstream.ui.profiles.BEDTIME_CHIP_OFF
import com.kennyb1201.kbstream.ui.profiles.ParentPinPromptMode
import com.kennyb1201.kbstream.ui.profiles.ParentPinSubmit
import com.kennyb1201.kbstream.ui.profiles.bedtimeChipFor
import com.kennyb1201.kbstream.ui.profiles.bedtimeChipLabel
import com.kennyb1201.kbstream.ui.profiles.bedtimeMinutesFor
import com.kennyb1201.kbstream.ui.profiles.parentPinPromptSpec
import com.kennyb1201.kbstream.ui.profiles.rememberParentPinSession
import com.kennyb1201.kbstream.ui.profiles.submitParentPin
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * Create/edit a profile, for a finger.
 *
 * The TV editor's form and its rules, unchanged - only the controls are the
 * phone's:
 *
 *  - name, the 8 generic avatar colours, an uploaded photo or a typed image
 *    URL, and REMOVE PHOTO;
 *  - the profile's own 4-digit PIN, with the current PIN required to change or
 *    remove it;
 *  - guest profile and Kids Mode (ceiling, lock add-ons, kid-safe Live TV,
 *    PIN-to-leave, daily limit, bedtime hour plus its minute);
 *  - the manage-mode chip row that switches which profile is edited (with the
 *    M7 gate on a target that carries a PIN or is a kids profile);
 *  - DELETE, guarded by [KidsGate.deleteNeedsPin].
 *
 * The parent-PIN gates are the TV editor's, verbatim: a first PIN on a PIN-less
 * profile needs an existing parent PIN (M6, no create-the-PIN fallback), a save
 * that changes kids configuration / a PIN / creates a profile beside a kids one
 * asks for the parent PIN first (C2/C5/M11), and backing out of that prompt
 * refuses the action rather than applying it with its protection dropped.
 *
 * The save itself mirrors `ProfileEditScreen.saveProfile` step for step -
 * validate the PIN fields, create-or-migrate, finalize the avatar, write guest,
 * Kids Mode and its options, then the PIN - because the two form factors must
 * write the same profile. The verified D-pad focus retries have no touch twin.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreenMobile(
    editingProfileId: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val feedback = rememberKBFeedback()
    val profiles by ProfileManager.profiles.collectAsStateWithLifecycle()

    var effectiveEditId by remember(editingProfileId) {
        mutableStateOf<String?>(editingProfileId)
    }
    val editing = profiles.firstOrNull { it.id == effectiveEditId }

    var name by remember(effectiveEditId) { mutableStateOf(editing?.name.orEmpty()) }
    var avatarIndex by remember(effectiveEditId) {
        mutableIntStateOf(editing?.avatarIndex ?: 0)
    }
    var pendingAvatarUrl by remember(effectiveEditId) {
        mutableStateOf<String?>(null)
    }
    var avatarUrlInput by remember(effectiveEditId) { mutableStateOf("") }
    var currentPinInput by remember(effectiveEditId) { mutableStateOf("") }
    var newPinInput by remember(effectiveEditId) { mutableStateOf("") }
    var confirmPinInput by remember(effectiveEditId) { mutableStateOf("") }
    var kidsModeOn by remember(effectiveEditId) {
        mutableStateOf(editing?.kidsMaxAge != null)
    }
    var kidsLevel by remember(effectiveEditId) {
        mutableIntStateOf(
            editing?.kidsMaxAge.let(KidsMode::normalize)
                ?: ProfileManager.KIDS_DEFAULT_MAX_AGE
        )
    }
    var guestProfile by remember(effectiveEditId) {
        mutableStateOf(editing?.guest ?: false)
    }
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
        mutableIntStateOf((editing?.kidsDailyLimitMinutes ?: 0) / 60)
    }
    var kidsBedtime by remember(effectiveEditId) {
        val mins = editing?.kidsBedtimeMinutes ?: 0
        mutableIntStateOf(
            bedtimeChipFor(mins).takeIf { it != BEDTIME_CHIP_OFF }
                ?: BEDTIME_CHIP_DEFAULT
        )
    }
    var kidsBedtimeMinute by remember(effectiveEditId) {
        val mins = editing?.kidsBedtimeMinutes ?: 0
        mutableIntStateOf(if (mins == 0) 0 else mins % 60)
    }
    var kidsBedtimeOn by remember(effectiveEditId) {
        mutableStateOf((editing?.kidsBedtimeMinutes ?: 0) > 0)
    }

    var parentPinPrompt by remember(effectiveEditId) { mutableStateOf(false) }
    var parentPinPromptGated by remember(effectiveEditId) { mutableStateOf(false) }
    var parentPinPromptWhy by remember(effectiveEditId) { mutableStateOf<String?>(null) }
    var parentPinAfterVerify by remember(effectiveEditId) {
        mutableStateOf<(() -> Unit)?>(null)
    }
    var parentPinGateTarget by remember(effectiveEditId) {
        mutableStateOf<ProfileManager.Profile?>(null)
    }
    var parentPinCurrent by remember(effectiveEditId) { mutableStateOf("") }
    var parentPinNew by remember(effectiveEditId) { mutableStateOf("") }
    var parentPinConfirm by remember(effectiveEditId) { mutableStateOf("") }
    var parentPinError by remember(effectiveEditId) { mutableStateOf<String?>(null) }
    var parentPinRevision by remember(effectiveEditId) { mutableIntStateOf(0) }
    // One verification per editor session: a parent asked three times for one
    // change is the field report this closes. Dies with the editor and never
    // leaves this screen (see [ParentPinSession]).
    val parentPinSession = rememberParentPinSession(effectiveEditId)

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

    val shownCustomUrl = when {
        avatarUrlInput.isNotBlank() -> avatarUrlInput.trim()
        useCustomAvatar -> pendingAvatarUrl ?: editing?.customAvatarUrl ?: editing?.avatarData
        else -> null
    }

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

    val gateWithParentPin: (String, ProfileManager.Profile?, () -> Unit) -> Unit =
        { why, target, action ->
            // Already verified in this sitting: the action runs. Otherwise the
            // prompt is raised for it, and only for it.
            parentPinSession.gate(action = action) { gated ->
                parentPinPromptGated = true
                parentPinPromptWhy = why
                parentPinGateTarget = target
                parentPinAfterVerify = gated
                parentPinPrompt = true
            }
        }

    // The save, as a lambda because the parent-PIN prompt runs it too. Same
    // validation order and the same writes as the TV editor.
    val saveProfile: () -> Unit = save@{
        val pinChanged = newPinInput.isNotBlank() || confirmPinInput.isNotBlank()
        val wantsClear = newPinInput.isBlank() && confirmPinInput.isBlank() &&
            currentPinInput.isNotBlank()
        var pinTargetId = editing?.id
        val hasExistingPin = editing?.pinHash != null
        if (pinChanged || wantsClear) {
            val currentOk = !hasExistingPin ||
                ProfileManager.verifyPin(requireNotNull(editing), currentPinInput)
            val newPinOk = !pinChanged ||
                (newPinInput.length == 4 && newPinInput == confirmPinInput)
            if (!currentOk || !newPinOk) {
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

        val typedUrl = avatarUrlInput.trim().takeIf { it.startsWith("http") }
        val kidsTargetId: String?
        if (editing == null) {
            val created = ProfileManager.createAndMigrateLegacy(context, name, avatarIndex)
            // The TV editor's rule, for the same reason: a refused create means
            // the stored list could not be read, not that a profile now exists.
            if (created == null) {
                feedback.show(
                    text = "This device's profiles couldn't be read, so nothing " +
                        "was saved",
                    isError = true
                )
                return@save
            }
            kidsTargetId = created.id
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

        kidsTargetId?.let { id ->
            ProfileManager.setGuest(context, id, guestProfile)
        }
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
        when {
            pinTargetId != null && pinChanged ->
                ProfileManager.setPin(context, pinTargetId, newPinInput)
            pinTargetId != null && wantsClear ->
                ProfileManager.setPin(context, pinTargetId, null)
        }
        onDone()
    }

    MobileTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The arrow is a CANCEL: it pops exactly as system back does (no
            // save, no onDone). Top-left of the form, like every other page.
            Row(modifier = Modifier.fillMaxWidth()) {
                MobileBackButton(onBack = onBack)
            }

            // Manage mode: pick which profile to edit, or a new one.
            if (editingProfileId == null && profiles.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    profiles.forEach { p ->
                        TouchChoiceChip(
                            label = p.name,
                            selected = effectiveEditId == p.id,
                            onClick = {
                                // M7: this row is how a kid reaches another
                                // profile's editor. A target with a PIN, or a
                                // kids sandbox, opens only with the parent PIN.
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
                    TouchChoiceChip(
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
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp)
            )

            KBTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Profile name",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
            )

            // ── Parental PIN ─────────────────────────────────────────
            SectionTitle("Parental lock", top = 24.dp)
            Hint(
                if (editing?.pinHash != null) {
                    "A PIN is set. Enter the current PIN to change or remove it."
                } else {
                    "Require a 4-digit PIN to open this profile from Who's Watching."
                }
            )
            if (editing?.pinHash != null) {
                PinField(
                    value = currentPinInput,
                    onValueChange = { currentPinInput = it.filter(Char::isDigit).take(4) },
                    placeholder = "Current PIN"
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                KBTextField(
                    value = newPinInput,
                    onValueChange = { newPinInput = it.filter(Char::isDigit).take(4) },
                    placeholder = "New PIN",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.NumberPassword,
                    visualTransformation = PasswordVisualTransformation()
                )
                KBTextField(
                    value = confirmPinInput,
                    onValueChange = { confirmPinInput = it.filter(Char::isDigit).take(4) },
                    placeholder = "Confirm",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.NumberPassword,
                    visualTransformation = PasswordVisualTransformation()
                )
            }

            // ── Guest profile ────────────────────────────────────────
            SectionTitle("Guest profile", top = 24.dp)
            Hint(
                if (guestProfile) {
                    "Home leads with a fixed set of built-in rails - the two Top 10 " +
                        "rows, new releases, airing now, trending, popular and top " +
                        "rated - so this profile needs no add-ons."
                } else {
                    "Turn on for a profile that needs no add-ons: Home leads with " +
                        "built-in TMDB rails for movies and shows."
                }
            )
            ChipRow(
                options = listOf("Off" to false, "On" to true),
                selected = guestProfile,
                onSelect = { guestProfile = it }
            )

            // ── Kids Mode ────────────────────────────────────────────
            SectionTitle("Kids Mode", top = 24.dp)
            Hint(
                if (kidsModeOn) {
                    "Search, discover and home rails only show titles rated at or " +
                        "below the chosen rating for this profile."
                } else {
                    "Turn on to filter search and discover to kid-friendly content " +
                        "with a maximum rating."
                }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                TouchChoiceChip(
                    label = "Off",
                    selected = !kidsModeOn,
                    onClick = { kidsModeOn = false }
                )
                listOf(
                    "PG-13 & under" to KidsMode.CEIL_PG13,
                    "PG & under" to KidsMode.CEIL_PG,
                    "G & under" to KidsMode.CEIL_G
                ).forEach { (label, level) ->
                    TouchChoiceChip(
                        label = label,
                        selected = kidsModeOn && kidsLevel == level,
                        onClick = {
                            kidsModeOn = true
                            kidsLevel = level
                        }
                    )
                }
            }

            if (kidsModeOn) {
                SectionTitle("Kids Mode options", top = 18.dp, small = true)
                KidsOption(
                    title = "Lock add-ons",
                    hint = "Hides the Add-ons screen for this profile so new " +
                        "catalogs can't be installed.",
                    options = listOf("On" to true, "Off" to false),
                    selected = kidsHideAddons,
                    onSelect = { kidsHideAddons = it }
                )
                KidsOption(
                    title = "Kid-safe Live TV",
                    hint = "Hides live TV channel groups that don't look " +
                        "kid-focused (cartoons, family, kids).",
                    options = listOf("On" to true, "Off" to false),
                    selected = kidsLockLiveTv,
                    onSelect = { kidsLockLiveTv = it }
                )
                KidsOption(
                    title = "PIN to leave profile",
                    hint = "Asks for this profile's PIN before switching to a " +
                        "different profile. Requires a PIN above.",
                    options = listOf("On" to true, "Off" to false),
                    selected = kidsRequirePinToExit,
                    onSelect = { kidsRequirePinToExit = it }
                )
                KidsOption(
                    title = "Daily watch limit",
                    hint = "Locks this profile once the daily watch time is used " +
                        "up. A PIN unlocks it early.",
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
                KidsOption(
                    title = "Bedtime lock",
                    hint = "Locks this profile from bedtime until 4:00 AM. A PIN " +
                        "unlocks it early.",
                    options = (BEDTIME_CHIP_OFF..BEDTIME_CHIP_MIDNIGHT).map { h ->
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
                if (kidsBedtimeOn) {
                    KidsOption(
                        title = "Bedtime minute",
                        hint = "The exact boundary the lock uses, on top of the " +
                            "hour above. A half hour here is what a 9:30 bedtime means.",
                        options = listOf(":00" to 0, ":30" to 30),
                        selected = if (kidsBedtimeMinute >= 30) 30 else 0,
                        onSelect = { kidsBedtimeMinute = it }
                    )
                }

                // ── The app-level parent PIN ─────────────────────────
                val parentPinSet = remember(parentPinRevision) {
                    KidsTimeGuard.hasParentPin()
                }
                Text(
                    text = "Parent PIN",
                    style = MaterialTheme.typography.bodyLarge,
                    color = KBTextHi,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Hint(
                    if (parentPinSet) {
                        "Set on this device, and asked for only when a time limit " +
                            "has locked a profile. It unlocks that session - and " +
                            "re-engages when the app restarts."
                    } else {
                        "One PIN for this device, asked for only when a time limit " +
                            "has locked a profile. A lock needs one, so kids " +
                            "profiles can stay PIN-free for daily use."
                    }
                )
                KBButton(
                    label = if (parentPinSet) "CHANGE" else "SET",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    parentPinPromptGated = false
                    parentPinPromptWhy = null
                    parentPinGateTarget = null
                    parentPinAfterVerify = null
                    parentPinPrompt = true
                }
            }

            // ── Avatars ──────────────────────────────────────────────
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                item(key = "custom") {
                    AvatarPlate(
                        selected = useCustomAvatar && shownCustomUrl != null,
                        label = null,
                        onClick = {
                            imagePicker.launch("image/*")
                            useCustomAvatar = true
                        }
                    ) {
                        if (shownCustomUrl != null) {
                            AsyncImage(
                                model = shownCustomUrl,
                                contentDescription = "Custom avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, KBTextHi, CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(KBSurfaceRaised)
                                    .border(1.dp, KBAccent.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = KBTextHi
                                )
                            }
                        }
                    }
                }

                items((0 until ProfileManager.AVATAR_COUNT).toList(), key = { "avatar:$it" }) {
                        index ->
                    val (bg, fg) = ProfileManager.AVATAR_COLORS[index]
                    val selected = avatarIndex == index
                    AvatarPlate(
                        selected = selected,
                        label = null,
                        onClick = {
                            avatarIndex = index
                            if (pendingAvatarUrl != null) pendingAvatarUrl = null
                            avatarUrlInput = ""
                            useCustomAvatar = false
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
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
                                text = name.take(1).uppercase().ifBlank { "?" },
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(fg)
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
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
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.width(8.dp))
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

            // ── Actions ──────────────────────────────────────────────
            KBButton(
                label = "SAVE",
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                // M6: a FIRST PIN on a PIN-less profile is refused until a
                // parent PIN exists - no create-the-PIN fallback here.
                val anyPinField = newPinInput.isNotBlank() ||
                    confirmPinInput.isNotBlank() || currentPinInput.isNotBlank()
                if (anyPinField && KidsGate.firstPinNeedsParentPin(editing)) {
                    feedback.show(
                        text = "Set a parent PIN first",
                        isError = true
                    )
                    return@KBButton
                }

                // C2/C5: what this save would change. The bedtime is compared
                // through bedtimeMinutesFor so the preserved :30 minute is part
                // of the comparison.
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                onDone()
            }

            if (shownCustomUrl != null) {
                KBButton(
                    label = "REMOVE PHOTO",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
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
                    danger = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    val target = editing
                    if (KidsGate.deleteNeedsPin(
                            target,
                            ProfileManager.activeProfile.value
                        )
                    ) {
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

            if (editing == null && profiles.isEmpty()) {
                Text(
                    text = "Your existing library (history, add-ons, settings) will " +
                        "be moved into this profile so nothing is lost.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        // ── The parent-PIN prompt: the row above, and every gated action ──
        if (parentPinPrompt) {
            val gateTarget = parentPinGateTarget
            val credentialExists = remember(
                parentPinRevision,
                gateTarget?.id,
                effectiveEditId
            ) {
                KidsGate.hasCredential(gateTarget)
            }
            // Verify-only when the credential exists, create when it does not,
            // rotate for the Parent PIN row (see parentPinPromptSpec).
            val spec = parentPinPromptSpec(
                gated = parentPinPromptGated,
                credentialExists = credentialExists
            )
            Dialog(onDismissRequest = { closeParentPinPrompt(true) }) {
                KBDialogPanel(
                    title = "Parent PIN",
                    subtitle = parentPinPromptWhy,
                    width = 480.dp
                ) {
                    Text(
                        text = when {
                            parentPinPromptGated && !credentialExists ->
                                "This device has no PIN yet, and the profile's " +
                                    "settings are the parent's to change. Set one now " +
                                    "- it stays with the device, not the profile."
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
                    if (spec.askCurrentPin) {
                        KBTextField(
                            value = parentPinCurrent,
                            onValueChange = {
                                parentPinCurrent = it.filter(Char::isDigit).take(4)
                                parentPinError = null
                            },
                            placeholder = if (spec.mode == ParentPinPromptMode.VERIFY) {
                                "PIN"
                            } else {
                                "Current PIN"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardType = KeyboardType.NumberPassword,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    if (spec.askNewPin) {
                        KBTextField(
                            value = parentPinNew,
                            onValueChange = {
                                parentPinNew = it.filter(Char::isDigit).take(4)
                                parentPinError = null
                            },
                            placeholder = "New PIN (4 digits)",
                            modifier = Modifier.fillMaxWidth(),
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
                            modifier = Modifier.fillMaxWidth(),
                            keyboardType = KeyboardType.NumberPassword,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
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
                            modifier = Modifier.weight(1f)
                        ) {
                            // The decision itself is shared with the TV editor
                            // (see submitParentPin). A refusal - wrong PIN, or
                            // a write that failed - keeps this dialog open and
                            // never runs the action that was waiting.
                            when (
                                val result = submitParentPin(
                                    spec = spec,
                                    gateTarget = gateTarget,
                                    currentPin = parentPinCurrent,
                                    newPin = parentPinNew,
                                    confirmPin = parentPinConfirm
                                )
                            ) {
                                is ParentPinSubmit.Rejected -> parentPinError = result.message
                                is ParentPinSubmit.Accepted -> {
                                    parentPinSession.markVerified()
                                    if (result.wrotePin) parentPinRevision += 1
                                    val after = parentPinAfterVerify
                                    closeParentPinPrompt(false)
                                    after?.invoke()
                                }
                            }
                        }
                        KBButton(
                            label = "CANCEL",
                            modifier = Modifier.weight(1f)
                        ) {
                            closeParentPinPrompt(true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, top: androidx.compose.ui.unit.Dp, small: Boolean = false) {
    Text(
        text = text,
        style = if (small) {
            MaterialTheme.typography.titleSmall
        } else {
            MaterialTheme.typography.titleMedium
        },
        color = if (small) KBTextLo else KBTextHi,
        modifier = Modifier.padding(top = top)
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = KBTextLo,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    KBTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        keyboardType = KeyboardType.NumberPassword,
        visualTransformation = PasswordVisualTransformation()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        options.forEach { (label, value) ->
            TouchChoiceChip(
                label = label,
                selected = selected == value,
                onClick = { onSelect(value) }
            )
        }
    }
}

@Composable
private fun <T> KidsOption(
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
    ChipRow(options = options, selected = selected, onSelect = onSelect)
}

/** One avatar plate: a circle with the chosen ring, or the add-photo tile. */
@Composable
private fun AvatarPlate(
    selected: Boolean,
    label: String?,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (selected) KBAccent.copy(alpha = 0.2f) else Color.Transparent)
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = if (selected) KBAccent else Color.Transparent,
                    shape = CircleShape
                )
                .clickable(onClick = onClick)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
        label?.let {
            Text(
                text = it,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
