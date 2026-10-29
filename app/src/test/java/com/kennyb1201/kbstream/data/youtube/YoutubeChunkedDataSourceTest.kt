package com.kennyb1201.kbstream.data.youtube

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * The chunk boundary: a dropped connection, a short body, an empty 206.
 *
 * Three field captures drove this file (Onn 4K Plus, TV Home hero):
 *
 *  - `4w1xZA7pX2c` died at 36,840/140,849 ms with `resume=none`;
 *  - `xgI5iYmOf5Q` died ~8 s in with NO `trailer.death` line at all - the
 *    silent shape, which is exactly what a fake EOF looks like from outside;
 *  - `-xSxaE1ClEE` died at 15,040/126,292 ms with `resume=cancelled`.
 *
 * The chunker used to answer BOTH a truncated response and an empty chunk with
 * `C.RESULT_END_OF_INPUT`, and media3 reads that as a clean end: no error, no
 * retry, no death line, just the backdrop. These tests pin the replacement -
 * only the server's own end signals (a 416, a consumed known length) end the
 * stream; everything else retries the chunk and then THROWS, so the hero
 * re-resolves instead of believing the trailer finished.
 *
 * A fake [HttpDataSource] scripts opens instead of bytes over a socket: the
 * class had no seam at all before, which is how the "truncation is EOF" rule
 * survived two earlier fixes in the same file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class YoutubeChunkedDataSourceTest {

    @Before
    fun clearLogs() {
        ShadowLog.clear()
    }

    // ---------------------------------------------------------------- retries

    @Test
    fun `a chunk that never opens throws the last failure instead of ending the stream`() {
        val stream = ByteArray(3 * CHUNK) { it.toByte() }
        val fake =
            FakeUpstream(stream) { index, _ ->
                // Chunk 1 serves; every open after it times out.
                if (index == 0) Open.Serve else Open.Fail(SocketTimeoutException("read timed out"))
            }
        val source = dataSource(fake)
        source.open(spec(position = 0))

        val failure = readUntilFailure(source)

        assertTrue(
            "the last failure is rethrown as-is, never synthesized: $failure",
            failure is SocketTimeoutException
        )
        assertEquals("one open for chunk 1, then the three attempts of chunk 2", 4, fake.opens.size)
        assertTrue("the retries re-opened mid-stream", fake.opens.drop(1).all { it.position > 0L })

        val lines = chunkLogs()
        assertEquals(
            "two retries, then the exhaustion line",
            2,
            lines.count { it.startsWith("Chunk retry") }
        )
        assertTrue(lines.any { it.startsWith("Chunk retry 2/3 at offset=") })
        assertTrue(lines.any { it.startsWith("Chunk open failed after 3 attempts") })
    }

    @Test
    fun `a transient failure on the first open is retried in place and playback continues`() {
        val stream = ByteArray(2 * CHUNK) { it.toByte() }
        val fake =
            FakeUpstream(stream) { index, _ ->
                if (index == 0) Open.Fail(SocketTimeoutException("connect timed out")) else Open.Serve
            }
        val source = dataSource(fake)

        source.open(spec(position = 0))
        val read = source.read(ByteArray(64 * 1024), 0, 64 * 1024)

        assertTrue("bytes are served after the retry", read > 0)
        assertEquals("the initial open plus exactly one retry", 2, fake.opens.size)
        assertEquals(
            "the retry asks for the SAME range as the open it replaces",
            fake.opens[0].position to fake.opens[0].length,
            fake.opens[1].position to fake.opens[1].length
        )
    }

    // ------------------------------------------------------- truncation is not EOF

    @Test
    fun `a from-0 body shorter than the delivered prefix is retried, not read as the end`() {
        val stream = ByteArray(3 * CHUNK) { it.toByte() }
        val fake =
            FakeUpstream(stream) { index, _ ->
                when (index) {
                    // Both position-mode opens are refused, which is what pushes
                    // the chunk back to a from-0 request...
                    0, 1 -> Open.Code(403)
                    // ...and that response is SHORTER than the prefix: truncated.
                    2 -> Open.Bytes(ByteArray(100))
                    // The retry re-opens at the position it was reading.
                    else -> Open.Serve
                }
            }
        val source = dataSource(fake)

        source.open(spec(position = CHUNK.toLong()))
        val read = source.read(ByteArray(4096), 0, 4096)

        assertTrue("the retried chunk delivers bytes instead of EOF", read > 0)
        assertTrue(
            "the retry re-opened the chunk at the position it was reading, not from 0",
            fake.opens.last().position == CHUNK.toLong()
        )
    }

    @Test
    fun `a chunk that serves nothing is retried, and exhaustion throws instead of ending the stream`() {
        val stream = ByteArray(2 * CHUNK) { it.toByte() }
        val fake =
            FakeUpstream(stream) { index, _ ->
                when (index) {
                    0 -> Open.Serve
                    1 -> Open.Bytes(ByteArray(0))
                    else -> Open.Serve
                }
            }
        val source = dataSource(fake)
        source.open(spec(position = 0))

        val buffer = ByteArray(CHUNK)
        assertEquals("chunk 1", CHUNK, source.read(buffer, 0, CHUNK))
        val read = source.read(buffer, 0, buffer.size)

        assertTrue("the empty 206 is retried and the retry serves bytes", read > 0)
        assertEquals("chunk 1, the empty open, the successful retry", 3, fake.opens.size)
    }

    @Test
    fun `a chunk that never serves a byte throws instead of ending the stream`() {
        val stream = ByteArray(2 * CHUNK) { it.toByte() }
        val fake =
            FakeUpstream(stream) { index, _ ->
                if (index == 0) Open.Serve else Open.Bytes(ByteArray(0))
            }
        val source = dataSource(fake)
        source.open(spec(position = 0))

        val buffer = ByteArray(CHUNK)
        assertEquals(CHUNK, source.read(buffer, 0, CHUNK))

        val failure = readUntilFailure(source)

        assertTrue("a dedicated IOException, not EOF and not a synthetic timeout", failure is IOException)
        assertTrue(
            "the message names the position and the budget: ${failure.message}",
            failure.message.orEmpty().contains("delivered no bytes") &&
                failure.message.orEmpty().contains("after 3 attempts")
        )
        assertEquals("chunk 1 plus three attempts at the empty one", 4, fake.opens.size)
    }

    // ----------------------------------------------------------- genuine ends

    @Test
    fun `a 416 on the position open ends the stream at once`() {
        val stream = ByteArray(2 * CHUNK) { it.toByte() }
        val fake = FakeUpstream(stream) { _, request -> if (request.position > 0L) Open.Code(416) else Open.Serve }
        val source = dataSource(fake)

        assertEquals("open() reports an empty stream", 0L, source.open(spec(position = CHUNK.toLong())))
        assertEquals(
            "a real end is not retried",
            C.RESULT_END_OF_INPUT,
            source.read(ByteArray(4096), 0, 4096)
        )
        assertEquals("exactly one open: the 416 is the answer", 1, fake.opens.size)
    }

    @Test
    fun `a fully consumed known length ends the stream without a retry`() {
        val stream = ByteArray(100_000) { it.toByte() }
        val fake = FakeUpstream(stream) { _, _ -> Open.Serve }
        val source = dataSource(fake)

        assertEquals(100_000L, source.open(spec(position = 0, length = 100_000L)))
        assertEquals(100_000, drain(source))
        assertEquals(
            "the length arm is the server's own end signal",
            C.RESULT_END_OF_INPUT,
            source.read(ByteArray(16), 0, 16)
        )
        assertEquals(1, fake.opens.size)
    }

    // ------------------------------------------------------------------ 403s

    @Test
    fun `a 403 is never retried and still runs the diagnostics exactly once`() {
        val stream = ByteArray(2 * CHUNK) { it.toByte() }
        val fake = FakeUpstream(stream) { _, _ -> Open.Code(403) }
        var diagnostics = 0
        val source =
            dataSource(fake, diagnostics = { _, _, _ -> diagnostics += 1 })

        val failure = openUntilFailure(source, spec(position = 0))

        assertTrue("the 403 the ladder could not beat is rethrown", failure is HttpDataSource.InvalidResponseCodeException)
        assertEquals(403, (failure as HttpDataSource.InvalidResponseCodeException).responseCode)
        assertEquals("four modes, no retries", 4, fake.opens.size)
        assertEquals("the bisection runs once, not once per attempt", 1, diagnostics)
        assertTrue(
            "no retry may be logged for a refusal",
            chunkLogs().none { it.startsWith("Chunk retry") }
        )
    }

    @Test
    fun `a 503 is retried and then thrown once the budget is spent`() {
        val stream = ByteArray(2 * CHUNK) { it.toByte() }
        val recovering =
            FakeUpstream(stream) { index, _ -> if (index < 2) Open.Code(503) else Open.Serve }
        val recovered = dataSource(recovering)
        recovered.open(spec(position = 0))
        assertTrue(recovered.read(ByteArray(1024), 0, 1024) > 0)
        assertEquals("two retries then the served open", 3, recovering.opens.size)

        val refusing = FakeUpstream(stream) { _, _ -> Open.Code(503) }
        val failure = openUntilFailure(dataSource(refusing), spec(position = 0))
        assertTrue(failure is HttpDataSource.InvalidResponseCodeException)
        assertEquals(503, (failure as HttpDataSource.InvalidResponseCodeException).responseCode)
        assertEquals("three attempts, then the throw", 3, refusing.opens.size)
    }

    // ------------------------------------------------------- budget and cancel

    @Test
    fun `the retry schedule is two retries with 250 ms and then 500 ms`() {
        assertEquals(3, YoutubeChunkedDataSourceFactory.CHUNK_OPEN_ATTEMPTS)
        assertEquals(250L, YoutubeChunkedDataSourceFactory.CHUNK_RETRY_BACKOFF_MS)
    }

    @Test
    fun `an interrupt during the backoff rethrows and spends no further attempt`() {
        val stream = ByteArray(2 * CHUNK) { it.toByte() }
        val fake = FakeUpstream(stream) { _, _ -> Open.Fail(SocketTimeoutException("timed out")) }
        val source = dataSource(fake)

        Thread.currentThread().interrupt()
        val failure =
            try {
                source.open(spec(position = 0))
                null
            } catch (e: IOException) {
                e
            } finally {
                val restored = Thread.currentThread().isInterrupted
                Thread.interrupted()
                assertTrue("the interrupt flag is restored, not swallowed", restored)
            }

        assertTrue("a cancellation is reported as one: $failure", failure is InterruptedIOException)
        assertEquals("the attempt in flight is the only one", 1, fake.opens.size)
    }

    // ---------------------------------------------------------- classification

    @Test
    fun `a timeout wrapped by the HTTP stack is still a transient failure`() {
        val wrapped =
            HttpDataSource.HttpDataSourceException(
                SocketTimeoutException("read timed out"),
                spec(position = 0),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            )
        assertTrue(wrapped.isTransientChunkFailure())
    }

    @Test
    fun `statuses are classified per code, not per exception type`() {
        assertTrue(codeFailure(503).isTransientChunkFailure())
        assertTrue(codeFailure(504).isTransientChunkFailure())
        assertFalse("a refusal belongs to the identity ladder", codeFailure(403).isTransientChunkFailure())
        assertFalse("a real end signal is never retried", codeFailure(416).isTransientChunkFailure())
        assertFalse("a wrong URL cannot be fixed by asking again", codeFailure(404).isTransientChunkFailure())
        assertFalse(
            "a cancel is never a retry",
            InterruptedIOException("canceled").isTransientChunkFailure()
        )
    }

    // ------------------------------------------------------------ passthrough

    @Test
    fun `a non-googlevideo host is passed through with no retries at all`() {
        val stream = ByteArray(2 * CHUNK) { it.toByte() }
        val fake = FakeUpstream(stream) { _, _ -> Open.Fail(SocketTimeoutException("timed out")) }
        val source = dataSource(fake)

        val failure = openUntilFailure(source, spec(position = 0, url = "https://cdn.example.com/movie.mp4"))

        assertTrue(failure is SocketTimeoutException)
        assertEquals("one attempt: the chunk retry loop wraps the googlevideo path only", 1, fake.opens.size)
    }

    // ------------------------------------------------------------------ seams

    private fun dataSource(
        upstream: HttpDataSource,
        userAgents: List<String> = listOf("UA-of-the-signing-client"),
        diagnostics: (url: String, userAgent: String, position: Long) -> Unit = { _, _, _ -> }
    ): YoutubeChunkedDataSourceFactory.YoutubeChunkedDataSource =
        YoutubeChunkedDataSourceFactory.YoutubeChunkedDataSource(
            upstream = upstream,
            chunkSize = CHUNK.toLong(),
            userAgentCandidates = userAgents,
            diagnostics = diagnostics
        )

    private fun spec(
        position: Long,
        length: Long = C.LENGTH_UNSET.toLong(),
        url: String = GOOGLEVIDEO_URL
    ): DataSpec =
        DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setPosition(position)
            .setLength(length)
            .build()

    private fun codeFailure(code: Int): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code,
            "status $code",
            null,
            emptyMap(),
            spec(position = 0),
            ByteArray(0)
        )

    private fun drain(source: DataSource, limit: Int = 8 * CHUNK): Int {
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (total < limit) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) return total
            total += read
        }
        return total
    }

    /** Reads until the source throws; fails the test if it ever answers EOF instead. */
    private fun readUntilFailure(source: DataSource): Throwable {
        val buffer = ByteArray(64 * 1024)
        repeat(64) {
            val read =
                try {
                    source.read(buffer, 0, buffer.size)
                } catch (e: IOException) {
                    return e
                }
            assertTrue(
                "read() answered EOF where the chunker owed an error",
                read != C.RESULT_END_OF_INPUT
            )
        }
        throw AssertionError("read() never failed")
    }

    private fun openUntilFailure(source: DataSource, dataSpec: DataSpec): Throwable =
        try {
            source.open(dataSpec)
            throw AssertionError("open() was expected to fail")
        } catch (e: IOException) {
            e
        }

    private fun chunkLogs(): List<String> = ShadowLog.getLogsForTag("YTChunkedDS").map { it.msg }

    /** What one `open()` of the fake does. */
    private sealed interface Open {
        /** Behave like a well-mannered server: serve the stream from the requested position. */
        data object Serve : Open

        /** Serve exactly these bytes, whatever the request asked for. */
        class Bytes(val body: ByteArray) : Open

        /** Fail the open with this transport error. */
        class Fail(val error: IOException) : Open

        /** Answer the open with this status code. */
        class Code(val code: Int) : Open
    }

    /**
     * A googlevideo-shaped upstream whose every open is scripted.
     *
     * The open count and the [DataSpec] of each open are kept so a test can
     * assert what was retried (the same range) and what was not (a 403).
     */
    private class FakeUpstream(
        private val stream: ByteArray,
        private val onOpen: (index: Int, request: DataSpec) -> Open
    ) : HttpDataSource {

        val opens = mutableListOf<DataSpec>()

        private var body: ByteArray = ByteArray(0)
        private var cursor = 0
        private var uri: Uri? = null
        private var responseCode = 200

        override fun open(dataSpec: DataSpec): Long {
            val index = opens.size
            opens += dataSpec
            uri = dataSpec.uri
            cursor = 0
            when (val scripted = onOpen(index, dataSpec)) {
                is Open.Fail -> throw scripted.error
                is Open.Code -> {
                    responseCode = scripted.code
                    throw HttpDataSource.InvalidResponseCodeException(
                        scripted.code,
                        "status ${scripted.code}",
                        null,
                        emptyMap(),
                        dataSpec,
                        ByteArray(0)
                    )
                }
                is Open.Bytes -> body = scripted.body
                Open.Serve -> {
                    val from = dataSpec.position.coerceAtMost(stream.size.toLong()).toInt()
                    val to =
                        if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                            stream.size
                        } else {
                            minOf(stream.size.toLong(), from + dataSpec.length).toInt()
                        }
                    body = stream.copyOfRange(from, maxOf(from, to))
                }
            }
            responseCode = 200
            return body.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (cursor >= body.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, body.size - cursor)
            System.arraycopy(body, cursor, buffer, offset, count)
            cursor += count
            return count
        }

        override fun close() {
            body = ByteArray(0)
            cursor = 0
        }

        override fun getUri(): Uri? = uri

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun getResponseCode(): Int = responseCode

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun setRequestProperty(name: String, value: String) = Unit

        override fun clearRequestProperty(name: String) = Unit

        override fun clearAllRequestProperties() = Unit
    }

    private companion object {
        const val CHUNK = 1 shl 20

        const val GOOGLEVIDEO_URL =
            "https://rr1---sn-abc123.googlevideo.com/videoplayback?expire=1&id=o-abc&itag=137"
    }
}
