package com.kennyb1201.kbstream.ui.theme

/**
 * The colour decision behind the TV Spotlight gradient and the phone Detail tint
 * (spec 2026-11-16, Parts B and C).
 *
 * Pure on purpose - packed ARGB ints in, packed ARGB ints out - so the rule "is
 * this artwork worth tinting the screen with, and with what" is testable without
 * Android, Compose or a device. The surface half (Compose colours, the Coil cache
 * sample, the crossfade) lives in `ArtworkTint.kt`.
 */
internal data class TintArgb(
    /** The darker colour, drawn at the top of the gradient. */
    val base: Int,
    /** The lifted colour, drawn towards its bottom. */
    val glow: Int
)

internal object ArtworkTintRules {

    /** How many pixels a caller should sample (a grid, not a strip). */
    const val SAMPLE_GRID = 10

    /** Below this a pixel is the letterbox, not the art. */
    private const val MIN_CHANNEL = 24

    /** Above this a pixel is a near-white caption or logo. */
    private const val MAX_CHANNEL = 232

    /** Grey art is left alone: a tint of grey is just a darker screen. */
    private const val MIN_SATURATION = 0.18f
    private const val MIN_VALUE = 0.12f

    private const val HUE_BUCKETS = 12
    private const val BASE_SCALE = 0.55f
    private const val GLOW_LIFT = 0.25f

    /**
     * The artwork's dominant usable colour, or null when it has none.
     *
     * Pixels are grouped into 30-degree hue buckets and the HEAVIEST bucket wins,
     * not the single most saturated pixel: one bright logo in a corner must not
     * decide the colour of a whole title, while a broad field of one hue - a sky,
     * a poster's background - is exactly what a tint is for.
     */
    fun fromPixels(pixels: IntArray): TintArgb? {
        if (pixels.isEmpty()) return null
        val weights = FloatArray(HUE_BUCKETS)
        val sumsR = FloatArray(HUE_BUCKETS)
        val sumsG = FloatArray(HUE_BUCKETS)
        val sumsB = FloatArray(HUE_BUCKETS)

        pixels.forEach { pixel ->
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 160) return@forEach
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max < MIN_CHANNEL || min > MAX_CHANNEL) return@forEach
            val saturation = (max - min).toFloat() / max.toFloat()
            if (saturation < MIN_SATURATION) return@forEach
            val value = max / 255f
            if (value < MIN_VALUE) return@forEach

            val bucket = hueBucket(r, g, b)
            val weight = saturation * value
            weights[bucket] += weight
            sumsR[bucket] += r * weight
            sumsG[bucket] += g * weight
            sumsB[bucket] += b * weight
        }

        val best = weights.indices.maxByOrNull { weights[it] } ?: return null
        val weight = weights[best]
        if (weight <= 0f) return null

        val r = (sumsR[best] / weight).toInt().coerceIn(0, 255)
        val g = (sumsG[best] / weight).toInt().coerceIn(0, 255)
        val b = (sumsB[best] / weight).toInt().coerceIn(0, 255)
        return TintArgb(
            base = darken(r, g, b, BASE_SCALE),
            glow = lift(r, g, b)
        )
    }

    /** Which 30-degree slice of the hue circle a colour sits in. */
    private fun hueBucket(r: Int, g: Int, b: Int): Int {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == rf -> 60f * (((gf - bf) / delta) % 6f)
            max == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }
        val normalized = if (hue < 0f) hue + 360f else hue
        return (normalized / 30f).toInt().coerceIn(0, HUE_BUCKETS - 1)
    }

    /** The base colour: the same hue, pulled down so it can sit under content. */
    private fun darken(r: Int, g: Int, b: Int, scale: Float): Int =
        argb((r * scale).toInt(), (g * scale).toInt(), (b * scale).toInt())

    /** The glow: the same hue lifted towards its own bright end, never white. */
    private fun lift(r: Int, g: Int, b: Int): Int {
        fun channel(c: Int): Int = (c + (255 - c) * GLOW_LIFT).toInt().coerceIn(0, 220)
        return argb(channel(r), channel(g), channel(b))
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        0xFF shl 24 or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
}
