package com.kennyb1201.kbstream.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The two colours one piece of artwork contributes (spec 2026-11-16, Parts B/C).
 *
 * [base] is the darker of the pair and [glow] the lifted one; a gradient is drawn
 * base -> glow -> transparent, so the surface never becomes a flat colour wash
 * and text keeps its contrast (C4).
 */
internal data class ArtworkTint(
    val base: Color,
    val glow: Color
)

/**
 * The extracted colours for one URL, memoised for the process.
 *
 * Bounded and tiny on purpose: it exists so a focus move back to a card the viewer
 * has already seen is instant, and so the same poster in the Spotlight rail and the
 * Detail header is sampled once (C2's "the second surface costs the first one's
 * work").
 *
 * A HIT is final. A MISS is not (F2, 2026-11-16): the first version stored "nothing
 * usable" as if it were an answer, so a title sampled while its backdrop was still
 * downloading stayed untinted for the rest of the process - and because the artwork
 * arrives AFTER the screen opens, that was every title. A miss is now remembered
 * only briefly: long enough that redrawing the screen cannot re-decode the same
 * file, short enough that the next visit, once the art is on disk, tints.
 */
internal object ArtworkTintCache {

    private const val MAX_ENTRIES = 96

    /** How long a "nothing usable in this art" answer stays fresh. */
    private const val MISS_TTL_MS = 20_000L

    private val hits = object : LinkedHashMap<String, ArtworkTint>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkTint>?): Boolean =
            size > MAX_ENTRIES
    }

    private val misses = object : LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(url: String): ArtworkTint? = hits[url]

    /** True while a "nothing usable" answer for [url] is still fresh. */
    @Synchronized
    fun isRecentMiss(url: String): Boolean {
        val at = misses[url] ?: return false
        if (System.currentTimeMillis() - at < MISS_TTL_MS) return true
        misses.remove(url)
        return false
    }

    @Synchronized
    fun put(url: String, tint: ArtworkTint) {
        hits[url] = tint
        misses.remove(url)
    }

    @Synchronized
    fun putMiss(url: String) {
        misses[url] = System.currentTimeMillis()
    }

    @Synchronized
    fun clear() {
        hits.clear()
        misses.clear()
    }
}

/**
 * A title's or card's tint, resolved from art already in the image cache.
 *
 * Null while nothing is known - the caller decides whether that means "draw
 * nothing" (Part C) or "keep the gradient you have" (Part B3).
 */
@Composable
internal fun rememberArtworkTint(url: String?): ArtworkTint? {
    val context = LocalContext.current
    val key = url?.trim()?.takeIf { it.isNotBlank() }
    var tint by remember(key) {
        mutableStateOf(key?.let { ArtworkTintCache.get(it) })
    }
    LaunchedEffect(key) {
        if (key == null || tint != null) return@LaunchedEffect
        if (ArtworkTintCache.isRecentMiss(key)) return@LaunchedEffect
        // This runs before the header's own image request has necessarily
        // finished - and the tint exists to describe THAT art - so a sample that
        // finds no file yet asks again shortly instead of giving up for the rest
        // of the visit (F2). Once the file lands, the very next attempt hits.
        repeat(TINT_SAMPLE_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(TINT_SAMPLE_RETRY_MS)
            val computed = withContext(Dispatchers.IO) { sampleFromCache(context, key) }
            if (computed != null) {
                ArtworkTintCache.put(key, computed)
                tint = computed
                return@LaunchedEffect
            }
        }
        ArtworkTintCache.putMiss(key)
    }
    return tint
}

/**
 * The one place a bitmap is sampled (B1/C2).
 *
 * `READ_ONLY` memory + `READ_ONLY` disk + `DISABLED` network is the spec's "never
 * fetches" rule at the wire: the disk file was downloaded to DISPLAY the art, so
 * decoding it small is reading what is already there, while the network leg stays
 * shut and a title that was never loaded has no tint to give.
 *
 * Memory alone was not enough, and that was the bug the phone reported (2026-11-16,
 * F1): Coil's memory key includes the requested SIZE, so a 96px lookup can never
 * reuse the hero-sized bitmap the header loaded, and it always missed. The disk key
 * is the URL's, so the file the header just wrote IS found here.
 *
 * `allowHardware(false)` for a related reason: `getPixel` on a hardware bitmap
 * throws, which would have turned every hit into a null anyway.
 */
