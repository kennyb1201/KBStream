package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.youtube.PlayableSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero trailer's retry-after-failure rule.
 *
 * The bug this pins: a trailer whose signed URL went stale mid-stream was
 * re-resolved with a fresh URL and restarted from 0, so a viewer saw the trailer
 * stop, blip, and begin again. The fix is a resume position carried by the
 * retry - which means two things have to hold, and both are asserted here:
 *
 *  1. the near-end guard arithmetic (a failure in the last
 *     [HeroTrailerNearEndMs] ends instead of replaying the tail), and
 *  2. the ORDER inside the error handler: the position must be read before the
 *     fallback releases the pooled player, because a released player reports 0
 *     and would quietly turn every retry back into a restart-from-zero.
 *
 * The seek itself needs a device: killing the network mid-trailer and watching
 * it resume is the only real proof, and the source checks below are the closest
 * a unit test can get.
 */
class HeroTrailerResumeTest {

    // --------------------------------------------------- near-end arithmetic --

    @Test
    fun `a failure in the last three seconds ends instead of retrying`() {
        // 30 s trailer, error at 29.5 s: replaying the tail is worse than the
        // backdrop, which is what a clean ending shows anyway.
        assertFalse(shouldRetryHeroTrailerAfterError(positionMs = 29_500L, durationMs = 30_000L))
        assertFalse(shouldRetryHeroTrailerAfterError(positionMs = 28_500L, durationMs = 30_000L))
        assertFalse(shouldRetryHeroTrailerAfterError(positionMs = 30_000L, durationMs = 30_000L))
    }

    @Test
    fun `the boundary is inclusive - exactly three seconds left still retries`() {
        assertTrue(shouldRetryHeroTrailerAfterError(positionMs = 27_000L, durationMs = 30_000L))
        assertFalse(shouldRetryHeroTrailerAfterError(positionMs = 27_001L, durationMs = 30_000L))
    }

    @Test
    fun `a mid-trailer failure retries`() {
        // The reported case: half-way through.
        assertTrue(shouldRetryHeroTrailerAfterError(positionMs = 15_000L, durationMs = 30_000L))
        assertTrue(shouldRetryHeroTrailerAfterError(positionMs = 1L, durationMs = 30_000L))
        assertTrue(shouldRetryHeroTrailerAfterError(positionMs = 0L, durationMs = 30_000L))
    }

    @Test
    fun `an unknown duration retries - that is the stale-url case`() {
        // ExoPlayer reports C.TIME_UNSET when the media never got far enough to
        // report a length, which is exactly what a stale signed URL 403ing on
        // the first chunk looks like. Skipping the retry here would disable the
        // fresh-URL retry in the one case it exists for.
        val timeUnset = Long.MIN_VALUE + 1
        assertTrue(shouldRetryHeroTrailerAfterError(positionMs = 0L, durationMs = timeUnset))
        assertTrue(shouldRetryHeroTrailerAfterError(positionMs = 1_234L, durationMs = timeUnset))
        assertTrue(
            "a zero duration is 'not known yet', not 'already ended'",
            shouldRetryHeroTrailerAfterError(positionMs = 0L, durationMs = 0L)
        )
        assertTrue(shouldRetryHeroTrailerAfterError(positionMs = 0L, durationMs = -1L))
    }

    @Test
    fun `a position past the duration ends`() {
        assertFalse(shouldRetryHeroTrailerAfterError(positionMs = 31_000L, durationMs = 30_000L))
    }

    @Test
    fun `a negative position is treated as the start`() {
        assertTrue(shouldRetryHeroTrailerAfterError(positionMs = -5_000L, durationMs = 30_000L))
        assertTrue(
            "a 3 s trailer with a nonsense position still has its full length left",
            shouldRetryHeroTrailerAfterError(positionMs = -5_000L, durationMs = 3_000L)
        )
        assertFalse(
            "coercion must not let a nonsense position mask the near-end window",
            shouldRetryHeroTrailerAfterError(positionMs = -5_000L, durationMs = 2_999L)
        )
    }

    @Test
    fun `the window is a small positive number`() {
        assertTrue(HeroTrailerNearEndMs > 0L)
        assertTrue(
            "the near-end window must not swallow a normal mid-trailer failure",
            HeroTrailerNearEndMs < 10_000L
        )
    }

    // ------------------------------------------------------- first-play shape --

    @Test
    fun `a playback value with no resume point starts at zero`() {
        val playback = HeroTrailerPlayback(source = stubSource())
        assertEquals("first play must not seek", 0L, playback.startPositionMs)
    }

    @Test
    fun `the resume point travels with its own source`() {
        // Structural, not temporal: the position is part of the value that
        // carries the source, so a different source can never be played at a
        // position left over from a previous playback session.
        val resumed = HeroTrailerPlayback(source = stubSource(), startPositionMs = 4_200L)
        val fresh = HeroTrailerPlayback(source = stubSource(), startPositionMs = 0L)
        assertNotEquals(resumed, fresh)
        assertEquals(4_200L, resumed.copy().startPositionMs)
    }

    // ---------------------------------------------------------------- wiring --

    @Test
    fun `the error handler reads the position before the fallback releases the player`() {
        val handler = errorHandler()
        val positionAt = handler.indexOf("val positionMs = exoPlayer.currentPosition")
        // Anchored on the CALL at its own indentation: the comment above it
        // explains this ordering and names onEnded() itself, so a bare search
        // for the name matches the comment instead of the statement.
        val endedAt = handler.indexOf("\n                    onEnded()")
        assertTrue("the handler must read the position", positionAt >= 0)
        assertTrue("the handler must fall back to the backdrop", endedAt >= 0)
        assertTrue(
            "reading the position AFTER onEnded() releases the pooled player, " +
                "which reports 0 - every retry would restart from the top",
            positionAt < endedAt
        )
        assertTrue(
            "the duration is read from the same live player, before the release",
            handler.indexOf("exoPlayer.duration") in 0 until endedAt
        )
    }

