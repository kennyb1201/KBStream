package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV engine's stall watchdog, pinned where it can be read.
 *
 * The commission: "Exo recovers from a hung stream on its own, MPV sits on a
 * frozen frame until the viewer intervenes." The fix is deliberately a PORT of
 * the main player's watchdog rather than a second design, so this test holds the
 * two together - the constants may not be retuned on one engine and forgotten on
 * the other - and pins the rules the port had to re-earn on mpv's signals:
 * arming only once there is picture, no declaration under a pause, a seek, a
 * load in flight, the error card or the end of the file, the kept-buffer branch
 * that protects a merely slow source, the manual retry consumed as the recovery,
 * the cap that ends in the existing error card, and the `MPV_STALL` lines that
 * make any of it visible from a diagnostics dump.
 *
 * There is no device or emulator in CI and a libmpv Activity cannot be driven in
 * a JVM test, so the structure is read out of the source the way this tree's
 * other player contracts are.
 */
class MpvStallWatchdogContractTest {

    private val activity: String by lazy { read(MPV) }
    private val view: String by lazy { read(MPV_VIEW) }
    private val exo: String by lazy { read(EXO) }

    @Test
    fun `the thresholds are the main player's own, not retuned`() {
        assertEquals(
            "the quiet window must be Exo's 30 s",
            "stallNoProgressMs = 30_000L",
            constant(exo, "stallNoProgressMs")
        )
        assertEquals(
            "the cap must be Exo's 4 recoveries",
            "stallMaxRecoveries = 4",
            constant(exo, "stallMaxRecoveries")
        )
        assertEquals(
            "the tick must be Exo's 2 s",
            "stallTickMs = 2_000L",
            constant(exo, "stallTickMs")
        )
        assertTrue(
            "and MPV's copy must hold the same three figures",
            activity.contains("STALL_NO_PROGRESS_MS = 30_000L") &&
                activity.contains("STALL_MAX_RECOVERIES = 4") &&
                activity.contains("STALL_TICK_MS = 2_000L")
        )
        assertTrue(
            "with Exo's >500 ms progress test and its >1 s kept-buffer runway",
            activity.contains("STALL_PROGRESS_MS = 500L") &&
                activity.contains("STALL_BUFFER_KEEP_MS = 1_000L")
        )
    }

    @Test
    fun `it arms only once the session has picture and is playing`() {
        val arm = functionBody(activity, "private fun armStallWatchdog()")
        assertTrue(
            "a file must be loaded and the session must not be paused or errored",
            arm.contains("if (!fileLoaded) return") &&
                arm.contains("if (surface?.isPaused() == true) return") &&
                arm.contains("if (errorContainer?.visibility == View.VISIBLE) return")
        )
        assertTrue(
            "the first frame arms it (VIDEO_RECONFIG), as does the load and a resume",
            activity.contains("view.onPictureReady = { armStallWatchdog() }") &&
                functionBody(activity, "private fun onFileLoaded(")
                    .contains("armStallWatchdog()") &&
                functionBody(activity, "private fun onPausedChanged(")
                    .contains("armStallWatchdog()")
        )
        assertFalse(
            "but arming must NOT reset the recovery budget: an arm after a recovery's own " +
                "reload would hand the session unlimited reloads and no cap at all",
            arm.contains("stallRecoveries = 0")
        )
        assertTrue(
            "while real progress does clear it, so the cap bounds a run of failures",
            functionBody(activity, "private fun tickStallWatchdog(")
                .contains("stallRecoveries = 0")
        )
    }

    @Test
    fun `a stall is never declared under a pause, a seek, a load, an error or the end`() {
        val tick = functionBody(activity, "private fun tickStallWatchdog(")
        listOf(
            "view.isPaused()" to "a paused session is intent, never a stall",
            "loadingContainer?.visibility == View.VISIBLE" to
                "an initial load or a recovery reload in flight",
            "scrubbing || lastSeekAtMs > stallLastProgressAtMs" to
                "a scrub or a seek that just landed restarts the window",
            "errorContainer?.visibility == View.VISIBLE" to "the error card is up",
            "endedHandled" to "the file has ended",
            "if (!fileLoaded) return" to "nothing has opened yet"
        ).forEach { (rule, why) ->
            assertTrue("the tick must disarm on $why ($rule)", tick.contains(rule))
        }
    }

    @Test
    fun `recovery is Exo's ladder on mpv's signals`() {
        val tick = functionBody(activity, "private fun tickStallWatchdog(")
        assertTrue(
            "a runway still ahead keeps the buffer instead of re-fetching",
            tick.contains("if (buffered > STALL_BUFFER_KEEP_MS)")
        )
        assertTrue(
            "and the reload is the manual retry's own path, not a second mechanism",
            tick.contains("retryCurrentSource()")
        )
        assertTrue(
            "mpv's cache is what 'buffered ahead' means on this engine",
            view.contains("fun cacheAheadMs()") &&
                view.contains("demuxer-cache-time") &&
                view.contains("demuxer-cache-duration") &&
                activity.contains("surface?.cacheAheadMs()")
        )
    }

    @Test
    fun `the cap ends on the existing error card, with no loop`() {
        val tick = functionBody(activity, "private fun tickStallWatchdog(")
        val giveUpAt = tick.indexOf("stallRecoveries >= STALL_MAX_RECOVERIES")
        assertTrue("the cap must be checked before a recovery is spent", giveUpAt > 0)
        val rest = tick.substring(giveUpAt)
        assertTrue(
            "giving up stops the tick chain",
            rest.contains("stallWatchdogToken++")
        )
        assertTrue(
            "and lands on the card whose Retry / Switch engine / Next source already exist",
            rest.contains("showError(") && rest.contains("stalled")
        )
    }

    @Test
    fun `every declaration is a line somebody can read afterwards`() {
        val tick = functionBody(activity, "private fun tickStallWatchdog(")
        val lines = Regex("Log\\.w\\(\\s*\"MPV_STALL\"").findAll(tick).count()
        assertTrue(
            "a stall that recovers silently in the night is a bug report nobody can file: " +
                "declared, kept-buffer, reloaded and gave-up each need their MPV_STALL line",
            lines >= 3
        )
        // The detail is assembled once, above the branches, so every line
        // carries the same fields: position, quiet duration, buffered-ahead.
        assertTrue(
            "and carry position, quiet duration and buffered-ahead",
            tick.contains("val where = \"pos=\${position}ms ahead=\${buffered}ms quiet=\${quietMs}ms\"")
        )
        assertTrue(
            "through the diagnostics ring the MPV errors already write to",
            tick.contains("noteStall(") &&
                functionBody(activity, "private fun noteStall(").contains("PlaybackEngineTrace")
        )
    }

    private fun constant(source: String, name: String): String? =
        Regex("${Regex.escape(name)}\\s*=\\s*[0-9_]+L?").find(source)?.value

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val brace = source.indexOf('{', start + signature.length)
        assertTrue("source missing body: $signature", brace >= 0)
        val rest = source.substring(brace + 1)
        val end = rest.indexOf("\n    }")
        return if (end < 0) rest else rest.substring(0, end)
    }

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

    private companion object {
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val EXO = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
