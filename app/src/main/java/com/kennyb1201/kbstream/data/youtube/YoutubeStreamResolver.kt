package com.kennyb1201.kbstream.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.VideoStream

object YoutubeStreamResolver {

    suspend fun resolveMuxedStreamUrl(
        videoId: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"

            val extractor = ServiceList.YouTube
                .getStreamExtractor(url)

            extractor.fetchPage()

            val videoStreams: List<VideoStream> =
                extractor.videoStreams

            videoStreams
                .filter { it.isVideoOnly.not() }
                .maxByOrNull { it.height }
                ?.url
        } catch (e: Exception) {
            null
        }
    }
}
