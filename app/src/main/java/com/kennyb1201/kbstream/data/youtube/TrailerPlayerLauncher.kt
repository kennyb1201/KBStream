package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity

object TrailerPlayerLauncher {

    private const val TAG = "TrailerLauncher"

    /**
     * Resolves a YouTube URL/ID down to a playable stream source
     * without launching any UI.
     *
     * NewPipe is attempted first.
     * NewPipeManager automatically falls back to Piped if
     * NewPipe cannot provide a usable stream.
     */
    suspend fun resolvePlayableUrl(
        trailerUrlOrId: String
    ): Result<PlayableSource> {

        val videoId = extractVideoId(trailerUrlOrId)

        if (videoId == null) {
            Log.e(
                TAG,
                "Could not extract YouTube video ID: $trailerUrlOrId"
            )

            return Result.failure(
                IllegalArgumentException(
                    "Could not extract YouTube video ID"
                )
            )
        }

        Log.d(
            TAG,
            "Extracted YouTube video ID: $videoId"
        )

        return NewPipeManager
            .getPlayableUrl(videoId)
            .onSuccess { source ->
                when (source) {
                    is PlayableSource.Muxed -> {
                        Log.d(
                            TAG,
                            "Trailer resolved successfully: " +
                                source.url.take(200)
                        )
                    }

                    is PlayableSource.Adaptive -> {
                        Log.d(
                            TAG,
                            "Trailer resolved successfully: video=" +
                                source.videoUrl.take(200) +
                                " audio=" +
                                source.audioUrl.take(200)
                        )
                    }
                }
            }
            .onFailure { error ->
                Log.e(
                    TAG,
                    "Trailer resolution failed",
                    error
                )
            }
    }

    suspend fun playTrailer(
        context: Context,
        trailerUrlOrId: String
    ) {
        Log.d(
            TAG,
            "playTrailer called: $trailerUrlOrId"
        )

        val source =
            resolvePlayableUrl(trailerUrlOrId)
                .getOrElse { error ->
                    Log.e(
                        TAG,
                        "Failed to resolve playable trailer URL",
                        error
                    )
                    return
                }

        val intent =
            Intent(
                context,
                NativePlayerActivity::class.java
            ).apply {

                when (source) {
                    is PlayableSource.Muxed -> {
                        putExtra(
                            "stream_url",
                            source.url
                        )
                    }

                    is PlayableSource.Adaptive -> {
                        putExtra(
                            "stream_url",
                            source.videoUrl
                        )

                        putExtra(
                            "audio_url",
                            source.audioUrl
                        )
                    }
                }

                putExtra(
                    "parent_type",
                    "movie"
                )

                putExtra(
                    "item_name",
                    "Trailer"
                )
            }

        Log.d(
            TAG,
            "Launching PlayerActivity for trailer"
        )

        context.startActivity(intent)
    }

    private fun extractVideoId(
        value: String
    ): String? {

        val input = value.trim()

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

            val uri = Uri.parse(input)

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
