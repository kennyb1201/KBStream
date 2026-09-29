package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PerfTrace.recordLaunch] exists because a launch figure is elapsed-PROCESS
 * time. Recorded again from a re-created activity it reports the whole session
 * as a startup cost, which is not theoretical: a capture with 7 minutes of
 * playback on it showed `startup.mainCreate=441900ms` heading both the
 * diagnostics startup breakdown and the slowest-items list, against a real cold
 * start of 118ms in that same capture. The contract worth pinning is the
 * refusal — the second call must not land.
 *
 * [PerfTrace] is a process-wide object, so each case uses its own label.
 */
class PerfTraceTest {

    @Test
    fun `a launch figure is recorded once`() {
        val label = "startup.testOnce"

        assertTrue(PerfTrace.recordLaunch(label, 118L))
        assertFalse(PerfTrace.recordLaunch(label, 441_900L))

        assertEquals(1, PerfTrace.count(label))
        assertEquals(118L, PerfTrace.latestByPrefix(label).first().second)
    }

    @Test
    fun `a refused launch figure cannot replace the recorded one`() {
        val label = "startup.testRefused"

        PerfTrace.recordLaunch(label, 120L)
        PerfTrace.recordLaunch(label, 439_164L)

        assertEquals("the newer, bogus figure must not be what the report shows", 120L, PerfTrace.maxMs(label))
    }

    /**
     * `http.ip` is shared by every bare address, so the label on its own says
     * only that time went somewhere unnamed. The host recorded beside it is what
     * makes the slowest endpoint fixable.
     *
     * These two cases record a sample large enough to rank in the summary's top
     * six whatever else this JVM recorded, and deliberately do NOT call
     * `reset()`: that would erase samples the launch cases in this class assert
     * on, and JUnit gives no order between them.
     */
    @Test
    fun `the host behind an unnamed label is named in the summary`() {
        val label = "http.ipTestHost"
        PerfTrace.recordHost(label, "132.4.9.11")
        PerfTrace.record(label, 9_000_000L)

        val line = PerfTrace.summary().lineSequence()
            .firstOrNull { it.startsWith("perf· $label ") }

        assertNotNull("the label must be ranked in the summary", line)
        assertTrue("the host must be named: $line", line!!.endsWith("host=132.4.9.11"))
    }

    @Test
    fun `a label that names its own destination carries no host`() {
        // `http.tmdb` already says where the call went; appending a host to it
        // would be noise on every line of the report.
        val label = "http.tmdbTestHost"
        PerfTrace.record(label, 9_000_001L)

        assertNull(PerfTrace.hostFor(label))
        val line = PerfTrace.summary().lineSequence()
            .firstOrNull { it.startsWith("perf· $label ") }

        assertNotNull("the label must be ranked in the summary", line)
        assertFalse("no host should be appended: $line", line!!.contains("host="))
    }

    @Test
    fun `clearing diagnostics does not re-arm the launch figure`() {
        val label = "startup.testAfterReset"

        assertTrue(PerfTrace.recordLaunch(label, 120L))
        PerfTrace.reset()

        // The ring is empty, but the process has still already launched: the
        // next create is a re-create, so its elapsed time is not a startup.
        assertFalse(PerfTrace.recordLaunch(label, 441_900L))
        assertEquals(0, PerfTrace.count(label))
    }
}
