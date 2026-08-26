package com.kennyb1201.kbstream.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
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

            val service: StreamingService =
                NewPipe.getServiceByName("YouTube")

            val url =
                "https://www.youtube.com/watch?v=$videoId"

            StreamInfo.getInfo(
                service,
                url
            )
        }
    }
}
