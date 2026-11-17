package com.kennyb1201.kbstream.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import android.os.SystemClock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.reporting.SpotlightDiagTrace
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import com.kennyb1201.kbstream.ui.mobile.MobileBillboard
import kotlinx.coroutines.delay

/**
 * How many frames the first-pill claim is retried for before it gives up (F3).
 *
 * 12 frames is ~200 ms at 60 Hz - the same short hand-back window the app's
 * other focus landings use, and long enough for the pill's node to attach after
 * the page that drew it. It is deliberately bounded: a claim that cannot land in
 * this window is not going to, and retrying longer would fight the viewer.
 *
 * Shared with the Home's UP carve-out (F6), which has the same job after it
 * scrolls the hero back: one window, so "the claim had its chance" means the
 * same thing on both paths.
 */
internal const val SPOTLIGHT_CLAIM_RETRY_FRAMES = 12

/**
 * Spotlight's top section: the phone's billboard, TV-sized.
 *
 * This is the same component the phone Home draws (MobileBillboard: the pager,
 * the cycle, the pills, the segments, the trailer modes) - not a second hero.
 * What differs is only what it is fed and the fact that it does NOT follow
 * focus: in Spotlight the focused card owns the preview, so a hero that also
 * showed the focused title would put the same meta and trailer in two places at
 * once. The billboard cycles its own feed instead.
 *
 * The prefs are the phone's, read straight from AppPreferences: one billboard,
 * one set of controls. A TV that picked catalogs, a hold or a trailer mode sees
 * them here, and the TV's own controls (Settings → Interface) write the same
 * values.
 */
