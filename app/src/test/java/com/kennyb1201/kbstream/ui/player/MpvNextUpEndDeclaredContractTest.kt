package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV Next Up countdown does not deadlock on mpv's own EOF pause.
 *
 * Field case (2026-11-20, TCL, tt7678620 S01E28): a decoder handoff played to
 * the end on mpv and the Next Episode card sat on "Paused - playing next when you
 * resume" forever; the paste's `pause save … pos=441608ms` against a 441791ms
 * file is that pause. `keep-open=yes` makes mpv pause itself at EOF, the view
 * mirrors it into `pauseState`, and the countdown's viewer-pause protection read
 * it as a person - but at EOF there is nothing left to resume.
 *
 * The distinction is made at the countdown (the only consumer that needs it), as
 * an `endDeclared` flag set at the one chokepoint both end paths pass through.
 * The protection itself has to survive: a viewer pause DURING the credits, before
 * the end, must still hold the countdown.
 */
class MpvNextUpEndDeclaredContractTest {

    private companion object {
        const val ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"

        /** The hold, with the exemption in it - the whole fix, in one line. */
        const val HOLD =
            "if (surface?.isPaused() != false && !endDeclared) {"

        /** How much source follows a reset before the flag must be cleared in it. */
        const val RESET_WINDOW = 700
    }

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
        return file.readText()
    }

    @Test
    fun `the countdown holds only while the viewer is still watching something`() {
        val source = source(ACTIVITY)

        assertTrue(
            "the pause-hold must still exist and must still require a paused surface, " +
                "exempted only by the declared end",
            source.contains(HOLD)
        )
        assertTrue(
            "the mid-episode protection is what the hold is for and must not be lost",
            source.contains("\"Paused - playing next when you resume\"")
        )
    }

    @Test
    fun `the end is declared at the chokepoint both end paths share`() {
        val source = source(ACTIVITY)

        assertTrue(
            "the flag must be declared",
            source.contains("private var endDeclared = false")
        )

        val route = source.indexOf("private fun routeAfterCompletedEnd()")
        assertTrue("routeAfterCompletedEnd must still exist", route >= 0)

        val declared = source.indexOf("endDeclared = true", route)
        assertTrue(
            "the end must be declared in routeAfterCompletedEnd, which both the natural " +
                "end and the error-tail completion pass through",
            declared > route
        )

        val sleepReturn = source.indexOf(
            "sleepTimerBlocksAutoAdvance(SleepTimer.state.value)",
            route
        )
        assertTrue(
            "declared before the sleep-timer return, so that path cannot leave a stale false",
            sleepReturn > declared || sleepReturn < 0
        )

        // `endedHandled` is NOT the trigger: the error-tail path files a
        // completion without setting it, so keying the flag to that latch would
        // miss the case this exists for.
        val endedLatch = source.indexOf("endedHandled = true", route)
        assertTrue(
            "the declaration must not be gated on endedHandled, which the error-tail " +
                "completion never sets",
            endedLatch < 0 || declared < endedLatch
        )
    }

    @Test
    fun `a new source starts with the end undeclared`() {
        val source = source(ACTIVITY)

        // Assignments at statement indent - the declaration line is not one.
        val reset = "\n        endDeclared = false"
        assertEquals(
            "every place the file's end latch is cleared must clear the countdown's " +
                "exemption too, or a source switch inherits endDeclared=true and the " +
                "viewer's pause on the new file stops holding the countdown",
            2,
            count(source, reset)
        )

        val endedReset = "\n        endedHandled = false"
        var index = source.indexOf(endedReset)
        var checked = 0
        while (index >= 0) {
            val window = source.substring(
                index,
                minOf(source.length, index + RESET_WINDOW)
            )
            assertTrue(
                "an endedHandled reset with no endDeclared reset beside it: " +
                    window.take(160)
            , window.contains(reset))
            checked++
            index = source.indexOf(endedReset, index + 1)
        }
        assertEquals("both resets must still be pinned", 2, checked)
    }

    private fun count(haystack: String, needle: String): Int {
        var total = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            total++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return total
    }
}
