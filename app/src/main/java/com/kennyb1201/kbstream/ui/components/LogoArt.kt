package com.kennyb1201.kbstream.ui.components

import android.content.Context
import android.widget.ImageView
import coil3.load
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace

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

/**
 * The player's title-slot load, issued through Coil's builder-block overload.
 *
 * Spec 2026-11-24, F2. This used to hand Coil a PRE-BUILT request
 * (`view.load(tracedLogoRequest(...))`), and the player is the only surface that
 * issues its logo loads during activity setup, before `onResume`, when its views
 * are inflated but not yet attached to a window. Every paste since that change
 * is silent: no `loaded`, no `load-failed`, no `cancelled`. The same views
 * loaded fine for months when the call was the builder block, and every other
 * surface that moved to the pre-built form loads after attachment.
 *
 * So the form that worked is the form now: Coil builds the request itself, with
 * the view as target and the view's lifecycle attached, and the 565 opt-out plus
 * the traced callbacks are applied inside the block. That covers both live
 * hypotheses - the pre-built form stalling on a detached view, and a timing
 * window the pre-built call opens - without guessing which one it was.
 *
 * The `issued` line is written HERE, at the instant the load call runs, because
 * that is the one thing the trace could not prove (F1): `LOGO: start` says the
 * launch carried a URL, not that a request was ever made. With both, the next
 * paste separates a call-site failure from Coil-level silence.
 *
 * A CANCELLED load is a third outcome, and until 2026-11-24 it was the silent
 * one: a second `load()` into the same slot cancels the request already on its
 * way. It is traced here, where every player title slot loads, so a
 * cancellation can never again be indistinguishable from art that never
 * arrived. [onLoaded] lets an owner that reuses one slot remember which URL
 * actually landed, which is what makes a later repaint of the same facts
 * bookkeeping instead of a fresh fetch.
 *
 * Coil's own error travels as its class name: one line, no stack, no URL.
 */
internal fun ImageView.loadLogo(
    url: String?,
    surface: String,
    onLoaded: (() -> Unit)? = null,
    onFailure: (() -> Unit)? = null
) {
    PlaybackSessionTrace.note("LOGO: issued $surface")
    load(url) {
        logoArt()
        listener(
            onSuccess = { _, _ ->
                PlaybackSessionTrace.note("LOGO: loaded $surface")
                onLoaded?.invoke()
            },
            onCancel = { _ ->
                PlaybackSessionTrace.note("LOGO: cancelled $surface")
            },
            onError = { _, result ->
                PlaybackSessionTrace.note(
                    "LOGO: load-failed $surface ${logoLoadFailureReason(result.throwable)}"
                )
                onFailure?.invoke()
            }
        )
    }
}

/** One token for a Coil failure: the throwable's own name, never its message. */
internal fun logoLoadFailureReason(throwable: Throwable): String =
    throwable.javaClass.simpleName.takeIf { it.isNotBlank() } ?: "unknown"
