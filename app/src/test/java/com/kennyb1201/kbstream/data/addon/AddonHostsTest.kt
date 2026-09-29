package com.kennyb1201.kbstream.data.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which host an installed addon's manifest is served from.
 *
 * This is what tells the diagnostics perf block that a slow call belongs to
 * AIOStreams rather than to "http.132". It has to be right about the forms an
 * addon URL actually takes — the bare addresses a self-hosted addon is
 * installed under above all — because a host that does not match produces no
 * attribution at all, and the report goes back to an unnamed number.
 */
class AddonHostsTest {

    @Test
    fun `an https manifest yields its host`() {
        assertEquals(
            "aiostreams.example.com",
            hostOfUrl("https://aiostreams.example.com/manifest.json")
        )
    }

    @Test
    fun `a bare address keeps every octet`() {
        // The reported case: a self-hosted AIOStreams reached by IP. The whole
        // address is the host, and it is what the summary prints.
        assertEquals(
            "132.226.4.9",
            hostOfUrl("http://132.226.4.9:3000/manifest.json")
        )
    }

    @Test
    fun `a port is not part of the host`() {
        assertEquals("aiostreams.lan", hostOfUrl("http://aiostreams.lan:3000/manifest.json"))
    }

    @Test
    fun `the host is lower-cased`() {
        assertEquals("aiostreams.example.com", hostOfUrl("HTTPS://AIOStreams.Example.COM/manifest.json"))
    }

    @Test
    fun `userinfo is dropped`() {
        assertEquals("addon.example.com", hostOfUrl("https://user:pass@addon.example.com/m.json"))
    }

    @Test
    fun `an IPv6 literal loses its brackets and keeps its colons`() {
        assertEquals("fe80::1", hostOfUrl("http://[fe80::1]:3000/manifest.json"))
        assertEquals("2606:4700::6810:84e5", hostOfUrl("https://[2606:4700::6810:84e5]/manifest.json"))
    }

    @Test
    fun `a scheme-less URL still yields its host`() {
        // Hand-typed into the add-addon field, which does not require a scheme.
        assertEquals("aiostreams.lan", hostOfUrl("aiostreams.lan:3000/manifest.json"))
    }

    @Test
    fun `query and fragment do not leak into the host`() {
        assertEquals("addon.example.com", hostOfUrl("https://addon.example.com?x=1"))
        assertEquals("addon.example.com", hostOfUrl("https://addon.example.com#frag"))
    }

    @Test
    fun `a string with no host yields null`() {
        listOf("", "   ", "///", "/manifest.json").forEach { url ->
            assertNull("\"$url\" has no host", hostOfUrl(url))
        }
    }

    @Test
    fun `a scheme-less string with no path is the host itself`() {
        // "aiostreams.lan" typed on its own. There is nothing else it can be,
        // and it is the same rule that makes "aiostreams.lan:3000/x" work -
        // which is why this is an assertion and not a null.
        assertEquals("aiostreams.lan", hostOfUrl("aiostreams.lan"))
        assertEquals("manifest.json", hostOfUrl("manifest.json"))
    }
}
