package com.kennyb1201.kbstream.data.youtube

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * How deep the cause chain is walked before giving up. Media3 buries the HTTP
 * failure under its own data-source, extractor and player exceptions; four or
 * five hops is the normal shape, and the bound keeps a cyclic chain from
 * hanging the handler.
 */
private const val MAX_CAUSE_HOPS = 8

private const val HTTP_FORBIDDEN = 403

/**
 * True when this player failure is YouTube refusing the client identity.
 *
 * The distinction matters because the two 403 families look identical to the
 * player and only one of them is worth acting on:
 *
 *  - a refused signed URL is recovered INSIDE the playback ladder
 *    ([YoutubeChunkedDataSourceFactory] cycles every client UA × ratebypass ×
 *    bounded-range mode and re-signs), so a 403 that reaches the player means
 *    every mode was refused;
 *  - that is not a mode mismatch. It is the watch config (visitor data + API
 *    key, cached for 3h) being refused, and re-resolving with the SAME identity
 *    only re-signs a URL YouTube has already said no to. The caller therefore
 *    invalidates the cached config before its retry, so the retry resolves with
 *    a genuinely fresh visitor.
 *
 * The googlevideo check is what keeps an addon/debrid 403 out of this: a
 * refused debrid link says nothing about YouTube's view of this device, and
 * throwing the watch config away for it would only cost the next resolve a
 * watch-page fetch.
 */
fun PlaybackException.isYouTubeIdentityBlocked(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return false

    var cause: Throwable? = this
    var hops = 0
    while (cause != null && hops < MAX_CAUSE_HOPS) {
        if (cause is HttpDataSource.InvalidResponseCodeException &&
            cause.responseCode == HTTP_FORBIDDEN
        ) {
            return isYouTubeMediaUrl(cause.dataSpec.uri.toString())
        }
        cause = cause.cause
        hops++
    }
    return false
}

/**
 * True for a URL served by YouTube's media CDN (googlevideo), where a 403 is
 * the player API's verdict on the signed URL rather than another host's own
 * refusal. The `com`/`co`/`googlevideo.com` forms all resolve here, and a
 * lookalike host (googlevideo.com.evil.example) does not.
 */
private fun isYouTubeMediaUrl(url: String): Boolean {
    val host = runCatching {
        android.net.Uri.parse(url).host
    }.getOrNull()?.lowercase() ?: return false

    return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
}
