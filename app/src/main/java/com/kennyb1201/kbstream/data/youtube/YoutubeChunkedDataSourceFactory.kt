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
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient

/**
 * DataSource.Factory that downloads YouTube googlevideo streams in ~1 MB
 * chunks by reopening a fresh connection per chunk. YouTube throttles (and
 * kills) connections that try to pull a whole adaptive stream in one shot,
 * but honors bounded range requests.
 *
 * Serving an extracted InnerTube URL reliably requires:
 *  - the same YouTube client User-Agent that requested the URL (otherwise
 *    googlevideo rejects the signed URL with 403),
 *  - `ratebypass=yes` (without it googlevideo throttles or 403s),
 *  - and range vs. open-ended requests, which googlevideo does not always
 *    allow for every stream (it 403s range requests on some URLs).
 *
 * googlevideo 403s many streams when requested open-ended (no Range header)
 * at offset 0, while honoring bounded range requests there — so the first
 * chunk is a bounded from-0 request (even when the total content length is
 * unknown: we chunk by a fixed size and advance). Bounded ranges at later
 * offsets 403, and a from-0 window grows with the playhead until it 403s too,
 * so every later chunk instead asks for the remainder open-ended from the
 * current position. All requests keep the signed URL otherwise byte-for-byte
 * intact.
 *
 * Two failure shapes at a chunk boundary are handled HERE rather than above,
 * because the only recovery above is a full re-resolve (a fresh signed URL and
 * a re-mounted player) and a dropped connection does not deserve one:
 *
 *  - a transient failure opening or reading a chunk (timeout, reset, refused
 *    connection, DNS blip, TLS failure, 5xx) is retried in place, up to
 *    [CHUNK_OPEN_ATTEMPTS] attempts on the same request with a short backoff;
 *  - a TRUNCATED response (a from-0 body shorter than the prefix already
 *    delivered, or a 206 that serves no bytes at all) is never end-of-stream.
 *    media3 reads EOF as a clean end - no error, no retry, no death line, the
 *    hero crossfades back to the backdrop - so a truncation answered with EOF
 *    is a trailer that silently stops mid-play. Truncation is retried, and
 *    exhaustion THROWS so the hero can re-resolve.
 *
 * A 403 and a 416 keep their exact handling: 403 belongs to the identity
 * ladder above (retrying it here would hide the refusal the hero classifies),
 * and 416 is the server's own end signal.
 *
 * Only activates for googlevideo.com URLs; other URLs pass through untouched.
 */
