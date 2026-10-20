package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.sync.KidsGate
import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * Who's watching?, for a finger.
 *
 * The same picker rules as the TV screen, unchanged - only the surfaces are the
 * phone's:
 *
 *  - tapping the active profile enters the app without rebinding the
 *    singletons;
 *  - tapping a profile with its own PIN raises the Enter PIN dialog and asks
 *    for THAT profile's code, auto-verifying on the fourth digit;
 *  - leaving a gated kids profile (switching, or opening Manage) raises the
 *    exit gate, answered by the profile's PIN or the device's parent PIN;
 *  - with neither credential set the gate CREATES the parent PIN instead of
 *    failing open (see [KidsGate]);
 *  - MANAGE sits in the same grid as the people.
 *
 * There is no D-pad focus ring, no first-tile focus request and no dialog focus
 * retry: the avatar is a tappable circle, and a dialog is a modal layer. Manage
 * still opens the TV profile editor, which is not ported yet (recorded in
 * docs/mobile_port.md).
 */
@Composable
fun ProfilePickerScreenMobile(
    onSelect: () -> Unit,
    onManage: () -> Unit
) {
    val context = LocalContext.current
    val profiles by ProfileManager.profiles.collectAsStateWithLifecycle()
    val active by ProfileManager.activeProfile.collectAsStateWithLifecycle()

    var pinTarget by remember { mutableStateOf<ProfileManager.Profile?>(null) }
    var pinEntry by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    var exitGateAction by remember { mutableStateOf<ExitGateAction?>(null) }
    var exitGateEntry by remember { mutableStateOf("") }
    var exitGateError by remember { mutableStateOf(false) }
    var exitGateNew by remember { mutableStateOf("") }
    var exitGateConfirm by remember { mutableStateOf("") }
    var exitGateCreateError by remember { mutableStateOf<String?>(null) }

    val exitGateActive = KidsGate.leavingNeedsPin(active)
    val exitGateHasCredential = KidsGate.hasCredential(active)

    fun clearExitGate() {
        exitGateAction = null
        exitGateEntry = ""
        exitGateError = false
        exitGateNew = ""
        exitGateConfirm = ""
        exitGateCreateError = null
    }

    fun openExitGate(action: ExitGateAction) {
        pinTarget = null
        exitGateAction = action
        exitGateEntry = ""
        exitGateError = false
        exitGateNew = ""
        exitGateConfirm = ""
        exitGateCreateError = null
    }

    fun pick(profile: ProfileManager.Profile) {
        when {
            active?.id == profile.id -> onSelect()
            ProfileManager.hasPin(profile) -> {
                pinTarget = profile
                pinEntry = ""
                pinError = false
            }
            exitGateActive -> openExitGate(ExitGateAction.Switch(profile))
            else -> {
                ProfileManager.setActive(context, profile)
                onSelect()
            }
        }
    }

    MobileTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Who's watching?",
                color = KBTextHi,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(top = 24.dp)
            ) {
                items(profiles, key = { "profile:${it.id}" }) { profile ->
                    ProfileAvatarTileMobile(
                        name = profile.name,
                        avatarIndex = profile.avatarIndex,
                        customAvatarUrl = profile.customAvatarUrl ?: profile.avatarData,
                        selected = active?.id == profile.id,
                        onClick = { pick(profile) }
                    )
                }
                item(key = "manage") {
                    ProfileAvatarTileMobile(
                        name = "MANAGE",
                        avatarIndex = -1,
                        customAvatarUrl = null,
                        selected = false,
                        onClick = {
                            if (exitGateActive) {
                                openExitGate(ExitGateAction.Manage)
                            } else {
                                onManage()
                            }
                        }
                    )
                }
            }
        }

        // The ENTER gate: a profile with its own PIN.
        pinTarget?.let { pinProfile ->
            Dialog(
                onDismissRequest = {
                    pinTarget = null
                    pinEntry = ""
                    pinError = false
                }
            ) {
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
                        modifier = Modifier.fillMaxWidth(),
                        keyboardType = KeyboardType.NumberPassword,
                        visualTransformation = PasswordVisualTransformation()
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

            // Four digits verify themselves; a wrong PIN clears the entry and
            // shows the error instead of kicking the viewer out.
            LaunchedEffect(pinEntry, pinProfile) {
                if (pinEntry.length == 4) {
                    if (ProfileManager.verifyPin(pinProfile, pinEntry)) {
                        pinTarget = null
                        pinEntry = ""
                        pinError = false
                        ProfileManager.setActive(context, pinProfile)
                        onSelect()
                    } else {
                        pinError = true
                        pinEntry = ""
                    }
                }
            }
        }

        // The EXIT gate: switching away from a gated kids profile, or opening
        // Manage from it. With no credential set it creates the parent PIN and
        // then runs the action, so a gated profile can never be left
        // unauthenticated.
        exitGateAction?.let { action ->
            val gateProfile = active
            val creating = gateProfile != null && !exitGateHasCredential
            val runGateAction = {
                clearExitGate()
                when (action) {
                    is ExitGateAction.Switch -> {
                        ProfileManager.setActive(context, action.target)
                        onSelect()
                    }
                    ExitGateAction.Manage -> onManage()
                }
            }

            Dialog(onDismissRequest = { clearExitGate() }) {
                KBDialogPanel(
                    title = "Parent PIN",
                    subtitle = gateProfile?.name,
                    width = 460.dp
                ) {
                    Text(
                        text = if (creating) {
                            "This profile asks for a PIN before it can be left, and " +
                                "no PIN is set on this device yet. Set one now - it " +
                                "stays with the device, not the profile."
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
                            modifier = Modifier.fillMaxWidth(),
                            keyboardType = KeyboardType.NumberPassword,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        KBTextField(
                            value = exitGateConfirm,
                            onValueChange = {
                                exitGateConfirm = it.filter(Char::isDigit).take(4)
                                exitGateCreateError = null
                            },
                            placeholder = "Confirm PIN",
                            modifier = Modifier.fillMaxWidth(),
                            keyboardType = KeyboardType.NumberPassword,
                            visualTransformation = PasswordVisualTransformation()
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
                            KBButton(
                                label = "SAVE",
                                modifier = Modifier.weight(1f)
                            ) {
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
                            KBButton(
                                label = "CANCEL",
                                modifier = Modifier.weight(1f)
                            ) {
                                clearExitGate()
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
                            modifier = Modifier.fillMaxWidth(),
                            keyboardType = KeyboardType.NumberPassword,
                            visualTransformation = PasswordVisualTransformation()
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
                            clearExitGate()
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
}

/** What to run after the CURRENT gated profile's PIN is verified. */
private sealed class ExitGateAction {
    data class Switch(val target: ProfileManager.Profile) : ExitGateAction()
    object Manage : ExitGateAction()
}

/**
 * One circular avatar, or the MANAGE action in the same slot.
 *
 * The circle IS the tap target on a phone, so the tile is a clipped clickable
 * Box rather than a surface: the selected profile keeps its 3dp light ring, and
 * the label sits under it exactly as on TV.
 */
@Composable
private fun ProfileAvatarTileMobile(
    name: String,
    avatarIndex: Int,
    customAvatarUrl: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            ProfileAvatarCircleMobile(
                name = name,
                avatarIndex = avatarIndex,
                customAvatarUrl = customAvatarUrl,
                size = 76.dp,
                ringWidth = if (selected) 3.dp else 0.dp
            )
        }

        Text(
            text = name,
            color = if (selected) KBTextHi else KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * One profile's avatar, as a circle - the single place the phone draws it.
 *
 * The picker uses it at 76dp with the selected tile's light ring and the bottom
 * bar at 30dp with no ring, but the resolution is one implementation: an
 * uploaded/custom image first, then the generic palette plus the initial, then
 * a manage glyph when there is no avatar at all. A profile therefore cannot
 * show one face in Who's Watching and another in the bar. Callers resolve the
 * image themselves - the pickers hand in `customAvatarUrl ?: avatarData`.
 */
@Composable
internal fun ProfileAvatarCircleMobile(
    name: String,
    avatarIndex: Int,
    customAvatarUrl: String?,
    size: Dp,
    ringWidth: Dp = 0.dp
) {
    when {
        customAvatarUrl != null -> AsyncImage(
            model = customAvatarUrl,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .border(width = ringWidth, color = KBTextHi, shape = CircleShape)
        )

        avatarIndex >= 0 -> {
            val (bg, fg) = ProfileManager.AVATAR_COLORS[
                avatarIndex.coerceIn(0, ProfileManager.AVATAR_COUNT - 1)
            ]
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(bg))
                    .border(width = ringWidth, color = KBTextHi, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    // Scaled from the circle instead of fixed: this one avatar
                    // is a 76dp tile in the picker and a 30dp dot in the bar,
                    // and a single type size cannot fit both.
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = (size.value * 0.36f).sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = Color(fg),
                    maxLines = 1
                )
            }
        }

        else -> Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(KBSurfaceRaised),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_profile_manage),
                contentDescription = "Manage profiles",
                tint = KBTextLo,
                modifier = Modifier.size(size * 0.4f)
            )
        }
    }
}
