package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's row models key on a stamp, not on the program map
 * (perf spec 2026-11-22, item 5).
 *
 * `programsByChannel` is republished by every window merge, and both grids
 * derived their rows in a `remember` keyed on that map. Compose compares keys
 * with `equals`, so each merge walked the whole lineup - five figures of
 * channels, same size on both sides, so every entry - on the main thread before
 * it could decide to re-derive. The ViewModel now stamps the map and the grids
 * key on the stamp, which is one Int compare.
 *
 * The stamp is only honest while a no-op merge keeps its map identity, so that
 * rule is pinned with the stamp and its wiring - at the source, because the
 * merge cannot be built on the JVM test harness without the Room entity it
 * reads.
 */
class GuideProgramsVersionContractTest {

    private val rules = source(GRID_RULES)
    private val viewModel = source(IPTV_VIEW_MODEL)
    private val tvGrid = source(TV_GRID)
    private val tvScreen = source(TV_SCREEN)
    private val mobileGrid = source(MOBILE_GRID)
    private val mobileScreen = source(MOBILE_SCREEN)

    @Test
    fun `a merge that merged nothing keeps its map, so it does not restamp`() {
        assertTrue(
            "an empty answer must not look like a new lineup",
            rules.contains("if (ids.isEmpty()) return existing")
        )
    }

    @Test
    fun `the ViewModel publishes every grid map through the stamper`() {
        assertTrue(
            "the stamp is exposed next to the map it stamps",
            viewModel.contains("internal val gridProgramsVersion: StateFlow<Int> =")
        )
        assertTrue(
            "the helper bumps it only for a new map",
            viewModel.contains("if (next === _gridPrograms.value) return") &&
                viewModel.contains("_gridProgramsVersion.value = _gridProgramsVersion.value + 1")
        )
        assertTrue(
            "both window merges and the clear go through it",
            viewModel.split("publishGridPrograms(").size - 1 == 4 &&
                viewModel.contains("publishGridPrograms(emptyMap())")
        )
        assertTrue(
            "the only direct assignment left is the one inside the stamper",
            viewModel.split("_gridPrograms.value =").size - 1 == 1
        )
    }

    @Test
    fun `both grids remember their rows on the stamp`() {
        listOf(tvGrid to "the TV grid", mobileGrid to "the phone grid").forEach { (grid, name) ->
            assertTrue(
                "$name takes the stamp",
                grid.contains("programsVersion: Int,")
            )
            assertTrue(
                "$name keys its rows on the stamp, not the map",
                grid.contains("remember(items, programsVersion, window) {") &&
                    !grid.contains("remember(items, programsByChannel, window) {")
            )
        }
    }

    @Test
    fun `both screens hand the stamp to their grid`() {
        listOf(
            tvScreen to "the TV screen",
            mobileScreen to "the phone screen"
        ).forEach { (screen, name) ->
            assertTrue(
                "$name collects the stamp",
                screen.contains(
                    "val gridProgramsVersion by viewModel.gridProgramsVersion" +
                        ".collectAsStateWithLifecycle()"
                )
            )
            assertTrue(
                "$name passes it beside the map",
                screen.contains("programsByChannel = gridPrograms,") &&
                    screen.contains("programsVersion = gridProgramsVersion,")
            )
        }
    }

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val GRID_RULES = "ui/iptv/GuideGridRules.kt"
        const val IPTV_VIEW_MODEL = "ui/iptv/IptvViewModel.kt"
        const val TV_GRID = "ui/iptv/GuideGridView.kt"
        const val TV_SCREEN = "ui/iptv/GuideScreen.kt"
        const val MOBILE_GRID = "ui/mobile/GuideGridMobileView.kt"
        const val MOBILE_SCREEN = "ui/mobile/GuideScreenMobile.kt"
    }
}
