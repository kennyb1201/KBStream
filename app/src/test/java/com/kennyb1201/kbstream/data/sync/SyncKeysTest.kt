package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile-scoped row keys. These pin the isolation contract that every
 * cross-profile leak (phantom watched markers, the "Simkl account follows me
 * between profiles" bug) ultimately rests on: a row may only be applied to
 * the profile whose prefix it carries.
 */
class SyncKeysTest {

    private val pidA = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"
    private val pidB = "bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb"

    @Test
    fun `scope prefixes the key with the profile id`() {
        assertEquals("p:$pidA:movie::tt0111161", SyncKeys.scoped("movie::tt0111161", pidA))
    }

    @Test
    fun `scoping without a profile leaves the legacy key untouched`() {
        assertEquals("movie::tt0111161", SyncKeys.scoped("movie::tt0111161", null))
    }

    @Test
    fun `unscoping strips exactly the profile prefix`() {
        assertEquals("movie::tt0111161", SyncKeys.unscoped("p:$pidA:movie::tt0111161"))
        // Colon-rich keys survive: only the first two segments are scope.
        assertEquals(
            "show::tt0903747::s1e2",
            SyncKeys.unscoped("p:$pidA:show::tt0903747::s1e2")
        )
    }

    @Test
    fun `unscoping a legacy key returns it unchanged`() {
        assertEquals("movie::tt0111161", SyncKeys.unscoped("movie::tt0111161"))
    }

    @Test
    fun `round trip is stable for both shapes`() {
        val scoped = SyncKeys.scoped("show::tt1234", pidB)
        assertEquals("show::tt1234", SyncKeys.unscoped(scoped))
        assertEquals(SyncKeys.unscoped(scoped), SyncKeys.unscoped(SyncKeys.scoped(SyncKeys.unscoped(scoped), pidB)))
    }

    @Test
    fun `scopeOf reports the profile, or null for legacy rows`() {
        assertEquals(pidA, SyncKeys.scopeOf("p:$pidA:movie::tt1"))
        assertNull(SyncKeys.scopeOf("movie::tt1"))
    }

    @Test
    fun `matchesProfile accepts only the exact scope`() {
        assertTrue(SyncKeys.matchesProfile("p:$pidA:x", pidA))
        assertFalse(SyncKeys.matchesProfile("p:$pidB:x", pidA))
        assertFalse(SyncKeys.matchesProfile("x", pidA))
        // A profile id that is a PREFIX of another must not match it.
        assertFalse(SyncKeys.matchesProfile("p:${pidA}extra:x", pidA))
    }

    @Test
    fun `no active profile matches only legacy rows`() {
        assertTrue(SyncKeys.matchesProfile("movie::tt1", null))
        assertFalse(SyncKeys.matchesProfile("p:$pidA:movie::tt1", null))
    }

    @Test
    fun `merge keeps local unless remote is strictly newer`() {
        assertTrue(remoteWins(remoteUpdated = 2000L, localUpdated = 1000L))
        assertFalse(remoteWins(remoteUpdated = 1000L, localUpdated = 1000L))
        assertFalse(remoteWins(remoteUpdated = 500L, localUpdated = 1000L))
    }
}
