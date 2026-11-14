package com.kennyb1201.kbstream.data.mal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * The two links `kbstream://oauth/mal` carries (spec 2026-11-13, F1).
 *
 * MAL has no device flow, so the connect is a browser round trip: the app opens
 * MAL's authorize page and MAL sends the viewer back to this scheme with a
 * one-time code. Two shapes land on the scheme:
 *
 *  - `kbstream://oauth/mal?code=…&state=…` - the callback, exchanged here.
 *  - `kbstream://oauth/mal?connect=1` - "start connecting on this device". It is
 *    what a TV shows as a QR code: the TV has no usable browser for this flow,
 *    and MAL has no device flow to hand to it, so the PHONE runs the connect and
 *    the synced session is what reaches the TV. Scanning the code opens the
 *    phone app, which starts its own flow and stores the tokens.
 *
 * Nothing here trusts the link's contents: the state is checked against the one
 * this profile generated (see [malRedirectOutcome]), because any app on the
 * device can send this scheme.
 */
internal object MalLinks {

    private const val TAG = "MalLinks"

    /** True when [rawUri] is the MAL callback (a code or a refusal). */
    fun isCallback(rawUri: String?): Boolean {
        val uri = rawUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        if (!uri.scheme.equals("kbstream", ignoreCase = true)) return false
        if (!uri.host.equals("oauth", ignoreCase = true)) return false
        if (!uri.path.orEmpty().trim('/').equals("mal", ignoreCase = true)) return false
        return uri.getQueryParameter("code") != null ||
            uri.getQueryParameter("error") != null
    }

    /** True when [rawUri] asks this device to START a connect. */
    fun isStart(rawUri: String?): Boolean {
        val uri = rawUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        if (!uri.scheme.equals("kbstream", ignoreCase = true)) return false
        if (!uri.host.equals("oauth", ignoreCase = true)) return false
        if (!uri.path.orEmpty().trim('/').equals("mal", ignoreCase = true)) return false
        return uri.getQueryParameter("connect") != null
    }

    /** The link a QR code carries when the viewer should connect from a phone. */
    fun startLink(): String = MAL_REDIRECT_URI + "?connect=1"

    /**
     * Opens MAL's authorize page in whatever handles https on this device.
     *
     * Returns WHAT HAPPENED rather than a Boolean (spec 2026-11-14, F2a). The two
     * failures are different problems with different sentences - a build with no
     * app id cannot start the flow at all, a device with no browser can - and the
     * old `false` for both is why a scanned QR opened KBStream and then sat there
     * silently. [MalStartOutcome] is also what the dialog's own button reads: a
     * phone with no browser falls back to the QR, a phone in an unconfigured
     * build does not pretend a QR would help.
     */
    fun openAuthorize(context: Context): MalStartOutcome {
        val url = MalAuth.beginConnect(context) ?: return MalStartOutcome.NotConfigured

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            MalStartOutcome.Opened
        }.getOrElse { error ->
            Log.i(TAG, "no browser for the MAL authorize page: ${error.javaClass.simpleName}")
            MalStartOutcome.NoBrowser
        }
    }
}
