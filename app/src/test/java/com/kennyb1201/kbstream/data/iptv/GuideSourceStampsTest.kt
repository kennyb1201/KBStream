package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule the global freshness marker is derived from: the OLDEST per-source
 * stamp, with a source that has never imported counting as zero.
 *
 * That rule is why one dead source keeps the whole set marked stale — it must
 * retry — and why a newly added source correctly reads as "never imported"
 * instead of inheriting a sibling's credit. The store itself is shared by the
 * worker and the in-app import; only this arithmetic is pure enough to test
 * without Android.
 */
class GuideSourceStampsTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `the marker is the oldest stamp, not the newest`() {
        val stamps = mapOf("a" to now, "b" to now - 5_000L)
        assertEquals(
            "a pass that refreshed one source must not mark its stale sibling fresh",
            now - 5_000L,
            GuideSourceStamps.oldest(stamps, listOf("a", "b"))
        )
    }

    @Test
    fun `a never-imported source is zero, which is always stale`() {
        assertEquals(
            0L,
            GuideSourceStamps.oldest(mapOf("a" to now), listOf("a", "b"))
        )
    }

    @Test
    fun `an empty source list has no marker to derive`() {
        assertEquals(0L, GuideSourceStamps.oldest(mapOf("a" to now), emptyList()))
    }
}
