package com.kennyb1201.kbstream.data.sync

/**
 * Why the phone never pushed by itself (spec 2026-11-14, F1/F2/F3).
 *
 * The automatic push is two halves - the enrollment backfill that queues a
 * session this device has never published, and the one-minute flush loop that
 * drains the outbox - and on Kenny's phone `lastPush=never pending=0` was the
 * same paste for three builds. That pair cannot say which half is dead: nothing
 * queued, nothing due, or nothing running all look identical from the outside.
 *
 * Everything here is the ANSWER, as strings, built from booleans alone. The
 * Android half in [SupabaseSync] reads the stores, asks the rules
 * ([TrackerAuthBackfillRules]) what is due, and hands the booleans in; the
 * durable prefs that let the panel answer after a force-stop live there too.
 * Pure on purpose: the three candidate breaks have to be distinguishable in a
 * unit test, not only on the device that is misbehaving.
 *
 * SHAPE ONLY, the same rule as the OAuth arrival tracing: key names, booleans
 * and durations. Never a token, verifier, client id, secret, fingerprint or
 * payload - which is why no field here can carry one ([KeyVerdict] holds flags,
 * and a failure is its exception's CLASS name, never its message).
 */
internal object SyncPushDiagnostics {

    // ── durable prefs (the sync layer's own store) ────────────────────────
    //
    // In prefs, not in memory: the process that needs the answer is the NEXT
    // start, and a force-stop is exactly when a viewer goes looking.

    /** The last backfill's panel line, e.g. "nothing due — kitsu no session". */
    const val PREFS_BACKFILL = "pushdiag:backfill"

    /** When that verdict was produced. */
    const val PREFS_BACKFILL_AT = "pushdiag:backfill_at"

    /** The flush loop's last state: [LOOP_ALIVE] or one of the exit tokens. */
    const val PREFS_LOOP_STATE = "pushdiag:loop_state"

    /** Heartbeat: the last time the loop woke and found itself signed in. */
    const val PREFS_LOOP_ALIVE_AT = "pushdiag:loop_alive_at"

    /** When the loop last stopped, and why ([PREFS_LOOP_EXIT]). */
    const val PREFS_LOOP_EXIT = "pushdiag:loop_exit"

    const val PREFS_LOOP_EXIT_AT = "pushdiag:loop_exit_at"

    /** The last flush that actually had rows to attempt. */
    const val PREFS_FLUSH_AT = "pushdiag:flush_at"

    const val PREFS_FLUSH_ATTEMPTED = "pushdiag:flush_attempted"

    const val PREFS_FLUSH_UPLOADED = "pushdiag:flush_uploaded"

    /** The last enqueue-time flush arm ([SupabaseSync.scheduleFlush]). */
    const val PREFS_SCHEDULE_AT = "pushdiag:schedule_at"

    const val PREFS_SCHEDULE_COUNT = "pushdiag:schedule_count"

    // ── loop states ───────────────────────────────────────────────────────

    const val LOOP_ALIVE = "alive"

    /** The loop's own condition failed: nobody is signed in any more. */
    const val LOOP_SIGNED_OUT = "signed_out"

    /** The loop's scope was cancelled under it. */
    const val LOOP_CANCELLED = "cancelled"

    /**
     * The loop died with its process - the one exit no code observes. Inferred
     * at the next start: the stored state still said [LOOP_ALIVE].
     */
    const val LOOP_PROCESS_END = "process_end"

    /** How a stored marker relates to the session this device holds now. */
    enum class Marker(val token: String) {
        /** This device has never published this key. */
        ABSENT("absent"),

        /** The marker is exactly the session held now: nothing to send. */
        MATCH("match"),

        /** Published something else before: the session moved since. */
        MISMATCH("diff")
    }

    /**
     * One enrolled key's verdict. `localSession` is "this device holds a copy at
     * all" - a PULLED Kitsu session counts, and is the distinction that stops a
     * correct "nothing due" from being read as a broken backfill (spec §4);
     * `gateOpen` is the enrollment's own "anything to publish" question.
     */
    data class KeyVerdict(
        val key: String,
        val localSession: Boolean,
        val gateOpen: Boolean,
        val marker: Marker,
        val due: Boolean,
        val queued: Boolean
    )

    /** One backfill run, as it will be recorded. */
    data class BackfillVerdict(
        val signedIn: Boolean,
        val pullRan: Boolean,
        val scopeActive: Boolean,
        val loopRunning: Boolean,
        val failureClass: String? = null,
        val keys: List<KeyVerdict> = emptyList()
    )

    /**
     * The compact paste line. Stays under [CrashReporter.recordEvent]'s 160-char
     * cap for three enrolled keys, which is asserted in the tests: a verdict
     * truncated to "anilist ses=" would be worse than no verdict.
     */
    fun backfillLine(verdict: BackfillVerdict): String {
        verdict.failureClass?.let { return "bf threw — $it" }
        if (!verdict.signedIn) return "bf skipped — not signed in"
        if (!verdict.pullRan) return "bf skipped — pull did not run"
        if (!verdict.scopeActive) return "bf skipped — profile changed mid-run"
        if (verdict.keys.isEmpty()) return "bf: no enrolled session"
        val flags = "bf: in=1 pull=1 scope=1 loop=${if (verdict.loopRunning) 1 else 0}"
        return (listOf(flags) + verdict.keys.map(::keyToken)).joinToString(" | ")
    }

