package com.kennyb1201.kbstream.ui.profiles

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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised

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
    val profiles by ProfileManager.profiles.collectAsStateWithLifecycle()
    val active by ProfileManager.activeProfile.collectAsStateWithLifecycle()

    // Parental lock: a PIN-protected profile asks for its PIN before it
    // activates. pinTarget holds the pending profile; pinEntry collects the
    // digits; pinError explains a wrong guess.
    var pinTarget by remember { mutableStateOf<ProfileManager.Profile?>(null) }
    var pinEntry by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    // Kids Mode exit gate: what to do once the CURRENT profile's PIN is
    // verified (switch to a target profile, or open Manage).
    var exitGateAction by remember {
        mutableStateOf<ExitGateAction?>(null)
    }

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
                .verticalScroll(rememberScrollState())
                .focusGroup()
                // Spacing lives INSIDE the scroll viewport: the focused
                // tile scales 1.06x, and padding placed before
                // verticalScroll() puts the clip edge right at the first
                // row — cutting off the top of the focus ring. Inside, the
                // ring grows into scrollable space instead.
                .padding(top = 40.dp, bottom = 12.dp)
        ) {
            // Exit gate: when the CURRENTLY ACTIVE kids profile opts into
            // "PIN to leave profile", leaving it (switching to any other
            // profile, or opening Manage) first requires that profile's
            // PIN. ExitGateAction records what to run after verification.
            val exitGateActive = active?.let {
                it.kidsMaxAge != null && it.kidsRequirePinToExit &&
                    ProfileManager.hasPin(it)
            } == true

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
                            // Leaving a gated kids profile asks for ITS pin.
                            exitGateActive -> {
                                pinTarget = null
                                exitGateAction = ExitGateAction.Switch(profile)
                                pinEntry = ""
                                pinError = false
                            }
                            else -> {
                                ProfileManager.setActive(pickerContext, profile)
                                onSelect()
                            }
                        }
                    }
                )
            } + PickerTile(
                name = "Manage",
                avatarIndex = -1,
                customAvatarUrl = null,
                selected = false,
                onClick = {
                    if (exitGateActive) {
                        pinTarget = null
                        exitGateAction = ExitGateAction.Manage
                        pinEntry = ""
                        pinError = false
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

    // PIN dialog target: the DESTINATION profile (enter gate) when
    // pinTarget is set, or the CURRENT profile (kids exit gate) when
    // exitGateAction is set.
    val pinDialogProfile = pinTarget
        ?: exitGateAction?.let { active }

    pinDialogProfile?.let { pinProfile ->
        val isExitGate = exitGateAction != null && pinTarget == null
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                pinTarget = null
                exitGateAction = null
                pinEntry = ""
                pinError = false
            }
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .background(KBSurfaceRaised, RoundedCornerShape(18.dp))
                    .border(1.dp, KBAccent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = pinProfile.name,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isExitGate) {
                        "Enter this profile's PIN to leave it"
                    } else {
                        "Enter PIN"
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
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
                KBCard(
                    onClick = {
                        pinTarget = null
                        exitGateAction = null
                        pinEntry = ""
                        pinError = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "CANCEL",
                        color = KBTextLo,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }

        // Auto-verify the moment four digits are in; a wrong PIN clears the
        // entry and shows the error instead of kicking the user out.
        LaunchedEffect(pinEntry, pinProfile) {
            if (pinEntry.length == 4) {
                if (ProfileManager.verifyPin(pinProfile, pinEntry)) {
                    val action = exitGateAction
                    val wasExitGate = action != null && pinTarget == null
                    pinTarget = null
                    exitGateAction = null
                    pinEntry = ""
                    pinError = false
                    if (wasExitGate) {
                        // Exit gate verified: run the held action (the
                        // destination profile has no PIN of its own — that
                        // case was routed to the enter gate above).
                        // `action` is non-null by construction here
                        // (wasExitGate checks it), so there is no null branch.
                        when (action) {
                            is ExitGateAction.Switch -> {
                                ProfileManager.setActive(pickerContext, action.target)
                                onSelect()
                            }
                            ExitGateAction.Manage -> onManage()
                        }
                    } else {
                        // Enter gate verified: activate the destination.
                        ProfileManager.setActive(pickerContext, pinProfile)
                        onSelect()
                    }
                } else {
                    pinError = true
                    pinEntry = ""
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
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