@Composable
internal fun SpotlightHeroSection(
    viewModel: HomeViewModel,
    rails: List<Rail>,
    trailerMode: BillboardTrailerMode,
    /**
     * The hero-trailer delay, mute and autoplay, handed in from the Home.
     *
     * They are read there, where their SYNCED revision key lives (see
     * PrefsPayloadApplier.revision): a bare remember here would keep the value
     * the screen opened with, so a change made on another device would never
     * reach this hero.
     */
    trailerDelayMs: Long,
    trailerMuted: Boolean,
    trailerAutoplay: Boolean,
    /**
     * The item's height, resolved by the Home (F1, F3).
     *
     * The hero is the rails list's FIRST ITEM now, so its height is the item's:
     * 52% of the viewport, held by the Home for both the hero and the placeholder
     * that reserves its space before the first page lands.
     */
    heroHeight: Dp,
    /**
     * Whether this composition may take focus for the Play pill (F2).
     *
     * True only on the FIRST entry to the screen. As a list item the hero leaves
     * composition when scrolled out, and an ungated request would fire again on
     * the way back - stealing focus from the rail card the viewer is on. Home
     * owns that latch (it also has to stand down for a pending return restore),
     * so this composable only obeys it.
     */
    claimEntryFocus: Boolean,
    /**
     * Whether one of the hero's pills holds focus right now (F4).
     *
     * This is what "the claim landed" MEANS. `requestFocus()` answering true
     * only says a node accepted the request for that frame - the field report's
     * skipped pills were a claim that answered true (or false) and never
     * actually held focus, so the latch closed on a landing nobody saw.
     */
    pillHoldsFocus: Boolean = false,
    /** The billboard's focus report, forwarded so the Home can hold it (F4/F5). */
    onPillFocusChange: (Boolean) -> Unit = {},
    /**
     * Called when a claim that HAD landed loses the pill (F4).
     *
     * A page settling replaces the pills (they are per-page), so a loss here is
     * not "the viewer left" - it is a claim that needs to re-arm. The Home owns
     * the latch and re-arms it; the viewer's own press is still the one gate that
     * cannot be crossed (see `viewerMovedFocusSinceEntry`).
     */
    onEntryFocusLost: () -> Unit = {},
    /** UP from a focused pill opens the top bar (F6, Spotlight only). */
    onOpenTopBar: () -> Unit = {},
    /**
     * Whether the rails list is scrolling right now (spec 2026-11-14, F4).
     *
     * The sweep is re-transformed every frame and the hero is still on screen
     * for the first part of a scroll, so it holds while the list moves and
     * resumes when it settles; the page's 10s pace is unchanged. A lambda so
     * the read happens in the billboard: at the call site it would recompose
     * the whole Home on every scroll start and stop.
     */
    scrollHeld: () -> Boolean = { false },
    /**
     * The Play pill's own node, owned by the Home.
     *
     * The hero's item leaves and re-enters composition as it scrolls, so the
     * requester cannot live inside it: UP from the first rail has to be able to
     * ask for the pill afterwards (F4), and that press happens while the item may
     * not have been composed at all yet.
     */
    pillFocusRequester: FocusRequester,
    /**
     * The profile on screen, handed in from the Home.
     *
     * One job: keying the trending-day load on it (spec 2026-11-16, F3). The
     * default feed is ceiling-filtered per profile and the switch empties it, so
     * the TV has to re-ask like the phone does instead of waiting for the picker
     * round-trip to dispose Home.
     */
    activeProfileId: String?,
    /** Called once the pill request has been taken, so the latch closes. */
    onEntryFocusClaimed: () -> Unit = {},
    /**
     * Called when the claim has given up after its bounded retries (F3).
     *
     * The latch still has to close: a claim left armed is the gate-hole that made
     * every later re-deal yank focus off whatever card the viewer was on. A
     * given-up claim is a given-up claim, and the trace says which it was.
     */
    onEntryFocusClaimGivenUp: () -> Unit = {},
    /** True while a card is expanded: the hero shows its still and nothing else. */
    cardExpanded: Boolean,
    onPlay: (MetaPreview) -> Unit,
    onDetails: (MetaPreview) -> Unit,
    /** Reports whether THIS surface is playing a trailer (the screensaver hold). */
    onTrailerPlayingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // These three billboard prefs are PER-PROFILE and sync TV to TV (spec
    // 2026-11-15), but their reads are seeded on entry rather than keyed on the
    // sync revision: applying a synced value lands on the NEXT Home entry, so a
    // layout or billboard change never re-flows under a viewer mid-scroll. This
    // section is composed on every entry, which is where they are re-read - and
    // a profile switch disposes Home for the picker, so a new entry reads the
    // new profile's answer.
    val billboardEnabled = remember { AppPreferences.getHomeBillboardEnabled(context) }
    val billboardFeeds = remember { AppPreferences.getHomeBillboardFeeds(context) }
    val billboardCycleMs = remember { AppPreferences.getHomeBillboardCycleMs(context) }

    // The feeds the TV can offer: the same keys the phone's picker stores, built
    // from this Home's own rails (see billboardFeedKey).
    val feedSources = remember(rails) {
        buildMap {
            rails.forEach { rail ->
                billboardFeedKey(rail)?.let { key -> put(key, rail.items) }
            }
        }
    }

    val trendingDay by viewModel.billboardTrendingDay.collectAsStateWithLifecycle()
    // Keyed on the profile as well as the switch, exactly as the phone's hero is
    // (spec 2026-11-16, F3): the default feed is ceiling-filtered for the profile
    // that loads it, and a profile switch EMPTIES it (see HomeViewModel's switch
    // block) - so the profile now on screen has to ask again, or a kids profile
    // inherits the previous profile's adult trending titles as its hero. The TV
    // used to rely on the picker round-trip disposing Home for the reload.
    LaunchedEffect(billboardEnabled, activeProfileId) {
        if (billboardEnabled) viewModel.loadBillboardTrendingDay()
    }

    val feed = remember(feedSources, billboardFeeds, trendingDay) {
        billboardChosenFeed(
            keys = billboardFeeds,
            sources = feedSources,
            defaultFeed = trendingDay
        ) ?: trendingDay
    }

    val pages by viewModel.billboardPages.collectAsStateWithLifecycle()
    LaunchedEffect(billboardEnabled, feed) {
        if (billboardEnabled) {
            viewModel.resolveBillboard(feed)
        } else {
            viewModel.clearBillboard("billboard disabled")
        }
    }

    if (!billboardEnabled || pages.none { page -> page.settled }) {
        // Which gate returned (spec 2026-11-12, follow-up F1): without this a
        // hero that is not composed and a hero that composed blank read
        // identically in the paste.
        SpotlightDiagTrace.billboardRenderHero(
            composed = false,
            gate = if (!billboardEnabled) "!billboardEnabled" else "!settled",
            pageKey = null,
            art = null,
            settled = pages.count { page -> page.settled },
            total = pages.size
        )
        return
    }

    // The billboard's pills are the only focus targets above the rails, so the
    // Play pill takes focus when Spotlight opens - something has to hold it for
    // the D-pad to move at all, and this is what the viewer's eye is on. The
    // requester is the Home's (see [pillFocusRequester]).
    val firstPillFocus = pillFocusRequester
    // First entry only (F2). The hero scrolls away with the list and re-enters
    // composition when the viewer comes back up; asking for the pill again then
    // would yank focus off whichever card they were on (and, on a return from
    // Detail, race the restore that already owns the landing).
    //
    // BOUNDED RETRY, NOT ONE SHOT (spec 2026-11-13, F3). requestFocus() answers
    // false for a node that is not attached yet rather than throwing, so a single
    // attempt on a cold start was a coin flip: the field paste reads
    // `lastClaim=none` and the viewer reports the buttons as unfocusable. The pill
    // is a few frames behind the page that drew it, so the request is retried for
    // a bounded window - and then GIVEN UP, calling back so the latch closes
    // instead of staying armed to steal focus on a later re-deal.
    //
    // The effect lives in this section on purpose: it can only run while the hero
    // item is composed, and F1's hold is what keeps the list at item 0 until the
    // pages settle - so the retry always happens with the hero on screen, and a
    // scrolled-away hero never gets a bare requestFocus() into a disposed node.

    // The live value of the focus report, readable from inside the retry loop:
    // params are snapshots, and the whole point of F4 is to watch the state
    // CHANGE while the loop runs.
    val pillHoldsFocusNow = rememberUpdatedState(pillHoldsFocus)
    LaunchedEffect(pages.isNotEmpty(), claimEntryFocus) {
        // The latch itself, which is what keeps the entry focus live across
        // re-deals when a claim failed (F3).
        SpotlightDiagTrace.spotlightClaimGate(claimEntryFocus)
        if (!claimEntryFocus || pages.isEmpty()) return@LaunchedEffect
        // One frame first, so the pill has a node to ask at all.
        withFrameNanos { }
        var landed = pillHoldsFocusNow.value
        var frames = 0
        while (!landed && frames < SPOTLIGHT_CLAIM_RETRY_FRAMES) {
            frames++
            runCatching { firstPillFocus.requestFocus() }
            withFrameNanos { }
            // The OBSERVATION is the answer (F4). `requestFocus()` returning
            // true only means a node accepted the request; the field report's
            // skipped pills were exactly that gap. The retry also waits for the
            // focus to arrive rather than for the request to be accepted.
            landed = pillHoldsFocusNow.value
        }
        // Either outcome closes the latch through the Home; the trace records
        // which - and how much of the window it took - so a claim that gave up
        // is visible rather than silent.
        SpotlightDiagTrace.spotlightClaim(landed, frames)
        if (landed) onEntryFocusClaimed() else onEntryFocusClaimGivenUp()
    }

    // A claim that landed and then LOST the pill re-arms (F4). The pills are
    // per-page: a page that settles, or a re-deal that replaces the pool, swaps
    // the focused node out from under the viewer without any press - and the
    // old code closed the latch on the request, so that loss was permanent.
    // The Home re-arms only when the viewer has not taken the D-pad and no
    // restore owns the landing, so this can never fight either.
    var claimObserved by remember { mutableStateOf(false) }
    LaunchedEffect(pillHoldsFocus) {
        if (pillHoldsFocus) {
            claimObserved = true
        } else if (claimObserved) {
            claimObserved = false
            onEntryFocusLost()
        }
    }

    MobileBillboard(
        heroHeight = heroHeight,
        scrollHeld = scrollHeld,
        firstPillFocusRequester = firstPillFocus,
        pages = pages,
        cycleMs = billboardCycleMs,
        // The single trailer surface rule: while a card is expanded the card owns
        // the pooled player and the hero shows its still; when it collapses the
        // viewer's mode comes back and the billboard's cycle restarts.
        trailerMode = if (cardExpanded) {
            SpotlightTrailerRule.heroModeWhileExpanded()
        } else {
            trailerMode
        },
        trailerDelayMs = trailerDelayMs,
        trailerMuted = trailerMuted,
        trailerAutoplay = trailerAutoplay,
        // The single-surface rule, enforced rather than assumed: while a card is
        // expanded the billboard STOPS its own trailer through its own stop path
        // (a deliberate cut, not a death) and renders its still - the mode flip
        // below only keeps it from starting a new one.
        trailerSuppressed = cardExpanded,
        onTrailerPlayingChange = onTrailerPlayingChange,
        // Play carries the autoplay intent on the route, exactly as the phone
        // does (spec 2026-11-17, F1): Detail presses its own Play on arrival.
        onPlay = { page -> onPlay(page.preview) },
        onDetails = { page -> onDetails(page.preview) },
        // F4's signal, and F6's UP chain: both belong to the pill group, so the
        // billboard reports the first and asks the second.
        onPillFocusChange = onPillFocusChange,
        onPillUpPressed = onOpenTopBar
    )
}

