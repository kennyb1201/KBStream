package com.kennyb1201.kbstream.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What an armed sleep timer was asked to stop on.
 *
 * The two content-based modes exist because they are the two things a viewer
 * actually means by "I'm going to fall asleep": *finish what I'm watching, then
 * stop* ([END_OF_EPISODE]) and *stop when this programme is over*
 * ([END_OF_PROGRAMME], live only). A minutes timer is the fallback for when
 * neither is on the table — a live channel with no guide data, or a viewer who
 * simply wants to be off to sleep by a certain time.
 */
internal enum class SleepTimerMode {
    OFF,

    /** Stops [SleepTimerState.deadlineMs], a wall-clock instant. */
    MINUTES,

    /**
     * Stops when the title playing now finishes, instead of chaining into the
     * next episode. Content-based, not clock-based: there is no deadline to
     * watch, which is what makes it immune to a wrong runtime.
     */
    END_OF_EPISODE,

    /** Live: stops when the guide's current programme block ends. */
    END_OF_PROGRAMME
}

/** One row of the panel's SLEEP TIMER section. */
internal data class SleepTimerOption(
    val label: String,
    val mode: SleepTimerMode,
    val minutes: Int = 0
)

/**
 * The armed timer.
 *
 * [label] is the option's own wording so the panel can mark the row that is in
 * force without re-deriving it, and so an "End of program" armed on one
 * channel still reads correctly after a zap.
 */
internal data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val deadlineMs: Long? = null,
    val label: String = ""
) {
    val isArmed: Boolean get() = mode != SleepTimerMode.OFF

    /**
     * True for the content-based ending: the player must not chain into the
     * next episode, and the end-of-episode panels are suppressed rather than
     * offering a PLAY NEXT the timer is about to override.
     */
    val stopsAtEndOfItem: Boolean get() = mode == SleepTimerMode.END_OF_EPISODE

    /**
     * Whether [option] is the row the panel should draw as in force.
     *
     * Matched on the option's own word rather than on its minutes, so a row the
     * viewer pressed is the row that lights up however the session's choices
     * were assembled - and because "Off" is a label like any other while an
     * unarmed state has no label to compare against.
     */
    fun marks(option: SleepTimerOption): Boolean = when (mode) {
        SleepTimerMode.OFF -> option.mode == SleepTimerMode.OFF
        else -> mode == option.mode && label == option.label
    }
}

/** The minute steps the panel offers, in the order it draws them. */
internal val SLEEP_TIMER_MINUTE_CHOICES = listOf(15, 30, 45, 60, 90)

/** Length of the audio fade that ends a timed sleep, in milliseconds. */
internal const val SLEEP_FADE_MS = 20_000L

/**
 * How a live "end of program" is clamped: the guide's own end when it is
 * usable, otherwise the viewer is never told they will be stopped.
 *
 * A block that ends in the past (a stale EPG whose rows never rolled over) or
 * absurdly far out (a malformed timestamp, or a placeholder all-night block)
 * is not something to hand a sleeping viewer, so the ends are pulled into this
 * window: at least a minute of playback, at most six hours.
 */
private const val PROGRAMME_MIN_LEAD_MS = 60_000L
private const val PROGRAMME_MAX_SPAN_MS = 6L * 60L * 60L * 1000L

/** How long the "minutes" choices run for. Wall clock, not content. */
internal fun sleepDeadlineMs(nowMs: Long, minutes: Int): Long =
    nowMs + minutes.coerceAtLeast(1) * 60_000L

/** Wall-clock deadline for "end of program", clamped as described above. */
internal fun programmeSleepDeadlineMs(nowMs: Long, programmeEndMs: Long): Long =
    programmeEndMs.coerceIn(nowMs + PROGRAMME_MIN_LEAD_MS, nowMs + PROGRAMME_MAX_SPAN_MS)

/**
 * The rows the panel shows, in order: Off, the minute steps, then whatever
 * ending this session can actually honour.
 *
 * "End of program" is offered **only** when the guide has a current block
 * that ends in the future. A row that silently degraded to an hour because the
 * EPG was empty would be a lie in the one place a viewer cannot check it —
 * they are on their way to sleep.
 */
internal fun sleepTimerOptions(
    isLive: Boolean,
    isEpisode: Boolean,
    hasProgrammeEnd: Boolean
): List<SleepTimerOption> {
    val endings = when {
        isLive && hasProgrammeEnd ->
            listOf(SleepTimerOption("End of program", SleepTimerMode.END_OF_PROGRAMME))

        isLive -> emptyList()

        else -> listOf(
            SleepTimerOption(
                if (isEpisode) "End of episode" else "End of movie",
                SleepTimerMode.END_OF_EPISODE
            )
        )
    }
    return listOf(SleepTimerOption("Off", SleepTimerMode.OFF)) +
        SLEEP_TIMER_MINUTE_CHOICES.map {
            SleepTimerOption("$it min", SleepTimerMode.MINUTES, it)
        } +
        endings
}

