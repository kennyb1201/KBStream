package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings → Viewing stats on a phone.
 *
 * The screen is a readout, so the contract is mostly that nothing was
 * re-derived: the same [com.kennyb1201.kbstream.ui.settings.StatsViewModel],
 * the same four headline numbers, the same `ViewingStats` formatting, the same
 * streak and top-show wording. What the port changes is the shape (two rows of
 * two headline plates instead of one row of four) and the controls (the back
 * arrow is the only thing that can be pressed, exactly as the TV plates take no
 * focus).
 */
class StatsScreenMobileContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun tvStats(): String =
        source("com/kennyb1201/kbstream/ui/settings/StatsScreen.kt")

    private fun mobileStats(): String =
        source("com/kennyb1201/kbstream/ui/mobile/StatsScreenMobile.kt")

    @Test
    fun `the tv screen dispatches on the form factor at its entry point`() {
        val tv = tvStats()
        val dispatch = tv.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val mobileCall = tv.indexOf("ui.mobile.StatsScreenMobile(")
        val tvBody = tv.indexOf("BackHandler(onBack = onBack)")
        assertTrue(
            "StatsScreen must branch on FormFactor.isTv - that is the one edit a TV " +
                "file is allowed to take in this port",
            dispatch >= 0
        )
        assertTrue("the branch must call StatsScreenMobile", mobileCall > dispatch)
        assertTrue(
            "the branch must hand over the same view model",
            tv.substring(dispatch, tvBody).contains("viewModel = viewModel")
        )
        assertTrue("and return, so the TV body never composes on a phone", tvBody > mobileCall)
        assertTrue(
            "the TV page must still exist below the branch",
            tv.substring(tvBody).contains("StatTile(") &&
                tv.substring(tvBody).contains("TopShowRow(")
        )
    }

    @Test
    fun `the mobile stats screen is built on the mobile theme and touch twins`() {
        val mobile = mobileStats()
        assertTrue("the screen must apply MobileTheme", mobile.contains("MobileTheme {"))
        assertTrue(
            "the screen's one control is the shared mobile back arrow, not a text BACK",
            mobile.contains("MobileBackButton(onBack = onBack)") &&
                !mobile.contains("label = \"BACK\"")
        )
        assertFalse(
            "mobile stats must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
        assertFalse(
            "the TV plate components must not leak into the phone screen",
            mobile.contains("import com.kennyb1201.kbstream.ui.components.KBPageTitle") ||
                mobile.contains("import com.kennyb1201.kbstream.ui.components.KBButton")
        )
    }

    @Test
    fun `the mobile stats read the same state and the same wording`() {
        val mobile = mobileStats()
        listOf(
            "StatsViewModel",
            "viewModel.state.collectAsStateWithLifecycle()",
            "state.finishedTitles",
            "state.finishedEpisodes",
            "state.finishedMovies",
            "state.finishedRuntimeMs",
            "state.streakDays",
            "state.topShows"
        ).forEach { read ->
            assertTrue("mobile stats must read $read", mobile.contains(read))
        }
        listOf(
            "\"Titles finished\"",
            "\"Episodes finished\"",
            "\"Movies finished\"",
            "\"Finished runtime\"",
            "\"TOP SHOWS\"",
            "\"Nothing finished yet — the titles you complete \"",
            "\"1 day streak\""
        ).forEach { wording ->
            assertTrue("the phone must keep the TV's wording: $wording", mobile.contains(wording))
        }
        assertTrue(
            "runtime must use the shared formatter, not a second copy",
            mobile.contains("ViewingStats.formatFinishedRuntime(")
        )
        assertTrue(
            "top shows must say 'episodes finished' the same way",
            mobile.contains("\" episodes finished\"") || mobile.contains("episodes finished\"")
        )
    }

    @Test
    fun `the mobile stats are read-only and stack the headline plates`() {
        val mobile = mobileStats()
        assertEquals(
            "the back arrow is the only pressable control - the headline numbers " +
                "are readouts",
            1,
            Regex("MobileBackButton\\(").findAll(mobile).count()
        )
        assertEquals(
            "and no word button is left on the page",
            0,
            Regex("KBButton\\(").findAll(mobile).count()
        )
        assertFalse(
            "no stat plate may be a card/tap target that answers with nothing",
            mobile.contains("KBCard(")
        )
        assertEquals(
            "the four headline numbers are still all drawn",
            4,
            Regex("StatTileMobile\\( label =").findAll(mobile).count()
        )
        assertTrue(
            "a phone stacks them two by two instead of one row of four",
            Regex("Row\\( horizontalArrangement = Arrangement\\.spacedBy\\(8\\.dp\\)").findAll(mobile).count() >= 2
        )
        assertTrue(
            "the page scrolls, so the last top-show row is never cut off",
            mobile.contains("LazyColumn(")
        )
    }
}
