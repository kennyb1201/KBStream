package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A short press on Play must retire the pending auto-play effect (HD-P2-1).
 *
 * For a series the auto-play effect parks itself while `episodesLoading ||
 * episodes.isEmpty()` - deliberately, so it can hand the picker a target that
 * knows the episode count - but the Play button is already visible in that
 * window. A short press there navigated straight to the streams screen while
 * `autoPlayed` was still false, so when the episodes landed the effect ran a
 * SECOND `onNavigateStreams` and also issued its own
 * `ManualSourceSelection.request`. The second navigation arrives with a manual
 * request attached, which is what stopped the target auto-selecting.
 *
 * Read from the source: the behaviour is a Compose effect racing a key press,
 * which needs a device to stage.
 */
class PlayPressRetiresAutoPlayContractTest {

    private val source: String by lazy {
        val file = File(findSourceRoot(), DETAIL)
        assertTrue("source missing: $file", file.isFile)
        file.readText()
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

    /** The body of `val openStreams = { ... }` up to its terminating `}`. */
    private fun openStreamsBody(): String {
        val start = source.indexOf("val openStreams = {")
        assertTrue("openStreams not found", start >= 0)
        val end = source.indexOf("onNavigateStreams(", start)
        assertTrue("openStreams does not navigate", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `the play press retires the effect before it navigates`() {
        val body = openStreamsBody()
        assertTrue(
            "the pending auto-play effect must be retired by a direct press, " +
                "or it fires a second navigation when the episodes land",
            body.contains("autoPlayed = true")
        )
    }

    @Test
    fun `the menu rows still hand the press back to the effect`() {
        // Both rows clear the flag on purpose: they deliberately do NOT
        // navigate themselves, so the effect has to be free to run again.
        val start = source.indexOf("if (playButtonMenu) {")
        assertTrue("the play button menu not found", start >= 0)
        val end = source.indexOf("onDismiss = { dismissPlayButtonMenu() }", start)
        assertTrue("the play button menu end not found", end > start)
        val menu = source.substring(start, end)
        assertTrue("Play from Beginning must re-arm the effect", menu.contains("startOver = true"))
        assertTrue("and Play Manually must too", menu.contains("manualPick = true"))
        assertTrue(
            "each row has to clear the flag, or the press would never act",
            Regex("autoPlayed = false").findAll(menu).count() >= 2
        )
    }

    @Test
    fun `the random-episode press retires it too`() {
        // It navigates directly in every branch, so it is the same hazard.
        val start = source.indexOf("val randomScope = scope")
        assertTrue("the random button not found", start >= 0)
        val handler = source.substring(start, source.indexOf("onNavigateStreams(", start))
        assertTrue(
            "a direct navigation has to retire the effect",
            handler.contains("autoPlayed = true")
        )
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
