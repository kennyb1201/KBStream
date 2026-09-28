package com.kennyb1201.kbstream.ui.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep timer is the one playback control a viewer sets while they are
 * about to stop watching it — which makes its failures quiet ones: a timer that
 * arms for the wrong instant, never fires, or cuts an episode off in the middle
 * is only noticed the next morning, if at all.
 *
 * So the rules are pure and pinned here: what each session is offered, what an
 * "End of program" resolves to when the guide is wrong, how the countdown
 * reads, and where the fade starts. The one-second enforcement that reads them
 * lives in the two player activities.
 */
class SleepTimerRulesTest {

    private val minute = 60_000L

    @After
    fun disarm() {
        // The armed state is process-wide by design; nothing may leak between
        // cases through it.
        SleepTimer.cancel()
    }

    // ── what a session is offered ────────────────────────────────────────────

    @Test
    fun `the minute steps are the ones the panel promises, in order`() {
        val minutes = sleepTimerOptions(isLive = false, isEpisode = true, hasProgramEnd = false)
            .filter { it.mode == SleepTimerMode.MINUTES }

        assertEquals(listOf(15, 30, 45, 60, 90), minutes.map { it.minutes })
        assertEquals(listOf("15 min", "30 min", "45 min", "60 min", "90 min"), minutes.map { it.label })
    }

    @Test
    fun `off leads the rows, so leaving the timer is the first press`() {
        val options = sleepTimerOptions(isLive = false, isEpisode = true, hasProgramEnd = false)
        assertEquals(SleepTimerMode.OFF, options.first().mode)
    }

    @Test
    fun `an episode offers the end of the episode and a film the end of the film`() {
        val episode = sleepTimerOptions(isLive = false, isEpisode = true, hasProgramEnd = false)
        val film = sleepTimerOptions(isLive = false, isEpisode = false, hasProgramEnd = false)

        assertEquals(
            "End of episode",
            episode.last { it.mode == SleepTimerMode.END_OF_EPISODE }.label
        )
        assertEquals("End of movie", film.last { it.mode == SleepTimerMode.END_OF_EPISODE }.label)
    }

    @Test
    fun `the live row reads the same words the guide screen does`() {
        // Pinned to the copy a viewer reads beside it, not to whatever the
        // module happens to call the thing.
        val live = sleepTimerOptions(isLive = true, isEpisode = false, hasProgramEnd = true)
            .first { it.mode == SleepTimerMode.END_OF_PROGRAM }

        assertEquals("End of program", live.label)
    }

    @Test
    fun `a live channel offers end of program only when the guide has an end`() {
        val withGuide = sleepTimerOptions(isLive = true, isEpisode = false, hasProgramEnd = true)
        val withoutGuide = sleepTimerOptions(isLive = true, isEpisode = false, hasProgramEnd = false)

        assertTrue(withGuide.any { it.mode == SleepTimerMode.END_OF_PROGRAM })
        assertFalse(withoutGuide.any { it.mode == SleepTimerMode.END_OF_PROGRAM })
        // A live channel must never be offered "end of movie": there is no
        // title, and a wrong row in this list is a wrong answer to a viewer on
        // their way to sleep.
        assertFalse(withoutGuide.any { it.mode == SleepTimerMode.END_OF_EPISODE })
        // Off + the five minute steps, plus the ending when the guide has one.
        assertEquals(7, withGuide.size)
        assertEquals(6, withoutGuide.size)
    }

    // ── deadlines ────────────────────────────────────────────────────────────

    @Test
    fun `a minute choice arms that many minutes from now`() {
        assertEquals(30 * minute, sleepDeadlineMs(nowMs = 1_000L, minutes = 30) - 1_000L)
    }

    @Test
    fun `a program that is already over is not armed in the past`() {
        // A stale EPG whose rows never rolled over: the block's end is behind
        // the clock. The deadline is pulled forward so the timer still fires,
        // rather than silently never.
        val now = 1_000_000L
        val deadline = programSleepDeadlineMs(now, programEndMs = now - 10 * minute)

        assertTrue("deadline=$deadline", deadline > now)
        assertEquals(now + minute, deadline)
    }

    @Test
    fun `an implausibly distant program end is capped at six hours`() {
        // A placeholder all-night block, or a malformed timestamp.
        val now = 1_000_000L
        val deadline = programSleepDeadlineMs(now, programEndMs = now + 40L * 60L * minute)

        assertEquals(now + 6L * 60L * minute, deadline)
    }

