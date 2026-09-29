package com.kennyb1201.kbstream.ui.components

/**
 * How a sampled brand mark should be drawn on the dark header.
 *
 *  - [AS_IS]    colored marks (Netflix N, NBC peacock), light marks, and dark
 *               PLATES that carry their own light lettering.
 *  - [WHITEN]   marks drawn for a light background: a dark glyph on
 *               transparency, or a dark plate whose lettering is knocked out.
 *               A SrcIn tint preserves the alpha and turns the dark parts
 *               white, so knockout lettering reads as the dark header showing
 *               through a white mark.
 *  - [UNUSABLE] a featureless filled plate, or artwork that never arrives.
 *               Nothing is drawn, so the header's name text stands alone
 *               rather than a white disc.
 */
internal enum class BrandMark { AS_IS, WHITEN, UNUSABLE }

/** Alpha at or above which a sampled pixel counts as part of the mark. */
internal const val BRAND_MARK_OPAQUE_ALPHA = 64

/**
 * Share of the sampled tile that must be enclosed transparent pixels before a
 * dark plate is treated as carrying knockout lettering. Measured on TMDB's
 * real artwork (2026-09): Bravo 0.085, CMT 0.146, FXX 0.032, BET 0.406 - all
 * wordmarks knocked out of a solid plate - against 0.000 for the featureless
 * plates (TNT's, E!'s) and for ABC's opaque-lettering disc. A tenth of a
 * percent separates them with room to spare.
 */
internal const val BRAND_MARK_KNOCKOUT_MIN_SHARE = 0.005f

/**
 * Decides how a decoded brand mark should be drawn on the dark header.
 *
 * Three measurements separate the cases:
 *
 *  - coverage: fraction of the tile that is opaque. A wordmark or a glyph
 *    covers well under half of it; a filled plate covers most of it.
 *  - spread: the luminance range between the mark's darkest and lightest
 *    opaque pixel. It is near zero for a flat plate and large for a mark that
 *    has its own internal contrast (dark plate, light lettering).
 *  - avgLum / avgSat: how dark and how colorless the mark is overall.
 *
 * A dark, colorless, HIGH-coverage plate with no internal contrast is the case
 * [BrandMark.UNUSABLE] exists for: whitening it erases whatever it said, and
 * leaving it alone hides a dark disc on a dark header. But that verdict is
 * only right when the plate is FEATURELESS. TMDB ships several networks only
 * as a solid plate with the wordmark knocked out of it - transparent letters
 * inside the mark - and those whitened render exactly as intended, as
 * lettering on a light background. [hasKnockoutDetail] tells the two apart by
 * looking for transparent pixels the mark encloses, which is what stopped
 * Bravo, CMT, FXX and BET from showing anything at all.
 */
internal fun brandMarkTreatment(pixels: IntArray, width: Int, height: Int): BrandMark {
    if (pixels.isEmpty() || width <= 0 || height <= 0) return BrandMark.AS_IS

    var count = 0
    var lumTotal = 0f
    var satTotal = 0f
    var lumMin = 1f
    var lumMax = 0f
    for (pixel in pixels) {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < BRAND_MARK_OPAQUE_ALPHA) continue // transparent padding
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        lumTotal += lum
        lumMin = minOf(lumMin, lum)
        lumMax = maxOf(lumMax, lum)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        satTotal += (max - min) / 255f
        count++
    }
    // Fully transparent artwork draws nothing: treat as unreadable rather
    // than reserving the header's logo slot for it.
    if (count == 0) return BrandMark.UNUSABLE

    val coverage = count.toFloat() / pixels.size
    val avgLum = lumTotal / count
    val avgSat = satTotal / count
    val spread = lumMax - lumMin

    return when {
        // Featureless filled plate (solid disc/square): nothing to read. A
        // plate whose lettering is knocked out is not this case - it whitens
        // into readable lettering, so it is treated as a mark for a light
        // background below.
        coverage > 0.62f && spread < 0.12f && avgSat < 0.28f &&
            !hasKnockoutDetail(pixels, width, height) -> BrandMark.UNUSABLE

        // Dark, colorless mark drawn for a light background: safe to recolor
        // white, and the only way it survives a dark header.
        avgLum < 0.55f && avgSat < 0.28f -> BrandMark.WHITEN

        else -> BrandMark.AS_IS
    }
}

/**
 * Whether the mark encloses transparent pixels - a knockout wordmark cut out
 * of a solid plate.
 *
 * The test is a flood fill of the transparent pixels from the tile's border:
 * every transparent pixel the fill cannot reach is enclosed by the mark, i.e.
 * a letter. Transparency around a glyph (the padding a wordmark sits in) is
 * reached from the border, so a plain glyph answers false; a plate with the
 * brand name knocked out of it answers true.
 */
internal fun hasKnockoutDetail(pixels: IntArray, width: Int, height: Int): Boolean {
    if (pixels.size < width * height || width < 3 || height < 3) return false

    fun transparent(x: Int, y: Int): Boolean {
        val alpha = (pixels[y * width + x] ushr 24) and 0xFF
        return alpha < BRAND_MARK_OPAQUE_ALPHA
    }

    val reached = BooleanArray(width * height)
    val queue = ArrayDeque<Int>()
    for (x in 0 until width) {
        for (y in intArrayOf(0, height - 1)) {
            val index = y * width + x
            if (transparent(x, y) && !reached[index]) {
                reached[index] = true
                queue.addLast(index)
            }
        }
    }
    for (y in 0 until height) {
        for (x in intArrayOf(0, width - 1)) {
            val index = y * width + x
            if (transparent(x, y) && !reached[index]) {
                reached[index] = true
                queue.addLast(index)
            }
        }
    }
    while (queue.isNotEmpty()) {
        val index = queue.removeFirst()
        val x = index % width
        val y = index / width
        for (neighbor in intArrayOf(
            if (x > 0) index - 1 else -1,
            if (x < width - 1) index + 1 else -1,
            if (y > 0) index - width else -1,
            if (y < height - 1) index + width else -1
        )) {
            if (neighbor >= 0 && transparent(neighbor % width, neighbor / width) &&
                !reached[neighbor]
            ) {
                reached[neighbor] = true
                queue.addLast(neighbor)
            }
        }
    }

    var enclosed = 0
    for (index in pixels.indices) {
        if (!reached[index] && transparent(index % width, index / width)) enclosed++
    }
    return enclosed.toFloat() / pixels.size >= BRAND_MARK_KNOCKOUT_MIN_SHARE
}
