package com.kennyb1201.kbstream.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-panel popup point: stored in tenths of a percent so the settings
 * screen can nudge it in 0.5 steps.
 *
 * The bug these pin down: the pref used to hold a whole percent (90..99) under
 * the same key. A build that reads the old number as tenths would put the point
 * at 9.8% — the panel would open nine minutes into an episode. The migration
 * below is the only thing standing between an upgrade and that.
 */
class EndPanelPointTest {

    // ── migration from the whole-percent values older builds wrote ───

    @Test
    fun `a legacy whole percent scales to the point that user chose`() {
        assertEquals(990, AppPreferences.endPanelPointTenthsFromStored(99))
        assertEquals(980, AppPreferences.endPanelPointTenthsFromStored(98))
        assertEquals(900, AppPreferences.endPanelPointTenthsFromStored(90))
    }

    @Test
    fun `an already-tenths value passes through untouched`() {
        // Everything a tenths build can store is >= MIN (800), so a value that
        // is not a legacy percent must not be scaled a second time.
        assertEquals(980, AppPreferences.endPanelPointTenthsFromStored(980))
        assertEquals(985, AppPreferences.endPanelPointTenthsFromStored(985))
        assertEquals(990, AppPreferences.endPanelPointTenthsFromStored(990))
        assertEquals(800, AppPreferences.endPanelPointTenthsFromStored(800))
    }

    @Test
    fun `an absent or nonsense value lands in the band`() {
        // An unset key is read as the default before it gets here, but a synced
        // or hand-edited value can still be out of range.
        assertEquals(
            AppPreferences.DEFAULT_END_PANEL_POINT_TENTHS,
            AppPreferences.endPanelPointTenthsFromStored(
                AppPreferences.DEFAULT_END_PANEL_POINT_TENTHS
            )
        )
        assertEquals(
            AppPreferences.MIN_END_PANEL_POINT_TENTHS,
            AppPreferences.endPanelPointTenthsFromStored(0)
        )
        assertEquals(
            AppPreferences.MAX_END_PANEL_POINT_TENTHS,
            AppPreferences.endPanelPointTenthsFromStored(5_000)
        )
    }

    @Test
    fun `every quick jump the settings screen offers is inside the band`() {
        // The chips map whole percents onto tenths, so an option outside the
        // band would render a point the pref refuses to store.
        AppPreferences.END_PANEL_PERCENT_OPTIONS.forEach { percent ->
            val tenths = AppPreferences.endPanelPointTenthsFromStored(percent)
            assertTrue(
                "quick jump ${percent}% lands outside the band",
                tenths in AppPreferences.MIN_END_PANEL_POINT_TENTHS..
                    AppPreferences.MAX_END_PANEL_POINT_TENTHS
            )
            assertEquals(percent * 10, tenths)
        }
    }

    // ── the 0.5-step control ─────────────────────────────────────────

    @Test
    fun `a step moves half a percent`() {
        assertEquals(985, AppPreferences.stepEndPanelPoint(980, 1))
        assertEquals(975, AppPreferences.stepEndPanelPoint(980, -1))
        assertEquals(980, AppPreferences.stepEndPanelPoint(975, 1))
    }

    @Test
    fun `a step stops at the band instead of running past it`() {
        assertEquals(
            AppPreferences.MAX_END_PANEL_POINT_TENTHS,
            AppPreferences.stepEndPanelPoint(AppPreferences.MAX_END_PANEL_POINT_TENTHS, 1)
        )
        assertEquals(
            AppPreferences.MIN_END_PANEL_POINT_TENTHS,
            AppPreferences.stepEndPanelPoint(AppPreferences.MIN_END_PANEL_POINT_TENTHS, -1)
        )
        // A jump that would cross the band still lands exactly on the edge.
        assertEquals(
            AppPreferences.MIN_END_PANEL_POINT_TENTHS,
            AppPreferences.stepEndPanelPoint(985, -100)
        )
        assertEquals(
            AppPreferences.MAX_END_PANEL_POINT_TENTHS,
            AppPreferences.stepEndPanelPoint(985, 100)
        )
    }

    // ── how the point reads ──────────────────────────────────────────

    @Test
    fun `a whole point drops the decimal and a half point keeps it`() {
        assertEquals("98%", AppPreferences.endPanelPointLabel(980))
        assertEquals("99%", AppPreferences.endPanelPointLabel(990))
        assertEquals("80%", AppPreferences.endPanelPointLabel(800))
        assertEquals("98.5%", AppPreferences.endPanelPointLabel(985))
        assertEquals("97.5%", AppPreferences.endPanelPointLabel(975))
    }

    @Test
    fun `a label out of range reads as the edge it is clamped to`() {
        // The label must never show a point the control cannot be sitting on.
        assertEquals("99%", AppPreferences.endPanelPointLabel(9_900))
        assertEquals("80%", AppPreferences.endPanelPointLabel(1))
    }
}