    @Test
    fun `the guard is what decides whether the retry happens`() {
        val handler = errorHandler()
        assertTrue(
            "the near-end decision must gate the retry",
            handler.contains("if (shouldRetryHeroTrailerAfterError(positionMs, durationMs))")
        )
        assertTrue(
            "and the position must be what is handed over",
            handler.contains("onFailed(positionMs)")
        )
    }

    @Test
    fun `the single-retry cap is unchanged`() {
        val screen = screen()
        assertTrue(
            "one retry per trailer: a genuinely dead video must not loop",
            screen.contains("if (trailerAttempt < 1) {")
        )
        assertTrue(
            "the retry still busts the cached (possibly stale) URL",
            screen.contains("TrailerPlayerLauncher.invalidate(trailerKey)")
        )
    }

    @Test
    fun `the resume position is only written when a retry will really happen`() {
        val failed = between(
            rawScreen(),
            "onFailed = { positionMs ->",
            "    } else {\n        AsyncImage("
        )
        val capAt = failed.indexOf("if (trailerAttempt < 1) {")
        val writeAt = failed.indexOf("trailerResumeMs = positionMs")
        assertTrue("the cap must exist", capAt >= 0)
        assertTrue("the resume write must exist", writeAt >= 0)
        assertTrue(
            "a position written on the capped failure would be picked up by a " +
                "later re-arm and seek a fresh play",
            writeAt > capAt
        )
        assertTrue(
            "and only when the retry is taken",
            failed.indexOf("trailerAttempt += 1") > writeAt
        )
    }

    @Test
    fun `the resume point is consumed with the resolve that uses it`() {
        val resolve = between(
            rawScreen(),
            "LaunchedEffect(trailerPlaying, trailerKey, trailerAttempt, resumeEpoch) {",
            "if (trailerPlaying && !trailerKey.isNullOrBlank()) {"
        )
        assertTrue(
            "the retry's resolve must read the handoff",
            resolve.contains("val resumeFrom = trailerResumeMs")
        )
        assertTrue(
            "and clear it, so no later re-arm can seek to a dead position",
            resolve.contains("trailerResumeMs = 0L")
        )
        assertTrue(
            "the start position travels with the resolved source",
            rawScreen().contains("startPositionMs = resumeFrom")
        )
    }

    @Test
    fun `the seek is applied once per source, after prepare`() {
        val screen = screen()
        val prepareAt = screen.indexOf("exoPlayer.prepare()")
        val seekAt = screen.indexOf("exoPlayer.seekTo(startPositionMs)")
        assertTrue(prepareAt >= 0)
        assertTrue("the retry must actually seek", seekAt >= 0)
        assertTrue(
            "a seek before prepare is dropped with the timeline",
            seekAt > prepareAt
        )
        assertTrue(
            "and it must be guarded, so a first play never seeks",
            screen.contains("if (startPositionMs > 0L) {")
        )
        assertEquals(
            "exactly one seek site",
            1,
            Regex("seekTo\\(").findAll(screen).count()
        )
    }

    @Test
    fun `the call site passes the resume point and the pooled player is still released`() {
        val screen = screen()
        assertTrue(
            "the player must be handed the resolved start position",
            screen.contains("startPositionMs = trailerPlayback.startPositionMs")
        )
        assertTrue(
            "dropping the source still silences the pooled player",
            screen.contains("if (resolvedTrailer == null) { TrailerPlayerPool.releaseForReuse() }")
        )
    }

    @Test
    fun `looping and the pool lifecycle are untouched`() {
        val screen = screen()
        assertTrue(screen.contains("exoPlayer.repeatMode = Player.REPEAT_MODE_OFF"))
        assertTrue(screen.contains("TrailerPlayerPool.releaseForReuse()"))
        assertTrue(screen.contains("TrailerPlayerPool.pauseCurrent()"))
        assertFalse(
            "a retry must never introduce a loop",
            screen.contains("REPEAT_MODE_ALL") || screen.contains("REPEAT_MODE_ONE")
        )
    }

    @Test
    fun `the start and rebuffer budgets are untouched`() {
        val screen = screen()
        assertTrue(screen.contains("private const val HeroTrailerStartGraceMs = 8_000L"))
        assertTrue(screen.contains("private const val HeroTrailerRebufferGraceMs = 30_000L"))
        assertTrue(
            "this change is the error path only: the re-buffer grace still applies",
            screen.contains("handler.postDelayed(watchdog, HeroTrailerRebufferGraceMs)")
        )
    }

    // --------------------------------------------------------------- helpers --

    private fun stubSource(): PlayableSource =
        PlayableSource.Muxed(url = "https://googlevideo.example/x", userAgent = null)

    /** The file verbatim, for anchors that span lines. */
    private fun rawScreen(): String =
        File(findSourceRoot(), SCREEN).readText()

    /** Whitespace-insensitive haystack, so indentation is not the test. */
    private fun screen(): String = rawScreen().replace(Regex("\\s+"), " ")

    /** The `onPlayerError` body, up to the end of the listener object. */
    private fun errorHandler(): String = between(
        rawScreen(),
        "override fun onPlayerError(",
        "exoPlayer.addListener(listener)"
    )

    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end after $start", to > from)
        return source.substring(from, to)
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
        const val SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
