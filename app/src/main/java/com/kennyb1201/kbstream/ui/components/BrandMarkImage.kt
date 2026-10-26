package com.kennyb1201.kbstream.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.Image
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * ONE brand mark drawn for a dark surface, with the treatment
 * [brandMarkTreatment] decides applied to it.
 *
 * The rule lives here rather than at each drawing site because the failure it
 * prevents is invisible in the code and obvious on screen. TMDB's company and
 * network endpoints default to a mark drawn for a LIGHT background - a dark,
 * colorless glyph on transparency - and the app's surfaces are near-black, so
 * the same bytes that read as a logo on themdb.org read as an empty card here.
 * A mark like that is whitened (a SrcIn tint keeps its alpha and turns its
 * shape white); everything else is drawn untouched, because tinting a colored
 * mark (Netflix's N, the NBC peacock) or a dark plate that already carries its
 * own light lettering is what produced the unreadable solid white circles.
 *
 * [onUnusable] is called when nothing can be drawn for [url] at all: a
 * featureless filled plate, or artwork that never arrives. The caller then has
 * to stand in for the mark itself - the studio header keeps its name text, a
 * Home Browse tile keeps its wordmark - rather than leaving a blank slot the
 * artwork silently failed to fill.
 *
 * A single URL, deliberately: walking a ranked candidate LIST is [BrandLogo]'s
 * job, and it is built on this. The caller that has only one URL to draw -
 * a Browse tile, whose art is already a resolved pick - gets the same
 * treatment without having to own a list.
 *
 * [onDrawn] fires when the artwork has actually painted, whatever the verdict.
 * A caller that shows placeholder text until its image arrives (a landscape
 * card's corner title, under the clearlogo) needs that moment; the verdict only
 * decides whether what painted is the mark or the caller's stand-in.
 *
 * The mark is also TRIMMED to its visible ink here, once, for every surface that
 * draws one (landscape cards, the hero, studio headers, browse tiles): a logo
 * file carries whatever transparent padding its publisher left in it, and
 * `ContentScale.Fit` fits the whole file, padding included - so two marks in the
 * same rail and the same slot render at visibly different sizes unless the ink
 * is what gets fitted (field report, 2026-10-25). The SLOT does not change:
 * a tight logo is its own ink box, so the transform on it is an identity, and
 * only the padded files move. See [brandMarkInk] for the measurement and
 * [layoutBrandMarkInk] for the fit; a mark whose box cannot be measured is
 * drawn untrimmed, exactly as before. Every part of this runs once per logo
 * URL - the measurement in the load callback, the transform when its two
 * inputs change - so the scroll path is untouched.
 */
/**
 * The decode cap for a brand mark. Four times the widest slot one is drawn in,
 * so a 2x-density ten-foot render is still sharp, while a published 1000px
 * wordmark no longer becomes a 1000px software bitmap per card.
 */
private const val BRAND_MARK_DECODE_PX = 256

@Composable
internal fun BrandMarkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onDrawn: () -> Unit = {},
    onUnusable: () -> Unit = {}
) {
    val context = LocalContext.current
    // The verdict AND the ink box for this URL, held for as long as the URL is
    // on screen: measured once, in the load callback below, never per frame.
    // (Four floats and a verdict per URL - the same `remember(url)` scope the
    // whitening verdict has always lived in.)
    var sample by remember(url) { mutableStateOf<BrandMarkSample?>(null) }
    // The box the mark is laid out in, reported back by the layout. The
    // transform needs it - the file can be letterboxed inside the box when the
    // card's width cap bites - and it is stable for the life of the card.
    var slot by remember { mutableStateOf(IntSize.Zero) }
    // Computed from the two things it depends on, not on every draw, so the
    // draw path below allocates nothing.
    val placement = remember(sample, slot) {
        val mark = sample ?: return@remember null
        val ink = mark.ink ?: return@remember null
        layoutBrandMarkInk(
            slotWidth = slot.width.toFloat(),
            slotHeight = slot.height.toFloat(),
            imageWidth = mark.imageWidth,
            imageHeight = mark.imageHeight,
            ink = ink
        )
    }
    // The verdict, as the drawing below reads it: AS_IS until the sample lands.
    val treatment = sample?.treatment ?: BrandMark.AS_IS

    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            // Decoded at the size it is drawn. A brand mark arrives at its
            // published size (a network wordmark is routinely 1000px wide) and
            // one of these sits in every landscape card, inside the scroll of
            // every Home rail and every landscape grid. 256px is four times
            // the widest slot a mark is drawn in and still leaves
            // [sampleBrandMark] plenty to sample from, so the verdict is
            // unchanged - the decode and the retained bitmap are an order of
            // magnitude smaller.
            //
            // Deliberately NOT `.allowHardware(false)` any more: that forced
            // Coil to decode every one of these on the CPU and keep a software
            // bitmap per card, which is exactly the scroll cost this decode
            // cap exists to bound. The luminance sample makes its own readable
            // copy instead ([sampleBrandMark]), so the drawn bitmap can stay on
            // the GPU path.
            .size(BRAND_MARK_DECODE_PX)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        colorFilter = if (treatment == BrandMark.WHITEN) {
            ColorFilter.tint(Color.White, BlendMode.SrcIn)
        } else {
            null
        },
        onSuccess = { state ->
            // Paint first: the trim below is a transform on this same paint, so
            // it can never hold the caller's title placeholder open.
            onDrawn()
            val sampled = sampleBrandMark(state.result.image)
            if (sampled.treatment == BrandMark.UNUSABLE) onUnusable() else sample = sampled
        },
        // A logo that never arrives must not hold its slot open either.
        onError = { onUnusable() },
        modifier = modifier
            .onSizeChanged { slot = it }
            .brandMarkInkTrim(placement)
    )
}

