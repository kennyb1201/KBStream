package com.kennyb1201.kbstream.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo

object NewPipeManager {

    private const val TAG = "NewPipeManager"

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return

        NewPipe.init(
            KBStreamDownloader.getInstance()
        )

        initialized = true
    }

    suspend fun getStreamInfo(
        videoId: String
    ): Result<StreamInfo> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInitialized()

            Log.d(
                TAG,
                "Extracting YouTube video: $videoId"
            )

            StreamInfo.getInfo(
                "https://www.youtube.com/watch?v=$videoId"
            )
        }.onFailure { error ->
            Log.e(
                TAG,
                "Failed to extract YouTube video: $videoId",
                error
            )
        }
    }

    suspend fun getPlayableUrl(
        videoId: String
    ): Result<String> = withContext(Dispatchers.IO) {

        getStreamInfo(videoId).mapCatching { info ->

            Log.d(
                TAG,
                "YouTube streams: " +
                    "video=${info.videoStreams.size}, " +
                    "videoOnly=${info.videoOnlyStreams.size}, " +
                    "audio=${info.audioStreams.size}"
            )

            /*
             * Prefer progressive streams because they already
             * contain both video and audio and can be passed
             * directly to Media3.
             */
            val progressive =
                info.videoStreams
                    .filter { stream ->
                        !stream.isVideoOnly
                    }
                    .filter { stream ->
                        val format =
                            stream.format
                                ?.name
                                ?.lowercase()
                                .orEmpty()

                        format.contains("mp4") ||
                            format.contains("webm")
                    }
                    .sortedByDescending { stream ->
                        stream.height
                    }
                    .firstOrNull()

            if (progressive != null) {
                Log.d(
                    TAG,
                    "Using progressive stream: " +
                        "format=${progressive.format}, " +
                        "height=${progressive.height}, " +
                        "url=${progressive.content.take(120)}"
                )

                return@mapCatching progressive.content
            }

            /*
             * NewPipe/YouTube may not expose a progressive stream.
             *
             * Media3 cannot directly play separate video/audio
             * URLs as one normal MediaItem, so fail explicitly
             * rather than silently doing nothing.
             */
            throw IllegalStateException(
                "No playable progressive YouTube stream found. " +
                    "video=${info.videoStreams.size}, " +
                    "videoOnly=${info.videoOnlyStreams.size}, " +
                    "audio=${info.audioStreams.size}"
            )
        }.onFailure { error ->
            Log.e(
                TAG,
                "Could not get playable URL for $videoId",
                error
            )
        }
    }
}
