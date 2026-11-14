package com.kennyb1201.kbstream.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.anime.AniListAuth
import com.kennyb1201.kbstream.data.anime.AniListConnectionState
import com.kennyb1201.kbstream.data.kitsu.KitsuAuth
import com.kennyb1201.kbstream.data.kitsu.KitsuConnectionState
import com.kennyb1201.kbstream.data.library.TasteRatingSubmission
import com.kennyb1201.kbstream.data.library.TasteRatingTracker
import com.kennyb1201.kbstream.data.mal.MalApiClient
import com.kennyb1201.kbstream.data.mal.MalAuth
import com.kennyb1201.kbstream.data.mal.MalConnectionState
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import kotlinx.coroutines.delay

/**
 * The three anime tracker rows - AniList, Kitsu, MyAnimeList - for BOTH Settings
 * surfaces (spec 2026-11-13: AniList/Kitsu on TV + phone, MAL on both).
 *
 * The rows were the gap the field report named: the MAL backend was complete
 * and had no row anywhere, and AniList/Kitsu connected only on the TV. The fix
 * is one block rather than six rows, so a row cannot exist on one surface and
 * not the other, and the labels, descriptions, states, dialogs and disconnect
 * behaviour are the same code on both. The two screens differ only in the plate:
 * [row] is the surface's own row component (the TV pane's `NavigationRow`, the
 * phone pane's `SettingsNavRowMobile`), exactly as their neighbouring rows are
 * drawn.
 *
 * WHAT EACH ROW IS:
 *  - AniList: the phase-1 public-username read plus the implicit-grant connect
 *    (paste the token AniList shows). No client secret exists anywhere in it.
 *  - Kitsu: the phase-1 public-username read plus the password grant.
 *  - MAL: OAuth only - MAL has no public username read - through [MalAuth]'s
 *    PKCE flow. On a phone the connect opens MAL in the browser; on a TV the
 *    dialog shows `MalLinks.startLink()` as a QR, because scanning it runs the
 *    connect on the phone and the session reaches the TV through the profile's
 *    synced `KEY_MAL_AUTH`. Either way "connected as <name>" comes from MAL's own
 *    `/users/@me` read, cached per profile.
 *
 * THE STATES RE-SEED on the profile and the sync revision, on every dialog
 * action, and when the pane comes back to the foreground: a MAL connect finishes
 * in a browser (or on another device), so nothing on this screen sees the change
 * when it happens, and the resume is what makes the row stop saying "connect".
 * The one delayed re-read covers the exchange still running as the pane resumes.
 *
 * DISCONNECT IS PER PROFILE AND LOCAL-SAFE: it drops that profile's session (and
 * the rating queue's view of that tracker) and touches no history and no other
 * tracker - the same contract the TV rows already had.
 */
