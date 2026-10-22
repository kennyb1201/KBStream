package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A resume must not re-engage a rate that is already engaged.
 *
 * Field bisect, 2026-10-18 (Onn 4K Plus, 23.976 fps H.264, mode-first order on):
 * the open was smooth, and a pause of about a minute followed by a resume was
 * choppy, with `CCodecBufferChannel: no present fence for frame N` at frame
 * cadence — on the original decoder and on the rebuilt one, with AFR off the
 * pause/resume was smooth. The re-arm on the way back in is the only AFR action
 * on that path, and it had two ways to change the panel when nothing needed
 * changing:
 *
 *  1. The mode-first ladder read "the panel is already in the best mode" as "no
 *     better mode exists" and fell through to `Surface.setFrameRate` on the LIVE
 *     surface, which this firmware mishandles mid-session. Re-offering a rate the
 *     panel is already in was a no-op only for the MODE step.
 *  2. `onStop` put the panel back to 60 Hz on every stop, including a transient
 *     one (a pause that turns the screen off, a brief trip away), so the resume
 *     re-requested the film rate: a full 60 → 24 switch racing the player
 *     rebuild, where the open-time switch happens before decode and is smooth.
 *
 * The shape pinned here is the fix's whole content — recognition of an already
 * engaged panel, the surface hint kept only where it is the only request there
 * is, and a restore that happens on the way OUT rather than on every stop. A
 * device cannot be driven from CI, so the sources are read, the way this tree's
 * other player contracts are (`FrameRateResumeContractTest`).
 */
class FrameRateEngageResumeContractTest {

    private val native = squash(source(NATIVE))
    private val mpv = squash(source(MPV))
    private val match = squash(source(MATCH))

    @Test
    fun `the mode-first branch tells an engaged panel from one with nothing better`() {
        val branch = modeFirstBranch()

        assertTrue(
            "the branch must use the exclusion-free search: chooseMode answers null for " +
                "BOTH \"nothing better exists\" and \"already there\", which is the confusion " +
                "that fired the surface hint at the live surface",
            branch.contains("val best = FrameRateMatch.bestMode(contentFps, modes)")
        )
        assertFalse(
            "and it must not go back to the choice search, which cannot tell them apart",
            branch.contains("FrameRateMatch.chooseMode(")
        )
        assertTrue(
            "a mode request is only for a genuinely different mode",
            branch.contains("best.id != modeId -> requestMode(best, contentFps, modeId)")
        )
        assertTrue(
            "and a panel already in the best mode is its own branch, whether or not this " +
                "session is what put it there (2026-10-22, Door 1)",
            branch.contains("else -> {")
        )
        assertFalse(
            "the fresh-matcher case must not be split out again: it is the exact branch that " +
                "fell through to the surface hint under a forced mode",
            branch.contains("hasEngaged -> {")
        )
    }

    @Test
    fun `a panel already in the best mode is asked for nothing at all`() {
        val branch = modeFirstBranch()
        val noOp = branch.substringAfter("else -> {")

        assertTrue(
            "the no-op has to be SAID, on the diagnostics row: the row is how a field capture " +
                "tells \"we left it alone\" from \"the TV refused\"",
            noOp.contains("FrameRateMatch.describeAlreadyInBestMode(contentFps, best)")
        )
        assertTrue(
            "and in the log, under the tag the probe and the matcher already use",
            noOp.contains("already in the best mode for \${rate(contentFps)} fps; no request made")
        )
        assertFalse(
            "this is the defect itself: no surface hint on the already-in-best-mode path, " +
                "fresh matcher included. Re-applying a rate to the live surface under a panel " +
                "already forced to that rate is what broke presentation (2026-10-18, " +
                "2026-10-22 Door 1).",
            noOp.contains("askSurfaceIfPossible(")
        )
        assertFalse(
            "no mode request either - there is nothing to switch to",
            noOp.contains("requestMode(")
        )
    }

