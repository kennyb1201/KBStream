package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which profile a namespaced store resolves to.
 *
 * This is the launch-window rule: Application.onCreate constructs
 * profile-scoped singletons before ProfileManager.init binds the active
 * profile, so the resolver has to agree with init (stored id, else the first
 * profile). Getting it wrong sends a store to the legacy un-namespaced name —
 * the shared store every profile reads and writes.
 */
class ProfileScopeRulesTest {

    private val pidA = "aaaa"
    private val pidB = "bbbb"
    private val ids = listOf(pidA, pidB)

    @Test
    fun `stored active id wins`() {
        assertEquals(pidB, ProfileScopeRules.resolve(pidB, ids))
    }

    @Test
    fun `null stored id falls back to the first profile`() {
        assertEquals(pidA, ProfileScopeRules.resolve(null, ids))
    }

    @Test
    fun `stale stored id falls back to the first profile`() {
        // KEY_ACTIVE can name a profile that was deleted on another device;
        // init activates the first profile in that case, and the stores must
        // follow it rather than resolving to a name no profile owns.
        assertEquals(pidA, ProfileScopeRules.resolve("deleted-profile", ids))
    }

    @Test
    fun `no profiles resolves to the legacy store`() {
        assertNull(ProfileScopeRules.resolve(null, emptyList()))
        assertNull(ProfileScopeRules.resolve(pidA, emptyList()))
    }
}
