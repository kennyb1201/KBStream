package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Exo engine's video follows the seek bar while scrubbing.
 *
 * The old scrub kept the bar moving but left the picture behind on slow 4K
 * content: every tick's `seekTo` was gated on the player being READY and idle,
 * so while the decoder refilled (the ordinary state during a scrub of a heavy
 * release) the gate skipped the seek entirely. The fix is to pause for the
 * scrub - a paused seek has no real-time playback deadline to miss - and then
 * seek on every tick, unconditionally. The D-pad tick is still the throttle,
 * and a touch drag throttles its own follow-seeks to [TOUCH_FOLLOW_SEEK_MS].
 *
 * The play state has to come back exactly: playing resumes on release, paused
 * stays paused, and a scrub cut short (an auto-hide, a Back press, the credits
 * panel taking the keys) still restores it. These assertions pin the pause
 * wrapper, the gate's absence, both D-pad entry points, the touch hooks, and
 * the exact resume - the wiring inside an Android activity that a unit test
 * cannot press.
 */
class ExoScrubFollowContractTest {

    // ── the pause wrapper ───────────────────────────────────────────────

    @Test
    fun `a scrub pauses the player and captures the play state`() {
        val body = functionBody(NATIVE, "private fun beginScrubPause() {")
        assertTrue(
            "the play state must be captured before the pause",
            body.indexOf("wasPlayingBeforeScrub = player.isPlaying") in
                0 until body.indexOf("player.pause()")
        )
        assertTrue(
            "an unseekable item (live has no duration) is never paused",
            body.contains("if (player.duration <= 0L) return")
        )
    }

    @Test
    fun `the play state is restored exactly and cleared per scrub`() {
        val body = functionBody(NATIVE, "private fun endScrubPause() {")
        assertTrue(
            "only a scrub that found the player playing resumes it",
            body.contains("if (wasPlayingBeforeScrub) exoPlayer?.play()")
        )
        assertTrue(
            "the flag is per-scrub, so it is cleared when honoured",
            body.contains("wasPlayingBeforeScrub = false")
        )
    }

    @Test
    fun `the flag starts false and the resume never touches a released player`() {
        assertTrue(
            readSource(NATIVE).contains("private var wasPlayingBeforeScrub = false")
        )
        // exoPlayer?.play() is the null check the spec requires.
        assertTrue(
            functionBody(NATIVE, "private fun endScrubPause() {")
                .contains("exoPlayer?.play()")
        )
    }

    // ── the gate is gone ────────────────────────────────────────────────

    @Test
    fun `the scrub tick seeks unconditionally, with no readiness gate`() {
        val tick = functionBody(NATIVE, "private val scrubRunnable = object : Runnable {")
        assertTrue(
            "the tick must seek to the owned target",
            tick.contains("player.seekTo(scrubTargetPosMs)")
        )
        assertFalse(
            "the STATE_READY gate is what left the picture behind the bar",
            tick.contains("Player.STATE_READY")
        )
        assertFalse(
            "and so is the isLoading half of it",
            tick.contains("isLoading")
        )
    }

    // ── both D-pad entry points take the pause ──────────────────────────

    @Test
    fun `the seek bar's D-pad hold pauses on the first press`() {
        val n = normalized(NATIVE)
        assertTrue(
            "the bar's hold must pause after seeding the target and before the auto-hide is cancelled",
            Regex(
                "scrubMoved = false[\\s\\S]{0,220}?beginScrubPause\\(\\)" +
                    "[\\s\\S]{0,120}?removeAutoHide\\(\\)"
            ).containsMatchIn(n)
        )
    }

    @Test
    fun `the surface hold pauses on the first press too`() {
        val n = normalized(NATIVE)
        assertTrue(
            "the surface's own hold must pause as well",
            n.contains(
                "scrubMoved = false\nbeginScrubPause()\nstepSeekBy(10_000L * scrubDirection)"
            )
        )
    }

    // ── every ending restores the state ─────────────────────────────────

