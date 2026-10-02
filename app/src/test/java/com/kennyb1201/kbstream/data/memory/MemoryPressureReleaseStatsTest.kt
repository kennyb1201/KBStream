package com.kennyb1201.kbstream.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release counters exist so a field capture can prove the hardening fired
 * instead of assuming it: "the app got tight and nothing came back" and "the
 * app got tight and 40 MB came back" look identical from the outside, and the
 * constrained device is the only place either can be observed.
 *
 * [MemoryPressure] is a process-wide object with no reset, so every case reads a
 * baseline first and asserts the *delta* — an absolute count would be at the
 * mercy of whichever other test ran first in the same JVM.
 */
class MemoryPressureReleaseStatsTest {

    @Test
    fun `releasing browsing caches is counted`() {
        val before = browsingCount()

        MemoryPressure.releaseBrowsingCaches()

        assertEquals(before + 1, browsingCount())
    }

    @Test
    fun `the image path is counted`() {
        val before = imageCount()

        MemoryPressure.noteImageCacheReleased()

        assertEquals(before + 1, imageCount())
    }

    @Test
    fun `a counted release surfaces a line naming both paths`() {
        MemoryPressure.releaseBrowsingCaches()
        MemoryPressure.noteImageCacheReleased()

        val line = MemoryPressure.releaseStatsLine()
        assertNotNull("a session that released something must report it", line)
        assertTrue("the line must name the image count: $line", line!!.contains("images="))
        assertTrue("the line must name the browsing count: $line", line.contains("browsing="))
    }

    /** The image count in the current line, 0 when there is no line yet. */
    private fun imageCount(): Int = count("images")

    /** The browsing count in the current line, 0 when there is no line yet. */
    private fun browsingCount(): Int = count("browsing")

    private fun count(key: String): Int {
        val line = MemoryPressure.releaseStatsLine() ?: return 0
        return Regex("$key=(\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: 0
    }
}