    /** One key, in the paste's short form: `kitsu ses=1 gate=1 due=1 q=1 mk=absent`. */
    fun keyToken(key: KeyVerdict): String = buildString {
        append(key.key)
        append(" ses=").append(if (key.localSession) 1 else 0)
        append(" gate=").append(if (key.gateOpen) 1 else 0)
        append(" due=").append(if (key.due) 1 else 0)
        append(" q=").append(if (key.queued) 1 else 0)
        append(" mk=").append(key.marker.token)
    }

    /**
     * The panel line: what happened, in words, because this one is read on a
     * phone rather than pasted. A due key that was queued says so; otherwise the
     * reason is named per key, so "nothing due" is never ambiguous.
     */
    fun backfillSummary(verdict: BackfillVerdict): String {
        verdict.failureClass?.let { return "threw $it" }
        if (!verdict.signedIn) return "skipped — not signed in"
        if (!verdict.pullRan) return "skipped — pull did not run"
        if (!verdict.scopeActive) return "skipped — profile changed mid-run"
        if (verdict.keys.isEmpty()) return "no enrolled session"
        val due = verdict.keys.filter { it.due }
        if (due.isNotEmpty()) {
            val queued = due.filter { it.queued }.map { it.key }
            return if (queued.isEmpty()) {
                "${due.joinToString(", ") { it.key }} due, not enqueued"
            } else {
                "${queued.joinToString(", ")} due, enqueued"
            }
        }
        val reasons = verdict.keys.map { key -> "${key.key} ${reasonFor(key)}" }
        return "nothing due — " + reasons.joinToString(", ")
    }

    private fun reasonFor(key: KeyVerdict): String = when {
        !key.localSession -> "no session"
        !key.gateOpen -> "nothing to publish"
        key.marker == Marker.MATCH -> "already published"
        key.marker == Marker.MISMATCH -> "marker differs, not due"
        else -> "not due"
    }

    /**
     * The loop's durable state, as the panel shows it: alive, or stopped with
     * the reason. `null` when this install has never run the loop at all - a
     * fresh install and a loop that never started are different facts, and
     * "not running (never started)" says which.
     */
    fun loopStateToken(alive: Boolean, storedState: String?): String = when {
        alive -> LOOP_ALIVE
        storedState == LOOP_SIGNED_OUT -> "not running (signed out)"
        storedState == LOOP_CANCELLED -> "not running (cancelled)"
        storedState == LOOP_PROCESS_END -> "not running (process ended)"
        storedState == LOOP_ALIVE -> "not running (last seen alive)"
        else -> "not running (never started)"
    }

    /** What an exit records: the token, plus the words a paste should carry. */
    fun loopExitLabel(state: String): String = when (state) {
        LOOP_SIGNED_OUT -> "signed out"
        LOOP_CANCELLED -> "cancelled"
        LOOP_PROCESS_END -> "process end"
        else -> state
    }

    /**
     * The flush-loop line: state, then the two numbers that say whether the loop
     * is doing anything - when it last flushed with rows to send, and how many
     * of those rows left the outbox.
     */
    fun loopLine(
        stateToken: String,
        lastAliveAtMs: Long,
        lastFlushAtMs: Long,
        lastUploaded: Int,
        lastAttempted: Int,
        nowMs: Long
    ): String {
        if (stateToken != LOOP_ALIVE) return "$stateToken, last seen ${ago(lastAliveAtMs, nowMs)}"
        val flush = if (lastFlushAtMs <= 0L) {
            "no flush yet"
        } else {
            val rows = if (lastUploaded == 1) "1 row" else "$lastUploaded rows"
            "last flush ${ago(lastFlushAtMs, nowMs)}, $rows" +
                if (lastUploaded == 0 && lastAttempted > 0) " of $lastAttempted (no progress)" else ""
        }
        return "alive, $flush"
    }

    /** The single most useful line in the spec, when it is true. */
    fun pullEndLoopLine(loopRunning: Boolean): String =
        if (loopRunning) "flush loop: running at pull end" else "flush loop: not running at pull end"

    /** One flush that had rows to attempt. */
    fun flushOutcomeLine(attempted: Int, uploaded: Int): String = buildString {
        append("flush: ").append(attempted).append(" attempted, ").append(uploaded).append(" uploaded")
        if (uploaded == 0) append(" (no progress)")
    }

    /**
     * A duration for a paste/panel line. Deliberately coarse: the question is
     * "minutes or days", not seconds, and a clock that is under a minute old
     * reads as "just now" rather than "0m ago".
     */
    fun ago(atMs: Long, nowMs: Long): String {
        if (atMs <= 0L) return "never"
        val delta = nowMs - atMs
        if (delta < 0L) return "just now"
        val minutes = delta / 60_000L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "${minutes}m ago"
            minutes < 60L * 24L -> "${minutes / 60L}h ago"
            else -> "${minutes / (60L * 24L)}d ago"
        }
    }
}