internal fun sampleFromCache(context: Context, url: String): ArtworkTint? = runCatching {
    val appContext = context.applicationContext
    val loader = SingletonImageLoader.get(appContext)
    val request = ImageRequest.Builder(appContext)
        .data(url)
        .size(SAMPLE_EDGE_PX, SAMPLE_EDGE_PX)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.READ_ONLY)
        .diskCachePolicy(CachePolicy.READ_ONLY)
        .networkCachePolicy(CachePolicy.DISABLED)
        .build()
    val result = runBlocking { loader.execute(request) }
    val bitmap: Bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
    sampleTint(bitmap)
}.getOrNull()

/** Samples a grid of pixels out of an already-decoded bitmap. */
internal fun sampleTint(bitmap: Bitmap, grid: Int = ArtworkTintRules.SAMPLE_GRID): ArtworkTint? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val step = grid.coerceAtLeast(1)
    val pixels = IntArray(step * step)
    for (y in 0 until step) {
        for (x in 0 until step) {
            val px = (((x + 0.5f) / step) * width).toInt().coerceIn(0, width - 1)
            val py = (((y + 0.5f) / step) * height).toInt().coerceIn(0, height - 1)
            pixels[y * step + x] = bitmap.getPixel(px, py)
        }
    }
    return ArtworkTintRules.fromPixels(pixels)?.let { ArtworkTint(Color(it.base), Color(it.glow)) }
}

/**
 * A tint strip: [base] at the top, [glow] in the middle, transparent at the bottom.
 *
 * Crossfaded rather than switched (B3): the colours animate over
 * [TINT_CROSSFADE_MS] so a focus move never snaps, and a null tint keeps whatever
 * was last shown until a new one resolves - a card whose art has not loaded yet
 * must not flash black.
 */
@Composable
internal fun ArtworkTintGradient(
    tint: ArtworkTint?,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    maxAlpha: Float = 1f,
    /**
     * Draw the tint upside down: darkest at the BOTTOM edge and clear at the top.
     *
     * Parts B and C want opposite ends - Spotlight's strip fades DOWN out of the
     * top bar, Detail's wash bleeds UP from the backdrop's bottom edge - so the
     * one gradient takes the direction rather than being copied.
     */
    fadeUpwards: Boolean = false
) {
    var held by remember { mutableStateOf<ArtworkTint?>(null) }
    if (tint != null && tint != held) held = tint
    val shown = tint ?: held

    val base by animateColorAsState(
        targetValue = shown?.base ?: Color.Transparent,
        animationSpec = tween(TINT_CROSSFADE_MS),
        label = "artworkTintBase"
    )
    val glow by animateColorAsState(
        targetValue = shown?.glow ?: Color.Transparent,
        animationSpec = tween(TINT_CROSSFADE_MS),
        label = "artworkTintGlow"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible && shown != null) maxAlpha else 0f,
        animationSpec = tween(TINT_FADE_MS),
        label = "artworkTintAlpha"
    )

    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .background(
                Brush.verticalGradient(
                    0.0f to if (fadeUpwards) Color.Transparent else base.copy(alpha = 0.62f),
                    0.55f to glow.copy(alpha = 0.22f),
                    1.0f to if (fadeUpwards) base.copy(alpha = 0.62f) else Color.Transparent
                )
            )
    )
}

private const val SAMPLE_EDGE_PX = 96

/**
 * How many times a sample that found no file waits for the art to land (F2).
 *
 * Six tries a quarter-second apart: long enough to cover a backdrop finishing its
 * download, short enough that an untintable title does not spin.
 */
private const val TINT_SAMPLE_ATTEMPTS = 6
private const val TINT_SAMPLE_RETRY_MS = 250L
private const val TINT_CROSSFADE_MS = 420
private const val TINT_FADE_MS = 320
