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
}
