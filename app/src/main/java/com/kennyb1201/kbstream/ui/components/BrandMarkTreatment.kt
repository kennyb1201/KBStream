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
 * Luminance at or above which an opaque pixel counts as the mark's own LIGHT
 * detail — chrome, silver or white lettering on a dark plate.
 *
 * 0.72 is deliberately above the grey a JPEG-compressed plate ripples through:
 * the point is to find DRAWN light content, not the top of a noisy gradient.
 */
internal const val BRAND_MARK_LIGHT_LUM = 0.72f

/**
 * Share of the mark's OPAQUE pixels that must be light before it is treated as
 * carrying its own readable light detail.
 *
 * Measured on TMDB's real artwork: Matchbox's clearlogo is chrome lettering on
 * a black plate with 15% of its opaque pixels above [BRAND_MARK_LIGHT_LUM]
 * (2026-10-18), while the anti-alias fringe around a dark glyph — the only
 * other way light pixels appear — sits well under a percent. Two percent
 * separates them with room to spare.
 */
internal const val BRAND_MARK_LIGHT_DETAIL_MIN_SHARE = 0.02f

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
 *  - lightShare: the share of OPAQUE pixels that are light. This is what
 *    separates a dark glyph (or a plate whose lettering is knocked out) from a
 *    dark plate that already carries its own light lettering — chrome, silver
 *    or white — and therefore needs no help (see [BrandMark.WHITEN]).
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
 *
 * The whitening has a second failure the measurements above cannot see, and it
 * is the one the field reported (2026-10-18): Matchbox's clearlogo is chrome
 * lettering ON a black plate - average luminance 0.42, average saturation
 * 0.14, so "dark and colorless" - but 15% of its opaque pixels are LIGHT. The
 * SrcIn tint recolored the plate AND the letters into one solid white rounded
 * rectangle. The rule now looks at [lightShare] for exactly this: a mark
 * already carrying readable light detail is drawn as it is (light lettering on
 * a dark surface needs no help), while the knockout case the whitening exists
 * for has no opaque light pixels at all - transparent letters are skipped by
 * the sampler - so it still whitens.
 */
internal fun brandMarkTreatment(pixels: IntArray, width: Int, height: Int): BrandMark {
    if (pixels.isEmpty() || width <= 0 || height <= 0) return BrandMark.AS_IS

    var count = 0
    var lumTotal = 0f
    var satTotal = 0f
    var lumMin = 1f
    var lumMax = 0f
    var lightCount = 0
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
        if (lum > BRAND_MARK_LIGHT_LUM) lightCount++
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
        // white - UNLESS it already carries opaque light detail (chrome /
        // silver / white lettering on the dark plate). Whitening that erases the
        // lettering into a solid white plate; drawn as-is, the light lettering
        // reads on the dark surface with no help.
        avgLum < 0.55f && avgSat < 0.28f &&
            lightCount.toFloat() / count < BRAND_MARK_LIGHT_DETAIL_MIN_SHARE ->
            BrandMark.WHITEN

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

// ── trimming the transparent borders ─────────────────────────────────────

/**
 * Alpha above which a decoded pixel counts as the mark's INK, for trimming the
 * transparent border off the artwork before it is fitted into its slot.
 *
 * Deliberately much lower than [BRAND_MARK_OPAQUE_ALPHA], which decides which
 * pixels the mark's COLOUR is averaged over: a soft drop shadow at alpha 40 is
 * still artwork as far as cropping goes, while a few pixels of JPEG ringing are
 * not. Both error directions are visible on a card - too low and a padded
 * wordmark keeps its halo (and so goes on rendering small), too high and the
 * shadow is sheared off as if the mark ended at its hard core.
 *
 * Pinned by test (see `BrandMarkInkTest`), not tuned by eye.
 */
internal const val BRAND_MARK_INK_ALPHA = 10

/**
 * The visible ink's bounds inside a brand mark's FILE, as fractions of it
 * (`left`/`top` where the ink starts, `right`/`bottom` where it ends).
 *
 * This exists because a clearlogo's file box is not its drawn box. TMDB's logo
 * files carry wildly different amounts of transparent padding, and a card fits
 * the WHOLE file into its slot, so a padded wordmark's ink lands far smaller
 * than the slot while a tight one fills it - in the same rail, from the same
 * card size (field report, 2026-10-25). The fractions let the card fit the ink
 * instead, without touching the slot the tight logos already fill correctly.
 */
internal data class BrandMarkInk(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Share of the file's width the ink spans. */
    val widthFraction: Float get() = right - left

    /** Share of the file's height the ink spans. */
    val heightFraction: Float get() = bottom - top
}

/**
 * Measures the bounding box of the mark's visible ink: the smallest box that
 * still contains every pixel above [BRAND_MARK_INK_ALPHA].
 *
 * Runs over the SAME pixels [brandMarkTreatment] is handed - one pass over an
 * already-allocated array, so trimming adds no decode, no retained bitmap and
 * no allocation of its own. Nothing here is per frame or per card: the caller
 * measures once per logo URL and keeps four floats.
 *
 * Returns null when there is no box to draw into: no ink at all (a blank
 * plate, artwork that never arrived), or a degenerate sample. Callers then draw
 * the mark UNTRIMMED, exactly as they did before trimming existed - a failure
 * here must never hide a logo, stand in for it with title text, or push it down
 * the unusable path.
 *
 * The box is measured on the coarse tile the sampler already builds (48x48, see
 * `sampleBrandMark`), so each edge is accurate to about 1/48 of that axis - a
 * few tenths of a dp at the sizes a clearlogo is drawn, and always generous
 * rather than tight where the resample blurs an edge, so ink is never clipped
 * away.
 */
internal fun brandMarkInk(pixels: IntArray, width: Int, height: Int): BrandMarkInk? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null

    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val alpha = (pixels[row + x] ushr 24) and 0xFF
            if (alpha <= BRAND_MARK_INK_ALPHA) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    if (maxX < minX || maxY < minY) return null

    return BrandMarkInk(
        left = minX.toFloat() / width,
        top = minY.toFloat() / height,
        right = (maxX + 1).toFloat() / width,
        bottom = (maxY + 1).toFloat() / height,
    )
}

