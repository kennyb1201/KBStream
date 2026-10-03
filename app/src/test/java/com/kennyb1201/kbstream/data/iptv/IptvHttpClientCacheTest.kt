package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping that turns stored validators into a conditional request.
 *
 * Every refresh of a 10-100 MB guide is skipped only if these headers actually
 * reach the request, so the mapping is pinned here: a validator that is stored
 * but never sent is an invisible return to full downloads, and nothing else in
 * the suite would notice it. The function is pure, so no HTTP harness is
 * needed - the retry/stream paths are exercised on device.
 */
class IptvHttpClientCacheTest {

    private fun headers(validators: GuideCacheValidators?) =
        IptvHttpClient.cacheRequestHeaders(validators)

    @Test
    fun `a guide with no stored validators sends none`() {
        assertTrue(headers(null).isEmpty())
    }

    @Test
    fun `blank validators are dropped rather than sent empty`() {
        // An empty If-None-Match / If-Modified-Since is a malformed request,
        // and a server that stopped sending an ETag must stop being asked to
        // match one.
        assertTrue(headers(GuideCacheValidators(etag = "  ", lastModified = "")).isEmpty())
    }

    @Test
    fun `an etag becomes If-None-Match`() {
        assertEquals(
            listOf("If-None-Match" to "\"abc123\""),
            headers(GuideCacheValidators(etag = "\"abc123\"", lastModified = null))
        )
    }

    @Test
    fun `a last-modified becomes If-Modified-Since`() {
        assertEquals(
            listOf("If-Modified-Since" to "Tue, 01 Oct 2026 12:00:00 GMT"),
            headers(
                GuideCacheValidators(
                    etag = null,
                    lastModified = "Tue, 01 Oct 2026 12:00:00 GMT"
                )
            )
        )
    }

    @Test
    fun `both validators are sent when the server offered both`() {
        // Order is stable so a server that honors only one still gets a
        // deterministic request; ETag first is the stronger validator.
        assertEquals(
            listOf(
                "If-None-Match" to "\"abc123\"",
                "If-Modified-Since" to "Tue, 01 Oct 2026 12:00:00 GMT"
            ),
            headers(
                GuideCacheValidators(
                    etag = "\"abc123\"",
                    lastModified = "Tue, 01 Oct 2026 12:00:00 GMT"
                )
            )
        )
    }
}
