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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
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

    /**
     * Forgets a "nothing usable" answer, because it was a timing miss rather than
     * an answer (F2, 2026-11-17).
     *
     * The miss TTL stays what it is - a throttle against re-decoding the same file
     * every time a surface redraws - but it only describes art the sampler has
     * actually seen. The moment that art lands (see [ArtworkTintArrivals]) the
     * answer it was standing in for is void, so the next sample is allowed to
     * happen now instead of up to 20 s later.
     */
    @Synchronized
    fun invalidateMiss(url: String) {
        misses.remove(url)
    }

    @Synchronized
    fun clear() {
        hits.clear()
        misses.clear()
    }
}

/**
 * A tint and whether its question has been ANSWERED - the pair the gradient needs
 * to tell "no colour yet" from "no colour at all" (F3, 2026-11-17).
 *
 * [resolved] is false while the only thing known is that the art had not landed
 * when it was last looked for. It becomes true on a hit, and on a sample taken
 * AFTER the art landed: that sample's null is the artwork's own answer (true grey
 * / black / white), and a gradient holding a previous card's colour on top of it
 * is showing the viewer a colour that belongs to something else.
 */
internal data class ArtworkTintState(
    val tint: ArtworkTint?,
    val resolved: Boolean
)

/**
 * The tint for [url], and whether it is an answer.
 *
 * Two triggers, one answer:
 *
 *  - **the disk, now**: a short retry window covers the common case where the art
 *    is on its way in when the screen opens (the original F2);
 *  - **the art's arrival** (F1, 2026-11-17): the image loader reports every load
 *    that reached the disk, so a backdrop that takes longer than the window is
 *    sampled the moment it lands - on the same visit, on both surfaces - instead
 *    of never. That was the whole defect: the trigger was the bug, not the
 *    sampler, and the TV held the previous card's colour while the phone Detail
 *    drew nothing at all.
 *
 * [ArtworkTintArrivals] carries the arrival; the miss it invalidates is the timing
 * miss F2 says is not an answer.
 */
@Composable
internal fun rememberArtworkTintState(url: String?): ArtworkTintState {
    val context = LocalContext.current
    val key = url?.trim()?.takeIf { it.isNotBlank() }
    var tint by remember(key) {
        mutableStateOf(key?.let { ArtworkTintCache.get(it) })
    }
    var resolved by remember(key) { mutableStateOf(tint != null) }

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
                resolved = true
                return@LaunchedEffect
            }
        }
        // Out of window. Whether that null is an answer depends on whether the art
        // had already landed when the loop started: if it had, the file was there
        // and the artwork simply has no usable colour; if it had not, this is a
        // timing miss, the record below is only a throttle, and the arrival
        // effect answers it properly.
        if (ArtworkTintArrivalLedger.hasLanded(key)) resolved = true
        ArtworkTintCache.putMiss(key)
    }

    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        ArtworkTintArrivals.landed
            .filter { arrived -> arrived == key }
            .collect {
                if (ArtworkTintCache.get(key) != null) return@collect
                val computed = withContext(Dispatchers.IO) { sampleFromCache(context, key) }
                if (computed != null) {
                    ArtworkTintCache.put(key, computed)
                    tint = computed
                } else {
                    // The file is on disk and holds no usable colour: a real
                    // answer (S4), not a timing miss.
                    ArtworkTintCache.putMiss(key)
                }
                resolved = true
            }
    }

    return ArtworkTintState(tint = tint, resolved = resolved)
}

/**
 * The arrival signal the tint listens to, fed by the app's image loader.
 *
 * One event per load that REACHED THE DISK - not per memory hit: a memory hit
 * means the file was already there, which the sampler's own window has covered.
 * The tint samples the disk cache and nothing else (F5), so a disk arrival is
 * precisely the moment its answer becomes readable.
 *
 * The sampler's own request goes through the same loader, so the ledger drops the
 * probe's own success rather than letting the signal feed itself.
 */
internal object ArtworkTintArrivals {

    private val _landed = MutableSharedFlow<String>(
        // Last arrival replayed: a card composed after its art landed still gets
        // its sample instantly. Drop-oldest, because a fling can outrun a sample.
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** The URLs whose art has just landed; see [noteLoaded]. */
    val landed: SharedFlow<String> = _landed

    /** Called by the image loader on a success that reached the disk cache. */
    fun noteLoaded(url: String?) {
        val key = url?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (!ArtworkTintArrivalLedger.noteLanded(key)) return
        // F2: whatever "nothing usable" was recorded for this art before it
        // arrived is void - it described a file that is now here.
        ArtworkTintCache.invalidateMiss(key)
        _landed.tryEmit(key)
    }
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
    // The probe is marked for its whole life: its success reaches the same
    // listener as every surface's, and counting it as an arrival would make the
    // tint answer its own question forever (see ArtworkTintArrivalLedger).
    ArtworkTintArrivalLedger.beginProbe(url)
    try {
        val result = runBlocking { loader.execute(request) }
        val bitmap: Bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
        sampleTint(bitmap)
    } finally {
        ArtworkTintArrivalLedger.endProbe(url)
    }
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
     * False while the tint is still unknown, true once it is an answer (see
     * [ArtworkTintState]). A held colour is released when this is true and the
     * tint is null - F3's "the hold is transient, not sticky".
     */
    resolved: Boolean = true,
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
    // F3: the hold is for an UNANSWERED card - no flash of black while a new
    // card's art is still coming. Once the art has landed and the sample says it
    // has no usable colour, that is the answer, and the strip must let the
    // previous card's colour go rather than wear it for the life of the visit.
    val shown = tint ?: if (resolved) null else held

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
