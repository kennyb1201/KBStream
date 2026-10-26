package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home rails' head-card D-pad Left edge-stop.
 *
 * Reported problem (TV, Onn): pressing Left from the first Continue Watching
 * card moved focus DOWN to the Upcoming rail's header button - the rail column
 * pads 12dp less than the card row does, so the header's left edge sits left of
 * the first card and default focus search fell through to it.
 *
 * The fix is one small extension that cancels the LEFT search on position 0 of
 * a rail, applied by every rail kind Home draws. Two things about it are worth
 * pinning at a seam a unit test can reach, because failing either one is
 * silent: the rule must cancel a search rather than swallow a key (a consumed
 * Left press would be a different, heavier mechanism, and would have to be
 * reported as such), and every rail kind must apply it to its own head card -
 * the geometry is the same for every rail, so a rail that skips it leaves the
 * same bug latent there.
 *
 * Read from the source: these are card modifier chains, which need a screen to
 * look at.
 */
class HomeRailHeadLeftEdgeStopContractTest {

    private val home: String by lazy { read(HOME_FILE) }
    private val slots: String by lazy { read(SLOTS_FILE) }

    @Test
    fun `the stop cancels the left search and touches no other direction`() {
        val stop = slice(
            slots,
            "internal fun Modifier.homeRailHeadLeftEdgeStop(",
            "internal const val HomeTopBarHoldMs"
        )
        assertTrue(
            "the preferred mechanism: the Left search is cancelled, so focus stays",
            stop.contains("this.focusProperties { left = FocusRequester.Cancel }")
        )
        assertFalse(
            "an edge-stop cancels a search; it must not intercept the key",
            stop.contains("onPreviewKeyEvent")
        )
        assertTrue(
            "and it applies to position 0 only, so Left/Right inside a rail still work",
            stop.contains("if (!isHeadCard) {")
        )
        listOf("up =", "down =", "right =").forEach { direction ->
            assertFalse(
                "the stop is Left-only, but it sets $direction",
                stop.contains(direction)
            )
        }
    }

    @Test
    fun `both built-in rails stop at their head card`() {
        val continueWatching = slice(
            home,
            "private fun BuiltinContinueWatchingRail(",
            "private fun BuiltinUpcomingRail("
        )
        assertTrue(
            "Continue Watching is the reported rail",
            continueWatching.contains(
                ".homeRailHeadLeftEdgeStop( index == CONTINUE_WATCHING_HEAD_INDEX )"
            )
        )

        val upcoming = slice(
            home,
            "private fun BuiltinUpcomingRail(",
            "private fun SectionTitle("
        )
        assertTrue(
            "Upcoming's own head card stops too, at its own index 0",
            upcoming.contains("isHeadCard = index == CONTINUE_WATCHING_HEAD_INDEX")
        )

        val upcomingCard = slice(
            home,
            "private fun UpcomingEpisodeCard(",
            "private fun HomeHeroHost("
        )
        assertTrue(
            "and the stop is in that card's own modifier chain",
            upcomingCard.contains(".homeRailHeadLeftEdgeStop(isHeadCard)")
        )
    }

    @Test
    fun `the catalog rows stop at their head card`() {
        assertTrue(
            "every add-on / Top Today rail draws through this one row, so the stop " +
                "belongs there and not once per catalog",
            home.contains(".homeRailHeadLeftEdgeStop( index == 0 )")
        )
    }

    @Test
    fun `the rows that live in the slots file stop at their head tile`() {
        assertEquals(
            "the collection row and the browse row each apply it once",
            2,
            slots.split(".homeRailHeadLeftEdgeStop(index == 0)").size - 1
        )
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

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

    private companion object {
        const val HOME_FILE = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SLOTS_FILE = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
    }
}
