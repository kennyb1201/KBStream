package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hiding a show takes it off the Upcoming rail too.
 *
 * The screen filtered two surfaces by hidden titles: the catalog rails and the
 * Continue Watching list. The Upcoming rail's episode list is a THIRD flow
 * (`upcomingSchedule`), built in the ViewModel, and nothing filtered it - so a
 * hidden show's next episode still surfaced there, and focusing that card put
 * the hidden show in the hero. The phone had the same hole.
 *
 * The filter lives at the screen's read (not in the ViewModel's build) so it is
 * reactive: `rememberHiddenTitleKeys` re-reads on every store change, which is
 * what makes a Hide take effect on the next frame.
 *
 * Read from the source: the rendering needs a device.
 */
class HomeUpcomingHiddenTitleContractTest {

    private val home = normalized(stripComments(source(HOME)))
    private val mobile = normalized(stripComments(source(MOBILE)))

    @Test
    fun `the television filters the Upcoming schedule by hidden titles`() {
        val tv = home.substringAfter("val upcomingScheduleRaw by")
            .substringBefore("val isLoading by")
        assertTrue(
            "the schedule must be filtered by the hidden-title set",
            tv.contains("HiddenTitles.hides(")
        )
        assertTrue(
            "under the ids an Upcoming episode carries: its show's and its own",
            tv.contains("episode.parentId") && tv.contains("episode.id")
        )
        assertTrue(
            "and the rail must draw the filtered schedule, not the raw flow",
            home.contains("episodes = upcomingSchedule,") &&
                !home.contains("episodes = upcomingScheduleRaw")
        )
    }

    @Test
    fun `the phone filters the Upcoming row the same way`() {
        val phone = mobile.substringAfter("val upcomingRaw by")
            .substringBefore("val kbViewModel")
        assertTrue(
            "the phone's schedule must be filtered by the same set",
            phone.contains("HiddenTitles.hides(")
        )
        assertTrue(
            "under the same show and episode ids",
            phone.contains("episode.parentId") && phone.contains("episode.id")
        )
        assertTrue(
            "and the row must draw the filtered list",
            mobile.contains("items( upcoming,")
        )
    }

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
    }
}
