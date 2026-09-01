package com.kennyb1201.kbstream.data.youtube

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * DataSource.Factory that downloads YouTube googlevideo streams in ~10 MB
 * chunks by reopening a fresh connection per chunk. YouTube throttles (and
 * kills) connections that try to pull a whole adaptive stream in one shot,
 * but honours bounded range requests.
 *
 * Serving an extracted InnerTube URL reliably requires:
 *  - the same YouTube client User-Agent that requested the URL (otherwise
 *    googlevideo rejects the signed URL with 403),
 *  - `ratebypass=yes` (without it googlevideo throttles or 403s),
 *  - and range vs. open-ended requests, which googlevideo does not always
 *    allow for every stream (it 403s range requests on some URLs).
 *
 * googlevideo 403s many streams when requested open-ended (no Range header)
 * while honouring bounded range requests, so we ALWAYS attempt a bounded
 * range first — even when the total content length is unknown (we chunk by
 * a fixed size and advance). To stay robust we try, in order: bounded-range +
 * ratebypass, bounded-range without ratebypass, open-ended + ratebypass,
 * then plain open-ended. All requests keep the signed URL otherwise
 * byte-for-byte intact.
 *
 * Only activates for googlevideo.com URLs; other URLs pass through untouched.
 */
@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val chunkSizeBytes: Long = CHUNK_SIZE
) : DataSource.Factory {

    companion object {
        private const val TAG = "YTChunkedDS"

        /** 10 MB chunks – large enough to avoid too many requests, small enough to dodge throttle. */
        private const val CHUNK_SIZE = 10L * 1024 * 1024

        /** Must match the android_vr client UA used to request the stream. */
        private const val YOUTUBE_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.56.21 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip"
    }

    override fun createDataSource(): DataSource {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(YOUTUBE_USER_AGENT)
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
        private var useRateByPass = true
        private var chunkedRange = true
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

        private fun buildChunkSpec(spec: DataSpec, useRateByPass: Boolean, bounded: Boolean): DataSpec {
            val uri = if (useRateByPass && spec.uri.getQueryParameter("ratebypass").isNullOrBlank()) {
                spec.uri.buildUpon().appendQueryParameter("ratebypass", "yes").build()
            } else {
                spec.uri
            }
            return spec.buildUpon()
                .setUri(uri)
                .setPosition(currentChunkStart)
                .setLength(
                    if (bounded) {
                        // Always bound by the chunk size, even when the total
                        // content length is unknown: open-ended googlevideo
                        // requests are the ones that get 403'd, while bounded
                        // range requests are honoured.
                        currentChunkEnd - currentChunkStart + 1
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
                )
                .build()
        }

        private fun openNextChunk(): Long {
            val spec = originalDataSpec ?: throw IllegalStateException("No DataSpec")
            currentChunkEnd = if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                minOf(currentChunkStart + chunkSize - 1, currentChunkStart + totalContentLength - 1)
            } else {
                currentChunkStart + chunkSize - 1
            }

            bytesReadInChunk = 0

            // Preferred mode first, then degrade on 403.
            val attempts = buildList {
                add(useRateByPass to chunkedRange)
                if (useRateByPass) add(false to chunkedRange)
                if (chunkedRange) add(useRateByPass to false)
                add(false to false)
            }.distinct()

            var last403: HttpDataSource.InvalidResponseCodeException? = null
            for ((rb, bounded) in attempts) {
                try {
                    upstream.open(buildChunkSpec(spec, rb, bounded))
                    useRateByPass = rb
                    chunkedRange = bounded
                    return if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                        totalContentLength
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    if (e.responseCode == 403) {
                        last403 = e
                        Log.w(
                            TAG,
                            "googlevideo 403 (ratebypass=$rb bounded=$bounded); trying next mode"
                        )
                        continue
                    }
                    throw e
                }
            }

            throw last403
                ?: IllegalStateException("Failed to open YouTube stream (no mode succeeded)")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isYouTubeStream || !chunkedRange) {
                // Not a YouTube stream, or googlevideo only let us start an
                // open-ended read — just stream it straight through.
                return upstream.read(buffer, offset, length)
            }

            val bytesRead = upstream.read(buffer, offset, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
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