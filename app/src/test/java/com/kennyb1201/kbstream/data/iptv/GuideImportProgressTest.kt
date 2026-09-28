package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The guide import's status line.
 *
 * Reported from the field: IMPORT EPG sat on "IMPORTING..." for five minutes
 * with no way to tell a slow import from a wedged one. These pin what the
 * screen can now say, and that a pass which is not running says nothing.
 */
class GuideImportProgressTest {

    /** Fails an assertion after another test left a pass open. */
    @Before
    fun reset() {
        GuideImportProgress.finish()
    }

    private fun label(state: GuideImportState?, nowMs: Long) =
        GuideImportProgress.label(state, nowMs)

    private val start = 1_700_000_000_000L

    @Test
    fun `no import shows nothing`() {
        assertEquals("", label(GuideImportProgress.state.value, start))
        assertNull(GuideImportProgress.state.value)
    }

    @Test
    fun `a fresh pass reads zeroes rather than nothing`() {
        GuideImportProgress.begin(sourceCount = 1, startedAtMs = start)

        assertEquals(
            "0.0 MB · 0 programmes · 0:00",
            label(GuideImportProgress.state.value, start)
        )
    }

    @Test
    fun `a single source reports bytes, rows and the clock`() {
        GuideImportProgress.begin(sourceCount = 1, startedAtMs = start)
        GuideImportProgress.bytes(35_861_299L) // ~34.2 MB
        GuideImportProgress.rows(128_400)

        assertEquals(
            "34.2 MB · 128,400 programmes · 4:12",
            label(GuideImportProgress.state.value, start + 252_000L)
        )
    }

    @Test
    fun `a multi-source import names the source it is on`() {
        GuideImportProgress.begin(sourceCount = 3, startedAtMs = start)
        GuideImportProgress.startSource(2)
        GuideImportProgress.bytes(1_048_576L)
        GuideImportProgress.rows(12)

        assertEquals(
            "source 2/3 · 1.0 MB · 12 programmes · 0:05",
            label(GuideImportProgress.state.value, start + 5_000L)
        )
    }

    @Test
    fun `the counters restart with the next source but the clock does not`() {
        GuideImportProgress.begin(sourceCount = 2, startedAtMs = start)
        GuideImportProgress.bytes(52_428_800L) // 50 MB of the first guide
        GuideImportProgress.rows(90_000)

        GuideImportProgress.startSource(2)

        val state = GuideImportProgress.state.value
        assertEquals(2, state?.sourceIndex)
        assertEquals(0L, state?.bytesRead)
        assertEquals(0, state?.rowsParsed)
        assertEquals(GuideImportPhase.READING, state?.phase)
        // Still timed from the start of the pass, not of the source.
        assertEquals(
            "source 2/2 · 0.0 MB · 0 programmes · 6:00",
            label(state, start + 360_000L)
        )
    }

    @Test
    fun `the save phase says what it is saving`() {
        GuideImportProgress.begin(sourceCount = 1, startedAtMs = start)
        GuideImportProgress.rows(128_400)
        GuideImportProgress.phase(GuideImportPhase.SAVING)

        assertEquals(
            "saving 128,400 programmes · 4:40",
            label(GuideImportProgress.state.value, start + 280_000L)
        )
    }

    @Test
    fun `an import past the hour reports hours`() {
        GuideImportProgress.begin(sourceCount = 1, startedAtMs = start)

        assertEquals(
            "0.0 MB · 0 programmes · 1:02:03",
            label(GuideImportProgress.state.value, start + 3_723_000L)
        )
    }

    @Test
    fun `a clock that runs backwards reads zero rather than negative`() {
        GuideImportProgress.begin(sourceCount = 1, startedAtMs = start)

        assertEquals(
            "0.0 MB · 0 programmes · 0:00",
            label(GuideImportProgress.state.value, start - 60_000L)
        )
    }

    @Test
    fun `writes with no pass open are ignored`() {
        GuideImportProgress.bytes(1_048_576L)
        GuideImportProgress.rows(10)
        GuideImportProgress.phase(GuideImportPhase.SAVING)
        GuideImportProgress.startSource(2)

        assertNull(GuideImportProgress.state.value)
        assertEquals("", label(GuideImportProgress.state.value, start))
    }

    @Test
    fun `a source count below one still reads as one source`() {
        GuideImportProgress.begin(sourceCount = 0, startedAtMs = start)

        assertEquals(1, GuideImportProgress.state.value?.sourceCount)
    }

    @Test
    fun `finishing closes the pass`() {
        GuideImportProgress.begin(sourceCount = 2, startedAtMs = start)
        GuideImportProgress.rows(500)

        GuideImportProgress.finish()

        assertNull(GuideImportProgress.state.value)
        assertEquals("", label(GuideImportProgress.state.value, start))
    }
}