/**
 * Draws the mark's INK box into the slot instead of its whole padded file.
 *
 * The content is already laid out with `ContentScale.Fit` for the whole file,
 * so this is one affine transform on that drawing: scale about the slot's
 * top-left corner, then translate, so the ink lands where a tight copy of the
 * same mark would have landed. The clip is what makes it a crop rather than a
 * blow-up that spills out of the slot.
 *
 * A null [placement] - no ink box, an unmeasurable mark, a slot that has not
 * been laid out yet - draws the image exactly as it always was. Failing here
 * must never hide a logo.
 */
private fun Modifier.brandMarkInkTrim(placement: BrandMarkInkPlacement?): Modifier {
    if (placement == null) return this
    return drawWithContent {
        // The image's own draw, which the transforms below re-map. Held as a
        // reference so it can be called from inside those blocks; the lambda
        // stays allocation-free.
        val content = this
        clipRect(0f, 0f, size.width, size.height) {
            translate(placement.x, placement.y) {
                scale(placement.scale, placement.scale, pivot = Offset.Zero) {
                    content.drawContent()
                }
            }
        }
    }
}

/**
 * What one sampling pass learned about a decoded brand mark: how to draw it,
 * the bounds of its visible ink inside the file, and the file's pixel size
 * (which the ink fractions are relative to).
 *
 * [ink] is null when the box could not be measured - a mark with no ink, or an
 * image that never decoded into a readable bitmap - and the mark is then drawn
 * untrimmed.
 */
internal data class BrandMarkSample(
    val treatment: BrandMark,
    val ink: BrandMarkInk?,
    val imageWidth: Int,
    val imageHeight: Int
)

/**
 * ONE brand mark drawn from a RANKED candidate list, walking past the
 * candidates that cannot be drawn on a dark surface.
 *
 * A brand's ranked list is not the same as a drawable one: TMDB holds some
 * networks only as a plate the dark surface reads as blank, as a 1x1 stub, or
 * as artwork that never arrives, and the best-ranked mark is often one of
 * those. Drawing a single URL - what the Home hero used to do - is what made a
 * service look logo-less there while its tile, which walks the list, showed the
 * brand's name instead. Both surfaces are given the same list now and walk it
 * the same way.
 *
 * Nothing is drawn, and [onUnusable] fires, only once every candidate has been
 * rejected - so the caller can stand in for the mark (the tile keeps its
 * wordmark) rather than leaving a slot the artwork silently failed to fill.
 */
@Composable
internal fun BrandMarkLogo(
    urls: List<String>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onUnusable: () -> Unit = {}
) {
    var index by remember(urls) { mutableStateOf(0) }
    // Set when every candidate has been rejected; nothing is drawn after that.
    var exhausted by remember(urls) { mutableStateOf(false) }

    val url = urls.getOrNull(index)
    if (url == null || exhausted) return

    BrandMarkImage(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier,
        onUnusable = {
            if (index + 1 < urls.size) {
                index += 1
            } else {
                exhausted = true
                onUnusable()
            }
        }
    )
}

/**
 * Samples a decoded logo down to one 48x48 tile, hands the pixels to
 * [brandMarkTreatment] for the verdict (which owns that decision, and is
 * unit-tested there), and measures the same pixels' ink box for the trim.
 *
 * Deliberately coarse because this runs once per logo. The image Coil hands us
 * is normally a HARDWARE bitmap now (see the request's own note), and a
 * hardware bitmap's pixels cannot be read or scaled at all - `getPixels`
 * throws and `createScaledBitmap` refuses it - so exactly one software copy is
 * made for the sample and dropped again. The bitmap being DRAWN stays the GPU
 * one, so the scroll path no longer carries a software bitmap per card just to
 * decide a tint.
 *
 * The trim is a second pass over the pixels this function has already read, not
 * a second decode and not a second bitmap: one extra walk of 2,304 ints per
 * logo URL, once. Its box is expressed in the FILE's fractions (hence
 * [BrandMarkSample.imageWidth]/[imageHeight] coming from the decoded bitmap, not
 * the 48x48 tile), so the coarse tile only costs accuracy - about 1/48 of an
 * axis per edge - and never correctness of the aspect.
 */
internal fun sampleBrandMark(image: Image): BrandMarkSample {
    // Only the two bitmaps this function allocates are ever recycled; the one
    // Coil decoded belongs to its cache and is drawn.
    var softwareCopy: Bitmap? = null
    var scaled: Bitmap? = null
    return try {
        val src = (image as? coil3.BitmapImage)?.bitmap
            ?: return BrandMarkSample(BrandMark.AS_IS, null, 0, 0)
        val readable = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false)?.also { softwareCopy = it }
                ?: return BrandMarkSample(BrandMark.AS_IS, null, 0, 0)
        } else {
            src
        }
        val small = if (readable.width <= 48 && readable.height <= 48) {
            readable
        } else {
            Bitmap.createScaledBitmap(readable, 48, 48, true).also { scaled = it }
        }
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        BrandMarkSample(
            treatment = brandMarkTreatment(pixels, small.width, small.height),
            ink = brandMarkInk(pixels, small.width, small.height),
            imageWidth = readable.width,
            imageHeight = readable.height
        )
    } catch (_: Exception) {
        // Undecodable/protected bitmap — leave the logo untouched.
        BrandMarkSample(BrandMark.AS_IS, null, 0, 0)
    } finally {
        runCatching { scaled?.recycle() }
        runCatching { softwareCopy?.recycle() }
    }
}
