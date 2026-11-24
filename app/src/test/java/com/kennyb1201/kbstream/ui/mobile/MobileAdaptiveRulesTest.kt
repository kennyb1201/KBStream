package com.kennyb1201.kbstream.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive layout rules (spec 2026-11-22, adaptive layouts, F1/F4/F5).
 *
 * Two things make this spec safe to land on a phone that must not change: the
 * breakpoints are the contract, and every Compact answer is today's constant.
 * Both are pinned here, at the boundaries where a rounding mistake hides -
 * 599/600 and 839/840.
 */
class MobileAdaptiveRulesTest {

    @Test
    fun `the width classes break at 600 and 840`() {
        assertEquals(MobileWidthClass.Compact, MobileAdaptiveRules.widthClassFor(599))
        assertEquals(MobileWidthClass.Medium, MobileAdaptiveRules.widthClassFor(600))
        assertEquals(MobileWidthClass.Medium, MobileAdaptiveRules.widthClassFor(839))
        assertEquals(MobileWidthClass.Expanded, MobileAdaptiveRules.widthClassFor(840))
        // The phone and the two tablet orientations, spelled out, because these
        // are the walks the acceptance names.
        assertEquals(MobileWidthClass.Compact, MobileAdaptiveRules.widthClassFor(360))
        assertEquals(MobileWidthClass.Medium, MobileAdaptiveRules.widthClassFor(800))
        assertEquals(MobileWidthClass.Expanded, MobileAdaptiveRules.widthClassFor(1280))
    }

    @Test
    fun `compact answers are today's constants`() {
        // The phone must render what it rendered before this file existed, so
        // every Compact branch is the value the old code used.
        assertEquals(0, MobileAdaptiveRules.homeBillboardHeightDp(MobileWidthClass.Compact, 400, 800))
        assertEquals(0, MobileAdaptiveRules.homeContentMaxWidthDp(MobileWidthClass.Compact))
        assertEquals(8, MobileAdaptiveRules.gridColumnCap(MobileWidthClass.Compact))
        assertEquals(3, MobileAdaptiveRules.searchChipColumns(MobileWidthClass.Compact))
        assertEquals(0, MobileAdaptiveRules.searchFieldMaxWidthDp(MobileWidthClass.Compact))
        assertEquals(0, MobileAdaptiveRules.detailColumnMaxWidthDp(MobileWidthClass.Compact))
        assertFalse(MobileAdaptiveRules.detailTwoPaneApplies(MobileWidthClass.Compact))
        assertFalse(MobileAdaptiveRules.settingsTwoPaneApplies(MobileWidthClass.Compact))
        assertFalse(MobileAdaptiveRules.navigationRailApplies(MobileWidthClass.Compact))
        assertFalse(MobileAdaptiveRules.playerWideStageApplies(isTv = false, windowWidthDp = 599))
    }

    @Test
    fun `the billboard is capped at 45 percent of a wide window's height`() {
        // 800dp tablet portrait, 1280dp tall: 16:10 asks for 500dp, the cap
        // gives 576 - so the cap does not bind and the hero stays 16:10.
        assertEquals(500, MobileAdaptiveRules.homeBillboardHeightDp(MobileWidthClass.Medium, 800, 1280))
        // 1280dp landscape, 800dp tall: 16:10 asks for 800dp, 45% gives 360.
        assertEquals(360, MobileAdaptiveRules.homeBillboardHeightDp(MobileWidthClass.Expanded, 1280, 800))
        // A short window binds too.
        assertEquals(225, MobileAdaptiveRules.homeBillboardHeightDp(MobileWidthClass.Medium, 800, 500))
    }

    @Test
    fun `Home and Search cap their content only when expanded`() {
        assertEquals(0, MobileAdaptiveRules.homeContentMaxWidthDp(MobileWidthClass.Medium))
        assertEquals(1200, MobileAdaptiveRules.homeContentMaxWidthDp(MobileWidthClass.Expanded))
        assertEquals(1200, MobileAdaptiveRules.searchContentMaxWidthDp(MobileWidthClass.Expanded))
        assertEquals(0, MobileAdaptiveRules.searchFieldMaxWidthDp(MobileWidthClass.Medium))
        assertEquals(640, MobileAdaptiveRules.searchFieldMaxWidthDp(MobileWidthClass.Expanded))
    }

    @Test
    fun `grid caps rise with the class and never fall below a phone's`() {
        assertEquals(8, MobileAdaptiveRules.gridColumnCap(MobileWidthClass.Compact))
        assertEquals(10, MobileAdaptiveRules.gridColumnCap(MobileWidthClass.Medium))
        assertEquals(12, MobileAdaptiveRules.gridColumnCap(MobileWidthClass.Expanded))
    }

    @Test
    fun `chip columns keep the old 600dp step and add one`() {
        // The ad-hoc `>= 600 -> 5 else 3` this replaces.
        assertEquals(3, MobileAdaptiveRules.searchChipColumns(MobileWidthClass.Compact))
        assertEquals(5, MobileAdaptiveRules.searchChipColumns(MobileWidthClass.Medium))
        assertEquals(7, MobileAdaptiveRules.searchChipColumns(MobileWidthClass.Expanded))
    }

    @Test
    fun `Detail caps its column at medium and splits at expanded`() {
        assertEquals(720, MobileAdaptiveRules.detailColumnMaxWidthDp(MobileWidthClass.Medium))
        assertEquals(0, MobileAdaptiveRules.detailColumnMaxWidthDp(MobileWidthClass.Expanded))
        assertFalse(MobileAdaptiveRules.detailTwoPaneApplies(MobileWidthClass.Medium))
        assertTrue(MobileAdaptiveRules.detailTwoPaneApplies(MobileWidthClass.Expanded))
    }

    @Test
    fun `Settings and the shell switch at expanded, not medium`() {
        assertFalse(MobileAdaptiveRules.settingsTwoPaneApplies(MobileWidthClass.Medium))
        assertTrue(MobileAdaptiveRules.settingsTwoPaneApplies(MobileWidthClass.Expanded))
        assertFalse(MobileAdaptiveRules.navigationRailApplies(MobileWidthClass.Medium))
        assertTrue(MobileAdaptiveRules.navigationRailApplies(MobileWidthClass.Expanded))
    }

    @Test
    fun `the wide player stage never applies on a TV`() {
        // A television reports an expanded window in landscape. The rule is
        // asked with isTv as a parameter so this answer cannot depend on a
        // window at all.
        assertFalse(MobileAdaptiveRules.playerWideStageApplies(isTv = true, windowWidthDp = 1920))
        assertFalse(MobileAdaptiveRules.playerWideStageApplies(isTv = true, windowWidthDp = 840))
        assertTrue(MobileAdaptiveRules.playerWideStageApplies(isTv = false, windowWidthDp = 840))
        assertFalse(MobileAdaptiveRules.playerWideStageApplies(isTv = false, windowWidthDp = 839))
        assertEquals(960, MobileAdaptiveRules.PLAYER_CHROME_MAX_WIDTH_DP)
    }
}