@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val chunkSizeBytes: Long = CHUNK_SIZE,
    private val userAgentHint: String? = null
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

        /**
         * How many times one chunk may be opened: the initial attempt plus two
         * retries.
         *
         * The budget is per chunk, not per playback: a chunk that recovers is
         * done with its budget, and a chunk that serves some bytes and then
         * fails is resumed at the advanced position as a new chunk. The hero's
         * own budgets (the 8 s start grace, the 30 s rebuffer grace) bound the
         * total, so nothing wider is added here. Three attempts is the shape
         * that covers a single dropped connection without turning a dead URL
         * into a 45 s stall before the throw.
         */
        internal const val CHUNK_OPEN_ATTEMPTS = 3

        /**
         * Backoff before retry 1, doubled per attempt (250 ms, then 500 ms).
         * Total added latency is under a second, so a healthy trailer never
         * notices and a dead one does not stall the loader thread for long.
         */
        internal const val CHUNK_RETRY_BACKOFF_MS = 250L

        /**
         * googlevideo only serves a signed URL to requests carrying the
         * User-Agent of the YouTube client the URL was signed FOR. Sources
         * resolved by InnerTubeExtractor carry their client's UA via
         * [userAgentHint]; when absent (e.g. NewPipe/Piped sources), every
         * known client UA is tried in order until one is accepted.
         *
         * Derived from [YouTubeClients], never hand-copied: the ladder used to
         * be a second set of literals that could drift from the extractor's
         * table (and did - both were frozen on 2025 client versions).
         */
        val KNOWN_CLIENT_USER_AGENTS: List<String> = YouTubeClients.userAgents

        /**
         * Legacy UA for callers that don't pass a hint: the android_vr entry,
         * unchanged in intent. Hint-less sources come from the NewPipe/Piped
         * path, which has always been resolved under that identity.
         */
        internal val LEGACY_USER_AGENT: String = YouTubeClients.androidVr.userAgent

        /**
         * Extra headers (Referer etc.) every chunked googlevideo request
         * should carry. Assign directly from embedders that also apply
         * headers to their main HTTP factory (assignable — not a private
         * set — so no JVM signature clash with the property setter).
         */
        @Volatile
        var defaultRequestProperties: Map<String, String> = emptyMap()
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
            .setUserAgent(userAgentHint ?: LEGACY_USER_AGENT)
            .createDataSource()
        return YoutubeChunkedDataSource(
            upstream,
            chunkSizeBytes,
            buildList {
                if (!userAgentHint.isNullOrBlank()) add(userAgentHint)
                addAll(KNOWN_CLIENT_USER_AGENTS)
            }.distinct()
        )
    }

    /**
     * The chunk reader itself.
     *
     * `internal` rather than private so a unit test can drive it against a fake
     * [HttpDataSource]. The retry and truncation rules are only describable by
     * scripting opens (codes, byte bodies, IOExceptions), and until this seam
     * existed no test touched this class at all - which is how "a truncated
     * response is the end of the stream" survived two fixes in this file. The
     * production construction path ([createDataSource]) is unchanged.
     */
    internal class YoutubeChunkedDataSource(
        private val upstream: HttpDataSource,
        private val chunkSize: Long,
        /**
         * User-Agent candidates, best-first: the signing client's UA (from the
         * resolved source) followed by every known client UA. googlevideo
         * rejects a signed URL requested with the wrong client's UA, so the
         * ladder advances only when every mode 403s with the current one.
         */
        private val userAgentCandidates: List<String>,
        /**
         * The raw-OkHttp bisection run once every UA x mode 403s. A constructor
         * parameter only so a unit test can observe the call without touching
         * the network; production always takes [runYouTubeChunkDiagnostics].
         */
        private val diagnostics: (url: String, userAgent: String, position: Long) -> Unit =
            ::runYouTubeChunkDiagnostics
    ) : DataSource {

        private var activeUaIndex = 0
        private var isYouTubeStream = false
        private var useRateByPass = true
        private var chunkedRange = true

        /**
         * True when the active chunk was opened open-ended from the current
         * position rather than from byte 0 (see [openNextChunk]). In this
         * mode nothing is re-downloaded and [bytesToSkip] stays 0.
         */
        private var positionMode = false

        /** Set when the server reports the requested range lies past the end. */
        private var openHitEndOfStream = false
        private var totalContentLength = C.LENGTH_UNSET.toLong()
        private var currentChunkStart = 0L
        private var currentChunkEnd = 0L
        private var bytesReadInChunk = 0L
        private var bytesToSkip = 0L

        /**
         * Attempts spent on the CURRENT chunk: 1 is the initial open, capped at
         * [CHUNK_OPEN_ATTEMPTS]. Reset for every new chunk, kept across retries
         * of the same one (see [openNextChunk]'s `continuingRetry`).
         */
        private var chunkAttempt = 1

        private var originalDataSpec: DataSpec? = null
        private var originalUrlString: String? = null

        /** Small buffer used to discard the prefix of a "from-0" response. */
        private val skipBuffer = ByteArray(8192)

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
            positionMode = false
            val openedLength = openNextChunk()
            // The server reported the requested position is past the end and
            // media3 did not already read it as an empty open: hand back an
            // empty stream rather than reading from a closed connection.
            if (openHitEndOfStream) return 0L
            return openedLength
        }

        private fun buildChunkSpec(
            spec: DataSpec,
            useRateByPass: Boolean,
            bounded: Boolean,
            fromPosition: Boolean = false
        ): DataSpec {
            val uri = if (useRateByPass && spec.uri.getQueryParameter("ratebypass").isNullOrBlank()) {
                spec.uri.buildUpon().appendQueryParameter("ratebypass", "yes").build()
            } else {
                spec.uri
            }
            // A bounded request carries the whole prefix from byte 0 (see
            // openNextChunk) and read() skips what was already delivered. A
            // from-position open-ended request asks for the rest of the stream
            // starting where we are, so nothing is re-downloaded.
            //
            // The chunk request carries the ACTIVE client User-Agent. DataSpec
            // headers override the OkHttpDataSource factory default, which is
            // what lets us switch UAs at runtime when googlevideo 403s.
            return spec.buildUpon()
                .setUri(uri)
                .setPosition(if (fromPosition) currentChunkStart else 0L)
                .setLength(
                    when {
                        fromPosition ->
                            // Open-ended: media3's HTTP source skips to the
                            // requested position itself when a server ignores
                            // the Range header (200 instead of 206).
                            C.LENGTH_UNSET.toLong()
                        bounded ->
                            currentChunkEnd + 1   // total bytes from 0 to chunk end
                        else ->
                            C.LENGTH_UNSET.toLong()
                    }
                )
                .setHttpRequestHeaders(activeRequestHeaders())
                .build()
        }

        private val activeUserAgent: String
            get() = userAgentCandidates[activeUaIndex]

        /** Merges factory defaults under the per-request UA (UA wins). */
        private fun activeRequestHeaders(): Map<String, String> =
            buildMap {
                putAll(YoutubeChunkedDataSourceFactory.defaultRequestProperties)
                put("User-Agent", activeUserAgent)
            }

        /**
         * Opens the next chunk (or re-opens the current one) and returns the
         * length media3 should expect, or [C.LENGTH_UNSET].
         *
         * [continuingRetry] keeps the attempt budget of the chunk that is being
         * retried in place; every other call is a NEW chunk at a new position
         * and starts with a fresh budget.
         */
        private fun openNextChunk(continuingRetry: Boolean = false): Long {
            val spec = originalDataSpec ?: throw IllegalStateException("No DataSpec")
            if (!continuingRetry) chunkAttempt = 1
            currentChunkEnd = if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                minOf(currentChunkStart + chunkSize - 1, currentChunkStart + totalContentLength - 1)
            } else {
                currentChunkStart + chunkSize - 1
            }

            bytesReadInChunk = 0
            openHitEndOfStream = false

            // Order proven by device bisection (see runDiagnostics): the clean
            // signed URL with a capped <=1MB range serves ONLY at offset 0;
            // capped ranges at later offsets return 403. Mid-stream the server
            // still honors an open-ended range from the current position, so
            // try clean-bounded first (offset 0 case), then clean open-ended,
            // then ratebypass variants as a last resort (mutating the URL can
            // corrupt the signature).
            val baseAttempts = buildList {
                add(false to chunkedRange)
                if (chunkedRange) add(false to false)
                add(true to chunkedRange)
                if (chunkedRange) add(true to false)
            }.distinct()

            val initialUaIndex = activeUaIndex
            var last403: HttpDataSource.InvalidResponseCodeException? = null
            for (uaAttempt in activeUaIndex until userAgentCandidates.size) {
                activeUaIndex = uaAttempt
                if (uaAttempt > 0) {
                    Log.w(
                        TAG,
                        "Trying User-Agent ${uaAttempt + 1}/${userAgentCandidates.size} " +
                            "for googlevideo stream"
                    )
                }

                // Mid-stream, ask for the REST of the stream from where we are
                // before falling back to a from-0 request. A from-0 request must
                // carry the whole already-delivered prefix (googlevideo only
                // serves bounded ranges at offset 0), so its window grows with
                // the playhead and 403s once it passes ~1 MB — the mid-stream
                // failure that stopped and restarted trailers halfway through.
                // An open-ended request from the current position serves the
                // remainder with no re-download.
                if (youTubeChunkUsesPosition(currentChunkStart)) {
                    for (rb in listOf(false, true)) {
                        try {
                            openChunk(
                                buildChunkSpec(spec, rb, bounded = false, fromPosition = true),
                                mode = "position"
                            )
                            useRateByPass = rb
                            chunkedRange = false
                            positionMode = true
                            bytesToSkip = 0L
                            return C.LENGTH_UNSET.toLong()
                        } catch (e: HttpDataSource.InvalidResponseCodeException) {
                            when (e.responseCode) {
                                403 -> {
                                    last403 = e
                                    Log.w(
                                        TAG,
                                        "googlevideo 403 (pos-open ratebypass=$rb); " +
                                            "trying next mode"
                                    )
                                }
                                416 -> {
                                    // Requested range starts past the end: the
                                    // stream is already fully delivered.
                                    Log.w(
                                        TAG,
                                        "googlevideo 416 from position; end of stream"
                                    )
                                    openHitEndOfStream = true
                                    return C.LENGTH_UNSET.toLong()
                                }
                                else -> throw e
                            }
                        }
                    }
                }

                // Each new User-Agent candidate sweeps ALL four modes (a fresh
                // UA has proven nothing yet, so the remembered open-ended
                // shortcut must not narrow its ladder).
                bytesToSkip = currentChunkStart   // skip prefix we already sent
                for ((rb, bounded) in if (uaAttempt == initialUaIndex) {
                    baseAttempts
                } else {
                    listOf(
                        false to true,
                        false to false,
                        true to true,
                        true to false
                    )
                }) {
                    try {
                        openChunk(buildChunkSpec(spec, rb, bounded), mode = "from0")
                        useRateByPass = rb
                        chunkedRange = bounded
                        positionMode = false
                        bytesToSkip = currentChunkStart
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
                // Every mode 403'd with this UA. A signed URL only plays with
                // the UA of the client that requested it, so fall through to
                // the next candidate and rerun the mode ladder.
            }

            // Every mode 403'd with the media3 stack. Run a raw-OkHttp bisection
            // against the untouched signed URL (same as the extractor's probe that
            // PASSED) so we can see exactly what googlevideo rejects and why.
            //
            // Runs ONLY here: a transient retry above must not multiply these
            // six probes, which would add throttle pressure to a URL that is
            // already being refused.
            diagnostics(
                originalUrlString ?: spec.uri.toString(),
                activeUserAgent,
                currentChunkStart
            )

            throw last403
                ?: IllegalStateException("Failed to open YouTube stream (no mode succeeded)")
        }

        /**
         * Opens [spec], retrying a transient failure in place.
         *
         * Only the SAME request is retried: a dropped connection says nothing
         * about the UA x mode ladder, and walking that ladder on one bad socket
         * is how a single timeout used to cost a whole playback. 403 / 416 and
         * every other status propagate untouched - 403 has to reach the ladder
         * in [openNextChunk], and 416 is the stream's own end signal.
         */
        private fun openChunk(spec: DataSpec, mode: String) {
            while (true) {
                try {
                    upstream.open(spec)
                    return
                } catch (e: IOException) {
                    if (
                        !e.isTransientChunkFailure() ||
                        !consumeChunkAttempt(describeFailure(e), mode)
                    ) {
                        throw e
                    }
                }
            }
        }

        /**
         * Spends one attempt on the current chunk, sleeps the backoff, and logs.
         *
         * Returns false - after logging the exhaustion - when the chunk has used
         * all [CHUNK_OPEN_ATTEMPTS]. The caller then RETHROWS, so the hero does
         * what it already does with a failed playback (ordinary retry, identity
         * ladder) instead of the chunker pretending the stream ended.
         */
        private fun consumeChunkAttempt(error: String, mode: String): Boolean {
            val used = chunkAttempt
            if (used >= CHUNK_OPEN_ATTEMPTS) {
                Log.w(
                    TAG,
                    "Chunk open failed after $used attempts at offset=$currentChunkStart " +
                        "error=$error"
                )
                return false
            }
            chunkAttempt = used + 1
            Log.w(
                TAG,
                "Chunk retry $chunkAttempt/$CHUNK_OPEN_ATTEMPTS at offset=$currentChunkStart " +
                    "error=$error mode=$mode uaIndex=$activeUaIndex"
            )
            sleepBeforeChunkRetry(chunkAttempt)
            return true
        }

        /**
         * [CHUNK_RETRY_BACKOFF_MS] before retry 1, doubled for retry 2. Sleeps on
         * the loader thread (never the main one), and never swallows an
         * interrupt: a cancellation has to stop the chunk immediately.
         */
        private fun sleepBeforeChunkRetry(attempt: Int) {
            val delayMs = CHUNK_RETRY_BACKOFF_MS shl (attempt - 2)
            try {
                Thread.sleep(delayMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("chunk retry interrupted after ${delayMs}ms")
            }
        }

        /** Which shape the attached chunk was opened in, for logs and re-opens. */
        private fun chunkMode(): String = if (positionMode) "position" else "from0"

        /**
         * Re-opens the chunk after a failed read.
         *
         * Bytes already handed to ExoPlayer are never re-delivered, so they are
         * booked as delivered first ([currentChunkStart] advances, the known
         * length shrinks). A chunk that delivered NOTHING is retried in place on
         * the SAME budget; a chunk that delivered some bytes is resumed as a new
         * chunk at the advanced position with a fresh budget - progress bounds
         * that loop, and the hero's own budgets bound the total.
         *
         * Returns false when the budget is spent, and the caller rethrows the
         * original failure rather than answering EOF.
         */
        private fun reopenAfterFailedRead(error: IOException): Boolean {
            upstream.close()
            val delivered = bytesReadInChunk
            val sameChunk = delivered <= 0L
            if (!sameChunk) {
                currentChunkStart += delivered
                if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                    totalContentLength -= delivered
                }
                bytesReadInChunk = 0
            }
            if (sameChunk && !consumeChunkAttempt(describeFailure(error), chunkMode())) {
                return false
            }
            openNextChunk(continuingRetry = sameChunk)
            return true
        }

        /**
         * A from-0 response ended before the prefix already delivered was
         * consumed: truncated. Retry the same position on the same budget.
         *
         * [skipChunkPrefix] zeroed [bytesToSkip] on its false return, so the
         * skip is re-derived by [openNextChunk]'s own assignments (from-0 sets
         * it to [currentChunkStart]) rather than from the zeroed field.
         */
        private fun retryTruncatedChunk(): Boolean {
            upstream.close()
            if (!consumeChunkAttempt("truncated prefix", chunkMode())) return false
            openNextChunk(continuingRetry = true)
            return true
        }

        /** A chunk that served no bytes at all: retry the same position on the same budget. */
        private fun retryEmptyChunk(): Boolean {
            if (!consumeChunkAttempt("empty chunk", chunkMode())) return false
            openNextChunk(continuingRetry = true)
            return true
        }

        private fun truncatedPrefixFailure(): IOException =
            IOException(
                "YouTube chunk at offset $currentChunkStart ended before the delivered prefix " +
                    "after $CHUNK_OPEN_ATTEMPTS attempts"
            )

        private fun emptyChunkFailure(): IOException =
            IOException(
                "YouTube chunk at offset $currentChunkStart delivered no bytes " +
                    "after $CHUNK_OPEN_ATTEMPTS attempts"
            )

        /** `error=<class>:<code or message>` for the retry log lines. */
        private fun describeFailure(e: Throwable): String {
            val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            val head = if (code != null) "${e.javaClass.simpleName}:$code" else e.javaClass.simpleName
            val root = generateSequence(e) { it.cause }.last()
            val forLog = if (root === e) "" else " via ${root.javaClass.simpleName}"
            return "$head$forLog: ${root.message.orEmpty().take(80)}"
        }

        /** Skips [bytesToSkip] bytes of the current response; false at end of input. */
        private fun skipChunkPrefix(): Boolean {
            while (bytesToSkip > 0) {
                val toRead = minOf(bytesToSkip, skipBuffer.size.toLong()).toInt()
                val skipped = upstream.read(skipBuffer, 0, toRead)
                if (skipped == C.RESULT_END_OF_INPUT) {
                    bytesToSkip = 0
                    return false
                }
                bytesToSkip -= skipped
            }
            return true
        }

        /**
         * Reads one chunk's worth of bytes, treating a failed or truncated chunk
         * as something to RETRY rather than as the end of the stream.
         *
         * media3 reads [C.RESULT_END_OF_INPUT] as a CLEAN end: no error, no
         * retry, no death line - the hero crossfades to the backdrop and the
         * trailer silently stops where it died (the "it just goes back to
         * backdrop, same spot every time" capture). So only the server's own
         * signals may answer EOF from here:
         *  - a 416 on a position open ([openHitEndOfStream]), and
         *  - a known length fully consumed with bytes actually delivered
         *    ([youTubeChunkEndsStream]'s length arm).
         *
         * Everything else - a transient open/read failure, a body shorter than
         * the prefix already delivered, a 206 that serves nothing - retries the
         * chunk under [CHUNK_OPEN_ATTEMPTS] and then THROWS, so the hero
         * re-resolves a fresh signed URL instead of believing the trailer
         * finished.
         *
         * [youTubeChunkEndsStream]'s zero-byte arm is deliberately NOT consulted
         * here any more: an empty chunk is a truncation candidate, and this
         * layer answers it with a retry (then a throw), not with EOF. The pure
         * rule is unchanged for every other caller.
         */
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                if (openHitEndOfStream) return C.RESULT_END_OF_INPUT

                // Not a YouTube stream: pass through, no chunk retry bookkeeping.
                if (!isYouTubeStream) return upstream.read(buffer, offset, length)

                // A from-0 chunk carries the prefix already delivered to
                // ExoPlayer, so it is discarded here. A from-position chunk
                // (the mid-stream default) requests exactly where we are and
                // needs no skip, so bytesToSkip stays 0.
                //
                // This skip MUST also run against a freshly opened chunk: the
                // old code read the next chunk directly, so the first read
                // after a chunk boundary handed ExoPlayer bytes from the
                // beginning of the stream again — the corruption that stalled
                // and stopped trailers mid-play.
                val skipped =
                    try {
                        skipChunkPrefix()
                    } catch (e: IOException) {
                        // Mid-skip leaves the response in an unknown state: close
                        // it and retry the chunk, exactly like a failed read.
                        if (!e.isTransientChunkFailure() || !reopenAfterFailedRead(e)) throw e
                        continue
                    }
                if (!skipped) {
                    // The response ended before the already-delivered prefix was
                    // consumed, so the server served a SHORTER body than the
                    // prefix: truncated, not end-of-stream. (Answering EOF here
                    // is the silent mid-trailer stop this fix exists for.)
                    if (!retryTruncatedChunk()) throw truncatedPrefixFailure()
                    continue
                }

                if (!chunkedRange && !positionMode) {
                    // googlevideo only let us start an open-ended from-0 read —
                    // just stream it straight through: there is no chunk
                    // boundary here to retry.
                    return upstream.read(buffer, offset, length)
                }

                val bytesRead =
                    try {
                        upstream.read(buffer, offset, length)
                    } catch (e: IOException) {
                        if (!e.isTransientChunkFailure() || !reopenAfterFailedRead(e)) throw e
                        continue
                    }
                if (bytesRead != C.RESULT_END_OF_INPUT) {
                    bytesReadInChunk += bytesRead
                    return bytesRead
                }

                // This chunk is exhausted. A chunk that served less than was
                // asked for is NOT the end of the stream (see
                // youTubeChunkEndsStream): googlevideo truncates chunks, and
                // reading that as the end is what stopped trailers mid-play.
                val chunkBytesReceived = bytesReadInChunk
                upstream.close()

                if (chunkBytesReceived > 0L) {
                    // Length-consumed check FIRST: it is the server's own end
                    // signal and must win over any retry.
                    if (
                        youTubeChunkEndsStream(
                            bytesDeliveredInChunk = chunkBytesReceived,
                            lengthKnown = totalContentLength != C.LENGTH_UNSET.toLong(),
                            remainingContentLength = totalContentLength
                        )
                    ) {
                        return C.RESULT_END_OF_INPUT
                    }

                    currentChunkStart += chunkBytesReceived
                    if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                        totalContentLength -= chunkBytesReceived
                    }

                    // A new chunk at the new position: fresh attempt budget.
                    //
                    // A failed mid-stream open still throws out of here rather
                    // than masquerading as EOF, and it does NOT try to
                    // re-sign: this DataSource has no path back to the
                    // extractor by design (re-signing with a refused identity
                    // is useless), so the hero above owns re-resolving.
                    openNextChunk()
                    continue
                }

                // Zero bytes: no 416 and no known length says the stream is
                // over, and a 206 that serves an empty body mid-stream is a
                // truncation by the same logic as a short prefix. Retry the
                // same position; exhaustion throws (see the class comment).
                if (!retryEmptyChunk()) throw emptyChunkFailure()
                continue
            }
        }

        override fun getUri(): Uri? = upstream.uri

        override fun close() {
            upstream.close()
            originalDataSpec = null
        }
    }
}

