package com.kennyb1201.kbstream.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.kennyb1201.kbstream.data.library.TasteSignals
import com.kennyb1201.kbstream.data.notifications.AirReminderRules
import com.kennyb1201.kbstream.data.notifications.AirReminderStore
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusNone
import com.kennyb1201.kbstream.ui.theme.KBFocusRow
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
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
    /**
     * False for the one kind of row that is shown but cannot be pressed.
     *
     * The touch sheet draws it muted with [description] under it, because a
     * disabled row with no words is a bug the viewer has to guess at (the
     * download gate for a protected title is the case this exists for). Every
     * other menu in the app leaves it at the default.
     */
    val isEnabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * The order a long-press menu's rows are drawn in, by *function* rather than by
 * the order each caller happens to list them in.
 *
 * Every poster menu in the app is built by [PosterContextMenu] from the same
 * pieces - the shared library rows, the derived play rows, and whatever the
 * caller adds - so without a canonical order the sequence drifted: a rail menu
 * read "Add to Library, Open in Grid, Go to Details, …" while the Home
 * continue-watching menu read "Go to Details, Play Manually, …". Sorting by
 * this rank gives every menu the same section order, stable within a section so
 * a caller's own rows keep their relative sequence:
 *
 *  0. Watched - any "Mark …" row, FIRST in every menu that has one
 *  1. Open / navigate - "Go to Details", "Open in Grid", "Add to Home"
 *  2. Playback - "Play Manually", "Play from Beginning", "Resume"
 *  3. Library - "Add to Library" / "In Library ✓", "Add to list…"
 *  4. Taste - "Like this title" / "Liked ✓", "Dislike this title" / "Disliked ✓"
 *  5. Reminders - "Remind me when it airs"
 *  6. Anything else the caller adds
 *  7. Destructive last - "Hide", "Remove"
 *
 * The watched row leads because it is the action a long press is most often
 * opened to do - "this one is watched already" - and it used to sit sixth, past
 * rows that navigate away from the title. It keeps the top of the list, and the
 * first row is where focus lands, so one press of Select does the thing the
 * viewer reached for; destructive rows stay last (below) so nothing that removes
 * content is ever a stray Down press from where the menu opens.
 *
 * Taste sits directly under the library rows because both are the viewer's own
 * verdict on the title (keep it / rate it) rather than something the app does
 * with it, and above the reminder because a reminder is about a future episode
 * while a signal is about the title as it stands. It is ranked by LABEL PREFIX,
 * not by who added the row, so the TV panel and the phone sheet - which build
 * their rows independently - still order them the same way.
 */
