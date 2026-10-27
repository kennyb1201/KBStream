package com.kennyb1201.kbstream.ui.profiles

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.sync.KidsGate
import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.mobile.ProfilePickerScreenMobile
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBFocusTile
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.flow.first

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
    // The phone entry: the same profile list, PIN gates and kids exit gate,
    // laid out as tappable avatars. One branch; the TV page below is unchanged
    // (see ProfilePickerScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        ProfilePickerScreenMobile(
            onSelect = onSelect,
            onManage = onManage
        )
        return
    }

    val pickerContext = androidx.compose.ui.platform.LocalContext.current
    val profiles by ProfileManager.profiles.collectAsStateWithLifecycle()
    val active by ProfileManager.activeProfile.collectAsStateWithLifecycle()
    // A blob this build cannot read is not "no profiles": the list is still on
    // disk, and this screen must not present it as an empty first run (see
    // ProfileManager.profilesUnreadable).
    val profilesUnreadable by ProfileManager.profilesUnreadable.collectAsStateWithLifecycle()

    // Parental lock: a PIN-protected profile asks for its PIN before it
    // activates. pinTarget holds the pending profile; pinEntry collects the
    // digits; pinError explains a wrong guess.
    var pinTarget by remember { mutableStateOf<ProfileManager.Profile?>(null) }
    var pinEntry by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    // Kids Mode exit gate: what to do once the CURRENT profile's credential is
    // verified (switch to a target profile, or open Manage).
    var exitGateAction by remember {
        mutableStateOf<ExitGateAction?>(null)
    }
    var exitGateEntry by remember { mutableStateOf("") }
    var exitGateError by remember { mutableStateOf(false) }
    // Set while the gate has NOTHING to verify against (a gated kids profile on
    // a device with no PIN of either kind): the same dialog then CREATES the
    // parent PIN, which is the audit's "never fail open" without bricking a TV
    // that has no credential to ask for.
    var exitGateNew by remember { mutableStateOf("") }
    var exitGateConfirm by remember { mutableStateOf("") }
    var exitGateCreateError by remember { mutableStateOf<String?>(null) }

    // ── The landing, driven to observation ────────────────────────────────
    //
    // This screen is the app's entry gate, and it is the one place where a
    // one-shot focus ask is a total lockout. The field report: a Fire TV
    // force-closed during playback, relaunched into this picker, and nothing on
    // it could be reached - no focus ring anywhere, all four directions dead,
    // OK dead - until a paired mouse or a reinstall. The ask was fired once at
    // first composition, lost that race, and was never repeated; and from
    // "nothing holds focus" no directional press can bootstrap one, so the
    // gate the whole app sits behind was unusable. Deterministic on that device
    // across force-stops, cache clears and a full reboot.
    //
    // So the landing is DRIVEN, the same rule the Home top bar and the Detail
    // hand-backs follow (a focus ask is retried until focus is OBSERVED on its
    // target, never fired once and assumed):
    //
    //  - keyed on the tile it is aimed at (the first profile, or the trailing
    //    MANAGE tile when there are no profiles), so the arrival of the very
    //    data the ask is for re-runs it;
    //  - asked again every frame until the tile's OWN focus state answers, and
    //    bounded - an exhausted drive says so in the log instead of vanishing;
    //  - watched afterwards, because a landing that DID work can be lost later:
    //    the startup sync pull re-emits the profile list, which rebuilds the
    //    tiles under the focus, and a spent ask cannot repair that.
    val firstRequester = remember { FocusRequester() }

    // Which tile holds focus, by its position in the grid; null when none does.
    // Every tile reports its own focus state: this is what the LANDING is
    // observed on, because "the target holds focus" is exactly what a tile can
    // answer and its parent cannot.
    var focusedTile by remember { mutableStateOf<Int?>(null) }

    // Whether the grid - the tiles' parent - holds focus anywhere in its
    // subtree. The watchdog reads this as well as the tile above, and demands
    // both: a tile index can go stale when the list is rebuilt under it (the
    // stale index then looks focused), and the parent can miss a state a tile
    // reports. Repairing only when BOTH say nobody holds focus can therefore
    // never fight a viewer whose focus merely moved.
    var pickerHasFocus by remember { mutableStateOf(false) }

    val firstProfile = profiles.firstOrNull()
    val landingKey = firstProfile?.let { "profile:${it.id}" } ?: MANAGE_TILE_KEY
    val landingName = firstProfile?.name ?: "MANAGE"

    // A dialog that is up owns focus (see the gates below): the watchdog must
    // never land behind it, and never compete with it.
    fun gateOpen(): Boolean = pinTarget != null || exitGateAction != null

    // The reported tile, dropped when the grid no longer has that many tiles
    // (a profile removed by a sync pull leaves its index behind, and a stale
    // index would read as "someone is focused" forever).
    fun focusedTileNow(): Int? =
        focusedTile?.takeIf { it in 0 until profiles.size + 1 }

    /** True only when the screen really has nowhere for the D-pad to go. */
    fun nothingFocused(): Boolean =
        !pickerHasFocus && focusedTileNow() == null && !gateOpen()

    // The ask, as the driver takes it: the requester itself is not part of the
    // loop (see driveProfileLanding), only "ask" and "was it observed".
    val askFirstTile: () -> Unit = { firstRequester.requestFocus() }

    LaunchedEffect(landingKey) {
        fun landed(): Boolean = focusedTileNow() == FIRST_TILE

        val frames = driveProfileLanding(ask = askFirstTile, observed = ::landed)
        if (frames > 0) {
            Log.d(PROFILE_FOCUS_TAG, "landed: $landingName after $frames frame(s)")
        } else {
            Log.w(
                PROFILE_FOCUS_TAG,
                "landing exhausted $PROFILE_LANDING_FRAMES frames: $landingName"
            )
        }

        // The watchdog. It sleeps on the nobody-holds-focus state rather than
        // polling, and it never moves focus that exists - it only repairs the
        // state in which the D-pad has nowhere to go, which is the lockout.
        var exhaustedLogged = false
        while (true) {
            snapshotFlow { nothingFocused() }.first { it }
            // Held for a few frames first: a single rebuilt frame during a
            // recomposition is not a lost viewer, and re-landing on it would
            // yank the D-pad back to the first tile for no reason.
            var emptyFrames = 0
            while (emptyFrames < PROFILE_NO_FOCUS_FRAMES && nothingFocused()) {
                withFrameNanos { }
                emptyFrames++
            }
            if (!nothingFocused()) continue

            Log.d(PROFILE_FOCUS_TAG, "nobody holds focus: re-driving the landing")
            val redriven = driveProfileLanding(ask = askFirstTile, observed = ::landed)
            if (redriven > 0) {
                exhaustedLogged = false
                Log.d(
                    PROFILE_FOCUS_TAG,
                    "re-driven landing: $landingName after $redriven frame(s)"
                )
            } else if (!exhaustedLogged) {
                // Once per streak: a device that can never land must not turn
                // the log into a flood, but its first failure has to be named.
                exhaustedLogged = true
                Log.w(
                    PROFILE_FOCUS_TAG,
                    "re-driven landing exhausted $PROFILE_LANDING_FRAMES frames: " +
                        landingName
                )
            }
        }
    }

    // Exit gate (audit C4/M2/M7), computed here rather than inside the tiles:
    // the dialog below needs the same answer, and leaving the CURRENT kids
    // profile - switching to any other profile, opening Manage, or creating one
    // - is an authenticated act whenever that profile ASKS for one or is LOCKED
    // right now. ExitGateAction records what to run afterwards.
    //
    // The old form was `kidsMaxAge != null && kidsRequirePinToExit &&
    // hasPin(profile)`, and that last term was the fail-open: a profile that
    // asked for a PIN and had none was left ungated, while a LOCKED profile
    // (veil up) let the kid walk to the picker and switch. The credential is now
    // the profile's own PIN or the device's parent PIN (see KidsGate), and with
    // neither set the gate CREATES the parent PIN rather than letting the press
    // through.
    val exitGateActive = KidsGate.leavingNeedsPin(active)
    val exitGateHasCredential = KidsGate.hasCredential(active)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // The screen's name, through the shared page title (Batch F item 38):
        // it was a headlineMedium+Bold of its own, so walking in from Library
        // or Add-ons changed the size of the app's name for the screen.
        KBPageTitle(text = "Who's watching?")

        if (profilesUnreadable) {
            // The one thing this state must not look like is a fresh device.
            // Nothing is lost: the blob is preserved and no profile write will
            // touch it until it can be read again (see ProfileManager), so the
            // sentence says where it comes back from rather than offering a
            // create that would be refused.
            Text(
                text = "Profiles couldn't be read on this device. Nothing has been " +
                    "deleted - sign in on another device to restore them, then open " +
                    "this screen again.",
                color = KBDanger,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 16.dp, start = 32.dp, end = 32.dp)
                    .widthIn(max = 520.dp)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .focusGroup()
                // The watchdog's screen-wide "does anything here hold focus"
                // (see the landing above): the tiles' parent, so it sees any
                // tile that takes focus, however it got there.
                .onFocusChanged { pickerHasFocus = it.hasFocus }
                // Spacing lives INSIDE the scroll viewport: the focused
                // tile scales 1.06x, and padding placed before
                // verticalScroll() puts the clip edge right at the first
                // row — cutting off the top of the focus ring. Inside, the
                // ring grows into scrollable space instead.
                .padding(top = 40.dp, bottom = 12.dp)
        ) {
            val tiles = profiles.map { profile ->
                PickerTile(
                    name = profile.name,
                    avatarIndex = profile.avatarIndex,
                    customAvatarUrl = profile.customAvatarUrl ?: profile.avatarData,
                    selected = active?.id == profile.id,
                    onClick = {
                        when {
                            // Already-active profile: just enter the app —
                            // re-running setActive would needlessly rebind
                            // every singleton mid-session.
                            active?.id == profile.id -> onSelect()
                            // Destination PIN gate takes priority (entering
                            // a PIN'd profile asks for THAT profile's code).
                            ProfileManager.hasPin(profile) -> {
                                pinTarget = profile
                                pinEntry = ""
                                pinError = false
                            }
                            // Leaving a gated kids profile asks for ITS pin,
                            // or the parent PIN (see the exit dialog).
                            exitGateActive -> {
                                pinTarget = null
                                exitGateAction = ExitGateAction.Switch(profile)
                                exitGateEntry = ""
                                exitGateError = false
                                exitGateNew = ""
                                exitGateConfirm = ""
                                exitGateCreateError = null
                            }
                            else -> {
                                ProfileManager.setActive(pickerContext, profile)
                                onSelect()
                            }
                        }
                    }
                )
            } + PickerTile(
                // A control label in the same slot as the names: ALL CAPS is how
                // every other chip and button in the app is written, so the one
                // tile that is an action rather than a person reads as one.
                name = "MANAGE",
                avatarIndex = -1,
                customAvatarUrl = null,
                selected = false,
                onClick = {
                    if (exitGateActive) {
                        pinTarget = null
                        exitGateAction = ExitGateAction.Manage
                        exitGateEntry = ""
                        exitGateError = false
                        exitGateNew = ""
                        exitGateConfirm = ""
                        exitGateCreateError = null
                    } else {
                        onManage()
                    }
                }
            )

            tiles.chunked(PROFILES_PER_ROW).forEachIndexed { rowIndex, rowTiles ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    rowTiles.forEachIndexed { colIndex, tile ->
                        val tileIndex = rowIndex * PROFILES_PER_ROW + colIndex
                        ProfileAvatarTile(
                            name = tile.name,
                            avatarIndex = tile.avatarIndex,
                            customAvatarUrl = tile.customAvatarUrl,
                            selected = tile.selected,
                            focusRequester = if (rowIndex == 0 && colIndex == 0) firstRequester else null,
                            // The landing above observes the (0,0) tile through
                            // this, and the watchdog reads every tile: a
                            // screen-wide "does anything hold focus" that is
                            // made of the tiles' own answers.
                            onFocused = { held ->
                                if (held) {
                                    focusedTile = tileIndex
                                } else if (focusedTile == tileIndex) {
                                    focusedTile = null
                                }
                            },
                            onClick = tile.onClick
                        )
                    }
                }
            }
        }
    }

    // The ENTER gate: activating a profile that has a PIN of its own asks for
    // THAT profile's code. The exit gate below is its own dialog, because it can
    // also be answered by the parent PIN and can have to CREATE it.
    pinTarget?.let { pinProfile ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                pinTarget = null
                pinEntry = ""
                pinError = false
            }
        ) {
            // The shared dialog plate: heading, then the profile it is asking
            // about.
            KBDialogPanel(
                title = "Enter PIN",
                subtitle = pinProfile.name,
                width = 420.dp
            ) {
                KBTextField(
                    value = pinEntry,
                    onValueChange = {
                        pinEntry = it.filter(Char::isDigit).take(4)
                        pinError = false
                    },
                    placeholder = "••••",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
                if (pinError) {
                    Text(
                        text = "Wrong PIN — try again",
                        color = KBDanger,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                KBButton(
                    label = "CANCEL",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    pinTarget = null
                    pinEntry = ""
                    pinError = false
                }
            }
        }

        // Auto-verify the moment four digits are in; a wrong PIN clears the
        // entry and shows the error instead of kicking the user out.
        LaunchedEffect(pinEntry, pinProfile) {
            if (pinEntry.length == 4) {
                if (ProfileManager.verifyPin(pinProfile, pinEntry)) {
                    pinTarget = null
                    pinEntry = ""
                    pinError = false
                    // Enter gate verified: activate the destination.
                    ProfileManager.setActive(pickerContext, pinProfile)
                    onSelect()
                } else {
                    pinError = true
                    pinEntry = ""
                }
            }
        }
    }

    // The EXIT gate (audit C4/M2/M7): switching away from the kids profile, or
    // opening Manage from it. Answered by the profile's own PIN or the parent
    // PIN; with NEITHER set the panel creates the parent PIN and then runs the
    // same action, so a gated profile can never be left unauthenticated.
    exitGateAction?.let { action ->
        val gateProfile = active
        val creating = gateProfile != null && !exitGateHasCredential
        val runGateAction = {
            exitGateAction = null
            exitGateEntry = ""
            exitGateError = false
            exitGateNew = ""
            exitGateConfirm = ""
            exitGateCreateError = null
            when (action) {
                is ExitGateAction.Switch -> {
                    ProfileManager.setActive(pickerContext, action.target)
                    onSelect()
                }
                ExitGateAction.Manage -> onManage()
            }
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                exitGateAction = null
                exitGateEntry = ""
                exitGateError = false
                exitGateNew = ""
                exitGateConfirm = ""
                exitGateCreateError = null
            }
        ) {
            KBDialogPanel(
                title = "Parent PIN",
                subtitle = gateProfile?.name,
                width = 460.dp
            ) {
                Text(
                    text = if (creating) {
                        "This profile asks for a PIN before it can be left, and " +
                            "no PIN is set on this TV yet. Set one now - it stays " +
                            "with the device, not the profile."
                    } else {
                        "Enter this profile's PIN to leave it, or the parent PIN."
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (creating) {
                    KBTextField(
                        value = exitGateNew,
                        onValueChange = {
                            exitGateNew = it.filter(Char::isDigit).take(4)
                            exitGateCreateError = null
                        },
                        placeholder = "New PIN (4 digits)",
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    KBTextField(
                        value = exitGateConfirm,
                        onValueChange = {
                            exitGateConfirm = it.filter(Char::isDigit).take(4)
                            exitGateCreateError = null
                        },
                        placeholder = "Confirm PIN",
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    exitGateCreateError?.let { message ->
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
                        KBButton(label = "SAVE", enabled = true) {
                            val failure = when {
                                !ProfileManager.isValidPin(exitGateNew) ->
                                    "The parent PIN must be 4 digits"
                                exitGateNew != exitGateConfirm -> "PINs don't match"
                                else -> null
                            }
                            if (failure != null) {
                                exitGateCreateError = failure
                            } else {
                                KidsTimeGuard.setParentPin(exitGateNew)
                                runGateAction()
                            }
                        }
                        KBButton(label = "CANCEL", enabled = true) {
                            exitGateAction = null
                            exitGateNew = ""
                            exitGateConfirm = ""
                            exitGateCreateError = null
                        }
                    }
                } else {
                    KBTextField(
                        value = exitGateEntry,
                        onValueChange = {
                            exitGateEntry = it.filter(Char::isDigit).take(4)
                            exitGateError = false
                        },
                        placeholder = "••••",
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    if (exitGateError) {
                        Text(
                            text = "Wrong PIN — try again",
                            color = KBDanger,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    KBButton(
                        label = "CANCEL",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        exitGateAction = null
                        exitGateEntry = ""
                        exitGateError = false
                    }
                }
            }
        }

        LaunchedEffect(exitGateEntry) {
            if (!creating && exitGateEntry.length == 4) {
                if (KidsGate.verifies(gateProfile, exitGateEntry)) {
                    runGateAction()
                } else {
                    exitGateError = true
                    exitGateEntry = ""
                }
            }
        }
    }
}

/** What to run after the CURRENT kids profile's exit PIN is verified. */
private sealed class ExitGateAction {
    data class Switch(val target: ProfileManager.Profile) : ExitGateAction()
    object Manage : ExitGateAction()
}

private const val PROFILES_PER_ROW = 5

/**
 * The grid position the landing is aimed at: the first tile.
 *
 * It is the first profile, or the trailing MANAGE tile when there are none -
 * the same tile the old one-shot was attached to.
 */
private const val FIRST_TILE = 0

/** The landing key of the no-profiles case (see the landing effect). */
private const val MANAGE_TILE_KEY = "manage"

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
    /** Reports this tile's own focus state, for the landing and its watchdog. */
    onFocused: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val tileContext = androidx.compose.ui.platform.LocalContext.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            // The tile IS a circle, so the surface has to SAY so. The focused
            // GLOW below is painted from this shape, not from the ring's Border
            // (tvSurfaceGlow draws the shape the surface was handed), so a tile
            // left on the default rounded-rectangle shape lit a rounded SQUARE
            // plate behind a round avatar: the square outline in the report.
            // The ring's own shape still comes from the Border; what this line
            // fixes is the tile's glow, its clip and its ripple. CastCard on
            // Detail, the app's other avatar circle, passes the same shape.
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    // 2dp - the ring every other focused surface draws. A tile
                    // that carries its own 3dp ring is not "more focused", it
                    // is a second focus language.
                    border = androidx.compose.foundation.BorderStroke(2.dp, KBAccent),
                    shape = CircleShape
                )
            ),
            // The avatar tiles are the biggest targets on this screen and they
            // were the only focusable surface in the app with no glow at all,
            // so crossing them lit a ring and nothing else. 8dp: a tile's glow
            // is the chip's, not the card's 12dp - see KBFocusGlowSmall.
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(elevationColor = KBAccent, elevation = KBFocusGlowSmall)
            ),
            scale = ClickableSurfaceDefaults.scale(
                focusedScale = KBFocusTile,
                pressedScale = KBFocusPressed
            ),
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocused(it.isFocused)
                }
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
                            .crossfade(false)
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
                            // Oswald through the type scale, like every other
                            // letter in the app (displayMedium is the 34sp Bold
                            // this literal was standing in for).
                            style = MaterialTheme.typography.displayMedium,
                            color = Color(fg)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(KBSurfaceRaised),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_profile_manage),
                            contentDescription = "Manage profiles",
                            tint = KBTextLo,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }

        Text(
            text = name,
            color = if (focused) KBTextHi else KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
