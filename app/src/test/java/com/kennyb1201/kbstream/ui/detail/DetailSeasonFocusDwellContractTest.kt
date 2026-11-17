package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail's season posters select on FOCUS, with no dwell (dig four, 2026-11-16,
 * §2a).
 *
 * That made the row the one per-focus data reload outside Home: each poster's
 * focus set `selectedSeason`, which drove the episode effect, which called
 * `loadEpisodesForSeason`, which emptied the episode row and refilled it. Walking
 * across eight posters was eight season reloads, with two `_episodes` emissions
 * each and every episode card's still re-requested from Coil - while Home's own
 * hero resolve at least debounces.
 *
 * The dwell collapses a walk onto the poster the viewer stops on. A TAP is a
 * deliberate action and still selects immediately; the row's own contract (focus
 * reports, press selects) is unchanged.
 */
class DetailSeasonFocusDwellContractTest {

    private val detail by lazy { source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt") }

    @Test
    fun `a focus move asks for the season and the dwell applies it`() {
        assertTrue(
            "the requested season is its own state",
            detail.contains("var pendingSeasonFocus by remember { mutableStateOf<Int?>(null) }")
        )
        val effect = detail
            .substringAfter("LaunchedEffect(pendingSeasonFocus) {")
            .substringBefore("LaunchedEffect(\n        type,")
        assertTrue(
            "the wait happens before the selection: $effect",
            effect.contains("delay(SEASON_FOCUS_DWELL_MS)") &&
                effect.indexOf("delay(SEASON_FOCUS_DWELL_MS)") <
                effect.indexOf("selectedSeason = next")
        )
        assertTrue(
            "and it re-checks after the wait, so an explicit selection wins",
            effect.countOf("selectedSeason != next") >= 1
        )
        assertTrue(
            "the dwell is short and documented",
            detail.contains("private const val SEASON_FOCUS_DWELL_MS = 140L")
        )
    }

    @Test
    fun `a focus move no longer selects the season outright`() {
        val focused = detail
            .substringAfter("onSeasonFocused = {")
            .substringBefore("// The chips answer UP and DOWN here")
        assertTrue(
            "focus records a request instead: $focused",
            focused.contains("pendingSeasonFocus =") &&
                !focused.contains("selectedSeason =")
        )
        assertTrue(
            "and the transition bookkeeping moved with it, so a walk does not " +
                "clear the row it is about to re-render eight times",
            !focused.contains("clearEpisodeTransitionState()")
        )
    }

    @Test
    fun `a tap still selects immediately`() {
        val selected = detail
            .substringAfter("onSeasonSelected = {")
            .substringBefore("onSeasonFocused = {")
        assertTrue(
            "the press path keeps its own immediate selection and transition reset: $selected",
            selected.contains("selectedSeason = seasonNum") &&
                selected.contains("clearEpisodeTransitionState()")
        )
    }

    private fun String.countOf(needle: String): Int =
        Regex(Regex.escape(needle)).findAll(this).count()

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: " + file.absolutePath)
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
