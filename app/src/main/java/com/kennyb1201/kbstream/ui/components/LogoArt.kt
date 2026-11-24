package com.kennyb1201.kbstream.ui.components

import android.content.Context
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
 * A logo request that says out loud whether it loaded (spec 2026-11-23, F2).
 *
 * A dead logo URL and a title block that was simply never painted looked
 * identical in a field paste: both were "no logo on screen" with no `LOGO:`
 * line after the launch one. Every logo load into a player title slot goes
 * through here now, so one line names the surface and the outcome - and on a
 * failure [onFailure] runs, which is how the chrome restores its text instead
 * of leaving an empty slot (F3).
 *
 * A CANCELLED load is a third outcome, and until 2026-11-24 it was the silent
 * one: a second `ImageView.load()` into the same slot cancels the request that
 * is already on its way, so setup painting one surface three times back to back
 * threw away every fetch but the last and the paste showed none of it (spec
 * 2026-11-24, F2). It is traced here, where every player title slot loads, so a
 * cancellation can never again be indistinguishable from art that never
 * arrived.
 *
 * [onLoaded] lets an owner that reuses one slot remember which URL actually
 * landed, which is what makes a later repaint of the same facts bookkeeping
 * instead of a fresh fetch (F3).
 *
 * Coil's own error travels as its class name: one line, no stack, no URL.
 */
internal fun tracedLogoRequest(
    context: Context,
    url: String?,
    surface: String,
    onLoaded: (() -> Unit)? = null,
    onFailure: (() -> Unit)? = null
): ImageRequest =
    logoRequest(context, url).newBuilder().listener(
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
    ).build()

/** One token for a Coil failure: the throwable's own name, never its message. */
internal fun logoLoadFailureReason(throwable: Throwable): String =
    throwable.javaClass.simpleName.takeIf { it.isNotBlank() } ?: "unknown"
