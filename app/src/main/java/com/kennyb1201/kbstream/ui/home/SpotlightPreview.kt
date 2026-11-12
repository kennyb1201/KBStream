package com.kennyb1201.kbstream.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * One focused rail card, as the Spotlight preview needs it.
 *
 * [bounds] are the card's own bounds in the window, captured while it has focus -
 * the preview is drawn AT the card and grows out of it, so the position is the
 * whole point. The rest is what a [com.kennyb1201.kbstream.ui.components.PosterCard]
 * already knows about itself; the meta line comes from the hero META resolve
 * (see [SpotlightPreviewPanel]), which keeps running here for exactly this.
 */
internal data class SpotlightCardSnapshot(
    val title: String?,
    val stillUrl: String?,
    val isWatched: Boolean,
    val isPartiallyWatched: Boolean,
    val bounds: Rect
)

/**
 * The Spotlight focus tracker: which rail card has focus, and since when.
 *
 * A rail card reports itself here through [com.kennyb1201.kbstream.ui.components.PosterCard],
 * so no rail has to know about Spotlight: the cards already draw themselves
 * through one component, and this is the one place they tell it that they are
 * the focused one. Null means no card is focused (the hero's pills, the top
 * bar, a long-press menu) - which is also what collapses the preview.
 */
internal class SpotlightCardFocus {

    var snapshot by mutableStateOf<SpotlightCardSnapshot?>(null)
        private set

    /** Uptime of the moment the current card gained focus (see the expand delay). */
    var focusedAtMs by mutableLongStateOf(0L)
        private set

    /** Bounds-only update, so a scroll under a still-focused card repositions it. */
    fun updateBounds(bounds: Rect) {
        val current = snapshot ?: return
        if (current.bounds == bounds) return
        snapshot = current.copy(bounds = bounds)
    }

    fun onCardFocus(focused: Boolean, card: () -> SpotlightCardSnapshot) {
        if (focused) {
            snapshot = card()
            focusedAtMs = System.currentTimeMillis()
        } else {
            snapshot = null
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
 * meta line, the overview (two lines) and the two pills the billboard hero
 * offers, plus the watched mark and a progress bar when the card had one.
 *
 * The trailer is the card's own, and it obeys the billboard's single mode:
 * AUTO plays it here (that is why the hero billboard is handed OFF while this is
 * up - see [SpotlightTrailerRule]), MANUAL offers the button, OFF shows neither.
 */
@Composable
internal fun SpotlightPreviewPanel(
    snapshot: SpotlightCardSnapshot,
    meta: Meta?,
    trailerKey: String?,
    trailerMode: BillboardTrailerMode,
    trailerMuted: Boolean,
    heroTrailerAutoplay: Boolean,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // The panel is wider than the card and taller, and it grows from the card's
    // own corner - an expansion, not a jump.
    val width = animateDpAsState(
        targetValue = (snapshot.bounds.width / context.resources.displayMetrics.density)
            .dp * PANEL_GROWTH,
        animationSpec = tween(EXPAND_MS),
        label = "spotlightWidth"
    )
    val height = animateDpAsState(
        targetValue = (snapshot.bounds.height / context.resources.displayMetrics.density)
            .dp * PANEL_GROWTH,
        animationSpec = tween(EXPAND_MS),
        label = "spotlightHeight"
    )

    var trailer by remember(snapshot.bounds, trailerKey) {
        mutableStateOf<PlayableSource?>(null)
    }
    var trailerRequested by remember(snapshot.bounds, trailerKey) { mutableStateOf(false) }

    // AUTO counts its six seconds from the EXPANSION (this composition), not from
    // focus: the expand delay and the trailer delay never stack into one timer.
    LaunchedEffect(snapshot.bounds, trailerKey, trailerMode) {
        if (trailerKey.isNullOrBlank()) return@LaunchedEffect
        if (!SpotlightTrailerRule.autoplaysInCard(trailerMode, heroTrailerAutoplay)) {
            return@LaunchedEffect
        }
        delay(SpotlightTrailerRule.EXPANDED_CARD_DELAY_MS)
        trailerRequested = true
    }
    // One resolve path: the hero's launcher, with its cache, its ladder, its
    // poison and its retries. The card invents no second way to fetch a trailer.
    LaunchedEffect(trailerRequested, trailerKey) {
        if (!trailerRequested || trailerKey.isNullOrBlank() || trailer != null) {
            return@LaunchedEffect
        }
        // The launcher already answers with a Result (and rethrows its own
        // cancellation), so this is the resolve's own outcome - a null on any
        // refusal, which leaves the still showing.
        trailer = TrailerPlayerLauncher.resolvePlayableUrl(
            trailerKey,
            imdbId = null,
            tmdbId = null,
            type = meta?.type
        ).getOrNull()
    }

    Column(
        modifier = modifier
            .width(width.value)
            .height(height.value)
            .background(KBVoid, KBShapeCard)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(height.value * 0.52f)) {
            val still = snapshot.stillUrl ?: meta?.background ?: meta?.poster
            if (trailer != null) {
                HeroInlineTrailerPlayer(
                    source = trailer!!,
                    muted = trailerMuted,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (!still.isNullOrBlank()) {
                AsyncImage(
                    model = still,
                    contentDescription = snapshot.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpotlightPill(label = "PLAY", onClick = onPlay)
            SpotlightPill(label = "DETAILS", onClick = onDetails)
            if (
                trailer == null &&
                SpotlightTrailerRule.showsTrailerButton(trailerMode, heroTrailerAutoplay)
            ) {
                SpotlightPill(label = "TRAILER", onClick = { trailerRequested = true })
            }
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
private fun SpotlightPill(label: String, onClick: () -> Unit) {
    com.kennyb1201.kbstream.ui.components.KBCard(
        onClick = onClick,
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

/** How much bigger than its card the preview panel draws. */
private const val PANEL_GROWTH = 1.9f

/** The expansion's own animation, matching the focus scale's feel. */
private const val EXPAND_MS = 220

/** Where the panel is drawn: over the card, skipping past its own corner. */
internal fun spotlightPanelOffset(bounds: Rect): IntOffset =
    IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt())
