package com.kennyb1201.kbstream.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.Image
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware

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
 */
@Composable
internal fun BrandMarkImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onUnusable: () -> Unit = {}
) {
    val context = LocalContext.current
    var treatment by remember(url) { mutableStateOf(BrandMark.AS_IS) }

    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            // Force a software bitmap so pixels can be sampled for luminance.
            .allowHardware(false)
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
            val sampled = sampleBrandMark(state.result.image)
            if (sampled == BrandMark.UNUSABLE) onUnusable() else treatment = sampled
        },
        // A logo that never arrives must not hold its slot open either.
        onError = { onUnusable() },
        modifier = modifier
    )
}

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
 * Samples a decoded logo down to one 48x48 tile and hands the pixels to
 * [brandMarkTreatment], which owns the decision (and is unit-tested there).
 *
 * Deliberately coarse because this runs once per logo, and it must stay off
 * the hardware path so the pixels can be read at all.
 */
internal fun sampleBrandMark(image: Image): BrandMark {
    return try {
        val src = (image as? coil3.BitmapImage)?.bitmap ?: return BrandMark.AS_IS
        val small = if (src.width <= 48 && src.height <= 48) {
            src
        } else {
            Bitmap.createScaledBitmap(src, 48, 48, true)
        }
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        brandMarkTreatment(pixels, small.width, small.height)
    } catch (_: Exception) {
        // Undecodable/protected bitmap — leave the logo untouched.
        BrandMark.AS_IS
    }
}
