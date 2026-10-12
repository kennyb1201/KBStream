package com.kennyb1201.kbstream.ui.detail

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded wait a play action does before it names its stream request.
 *
 * The bug this exists for is a race, so the cases that matter are the two
 * middles of it: a press that arrives BEFORE the resolution (the wait has to
 * pick the resolved id up when it lands, not the catalog id it started with)
 * and a resolution that never comes (the wait has to end on the catalog id
 * rather than hold the press). Both run on real coroutines here; the timeouts
 * are short so the suite stays quick.
 */
class StreamRootWaitTest {

    @Test
    fun `an already resolved root is answered straight away`() = runBlocking {
        val started = System.currentTimeMillis()
        assertEquals(
            "tt4873036",
            waitForStreamRootId(
                MutableStateFlow("tt4873036"),
                routeId = "tt3121722",
                timeoutMs = 5_000L
            )
        )
        // The common case: the lookup is cached, so a press does not sit on
        // the budget it was given.
        assertTrue(System.currentTimeMillis() - started < 1_000L)
    }

    @Test
    fun `a root resolved while the press waits is the one used`() = runBlocking {
        val root = MutableStateFlow("")
        launch {
            delay(50L)
            root.value = "tt4873036"
        }
        assertEquals(
            "the press must be named by the root that resolved, not by the id it started with",
            "tt4873036",
            waitForStreamRootId(root, routeId = "tt3121722", timeoutMs = 5_000L)
        )
    }

    @Test
    fun `a blank answer is not an answer`() = runBlocking {
        // The published state is blank until the lookup answers, and the
        // screen's own fallback is the same blank read - so an all-blank flow
        // is what a missing TMDB record, a blank key and an offline device
        // all look like from here.
        val root = MutableStateFlow("   ")
        launch {
            delay(50L)
            root.value = "tt4873036"
        }
        assertEquals(
            "tt4873036",
            waitForStreamRootId(root, routeId = "tt3121722", timeoutMs = 5_000L)
        )
    }

    @Test
    fun `a resolution that never comes falls back to the route id, bounded`() = runBlocking {
        val started = System.currentTimeMillis()
        assertEquals(
            "tt3121722",
            waitForStreamRootId(
                MutableStateFlow(""),
                routeId = "tt3121722",
                timeoutMs = 60L
            )
        )
        // Bounded: the wait ends on the caller's ceiling, and the press plays
        // the catalog id exactly as it did before any of this existed.
        val waited = System.currentTimeMillis() - started
        assertTrue("wait must be bounded by the timeout, waited ${waited}ms", waited < 5_000L)
    }
}
