package com.kennyb1201.kbstream.data.watched

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A marker-set refresh keeps what it could not replace.
 *
 * The failure this pins: every Simkl fetch ended in `catch { emptySet() }`, and
 * that blank was written into the live sets — one timeout erased every
 * Simkl-only checkmark on screen for the whole TTL, and re-ran the full
 * all-shows fetch on every preload until one succeeded.
 */
class SimklMarkerRefreshRulesTest {

    private val watched = setOf("tt0903747", "tt0111161")

    @Test
    fun `a successful fetch is adopted`() = runBlocking {
        val attempt = SimklMarkerRefreshRules.keepPreviousOnFailure(watched) {
            setOf("tt0133093")
        }

        assertEquals(setOf("tt0133093"), attempt.keys)
        assertNull(attempt.failure)
        assertFalse(attempt.failed)
    }

    @Test
    fun `a genuinely empty answer is adopted, not treated as a failure`() = runBlocking {
        // An account that has watched nothing is a successful fetch. Refusing
        // this would leave removed titles marked forever.
        val attempt = SimklMarkerRefreshRules.keepPreviousOnFailure(watched) { emptySet() }

        assertTrue(attempt.keys.isEmpty())
        assertFalse(attempt.failed)
    }

    @Test
    fun `a failed fetch keeps the set this device already had`() = runBlocking {
        val attempt = SimklMarkerRefreshRules.keepPreviousOnFailure(watched) {
            throw IOException("timeout")
        }

        assertEquals(watched, attempt.keys)
        assertTrue(attempt.failed)
        assertEquals("timeout", attempt.failure?.message)
    }

    @Test
    fun `the fallback is the caller's own set, not a copy it can mutate`() = runBlocking {
        val previous = mutableSetOf("tt0903747")

        val attempt = SimklMarkerRefreshRules.keepPreviousOnFailure(previous) {
            throw IOException("offline")
        }

        assertTrue(previous === attempt.keys)
    }

    @Test
    fun `cancellation is rethrown instead of reported as a Simkl failure`() = runBlocking {
        // A profile switch cancels the preload. Reporting that as "Simkl is
        // down" would keep the TTL unstamped and refetch, and would put a
        // cancellation in the error log with no underlying fault.
        var thrown: Throwable? = null
        try {
            SimklMarkerRefreshRules.keepPreviousOnFailure(watched) {
                throw CancellationException("profile switched")
            }
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue("cancellation must escape", thrown is CancellationException)
    }
}
