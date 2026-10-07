package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished episode stays finished, and stays watched - even when the viewer
 * keeps pressing buttons on the BECAUSE YOU WATCHED panel.
 *
 * The report: the last aired episode of a show (the one whose next episode has
 * not aired, which is exactly when the credits recommendations are raised
 * instead of the Up Next card) "keeps replaying when it finishes ... while I'm
 * browsing because you watched", and "it also didn't mark it as watched since
 * it started over".
 *
 * Two things met behind that panel:
 *
 *  1. The panel did not actually own the D-pad while it was up. Its key rule
 *     sat behind `plainPlaybackForeground()`, and it only swallowed a press when
 *     `bywUi.focusFirst()` succeeded - which it cannot until the pick row has
 *     been built (a TMDB fetch takes a beat). The controls overlay is very often
 *     still up under the panel (it is never auto-hidden once the file stops
 *     playing), and [NativePlayerActivity.focusControls] parks focus on the
 *     SEEK BAR on purpose. So LEFT/RIGHT aimed at a pick landed on that bar,
 *     whose key handling steps the video 10s per press: the finished episode
 *     scrubbed backwards and played again from there. The release half was
 *     worse - the bar's release handler commits its own position as a seek.
 *
 *  2. The completion was stored only in `playbackEndedHandled`, the PLAY guard,
 *     which a seek clears on purpose (PB-P2-1: rewind the ending, press play,
 *     watch it again). Once the scrub cleared it, the next save was judged from
 *     where the playhead now sat - so leaving filed a RESUME row over the
 *     completion, and the episode went straight back onto Continue Watching.
 *
 * The fix: the panel answers first for every key that could be a browse press
 * (and swallows the press it cannot hand to a pick), and "this episode reached
 * its end" is its own sticky fact that no later position can unsay.
 *
 * This is key routing and history inside an Android Activity, so a device
 * cannot be avoided here. What a contract test can pin is the shape and the
 * order, which is what this class does.
 */
class FinishedEpisodeReplayContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    /**
     * The text of the function starting at [signature] up to the next member.
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** Source with per-line indentation stripped, so nested guards can be matched. */
    private fun normalized(raw: String): String = raw.lines().joinToString("\n") { it.trim() }

    @Test
    fun `the credits panel answers the d-pad before the overlay can`() {
        val body = functionBody(NATIVE, "override fun dispatchKeyEvent(")
        val panel = body.indexOf("val skipPromptOwnsConfirm =")
        val scrub = body.indexOf("if (plainPlaybackForeground() && horizontal &&")

        assertTrue("the panel must decide on the D-pad at all", panel >= 0)
        assertTrue("the surface scrub must still exist", scrub >= 0)
        assertTrue(
            "the panel answers first: a press it does not take must never reach " +
                "the seek bar the overlay parks focus on",
            panel < scrub
        )

        val block = normalized(body.substring(panel, scrub))
        assertTrue(
            "a press the panel cannot hand to a pick must still be swallowed, " +
                "not run through focusFirst() as a condition",
            block.contains("bywUi.focusFirst()\nreturn true")
        )
        assertFalse(
            "focusFirst() must not gate the swallow - until the row is built it " +
                "finds nothing, and the press would fall through to the seek bar",
            block.contains("bywUi.focusFirst())")
        )
        assertTrue(
            "UP/DOWN must not raise the overlay mid-panel",
            block.contains("if (isOverlayRaisingKey(event.keyCode)) return true")
        )
        // The one press the panel must keep its hands off: a SKIP CREDITS prompt
        // is raised over these very recommendations, and it owns OK while the
        // chrome is down. The panel block runs ahead of the prompt branch now,
        // so it has to stand aside for exactly that press.
        assertTrue(
            "a visible skip prompt must keep its OK",
            block.contains("(isConfirmKey(event.keyCode) && !skipPromptOwnsConfirm)")
        )
        assertTrue(
            "...and the prompt case is the chrome being down with the prompt up",
            block.contains(
                "val skipPromptOwnsConfirm =\n" +
                    "btnSkipIntro.visibility == View.VISIBLE && !controlsVisible"
            )
        )
    }

    @Test
    fun `a LEFT or RIGHT release belongs to the panel too`() {
        val body = functionBody(NATIVE, "override fun dispatchKeyEvent(")
        val release = body.indexOf(
            "if (creditsPanelForeground() && horizontal &&\n" +
                "            event.action != KeyEvent.ACTION_DOWN"
        )
        assertTrue(
            "the release half must be claimed as well: with the overlay up it " +
                "reaches the seek bar, whose release handler commits a seek",
            release >= 0
        )
        val block = normalized(body.substring(release, release + 400))
        assertTrue(
            "and an in-flight surface scrub is ended here, since its own " +
                "release never gets through",
            block.contains("if (scrubDirection != 0) stopSurfaceScrub()")
        )
    }

    @Test
    fun `reaching the end is its own sticky fact for the session`() {
        val ended = normalized(functionBody(NATIVE, "private fun onPlaybackEnded() {"))
        assertTrue(
            "the end of playback must record the episode as finished, not only " +
                "as 'the play guard is up'",
            ended.contains("playbackEndReached = true")
        )
        // Recorded after the "did anything actually play?" guard, so a source
        // that never came up still files nothing.
        assertTrue(
            "the never-played guard must come first",
            ended.indexOf("if (!sessionHasPlayed())") in
                0 until ended.indexOf("playbackEndReached = true")
        )
        assertTrue(
            "a fresh session is a fresh activity: the flag starts false",
            readSource(NATIVE).contains("private var playbackEndReached = false")
        )
    }

    @Test
    fun `a seek cannot unsay that the episode finished`() {
        val source = readSource(NATIVE)
        val discontinuity = functionBody(NATIVE, "override fun onPositionDiscontinuity(")
        assertTrue(
            "the PLAY guard is still cleared by a seek, so a rewind can be replayed",
            discontinuity.contains("playbackEndedHandled = false")
        )
        assertFalse(
            "the completion must survive that same seek",
            discontinuity.contains("playbackEndReached = false")
        )
        assertEquals(
            "only the field's own initializer may set it false - nothing clears " +
                "a finished episode for the session",
            1,
            source.split("playbackEndReached = false").size - 1
        )
        assertTrue(source.contains("private var playbackEndReached = false"))
        assertEquals(
            "both completion verdicts must read the sticky fact as well as the latch",
            2,
            source.split("playbackEnded = playbackEndedHandled || playbackEndReached,").size - 1
        )
    }

    @Test
    fun `every write in a finished session is filed as completed`() {
        // Sliced by hand rather than through [functionBody]: saveProgress's
        // parameter list spans lines, and the member helper's four-space rule
        // would stop at the closing paren of that list.
        val source = readSource(NATIVE)
        val start = source.indexOf("private fun saveProgress(")
        assertTrue("source missing saveProgress", start >= 0)
        val save = normalized(
            source.substring(start, source.indexOf("\n    }\n", start))
        )
        assertTrue(
            "the verdict must be session-wide, so a pause after a rewind cannot " +
                "downgrade the row",
            save.contains("val sessionCompleted = forceCompleted || playbackEndReached")
        )
        assertTrue(
            "the row's completion must be written from it",
            save.contains("val isCompleted =\nsessionCompleted ||")
        )
        // The two early returns are what a rewind hits first (a position at 0):
        // they must not abandon the write for a session that has finished.
        assertTrue(
            "a zero-duration finished session must still be written",
            save.contains("if (dur == null && !sessionCompleted) return")
        )
        assertTrue(
            "and so must one whose playhead is back at the start",
            save.contains("if (pos < MIN_RESUME_POSITION_MS && !sessionCompleted) return")
        )
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
