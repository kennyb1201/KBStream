package com.kennyb1201.kbstream.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared base client must never hand a feature the "wait forever" defaults.
 *
 * The failure this pins: `OkHttpClient.Builder()` with no timeouts set means
 * OkHttp's own defaults, and those are ZERO. Every feature client that derives
 * from this base and sets none of its own inherited that — one stalled TLS
 * handshake suspended its coroutine for the life of the process and held one
 * of the twelve per-host dispatcher slots the home rails, Detail and the
 * Continue Watching scan share, so a few of them wedged the host.
 *
 * A derived client is asserted too, because that is the direction the fix has
 * to travel: the derived builder starts from the base's settings, so a feature
 * that configures only an interceptor or a dispatcher still gets them.
 */
class BaseHttpClientTest {

    @Test
    fun `the base client bounds a stalled connect and a stalled read`() {
        val client = BaseHttpClient.get()

        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
    }

    @Test
    fun `no call-wide timeout is imposed on the base`() {
        // Deliberate: a callTimeout caps the whole call, and the features that
        // stream something long (IPTV/EPG, subtitle downloads, the updater)
        // derive from this base. They set their own read windows; the base must
        // not cap them.
        assertEquals(0, BaseHttpClient.get().callTimeoutMillis)
    }

    @Test
    fun `a derived client that configures nothing else inherits them`() {
        val derived = BaseHttpClient.derived { addInterceptor { chain -> chain.proceed(chain.request()) } }

        assertEquals(10_000, derived.connectTimeoutMillis)
        assertEquals(30_000, derived.readTimeoutMillis)
        assertEquals(30_000, derived.writeTimeoutMillis)
    }

    @Test
    fun `a derived client may still replace them for its own host`() {
        val derived = BaseHttpClient.derived { readTimeout(180, java.util.concurrent.TimeUnit.SECONDS) }

        assertEquals(180_000, derived.readTimeoutMillis)
        // ...and keeps the rest, so it does not have to restate them.
        assertTrue(derived.connectTimeoutMillis == 10_000)
    }

    @Test
    fun `deriving shares the base connection pool rather than building a new one`() {
        // The pool is what the class exists for; a derived client that built its
        // own would re-handshake every host.
        assertEquals(
            BaseHttpClient.get().connectionPool,
            BaseHttpClient.derived { }.connectionPool
        )
    }
}