    @Test
    fun `a plausible program end is used as it stands`() {
        val now = 1_000_000L
        val end = now + 47 * minute

        assertEquals(end, programSleepDeadlineMs(now, end))
    }

    // ── the countdown and its caption ────────────────────────────────────────

    @Test
    fun `the countdown rounds up, so arming 30 minutes reads 30 minutes`() {
        assertEquals("30 min", sleepCountdownText(30 * minute))
        assertEquals("30 min", sleepCountdownText(29 * minute + 1_000L))
        assertEquals("30 min", sleepCountdownText(29 * minute + 59_000L))
    }

    @Test
    fun `the countdown drops to minutes below an hour and to seconds below one`() {
        assertEquals("1 min", sleepCountdownText(minute))
        assertEquals("59s", sleepCountdownText(59_000L))
        assertEquals("1s", sleepCountdownText(1L))
        assertEquals("0s", sleepCountdownText(0L))
        assertEquals("0s", sleepCountdownText(-5_000L))
    }

    @Test
    fun `the caption says which ending is armed instead of counting down`() {
        val now = 1_000_000L
        val armed = SleepTimerState(
            mode = SleepTimerMode.MINUTES,
            deadlineMs = now + 24 * minute,
            label = "30 min"
        )

        assertEquals("Sleeping in 24 min", sleepTimerStatusText(armed, now))
        assertEquals(
            "Stopping when this title ends",
            sleepTimerStatusText(SleepTimerState(mode = SleepTimerMode.END_OF_EPISODE), now)
        )
        assertEquals(
            "Off — playback runs until you stop it",
            sleepTimerStatusText(SleepTimerState(), now)
        )
    }

    @Test
    fun `a deadline that has passed reads as stopping, not as a negative count`() {
        val now = 1_000_000L
        val expired = SleepTimerState(
            mode = SleepTimerMode.MINUTES,
            deadlineMs = now - 1L,
            label = "30 min"
        )
        assertEquals("Stopping now", sleepTimerStatusText(expired, now))
    }

    // ── the fade ─────────────────────────────────────────────────────────────

    @Test
    fun `the fade holds full volume until its own window and then falls to silence`() {
        assertEquals(1f, sleepFadeGain(20 * minute), 0.0001f)
        assertEquals(1f, sleepFadeGain(SLEEP_FADE_MS), 0.0001f)
        assertEquals(0.5f, sleepFadeGain(SLEEP_FADE_MS / 2), 0.0001f)
        assertEquals(0f, sleepFadeGain(0L), 0.0001f)
        assertEquals(0f, sleepFadeGain(-1L), 0.0001f)
    }

    @Test
    fun `the fade is monotonic, so a timer can only ever get quieter`() {
        // Every step down the window must be no louder than the one above it:
        // a non-monotonic gain would swell the audio back up in the last
        // seconds, which is precisely what wakes a half-asleep viewer.
        var previous = 1f
        for (remaining in SLEEP_FADE_MS downTo 0L step 250L) {
            val gain = sleepFadeGain(remaining)
            assertTrue("gain rose at ${remaining}ms: $previous -> $gain", gain <= previous + 0.0001f)
            previous = gain
        }
        assertEquals(0f, previous, 0.0001f)
    }

    // ── the timer against the binge chain ────────────────────────────────────

    @Test
    fun `an end-of-episode timer forbids an automatic handoff`() {
        // "Stop when this episode ends" and "start the next one on its own"
        // cannot both happen, and the timer is the instruction the viewer gave
        // deliberately - the Up Next card is already up by the time they arm it.
        assertTrue(
            sleepTimerBlocksAutoAdvance(
                SleepTimerState(mode = SleepTimerMode.END_OF_EPISODE)
            )
        )
    }

    @Test
    fun `a minutes timer leaves the auto-advance alone`() {
        // The viewer asked to be asleep by a time, not for this episode to be
        // the last one. A ninety-minute timer has to survive an episode
        // rollover or it is useless.
        val armed = SleepTimerState(
            mode = SleepTimerMode.MINUTES,
            deadlineMs = 90 * minute,
            label = "90 min"
        )

        assertFalse(sleepTimerBlocksAutoAdvance(armed))
    }

    @Test
    fun `an end-of-program timer leaves the auto-advance alone`() {
        // It is a clock deadline on a live channel, not a statement about a
        // title's ending - and a live channel has no next episode to hand off
        // to anyway.
        val armed = SleepTimerState(
            mode = SleepTimerMode.END_OF_PROGRAM,
            deadlineMs = 60 * minute,
            label = "End of program"
        )

        assertFalse(sleepTimerBlocksAutoAdvance(armed))
    }

