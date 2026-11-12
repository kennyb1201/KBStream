package com.kennyb1201.kbstream.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import com.kennyb1201.kbstream.ui.detail.isEpisodeUnavailable
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * One focused rail card, as the Spotlight preview needs it.
 *
 * [bounds] are the card's own bounds in the window, captured while it has focus -
 * the preview is drawn AT the card and grows out of it, so the position is the
 * whole point. The rest is what a [com.kennyb1201.kbstream.ui.components.PosterCard]
 * already knows about itself; the meta line comes from the hero META resolve
 * (see [SpotlightPreviewPanel]), which keeps running here for exactly this.
 *
 * [airDate] is the card's own release/air date when the rail carries one (an
 * Upcoming card's `releaseInfo` is a full date): an unaired card offers no PLAY,
 * the rule Detail's episode cards keep.
 */
internal data class SpotlightCardSnapshot(
    val title: String?,
    val stillUrl: String?,
    val isWatched: Boolean,
    val isPartiallyWatched: Boolean,
    val airDate: String? = null,
    val bounds: Rect
) {
    /** True for a card whose date is still in the future (see [isEpisodeUnavailable]). */
    val isUnaired: Boolean get() = isEpisodeUnavailable(airDate)
}

/**
 * The Spotlight focus tracker: which rail card has focus, and its panel.
 *
 * A rail card reports itself here through [com.kennyb1201.kbstream.ui.components.PosterCard],
 * so no rail has to know about Spotlight: the cards already draw themselves
 * through one component, and this is the one place they tell it that they are
 * the focused one. Null means no card is focused (the hero's pills, the top
 * bar, a long-press menu) - which is also what collapses the preview.
 *
 * It also owns the panel's focus neighbourhood: DOWN from the card enters the
 * pill row ([focusPills]), UP from the row returns to the card
 * ([cardRequester]), and a blur caused by that handoff does not collapse the
 * panel - the card has not lost the viewer, it has handed them to its own
 * actions. Without this the pills were reachable only by accident (from a card
 * with nothing below it) and the panel then collapsed out from under the pill
 * the viewer was pressing.
 */
internal class SpotlightCardFocus {

    var snapshot by mutableStateOf<SpotlightCardSnapshot?>(null)
        private set

    /** True while the panel is up; the card's DOWN is a handoff only then. */
    var panelExpanded by mutableStateOf(false)

    /** Focus inside the pill row: the panel's own neighbourhood, not a blur. */
    var pillsFocused by mutableStateOf(false)
        private set

    /**
     * A DOWN handoff is in flight: the card's blur is the pills taking focus,
     * not the viewer leaving, so the panel holds until the row reports in.
     */
    private var handingOff = false

    /** The focused card's own node, so the pill row can send UP back to it. */
    var cardRequester: FocusRequester? = null

    /** Set by the panel while it is composed: what DOWN from the card calls. */
    var focusPills: (() -> Unit)? = null

    /** Bounds-only update, so a scroll under a still-focused card repositions it. */
    fun updateBounds(bounds: Rect) {
        val current = snapshot ?: return
        if (current.bounds == bounds) return
        snapshot = current.copy(bounds = bounds)
    }

    fun onCardFocus(focused: Boolean, card: () -> SpotlightCardSnapshot) {
        if (focused) {
            handingOff = false
            snapshot = card()
        } else {
            // A blur while the panel owns the viewer is the handoff, not leaving.
            if (handingOff || pillsFocused) return
            snapshot = null
        }
    }

    /**
     * DOWN pressed on the focused card: enter the panel when it is up.
     *
     * Returns true when the press was consumed, so the card's own key handler
     * does not also move focus into the rail below.
     */
    fun onDownFromCard(): Boolean {
        val focus = focusPills ?: return false
        if (!panelExpanded || snapshot == null) return false
        handingOff = true
        focus()
        return true
    }

    /** The pill row took or lost focus. */
    fun onPillsFocusChanged(focused: Boolean) {
        pillsFocused = focused
        if (focused) {
            handingOff = false
            return
        }
        // Left the row and nothing claimed focus: the viewer is done with the
        // panel, so it collapses (the card is not focused either by then).
        if (!handingOff) snapshot = null
    }