/**
 * Where the ink box of [ink] has to be drawn so that it - and not the padded
 * file around it - is what is fitted into the slot.
 *
 * [scale] and ([x], [y]) are the affine transform to apply to content that is
 * ALREADY laid out with `ContentScale.Fit` for the WHOLE file: `c * scale + (x,
 * y)`, measured in slot pixels from the slot's top-left corner. [width] and
 * [height] are the size the ink finally draws at, which is what the tests pin
 * (a padded wordmark draws as large as the same wordmark drawn tight, a mark
 * whose ink is wider than the slot still shrinks by the slot's width cap).
 *
 * The slot is the whole budget, so the width cap applies to the INK: a
 * 68%-of-the-card box constrains 68% of ink, where before it constrained 68%
 * of a file that might have been half padding. Padding never spends it.
 *
 * Returns null for a slot or image that cannot be laid out; the caller then
 * draws the mark untrimmed.
 */
internal fun layoutBrandMarkInk(
    slotWidth: Float,
    slotHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    ink: BrandMarkInk,
): BrandMarkInkPlacement? {
    if (slotWidth <= 0f || slotHeight <= 0f) return null
    if (imageWidth <= 0 || imageHeight <= 0) return null

    val fileWidth = imageWidth.toFloat()
    val fileHeight = imageHeight.toFloat()
    val inkWidth = fileWidth * ink.widthFraction
    val inkHeight = fileHeight * ink.heightFraction
    if (inkWidth <= 0f || inkHeight <= 0f) return null

    // What the slot draws today: the whole file, fitted into it.
    val fileFit = minOf(slotWidth / fileWidth, slotHeight / fileHeight)
    // What it should draw instead: the ink box, fitted into the same slot.
    val inkFit = minOf(slotWidth / inkWidth, slotHeight / inkHeight)
    if (fileFit <= 0f || inkFit <= 0f) return null

    val drawnWidth = inkWidth * inkFit
    val drawnHeight = inkHeight * inkFit
    // Both boxes are centred in the slot, which is what ContentScale.Fit does
    // with a single image.
    val fileX = (slotWidth - fileWidth * fileFit) / 2f
    val fileY = (slotHeight - fileHeight * fileFit) / 2f
    val scale = inkFit / fileFit
    return BrandMarkInkPlacement(
        scale = scale,
        x = (slotWidth - drawnWidth) / 2f - ink.left * fileWidth * inkFit - fileX * scale,
        y = (slotHeight - drawnHeight) / 2f - ink.top * fileHeight * inkFit - fileY * scale,
        width = drawnWidth,
        height = drawnHeight,
    )
}

/**
 * The transform [layoutBrandMarkInk] computes, plus the size the ink draws at.
 */
internal data class BrandMarkInkPlacement(
    val scale: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)
