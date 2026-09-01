package com.kennyb1201.kbstream.data.youtube

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient

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

        /**
         * 1 MB chunks. Verified empirically against googlevideo (device DIAG
         * logs): these signed URLs return 403 for range requests of 10 MB (and
         * for open-ended requests), but 206 for ranges up to 1 MB. Larger
         * chunks avoid request churn on other hosts, but here they get a hard
         * 403 — so the chunk stays well inside the proven-safe size.
         */
        private const val CHUNK_SIZE = 1L * 1024 * 1024

        /** Must match the android_vr client UA used to request the stream. */
        private const val YOUTUBE_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.56.21 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip"
    }

    override fun createDataSource(): DataSource {
        // googlevideo rejects the same signed URL differently depending on the
        // HTTP stack: the OkHttp-based reachability probe (Range bytes=0-0)
        // succeeds, while the HttpURLConnection-based DefaultHttpDataSource
        // gets a hard 403 on every mode (missing gzip Accept-Encoding, HTTP/1.1
        // quirks, etc.). So playback must use OkHttp too - matching the probe's
        // stack is what makes the signed URL actually play.
        val upstream = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        )
            .setUserAgent(YOUTUBE_USER_AGENT)
            .createDataSource()
        return YoutubeChunkedDataSource(upstream, chunkSizeBytes)
    }

    private class YoutubeChunkedDataSource(
        private val upstream: HttpDataSource,
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
        private var originalUrlString: String? = null

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
            originalUrlString = dataSpec.uri.toString()
            Log.d(TAG, "Opening YT stream: host=$host url=${originalUrlString?.take(160)}")
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

            // Order proven by device bisection (see runDiagnostics): the clean
            // signed URL with a capped <=1MB range serves ONLY at offset 0;
            // capped ranges at later offsets return 403. Mid-stream the server
            // still honours an open-ended range from the current position, so
            // try clean-bounded first (offset 0 case), then clean open-ended,
            // then ratebypass variants as a last resort (mutating the URL can
            // corrupt the signature).
            val attempts = buildList {
                add(false to chunkedRange)
                if (chunkedRange) add(false to false)
                add(true to chunkedRange)
                if (chunkedRange) add(true to false)
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

            // Every mode 403'd with the media3 stack. Run a raw-OkHttp bisection
            // against the untouched signed URL (same as the extractor's probe that
            // PASSED) so we can see exactly what googlevideo rejects and why.
            runDiagnostics(originalUrlString ?: spec.uri.toString())

            throw last403
                ?: IllegalStateException("Failed to open YouTube stream (no mode succeeded)")
        }

        /**
         * Debug-only: replays the failing URL through a plain OkHttpClient with
         * different Range styles and logs each response code + body. Device
         * bisection so far: capped ranges succeed at offset 0 (0-0, 0-1M) but
         * 403 at 10M span and at later offsets. These probes add the missing
         * offset cases: open-ended from 0 and from the current chunk position.
         */
        private fun runDiagnostics(url: String) {
            runCatching {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val pos = currentChunkStart
                val probes = listOf(
                    "plain-get" to null,
                    "range-0-0" to "bytes=0-0",
                    "range-0-1M" to "bytes=0-1048575",
                    "range-0-10M" to "bytes=0-10485759",
                    "range-0-openpos" to "bytes=0-$pos",
                    "range-pos-open" to "bytes=$pos-"
                )
                for ((label, range) in probes) {
                    val rb = okhttp3.Request.Builder().url(url).get()
                    rb.header("User-Agent", YOUTUBE_USER_AGENT)
                    if (range != null) rb.header("Range", range)
                    client.newCall(rb.build()).execute().use { resp ->
                        val body = resp.peekBody(400).string().take(400)
                        val loc = resp.header("Location")
                        Log.w(
                            TAG,
                            "DIAG[$label] code=${resp.code} msg=${resp.message} loc=$loc body=$body"
                        )
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "DIAG failed: ${e.message}")
            }
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

                // Never mask a failed mid-stream open as end-of-input: that
                // silently truncates playback. Surface the real cause.
                openNextChunk()
                return upstream.read(buffer, offset, length)
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