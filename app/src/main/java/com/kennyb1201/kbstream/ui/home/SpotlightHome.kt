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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import com.kennyb1201.kbstream.ui.mobile.MobileBillboard
import kotlinx.coroutines.delay

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
     * The Play pill's own node, owned by the Home.
     *
     * The hero's item leaves and re-enters composition as it scrolls, so the
     * requester cannot live inside it: UP from the first rail has to be able to
     * ask for the pill afterwards (F4), and that press happens while the item may
     * not have been composed at all yet.
     */
    pillFocusRequester: FocusRequester,
    /** Called once the pill request has been taken, so the latch closes. */
    onEntryFocusClaimed: () -> Unit = {},
    /** True while a card is expanded: the hero shows its still and nothing else. */
    cardExpanded: Boolean,
    onPlay: (MetaPreview) -> Unit,
    onDetails: (MetaPreview) -> Unit,
    /** Reports whether THIS surface is playing a trailer (the screensaver hold). */
    onTrailerPlayingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // These three billboard prefs are DEVICE-LOCAL (EXCLUDED_PREF_KEYS), so they
    // are remembered with no revision key - the other TV's answer must not move
    // this one's. Coming back from Settings re-enters this composable, which is
    // how a change made here is picked up.
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
    LaunchedEffect(billboardEnabled) {
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
        if (billboardEnabled) viewModel.resolveBillboard(feed) else viewModel.clearBillboard()
    }

    if (!billboardEnabled || pages.none { page -> page.settled }) return

    // The billboard's pills are the only focus targets above the rails, so the
    // Play pill takes focus when Spotlight opens - something has to hold it for
    // the D-pad to move at all, and this is what the viewer's eye is on. The
    // requester is the Home's (see [pillFocusRequester]).
    val firstPillFocus = pillFocusRequester
    // First entry only (F2). The hero scrolls away with the list and re-enters
    // composition when the viewer comes back up; asking for the pill again then
    // would yank focus off whichever card they were on (and, on a return from
    // Detail, race the restore that already owns the landing).
    LaunchedEffect(pages.isNotEmpty(), claimEntryFocus) {
        if (!claimEntryFocus || pages.isEmpty()) return@LaunchedEffect
        // One frame, so the pill has a node to take focus.
        withFrameNanos { }
        val taken = runCatching { firstPillFocus.requestFocus() }.getOrDefault(false)
        if (taken) onEntryFocusClaimed()
    }

    MobileBillboard(
        heroHeight = heroHeight,
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
        // Play is the billboard's own press: it asks the Detail page to press its
        // own Play (see BillboardPlaySelection), exactly as the phone does.
        onPlay = { page ->
            BillboardPlaySelection.request(page.preview.id)
            onPlay(page.preview)
        },
        onDetails = { page -> onDetails(page.preview) }
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
    /** The hero meta resolve failed: the panel says so and offers its retry. */
    metaFailed: Boolean = false,
    onRetryMeta: () -> Unit = {},
    onPlay: (MetaPreview) -> Unit,
    onDetails: (MetaPreview) -> Unit,
    onTrailerPlayingChange: (Boolean) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf<SpotlightCardSnapshot?>(null) }
    val snapshot = tracker.snapshot
    val hasSnapshot = snapshot != null

    // Keyed on the CARD, not on the snapshot object (F6b): updateBounds copies
    // the snapshot whenever the card's bounds move - a scroll, the hero landing,
    // a feed re-resolve - and keyed on the object every copy restarted the dwell,
    // so a card under a settling list never expanded at all. The focus token
    // changes only when a card takes focus; hasSnapshot still closes the panel
    // when focus leaves.
    LaunchedEffect(tracker.focusToken, hasSnapshot, expandDelayMs, menuOpen) {
        // The menu is the viewer's explicit request, so it wins: no panel opens
        // over it, and one already up collapses when it appears.
        if (!hasSnapshot || menuOpen || !HomeExpandDelay.expands(expandDelayMs)) {
            expanded = null
            onExpandedChange(false)
            return@LaunchedEffect
        }
        // The card must HOLD focus for the whole delay; a move cancels this run
        // and the next card starts its own clock.
        delay(expandDelayMs)
        expanded = tracker.snapshot
        onExpandedChange(expanded != null)
    }
    // A scroll under a still-focused card moves the panel with it. Applied after
    // composition, so the write schedules a frame instead of mutating inside one.
    SideEffect {
        val current = snapshot
        if (expanded != null && current != null && expanded?.bounds != current.bounds) {
            expanded = current
        }
    }

    // The panel is the card's own focus neighbourhood while it is up (see
    // SpotlightCardFocus): DOWN from the card enters its pills, and their focus
    // keeps it open.
    SideEffect { tracker.panelExpanded = expanded != null }
    // Audit a2: BACK with a panel up collapses it and hands focus back to the
    // card that owns it, instead of leaving Home. The top bar and the screensaver
    // have their own handlers, but neither can be up with a panel: a menu
    // collapses the panel, and the screensaver only arms behind an idle viewer.
    // So this is the innermost of the three.
    BackHandler(enabled = expanded != null) {
        expanded = null
        onExpandedChange(false)
        runCatching { tracker.cardRequester?.requestFocus() }
    }
    val panel = expanded ?: return
    // Audit A4: the pills act on the CARD's own identity, never on the
    // enrichment. PLAY and DETAILS used to be gated on the resolved meta, so a
    // slow or failed resolve left both as silent dead presses; the panel now has
    // what the card already had.
    val preview = (panel.id ?: meta?.id)?.let { id ->
        MetaPreview(
            id = id,
            type = panel.type ?: meta?.type ?: "movie",
            name = meta?.name ?: panel.title.orEmpty(),
            poster = meta?.poster ?: panel.stillUrl,
            background = meta?.background,
            imdbRating = meta?.imdbRating
        )
    }

    Box(modifier = modifier.fillMaxSize().zIndex(SPOTLIGHT_PANEL_Z)) {
        // The overlay's own size is the viewport the panel clamps to: it is the
        // same coordinate space the card's bounds were measured in, so there is
        // no second screen-size estimate to disagree with the rail (F4).
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it })
        SpotlightPreviewPanel(
            snapshot = panel,
            meta = meta,
            trailerKey = trailerKey,
            imdbId = imdbId,
            tmdbId = tmdbId,
            trailerMode = trailerMode,
            trailerMuted = trailerMuted,
            heroTrailerAutoplay = heroTrailerAutoplay,
            trailerDelayMs = SpotlightTrailerRule.expandedCardDelayMs(trailerDelayMs),
            viewport = viewport,
            tracker = tracker,
            metaLoading = metaLoading,
            metaFailed = metaFailed,
            onRetryMeta = onRetryMeta,
            onPlay = {
                // The card's Play is the billboard's: ask Detail to press its own
                // Play for this title, the one path that resolves a stream.
                val target = preview
                if (target != null) {
                    BillboardPlaySelection.request(target.id)
                    onPlay(target)
                }
            },
            onDetails = {
                val target = preview
                if (target != null) onDetails(target)
            },
            onTrailerPlayingChange = onTrailerPlayingChange
        )
    }
}

/** Above the rails and their menus, below the screensaver. */
private const val SPOTLIGHT_PANEL_Z = 4f

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

