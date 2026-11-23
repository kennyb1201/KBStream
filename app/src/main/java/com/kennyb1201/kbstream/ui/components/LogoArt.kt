package com.kennyb1201.kbstream.ui.components

import android.content.Context
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade

/**
 * The one place a logo request is allowed to be built.
 *
 * RGB_565 has no alpha channel. A transparent wordmark decoded as 565 draws its
 * transparent pixels opaque, so the art arrives as a solid block of its ink
 * colour - the Chappelle's Show hero logo on the 2026-11-21 build. Coil only
 * falls back to 8888 when it DETECTS alpha, and some PNG encodings (a palette
 * with a tRNS chunk among them) slip past that sniff, so for logo art the
 * answer is to ask for 8888 rather than trust the detection.
 *
 * Posters, backdrops and stills are opaque and keep the loader's 565 default -
 * that is where the walk's decode win comes from, and it is untouched here.
 */
internal fun ImageRequest.Builder.logoArt(): ImageRequest.Builder = allowRgb565(false)

/**
 * A logo URL as a request that cannot decode as 565.
 *
 * Plain-URL `AsyncImage(model = url)` cannot carry the opt-out, so every logo
 * draw built from a URL goes through here (or calls [logoArt] on its own
 * builder). [LogoAlphaContractTest] fails a logo draw that does neither.
 *
 * A null URL is allowed and is Coil's own "nothing to load" answer - a missing
 * logo is not art, and the caller's text fallback covers it.
 */
internal fun logoRequest(
    context: Context,
    url: String?,
    /** Crossfade in ms, for surfaces whose brand is to fade the art in. */
    crossfadeMs: Int? = null
): ImageRequest {
    val builder = ImageRequest.Builder(context).data(url).logoArt()
    if (crossfadeMs != null) builder.crossfade(crossfadeMs)
    return builder.build()
}