@Composable
internal fun AnimeTrackerRows(
    phone: Boolean,
    row: @Composable (label: String, description: String, onClick: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val profileRowKey = rememberProfileRowKey()
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()

    var resumeRevision by remember { mutableStateOf(0) }
    var settledRevision by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeRevision++ }
    LaunchedEffect(resumeRevision) {
        if (resumeRevision > 0) {
            // Returning from the browser can beat the code exchange by a beat;
            // one re-read after it lands is what turns the row to "connected".
            delay(1_500)
            settledRevision++
        }
    }

    // ── AniList ────────────────────────────────────────────────────────────
    val aniListUsername = remember(profileRowKey, syncedRevision) {
        AppPreferences.getAniListUsername(context)
    }
    var aniListEditor by remember { mutableStateOf(false) }
    var aniListRevision by remember { mutableStateOf(0) }
    val aniListState = remember(
        profileRowKey,
        syncedRevision,
        resumeRevision,
        settledRevision,
        aniListRevision
    ) {
        AniListAuth.connectionState(context)
    }
    val aniListViewer = remember(
        profileRowKey,
        syncedRevision,
        resumeRevision,
        settledRevision,
        aniListRevision
    ) {
        AniListAuth.viewerName(context)
    }
    row(
        when (aniListState) {
            // Same rule as the MAL row: a build with no app id says so, because
            // the Connect can do nothing and no setting can change that.
            AniListConnectionState.NotConfigured -> "AniList — not configured"
            AniListConnectionState.Connected -> "AniList — connected"
            else -> animeTrackerRowLabel("AniList", aniListUsername)
        },
        when (aniListState) {
            AniListConnectionState.NotConfigured ->
                "This build has no AniList app id, so connecting is unavailable " +
                    "here. The public-profile read still works: enter your AniList " +
                    "username to read a public anime list."
            AniListConnectionState.Connected ->
                "Progress and ratings are written to your AniList list" +
                    (aniListViewer?.let { " ($it)" } ?: "") +
                    ". Open to disconnect, or to read a public profile instead."
            else -> animeTrackerDescription("AniList", aniListUsername)
        },
        { aniListEditor = true }
    )
    if (aniListEditor) {
        AniListConnectDialog(
            state = aniListState,
            authorizeUrl = AniListAuth.authorizeUrl(),
            currentUsername = aniListUsername,
            phone = phone,
            onDismiss = { aniListEditor = false },
            onConnect = { pasted ->
                val result = AniListAuth.connect(context, pasted)
                aniListRevision++
                if (result is AniListAuth.ConnectResult.Failed) {
                    result.reason
                } else {
                    // A connect re-opens the rating leg, so anything queued while
                    // the session was dead is due now.
                    TasteRatingSubmission.onTrackerConnected(
                        context,
                        TasteRatingTracker.ANILIST
                    )
                    null
                }
            },
            onDisconnect = {
                AniListAuth.disconnect(context)
                TasteRatingSubmission.onTrackerDisconnected(
                    context,
                    TasteRatingTracker.ANILIST
                )
                aniListRevision++
            },
            onSaveUsername = { username ->
                AppPreferences.setAniListUsername(context, username)
            }
        )
    }

    // ── Kitsu ──────────────────────────────────────────────────────────────
    val kitsuUsername = remember(profileRowKey, syncedRevision) {
        AppPreferences.getKitsuUsername(context)
    }
    var kitsuEditor by remember { mutableStateOf(false) }
    var kitsuRevision by remember { mutableStateOf(0) }
    val kitsuState = remember(
        profileRowKey,
        syncedRevision,
        resumeRevision,
        settledRevision,
        kitsuRevision
    ) {
        KitsuAuth.connectionState(context)
    }
    row(
        if (kitsuState == KitsuConnectionState.Connected) {
            "Kitsu — connected"
        } else {
            animeTrackerRowLabel("Kitsu", kitsuUsername)
        },
        if (kitsuState == KitsuConnectionState.Connected) {
            "Progress and ratings are written to your Kitsu library. " +
                "Open to disconnect, or to read a public profile instead."
        } else {
            animeTrackerDescription("Kitsu", kitsuUsername)
        },
        { kitsuEditor = true }
    )
    if (kitsuEditor) {
        KitsuConnectDialog(
            state = kitsuState,
            currentUsername = kitsuUsername,
            phone = phone,
            onDismiss = { kitsuEditor = false },
            onConnect = { email, password ->
                val result = KitsuAuth.connect(context, email, password)
                kitsuRevision++
                if (result is KitsuAuth.ConnectResult.Failed) {
                    result.reason
                } else {
                    TasteRatingSubmission.onTrackerConnected(
                        context,
                        TasteRatingTracker.KITSU
                    )
                    null
                }
            },
            onDisconnect = {
                KitsuAuth.disconnect(context)
                TasteRatingSubmission.onTrackerDisconnected(
                    context,
                    TasteRatingTracker.KITSU
                )
                kitsuRevision++
            },
            onSaveUsername = { username ->
                AppPreferences.setKitsuUsername(context, username)
                kitsuEditor = false
            }
        )
    }

    // ── MyAnimeList ────────────────────────────────────────────────────────
    var malEditor by remember { mutableStateOf(false) }
    var malRevision by remember { mutableStateOf(0) }
    val malState = remember(
        profileRowKey,
        syncedRevision,
        resumeRevision,
        settledRevision,
        malRevision
    ) {
        MalAuth.connectionState(context)
    }
    var malViewer by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        profileRowKey,
        syncedRevision,
        resumeRevision,
        settledRevision,
        malRevision,
        malState
    ) {
        malViewer = if (malState == MalConnectionState.Connected) {
            MalApiClient.viewerName(context)
        } else {
            null
        }
    }
    row(
        malRowLabel(malState, malViewer),
        malRowDescription(malState, malViewer),
        { malEditor = true }
    )
    if (malEditor) {
        MalConnectDialog(
            state = malState,
            viewerName = malViewer,
            phone = phone,
            onDismiss = { malEditor = false },
            onDisconnect = { malRevision++ }
        )
    }
}
