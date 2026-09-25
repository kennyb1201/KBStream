package com.kennyb1201.kbstream.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusNone
import com.kennyb1201.kbstream.ui.theme.KBFocusRow
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Label for the long-press watched toggle on a poster / title tile. On a
 * SERIES the action marks or clears the whole show — every season, on this
 * device and on the connected trackers — so it says so outright instead of
 * the ambiguous "Mark as Watched", which is what someone hunting for a
 * one-shot "mark the entire series" action needs to be able to find.
 */
fun watchedMenuLabel(
    isWatched: Boolean,
    mediaType: String?
): String = when {
    isSeriesType(mediaType) && isWatched -> "Mark Entire Series as Unwatched"
    isSeriesType(mediaType) -> "Mark Entire Series as Watched"
    isWatched -> "Mark as Unwatched"
    else -> "Mark as Watched"
}

/** Every media-type spelling the app has to treat as a series. */
fun isSeriesType(mediaType: String?): Boolean =
    mediaType?.trim()?.lowercase() in
        setOf("series", "tv", "show", "anime", "anime.series")

/**
 * Description paired with [watchedMenuLabel]: movies flip one title's watched
 * state, a series flips every season of it on this device and on the
 * connected trackers (Simkl / MDBList).
 */
fun watchedMenuDescription(
    isWatched: Boolean,
    mediaType: String?
): String = when {
    isSeriesType(mediaType) && isWatched ->
        "Clear every season on this device and your trackers"
    isSeriesType(mediaType) ->
        "Mark every season watched on this device and your trackers"
    isWatched ->
        "Clear watched status on this device and Simkl"
    else ->
        "Show this title as watched"
}

/**
 * One selectable row in a [PosterContextMenu]. The action itself is
 * responsible for dismissing the menu (the caller normally clears its
 * menu state first, then runs the action / restores focus).
 */