/**
 * The countdown itself, rounded **up**: arming "30 min" must read "30 min" on
 * the next frame rather than dropping to 29 the moment it is pressed, and the
 * last minute counts in seconds so the fade does not arrive unannounced.
 */
internal fun sleepCountdownText(remainingMs: Long): String = when {
    remainingMs <= 0L -> "0s"
    remainingMs < 60_000L -> "${(remainingMs / 1_000L).coerceAtLeast(1L)}s"
    else -> "${(remainingMs + 59_999L) / 60_000L} min"
}

/** The caption under the section's pills. */
internal fun sleepTimerStatusText(state: SleepTimerState, nowMs: Long): String {
    if (!state.isArmed) return "Off — playback runs until you stop it"
    if (state.stopsAtEndOfItem) return "Stopping when this title ends"
    val deadline = state.deadlineMs ?: return "Off — playback runs until you stop it"
    val remaining = deadline - nowMs
    return if (remaining <= 0L) {
        "Stopping now"
    } else {
        "Sleeping in ${sleepCountdownText(remaining)}"
    }
}

/**
 * Whether an armed sleep timer forbids an **automatic** handoff to the next
 * episode.
 *
 * Only the content-based ending does: "stop when this episode ends" and "start
 * the next one on its own" are contradictory instructions, and the timer is the
 * one the viewer gave deliberately - it takes opening the settings panel and
 * pressing a row. A minutes timer deliberately does **not** block a handoff: the
 * viewer asked for a time rather than for this episode to be the last, and a
 * ninety-minute timer has to survive an episode rollover or it is useless.
 *
 * This is what the Up Next countdown re-checks on every tick, so arming the
 * timer *after* the card appeared - which is the normal way the two meet, since
 * the card opens during the credits - is honoured too, right up to the last
 * second.
 *
 * Deliberately not applied to a handoff the viewer presses for: an explicit
 * PLAY NEXT cancels the timer instead (see each engine's launchNextEpisode).
 */
internal fun sleepTimerBlocksAutoAdvance(state: SleepTimerState): Boolean =
    state.stopsAtEndOfItem

/**
 * The output gain the fade multiplies playback by: 1 while there is time left,
 * falling linearly to 0 at the stop instant.
 *
 * A fade and not a cut because the timer usually fires in the middle of a
 * scene, and a hard stop from full volume is what wakes a half-asleep viewer.
 */
internal fun sleepFadeGain(remainingMs: Long, fadeMs: Long = SLEEP_FADE_MS): Float {
    if (fadeMs <= 0L) return if (remainingMs > 0L) 1f else 0f
    if (remainingMs >= fadeMs) return 1f
    if (remainingMs <= 0L) return 0f
    return (remainingMs.toFloat() / fadeMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * The armed timer, shared by both engines.
 *
 * A process-wide object rather than a field on either player because the timer
 * belongs to the *evening*, not to the title: next-episode autoplay starts a
 * brand-new player activity, and an "I want to be asleep in 30 minutes" that
 * reset itself at every episode boundary would be useless. It is deliberately
 * **not** persisted — a sleep timer is a fact about tonight, and a deadline
 * resurrected after a reboot days later would stop playback for no reason the
 * viewer can see.
 *
 * It is also enforced by whichever player is on screen, so the countdown keeps
 * running across an engine handoff (the MPV fallback, an installed external
 * player, a switch from the bar) without either engine having to carry it
 * across the handoff itself.
 */
internal object SleepTimer {

    private val _state = MutableStateFlow(SleepTimerState())

    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    /**
     * Arms [option]. "End of program" with no usable block end clears the
     * timer instead of arming something that cannot fire — the panel only
     * offers that row when the guide has an end, so this is the case where the
     * guide data went away between drawing the row and pressing it.
     */
    fun select(option: SleepTimerOption, nowMs: Long, programmeEndMs: Long? = null) {
        _state.value = when (option.mode) {
            SleepTimerMode.OFF -> SleepTimerState()

            SleepTimerMode.MINUTES -> SleepTimerState(
                mode = option.mode,
                deadlineMs = sleepDeadlineMs(nowMs, option.minutes),
                label = option.label
            )

            SleepTimerMode.END_OF_PROGRAMME -> programmeEndMs?.let { end ->
                SleepTimerState(
                    mode = option.mode,
                    deadlineMs = programmeSleepDeadlineMs(nowMs, end),
                    label = option.label
                )
            } ?: SleepTimerState()

            SleepTimerMode.END_OF_EPISODE -> SleepTimerState(mode = option.mode, label = option.label)
        }
    }

    /** Disarms. Called when the timer fires and when the viewer picks Off. */
    fun cancel() {
        _state.value = SleepTimerState()
    }
}
