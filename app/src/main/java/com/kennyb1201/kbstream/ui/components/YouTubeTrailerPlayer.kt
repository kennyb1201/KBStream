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
            val embedUrl =
                "https://www.youtube.com/embed/$videoId" +
                    "?autoplay=1" +
                    "&playsinline=1" +
                    "&controls=0" +
                    "&rel=0" +
                    "&modestbranding=1" +
                    "&iv_load_policy=3"

            val current = webView.url.orEmpty()
            if (!current.contains("/$videoId?")) {
                webView.loadUrl(embedUrl)
            }
        }
    )
}
