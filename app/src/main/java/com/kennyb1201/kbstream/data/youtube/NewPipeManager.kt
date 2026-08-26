package com.kennyb1201.kbstream.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo

object NewPipeManager {

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

            StreamInfo.getInfo(
                "https://www.youtube.com/watch?v=$videoId"
            )
        }
    }

    suspend fun getPlayableUrl(
        videoId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        getStreamInfo(videoId).mapCatching { info ->

            /*
             * Prefer progressive streams because they contain
             * both video and audio and can be handed directly
             * to the existing Media3 PlayerActivity.
             */
            val progressive =
                info.videoStreams
                    .filter { stream ->
                        stream.isVideoOnly.not()
                    }
                    .filter { stream ->
    val format =
        stream.format?.name
            ?.lowercase()
            .orEmpty()

    format.contains("mp4") ||
        format.contains("webm")
}
                    .sortedByDescending { stream ->
                        stream.height
                    }
                    .firstOrNull()

            progressive?.content
                ?: throw IllegalStateException(
                    "No playable progressive YouTube stream found"
                )
        }
    }
}
