package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
