package com.kennyb1201.kbstream.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trim's target arithmetic (spec 2026-11-15 round two F4; ordering revised
 * by dig four, 2026-11-16, §3c).
 *
 * The ORDER is no longer computed here - Coil's cache is an LRU and
 * `trimToSize` evicts eldest-first, so the old hero-first/biggest-first list is
 * gone and the renderer's own order does the work. What remains testable
 * without a Context, a loader or a bitmap is how much a trim gives back, and
 * that is what these cases pin. The wiring - that the trim is `trimToSize` and
 * nothing else - is pinned by [LruTrimWiringContractTest].
 */
class ImageCacheTrimTest {

    @Test
    fun `a trim gives back half of what is resident`() {
        assertEquals(
            "32 MB resident, 16 MB target - the same half the graded trim aimed for",
            16_000_000L,
            ImageCacheTrim.targetBytes(32_000_000L)
        )
    }

    @Test
    fun `the fraction is the knob`() {
        assertEquals(
            "a quarter back, as a caller may ask for",
            7_500_000L,
            ImageCacheTrim.targetBytes(10_000_000L, targetFraction = 0.25)
        )
        assertEquals(
            "giving everything back targets zero, which is a clear by another name",
            0L,
            ImageCacheTrim.targetBytes(10_000_000L, targetFraction = 1.0)
        )
        assertEquals(
            "asking for nothing back leaves the target where it was",
            10_000_000L,
            ImageCacheTrim.targetBytes(10_000_000L, targetFraction = 0.0)
        )
    }

    @Test
    fun `nothing resident and nonsense fractions are safe`() {
        assertEquals(
            "an empty cache has nothing to give back",
            0L,
            ImageCacheTrim.targetBytes(0L)
        )
        assertEquals(
            "a negative resident cannot ask for a negative target",
            0L,
            ImageCacheTrim.targetBytes(-5L)
        )
        assertTrue(
            "an out-of-range fraction is clamped rather than trusted",
            ImageCacheTrim.targetBytes(1_000L, targetFraction = 4.0) == 0L &&
                ImageCacheTrim.targetBytes(1_000L, targetFraction = -2.0) == 1_000L
        )
    }

    @Test
    fun `the default fraction is the half this build ships`() {
        assertEquals(
            "the graded response the TCL's pastes were read against",
            0.5,
            ImageCacheTrim.TARGET_FRACTION,
            0.0
        )
    }
}
