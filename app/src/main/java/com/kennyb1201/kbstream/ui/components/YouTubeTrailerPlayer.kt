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
 * Plays a YouTube trailer inside KBStream.
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
                    javaScriptCanOpenWindowsAutomatically = true
                    allowFileAccess = false
                    allowContentAccess = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                isFocusable = false
                isFocusableInTouchMode = false
            }
        },
        update = { webView ->

            if (webView.tag != videoId) {

                val embedUrl =
                    "https://www.youtube.com/embed/$videoId" +
                        "?autoplay=1" +
                        "&playsinline=1" +
                        "&controls=0" +
                        "&rel=0" +
                        "&iv_load_policy=3" +
                        "&modestbranding=1"

                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport"
                              content="width=device-width,
                                       initial-scale=1.0,
                                       maximum-scale=1.0,
                                       user-scalable=no">
                        <style>
                            html, body {
                                margin: 0;
                                padding: 0;
                                width: 100%;
                                height: 100%;
                                overflow: hidden;
                                background: #000;
                            }

                            iframe {
                                position: absolute;
                                top: 0;
                                left: 0;
                                width: 100%;
                                height: 100%;
                                border: 0;
                            }
                        </style>
                    </head>
                    <body>
                        <iframe
                            src="$embedUrl"
                            allow="autoplay; encrypted-media; fullscreen"
                            allowfullscreen>
                        </iframe>
                    </body>
                    </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(
                    "https://kennyb1201.github.io/KBStream/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )

                webView.tag = videoId
            }
        }
    )
}
