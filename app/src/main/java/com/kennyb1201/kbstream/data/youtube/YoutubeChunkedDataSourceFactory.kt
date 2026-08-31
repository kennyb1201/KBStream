package com.kennyb1201.kbstream.data.youtube

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * DataSource.Factory that wraps DefaultHttpDataSource and downloads YouTube
 * googlevideo streams in ~10 MB chunks, reopening a fresh connection per
 * chunk. YouTube throttles (and kills) connections that try to download a
 * whole adaptive stream in one shot, but honours bounded range requests.
 *
 * The signed googlevideo URL is left byte-for-byte untouched — appending any
 * query parameter (e.g. `&range=...`) invalidates the URL signature and gets
 * a 403. Instead each chunk is requested via the HTTP `Range` header, which
 * DefaultHttpDataSource derives from DataSpec.position/length.
 *
 * Only activates for googlevideo.com URLs; all other URLs pass through
 * untouched.
 */
@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val chunkSizeBytes: Long = CHUNK_SIZE
) : DataSource.Factory {

    companion object {
        private const val TAG = "YTChunkedDS"

        /** 10 MB chunks – large enough to avoid too many requests, small enough to dodge throttle. */
        private const val CHUNK_SIZE = 10L * 1024 * 1024
    }

    override fun createDataSource(): DataSource {
        val upstream = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        return YoutubeChunkedDataSource(upstream, chunkSizeBytes)
    }

    private class YoutubeChunkedDataSource(
        private val upstream: DefaultHttpDataSource,
        private val chunkSize: Long
    ) : DataSource {

        private var isYouTubeStream = false
        private var totalContentLength = C.LENGTH_UNSET.toLong()
        private var currentChunkStart = 0L
        private var currentChunkEnd = 0L
        private var bytesReadInChunk = 0L
        private var originalDataSpec: DataSpec? = null

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val host = dataSpec.uri.host.orEmpty()
            isYouTubeStream = host.contains("googlevideo.com")
            if (!isYouTubeStream) {
                return upstream.open(dataSpec)
            }
            originalDataSpec = dataSpec
            currentChunkStart = dataSpec.position
            totalContentLength = dataSpec.length
            return openNextChunk()
        }

        private fun openNextChunk(): Long {
            val spec = originalDataSpec ?: throw IllegalStateException("No DataSpec")
            val end = if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                minOf(currentChunkStart + chunkSize - 1, currentChunkStart + totalContentLength - 1)
            } else {
                currentChunkStart + chunkSize - 1
            }
            currentChunkEnd = end

            // URL stays untouched (signature). The Range header comes from
            // position/length: bounded to the chunk when the total is known,
            // otherwise open-ended.
            val chunkedSpec = spec.buildUpon()
                .setPosition(currentChunkStart)
                .setLength(
                    if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                        currentChunkEnd - currentChunkStart + 1
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
                )
                .build()

            bytesReadInChunk = 0
            upstream.open(chunkedSpec)
            return if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                totalContentLength
            } else {
                C.LENGTH_UNSET.toLong()
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isYouTubeStream) {
                return upstream.read(buffer, offset, length)
            }

            val bytesRead = upstream.read(buffer, offset, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                // Current chunk exhausted — open the next one
                val chunkBytesReceived = bytesReadInChunk
                upstream.close()

                // If this chunk returned fewer bytes than requested, the stream is done
                if (chunkBytesReceived < (currentChunkEnd - currentChunkStart + 1)) {
                    return C.RESULT_END_OF_INPUT
                }

                currentChunkStart += chunkBytesReceived
                if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                    totalContentLength -= chunkBytesReceived
                    if (totalContentLength <= 0) {
                        return C.RESULT_END_OF_INPUT
                    }
                }

                return try {
                    openNextChunk()
                    upstream.read(buffer, offset, length)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open next chunk at $currentChunkStart: ${e.message}")
                    C.RESULT_END_OF_INPUT
                }
            }

            bytesReadInChunk += bytesRead
            return bytesRead
        }

        override fun getUri(): Uri? = upstream.uri

        override fun close() {
            upstream.close()
            originalDataSpec = null
        }
    }
}
