package com.kennyb1201.kbstream.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Plays a YouTube trailer inside KBStream using YouTube's supported embed player.
 * It never launches the YouTube app or an external browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeTrailerPlayer(
    videoId: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }

                isFocusable = false
                isFocusableInTouchMode = false
            }
        },
        update = { webView ->
            // FIXED (YouTube error 153): calling webView.loadUrl() on the
            // raw https://www.youtube.com/embed/{id} URL sends no
            // Referer/Origin header. YouTube's embed player treats that as
            // an unauthorized/unknown origin and refuses to play, which
            // surfaces here as error 153 (blank black player). Wrapping the
            // embed in a minimal HTML page and loading it with
            // loadDataWithBaseURL against a real https:// origin gives
            // YouTube a legitimate referring page, which resolves it -- this
            // is the standard fix for YouTube embeds inside an Android
            // WebView.
            //
            // Tracking the loaded video id via the view's tag instead of
            // webView.url, since after loadDataWithBaseURL the reported URL
            // is the base origin, not the embed URL, so the old
            // url.contains(videoId) check would never match and the player
            // would reload on every recomposition.
            if (webView.tag != videoId) {
                val embedUrl =
                    "https://www.youtube.com/embed/$videoId" +
                        "?autoplay=1" +
                        "&playsinline=1" +
                        "&controls=0" +
                        "&rel=0" +
                        "&modestbranding=1" +
                        "&iv_load_policy=3" +
                        "&enablejsapi=1" +
                        "&origin=https://www.youtube.com"

                val html = """
                    <html>
                      <body style="margin:0;padding:0;background:#000;">
                        <iframe
                          width="100%"
                          height="100%"
                          style="position:fixed;top:0;left:0;border:0;"
                          src="$embedUrl"
                          frameborder="0"
                          allow="autoplay; encrypted-media"
                          allowfullscreen>
                        </iframe>
                      </body>
                    </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    html,
                    "text/html",
                    "utf-8",
                    null
                )

                webView.tag = videoId
            }
        }
    )
}
