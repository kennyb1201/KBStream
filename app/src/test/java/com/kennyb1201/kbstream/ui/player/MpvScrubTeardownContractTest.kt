package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mpv engine's scrub timer is cleared when the Activity goes away.
 *
 * The hold-to-scrub ramp is the one handler in this Activity that keeps itself
 * alive: [MpvPlayerActivity]'s runnable calls `postDelayed(this, …)` from inside
 * its own callback, so it only stops when something removes it, and the key-up
 * that would normally do that is exactly what a session destroyed without an
 * onStop never receives. It also holds three fields of the Activity
 * (`mpvScrubTargetMs` and the surface it seeks) through the runnable's captured
 * receiver, so a timer left running is not just a stray seek: it is the whole
 * Activity held on the main thread's queue for the life of the process.
 *
 * onStop already lands the scrub, which covers every ordinary exit; these
 * assertions are about the teardown under it, because "the player dies without
 * hitting onStop" is the case a safety net is for and the case nothing else
 * covers.
 */
class MpvScrubTeardownContractTest {

    // ── what makes the teardown necessary ───────────────────────────────

    @Test
    fun `the ramp re-posts itself, so nothing but a removal ends it`() {
        val tick = functionBody(MPV, "private val mpvScrubRunnable = object : Runnable {")
        assertTrue(
            "the next tick is armed from inside the tick",
            tick.contains("mpvScrubHandler.postDelayed(this, ScrubAcceleration.TICK_MS)")
        )
        assertTrue(
            "and only an idle direction stops the ramp by itself",
            tick.contains("if (mpvScrubDirection == 0) return")
        )
    }

    @Test
    fun `the ramp holds the Activity's surface and its scrub state`() {
        val tick = functionBody(MPV, "private val mpvScrubRunnable = object : Runnable {")
        assertTrue(
            "the tick reaches the Activity's own surface, which is the reference " +
                "that outlives onDestroy if the handler is not cleared",
            tick.contains("val view = surface ?: return")
        )
        assertTrue(
            "and it reads the Activity's held-to position",
            tick.contains("mpvScrubTargetMs")
        )
    }

    // ── the teardown ────────────────────────────────────────────────────

    @Test
    fun `onDestroy clears the scrub handler`() {
        assertTrue(
            "a destroyed session must not leave a self-re-posting timer behind",
            functionBody(MPV, "override fun onDestroy() {")
                .contains("mpvScrubHandler.removeCallbacksAndMessages(null)")
        )
    }

    @Test
    fun `onDestroy still clears every handler it cleared before`() {
        // The scrub handler joins the existing net; it does not replace it.
        val destroy = functionBody(MPV, "override fun onDestroy() {")
        listOf("handler", "clockHandler", "nextUpCountdownHandler").forEach { name ->
            assertTrue(
                "$name is no longer cleared in onDestroy",
                destroy.contains("$name.removeCallbacksAndMessages(null)")
            )
        }
    }

    @Test
    fun `the timer is cleared before the surface it seeks is released`() {
        // Both run on the main thread, so ordering is what keeps a tick that is
        // already queued from landing between the two.
        val destroy = functionBody(MPV, "override fun onDestroy() {")
        val clearedAt = destroy.indexOf("mpvScrubHandler.removeCallbacksAndMessages(null)")
        val releasedAt = destroy.indexOf("surface?.release()")
        assertTrue("the clear must be in onDestroy", clearedAt >= 0)
        assertTrue("the release must be in onDestroy", releasedAt >= 0)
        assertTrue(
            "the scrub timer has to be cleared before the surface is released",
            clearedAt < releasedAt
        )
    }

    @Test
    fun `onStop still lands a scrub rather than leaving it to the teardown`() {
        // The teardown is a safety net, not the mechanism: a pause/leave must
        // still commit the position it scrubbed to and re-raise the overlay.
        assertTrue(
            "leaving the screen has to land the scrub",
            functionBody(MPV, "override fun onStop() {").contains("endMpvHoldScrub()")
        )
        val land = functionBody(MPV, "private fun endMpvHoldScrub() {")
        assertTrue(land.contains("mpvScrubHandler.removeCallbacks(mpvScrubHoldStarter)"))
        assertTrue(land.contains("mpvScrubHandler.removeCallbacks(mpvScrubRunnable)"))
        assertTrue(
            "and land the position it scrubbed to",
            land.contains("surface?.seekTo(mpvScrubTargetMs)")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
