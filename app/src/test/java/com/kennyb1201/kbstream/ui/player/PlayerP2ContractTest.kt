package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playback P2s. The behavioural half pins the ASS-usability rule directly; the
 * wiring half pins the call sites that cannot be driven without a real decoder.
 */
class PlayerP2ContractTest {

    // ── PB-P2-5: an ASS body must actually carry a cue ──────────────────

    @Test
    fun `a header-only ASS script is not a usable body`() {
        // `isAssContent` accepts a `[Script Info]` header on its own, so a blank
        // check used to call this renderable - and the player rebuilt for a file
        // with no Dialogue event at all.
        val headerOnly = "[Script Info]\nTitle: x\nScriptType: v4.00+\n"
        assertFalse(SubtitleSearchHelper.isUsableSubtitleBody(headerOnly, assRenderable = true))
    }

    @Test
    fun `an ASS script with a dialogue event is usable only when renderable`() {
        val ass = "[Script Info]\nTitle: x\n[Events]\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,hi\n"
        assertTrue(SubtitleSearchHelper.isUsableSubtitleBody(ass, assRenderable = true))
        assertFalse(SubtitleSearchHelper.isUsableSubtitleBody(ass, assRenderable = false))
    }

    // ── PB-P2-1: a seek clears the end-of-playback latch ────────────────

    @Test
    fun `an explicit seek clears the ended latch`() {
        val body = functionBody(NATIVE, "override fun onPositionDiscontinuity(")
        val seekBranch = body.indexOf("DISCONTINUITY_REASON_SEEK")
        val clear = body.indexOf("playbackEndedHandled = false")
        assertTrue("the seek branch must clear the end-of-playback latch", clear >= 0)
        assertTrue("and the clear must sit inside the seek branch", clear > seekBranch)
    }

    // ── PB-P2-4: the prefetch asks the engine, not a constant ───────────

    @Test
    fun `the subtitle prefetch asks the engine whether ASS is renderable`() {
        val src = source(PREFETCH)
        assertTrue(
            "the prefetch must use the engine's own ASS capability",
            src.contains("assRenderable = AssSubtitleRenderer.available")
        )
        assertFalse(
            "a hardcoded true accepts an ASS track a libass-less build cannot draw",
            src.contains("isUsableSubtitleBody(result.body, assRenderable = true)")
        )
    }

    // ── PB-P2-6: a source switch clears the live playhead ───────────────

    @Test
    fun `a source switch clears the live playhead after the resume point is kept`() {
        val body = functionBody(MPV, "private fun switchToSource(")
        val resume = body.indexOf("startPositionMs = resumeAt")
        val reset = body.indexOf("positionMs = 0L")
        assertTrue("the switch must clear the live playhead", reset >= 0)
        assertTrue(
            "and do it after the resume point is captured, or the source restarts at 0",
            reset > resume
        )
    }

    @Test
    fun `the prefetch aborts every call it started`() {
        val src = source(PREFETCH_LIVE)
        assertTrue(
            "in-flight calls must be tracked as a set, not one slot",
            src.contains("newKeySet<Call>()")
        )
        assertTrue(src.contains("inFlight.add(call)"))
        assertTrue(src.contains("inFlight.remove(call)"))
        val body = functionBody(PREFETCH_LIVE, "override fun cancelInFlight(")
        assertTrue(
            "the cancel must abort ALL tracked calls",
            body.contains("calls.forEach { it.cancel() }")
        )
    }

    private fun source(path: String): String {
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

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(path: String, signature: String): String {
        val src = source(path)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val PREFETCH = "com/kennyb1201/kbstream/ui/player/SubtitlePrefetch.kt"
        const val PREFETCH_LIVE = "com/kennyb1201/kbstream/ui/player/LiveChannelPrefetch.kt"
    }
}
