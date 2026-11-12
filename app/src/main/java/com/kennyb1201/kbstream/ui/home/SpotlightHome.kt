package com.kennyb1201.kbstream.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    /** True while a card is expanded: the hero shows its still and nothing else. */
    cardExpanded: Boolean,
    onPlay: (MetaPreview) -> Unit,
    onDetails: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Device-local billboard prefs, exactly as the phone reads them. Keyed on
    // nothing: the TV's Settings is a different destination, so coming back to
    // Home re-enters this composable and these reads run again.
    val billboardEnabled = remember { AppPreferences.getHomeBillboardEnabled(context) }
    val billboardFeeds = remember { AppPreferences.getHomeBillboardFeeds(context) }
    val billboardCycleMs = remember { AppPreferences.getHomeBillboardCycleMs(context) }
    val trailerDelayMs = remember { AppPreferences.getHeroTrailerDelayMs(context) }
    val trailerMuted = remember { AppPreferences.getHeroTrailerMuted(context) }
    val trailerAutoplay = remember { AppPreferences.getHeroTrailerAutoplay(context) }

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

    MobileBillboard(
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
    trailerMode: BillboardTrailerMode,
    trailerMuted: Boolean,
    heroTrailerAutoplay: Boolean,
    onPlay: (MetaPreview) -> Unit,
    onDetails: (MetaPreview) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf<SpotlightCardSnapshot?>(null) }
    val snapshot = tracker.snapshot

    LaunchedEffect(snapshot, expandDelayMs) {
        if (snapshot == null || !HomeExpandDelay.expands(expandDelayMs)) {
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

    val panel = expanded ?: return
    val preview = meta?.let { detail ->
        MetaPreview(
            id = detail.id,
            type = detail.type,
            name = detail.name,
            poster = detail.poster,
            background = detail.background,
            imdbRating = detail.imdbRating
        )
    }

    Box(modifier = modifier.fillMaxSize().zIndex(SPOTLIGHT_PANEL_Z)) {
        SpotlightPreviewPanel(
            snapshot = panel,
            meta = meta,
            trailerKey = trailerKey,
            trailerMode = trailerMode,
            trailerMuted = trailerMuted,
            heroTrailerAutoplay = heroTrailerAutoplay,
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
            modifier = Modifier.offset { spotlightPanelOffset(panel.bounds) }
        )
    }
}

/** Above the rails and their menus, below the screensaver. */
private const val SPOTLIGHT_PANEL_Z = 4f

