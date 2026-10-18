package com.kennyb1201.kbstream.data.iptv

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cancelled guide fetch has to let go of its socket.
 *
 * This is the half of the stall watchdog that no state machine can assert: the
 * watchdog cancels the coroutine doing the download, but a coroutine blocked in
 * a socket read does not notice a cancellation - it keeps the connection, and
 * with it [XmltvImporter]'s import mutex, until the read window gives up three
 * minutes later. The retry the watchdog then starts would queue behind the
 * attempt it was meant to replace, so the stall would never actually be
 * abandoned.
 *
 * A real (loopback) server that sends headers and a sliver of XML and then goes
 * quiet is the only way to prove the difference: with the connection closed on
 * cancellation this test finishes in milliseconds, and without it the cancel
 * would wait out the client's 180-second read window.
 */
class GuideFetchCancellationTest {

    @Test
    fun `cancelling a stalled guide fetch closes its connection`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val connectionClosed = CompletableDeferred<Unit>()
        val serverReady = CompletableDeferred<Unit>()

        val serverThread = thread(isDaemon = true, name = "guide-fetch-test-server") {
            try {
                server.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write(
                            (
                                // A length the provider then never delivers:
                                // this is the shape of the field failure -
                                // headers, a first sliver, then silence with the
                                // connection held open.
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/xml\r\n" +
                                    "Content-Length: 1000000\r\n" +
                                    "Connection: keep-alive\r\n" +
                                    "\r\n" +
                                    "<tv><channel id=\"a\"><display-name>A</display-name></channel>"
                                ).toByteArray()
                        )
                        flush()
                    }
                    serverReady.complete(Unit)
                    // Blocks until the client's end is gone (-1), or throws when
                    // the close resets the connection. Either way: closed.
                    socket.getInputStream().read()
                }
            } catch (expected: IOException) {
                // A reset is still the connection going away.
            } finally {
                connectionClosed.complete(Unit)
            }
        }

        val client = IptvHttpClient.create()
        val reading = CompletableDeferred<Unit>()
        try {
            val job = launch(Dispatchers.IO) {
                IptvHttpClient.streamXmltvWithRetry(
                    client,
                    "http://127.0.0.1:${server.localPort}/guide.xml"
                ) { stream ->
                    reading.complete(Unit)
                    // The provider has stopped sending: this blocks.
                    stream.readBytes()
                    Unit
                }
            }

            serverReady.await()
            reading.await()
            // Let the read actually park on the socket before cancelling it.
            delay(200)

            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue("the cancelled fetch must end cancelled", job.isCancelled)

            withTimeout(5_000) { connectionClosed.await() }
            assertTrue(
                "a cancelled fetch that leaves its socket open would wedge the " +
                    "retry behind it",
                connectionClosed.isCompleted
            )
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }
}