    /** UP from the pill row: back to the card that owns the panel. */
    fun onUpFromPills(): Boolean {
        val requester = cardRequester ?: return false
        runCatching { requester.requestFocus() }
        return true
    }
}

/**
 * Provided by the Spotlight Home only, so the Modern layout pays nothing for
 * this feature: a null tracker is what [com.kennyb1201.kbstream.ui.components.PosterCard]
 * checks before it does any reporting at all.
 */
internal val LocalSpotlightCardFocus = staticCompositionLocalOf<SpotlightCardFocus?> { null }

/**
 * The expanded card: drawn OVER the rail at the focused card's own bounds.
 *
 * The expansion overlays rather than reflows - the rail's other cards never
 * move, which is the point of the layout - so this is a panel anchored to the
 * card, not a change to the card's slot. It renders the still, the title, the
 * meta line, the overview (two lines) and the pills the billboard hero offers,
 * plus the watched mark, and it is CLAMPED to the viewport (see
 * [spotlightPanelBox]): the whole panel, pills row included, is on screen, and
 * the pills wrap inside the panel's background rather than running out of it.
 *
 * The trailer is the card's own, mounted by [SpotlightPanelTrailer] with the
 * hero's ladder (F5), and it obeys the single-surface mode: AUTO plays it here,
 * MANUAL offers the button, OFF shows neither.
 */