internal fun contextMenuSectionRank(action: PosterContextAction): Int = when {
    // Checked before the label so "Hide"/"Remove" can never be hoisted by a
    // prefix match: the one row that takes the title off the screen stays last.
    action.isDestructive -> 7
    action.label.startsWith("Mark") -> 0
    action.label == "Add to Home" ||
        action.label.startsWith("Go to") ||
        action.label.startsWith("Open") -> 1
    action.label.startsWith("Play") || action.label.startsWith("Resume") -> 2
    action.label.startsWith("Add to Library") ||
        action.label.startsWith("In Library") ||
        action.label.startsWith("Add to list") ||
        action.label.startsWith("Remove from Library") ||
        action.label.startsWith("Add to My List") ||
        action.label.startsWith("Remove from My List") -> 3
    action.label.startsWith("Like") ||
        action.label.startsWith("Dislike") ||
        action.label.startsWith("Rate") -> 4
    action.label.startsWith("Remind") -> 5
    else -> 6
}

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
    val ids: List<String?>,
    /** The title's year, when the surface has one, so the hidden entry can be
     *  matched by name without catching a different title that shares it. */
    val year: Int? = null
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
    ids: List<String?>,
    year: Int? = null
): HideTarget? {
    val usable = ids.filterNotNull().mapNotNull { HiddenTitles.normalizeId(it) }
    if (usable.isEmpty()) return null
    return HideTarget(
        title = title.trim(),
        mediaType = mediaType,
        posterUrl = posterUrl?.takeIf { it.isNotBlank() },
        ids = ids,
        year = year
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
 * The active profile's taste signal for one title, re-read whenever the store
 * changes, so a thumb set from a poster menu flips the Detail page's own row
 * without a revisit - and the other way round. Same repaint rule as the
 * library toggle's [LibraryAdds.revision]: state is painted from the store,
 * never from the last press.
 *
 * [ids] are the spellings this surface knows, in the watched-key order (the
 * resolved IMDB flavor first, the raw `tmdb:<n>` second); the name and year
 * bridge the two id namespaces when only one of them is known.
 */
@Composable
fun rememberTasteSignal(
    mediaType: String?,
    title: String?,
    year: Int?,
    ids: List<String?>
): String? {
    val context = LocalContext.current
    val version by TasteSignals.version.collectAsStateWithLifecycle()
    return remember(version, mediaType, title, year, ids) {
        TasteSignals.signalFor(context, mediaType, title, year, ids)
    }
}

/**
 * The numeric rating this title carries, repainted on every store change - the
 * rating twin of [rememberTasteSignal], so a number set from a poster menu shows
 * on the title's own page without a revisit, and vice versa.
 */
@Composable
fun rememberTasteRating(
    mediaType: String?,
    title: String?,
    year: Int?,
    ids: List<String?>
): Int? {
    val context = LocalContext.current
    val version by TasteSignals.version.collectAsStateWithLifecycle()
    return remember(version, mediaType, title, year, ids) {
        TasteSignals.ratingFor(context, mediaType, title, year, ids)
    }
}

/**
 * The taste rows one title's menu carries: "Like this title" / "Liked ✓" and
 * the dislike pair.
 *
 * Built here rather than at the two menus' call sites (the TV panel and the
 * phone sheet) for the same reason the library rows are: a long press has to
 * mean the same thing on both, and a signal set from a rail has to be the same
 * signal the title's own page paints. The title's identity is the menu's own
 * [HideTarget] - the id spellings the surface already knows - so the store keys
 * a press here exactly as it keys a press from Detail.
 *
 * The labels carry the state-mark pattern the library and reminder rows use
 * ("Liked ✓"), and the press reports through the shared feedback channel: a
 * row that changes what the app thinks of a title while saying nothing is
 * indistinguishable from a row that failed. Pressing the active thumb clears
 * it, pressing the other flips it (see [TasteSignals.nextSignal]).
 */
internal fun tasteMenuActions(
    context: android.content.Context,
    target: HideTarget,
    feedback: KBFeedbackState,
    dismiss: () -> Unit,
    onRate: () -> Unit
): List<PosterContextAction> {
    val current = TasteSignals.signalFor(
        context = context,
        mediaType = target.mediaType,
        title = target.title,
        year = target.year,
        ids = target.ids
    )
    val rating = TasteSignals.ratingFor(
        context = context,
        mediaType = target.mediaType,
        title = target.title,
        year = target.year,
        ids = target.ids
    )

    val press: (String) -> Unit = { signal ->
        val next = TasteSignals.nextSignal(current, signal)
        val stored = if (next == null) {
            TasteSignals.clearSignal(
                context = context,
                mediaType = target.mediaType,
                title = target.title,
                year = target.year,
                ids = target.ids
            )
        } else {
            TasteSignals.setSignal(
                context = context,
                mediaType = target.mediaType,
                title = target.title,
                posterUrl = target.posterUrl,
                year = target.year,
                ids = target.ids,
                signal = next
            )
        }
        feedback.show(
            text = when {
                !stored -> "Couldn't save your rating"
                next == null -> "Rating removed: ${target.title}"
                next == TasteSignals.LIKE -> "Liked: ${target.title}"
                else -> "Disliked: ${target.title}"
            },
            isError = !stored
        )
        dismiss()
    }

    return listOf(
        PosterContextAction(
            label = if (current == TasteSignals.LIKE) "Liked ✓" else "Like this title",
            description = if (current == TasteSignals.LIKE) {
                "Already liked on this profile"
            } else {
                "Tell the recommendations you like this title"
            }
        ) { press(TasteSignals.LIKE) },
        PosterContextAction(
            label =
                if (current == TasteSignals.DISLIKE) "Disliked ✓" else "Dislike this title",
            description = if (current == TasteSignals.DISLIKE) {
                "Already disliked on this profile"
            } else {
                "Keep this title out of your recommendations"
            }
        ) { press(TasteSignals.DISLIKE) },
        // The numeric rating, in the same taste section: the row OPENS the
        // rating dialog rather than writing, and it does not dismiss the menu -
        // the dialog takes over the layer and the menu goes when it closes (see
        // the two menus' own hosting). That is what makes a title rateable from
        // a rail: no trip through Detail.
        PosterContextAction(
            label = if (rating != null) "Rated $rating/10 ✓" else "Rate this title",
            description = if (rating != null) {
                "Already rated $rating out of 10 on this profile"
            } else {
                "Rate this title from 1 to 10"
            }
        ) { onRate() }
    )
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
    hideTarget: HideTarget? = null,
    /**
     * The title's library rows: "Add to Library" and "Add to list…".
     *
     * Passing the title's ids gives this menu both rows, in the place and with
     * the behavior every other menu in the app has them (see [LibraryAdds]),
     * which is the point of them living here rather than being spelled out at
     * each of the ~15 call sites: a long press on a poster means the same thing
     * wherever it happens.
     *
     * Null means the menu must not offer them, and only two kinds of menu are
     * in that position: the Continue Watching card, which is one in-progress
     * episode of a show whose own poster carries both rows, and the detail
     * screen's season and episode menus, which play one specific episode
     * rather than being about a title at all. Every menu about a title — any
     * poster, any rail, any search result — passes it.
     */
    libraryTarget: LibraryAddTarget? = null,
    /**
     * Called when the viewer picks "Hide", BEFORE the title is actually taken
     * off the screens (and before [onDismiss]).
     *
     * The subject of the menu is about to leave the composition, so it is not a
     * place focus can be handed to - and the surrounding rail has to name what
     * takes its place while the gone cell is still in the tree. Only a caller
     * that can name a neighbour passes this (see the search results rail in
     * SearchScreen); every other surface lets the framework move focus to the
     * nearest remaining card, which is what the Hide row's own hand-back has
     * always relied on.
     */
    onBeforeHide: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // Every screen's poster menu shares this one channel, so the confirmation
    // for a destructive action lands in the same place app-wide.
    val feedback = rememberKBFeedback()

    // Library rows' state. Whether the title is already saved is read as the
    // menu is composed — it only exists while it is open, so the answer is as
    // fresh as the card that was pressed. The add itself runs on
    // [LibraryAdds]'s own scope, because the menu is dismissed as the row is
    // pressed; see `LibraryAdds.runAdd`.
    val librarySaved = libraryTarget != null && LibraryAdds.inMyList(context, libraryTarget)
    var libraryPickTarget by remember {
        mutableStateOf<LibraryAddTarget?>(null)
    }

    // The title the rating dialog is open for, if any. Hosted here beside the
    // list picker, for the same reason: the "Rate this title" row and the
    // dialog it opens must be composed together, once.
    var ratingTarget by remember {
        mutableStateOf<HideTarget?>(null)
    }

    // The air reminder row, only for series (a movie has no air date). State is
    // kept here, above the row builder, so the label can flip in place as the
    // row is pressed without recomposing the whole menu through a reload.
    val reminderShowId = libraryTarget
        ?.takeIf { isSeriesType(it.mediaType) }
        ?.let { AirReminderRules.showIdFor(it.imdbId, it.tmdbId) }
    var reminderOn by remember(reminderShowId) {
        mutableStateOf(
            reminderShowId != null && AirReminderStore(context).isFlagged(reminderShowId)
        )
    }

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
    //
    // The list is then put in the app's canonical section order (see
    // [contextMenuSectionRank]) so every menu reads the same way; the sort is
    // stable, so a caller's own rows keep their relative order inside a section.
    /*
     * ...and the way out.
     *
     * Dismissal used to unmount the panel and its scrim in the frame the press
     * landed - the entrance was animated, the exit was a cut. The menu now fades
     * and shrinks over [KB_PANEL_EXIT_MS] and only then calls the caller's
     * [onDismiss], which is what actually unmounts it.
     *
     * The focus hand-back is untouched in ORDER and only shifted in TIME: the
     * caller captures its target and hands focus back in the same code it always
     * ran, after this. Nothing is asked for focus while the menu is fading, and
     * the menu stops taking input the moment the fade starts, so the 120ms
     * cannot swallow a press or re-trigger a row.
     *
     * Declared HERE, above the rows, because every row that closes the menu
     * calls it: a local declared after the list would not be in scope inside
     * the row lambdas that build it.
     */
    val exitScope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }
    val exit = remember { Animatable(1f) }
    val dismiss: () -> Unit = {
        if (!dismissing) {
            dismissing = true
            exitScope.launch {
                exit.animateTo(
                    0f,
                    tween(durationMillis = KB_PANEL_EXIT_MS, easing = KB_EASE)
                )
                onDismiss()
            }
        }
    }

    val rows = buildList {
        // The library rows are added here, but their PLACE in the menu is
        // decided by [contextMenuSectionRank] (the library section), not by
        // this order - the sort below puts them in the same place on every
        // menu that carries them.
        //
        // "In Library ✓" is a state, not a different action: pressing it again
        // re-runs the add, which is how a title whose tracker mirror failed
        // earlier heals (the local write is idempotent, and the mirrors fire
        // whether or not it changed anything). The confirmation goes to the
        // app's shared feedback channel, because a menu row that saves a title
        // while saying nothing at all is indistinguishable from a row that
        // failed.
        libraryTarget?.let { target ->
            add(
                PosterContextAction(
                    label = if (librarySaved) "In Library ✓" else "Add to Library",
                    description = if (librarySaved) {
                        "Already on this profile's My List"
                    } else {
                        "Save to My List" + LibraryAdds.mirrorSuffix(context)
                    }
                ) {
                    LibraryAdds.add(context, target) { added ->
                        feedback.show(
                            text = if (added) {
                                "Added to My List: ${target.title}"
                            } else {
                                "Couldn't add ${target.title}"
                            },
                            isError = !added
                        )
                    }
                    dismiss()
                }
            )
            add(
                PosterContextAction(
                    label = "Add to list…",
                    description = "Pick a personal list or watchlist"
                ) {
                    libraryPickTarget = target
                }
            )
        }

        // The taste rows, for any menu that is about a title at all (the same
        // hide target the library rows key off). A menu built around one
        // episode - Continue Watching, the season and episode lists - passes
        // no hide target and so offers no thumbs, which is right: a signal is
        // about a title, and those rows are about a file.
        hideTarget?.let { target ->
            addAll(
                tasteMenuActions(context, target, feedback, dismiss) {
                    ratingTarget = target
                }
            )
        }

        if (reminderShowId != null && libraryTarget != null) {
            add(
                PosterContextAction(
                    label = if (reminderOn) "Reminder on ✓" else "Remind me when it airs",
                    description = if (reminderOn) {
                        "You'll be alerted on the day a new episode airs"
                    } else {
                        "Get a notification on the day a new episode airs"
                    }
                ) {
                    val nowOn = AirReminderStore(context).toggle(reminderShowId)
                    reminderOn = nowOn
                    // The pref alone schedules nothing: re-arm the round so a
                    // show flagged for an episode airing today is checked now,
                    // and a removed flag can drop the work when it was the last
                    // one.
                    runCatching {
                        com.kennyb1201.kbstream.work.NewEpisodeWorker.syncScheduleForPrefs(
                            context,
                            runImmediate = nowOn
                        )
                    }
                    feedback.show(
                        text = if (nowOn) {
                            "Reminder on: ${libraryTarget.title}"
                        } else {
                            "Reminder off: ${libraryTarget.title}"
                        }
                    )
                }
            )
        }

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

        // Hide is destructive, so [contextMenuSectionRank] sorts it into the
        // last section: it is the one action here that takes the menu's own
        // subject off the screen, so it sits below everything a viewer reaches
        // for and never at a D-pad distance they could hit while aiming at
        // something else.
        hideTarget?.let { target ->
            add(
                PosterContextAction(
                    // "everywhere" is what separates this row from a rail's own
                    // "Remove from Continue Watching" / "Remove from Home"
                    // sitting above it: those take the title off ONE row, this
                    // takes it off every screen, and the bare "Hide" read as the
                    // same kind of thing at a glance.
                    label = "Hide everywhere",
                    description = "Remove this title from every screen",
                    isDestructive = true
                ) {
                    // The caller's hand-off runs FIRST: the subject is about to
                    // leave the composition, so a rail that knows which cell
                    // takes its place has to focus that cell while the gone one
                    // is still attached to hand the focus over.
                    onBeforeHide?.invoke()
                    // Capture the key forms BEFORE hiding: the stored entry
                    // keeps every spelling of the title, and the undo has to
                    // match one of them.
                    val hiddenKeys = HiddenTitles.keysFor(target.mediaType, target.ids)
                    val stored = HiddenTitles.hide(
                        context = context,
                        title = target.title,
                        mediaType = target.mediaType,
                        posterUrl = target.posterUrl,
                        ids = target.ids,
                        year = target.year
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
                    dismiss()
                }
            )
        }
    }.sortedBy { contextMenuSectionRank(it) }

    val dialogShape = KBShapePanel

    // Dismiss on system Back. BackHandler is used instead of key-event
    // intercepts because the Activity back dispatcher consumes the Back key
    // before it ever reaches compose key handlers.
    BackHandler {
        dismiss()
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

    /*
     * The menu fades up instead of appearing in one frame.
     *
     * Every poster menu in the app is this component - Home's title menu, the
     * Detail page's poster/backdrop/media menus, the browse grids - and every
     * one of them used to compose the scrim and the panel in the same frame. The
     * recent focus work made DISMISSAL graceful (the hand-back lands before the
     * node leaves); this is the same courtesy on the way in.
     *
     * Read in graphicsLayer so the fade costs no recomposition, and applied to
     * the scrim container as well as the panel so the dim arrives with the menu
     * rather than cutting to 60% black under it.
     */
    val menuEnter = rememberEntranceProgress()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = menuEnter.value * exit.value }
            // The picker is this menu's own second stage, and while it is open
            // the menu and its scrim step out of the way rather than sitting
            // dimmed behind it. The picker is a dialog window of its own, so
            // nothing composed under here can take a press while it is up.
            .alpha(if (libraryPickTarget == null && ratingTarget == null) 1f else 0f)
            .focusGroup()
            .background(KBVoid.copy(alpha = 0.60f))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else if (dismissing) {
                    // The fade is already running; the menu has stopped being
                    // the thing the remote is driving, so swallow the press
                    // rather than letting it reach a row that is on its way out.
                    true
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
                            dismiss()
                            true
                        }

                        else -> false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // A long menu (Home's title menu carries library, reminder, watch and
        // hide rows) is taller than a short panel, and an unscrolled column
        // simply clipped its last row - the destructive "Hide" - off the
        // bottom with no way to D-pad to it. The panel is capped to the window
        // and the rows scroll inside it, so the last action is always
        // reachable.
        val maxPanelHeight = maxHeight * 0.92f

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
            onClick = dismiss,
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
                // is the border and the lifted container color instead.
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
                .heightIn(max = maxPanelHeight)
                .graphicsLayer {
                    val scale = 0.96f + 0.04f * menuEnter.value
                    scaleX = scale * exit.value
                    scaleY = scale * exit.value
                }
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
                    // The scroll that keeps the last row reachable; the focused
                    // row is auto-scrolled into view by Compose.
                    .verticalScroll(rememberScrollState())
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

                // Room below the last row for that row's own focus ring.
                //
                // The ring is drawn OUTSIDE the focused row's layout box -
                // KBFocusRow's 1.02x scale plus the 2dp accent border grow it
                // past its bounds - and the scroll container clips at its own
                // edge, so the 22dp panel padding was not enough: on the field
                // report the bottom of the focused "Hide" row's ring was cut
                // off flush with the menu's rounded bottom edge.
                //
                // A trailing spacer rather than more padding, following the
                // sports hub's list (SportsHubScreen): the scrolling area keeps
                // its visual inset and gains the overflow room, and only the
                // last row's focus ever expands into it.
                Spacer(modifier = Modifier.height(KBFocusRowInset))
            }
        }
    }

    // The rating dialog behind "Rate this title", hosted here so the row can
    // never be offered without it (the list picker's own single-owner rule).
    // Committing or cancelling closes the dialog AND the menu: the viewer asked
    // for the dialog and lands on the screen behind it, not back in the menu.
    ratingTarget?.let { target ->
        val rating = TasteSignals.ratingFor(
            context = context,
            mediaType = target.mediaType,
            title = target.title,
            year = target.year,
            ids = target.ids
        )
        val signal = TasteSignals.signalFor(
            context = context,
            mediaType = target.mediaType,
            title = target.title,
            year = target.year,
            ids = target.ids
        )
        fun closeRating() {
            ratingTarget = null
            dismiss()
        }
        TvTitleRatingDialog(
            title = target.title,
            rating = rating,
            hasSignal = rating != null || signal != null,
            onPick = { number ->
                val stored = TasteSignals.setRating(
                    context = context,
                    mediaType = target.mediaType,
                    title = target.title,
                    posterUrl = target.posterUrl,
                    year = target.year,
                    ids = target.ids,
                    rating = number
                )
                closeRating()
                feedback.show(
                    text = if (stored) {
                        "Rated $number/10: ${target.title}"
                    } else {
                        "Couldn't save your rating"
                    },
                    isError = !stored
                )
            },
            onRemove = {
                val cleared = TasteSignals.clearSignal(
                    context = context,
                    mediaType = target.mediaType,
                    title = target.title,
                    year = target.year,
                    ids = target.ids
                )
                closeRating()
                feedback.show(
                    text = if (cleared) {
                        "Rating removed: ${target.title}"
                    } else {
                        "Couldn't save your rating"
                    },
                    isError = !cleared
                )
            },
            onCancel = { closeRating() }
        )
    }

    // The picker behind the row above, hosted here so a menu can never offer
    // "Add to list…" without it. Ten per-screen copies meant ten chances for
    // one to be composed somewhere its own row's dismissal took it with it.
    libraryPickTarget?.let { target ->
        LibraryAddToListDialog(
            mediaType = target.mediaType,
            imdbId = target.imdbId,
            tmdbId = target.tmdbId,
            title = target.title,
            year = target.year,
            posterUrl = target.posterUrl,
            onDismiss = {
                libraryPickTarget = null
                dismiss()
            }
        )
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