/**
 * Draws the expanded card, once its dwell has passed.
 *
 * The dwell is the expand delay from Settings (measured from the moment the card
 * gained focus), NOT the hero's 150 ms meta debounce: the debounce protects the
 * meta resolve from a held D-pad's repeat rate, this decides when the PREVIEW
 * appears - two timers with two jobs, and a fast scroll crosses cards without
 * ever letting this one fire.
 *
 * The overlay is drawn over the rail at the card's own bounds (see
 * [spotlightPanelOffset]), so nothing in the rail moves: that is the whole shape
 * of the layout.
 */
@Composable
internal fun SpotlightPreviewHost(
    tracker: SpotlightCardFocus,
    expandDelayMs: Long,
    meta: Meta?,
    trailerKey: String?,
    imdbId: String?,
    tmdbId: Int?,
    trailerMode: BillboardTrailerMode,
    trailerMuted: Boolean,
    heroTrailerAutoplay: Boolean,
    /** The viewer's hero-trailer delay; the card waits the same one (F7). */
    trailerDelayMs: Long,
    /** A long-press menu (or any modal) is open: the panel must not open over it. */
    menuOpen: Boolean,
    /** The hero meta has not landed yet (and has not failed): the panel says so. */
    metaLoading: Boolean = false,
    /** The hero meta resolve failed: the panel says so. */
    metaFailed: Boolean = false,
    /**
     * The title whose resolve has settled, as `id:type` (F1, 2026-11-13).
     *
     * The panel draws the picture for the card it is SHOWING, and the resolved
     * backdrop belongs to whichever card the hero last settled on. Comparing the
     * two is what makes "no picture for this title" distinguishable from "a
     * picture for a title that is no longer on screen" - the old code could not
     * tell them apart and drew the rail's small still into the gap.
     */
    settledTitleKey: String? = null,
    /**
     * The settled title's resolved BACKDROP only - never a poster (F1).
     *
     * Null while the resolve for the shown card has not landed, when it failed,
     * and when the title has no backdrop at all: all three draw the panel's text
     * tile rather than an upscaled small image.
     */
    settledBackdropUrl: String? = null,
    /**
     * The SETTLED title's "8 Seasons • 164 Episodes", or null (spec 2026-11-15).
     *
     * The same settled-title rule the backdrop follows, and for the same reason:
     * the counts belong to whichever title the HERO last resolved, so handing
     * them to a panel showing a different card would print one title's seasons
     * under another title's name during a fast focus move. The host therefore
     * passes this only when the panel's own key IS the settled key - the gate is
     * here, once, not inside the panel.
     *
     * Computed from the hero detail the resolve already produced
     * (`displaySeasonEpisodeCount()`): no new fetch, no new cache, no id lookup.
     * A movie's detail carries no counts, so its value is null and its panel is
     * untouched.
     */
    settledSeasonEpisodeCount: String? = null,
    /**
     * The SETTLED title's TMDB English clearlogo, or null (spec 2026-11-15).
     *
     * The same settled-title gate as the backdrop and the counts, and it needs no
     * extra care of its own: the logo publishes under the hero's one
     * `_heroSettledKey` beside the backdrop and the detail, so there is no state
     * where this can be the previous title's mark. TMDB's English wordmark only -
     * the hero's rule, not Detail's fanart fallback.
     */
    settledLogoUrl: String? = null,
    onTrailerPlayingChange: (Boolean) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf<ExpandedPanel?>(null) }
    val snapshot = tracker.snapshot
    // The card the viewer is HELD on, as a NODE (spec 2026-11-12, F5).
    //
    // The dwell used to key on the card's `type:id` string, which is not a card
    // identity: the same show sits on Continue Watching, on Upcoming and on a
    // catalog rail, and on two catalog rails as well. Moving between two of those
    // cards read as "no move", so the panel followed the new bounds and never
    // collapsed - the field report's stuck panel. A node cannot collide.
    val heldNodeNow = if (tracker.cardFocused) tracker.focusedNode else null

    // ── The card the viewer is HELD on, debounced (spec 2026-11-12) ──────
    //
    // The dwell keys on the card's identity, and a card losing focus is only
    // believed after a grace. A one-frame recomposition blur that takes focus
    // back lands on the same node and cancels nothing: that is the survival
    // 4c29def2a earned, and it stays.
    var heldNode by remember { mutableStateOf<Any?>(null) }
    var heldLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(heldNodeNow) {
        if (heldNodeNow != null) {
            heldNode = heldNodeNow
            heldLabel = snapshot?.cardIdentity()
            return@LaunchedEffect
        }
        // Nothing is focused: give the card a moment to take it back.
        delay(SPOTLIGHT_BLUR_GRACE_MS)
        heldNode = null
        heldLabel = null
    }

    // What the in-flight delay was armed on, so a cancellation can NAME the
    // input that did it (F1). Plain holder: the effect is the only writer.
    val dwellRun = remember { DwellRun() }

    // ── The dwell's CLOCK, kept across effect restarts (F1, 2026-11-13) ───
    //
    // The field report (0.9.285/296, "you have to wait multiple minutes before
    // they all work") is a restart that the trace cannot even name: the effect is
    // also keyed on `heldNode` - the card's own FocusRequester - and
    // [DwellInputs] only names card/identity, delay and menu changes. A card
    // RE-CREATED under the viewer therefore cancelled the two seconds silently:
    // a rail re-deal, a bring-into-view rebuild or a re-landed focus hands back a
    // new node for the same card, and every one of those began the delay again
    // from zero. A cold box rebuilds its rails for a while, so a hold taken at 3 s
    // could be restarted every second until the screen went quiet - minutes -
    // and after that, every card "works".
    //
    // So the clock belongs to the CARD, not to the effect run: a restart for the
    // same card resumes the time already held, and only a different card starts a
    // new clock. What the spec forbids is untouched - the delay is still the
    // viewer's setting, the identity key still re-arms a real card change, and a
    // blur that lands back on the same card still does not end the panel.
    //
    // Since the 2026-11-16 field report (spec A1) the clock is PER CARD: it was a
    // single slot wiped by any card change, so scrolling across a rail to read it
    // threw away the second a card had already earned and the viewer had to come
    // back and hold again from zero. Time now accumulates per card identity and
    // only a panel opening (its wait is spent) or a deliberate close forgets it.
    val dwellClock = remember { SpotlightDwellClock() }

    // The held card's snapshot IDENTITY is a key (spec 2026-11-13, F2).
    //
    // The dwell bails out while the card's details are still resolving, and
    // nothing restarted it when they arrived - so a card whose meta was already
    // cached expanded and one that needed a resolve never did, however long the
    // viewer held it (field 0.9.267: 3 of 7 expanded, the rest `hasSnapshot`
    // cancellations of a dwell that had not armed). Keyed on the IDENTITY, not on
    // the snapshot object: `updateBounds` copies the snapshot on every scroll,
    // and keying on the object is what restarted the delay forever in the older
    // bug this keys the same way to avoid.
    val heldSnapshotIdentity = snapshot?.cardIdentity()
    LaunchedEffect(heldNode, expandDelayMs, menuOpen, heldSnapshotIdentity) {
        // The details landed for the card being held: the trace's label is the
        // card's own identity, so it can be named now (`card=none` in the field
        // paste was this same unresolved snapshot).
        if (heldNode != null && heldLabel == null) {
            heldLabel = snapshot?.cardIdentity()
        }
        val now = DwellInputs(heldLabel, expandDelayMs, menuOpen)
        // A delay that was armed and never completed was cancelled by the input
        // that changed under it. Reported BEFORE this run re-arms.
        if (dwellRun.armed) {
            dwellRun.inputs.changedTo(now)?.let { (key, from, to) ->
                SpotlightDiagTrace.spotlightDwellCancelled(key, from, to)
            }
        }
        dwellRun.armed = false
        dwellRun.inputs = now
        SpotlightDiagTrace.spotlightDwell(
            expandDelayMs = expandDelayMs,
            menuOpen = menuOpen,
            token = tracker.focusToken,
            card = heldLabel
        )
        // ── The panel follows the CARD, not the snapshot (F3) ───────────
        //
        // Three things end an open panel: the viewer opens a menu, turns
        // expansion off, or takes their card away - a DIFFERENT card taking
        // focus (`cardChanged`), or focus leaving the cards altogether
        // (`focusLeft`, believed after the grace above). What does NOT end it is
        // a blur that lands back on the same card, which is the case
        // 4c29def2a fixed and this keeps.
        val live = expanded
        val collapseGuard = when {
            menuOpen -> "menuOpen"
            !HomeExpandDelay.expands(expandDelayMs) -> "!expands"
            live != null && live.node !== heldNode ->
                if (heldNode == null) "focusLeft" else "cardChanged"
            else -> null
        }
        if (collapseGuard != null) {
            val closingCard = live?.snapshot?.cardIdentity()
            if (live != null) {
                SpotlightDiagTrace.spotlightCollapsed(collapseGuard)
                expanded = null
                onExpandedChange(false)
            }
            if (collapseGuard == "cardChanged") {
                // The viewer moved to ANOTHER card (spec A1/A3): this one's time
                // is BANKED, not thrown away, so coming back resumes the wait.
                // The panel closes - a card that lost it has to earn it again -
                // but every card keeps what it already held.
                dwellClock.pause(SystemClock.uptimeMillis())
            } else {
                // A closed panel is not a held card any more, and a menu, an
                // expansion switched off or focus leaving the cards ends the
                // whole dwell: the next hold waits the full delay again.
                dwellClock.spend(closingCard)
                return@LaunchedEffect
            }
        }
        // Focus is nowhere: nothing to arm, and the guard above has already
        // closed what was up. The time already held is banked first, so the same
        // card taking focus back resumes it (A1).
        if (heldNode == null) {
            dwellClock.pause(SystemClock.uptimeMillis())
            return@LaunchedEffect
        }
        // The card must HOLD focus for the whole delay; a move cancels this run
        // and the next card starts its own clock. A null snapshot still means
        // "nothing to expand yet" - but the identity above is a key now, so the
        // dwell re-arms the moment the details land (F2) instead of dropping the
        // wait on the floor.
        //
        // A snapshot that blinks while the SAME card still holds focus is a PAUSE,
        // not a cancel (spec A2): the time already held is banked and the wait
        // picks it up when the details land, instead of the card losing the
        // second it had earned to a resolve flicker.
        val armedOn = tracker.snapshot ?: run {
            dwellClock.pause(SystemClock.uptimeMillis())
            return@LaunchedEffect
        }
        val armedNode = tracker.focusedNode
        dwellRun.inputs = now
        // The clock is the CARD's, and it survives a restart of this effect (F1)
        // and a move across the rail (A1). `start` banks whatever run it replaces
        // first, so a re-created node for this card adds up rather than restarts.
        val cardNow = armedOn.cardIdentity()
        val alreadyHeldMs = dwellClock.start(cardNow, SystemClock.uptimeMillis())
        dwellRun.armed = true
        delay((expandDelayMs - alreadyHeldMs).coerceAtLeast(0L))
        dwellRun.armed = false
        // The panel is up: this card's wait is spent, so a later re-hold of it
        // starts the viewer's delay again (the other cards keep theirs).
        dwellClock.spend(cardNow)
        // The snapshot captured when the dwell ARMED, not whatever the tracker
        // holds now (F3): the panel a held card earned must not be lost to a
        // live snapshot that flickered during the last frame of the wait.
        expanded = ExpandedPanel(armedOn, armedNode)
        SpotlightDiagTrace.spotlightExpanded(armedOn.title)
        onExpandedChange(true)
    }
    // A scroll under a still-focused card moves the panel with it. Applied after
    // composition, so the write schedules a frame instead of mutating inside one.
    SideEffect {
        val current = snapshot
        val live = expanded
        if (live != null && current != null && live.node === tracker.focusedNode &&
            live.snapshot.bounds != current.bounds
        ) {
            expanded = live.copy(snapshot = current)
        }
    }

    // Audit a2: BACK with a panel up collapses it and hands focus back to the
    // card that owns it, instead of leaving Home. The top bar and the screensaver
    // have their own handlers, but neither can be up with a panel: a menu
    // collapses the panel, and the screensaver only arms behind an idle viewer.
    // So this is the innermost of the three.
    BackHandler(enabled = expanded != null) {
        // The viewer closed it: this card's wait is spent, it does not re-open
        // from the clock the dismissed panel already spent. The other cards keep
        // theirs (A1).
        val closedCard = expanded?.snapshot?.cardIdentity()
        expanded = null
        onExpandedChange(false)
        dwellClock.spend(closedCard)
        runCatching { tracker.cardRequester?.requestFocus() }
    }
    val panel = expanded?.snapshot ?: return

    Box(modifier = modifier.fillMaxSize().zIndex(SPOTLIGHT_PANEL_Z)) {
        // The overlay's own size is the viewport the panel clamps to: it is the
        // same coordinate space the card's bounds were measured in, so there is
        // no second screen-size estimate to disagree with the rail (F4).
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it })
        SpotlightPreviewPanel(
            snapshot = panel,
            meta = meta,
            // The picture for THIS card's title, or nothing (F1): the settle key
            // must name the card the panel is showing before its backdrop is
            // allowed to draw.
            backdropUrl = panel.heroTitleKey()
                ?.takeIf { it == settledTitleKey }
                ?.let { settledBackdropUrl },
            // ...and whether that title's resolve has finished, so the tile can
            // be a wait (loading) or the answer (none) rather than both.
            backdropSettled = panel.heroTitleKey() != null &&
                panel.heroTitleKey() == settledTitleKey,
            // The counts follow the backdrop's gate exactly (spec 2026-11-15):
            // the settled title's counts, or none - never the previous card's
            // seasons under this card's title.
            seasonEpisodeCount = panel.heroTitleKey()
                ?.takeIf { it == settledTitleKey }
                ?.let { settledSeasonEpisodeCount },
            // ...and the settled title's wordmark under the same gate (F1): the
            // panel draws it in the title's box, or draws the text title.
            logoUrl = panel.heroTitleKey()
                ?.takeIf { it == settledTitleKey }
                ?.let { settledLogoUrl },
            trailerKey = trailerKey,
            imdbId = imdbId,
            tmdbId = tmdbId,
            trailerMode = trailerMode,
            trailerMuted = trailerMuted,
            heroTrailerAutoplay = heroTrailerAutoplay,
            trailerDelayMs = SpotlightTrailerRule.expandedCardDelayMs(trailerDelayMs),
            viewport = viewport,
            metaLoading = metaLoading,
            metaFailed = metaFailed,
            onTrailerPlayingChange = onTrailerPlayingChange
        )
    }
}