@Composable
internal fun SpotlightPreviewPanel(
    snapshot: SpotlightCardSnapshot,
    meta: Meta?,
    trailerKey: String?,
    imdbId: String?,
    tmdbId: Int?,
    trailerMode: BillboardTrailerMode,
    trailerMuted: Boolean,
    heroTrailerAutoplay: Boolean,
    trailerDelayMs: Long,
    /** The overlay's own size, in the same pixels `boundsInRoot` reports. */
    viewport: IntSize,
    tracker: SpotlightCardFocus?,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onTrailerPlayingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val marginPx = with(density) { SPOTLIGHT_PANEL_MARGIN.toPx() }
    // Growth stays 1.9x (the expansion's own shape) but the panel may not
    // exceed the viewport: a first-rail portrait card grew past the bottom edge
    // and put the pills row - the part a viewer presses - off screen.
    val box = spotlightPanelBox(
        cardLeftPx = snapshot.bounds.left,
        cardTopPx = snapshot.bounds.top,
        cardRightPx = snapshot.bounds.right,
        cardBottomPx = snapshot.bounds.bottom,
        growth = PANEL_GROWTH,
        viewportWidthPx = viewport.width,
        viewportHeightPx = viewport.height,
        marginPx = marginPx
    )
    val width = animateDpAsState(
        targetValue = with(density) { box.width.toDp() },
        animationSpec = tween(EXPAND_MS),
        label = "spotlightWidth"
    )
    val height = animateDpAsState(
        targetValue = with(density) { box.height.toDp() },
        animationSpec = tween(EXPAND_MS),
        label = "spotlightHeight"
    )

    val pillsFocus = remember { FocusRequester() }
    // A press of the TRAILER pill, through a counter: the trailer keeps its own
    // request state, and a fresh press must fire again even after a resolve that
    // failed (the effect keys would otherwise be unchanged).
    var trailerPresses by remember(trailerKey) { mutableIntStateOf(0) }
    // The panel registers itself into the card's neighbourhood: DOWN from the
    // focused card calls this, and the row's own focus reports keep the panel up.
    SideEffect {
        tracker?.focusPills = {
            runCatching { pillsFocus.requestFocus() }
        }
    }
    DisposableEffect(tracker) {
        onDispose { tracker?.focusPills = null }
    }

    Column(
        modifier = modifier
            .offset { IntOffset(box.left, box.top) }
            .width(width.value)
            .height(height.value)
            .background(KBVoid, KBShapeCard)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height((height.value * 0.46f))) {
            SpotlightPanelTrailer(
                trailerKey = trailerKey,
                imdbId = imdbId,
                tmdbId = tmdbId,
                type = meta?.type,
                muted = trailerMuted,
                mode = trailerMode,
                heroTrailerAutoplay = heroTrailerAutoplay,
                delayMs = trailerDelayMs,
                stillUrl = snapshot.stillUrl ?: meta?.background ?: meta?.poster,
                title = snapshot.title,
                pressRequest = trailerPresses,
                onPlayingChange = onTrailerPlayingChange
            )
            if (snapshot.isWatched) {
                Text(
                    text = "\u2713",
                    color = KBAccent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                )
            }
        }
        Text(
            text = meta?.name ?: snapshot.title.orEmpty(),
            color = KBTextHi,
            style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = spotlightMetaLine(meta),
            color = KBTextLo,
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val overview = meta?.description?.trim().orEmpty()
        if (overview.isNotEmpty()) {
            Text(
                text = overview,
                color = KBTextLo,
                style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        SpotlightPillRow(
            unaired = snapshot.isUnaired,
            showTrailer = SpotlightTrailerRule.showsTrailerPill(
                trailerMode,
                heroTrailerAutoplay,
                trailerKey
            ),
            pillsFocus = pillsFocus,
            tracker = tracker,
            onPlay = onPlay,
            onDetails = onDetails,
            onTrailer = { trailerPresses += 1 }
        )
    }
}

/**
 * The panel's actions.
 *
 * A FlowRow, not a Row: three pills are wider than a portrait card's panel, and
 * a plain Row let TRAILER render outside the panel's own background (and be cut
 * entirely for a right-column card). Wrapping keeps every pill inside it.
 *
 * The row is the panel's focus neighbourhood (see [SpotlightCardFocus]): its
 * first pill is what DOWN from the card lands on, focus inside the row keeps
 * the panel up, and UP returns to the card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpotlightPillRow(
    unaired: Boolean,
    showTrailer: Boolean,
    pillsFocus: FocusRequester,
    tracker: SpotlightCardFocus?,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onTrailer: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state -> tracker?.onPillsFocusChanged(state.hasFocus) }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    tracker?.onUpFromPills() == true
                } else {
                    false
                }
            }
    ) {
        if (unaired) {
            // Detail's own rule: an unaired card says UNAVAILABLE and offers no
            // PLAY. Its PLAY used to open Detail and auto-press Play for an
            // episode with no streams.
            SpotlightPill(
                label = "UNAVAILABLE",
                onClick = {},
                modifier = Modifier.focusRequester(pillsFocus)
            )
        } else {
            SpotlightPill(
                label = "PLAY",
                onClick = onPlay,
                modifier = Modifier.focusRequester(pillsFocus)
            )
        }
        SpotlightPill(label = "DETAILS", onClick = onDetails)
        if (showTrailer) {
            SpotlightPill(label = "TRAILER", onClick = onTrailer)
        }
    }
}

/** The meta line the billboard hero prints: year, runtime, rating, genres. */
internal fun spotlightMetaLine(meta: Meta?): String {
    if (meta == null) return ""
    val parts = ArrayList<String>(4)
    meta.releaseInfo?.trim()?.take(4)?.takeIf { it.isNotEmpty() }?.let { parts += it }
    meta.runtime?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    meta.imdbRating?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "\u2605 $it" }
    meta.genres?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    return parts.joinToString(" \u00b7 ")
}

@Composable
private fun SpotlightPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    com.kennyb1201.kbstream.ui.components.KBCard(
        onClick = onClick,
        modifier = modifier,
        shape = KBShapeCard
    ) {
        Text(
            text = label,
            color = KBTextHi,
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** How much bigger than its card the preview panel asks to draw. */
private const val PANEL_GROWTH = 1.9f

/** The expansion's own animation, matching the focus scale's feel. */
private const val EXPAND_MS = 220

/** The panel never touches the screen edge (or the rail's own landing inset). */
internal val SPOTLIGHT_PANEL_MARGIN = 16.dp

/** Where the panel is drawn: the clamped, flipped box from [spotlightPanelBox]. */
internal fun spotlightPanelOffset(box: SpotlightPanelBox): IntOffset =
    IntOffset(box.left, box.top)
