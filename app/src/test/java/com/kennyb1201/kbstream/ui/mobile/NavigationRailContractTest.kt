package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2: on an expanded window the five top-level destinations move to a left rail.
 *
 * The arithmetic (`navigationRailApplies` past 840dp, Compact/Medium answering
 * false) is `MobileAdaptiveRulesTest`'s. What is pinned here is the shell wiring,
 * which is the part that can regress silently: the rail has to appear exactly
 * where the bar did, the bar has to withdraw when it does, and both hosts have to
 * be driven by the same destinations so a sixth item cannot land on one of them.
 */
class NavigationRailContractTest {

    private val shell: String by lazy { stripComments(source(MAIN_ACTIVITY)) }
    private val nav: String by lazy { stripComments(source(MOBILE_BOTTOM_NAV)) }

    @Test
    fun `the shell asks the shared rule, and only where the bar would be`() {
        assertTrue(
            "the shell no longer asks navigationRailApplies, so an expanded " +
                "window keeps the bottom bar",
            shell.contains("MobileAdaptiveRules.navigationRailApplies(rememberMobileWindowWidthClass())")
        )
        assertTrue(
            "the rail must be gated on a destination the way the bar is - a " +
                "television (mobileNavDestination null) and every pushed screen " +
                "must never see it",
            shell.contains("val navigationRail = mobileNavDestination != null &&")
        )
        assertTrue(
            "and the bar must stand down while the rail is up, or the window " +
                "carries two copies of the same five items",
            shell.contains("visible = mobileNavDestination != null && !navigationRail")
        )
    }

    @Test
    fun `the rail is hosted by the shell, beside the screen`() {
        val railHost = shell.indexOf("if (navigationRail) {")
        val screen = shell.indexOf("Box(modifier = Modifier.weight(1f).fillMaxHeight()")
        val row = shell.indexOf("Row(modifier = Modifier.weight(1f).fillMaxWidth()) {")
        assertTrue("the shell no longer composes the rail", railHost > 0)
        assertTrue("the shell must open a content row for the rail", row > 0)
        assertTrue(
            "the rail belongs between the row and the screen box: it is a " +
                "sibling of the screen, not an overlay over it",
            row < railHost && railHost < screen
        )
        assertTrue(
            "the screen box takes the width the rail leaves",
            screen > 0
        )
    }

    @Test
    fun `the rail draws the bar's own destinations`() {
        assertEquals(
            "both hosts must iterate MobileNavDestination.entries - a list " +
                "written out twice is how the two navs drift apart",
            2,
            Regex("MobileNavDestination\\.entries\\.forEach").findAll(nav).count()
        )
        assertTrue(
            "the rail takes its width from the rule, not a literal",
            nav.contains("MobileAdaptiveRules.NAVIGATION_RAIL_WIDTH_DP.dp")
        )
        assertTrue(
            "the rail must style its items like the bar's",
            nav.contains("NavigationRailItemDefaults.colors(") &&
                nav.contains("indicatorColor = KBAccent.copy(alpha = 0.16f)")
        )
        assertEquals(
            "the profile door is one button - switching identity is not a " +
                "sixth destination on either host",
            2,
            Regex("MobileProfileButton\\(onClick = onSwitchProfile\\)").findAll(nav).count()
        )
    }

    @Test
    fun `a pushed screen and a television still get no navigation`() {
        assertTrue(
            "television keeps its rail-less shell",
            shell.contains("if (FormFactor.isTv) null else mobileNavSelection(screen)")
        )
        assertTrue(
            "anything reached from a tab (Detail, a grid, the player) is not a " +
                "destination, so neither host draws",
            nav.contains("else -> null")
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun findMainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val MOBILE_BOTTOM_NAV = "com/kennyb1201/kbstream/ui/mobile/MobileBottomNav.kt"
    }
}