/**
 * The panel that is up: the card's snapshot and the NODE that earned it.
 *
 * The node is what makes "another card" mean another card (F3/F5); the snapshot
 * is what the panel draws.
 */
private data class ExpandedPanel(
    val snapshot: SpotlightCardSnapshot,
    val node: Any?
)

/** Above the rails and their menus, below the screensaver. */
private const val SPOTLIGHT_PANEL_Z = 4f

/**
 * How long a blurred card may take focus back before the dwell believes it left.
 *
 * A recomposition can blur and re-focus the held card inside a frame or two;
 * treating that as a departure is what cancelled the two-second dwell on the
 * field TV. The field build also lost its card to NOTHING for about half a
 * second after the panel opened (2026-11-12, 4c29def2a), so this is 600 ms: long
 * enough to cover that blur, short enough that a real move - which is immediate,
 * the next card's node lands at once - or a real exit still closes the panel
 * promptly.
 */
private const val SPOTLIGHT_BLUR_GRACE_MS = 600L

/**
 * The four inputs the dwell effect keys on, and their last armed values.
 *
 * A cancellation compares the run that was in flight against the one replacing
 * it and names the FIRST input that differs, in the spec's own vocabulary -
 * `hasSnapshot (series:tt…→none)` for a blur, `expandDelayMs (2000ms→0ms)` for
 * a settings change, `menuOpen (no→yes)` for the menu.
 */
