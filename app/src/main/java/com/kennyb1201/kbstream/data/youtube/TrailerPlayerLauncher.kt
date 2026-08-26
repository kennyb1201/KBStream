package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kennyb1201.kbstream.ui.player.PlayerActivity

object TrailerPlayerLauncher {

    suspend fun playTrailer(
        context: Context,
        trailerUrlOrId: String
    ) {
        val videoId = extractVideoId(trailerUrlOrId)
            ?: return

        val playableUrl = NewPipeManager
            .getPlayableUrl(videoId)
            .getOrNull()
            ?: return

        val intent = Intent(
            context,
            PlayerActivity::class.java
        ).apply {
            putExtra("stream_url", playableUrl)
        }

        context.startActivity(intent)
    }

    private fun extractVideoId(
        value: String
    ): String? {
        val input = value.trim()

        if (input.isBlank()) {
            return null
        }

        // Already a YouTube video ID
        if (
            !input.contains("/") &&
            !input.contains("?") &&
            !input.contains("&") &&
            !input.contains("=") &&
            input.length in 8..20
        ) {
            return input
        }

        return runCatching {
            val uri = Uri.parse(input)
            val host = uri.host?.lowercase().orEmpty()

            when {
                host == "youtu.be" ||
                    host.endsWith(".youtu.be") -> {
                    uri.pathSegments.firstOrNull()
                }

                host == "youtube.com" ||
                    host.endsWith(".youtube.com") -> {
                    uri.getQueryParameter("v")
                        ?: when {
                            uri.pathSegments.firstOrNull() == "embed" ->
                                uri.pathSegments.getOrNull(1)

                            uri.pathSegments.firstOrNull() == "shorts" ->
                                uri.pathSegments.getOrNull(1)

                            else -> null
                        }
                }

                else -> null
            }
        }.getOrNull()
    }
}
