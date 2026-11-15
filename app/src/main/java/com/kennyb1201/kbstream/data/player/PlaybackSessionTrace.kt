package com.kennyb1201.kbstream.data.player

import android.os.SystemClock
import com.kennyb1201.kbstream.data.reporting.PerfTrace

/**
 * The leg the `playback:` line could not see: the viewer's Play press to the
 * player opening (spec 2026-11-15, Play-to-first-frame F1).
 *
 * ## The gap
 *
 * Every playback number the app already reports starts at `createPlayer()` -
 * `startupTraceStartMs`, and the `source→ready` / `ready→firstFrame` pair read
 * off it. Everything BEFORE that - the add-on probes, the TorBox mint, the whole
 * "Finding sources" splash the viewer is actually looking at - happens while the
 * player does not exist yet, so it was outside every total. A build with a fast
 * source and a fast paint could still feel slow, and the report would show a
 * small number and no explanation.
 *
 * So the press is stamped when the viewer presses Play, and the leg is closed in
 * the player's own first moment, which is where the existing clock starts: the
 * two then add up to the wait the viewer feels,
 * `resolve + (createPlayer → first frame)`, instead of only the second half.
 *
 * ## Why it is kept here rather than only in the ring
 *
 * [PerfTrace] holds 400 samples and a long session evicts the newest figures
 * along with the oldest (the same exposure the `startup.` phases had, see
 * [PerfTrace.pinnedStartup]). The facts of the LAST launch are a handful of
 * values and per-process, so they are held here and read directly: the line
 * reports the run that just happened even after an hour of browsing.
 *
 * [engine] and [kind] exist because the numbers alone are ambiguous: the split
 * is instrumented on the ExoPlayer engine only, and a debrid-minted CDN link and
 * a scraper's hoster link have different failure shapes. A line that says which
 * engine and which kind of source produced the figures is the difference between
 * "the network is slow" and "MPV has no split at all".
 */
internal object PlaybackSessionTrace {

    private val lock = Any()

    /**
     * The longest press this will attribute a launch to.
     *
     * A press that never became a launch (a resolve that failed and left the
     * picker up) must not be measured as the resolve of whatever opens the
     * player next - a channel zap an hour later would otherwise report the
     * afternoon as its "Finding sources" time.
     */
    private const val MAX_LEG_MS = 120_000L

    /** When the viewer pressed Play, or 0 when no press is outstanding. */
    private var pressedAtMs = 0L

    private var engineName: String? = null

    private var sourceKind: String? = null

    private var resolveMs = -1L

    /** What the last launch looked like, for the report. */
    data class Facts(
        val engine: String,
        val kind: String,
        /** Press → player open, or -1 when the launch was not a press. */
        val resolveMs: Long
    )

    /**
     * Stamps the Play press.
     *
     * Called from the moment the viewer's press turns into work: the auto-play
     * resolve behind Play from Detail/Home/Continue Watching, and a picker row
     * press. Not from inside the player - a source switch is a new link in a
     * session that is already open, not a new start.
     */
    fun markPressed(atMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) { pressedAtMs = atMs }
    }

    /**
     * Closes the leg at the player's first moment and records the session's
     * engine and source kind. Returns the resolve leg, or -1 when this launch
     * had no press to measure from (a handoff, a channel zap, a download).
     *
     * [kind] is the classification of the link the player is actually starting -
     * "debrid" for a debrid-served host or tag, "http" otherwise - so the report
     * says which side of the socket the figures came from.
     */
    fun beginSession(
        engine: String,
        kind: String,
        atMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        val now = atMs
        val leg = synchronized(lock) {
            engineName = engine
            sourceKind = kind
            val pressedAt = pressedAtMs
            val measured = if (pressedAt > 0L && now - pressedAt <= MAX_LEG_MS) {
                (now - pressedAt).coerceAtLeast(0L)
            } else {
                -1L
            }
            resolveMs = measured
            pressedAtMs = 0L
            measured
        }
        if (leg >= 0L) {
            // The ring keeps it beside the rest of the playback figures; the
            // report reads the field above, so an eviction cannot lose it.
            PerfTrace.record("playback.resolve", leg)
        }
        return leg
    }

    /** The last launch's facts, or null when nothing has started this process. */
    fun facts(): Facts? = synchronized(lock) {
        val engine = engineName ?: return null
        Facts(engine, sourceKind ?: "?", resolveMs)
    }
}