private data class DwellInputs(
    val card: String?,
    val delayMs: Long,
    val menuOpen: Boolean
) {
    /** The first input that changed, as (key, from, to), or null when none did. */
    fun changedTo(next: DwellInputs): Triple<String, String, String>? = when {
        card != next.card ->
            Triple("hasSnapshot", card ?: "none", next.card ?: "none")
        delayMs != next.delayMs ->
            Triple("expandDelayMs", "${delayMs}ms", "${next.delayMs}ms")
        menuOpen != next.menuOpen ->
            Triple("menuOpen", if (menuOpen) "yes" else "no", if (next.menuOpen) "yes" else "no")
        else -> null
    }
}

/** Whether the dwell's delay is currently armed and what it was armed on. */
private class DwellRun {
    var armed = false
    var inputs = DwellInputs(null, 0L, false)
}

/**
 * The hero resolve's key for this card's title: `id:type`.
 *
 * Not the same spelling as [cardIdentity] (`type:id`): this one has to match
 * `HomeViewModel.heroSettledKey`, which the resolve publishes as
 * `"<id>:<type>"`. Two spellings of one key is exactly how a title ends up
 * compared against the wrong card.
 */
internal fun SpotlightCardSnapshot.heroTitleKey(): String? {
    val cardId = id?.takeIf { it.isNotBlank() } ?: return null
    return "$cardId:${type.orEmpty()}"
}