    @Test
    fun `the bar's release resumes after it commits the exact landing`() {
        val n = normalized(NATIVE)
        assertTrue(
            "the view's committed seek must still be the landing",
            n.contains("commitSeekFromBar()")
        )
        assertTrue(
            "the play state must be restored in the same release handler",
            Regex(
                "scrubMoved = false[\\s\\S]{0,40}?commitSeekFromBar\\(\\)" +
                    "[\\s\\S]{0,220}?endScrubPause\\(\\)"
            ).containsMatchIn(n)
        )
    }

    @Test
    fun `the surface release and both teardowns restore the state`() {
        val n = normalized(NATIVE)
        assertTrue(
            "the surface release lands, then restores",
            n.contains("if (wasScrubbing) landScrub()\nif (wasScrubbing) endScrubPause()")
        )
        assertTrue(
            "a scrub cut short by stopSurfaceScrub must still restore",
            functionBody(NATIVE, "private fun stopSurfaceScrub() {")
                .contains("endScrubPause()")
        )
        assertTrue(
            "and one cut short by hideControls must too",
            functionBody(NATIVE, "private fun hideControls() {")
                .contains("endScrubPause()")
        )
    }

    // ── the touch drag ──────────────────────────────────────────────────

    @Test
    fun `the shared bar tells the engine about a touch drag`() {
        val chrome = readSource(CHROME)
        assertTrue(
            "the drag's start pauses the engine",
            chrome.contains("host.onChromeScrubStart()")
        )
        assertTrue(
            "every user progress change is reported",
            chrome.contains("host.onChromeScrubProgress(positionMs)")
        )
        assertTrue(
            "the drag's end restores the engine",
            chrome.contains("host.onChromeScrubEnd()")
        )
    }

    @Test
    fun `the follow-seek is throttled, not one seek per progress event`() {
        val body = functionBody(NATIVE, "override fun onChromeScrubProgress(positionMs: Long) {")
        assertTrue(
            "the throttle must gate the seek",
            body.contains("if (now - lastFollowSeekMs < TOUCH_FOLLOW_SEEK_MS) return")
        )
        assertTrue(body.contains("lastFollowSeekMs = now"))
        assertTrue(body.contains("player.seekTo(positionMs)"))
        assertTrue(
            "the window is 150ms",
            readSource(NATIVE).contains("private const val TOUCH_FOLLOW_SEEK_MS = 150L")
        )
    }

    @Test
    fun `the drag's own hooks pause and resume`() {
        val native = readSource(NATIVE)
        assertTrue(
            "the drag pauses exactly as the D-pad scrub does",
            native.contains("override fun onChromeScrubStart() = beginScrubPause()")
        )
        assertTrue(
            "and restores exactly as it does",
            native.contains("override fun onChromeScrubEnd() = endScrubPause()")
        )
    }

    @Test
    fun `mpv does not opt into the scrub hooks, so its own seek handling stands`() {
        // The hooks are defaults on the shared host; an engine that does not
        // override them is untouched. This spec is Exo only.
        assertFalse(
            "mpv must not take the Exo engine's scrub pause",
            readSource(MPV).contains("onChromeScrub")
        )
        assertTrue(
            "and the defaults really are empty",
            readSource(CHROME).contains("fun onChromeScrubStart() {}") &&
                readSource(CHROME).contains("fun onChromeScrubProgress(positionMs: Long) {}") &&
                readSource(CHROME).contains("fun onChromeScrubEnd() {}")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun normalized(path: String): String =
        readSource(path).lines().joinToString("\n") { it.trim() }

    /**
     * The text of the member starting at [signature] up to the next member: a
     * line indented by exactly four spaces, which the body's deeper indents do
     * not match.
     */
    private fun functionBody(path: String, signature: String): String {
        val src = readSource(path)
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
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
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        private const val CHROME =
            "com/kennyb1201/kbstream/ui/player/PlayerChrome.kt"
    }
}
