package com.kennyb1201.kbstream.ui.mobile

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.kennyb1201.kbstream.FormFactor

/**
 * The window width class the mobile layouts switch on (spec 2026-11-22, adaptive
 * layouts, F1).
 *
 * Three values, and the breakpoints are the contract: Compact under 600dp,
 * Medium 600-840dp, Expanded at 840dp and up. This is
 * `androidx.window.sizeclass`'s own vocabulary spelled as a local enum, on
 * purpose: every decision below it ([MobileAdaptiveRules]) is a pure function of
 * this value and two pixel counts, so the boundaries are pinned by a JVM test
 * instead of by a device. Only [rememberMobileWindowWidthClass] touches the
 * platform.
 */
internal enum class MobileWidthClass {
    Compact,
    Medium,
    Expanded
}

/**
 * Every size-class decision the phone and tablet surfaces make.
 *
 * Pure by construction - no CompositionLocal, no Context, no Dp arithmetic
 * against a window - following the [MobileDetailRules] / `mobileGridColumns`
 * pattern this file's neighbours already use. A rule that could not be called
 * from a unit test would not be a rule, it would be a layout.
 *
 * The most important property here is the one the spec's whole acceptance rests
 * on: every Compact answer is today's constant. A phone that never crosses
 * 600dp must render pixel-for-pixel what it rendered before this file existed.
 */
internal object MobileAdaptiveRules {

    /** Below this the window is a phone. */
    const val COMPACT_MAX_WIDTH_DP = 600

    /** At or above this the window is a tablet in landscape, or a desktop window. */
    const val EXPANDED_MIN_WIDTH_DP = 840

    /** The width class a plain dp count falls in. */
    fun widthClassFor(widthDp: Int): MobileWidthClass = when {
        widthDp < COMPACT_MAX_WIDTH_DP -> MobileWidthClass.Compact
        widthDp < EXPANDED_MIN_WIDTH_DP -> MobileWidthClass.Medium
        else -> MobileWidthClass.Expanded
    }

    /**
     * Home's billboard height, or 0 for "keep today's `aspectRatio(16f / 11f)`".
     *
     * A phone sizes the hero by its width and that is right on a 400dp window: a
     * full-bleed 16:10 banner is the top third of the screen. The same rule on an
     * 800dp tablet portrait asks for a 500dp hero - most of the window, with no
     * rail below it - which is exactly the failure the TV's fixed `heroHeight`
     * exists to avoid. So above Compact the height is the smaller of the 16:10
     * width slice and HALF the window's height: the cap that keeps a rail on
     * screen matches the taller frame the phone draws (2026-11-25, 45% -> 50%),
     * so the banner does not go back to being a strip the moment the window is
     * wide enough for the rule to apply.
     */
    fun homeBillboardHeightDp(widthClass: MobileWidthClass, widthDp: Int, heightDp: Int): Int {
        if (widthClass == MobileWidthClass.Compact) return 0
        val sixteenByTen = (widthDp * 10f / 16f).toInt()
        val halfTheWindow = (heightDp * 50 / 100)
        return minOf(sixteenByTen, halfTheWindow)
    }

    /**
     * The width Home's content column may take, or 0 for "as wide as the window".
     *
     * Expanded only: on a 13" tablet the rails run edge to edge otherwise, and a
     * poster row 1280dp wide is a wall rather than a shelf. Medium - a tablet in
     * portrait - still wants the whole width it has.
     */
    fun homeContentMaxWidthDp(widthClass: MobileWidthClass): Int =
        if (widthClass == MobileWidthClass.Expanded) 1200 else 0

    /**
     * The most poster columns a grid may show.
     *
     * Compact keeps today's 8: a phone window cannot reach the cap anyway (the
     * fit is width-derived and several columns short of it), so the value is
     * unchanged where it is unreachable. Above Compact the cap is what stops a
     * wide window turning a poster grid into a contact sheet.
     */
    fun gridColumnCap(widthClass: MobileWidthClass): Int = when (widthClass) {
        MobileWidthClass.Compact -> 8
        MobileWidthClass.Medium -> 10
        MobileWidthClass.Expanded -> 12
    }

    /**
     * Browse-chip columns under Search.
     *
     * Re-expresses the ad-hoc `screenWidthDp >= 600` breakpoint that was here
     * before: Compact 3, Medium 5 (the same number the old check produced), and
     * one more step for Expanded.
     */
    fun searchChipColumns(widthClass: MobileWidthClass): Int = when (widthClass) {
        MobileWidthClass.Compact -> 3
        MobileWidthClass.Medium -> 5
        MobileWidthClass.Expanded -> 7
    }

    /** The query field's width cap, or 0 for "as wide as the content". */
    fun searchFieldMaxWidthDp(widthClass: MobileWidthClass): Int =
        if (widthClass == MobileWidthClass.Expanded) 640 else 0

