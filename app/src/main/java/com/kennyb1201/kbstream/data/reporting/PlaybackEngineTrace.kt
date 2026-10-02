package com.kennyb1201.kbstream.data.reporting

/**
 * Every decoder failure and engine handoff of the session, in order — for the
 * diagnostics report.
 *
 * Why: the player already logs all of this under `PLAYER_RETRY` / `PLAYER_DV`,
 * but logcat is not reachable from a TV remote, and the one report that asks
 * this question — "after Skip Intro this TV is out of decoder sources" — was
 * answered from a diagnostics dump that said nothing about decoders at all. The
 * `playback:` line names the start, the stalls and the rebuild count, and the
 * `trickplay:` line names the preview pipeline's own declines, but the recovery
 * ladder in between — resource exhaustion, a missing decoder, a Dolby Vision
 * refusal, and the handoff to the backup engine — recorded nothing. A capture
 * therefore could not say whether the box had run dry of decoders, whether the
 * format was the verdict, or whether the session had changed engines at all.
 *
 * This is that record. It is deliberately a *ring of finished strings* rather
 * than structured events: the ladder already decides what each event means as
 * it logs it, so the useful artifact is the sentence, not a second model of the
 * decision that could drift from the one the player acted on.
 *
 * Deliberately a leaf, like the other report rings: it knows nothing about
 * Media3, the player or the engine, so it can be written from either activity
 * and read by [Diagnostics] without pulling any of that in.
 */
internal object PlaybackEngineTrace {

    /** Events kept. A recovery ladder is a handful of steps; this is a ring. */
    private const val MAX_LINES = 16

    private val lock = Any()

    @Volatile
    private var lines: List<String> = emptyList()

    /** Appends one event line, dropping the oldest past [MAX_LINES]. */
    fun note(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) { lines = (lines + trimmed).takeLast(MAX_LINES) }
    }

    /** The recent events, oldest first. Empty until one has happened. */
    fun lines(): List<String> = lines

    /**
     * The one-line summary for [Diagnostics], or null on a session whose player
     * never had a decoder problem.
     *
     * Null rather than an empty header on purpose: a report from a session that
     * played cleanly should stay exactly as short as it was, and its absence is
     * itself the answer to "was this a decoder session".
     */
    fun summary(): String? {
        val kept = lines()
        if (kept.isEmpty()) return null
        return "decoders: ${kept.size} event(s) · " + kept.joinToString(" · ")
    }

    /**
     * The decoder failure in one clause, so the ladder's own log call and the
     * report agree on the words.
     *
     * [detail] is whatever the ladder knows that the cause alone does not — the
     * codec and its dimensions for a missing decoder, the stream position for a
     * handoff. Kept out of this object's own vocabulary so a caller never has to
     * decide the shape of the sentence twice.
     */
    fun describe(cause: String, detail: String? = null): String {
        val head = cause.trim()
        val tail = detail?.trim().orEmpty()
        return if (tail.isEmpty()) head else "$head ($tail)"
    }

    /** Clears the ring (the "clear diagnostics" action and tests). */
    fun reset() {
        synchronized(lock) { lines = emptyList() }
    }
}
