package com.kennyb1201.kbstream.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeService

/**
 * NewPipe-based YouTube stream extraction for KBStream.
 *
 * This class contains no UI or ExoPlayer code.
 */
object NewPipeExtractor {

    private var initialized = false

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return

        NewPipe.init(KBStreamDownloader())

        initialized = true
    }

    suspend fun extractVideoStream(
        videoId: String
    ): Result<StreamInfo> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInitialized()

            val service: StreamingService =
                NewPipe.getServiceByName("YouTube")

            val url = "https://www.youtube.com/watch?v=$videoId"

            StreamInfo.getInfo(
                service,
                url
            )
        }
    }
}
