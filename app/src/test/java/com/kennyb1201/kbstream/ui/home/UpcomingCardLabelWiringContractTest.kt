package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That both Upcoming cards print their season/episode pair through the one rule
 * the Continue Watching rail uses ([upNextEpisodeLabel]).
 *
 * The pair was spelled out inline on each card as `"S%02d · E%02d"`, so the
 * Upcoming rail read "S02 · E08" while the Continue Watching rail right above
 * it read "S2 E8". Two formats for one fact, on one screen, is exactly the
 * drift the shared rule exists to stop - and the two cards are in two different
 * files, one per form factor, which is how they drifted in the first place.
 *
 * Read from the source: these are Compose card bodies, which need a screen to
 * look at.
 */
class UpcomingCardLabelWiringContractTest {

    private val tv: String by lazy { read(TV_FILE) }
    private val mobile: String by lazy { read(MOBILE_FILE) }

    @Test
    fun `the television card asks the shared rule for its pair`() {
        val card = slice(tv, "private fun UpcomingEpisodeCard(", "private fun HomeHeroHost(")
        assertTrue(
            "the Upcoming card must number its episode the way the Continue " +
                "Watching rail does",
            card.contains("upNextEpisodeLabel( upcoming.season, upcoming.episode )")
        )
        assertFalse(
            "and must not keep a padded format of its own",
            card.contains("%02d")
        )
    }

    @Test
    fun `the phone card asks the same rule for its pair`() {
        val card = slice(mobile, "private fun UpcomingCard(", "private fun rememberHidesEpisodeTitle(")
        assertTrue(
            "the phone's Upcoming card reads the television's rule (imported as " +
                "tvUpNextEpisodeLabel), so the two rails cannot disagree",
            card.contains("tvUpNextEpisodeLabel(episode.season, episode.episode)")
        )
        assertFalse(
            "and keeps no padded format of its own",
            card.contains("%02d")
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
        const val TV_FILE = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val MOBILE_FILE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
    }
}
