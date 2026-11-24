package com.kennyb1201.kbstream.data.cast

import android.content.Context

/**
 * The launch decision: does this Play press go to the television instead of
 * opening a player here?
 *
 * Routing, not a player engine. Cast sits AHEAD of MainActivity's engine `when`
 * (see the launch effect): a receiver that is already connected means the
 * television is where the viewer is watching, so the launch loads there and no
 * local activity opens at all. Everything else about the launch is unchanged -
 * the source was picked, the resume point is known, and the history row belongs
 * to the same title either way.
 *
 * Three answers, in the order the app tries them:
 *
 *  - [CastRules.castVerdict] says no: this launch cannot be cast (a kids
 *    profile, DRM, a header-gated or address-bound link). The caller falls
 *    through to its engines, and a phone WITH a receiver connected still plays
 *    locally - the honest alternative to a black screen on the TV.
 *  - the verdict says yes but there is no live session: nothing to load onto,
 *    so again the caller falls through. Being connected is the viewer's own
 *    statement that they want the TV.
 *  - yes, and the load took: [CastWatchSession] starts, and the launch is done.
 */
internal object CastLauncher {

    /**
     * Casts the launch if it can be, returning whether it was taken.
     *
     * [seed] is what the history row is built from - the same identity the
     * in-app players would file, so a cast and a local playback of one episode
     * cannot make two Continue Watching cards.
     */
    fun castIfCasting(
        context: Context,
        url: String?,
        headers: Map<String, String>?,
        drmProtected: Boolean,
        title: String,
        subtitle: String?,
        posterUrl: String?,
        startPositionMs: Long,
        phone: Boolean,
        kidsProfile: Boolean,
        seed: CastWatchSession.Seed
    ): Boolean {
        val verdict = castVerdict(
            url = url,
            headers = headers,
            drmProtected = drmProtected,
            // A launch with no direct link IS the torrent case by the time it
            // reaches here: the picker only launches a resolved URL, so a blank
            // one is a stream that never came back with a playable link.
            torrentOnly = url.isNullOrBlank(),
            title = title,
            subtitle = subtitle,
            posterUrl = posterUrl,
            startPositionMs = startPositionMs,
            phone = phone,
            kidsProfile = kidsProfile
        )
        val plan = (verdict as? CastVerdict.Castable)?.plan ?: return false
        if (!CastSender.isSessionLive(context)) return false
        if (!CastSender.load(context, plan)) return false
        CastWatchSession.start(context, seed)
        return true
    }
}
