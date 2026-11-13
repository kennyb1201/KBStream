package com.kennyb1201.kbstream.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.reporting.SpotlightDiagTrace
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.detail.isEpisodeUnavailable
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
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
    /**
     * The card's own Detail identity (audit A4).
     *
     * The panel's PLAY and DETAILS act on THIS, not on the resolved hero meta:
     * the enrichment is debounced and can fail, and a pill that needs it was a
     * silent dead press until it landed. Null only for a card that was handed no
     * id (nothing on a Home rail is).
     */
    val id: String? = null,
    val type: String? = null,
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
 * The Spotlight focus tracker: which rail card has focus.
 *
 * A rail card reports itself here through [com.kennyb1201.kbstream.ui.components.PosterCard],
 * so no rail has to know about Spotlight: the cards already draw themselves
 * through one component, and this is the one place they tell it that they are
 * the focused one.
 *
 * Only a CATALOG card reports (spec 2026-11-12, F1): CW and Upcoming cards are
 * handed `spotlightExpandable = false` at their own call sites, so they never
 * set a snapshot and never arm the dwell. Their focus ring, their click and
 * their resume/progress behaviour are the ones they always had.
 */
internal class SpotlightCardFocus {

    var snapshot by mutableStateOf<SpotlightCardSnapshot?>(null)
        private set

    /**
     * Bumped every time a card TAKES focus - the dwell's key (F6b).
     *
     * The panel's effect keys on this rather than on [snapshot], because
     * [updateBounds] copies the snapshot whenever the card's bounds change (a
     * scroll, the hero landing, a feed re-resolve). Keyed on the object, every
     * one of those copies RESTARTED the two-second delay: a card under a settling
     * list never reached its dwell at all - the field report's "no card expands".
     * A bounds change now only moves the panel; a focus change to a different
     * (or the same, re-entered) card still arms a fresh dwell.
     */
    var focusToken by mutableIntStateOf(0)
        private set

    /**
     * True while a rail card holds focus.
     *
     * Separate from [snapshot] since the 2026-11-12 dwell spec: the snapshot is
     * the card the viewer last held - kept across a blur, because a
     * recomposition can blur and re-focus the same card inside a frame - while
     * this is the honest "is anything focused" bit the Home's debounce reads. A
     * blur that clears the snapshot outright is what cancelled a two-second
     * dwell mid-hold and left the panel unopened.
     */
    var cardFocused by mutableStateOf(false)
        private set

    /**
     * The card NODE that last reported focus (spec 2026-11-12, F5).
     *
     * The snapshot's `type:id` is not a card identity: the same show appears on
     * Continue Watching, on Upcoming and on a catalog rail, and it appears on two
     * different catalog rails as well. Keyed on the title, moving between those
     * cards read as "still the same card", so the panel followed the new bounds
     * and never collapsed - the field report's stuck panel. This is the node, so
     * "another card" means another card.
     */
    var focusedNode: Any? = null
        private set

    /** The focused card's own node, so BACK can hand focus back to it. */
    var cardRequester: FocusRequester? = null

    /** Bounds-only update, so a scroll under a still-focused card repositions it. */
    fun updateBounds(bounds: Rect) {
        val current = snapshot ?: return
        if (current.bounds == bounds) return
        snapshot = current.copy(bounds = bounds)
    }

