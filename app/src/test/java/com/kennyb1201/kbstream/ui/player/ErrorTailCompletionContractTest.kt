package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source error in the last 5% of an episode files the completion instead of
 * running the recovery ladder.
 *
 * The field report (Onn 4K Plus, 0.6.110, S04E03 `tt27053309`): the viewer
 * paused at 77.43%, resumed, played on to 99.28% - and the Matroska tail threw
 * `No valid varint length mask found`. `onPlayerError` went to its recovery
 * ladder, the rebuild re-ran the launch resume-restore, and the resume point
 * read back was the one written at the PAUSE (resume rows are written on
 * pause/exit, not continuously). Playback restarted at 77.43%, the completion
 * was never filed, and the episode sat in Continue Watching with nine minutes
 * "left" on it. The `byw shown filed=false` assertion the Oct 13 completion
 * fix planted is what caught it: that fix covers the natural end, and a source
 * error at the tail bypassed completion filing entirely.
 *
 * The two facts this class pins are the ones a refactor can quietly lose, and
 * neither is visible to a JVM test any other way (the engines drive an
 * ExoPlayer/libmpv surface an Activity needs a device for):
 *
 *  1. the decision is made BEFORE the ladder - before the link-failure
 *     classification, before `scheduleRetry()`, before the MPV handoff. After
 *     the ladder has rebuilt the player, the error-time playhead is gone and
 *     the only position left to read is the stale resume point, which is the
 *     bug itself;
 *  2. what it does on a hit is the EXISTING completion machinery - the ordinary
 *     save with `forceCompleted = true` (completed row, tracker push, and the
 *     writer's delete-and-tombstone of the superseded resume rows) - and
 *     nothing else. No retry, no rebuild, no source switch, no error card.
 *
 * The threshold itself is [PlayerCompletionRulesTest]'s subject; this class
 * only pins that both engines hand it their own `COMPLETION_THRESHOLD_RATIO`
 * rather than a second number that could drift from the save that follows.
 */
class ErrorTailCompletionContractTest {

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

    /**
     * The text of a four-space member starting at [signature], up to the next
     * member. Nested braces (an anonymous listener) are not a problem for the
     * helpers pinned here, which are members of the activity.
     */
    private fun body(source: String, signature: String): String {
        val at = source.indexOf(signature)
        assertTrue("source missing member: $signature", at >= 0)
        val rest = source.substring(at + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** Per-line indentation stripped, so nested guards can be matched. */
    private fun normalized(raw: String): String = raw.lines().joinToString("\n") { it.trim() }

    private val native by lazy { read(NATIVE) }
    private val mpv by lazy { read(MPV) }

    // ── 1. the decision is made before the ladder ──────────────────────────

    @Test
    fun `the native error handler files a tail completion before recovering`() {
        val at = native.indexOf("override fun onPlayerError(error: PlaybackException) {")
        assertTrue("onPlayerError is what this fix hangs on", at >= 0)
        // A bounded window: the handler is ~24k characters, and everything the
        // ordering is about sits in its first few thousand.
        val handler = native.substring(at, minOf(native.length, at + 6_000))
        val decided = handler.indexOf("fileErrorTailCompletion()")
        assertTrue(
            "a source error has to consult the completion threshold at all: " +
                "without it, the tail error goes straight to the rebuild that " +
                "resurrects the resume point written at the viewer's pause",
            decided >= 0
        )
        assertTrue(
            "and it must RETURN once the completion is filed, so no recovery runs",
            normalized(handler).contains("if (fileErrorTailCompletion()) return")
        )

        // Every rung of the ladder below must come after the decision.
        val ladder = listOf(
            "PlaybackRecoveryRules.isLinkFailure(error)",
            "scheduleRetry()",
            "handOffToMpv(",
            "updateUIError()"
        )
        ladder.forEach { rung ->
            val atRung = handler.indexOf(rung)
            assertTrue("$rung no longer exists in onPlayerError", atRung >= 0)
            assertTrue(
                "the threshold decision must precede $rung: reading the playhead " +
                    "after a rung has acted reads it off a player that is being rebuilt, " +
                    "or off the stored resume point the ladder is about to restore",
                decided < atRung
            )
        }
    }

    // ── 2. the native path writes the ordinary completion and nothing else ──

    @Test
    fun `the native tail completion reuses the save's own guards and threshold`() {
        val helper = normalized(body(native, "private fun fileErrorTailCompletion(): Boolean {"))
        assertTrue(
            "the guards are saveProgress's own: a live channel never completes, and a " +
                "session with no title/episode identity has nothing to file against",
            helper.contains("if (isLiveChannel || parentId.isBlank() || historyId.isBlank()) return false")
        )
        assertTrue(
            "the decision reads the ERROR-TIME playhead, while the player is still alive " +
                "- not the stored resume point",
            helper.contains("player.currentPosition")
        )
        assertFalse(
            "and specifically not PlaybackResume, whose value is the 77.43% pause point " +
                "the field report restarted from",
            helper.contains("PlaybackResume")
        )
        assertTrue(
            "the threshold is the save's own constant, not a second ratio",
            helper.contains("errorAtCompletionThreshold(pos, dur ?: 0L, COMPLETION_THRESHOLD_RATIO)")
        )
        assertTrue(
            "and an unknown duration is no tail: it must not be turned into one",
            helper.contains("if (rawDur == C.TIME_UNSET || rawDur <= 0L) null else rawDur")
        )
        assertTrue(
            "the completion goes through the existing save, with the field's own reason " +
                "so a log says which path filed it",
            helper.contains("saveProgress(reason = \"error-tail-completion\", forceCompleted = true)")
        )
        assertTrue(
            "and the session routes to the same end state a natural end does",
            helper.contains("routeAfterCompletedEnd()")
        )
        assertTrue(
            "it must report which path ran, next to the ladder's own lines",
            helper.contains("source error inside the completion threshold")
        )
    }

    @Test
    fun `the native tail completion never enters the recovery ladder`() {
        val helper = normalized(body(native, "private fun fileErrorTailCompletion(): Boolean {"))
        listOf(
            "scheduleRetry(",
            "recreatePlayer(",
            "tryNextSource(",
            "handOffToMpv(",
            "updateUIError(",
            "errorMessageStr =",
            "lastPlaybackError ="
        ).forEach { rung ->
            assertFalse(
                "a filed completion must not $rung: the episode is finished, so a " +
                    "rebuild only risks restarting it from stale history",
                helper.contains(rung)
            )
        }
    }

    // ── 3. the MPV engine files it too, and takes the card down ────────────

    @Test
    fun `the mpv stream death files a tail completion instead of raising the card`() {
        assertTrue(
            "the MPV engine's played-then-died entry must consult the threshold",
            normalized(mpv).contains("if (!fileErrorTailCompletion()) {")
        )
        val handler = body(mpv, "view.onStreamDied = { message ->")
        val decided = handler.indexOf("fileErrorTailCompletion()")
        val card = handler.indexOf("showError(")
        assertTrue("the MPV handler must show the error card at all", card >= 0)
        assertTrue(
            "the card belongs to a recovery, so the completion is decided before it " +
                "- a tail completion raises no card at all",
            decided >= 0 && decided < card
        )

        val helper = normalized(body(mpv, "private fun fileErrorTailCompletion(): Boolean {"))
        assertTrue(
            "the guards are saveProgress's own (this engine has no live path)",
            helper.contains("if (parentId.isBlank() || historyId.isBlank()) return false")
        )
        assertTrue(
            "mpv's own last position and duration, read in the handler",
            helper.contains("view.positionMs()") && helper.contains("view.durationMs()")
        )
        assertTrue(
            "the same threshold constant the save uses",
            helper.contains("errorAtCompletionThreshold(pos, dur, COMPLETION_THRESHOLD_RATIO)")
        )
        assertTrue(
            "the same existing save, with the same reason",
            helper.contains("saveProgress(reason = \"error-tail-completion\", forceCompleted = true)")
        )
        assertTrue(
            "and the card comes down, or it would sit over the end state",
            helper.contains("errorContainer?.visibility = View.GONE")
        )
        assertTrue(
            "then the same end state a natural end reaches",
            helper.contains("routeAfterCompletedEnd()")
        )
        listOf("retryCurrentSource(", "tryNextSource(", "startPositionMs =", "showError(").forEach { rung ->
            assertFalse(
                "this engine's retry re-opens the source at the live playhead - at " +
                    "99.28% that is the same corrupt tail - or walks the ladder: " +
                    "neither may be $rung on a filed completion",
                helper.contains(rung)
            )
        }
    }

    // ── 4. both engines reach the SAME end state as a natural end ──────────

    @Test
    fun `a filed tail completion ends the episode like a natural end does`() {
        listOf(
            "the native end" to body(native, "private fun onPlaybackEnded() {"),
            "the MPV end" to body(mpv, "private fun onPlaybackEnded() {")
        ).forEach { (what, ended) ->
            assertTrue(
                "$what must route through the shared end state, so the error path " +
                    "and this one cannot drift into two different endings",
                ended.contains("routeAfterCompletedEnd()")
            )
            // …and the natural end must still file its completion before it,
            // which is what the shared helper assumes.
            assertTrue(
                "$what must still write the completion itself",
                ended.contains("saveProgress(reason = \"ended\", forceCompleted = true)")
            )
            val order = ended.indexOf("saveProgress(reason = \"ended\", forceCompleted = true)") <
                ended.indexOf("routeAfterCompletedEnd()")
            assertTrue("$what: the completion must be written first", order)
        }

        listOf(
            "the native end state" to normalized(body(native, "private fun routeAfterCompletedEnd() {")),
            "the MPV end state" to normalized(body(mpv, "private fun routeAfterCompletedEnd() {"))
        ).forEach { (what, helper) ->
            assertTrue(
                "$what must honor a sleep timer armed for the end of the episode",
                helper.contains("if (sleepTimerBlocksAutoAdvance(SleepTimer.state.value)) {") &&
                    helper.contains("exitForSleepTimer(savePartialProgress = false)")
            )
            assertTrue(
                "$what must let a held up-next countdown run",
                helper.contains("if (nextUpCountdownHeld) armNextUpAutoAdvance(remainingMs = 0L)")
            )
            assertTrue("$what must raise the panel", helper.contains("showEndPanels()"))
        }
    }

    // ── 5. one threshold, in both directions ──────────────────────────────

    @Test
    fun `the error path shares the save's threshold rather than inventing one`() {
        assertTrue(
            "the native save's threshold must be untouched",
            native.contains("private const val COMPLETION_THRESHOLD_RATIO = 0.95f")
        )
        assertTrue(
            "and so must the MPV one",
            mpv.contains("private const val COMPLETION_THRESHOLD_RATIO = 0.95f")
        )
        listOf(
            "the native helper" to normalized(body(native, "private fun fileErrorTailCompletion(): Boolean {")),
            "the MPV helper" to normalized(body(mpv, "private fun fileErrorTailCompletion(): Boolean {"))
        ).forEach { (what, helper) ->
            assertTrue(
                "$what must pass COMPLETION_THRESHOLD_RATIO",
                helper.contains("COMPLETION_THRESHOLD_RATIO")
            )
            assertFalse(
                "$what must not carry a second ratio of its own (0.95/95 written out, " +
                    "or any other fraction): a divergent number is exactly how the error " +
                    "path and the save it calls would disagree about whether an episode is over",
                Regex("0\\.9\\d*|0\\.8\\d*|95f|percent").containsMatchIn(helper)
            )
        }
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
