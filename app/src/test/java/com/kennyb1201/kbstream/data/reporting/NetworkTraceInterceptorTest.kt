package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which hosts get the shared `http.ip` label.
 *
 * The point of the label is that a bare address cannot be named in the perf
 * summary, so a report that says "20s went to `http.ip`" now says which address
 * it was and the endpoint can be fixed. Getting the test wrong in either
 * direction costs something real: reply `true` for a hostname and a whole
 * service loses its own line, reply `false` for an address and the slowest
 * thing in the capture is `http.132` again.
 */
class NetworkTraceInterceptorTest {

    @Test
    fun `bare IPv4 addresses are recognised`() {
        listOf("132.226.4.9", "10.0.0.1", "0.0.0.0", "255.255.255.255").forEach { host ->
            assertTrue("$host is a bare address", isIpLiteralHost(host))
        }
    }

    @Test
    fun `IPv6 literals are recognised`() {
        // okhttp's url.host drops the brackets the URL carried.
        listOf("::1", "fe80::1", "2606:4700::6810:84e5").forEach { host ->
            assertTrue("$host is a bare address", isIpLiteralHost(host))
        }
    }

    @Test
    fun `hostnames are not addresses`() {
        listOf(
            "aiostreams.elfhosted.com",
            "api.themoviedb.org",
            "torbox.app",
            // The old label for these was `http.132`, which named nothing.
            "132.example.com",
            "1234.5.6.7",
            "v1.2.3.4.example.com"
        ).forEach { host ->
            assertFalse("$host is a name", isIpLiteralHost(host))
        }
    }

    @Test
    fun `things that look like addresses but are not are refused`() {
        listOf(
            "",
            "1.2.3",
            "1.2.3.4.5",
            "1.2.3.999",   // out of range
            "192.168.1.",  // trailing dot leaves an empty part
            "-1.2.3.4",
            "1.2.3.4a"
        ).forEach { host ->
            assertFalse("\"$host\" is neither a name nor a usable address", isIpLiteralHost(host))
        }
    }
}