    /** Search's content cap: the same 1200dp shelf rule Home uses, Expanded only. */
    fun searchContentMaxWidthDp(widthClass: MobileWidthClass): Int =
        homeContentMaxWidthDp(widthClass)

    /**
     * Detail's single-column cap, or 0 for "full width".
     *
     * Medium only. 720dp is where an overview paragraph stops being a line and
     * starts being a scan, and it is narrower than the 840dp the class starts at,
     * so it binds. Expanded does not use this: it gets two panes instead
     * ([detailTwoPaneApplies]).
     */
    fun detailColumnMaxWidthDp(widthClass: MobileWidthClass): Int =
        if (widthClass == MobileWidthClass.Medium) 720 else 0

    /** Expanded Detail is list-detail: art/CTA/facts left, everything else right. */
    fun detailTwoPaneApplies(widthClass: MobileWidthClass): Boolean =
        widthClass == MobileWidthClass.Expanded

    /** The fixed left pane's width in the two-pane Detail. */
    const val DETAIL_LEFT_PANE_WIDTH_DP = 420

    /** Expanded Settings shows the list beside the pane instead of pushing it. */
    fun settingsTwoPaneApplies(widthClass: MobileWidthClass): Boolean =
        widthClass == MobileWidthClass.Expanded

    /** The fixed list width in the two-pane Settings. */
    const val SETTINGS_LIST_WIDTH_DP = 320

    /** Expanded top-level screens move the bottom bar to a left rail. */
    fun navigationRailApplies(widthClass: MobileWidthClass): Boolean =
        widthClass == MobileWidthClass.Expanded

    /** The rail's width in the expanded shell. */
    const val NAVIGATION_RAIL_WIDTH_DP = 80

    /**
     * The player's wide-window chrome stage (F9): full-frame video with the
     * bottom control cluster capped and centred.
     *
     * `isTv` is a parameter rather than a read so the rule is testable and so the
     * TV answer is stated where it matters: a television never gets this stage,
     * whatever its window reports. Below Expanded the portrait stage and the
     * ordinary chrome own the layout, unchanged.
     */
    fun playerWideStageApplies(isTv: Boolean, windowWidthDp: Int): Boolean =
        !isTv && windowWidthDp >= EXPANDED_MIN_WIDTH_DP

    /** The wide-window chrome cluster's cap. */
    const val PLAYER_CHROME_MAX_WIDTH_DP = 960
}

/**
 * The window's width class, resolved once per composition of a mobile screen.
 *
 * The TV guard is the FIRST line and it reads [FormFactor], not the window: on a
 * television this returns Compact before the platform is asked anything. Mobile
 * screens are never composed on a TV - the dispatch at the top of each TV screen
 * entry keeps them out - so this is belt-and-braces, and it is the brace that
 * matters: a television reports an Expanded window, so a future mis-dispatch
 * would otherwise hand a TV the tablet layout. [FormFactor] answers "TV" when it
 * has not been resolved, which is the same direction of failure.
 *
 * The platform value is preferred when a host activity can answer, because it is
 * the system's own view of the window (it knows about multi-window and fold
 * bounds). `widthClassFor` is the fallback, and the two agree by construction -
 * the material3 classes use the same 600/840dp boundaries, which is what
 * [MobileAdaptiveRulesTest] pins from this side.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
internal fun rememberMobileWindowWidthClass(): MobileWidthClass {
    if (FormFactor.isTv) return MobileWidthClass.Compact
    val activity = LocalActivity.current
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    // `calculateWindowSizeClass` is itself a composition read, so it cannot sit
    // inside a runCatching block or a remember lambda - the compiler rejects a
    // composable call in either. Asked here, unconditionally, only when a host
    // activity exists (the condition is fixed for the life of this screen, so
    // the slot is stable), and everything else stays in the remembered block.
    val platformWidth = if (activity != null) {
        calculateWindowSizeClass(activity).widthSizeClass
    } else {
        null
    }
    return remember(platformWidth, windowWidthDp) {
        platformWidth?.let(::mobileWidthClassOf)
            ?: MobileAdaptiveRules.widthClassFor(windowWidthDp)
    }
}

/**
 * The platform's width class as this app's own.
 *
 * Kept out of [MobileAdaptiveRules] on purpose: that object is the pure half and
 * must stay loadable by a plain JVM test, and this is the only line in the app
 * that names a `WindowWidthSizeClass`.
 */
internal fun mobileWidthClassOf(width: WindowWidthSizeClass): MobileWidthClass = when (width) {
    WindowWidthSizeClass.Compact -> MobileWidthClass.Compact
    WindowWidthSizeClass.Medium -> MobileWidthClass.Medium
    WindowWidthSizeClass.Expanded -> MobileWidthClass.Expanded
    else -> MobileWidthClass.Compact
}
