package com.kennyb1201.kbstream.data.youtube

import android.net.Uri

/**
 * Whether this URL is served by YouTube's media CDN - the only host that
 * understands `pot`. A lookalike host (googlevideo.com.evil.example) does not.
 */
internal fun isGooglevideoUrl(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
}

/**
 * The stream URL a GVS token rides: `pot=<token>` appended to the signed URL.
 *
 * Deliberately an append, not `Uri.Builder.appendQueryParameter`: the signature
 * params (`sig`, `lsig`, `n`) are part of the URL byte-for-byte, and rebuilding
 * the query re-encodes them into a URL googlevideo 403s. Appending leaves every
 * existing parameter exactly as the player response produced it.
 *
 * Nothing is touched for: no token, a non-googlevideo host, or a URL that
 * already carries `pot` (exactly once). A fragment, should one ever appear, is
 * kept last.
 */
internal fun potDecorated(url: String, token: String?): String {
    if (token.isNullOrBlank() || url.isBlank()) return url
    if (!isGooglevideoUrl(url)) return url
    if (!runCatching { Uri.parse(url).getQueryParameter("pot") }.getOrNull().isNullOrBlank()) {
        return url
    }

    val fragmentAt = url.indexOf('#')
    val base = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
    val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
    val separator = if (base.contains('?')) '&' else '?'
    return base + separator + "pot=" + Uri.encode(token) + fragment
}

/** Whether the resolved source's URLs actually carry a `pot` parameter. */
internal fun PlayableSource.carriesPot(): Boolean = when (this) {
    is PlayableSource.Muxed -> hasPot(url)
    is PlayableSource.Adaptive -> hasPot(videoUrl) && hasPot(audioUrl)
}

private fun hasPot(url: String): Boolean =
    !runCatching { Uri.parse(url).getQueryParameter("pot") }.getOrNull().isNullOrBlank()

/**
 * The same decoration for every URL a [PlayableSource] carries: the manifest for
 * HLS, both halves of the adaptive pair (each is its own signed googlevideo URL),
 * and the muxed progressive stream. HLS segment URLs are server-issued from the
 * manifest fetch, so the manifest is the only place the token goes.
 *
 * A null token returns the source untouched, so the tokenless path is
 * byte-identical to pre-spec behavior.
 */
internal fun PlayableSource.withPotToken(token: String?): PlayableSource {
    if (token.isNullOrBlank()) return this
    return when (this) {
        is PlayableSource.Muxed -> copy(url = potDecorated(url, token))
        is PlayableSource.Adaptive -> copy(
            videoUrl = potDecorated(videoUrl, token),
            audioUrl = potDecorated(audioUrl, token)
        )
    }
}
