package com.kennyb1201.kbstream.data.youtube

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * A [DataSource.Factory] for YouTube/googlevideo stream URLs.
 *
 * The DIAG proved googlevideo requires TWO things for a successful response:
 *  1. A `Range` header (without it, plain GET → 403)
 *  2. The User-Agent matching the InnerTube client that resolved the URL
 *
 * ExoPlayer's default [DefaultHttpDataSource] sends neither, so every request
 * gets 403. This factory sets both as default request properties.
 */
@OptIn(UnstableApi::class)
class YouTubeStreamDataSourceFactory : DataSource.Factory {

    /**
     * The User-Agent string that was used when InnerTube resolved the URL.
     * Must match exactly — googlevideo signs the URL for a specific client.
     */
    var userAgent: String = ANDROID_VR_USER_AGENT
        set(value) {
            field = value.ifBlank { ANDROID_VR_USER_AGENT }
        }

    override fun createDataSource(): DataSource {
        return DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(buildMap {
                put("User-Agent", userAgent)
                // The DIAG proved: no Range header → 403, bytes=0-* → 206.
                // Always request from byte 0 so ExoPlayer's continuous read
                // works. ExoPlayer handles the position tracking internally.
                put("Range", "bytes=0-")
            })
            .createDataSource()
    }

    companion object {
        /**
         * The android-vr client User-Agent from the InnerTube extractor.
         * googlevideo signs URLs for this client, so playback must use the
         * same UA.
         */
        const val ANDROID_VR_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.56.21 " +
                "(Linux; U; Android 12; en_US; Quest 3; " +
                "Build/SQ3A.220605.009.A1) gzip"
    }
}