/**
 * Whether a chunk open/read failure is worth retrying in place.
 *
 * Retryable is "the network hiccuped": a timeout, a reset or refused
 * connection, a DNS blip, a TLS failure, a 5xx from the edge, and an
 * unrecognised IOException (the budget bounds the damage, and guessing which
 * wrapper a transport failure arrived in is worse than one extra attempt).
 *
 * Everything else is a verdict, not a hiccup:
 *  - a 403 belongs to the identity ladder in [YoutubeChunkedDataSourceFactory]
 *    (retrying it here would hide the refusal the hero classifies), a 416 is
 *    the stream's own end signal, and any other 4xx means the signed URL is
 *    simply wrong;
 *  - an interrupt is a cancellation, and must never become a retry.
 *
 * The cause chain is walked because OkHttpDataSource wraps every transport
 * failure in a `HttpDataSourceException`, so the timeout arrives as a CAUSE.
 */
@UnstableApi
internal fun Throwable.isTransientChunkFailure(): Boolean {
    var node: Throwable? = this
    var depth = 0
    while (node != null && depth++ < 8) {
        when (node) {
            is HttpDataSource.InvalidResponseCodeException ->
                return node.responseCode in TRANSIENT_HTTP_CODES
            // SocketTimeoutException IS an InterruptedIOException (a read that
            // timed out); it is checked first on purpose.
            is SocketTimeoutException -> return true
            is InterruptedIOException -> return false
            is InterruptedException -> return false
            is SocketException,
            is ConnectException,
            is UnknownHostException,
            is SSLException -> return true
            else -> node = node.cause
        }
    }
    return true
}

