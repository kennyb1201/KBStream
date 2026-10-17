package com.kennyb1201.kbstream.ui.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The probe's download half: the URL rules, the ranged head it fetches, the
 * process-lifetime cache, and the timeout that keeps a stalled origin from
 * hanging pre-flight.
 *
 * The rate reader is swapped for a seam here because the production one is the
 * platform's [android.media.MediaExtractor] — an Android native API a JVM test
 * cannot drive. The bytes served are deliberately opaque: what is under test is
 * that the head is fetched, forwarded with the stream's headers, cached, and
 * refused politely when it cannot be read.
 */
class FrameRateProbeTest {

    private lateinit var server: HttpServer
    private lateinit var headDir: File
    private val requests = mutableListOf<String>()
    private var body = ByteArray(0)
    private var stallMs = 0L
    private var capturedUserAgent: String? = null
    private var originalReader: ((File) -> Double?)? = null
    private var originalBudget = 0L

    @Before
    fun setUp() {
        originalReader = FrameRateProbe.fpsReader
        originalBudget = FrameRateProbe.budgetMs
        requests.clear()
        body = ByteArray(0)
        stallMs = 0L
        capturedUserAgent = null
        headDir = Files.createTempDirectory("afrprobe-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        originalReader?.let { FrameRateProbe.fpsReader = it }
        FrameRateProbe.budgetMs = originalBudget
        headDir.deleteRecursively()
    }

    private fun handle(exchange: HttpExchange) {
        synchronized(requests) {
            requests += exchange.requestHeaders.getFirst("Range").orEmpty()
        }
        capturedUserAgent = exchange.requestHeaders.getFirst("User-Agent")
        if (stallMs > 0) Thread.sleep(stallMs)
        exchange.responseHeaders.add(
            "Content-Range",
            "bytes 0-${(body.size - 1).coerceAtLeast(0)}/${body.size}"
        )
        exchange.sendResponseHeaders(206, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun url(name: String): String = "http://127.0.0.1:${server.address.port}/$name"

    @Test
    fun `the reader's rate comes back for a probeable file URL`() = runBlocking {
        FrameRateProbe.fpsReader = { 23.976 }
        body = ByteArray(64) { it.toByte() }

        val fps = FrameRateProbe.probe(
            url("movie.mkv"),
            mapOf("User-Agent" to "KBStream"),
            headDir
        )

        assertEquals(23.976, fps ?: 0.0, 0.0)
        assertEquals("the stream's own headers must reach the origin", "KBStream", capturedUserAgent)
    }

    @Test
    fun `a one-byte warm-up precedes the ranged head`() = runBlocking {
        FrameRateProbe.fpsReader = { 24.0 }
        body = ByteArray(32)

        FrameRateProbe.probe(url("ranged.mkv"), emptyMap(), headDir)

        val ranges = synchronized(requests) { requests.toList() }
        assertTrue("a warm-up request is expected first", ranges.first().contains("bytes=0-0"))
        assertTrue(
            "the head is the ~4 MB ranged fetch",
            ranges.any { it.contains("bytes=0-4194303") }
        )
    }

    @Test
    fun `the same URL probed twice downloads once`() = runBlocking {
        FrameRateProbe.fpsReader = { 23.976 }
        body = ByteArray(16)
        val target = url("cached.mkv")

        FrameRateProbe.probe(target, emptyMap(), headDir)
        val afterFirst = synchronized(requests) { requests.size }
        FrameRateProbe.probe(target, emptyMap(), headDir)
        val afterSecond = synchronized(requests) { requests.size }

        assertTrue("the first probe must reach the server", afterFirst > 0)
        assertEquals("the second probe must be served from the cache", afterFirst, afterSecond)
    }

    @Test
    fun `an unreadable head yields null and is retried next time`() = runBlocking {
        FrameRateProbe.fpsReader = { null }
        body = ByteArray(16)
        val target = url("junk.mkv")

        assertNull(FrameRateProbe.probe(target, emptyMap(), headDir))
        val afterFirst = synchronized(requests) { requests.size }
        assertNull(FrameRateProbe.probe(target, emptyMap(), headDir))
        val afterSecond = synchronized(requests) { requests.size }

        assertTrue("a null result is not cached, so a later probe may still succeed", afterSecond > afterFirst)
    }

    @Test
    fun `a non-http URL is never probed`() = runBlocking {
        FrameRateProbe.fpsReader = { 23.976 }

        assertNull(FrameRateProbe.probe("rtmp://host/live", emptyMap(), headDir))
        assertNull(FrameRateProbe.probe("file:///sdcard/x.mkv", emptyMap(), headDir))

        assertTrue("nothing may be sent for a URL that cannot be probed", synchronized(requests) { requests.isEmpty() })
    }

    @Test
    fun `a stalled server resolves to null inside the budget`() = runBlocking {
        FrameRateProbe.fpsReader = { 23.976 }
        FrameRateProbe.budgetMs = 400
        stallMs = 3_000
        body = ByteArray(16)

        val started = System.nanoTime()
        val fps = FrameRateProbe.probe(url("stall.mkv"), emptyMap(), headDir)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull(fps)
        assertTrue("probe waited ${elapsedMs}ms; it must not sit out the stalled origin", elapsedMs < 3_000)
    }

    @Test
    fun `only http(s) file URLs are probeable`() {
        assertTrue(FrameRateProbe.isProbeable("http://host/movie.mkv"))
        assertTrue(FrameRateProbe.isProbeable("https://host/movie.mp4?token=abc"))
        assertFalse(
            "a live playlist's bytes never end",
            FrameRateProbe.isProbeable("http://host/live.m3u8?x=1")
        )
        assertFalse(FrameRateProbe.isProbeable("https://host/manifest.mpd"))
        assertFalse(FrameRateProbe.isProbeable("rtmp://host/live"))
        assertFalse(FrameRateProbe.isProbeable("file:///sdcard/movie.mkv"))
    }

    // ── the retry's input: which failure the probe recorded ───────────────
    //
    // Field 2026-10-16: the 6 s budget lost to a slow host's cold start (its
    // first bytes arrived ~3 s later, and a 9654 ms rebuffer sits in the same
    // window), and nothing ever re-attempted, so the panel stayed at 60 Hz.
    // The retry is allowed for that failure and for no other, which is what the
    // recorded kind is for.

    @Test
    fun `a download that never completed is a network failure, spendable once`() = runBlocking {
        FrameRateProbe.budgetMs = 400
        stallMs = 2_000
        body = ByteArray(16)
        val target = url("slow-host.mkv")

        assertNull(
            "a host slower than the budget is a null reading, exactly as before",
            FrameRateProbe.probe(target, emptyMap(), headDir)
        )
        assertEquals(
            "the download is what failed, so this is the failure a retry can fix",
            ProbeFailure.NETWORK,
            FrameRateProbe.failureKind(target)
        )
        assertTrue("and it may arm the retry", FrameRateProbe.consumeNetworkFailure(target))
        assertFalse(
            "but only once: a second true here is the loop an unbounded retry would be",
            FrameRateProbe.consumeNetworkFailure(target)
        )
    }

    @Test
    fun `an unreadable head is unusable, and never arms the retry`() = runBlocking {
        // The head downloaded fine and declared no rate: a second download of
        // the same head returns the same nothing, so it must not be paid for.
        FrameRateProbe.fpsReader = { null }
        body = ByteArray(16)
        val target = url("silent.mkv")

        assertNull(FrameRateProbe.probe(target, emptyMap(), headDir))
        assertEquals(
            "the reading, not the download, is what failed",
            ProbeFailure.UNUSABLE,
            FrameRateProbe.failureKind(target)
        )
        assertFalse(
            "so nothing is armed: re-downloading would return the same null",
            FrameRateProbe.consumeNetworkFailure(target)
        )
    }

    @Test
    fun `a success, a cache hit and a skipped probe all leave nothing armed`() = runBlocking {
        FrameRateProbe.fpsReader = { 23.976 }
        body = ByteArray(16)
        val target = url("clean.mkv")

        assertNull(
            "a URL the probe refuses never reached a download",
            FrameRateProbe.probe("rtmp://host/live", emptyMap(), headDir)
        )
        assertNull(
            "nothing may be recorded for it",
            FrameRateProbe.failureKind("rtmp://host/live")
        )
        assertEquals(23.976, FrameRateProbe.probe(target, emptyMap(), headDir) ?: 0.0, 0.0)
        assertNull("a success records no failure", FrameRateProbe.failureKind(target))
        assertEquals(
            "and a second call is the cache hit, which must not invent one either",
            23.976,
            FrameRateProbe.probe(target, emptyMap(), headDir) ?: 0.0,
            0.0
        )
        assertNull(FrameRateProbe.failureKind(target))
        assertFalse(FrameRateProbe.consumeNetworkFailure(target))
    }

    @Test
    fun `the budget a caller passes is the budget the second attempt gets`() = runBlocking {
        // The retry passes a bigger budget because the first one ran out on a
        // slow host, so the parameter has to reach the HTTP requests themselves:
        // a larger coroutine deadline wrapped around the same 6 s socket
        // timeouts would change nothing at all.
        FrameRateProbe.fpsReader = { 23.976 }
        FrameRateProbe.budgetMs = 100
        stallMs = 400
        body = ByteArray(16)
        val target = url("retried.mkv")

        assertNull(
            "a caller that passes no budget still gets the process budget - today's behavior",
            FrameRateProbe.probe(target, emptyMap(), headDir)
        )
        assertEquals(
            "and that is the network failure the retry exists for",
            ProbeFailure.NETWORK,
            FrameRateProbe.failureKind(target)
        )
        assertEquals(
            "the retry's own budget gets the same host's head",
            23.976,
            FrameRateProbe.probe(target, emptyMap(), headDir, budgetMs = 6_000) ?: 0.0,
            0.0
        )
        assertNull(
            "and its success clears the record, so no later retry can spend it",
            FrameRateProbe.failureKind(target)
        )
        assertFalse(FrameRateProbe.consumeNetworkFailure(target))
    }

    // ── the timestamp fallback: a head that declares no rate ──────────────
    //
    // Field 2026-10-11: the probe ran, downloaded the head, found the video
    // track — and returned null, because a Matroska WEB-DL declares no
    // KEY_FRAME_RATE at all while its cadence sits in the sample timestamps the
    // head already carries. These pin the pure half of that fallback; the walk
    // that feeds it is the platform extractor, which a JVM test cannot drive.

    /** A steady run of [count] samples at [deltaUs] apart, in presentation order. */
    private fun cadence(count: Int, deltaUs: Long): List<Long> =
        (0 until count).map { index -> index * deltaUs }

    @Test
    fun `a steady 23_976 cadence reads back as 23_976`() {
        val fps = estimateFpsFromTimestamps(cadence(24, 41_708L))
        assertEquals("41708us is the 23.976 cadence", 23.976, fps ?: 0.0, 0.01)
    }

    @Test
    fun `a 29_97 cadence reads back as 29_97`() {
        val fps = estimateFpsFromTimestamps(cadence(24, 33_367L))
        assertEquals("33367us is the 29.97 cadence", 29.97, fps ?: 0.0, 0.01)
    }

    @Test
    fun `decode-order B-frame jitter is sorted back to the real cadence`() {
        // MediaExtractor walks samples in DECODE order, so an IBBP file hands
        // back times that jump backwards: consecutive deltas are jitter, while
        // the sorted run is the presentation cadence.
        val presentation = cadence(24, 41_708L)
        val decodeOrder = presentation.chunked(3).flatMap { it.reversed() }
        assertTrue(
            "the shuffled run must actually be out of order, or the test proves nothing",
            decodeOrder != presentation
        )

        val fps = estimateFpsFromTimestamps(decodeOrder)
        assertEquals("the sort recovers the presentation cadence", 23.976, fps ?: 0.0, 0.01)
    }

    @Test
    fun `variable frame rate deltas are refused`() {
        // Alternating 41ms / 83ms: exactly the mixed cadence the estimate must
        // not average into a rate nobody is playing at.
        val stamps = mutableListOf(0L)
        repeat(23) { index ->
            stamps.add(stamps.last() + if (index % 2 == 0) 41_708L else 83_417L)
        }

        assertNull(
            "most deltas disagree with the median, so there is no cadence to report",
            estimateFpsFromTimestamps(stamps)
        )
    }

    @Test
    fun `too few samples are refused`() {
        assertNull("a dozen samples is the floor", estimateFpsFromTimestamps(cadence(11, 41_708L)))
        assertNull("and nothing at all is not a cadence", estimateFpsFromTimestamps(emptyList()))
    }

    @Test
    fun `timestamps that do not advance are refused`() {
        // A head whose samples all share one timestamp (or repeat it enough
        // times) has no deltas to read. The guess stays null — today's answer.
        assertNull(
            "zero deltas only",
            estimateFpsFromTimestamps(List(24) { 1_234_567L })
        )
        // A full dozen samples that mostly sit on the same timestamp: the deltas
        // that survive the zero filter are fewer than the floor needs.
        assertNull(
            "fewer advancing steps than the floor needs",
            estimateFpsFromTimestamps(
                listOf(
                    0L, 0L, 0L, 0L,
                    41_708L, 41_708L, 41_708L, 41_708L,
                    83_416L, 83_416L, 83_416L, 83_416L
                )
            )
        )
    }
}