    @Test
    fun `nothing armed never blocks anything`() {
        assertFalse(sleepTimerBlocksAutoAdvance(SleepTimerState()))
    }

    // ── arming ───────────────────────────────────────────────────────────────

    @Test
    fun `a minute choice arms a wall-clock deadline and names itself`() {
        val now = 5_000_000L
        SleepTimer.select(SleepTimerOption("45 min", SleepTimerMode.MINUTES, 45), nowMs = now)

        val state = SleepTimer.state.value
        assertEquals(SleepTimerMode.MINUTES, state.mode)
        assertEquals(now + 45 * minute, state.deadlineMs)
        assertEquals("45 min", state.label)
        assertTrue(state.isArmed)
        assertFalse(state.stopsAtEndOfItem)
    }

    @Test
    fun `end of episode arms no deadline at all`() {
        // It is honored where the episode ends, not on a clock, so a wrong
        // runtime cannot make it fire early or never.
        SleepTimer.select(
            SleepTimerOption("End of episode", SleepTimerMode.END_OF_EPISODE),
            nowMs = 1_000L
        )

        val state = SleepTimer.state.value
        assertNull(state.deadlineMs)
        assertTrue(state.stopsAtEndOfItem)
    }

    @Test
    fun `end of program arms the guide's own end`() {
        val now = 2_000_000L
        val end = now + 35 * minute
        SleepTimer.select(
            SleepTimerOption("End of program", SleepTimerMode.END_OF_PROGRAM),
            nowMs = now,
            programEndMs = end
        )

        assertEquals(end, SleepTimer.state.value.deadlineMs)
        assertEquals(SleepTimerMode.END_OF_PROGRAM, SleepTimer.state.value.mode)
    }

    @Test
    fun `end of program with no usable end clears the timer rather than arming a guess`() {
        // The panel only offers the row when the guide has an end, so this is
        // the guide data going away between drawing the row and pressing it.
        // Arming an hour would be a lie in the one place it cannot be checked.
        SleepTimer.select(SleepTimerOption("30 min", SleepTimerMode.MINUTES, 30), nowMs = 0L)
        assertTrue(SleepTimer.state.value.isArmed)

        SleepTimer.select(
            SleepTimerOption("End of program", SleepTimerMode.END_OF_PROGRAM),
            nowMs = 0L,
            programEndMs = null
        )

        assertFalse(SleepTimer.state.value.isArmed)
    }

    // ── which row the panel lights up ────────────────────────────────────────

    @Test
    fun `the Off row is the one lit while nothing is armed`() {
        // The unarmed state carries no label, so "Off" has to be matched on the
        // mode: without this the panel would show every row unselected and read
        // as a timer that forgot what it was doing.
        val options = sleepTimerOptions(isLive = false, isEpisode = true, hasProgramEnd = false)
        val state = SleepTimerState()

        assertTrue(state.marks(options.first { it.mode == SleepTimerMode.OFF }))
        assertFalse(state.marks(options.first { it.mode == SleepTimerMode.MINUTES }))
    }

    @Test
    fun `only the minute step that was chosen lights up`() {
        SleepTimer.select(SleepTimerOption("45 min", SleepTimerMode.MINUTES, 45), nowMs = 0L)
        val options = sleepTimerOptions(isLive = false, isEpisode = true, hasProgramEnd = false)
        val state = SleepTimer.state.value

        val lit = options.filter { state.marks(it) }
        assertEquals(listOf("45 min"), lit.map { it.label })
    }

    @Test
    fun `an ending lights up its own row and not the minute steps`() {
        SleepTimer.select(
            SleepTimerOption("End of episode", SleepTimerMode.END_OF_EPISODE),
            nowMs = 0L
        )
        val options = sleepTimerOptions(isLive = false, isEpisode = true, hasProgramEnd = false)
        val state = SleepTimer.state.value

        val lit = options.filter { state.marks(it) }
        assertEquals(listOf(SleepTimerMode.END_OF_EPISODE), lit.map { it.mode })
    }

    @Test
    fun `choosing Off clears whatever was armed`() {
        SleepTimer.select(
            SleepTimerOption("End of episode", SleepTimerMode.END_OF_EPISODE),
            nowMs = 0L
        )
        SleepTimer.select(SleepTimerOption("Off", SleepTimerMode.OFF), nowMs = 0L)

        assertEquals(SleepTimerMode.OFF, SleepTimer.state.value.mode)
        assertNull(SleepTimer.state.value.deadlineMs)
        assertFalse(SleepTimer.state.value.isArmed)
    }
}
