package com.kennyb1201.kbstream.data.sync

import com.kennyb1201.kbstream.data.sync.KidsTimeGuard.ForegroundTransition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kids-time foreground tracking. The daily-limit clock may only run while the
 * PROCESS is foregrounded, and the count of started Activities is what decides
 * that.
 *
 * This is the guard that matters most in practice: every player is a separate
 * Activity, so the old MainActivity-owned wiring counted "the player opened"
 * as the app going to the background and stopped the clock for the whole of
 * playback. A child who let the Up Next chain keep feeding episodes therefore
 * never spent any of the budget, and the limit never arrived.
 */
class KidsTimeGuardTest {

    @Test
    fun `first started activity foregrounds the app`() {
        assertEquals(
            ForegroundTransition.FOREGROUNDED,
            KidsTimeGuard.foregroundTransition(0, 1)
        )
    }

    @Test
    fun `opening the player on top keeps the app foregrounded`() {
        // MainActivity (1) then the player (2): the clock must not pause.
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(1, 2)
        )
        // A hand-off (native -> mpv, or the external picker) stacks one more.
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(2, 3)
        )
    }

    @Test
    fun `closing the player keeps the app foregrounded`() {
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(2, 1)
        )
    }

    @Test
    fun `last started activity stopping backgrounds the app`() {
        assertEquals(
            ForegroundTransition.BACKGROUNDED,
            KidsTimeGuard.foregroundTransition(1, 0)
        )
        // An unmatched stop must still read as backgrounded, never as a
        // negative count that keeps the clock running forever.
        assertEquals(
            ForegroundTransition.BACKGROUNDED,
            KidsTimeGuard.foregroundTransition(2, 0)
        )
    }

    @Test
    fun `an unchanged count announces nothing`() {
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(0, 0)
        )
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(3, 3)
        )
    }

    /**
     * The persisted day counter is written by the ticker (Dispatchers.Default)
     * and read on the MAIN thread by the lock snapshot and the overlay, so the
     * fields need @Volatile: without it a main-thread read can see the previous
     * day's key with this day's minutes, which shows yesterday's usage and a
     * locked state that never lifts. Pinned at the source because JVM visibility
     * has no observable outcome in a single-threaded unit test.
     */
    @Test
    fun `the ticker's counters are visible across threads`() {
        val file = File(findSourceRoot(),
            "com/kennyb1201/kbstream/data/sync/KidsTimeGuard.kt")
        assertTrue("source missing: $file", file.isFile)
        val src = file.readText()
        listOf("dayKey", "todayMinutes", "profileIdForCounter").forEach { field ->
            assertTrue(
                "$field is written by the ticker and read on the main thread",
                Regex("@Volatile\\s+private var $field\\b").containsMatchIn(src)
            )
        }
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