/** Edge statuses that mean "try the same request again", never "refused". */
private val TRANSIENT_HTTP_CODES = setOf(500, 502, 503, 504)

/**
 * Debug-only: replays the failing URL through a plain OkHttpClient with
 * different Range styles and logs each response code + body. Device
 * bisection so far: capped ranges succeed at offset 0 (0-0, 0-1M) but
 * 403 at 10M span and at later offsets. These probes add the missing
 * offset cases: open-ended from 0 and from the current chunk position.
 *
 * Top-level (not a member) only so a unit test can substitute it through
 * [YoutubeChunkedDataSourceFactory]'s constructor and observe the call without
 * touching the network. Behavior is unchanged.
 */
private fun runYouTubeChunkDiagnostics(url: String, userAgent: String, position: Long) {
    runCatching {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val probes = listOf(
            "plain-get" to null,
            "range-0-0" to "bytes=0-0",
            "range-0-1M" to "bytes=0-1048575",
            "range-0-10M" to "bytes=0-10485759",
            "range-0-openpos" to "bytes=0-$position",
            "range-pos-open" to "bytes=$position-"
        )
        for ((label, range) in probes) {
            val rb = okhttp3.Request.Builder().url(url).get()
            rb.header("User-Agent", userAgent)
            if (range != null) rb.header("Range", range)
            client.newCall(rb.build()).execute().use { resp ->
                val body = resp.peekBody(400).string().take(400)
                val loc = resp.header("Location")
                Log.w(
                    "YTChunkedDS",
                    "DIAG[$label] code=${resp.code} msg=${resp.message} loc=$loc body=$body"
                )
            }
        }
    }.onFailure { e ->
        Log.w("YTChunkedDS", "DIAG failed: ${e.message}")
    }
}
