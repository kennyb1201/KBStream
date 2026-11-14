package com.kennyb1201.kbstream.data.anime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * The `kbstream://oauth/anilist` link (spec 2026-11-14, F3).
 *
 * The code-grant connect is a browser round trip, so AniList sends the viewer
 * back to this app's own scheme with a one-time code - the same shape MAL's
 * `kbstream://oauth/mal` callback has, and the reason the ordinary path pastes
 * nothing.
 *
 * Unlike MAL there is no `?connect=1` start link. MAL needs one because its QR
 * has to make the PHONE start the flow that the TV cannot run; AniList's QR
 * carries the authorize URL itself, and the phone completes it in the browser
 * (see [AniListConnectRules.redirectOutcome] for how the code finds its way back
 * on a device that never generated the state).
 *
 * Nothing here trusts the link's contents: the state is compared against the one
 * this profile generated (when it generated one), because any app on the device
 * can send this scheme.
 */
internal object AniListLinks {

    private const val TAG = "AniListLinks"

    /** True when [rawUri] is the AniList callback (a code or a refusal). */
    fun isCallback(rawUri: String?): Boolean {
        val uri = rawUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        if (!uri.scheme.equals("kbstream", ignoreCase = true)) return false
        if (!uri.host.equals("oauth", ignoreCase = true)) return false
        if (!uri.path.orEmpty().trim('/').equals("anilist", ignoreCase = true)) return false
        return uri.getQueryParameter("code") != null ||
            uri.getQueryParameter("error") != null
    }

    /**
     * Starts a connect on THIS device: the state is stored before the URL exists,
     * then the browser opens AniList's authorize page.
     *
     * Returns false only when there is nothing to open - an unconfigured build, or
     * a device with no browser. The dialog says so and falls back to the link and
     * QR, so the viewer never presses a button that does nothing.
     */
    fun openAuthorize(context: Context): Boolean {
        val url = AniListAuth.beginConnect(context) ?: return false

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { error ->
            Log.i(TAG, "no browser for the AniList authorize page: ${error.javaClass.simpleName}")
            false
        }
    }
}