    fun onCardFocus(focused: Boolean, card: () -> SpotlightCardSnapshot) {
        if (focused) {
            // The card sets `cardRequester` before it reports, so this is the
            // node that just took focus.
            focusedNode = cardRequester
            focusToken++
            val next = card()
            snapshot = next
            cardFocused = true
            // The paste is taken from Settings, so by then this card has
            // blurred - the trace keeps THIS one as its last (spec 2026-11-12,
            // F5), which is what answers "did the tracker ever get a snapshot
            // for the card I held".
            SpotlightDiagTrace.spotlightFocused(
                token = focusToken,
                key = next.id?.let { id -> "${next.type.orEmpty()}:$id" },
                title = next.title
            )
        } else {
            if (snapshot != null) SpotlightDiagTrace.spotlightBlurred()
            // The SNAPSHOT IS KEPT (2026-11-12): focus has left, the card has
            // not gone anywhere. The panel's host debounces this bit and decides,
            // after SPOTLIGHT_BLUR_GRACE_MS with no card taking focus back, that
            // the viewer really has moved on. Clearing it here is what made a
            // one-frame blur look like the viewer leaving.
            cardFocused = false
        }
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
 * meta line and the overview (two lines), plus the watched mark, and it is
 * CLAMPED to the viewport (see [spotlightPanelBox]).
 *
 * THERE ARE NO ACTIONS IN IT (spec 2026-11-12, F2). It used to end in a row of
 * PLAY / DETAILS / TRAILER pills drawn at the card's own width, where they were
 * clipped and truncated - the panel is as wide as a poster, and two pills do not
 * fit. The row is gone rather than shrunk: the card itself is the action now.
 * OK on the expanded card opens Details (the card's own click, unchanged), and
 * DOWN moves to the next rail, which collapses the panel (F3).
 *
 * The trailer is the card's own, mounted by [SpotlightPanelTrailer] with the
 * hero's ladder (F4), and it obeys the single-surface mode: the viewer's
 * autoplay pref decides, and OFF is the only mode that shows nothing.
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
    /** The hero meta has not landed yet, and has not failed: show placeholders. */
    metaLoading: Boolean = false,
    /** The hero meta resolve FAILED: say so and offer the retry (audit A4). */
    metaFailed: Boolean = false,
    onTrailerPlayingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
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

    // The panel is a poster surface, so it draws the Poster Edge the viewer
    // chose - the same shared shape the rail cards use, live (spec 2026-11-13).
    //
    // ONE clip on the container, not a shape on each layer: the backdrop, the
    // trailer surface, the scrims and the pills are all painted inside this
    // Column, so a single clip is what guarantees the artwork's square corners
    // cannot poke past the curve. The shape object is the whole contract - no
    // radius is copied here and no edge is special-cased.
    val panelShape = posterEdgeShape()

    Column(
        modifier = modifier
            .offset { IntOffset(box.left, box.top) }
            .width(width.value)
            .height(height.value)
            .clip(panelShape)
            .background(KBVoid)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // The backdrop is the panel's own shape now (16:9 at the panel's
        // width), not a share of a card-height panel: that is what makes the
        // still and the trailer read as a picture instead of a thumbnail
        // (spec 2026-11-12, F1).
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            // A title with no still, no backdrop and no poster used to leave this
            // half of the panel an empty KBVoid box (audit a5). The tile's own
            // fallback says what the title is, so the panel uses the same
            // words: the app has no generic empty-art image (ic_cast_placeholder
            // is the cast silhouette, not a title), and the card's fallback has
            // always been text.
            // The FULL-RESOLUTION backdrop first (spec 2026-11-13, F3). The
            // card's `stillUrl` is the rail's own image - a poster/backdrop at
            // card size - and the panel is 62% of the viewport wide in 16:9, so
            // blowing that small image up is what made the backdrop fuzzy. The
            // hero meta's `background` is the title's own backdrop (the w1280
            // variant, or the addon's full-size artwork - see the resolve in
            // HomeViewModel), which is the picture this size wants. The card's
            // still is the fallback, then the poster.
            val stillNow = meta?.background ?: snapshot.stillUrl ?: meta?.poster
            if (stillNow.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(KBSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = snapshot.title.orEmpty(),
                        color = KBTextLo,
                        style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
            SpotlightPanelTrailer(
                trailerKey = trailerKey,
                imdbId = imdbId,
                tmdbId = tmdbId,
                type = meta?.type,
                muted = trailerMuted,
                mode = trailerMode,
                heroTrailerAutoplay = heroTrailerAutoplay,
                delayMs = trailerDelayMs,
                // THE SAME PICTURE the empty-state check above chose (spec
                // 2026-11-13, F1 follow-up). F3 fixed `stillNow` but left this
                // argument on the old order, and this is the one that DRAWS:
                // [SpotlightPanelTrailer] paints it under the surface until the
                // trailer's first frame, and keeps painting it whenever no
                // trailer plays. Handing it the rail's small image first is what
                // kept the expanded panel fuzzy on 0.9.285. Passing the single
                // computed value rather than repeating the chain is what stops
                // the two from drifting apart a third time.
                stillUrl = stillNow,
                title = snapshot.title,
                onPlayingChange = onTrailerPlayingChange
            )
            if (snapshot.isWatched) {
                Text(
                    text = "\u2713",
                    color = KBAccent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                )
            } else if (snapshot.isPartiallyWatched) {
                // The card's own started-not-finished mark, mirrored here (audit
                // a3): a half-watched title looked untouched in the panel while
                // its card carried the eye.
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = "Started, not finished",
                    tint = KBAccent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                )
            }
        }
        Text(
            text = meta?.name ?: snapshot.title.orEmpty(),
            color = KBTextHi,
            style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
            // Two lines, because the panel is now wide enough to carry them: a
            // long title wraps instead of ending in an ellipsis (F2).
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // Silence while the meta resolves was the other half of audit A4: the
        // panel opened blank rather than saying it was still working. Each line
        // has a placeholder, and a failed resolve says so.
        Text(
            text = when {
                metaFailed -> "DETAILS UNAVAILABLE"
                metaLoading -> "LOADING DETAILS…"
                else -> spotlightMetaLine(meta)
            },
            color = KBTextLo,
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
            // Year, runtime, rating and genre at the panel's width: two lines
            // is the whole meta line, not an ellipsis'd start of it.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val overview = meta?.description?.trim().orEmpty()
        val overviewLine = when {
            // The RETRY pill went with the rest of the row (F2): the panel says
            // what failed and the card's own OK opens Details regardless -
            // Details fetches its own metadata.
            metaFailed -> "Could not fetch this title's details."
            metaLoading -> "Fetching the synopsis…"
            else -> overview
        }
        if (overviewLine.isNotEmpty()) {
            Text(
                text = overviewLine,
                color = KBTextLo,
                style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                // The synopsis gets the panel's own line limit - several lines
                // - rather than the two a card-width panel could show. A long
                // description still ellipsizes, but at the panel's limit (F2).
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
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

/** How much bigger than its card the preview panel asks to draw. */
private const val PANEL_GROWTH = 1.9f

/** The expansion's own animation, matching the focus scale's feel. */
private const val EXPAND_MS = 220

/** The panel never touches the screen edge (or the rail's own landing inset). */
internal val SPOTLIGHT_PANEL_MARGIN = 16.dp

/** Where the panel is drawn: the clamped, flipped box from [spotlightPanelBox]. */
internal fun spotlightPanelOffset(box: SpotlightPanelBox): IntOffset =
    IntOffset(box.left, box.top)
