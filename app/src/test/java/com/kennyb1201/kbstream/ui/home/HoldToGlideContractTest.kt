package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Hold to glide" (spec 2026-11-16, F5): the numbers and the landing choice.
 *
 * The behavior itself needs a TV and a remote, but three things can be wrong
 * without one: the constant velocity the glide promises, the ramp that keeps its
 * first frame from lurching, and which item a release lands on. They live in
 * [GlideRules] (no Compose types) so they are pinned here rather than only by a
 * field paste.
 *
 * The velocities are the upstream numbers the spec fixes: horizontal 1600 dp/s,
 * vertical 2000 dp/s (not the original 3200 - rows have to stay legible as they
 * pass), a ramp over the first ~130 ms, and a per-frame dt cap of 0.048 s so a
 * paused frame is not paid back as one jump.
 */
class HoldToGlideContractTest {

    // ── Velocity and ramp ────────────────────────────────────────────────

    @Test
    fun `full speed is the spec's 1600 horizontal and 2000 vertical`() {
        assertEquals(
            "a held Left/Right glides at 1600 dp per second",
            1600f,
            glideDpPerSecond(GlideAxis.Horizontal, GlideRampMs),
            0.001f
        )
        assertEquals(
            "a held Up/Down glides at 2000 dp per second - the legible rate, " +
                "not the original 3200",
            2000f,
            glideDpPerSecond(GlideAxis.Vertical, GlideRampMs),
            0.001f
        )
    }

    @Test
    fun `the speed ramps in over the first 130ms and is constant after`() {
        assertEquals(
            "the first frame is at a standstill - a glide, not a jump",
            0f,
            glideDpPerSecond(GlideAxis.Horizontal, 0L),
            0.001f
        )
        assertEquals(
            "halfway through the ramp is half speed",
            800f,
            glideDpPerSecond(GlideAxis.Horizontal, GlideRampMs / 2),
            0.001f
        )
        assertEquals(
            "130ms in, the glide is at full speed",
            1600f,
            glideDpPerSecond(GlideAxis.Horizontal, GlideRampMs),
            0.001f
        )
        assertEquals(
            "and it stays there however long the hold lasts",
            1600f,
            glideDpPerSecond(GlideAxis.Horizontal, 4_000L),
            0.001f
        )
    }

    // ── The per-frame distance ───────────────────────────────────────────

    @Test
    fun `a frame travels speed times dt`() {
        // 1/60s at full horizontal speed: 1600/60.
        assertEquals(
            1600f / 60f,
            glideDpForFrame(GlideAxis.Horizontal, GlideRampMs, 1f / 60f),
            0.001f
        )
    }

    @Test
    fun `a late frame is capped at 48ms instead of teleporting the list`() {
        val capped = glideDpForFrame(GlideAxis.Vertical, GlideRampMs, 1f)

        assertEquals(
            "a one-second frame may only travel 48ms' worth",
            2000f * 0.048f,
            capped,
            0.001f
        )
        assertEquals(
            "and that is exactly what a frame of the cap's own length travels",
            glideDpForFrame(GlideAxis.Vertical, GlideRampMs, 0.048f),
            capped,
            0.001f
        )
    }

    @Test
    fun `a zero or backwards dt travels nowhere`() {
        assertEquals(
            0f,
            glideDpForFrame(GlideAxis.Horizontal, GlideRampMs, 0f),
            0.001f
        )
        assertEquals(
            0f,
            glideDpForFrame(GlideAxis.Horizontal, GlideRampMs, -0.02f),
            0.001f
        )
    }

    // ── The landing ──────────────────────────────────────────────────────

    @Test
    fun `the release lands on the first FULLY visible item, not a clipped one`() {
        // A row scrolled half a card past its edge: the clipped card at -60 is
        // only partly on screen, so the landing is the one after it.
        val offsets = listOf(-60, 140, 340, 540)
        val sizes = listOf(200, 200, 200, 200)

        assertEquals(
            1,
            glideLandingIndex(offsets, sizes, viewportStart = 0, viewportEnd = 600)
        )
    }

    @Test
    fun `a viewport whose start is padded still lands on a whole item`() {
        // The rails column starts under the hero: items above the viewport's own
        // start are visible to the layout but clipped on screen.
        val offsets = listOf(0, 300, 600, 900)
        val sizes = listOf(300, 300, 300, 300)

        assertEquals(
            2,
            glideLandingIndex(offsets, sizes, viewportStart = 600, viewportEnd = 1200)
        )
    }

    @Test
    fun `when no item fits whole the first visible one lands it`() {
        // A rail shorter than the cards in it: nothing is fully visible, so the
        // release must still land somewhere rather than leave no focused node.
        val offsets = listOf(0)
        val sizes = listOf(900)

        assertEquals(
            0,
            glideLandingIndex(offsets, sizes, viewportStart = 100, viewportEnd = 800)
        )
    }

    @Test
    fun `an empty or inconsistent measurement has no landing`() {
        assertNull(
            "nothing measured, nothing to land on",
            glideLandingIndex(
                emptyList(),
                emptyList(),
                viewportStart = 0,
                viewportEnd = 600
            )
        )
        assertNull(
            "an offset without its size is not a measurement we can use",
            glideLandingIndex(
                listOf(0, 200),
                listOf(200),
                viewportStart = 0,
                viewportEnd = 600
            )
        )
    }
}
