package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Simkl session blob is published as a FULL REPLACE and applied as
 * "blank token = signed out". Careless, those two halves erase live sessions:
 * a device that never connected Simkl published a blank blob, so the other
 * TV's sign-in was wiped instead of shared ("Simkl sign-in didn't sync").
 *
 * These tests pin the two rules that stop it: a device only publishes when it
 * has something to say, and a blank token only clears a session when it is an
 * explicit sign-out tombstone.
 */
class SimklAuthRulesTest {

    @Test
    fun `a token is always published`() {
        assertTrue(SimklAuthRules.shouldPublish("tok", signedOut = false))
        // A token wins over a stale disconnect marker.
        assertTrue(SimklAuthRules.shouldPublish("tok", signedOut = true))
    }

    @Test
    fun `a deliberate disconnect is published`() {
        assertTrue(SimklAuthRules.shouldPublish(null, signedOut = true))
        assertTrue(SimklAuthRules.shouldPublish("", signedOut = true))
    }

    @Test
    fun `a device with nothing to say stays out of the cloud`() {
        assertFalse(SimklAuthRules.shouldPublish(null, signedOut = false))
        assertFalse(SimklAuthRules.shouldPublish("", signedOut = false))
        assertFalse(SimklAuthRules.shouldPublish("   ", signedOut = false))
    }

    @Test
    fun `only a tombstoned blank token clears the session`() {
        assertTrue(SimklAuthRules.clearsSession(null, signedOut = true))
        assertTrue(SimklAuthRules.clearsSession("", signedOut = true))
        // Legacy/blank rows written before the tombstone existed must be
        // inert: they used to wipe a healthy session on every pull.
        assertFalse("blank row without the tombstone must be ignored", SimklAuthRules.clearsSession("", signedOut = false))
        assertFalse(SimklAuthRules.clearsSession(null, signedOut = false))
        // And a real token never clears.
        assertFalse(SimklAuthRules.clearsSession("tok", signedOut = true))
    }
}