    @Test
    fun `a panel that is not ours is taken over without a surface hint`() {
        val branch = modeFirstBranch()
        val adopted = branch.substringAfter("else -> {")

        assertTrue(
            "single-rate panels keep the surface hint as their whole request - that is the " +
                "one case it survives for, and it must not be lost with the defect",
            branch.contains("best == null -> askSurfaceIfPossible(contentFps, modeId)")
        )
        assertFalse(
            "while a panel that already offers the best mode gets no rate on its live surface, " +
                "even when this session did not put it there",
            adopted.contains("askSurfaceIfPossible(")
        )
        assertTrue(
            "and the state this session found is now its own to put back: after an engine " +
                "switch the successor is the only session left holding the panel, and the one " +
                "that engaged it has stopped without finishing",
            adopted.contains("restoreOnRelease = true")
        )
    }

    @Test
    fun `a transient stop keeps the panel and only drops the pending confirm`() {
        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            val stop = body(src, "override fun onStop() {")

            assertTrue(
                "$who must still restore the panel when the session is really ending",
                stop.contains("if (isFinishing) frameRateMatcher?.release()")
            )
            assertTrue(
                "$who must keep the panel on a transient stop and drop the pending confirm " +
                    "instead: a backstop posted at open has no business firing while the " +
                    "player is not drawing",
                stop.contains("else frameRateMatcher?.cancelPending()")
            )
            assertEquals(
                "$who must not release unconditionally any more - that is what forced the " +
                    "resume to switch the panel back",
                1,
                count(stop, "frameRateMatcher?.release()")
            )
        }
    }

    @Test
    fun `cancelling a confirm touches no panel state`() {
        assertTrue(
            "the only thing a transient stop may do is drop the queued look at the panel: the " +
                "request it was posted for, the mode this session asked for and the rate already " +
                "on the surface all stay, so the resume has nothing to redo",
            match.contains(
                "fun cancelPending() { handler.removeCallbacksAndMessages(null) }"
            )
        )
        assertTrue(
            "release() is the finishing path, and it cancels through the same call",
            body(match, "fun release() {").contains("cancelPending()")
        )
    }

    @Test
    fun `the restore is owed by whoever is left holding the panel`() {
        val release = body(match, "fun release() {")

        assertTrue(
            "release() must gate the attribute restore on the debt, not on a mode id it asked " +
                "for itself: a session that took the panel over asked for none",
            release.contains("if (!restoreOnRelease) return")
        )
        assertTrue(
            "and this is what a resume, and an off-toggle while away, test before deciding " +
                "there is something to undo",
            match.contains("get() = restoreOnRelease || surfaceRateApplied")
        )
    }

    @Test
    fun `a setting switched off while away puts the panel back on return`() {
        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            val rearm = body(src, "private fun rearmFrameRateMatch() {")
            val offPath = rearm.substringBefore("val fps = FrameRateMatch.resumeRate")

            assertTrue(
                "$who must restore the panel when AFR is off on return: with a transient stop " +
                    "keeping the film rate, nothing else would",
                offPath.contains(
                    "if (frameRateMatcher?.hasEngaged == true) frameRateMatcher?.release()"
                )
            )
        }
    }

    /**
     * The `ENGAGE_MODE_FIRST` branch of `apply()`, up to the pre-fix ladder's
     * comment. Asserted to exist, so a rename here fails loudly rather than
     * quietly matching the whole file.
     */
    private fun modeFirstBranch(): String {
        val apply = body(match, "private fun apply(contentFps: Double, source: FrameRateSource) {")
        assertTrue("the mode-first branch must exist", apply.contains("if (ENGAGE_MODE_FIRST) {"))
        return apply.substringAfter("if (ENGAGE_MODE_FIRST) {")
            .substringBefore("// Pre-fix ladder")
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The squashed source of the function starting at [signature]. */
    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val end = src.indexOf(" private ", start + signature.length)
        val close = src.indexOf(" override ", start + signature.length)
        val stops = listOf(end, close).filter { it > 0 }
        val stop = if (stops.isEmpty()) src.length else stops.min()
        return src.substring(start, stop)
    }

    /** How many times [needle] occurs in [text]. */
    private fun count(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MATCH = "com/kennyb1201/kbstream/ui/player/FrameRateMatch.kt"
    }
}
