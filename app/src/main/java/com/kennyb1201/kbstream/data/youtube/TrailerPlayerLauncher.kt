package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.kennyb1201.kbstream.ui.player.PlayerActivity

object TrailerPlayerLauncher {

    private const val TAG = "TrailerLauncher"

    suspend fun playTrailer(
        context: Context,
        trailerUrlOrId: String
    ) {
        Log.d(
            TAG,
            "playTrailer called: $trailerUrlOrId"
        )

        val videoId =
            extractVideoId(trailerUrlOrId)

        if (videoId == null) {
            Log.e(
                TAG,
                "Could not extract YouTube video ID: " +
                    trailerUrlOrId
            )
            return
        }

        Log.d(
            TAG,
            "Extracted YouTube video ID: $videoId"
        )

        val result =
            NewPipeManager.getPlayableUrl(
                videoId
            )

        val playableUrl =
            result.getOrElse { error ->
                Log.e(
                    TAG,
                    "Failed to resolve playable trailer URL",
                    error
                )
                return
            }

        Log.d(
            TAG,
            "Launching PlayerActivity with URL: " +
                playableUrl.take(200)
        )

        val intent =
            Intent(
                context,
                PlayerActivity::class.java
            ).apply {
                putExtra(
                    "stream_url",
                    playableUrl
                )

                putExtra(
                    "parent_type",
                    "movie"
                )

                putExtra(
                    "item_name",
                    "Trailer"
                )
            }

        context.startActivity(intent)
    }

    private fun extractVideoId(
        value: String
    ): String? {
        val input =
            value.trim()

        if (input.isBlank()) {
            return null
        }

        /*
         * Already a YouTube video ID.
         */
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

            val uri =
                Uri.parse(input)

            val host =
                uri.host
                    ?.lowercase()
                    .orEmpty()

            when {

                host == "youtu.be" ||
                    host.endsWith(".youtu.be") -> {

                    uri.pathSegments
                        .firstOrNull()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                }

                host == "youtube.com" ||
                    host.endsWith(".youtube.com") -> {

                    uri.getQueryParameter("v")
                        ?: when (
                            uri.pathSegments
                                .firstOrNull()
                                ?.lowercase()
                        ) {

                            "embed" ->
                                uri.pathSegments
                                    .getOrNull(1)

                            "shorts" ->
                                uri.pathSegments
                                    .getOrNull(1)

                            "live" ->
                                uri.pathSegments
                                    .getOrNull(1)

                            else ->
                                null
                        }
                }

                else ->
                    null
            }
        }.getOrNull()
    }
}