data class PosterContextAction(
    val label: String,
    val description: String? = null,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * What a long-press menu needs in order to hide the title it was opened on:
 * the id spellings the caller knows for it, plus enough of the artwork to
 * draw the row in Settings → Hidden titles after the title itself is gone
 * from every rail that could have shown it.
 */
data class HideTarget(
    val title: String,
    val mediaType: String?,
    val posterUrl: String?,
    val ids: List<String?>
)

/**
 * Builds the hide target for one title, or null when the caller has no id to
 * key it on — a menu for an unidentifiable item simply gets no Hide row
 * instead of one that would hide nothing.
 */
fun hideTarget(
    title: String,
    mediaType: String?,
    posterUrl: String?,
    ids: List<String?>
): HideTarget? {
    val usable = ids.filterNotNull().mapNotNull { HiddenTitles.normalizeId(it) }
    if (usable.isEmpty()) return null
    return HideTarget(
        title = title.trim(),
        mediaType = mediaType,
        posterUrl = posterUrl?.takeIf { it.isNotBlank() },
        ids = ids
    )
}

/**
 * The active profile's hidden titles, re-read whenever the store changes, so
 * a screen that hid a title from its own long-press menu drops it on the next
 * frame without any other plumbing.
 */
@Composable
fun rememberHiddenTitleKeys(): Set<String> {
    val context = LocalContext.current
    val version by HiddenTitles.version.collectAsStateWithLifecycle()
    return remember(version) { HiddenTitles.keys(context) }
}

/**
 * One-shot handoff from a context-menu action to the navigation layer. The
 * detail screen's Play Manually action uses the shared menu, so this keeps
 * that intent available to MainActivity without changing every menu callback.
 */
object ManualSourceSelection {
    /**
     * A short-lived "open the streams picker rather than auto-selecting a
     * source" request. Raised by a "Play Manually" row in this menu and read
     * by whoever takes the user where the picking happens:
     *
     *  - screens that open the streams screen themselves (Continue Watching
     *    on Home) leave it for MainActivity's `onNavigateStreams`, which
     *    consumes it as it builds the screen;
     *  - the other poster menus can only navigate into the detail screen, so
     *    it sits there and the detail screen consumes it when it appears and
     *    hands it on to MainActivity the moment it opens the picker.
     *
     * Either way it is read once and cleared, and it ages out on its own, so
     * a request whose navigation never happened cannot turn a later, ordinary
     * Play press into a picker.
     */
    var requested: Boolean = false
        private set

    private var requestedAtMs: Long = 0L

    /** How long a request stays valid - long enough for a navigation to
     *  happen (including a TMDB id lookup), short enough that a stray one
     *  cannot outlive the press that made it. */
    private const val VALID_FOR_MS = 12_000L

    /**
     * The target the request is FOR, as the same raw "contentType:streamId"
     * key [com.kennyb1201.kbstream.ui.streams.StreamsViewModel] stamps on the
     * streams it loads (see [isPendingPickFor]). Null when the requester only
     * navigates and does not build the target itself - a poster menu row: the
     * target is resolved later on the detail screen, which re-raises the
     * request with the key in hand.
     *
     * Deliberately NOT cleared by [consume]: neither MainActivity nor the
     * detail screen has any use for the target, and the only screen that does
     * is the picker it opens - which is exactly the screen that has to know it
     * must not auto-select a source for it.
     */
    private var pickedKey: String? = null
    private var pickedAtMs: Long = 0L

    /**
     * When a request was raised WITHOUT knowing its target — a poster menu
     * row, or the detail screen's episode-card long-press. [consume] clears
     * the request itself, and the consumer (MainActivity) has no use for the
     * target anyway: the only screen that has to act on it is the picker, and
     * a keyless request can only mean the picker it opens is a manual pick.
     * Kept past [consume] for the same reason [pickedKey] is, and ages out on
     * its own.
     */
    private var keylessAtMs: Long = 0L

    fun request(targetKey: String? = null) {
        requested = true
        requestedAtMs = System.currentTimeMillis()
        if (targetKey != null) {
            pickedKey = targetKey
            pickedAtMs = requestedAtMs
            keylessAtMs = 0L
        } else {
            // A requester that does not know the target yet (a poster menu
            // row, or the episode-card long-press) starts a fresh request, so
            // a target left over from an earlier pick must not answer for it.
            pickedKey = null
            pickedAtMs = 0L
            keylessAtMs = requestedAtMs
        }
    }

    /** Reads the request once and clears it, whether or not it was still fresh. */
    fun consume(): Boolean {
        val wanted =
            requested &&
                System.currentTimeMillis() - requestedAtMs <= VALID_FOR_MS
        requested = false
        requestedAtMs = 0L
        return wanted
    }

    /**
     * True while the picker opened for [targetKey] belongs to a "Play
     * Manually" request, so that opening it must NOT auto-select the top
     * source even with Auto-select turned on. With Auto-select on, "Play
     * Manually" is the one action that is supposed to reach the picker; the
     * poster menu already suppresses the picker's own autoplay for the
     * Continue-Watching route, but the route that goes through the detail
     * screen only opened the picker - the picker then auto-selected the top
     * result and jumped straight to the player, so the picker was never
     * usable.
     *
     * Read-only, so the picker can ask this from composition without a side
     * effect; the request ages out on its own (a later, unrelated pick for the
     * same target inside the window is still the same target and suppressing
     * its autoplay is what the viewer just asked for).
     */
    fun isPendingPickFor(targetKey: String?): Boolean {
        if (targetKey == null) return false
        val now = System.currentTimeMillis()
        val key = pickedKey
        if (key != null) return key == targetKey && now - pickedAtMs <= VALID_FOR_MS
        // No target carried: the request was raised by a screen that only
        // navigates, and any picker opened while it is fresh is that pick.
        return keylessAtMs != 0L && now - keylessAtMs <= VALID_FOR_MS
    }
}

/**
 * One-shot handoff from a poster menu's "Play from Beginning" row to the
 * detail screen, the same way [ManualSourceSelection] hands "Play Manually"
 * over: a poster menu can only navigate into the detail screen, and the
 * play target (position included) is built there. While it is fresh, the
 * detail screen starts the title at position 0 instead of resuming the saved
 * watch history - so the player never falls back to the progress either.
 */
object PlayFromBeginningSelection {
    var requested: Boolean = false
        private set

    private var requestedAtMs: Long = 0L

    /** Same window as [ManualSourceSelection]: long enough for a navigation
     *  (including a TMDB id lookup), short enough that a stray request cannot
     *  outlive the press that made it. */
    private const val VALID_FOR_MS = 12_000L

    fun request() {
        requested = true
        requestedAtMs = System.currentTimeMillis()
    }

    /** Reads the request once and clears it, whether or not it was still fresh. */
    fun consume(): Boolean {
        val wanted =
            requested &&
                System.currentTimeMillis() - requestedAtMs <= VALID_FOR_MS
        requested = false
        requestedAtMs = 0L
        return wanted
    }
}

/**
 * Shared long-press overlay menu for poster cards on every screen (Home
 * rails, actor credits, studio/network rails, genre/keyword rails, ...).
 *
 * Rendered in-window on top of the caller's screen with a dim scrim, just
 * like the per-screen menus it replaced. Three things keep D-pad focus from
 * escaping into the rails behind the scrim:
 *
 *  1. The overlay is its own [focusGroup] - focus search performed from a
 *     focused menu row is bounded by the group, so directional presses
 *     cannot reach focusables under the scrim.
 *  2. Left/Right are swallowed outright (a vertical action list has no
 *     horizontal neighbors), so a stray press can never strand focus on an
 *     invisible item behind the menu - which previously forced users to
 *     press Back to recover.
 *  3. Up/Down are swallowed at the menu's vertical edges (first/last
 *     action). The focusGroup only bounds focus *search* - when the search
 *     finds no next row the key event still falls through to the screen
 *     behind the scrim, whose scrollables/focusables pick it up and steal
 *     focus. Consuming the edge press keeps focus clamped to the menu.
 */
@Composable
fun PosterContextMenu(
    title: String,
    subtitle: String? = null,
    actions: List<PosterContextAction>,
    onDismiss: () -> Unit,
    /**
     * The title this menu was opened on, when the caller can identify it.
     * Adds the destructive "Hide" row that removes it from every screen; see
     * [hideTarget] and [HiddenTitles].
     */
    hideTarget: HideTarget? = null
) {
    val context = LocalContext.current

    // Every screen's poster menu shares this one channel, so the confirmation
    // for a destructive action lands in the same place app-wide.
    val feedback = rememberKBFeedback()

    val firstRowFocusRequester = remember {
        FocusRequester()
    }

    // Focus anchor for the overlay itself: a plain 1x1 focusable spacer that
    // is composed in the same frame as the scrim, so requesting focus on it
    // reliably pulls focus off the poster behind the overlay while the
    // action rows are still attaching. While it (or any row) holds focus,
    // the enclosing focusGroup bounds every directional press to the menu,
    // so the D-pad can never reach the dimmed rails behind it. Once the
    // first row reports focus the anchor stops being focusable so it cannot
    // trap edge navigation itself.
    val overlayFocusRequester = remember {
        FocusRequester()
    }

    // True once the first action row reports it actually holds focus. Used by
    // the LaunchedEffect below to keep re-requesting until focus really lands
    // inside the menu instead of assuming the very first request succeeded.
    var firstActionFocused by remember {
        mutableStateOf(false)
    }

    // True while the invisible overlay anchor holds focus (Phase 1 below).
    var overlayAnchorFocused by remember {
        mutableStateOf(false)
    }

    // Index of the action row that currently holds focus; -1 while the
    // invisible anchor still holds it. Drives the Up/Down edge clamping in
    // the overlay's key handler so a press at the first/last action can
    // never escape into the screen behind the scrim.
    var focusedActionIndex by remember {
        mutableIntStateOf(-1)
    }

    // Every title poster menu offers "Play Manually" and "Play from
    // Beginning", derived from the caller's own "Go to Details" row - same
    // navigation, just without auto-selecting a source, and starting this
    // title over instead of resuming it - dropped in right after it.
    // Deriving them here rather than at each of the ~15 call sites means a
    // long press on a poster means the same thing on every screen (Home
    // rails, actor credits, studio/network and genre rails, decade rails, KB
    // folders, the catalog grid, search results and the detail screen's own
    // rails). "Play from Beginning" is offered whether or not the title has
    // progress: the press is what decides to start over, and a menu that
    // sometimes has the row and sometimes does not is harder to use than one
    // that always does. Callers that already list their own row - Continue
    // Watching and the episode menus, which play one specific episode - keep
    // their wording and their position; menus with no "Go to Details" at all,
    // such as the browse chips, get no row.
    val rows = buildList {
        val alreadyOffered =
            actions.any { it.label == "Play Manually" }
        val alreadyOfferedFromBeginning =
            actions.any { it.label == "Play from Beginning" }
        actions.forEach { action ->
            add(action)
            if (action.label == "Go to Details") {
                if (!alreadyOffered) {
                    add(
                        PosterContextAction(
                            label = "Play Manually",
                            description = "Pick a source instead of auto-selecting"
                        ) {
                            action.onClick()
                        }
                    )
                }
                if (!alreadyOfferedFromBeginning) {
                    add(
                        PosterContextAction(
                            label = "Play from Beginning",
                            description =
                                "Start this title over, ignoring saved progress"
                        ) {
                            // Read by the detail screen this row navigates to
                            // (see PlayFromBeginningSelection), so the title
                            // starts at 0 rather than at the saved position.
                            PlayFromBeginningSelection.request()
                            action.onClick()
                        }
                    )
                }
            }
        }

        // Hide is always the LAST row and always destructive: it is the one
        // action here that takes the menu's own subject off the screen, so it
        // sits below everything a viewer reaches for and never at a D-pad
        // distance they could hit while aiming at something else.
        hideTarget?.let { target ->
            add(
                PosterContextAction(
                    label = "Hide",
                    description = "Remove this title from every screen",
                    isDestructive = true
                ) {
                    // Capture the key forms BEFORE hiding: the stored entry
                    // keeps every spelling of the title, and the undo has to
                    // match one of them.
                    val hiddenKeys = HiddenTitles.keysFor(target.mediaType, target.ids)
                    val stored = HiddenTitles.hide(
                        context = context,
                        title = target.title,
                        mediaType = target.mediaType,
                        posterUrl = target.posterUrl,
                        ids = target.ids
                    )
                    if (stored) {
                        // The card leaves the screen the instant this runs, so
                        // with no message the app just silently deletes a
                        // title. It gets an undo because a menu row that
                        // removes content from EVERY screen is precisely the
                        // kind of action that needs one.
                        feedback.show(
                            text = "Hidden: ${target.title}",
                            actionLabel = "UNDO",
                            onAction = { HiddenTitles.unhide(context, hiddenKeys) }
                        )
                    }
                    // The caller's dismiss restores focus to the poster that
                    // was long-pressed; that card is on its way out of the
                    // composition, so Compose moves focus on to the nearest
                    // poster that is still there.
                    onDismiss()
                }
            )
        }
    }

    val dialogShape = KBShapePanel

    // Dismiss on system Back. BackHandler is used instead of key-event
    // intercepts because the Activity back dispatcher consumes the Back key
    // before it ever reaches compose key handlers.
    BackHandler {
        onDismiss()
    }

    LaunchedEffect(Unit) {
        if (rows.isEmpty()) {
            return@LaunchedEffect
        }

        // Phase 1: land focus inside the overlay immediately. The anchor
        // spacer is a plain focusable already attached to the composition,
        // so this reliably steals focus from the poster behind the scrim
        // within a frame or two (unlike the rows, whose focus tree attach
        // can race the first request).
        for (attempt in 1..10) {
            runCatching {
                overlayFocusRequester.requestFocus()
            }
            if (overlayAnchorFocused) {
                break
            }
            kotlinx.coroutines.delay(16L)
        }

        // Phase 2: move focus onto the first action row. The rows were just
        // added to the composition; if the request fires before the focus
        // tree has attached them it can silently no-op and leave focus on
        // the anchor (still safely inside the group). Retry until the first
        // row really is focused (or the menu is gone), so a D-pad press
        // right after opening can never escape the overlay.
        repeat(40) {
            runCatching {
                firstRowFocusRequester.requestFocus()
            }
            if (firstActionFocused) {
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .background(KBVoid.copy(alpha = 0.60f))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        // No horizontal focus target exists inside a vertical
                        // menu; consuming Left/Right keeps focus trapped on
                        // the action list instead of letting it wander
                        // behind the scrim.
                        Key.DirectionLeft,
                        Key.DirectionRight -> true

                        // Same for the vertical edges: once focus is on the
                        // first/last action there is no next row, so swallow
                        // Up/Down there too. The focusGroup above only bounds
                        // focus *search* - when the search finds nothing the
                        // key event still falls through to the screen behind
                        // the scrim, and its scrollables/focusables steal
                        // focus. Consuming the edge press keeps focus clamped
                        // to the menu. (While the invisible anchor holds
                        // focus, focusedActionIndex is -1, so Up is already
                        // an edge press; Down is still free to reach row 0.)
                        Key.DirectionUp -> focusedActionIndex <= 0
                        Key.DirectionDown ->
                            focusedActionIndex >= rows.lastIndex

                        Key.Back -> {
                            // Some TV platforms deliver BACK straight to the
                            // focused compose hierarchy instead of routing it
                            // through the Activity back dispatcher, which
                            // can make the menu look like it needs two
                            // presses to dismiss. Consume it here too so a
                            // single press always closes the menu; the
                            // BackHandler above remains the primary path and
                            // simply no-ops when this already dismissed it.
                            onDismiss()
                            true
                        }

                        else -> false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Invisible focus anchor: gives the overlay a focused node the moment
        // it appears, so directional presses are bounded by the focusGroup
        // even during the frame(s) before the action rows attach. Drops out
        // of the focus tree once the first row has focus.
        Spacer(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(1.dp)
                .focusProperties {
                    canFocus = !firstActionFocused
                }
                .focusRequester(overlayFocusRequester)
                .onFocusChanged { focusState ->
                    overlayAnchorFocused =
                        focusState.isFocused
                }
        )

        Surface(
            onClick = onDismiss,
            shape = ClickableSurfaceDefaults.shape(
                shape = dialogShape
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = KBSurfaceRaised.copy(alpha = 0.97f),
                contentColor = KBTextHi,
                focusedContainerColor = KBSurfaceRaised.copy(alpha = 0.97f),
                focusedContentColor = KBTextHi
            ),
            scale = ClickableSurfaceDefaults.scale(
                scale = 1f,
                // No growth: this panel is nearly full width, so its focus cue
                // is the border and the lifted container colour instead.
                focusedScale = KBFocusNone
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(
                        1.dp,
                        KBTextLo.copy(alpha = 0.10f)
                    ),
                    shape = dialogShape
                ),
                focusedBorder = Border(
                    border = BorderStroke(
                        1.dp,
                        KBTextLo.copy(alpha = 0.10f)
                    ),
                    shape = dialogShape
                )
            ),
            modifier = Modifier
                .width(380.dp)
                // The dialog container is click-to-dismiss for pointer users
                // only; keeping it out of the focus search means pressing
                // Down/Up past the last/first action can never land D-pad
                // focus on the container itself.
                .focusProperties {
                    canFocus = false
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                subtitle
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sub ->
                        Text(
                            text = sub,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                rows.forEachIndexed { index, action ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    ContextMenuActionRow(
                        label = action.label,
                        isDestructive = action.isDestructive,
                        focusRequester = if (index == 0) {
                            firstRowFocusRequester
                        } else {
                            null
                        },
                        onFocused = {
                            if (index == 0) {
                                firstActionFocused = true
                            }
                            focusedActionIndex = index
                        },
                        onClick = {
                            if (action.label == "Play Manually") {
                                ManualSourceSelection.request()
                            }
                            action.onClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextMenuActionRow(
    label: String,
    isDestructive: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null
) {
    var focused by remember {
        mutableStateOf(false)
    }

    val rowShape = KBShapeCard

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(
            shape = rowShape
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (isDestructive) {
                KBDanger
            } else {
                KBTextHi
            },
            focusedContainerColor = KBAccent.copy(alpha = 0.16f),
            focusedContentColor = KBTextHi
        ),
        scale = ClickableSurfaceDefaults.scale(
            scale = 1f,
            focusedScale = KBFocusRow
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = rowShape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = rowShape
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged { focusState ->
                focused = focusState.isFocused

                if (focusState.isFocused) {
                    onFocused?.invoke()
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
        ) {
            Text(
                text = label,
                color = when {
                    focused -> KBTextHi
                    isDestructive -> KBDanger
                    else -> KBTextHi
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