/** The card's identity across snapshot copies: type:id, or the title alone. */
private fun SpotlightCardSnapshot.cardIdentity(): String =
    when {
        !id.isNullOrBlank() -> "${type.orEmpty()}:$id"
        !title.isNullOrBlank() -> title.orEmpty()
        else -> "?"
    }

/**
 * The hero's key in the rails LazyColumn (F1).
 *
 * Stable, so the list keeps the item's own state while the hero scrolls out and
 * back - and so the landing math (F5) can find the item to read its bottom edge.
 */
internal const val SPOTLIGHT_HERO_ITEM_KEY = "spotlight_hero"

/**
 * The spotlight hero's share of the viewport.
 *
 * 52%: big enough to be the layout's banner, small enough that the first rail
 * shows under it without scrolling (the spec's ceiling is 60%). A fraction, not
 * a dp height, so it holds at every TV density.
 */
internal const val SPOTLIGHT_HERO_FRACTION = 0.52f

/**
 * The hero item's height on this screen: 52% of the viewport, in dp.
 *
 * One function so the item, the placeholder that reserves its space and the
 * billboard itself cannot disagree about how tall the hero is.
 */
internal fun spotlightHeroHeight(screenHeightDp: Int): Dp =
    (screenHeightDp * SPOTLIGHT_HERO_FRACTION).dp

