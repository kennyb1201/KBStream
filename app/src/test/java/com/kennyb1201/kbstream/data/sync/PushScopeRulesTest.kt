package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that keeps a bulk push pass from publishing one profile's data under
 * another profile's cloud scope.
 *
 * Reported: "a single watched episode of paw patrol ... in my simkl and mdblist
 * history ... for profile 1, but I've never watched those except on profile 3
 * the kids profile". The tracker accounts are per ACCOUNT, but the session that
 * pushes to them is stored PER PROFILE. Every push pass (history, watched
 * markers, prefs) captures the active profile id and then reads rows / builds
 * blobs that resolve the active profile AGAIN; a profile switch in between made
 * the data one profile's and the scope the other's, so the kids profile ended
 * up holding (and pushing with) the adult profile's Simkl session and MDBList
 * key.
 *
 * [PushScopeRules.scopeStillActive] is the one rule that closes it: a pass may
 * only publish while the profile it captured is still the active one.
 */
class PushScopeRulesTest {

    private val pidA = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"
    private val pidB = "bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb"

    @Test
    fun `a pass whose profile is still active publishes`() {
        assertTrue(PushScopeRules.scopeStillActive(pidA, pidA))
    }

    @Test
    fun `a switch during the pass drops it`() {
        // The exact leak: captured profile 1, built under profile 3.
        assertFalse(PushScopeRules.scopeStillActive(pidA, pidB))
    }

    @Test
    fun `a profile appearing mid-pass drops it`() {
        // Legacy startup (no profile) that becomes a profiled one before the
        // build: a legacy-scoped pass must not stamp a profile's scope.
        assertFalse(PushScopeRules.scopeStillActive(null, pidA))
    }

    @Test
    fun `a profile disappearing mid-pass drops it`() {
        assertFalse(PushScopeRules.scopeStillActive(pidA, null))
    }

    @Test
    fun `legacy no-profiles mode still publishes`() {
        // Pre-profiles installs have no scope to cross — unchanged behavior.
        assertTrue(PushScopeRules.scopeStillActive(null, null))
    }
}
