package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source that never OPENED must not be a dead end on the backup engine.
 *
 * The main player walks past an unopenable source on its own (see the
 * unopenable ladder in NativePlayerActivity); the MPV engine only ever offered
 * the error card's manual button, so a session that landed here — which is
 * exactly the session whose first source was already trouble, a decoder handoff
 * or a container ExoPlayer's extractor refused — could fail to open and stop
 * there with the whole ranked list untried. The report that showed it: an
 * engine switch to MPV for a container, then "MPV could not open the stream",
 * five zero-second playback stats and four rebuilds, with 19 sources loaded.
 *
 * This pins the wiring, because an Activity that builds libmpv cannot be driven
 * in a JVM test: the callback fires ONLY for a file that never opened, the
 * advance is bounded, the card stays the outcome when the list runs out, and the
 * counter is a session counter that a file which actually opened clears.
 */
class MpvOpenFailureAdvanceContractTest {

    private val view: String by lazy { read(MPV_VIEW) }
    private val activity: String by lazy { read(MPV) }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    /** The text of the member starting at [signature] up to the next member. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = source.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private val errorHandler: String by lazy {
        val start = activity.indexOf("view.onPlaybackError = {")
        assertTrue("MpvPlayerActivity must handle the view's playback error", start >= 0)
        activity.substring(start, minOf(start + 2_600, activity.length))
    }

    @Test
    fun `the callback means the file never opened`() {
        assertEquals(
            "onPlaybackError must be raised from exactly one place",
            1,
            view.split("onPlaybackError?.invoke(").size - 1
        )
        val endFile = view.substringAfter("MPVLib.MpvEvent.MPV_EVENT_END_FILE ->")
        assertTrue(
            "and only from the branch that saw END_FILE without a file to end",
            endFile.substring(0, endFile.indexOf("MPV_EVENT_VIDEO_RECONFIG"))
                .contains("if (!fileLoaded) {")
        )
    }

    @Test
    fun `a failed open advances before the card is shown`() {
        val rule = errorHandler.indexOf("shouldAdvancePastOpenFailure(")
        val advance = errorHandler.indexOf("tryNextSource()")
        val card = errorHandler.indexOf("showError(")
        assertTrue("the handler must ask the bounded rule", rule >= 0)
        assertTrue("and use the ranked list's own next source", advance >= 0)
        assertTrue("the advance must be decided before the card", rule in 0 until card)
        assertTrue("and the card must stay what a spent ladder falls to", card > advance)
    }

    @Test
    fun `the list is checked before advancing, so the card is never unreachable`() {
        assertTrue(
            "advancing needs a source to advance TO",
            errorHandler.contains("hasAnotherSource = nextSourceOrNull() != null")
        )
        assertTrue(
            "and the counter is what the bound is applied to",
            errorHandler.contains("sourcesTried = openFailureSourcesTried")
        )
    }

    @Test
    fun `the counter is a session counter, not per source`() {
        assertTrue(
            "MpvPlayerActivity must carry the counter across a source switch",
            activity.contains("private var openFailureSourcesTried = 0")
        )
        val fileLoaded = functionBody(activity, "private fun onFileLoaded(mediaTitle: String?) {")
        assertTrue(
            "a source that OPENED buys the next one a fresh budget",
            fileLoaded.contains("openFailureSourcesTried = 0")
        )
        val switch = functionBody(
            activity,
            "private fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {"
        )
        assertTrue(
            "a switch resets the source's own state and must NOT reset the ladder",
            !switch.contains("openFailureSourcesTried")
        )
    }

    @Test
    fun `the advance is recorded in the diagnostics report`() {
        assertTrue(
            "a dump has to explain why no card appeared",
            errorHandler.contains("PlaybackEngineTrace.note(")
        )
    }

    private companion object {
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
    }
}
