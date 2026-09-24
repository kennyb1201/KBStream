package com.kennyb1201.kbstream.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind the shared Simkl disk-cache table.
 *
 * Reported bug: after switching profiles, the previous profile's Upcoming rail
 * sometimes stayed on the new profile until a force-close - and not always
 * even then. The memory half is cleared on a switch; the 12h DISK blob was the
 * part a restart could not fix, because the key named only the profile. A
 * fetch started under the outgoing profile resolves its Simkl token before the
 * switch but its cache key after it, so it filed the old account's library
 * under the incoming profile's slot. The key therefore has to name the account
 * as well as the profile.
 */
class SimklCacheKeysTest {

    private val tokenA = "simkl-token-account-a"
    private val tokenB = "simkl-token-account-b"

    @Test
    fun `the same account always resolves to the same key`() {
        // Stability is what makes the blob reusable at all: a value that
        // changed between calls would miss the blob the previous call wrote
        // and re-download the whole show library on every launch.
        assertEquals(
            SimklCacheKeys.discriminator(tokenA),
            SimklCacheKeys.discriminator(tokenA)
        )
        assertEquals(
            SimklCacheKeys.scoped("profile-1/simkl:all_show_items", tokenA),
            SimklCacheKeys.scoped("profile-1/simkl:all_show_items", tokenA)
        )
    }

    @Test
    fun `two accounts never share a key`() {
        assertNotEquals(
            SimklCacheKeys.discriminator(tokenA),
            SimklCacheKeys.discriminator(tokenB)
        )
        assertNotEquals(
            SimklCacheKeys.scoped("profile-1/simkl:all_show_items", tokenA),
            SimklCacheKeys.scoped("profile-1/simkl:all_show_items", tokenB)
        )
    }

    @Test
    fun `two profiles never share a key`() {
        // The account half alone is not enough: two profiles can be connected
        // to one account, but one profile can also hold two accounts across a
        // session. Both dimensions have to be in the key.
        assertNotEquals(
            SimklCacheKeys.scoped("profile-1/simkl:all_show_items", tokenA),
            SimklCacheKeys.scoped("profile-2/simkl:all_show_items", tokenA)
        )
    }

    @Test
    fun `the key carries a fixed-width digest, not the token`() {
        val discriminator = SimklCacheKeys.discriminator(tokenA)

        // 6 bytes as hex - short enough to stay well inside the cache key
        // limit, long enough that two accounts cannot collide by accident.
        assertEquals(12, discriminator.length)
        assertTrue(discriminator.all { it in "0123456789abcdef" })

        val key = SimklCacheKeys.scoped("profile-1/simkl:all_show_items", tokenA)
        assertTrue(key.startsWith("profile-1/simkl:all_show_items#"))
        assertTrue(key.endsWith(discriminator))

        // These keys sit as plain text in the shared cache table, so a working
        // credential must never be readable from one.
        assertFalse(key.contains(tokenA))
    }

    @Test
    fun `an empty token still yields a usable key`() {
        // A profile that never connected Simkl reaches the cache with a blank
        // token; that must resolve to a key rather than throwing out of a
        // lookup.
        val key = SimklCacheKeys.scoped("simkl:continue_watching", "")

        assertTrue(key.startsWith("simkl:continue_watching#"))
        assertFalse(key.contains("null"))
    }
}
