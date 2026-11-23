package com.kennyb1201.kbstream.data.sync

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The pull watermark's arithmetic (perf spec 2026-11-22, item 9).
 *
 * Every sync cycle used to download its tables in full - all four profiles'
 * history and watched rows, growing with the account forever - and filter them
 * in memory. A watermark turns that into "only what changed since last time".
 *
 * Two rules make it safe, and both are pure so they can be tested on the JVM:
 *
 *  - **An overlap window.** Postgres stamps `updated_at` with the TRANSACTION's
 *    start time, so a long write can commit after a pull has already read past
 *    its timestamp. A strict `> watermark` would then skip that row forever.
 *    The window starts a little BEFORE the watermark, so a row stamped around
 *    the boundary is read again on the next cycle. Reading a row twice is
 *    harmless - every apply is idempotent and timestamp-compared - but reading
 *    it never is not.
 *  - **Advance only to what was actually seen.** The watermark moves to the
 *    newest server timestamp in a cycle that applied completely; a cycle that
 *    failed, or that lost its profile mid-flight, advances nothing, so its
 *    window is re-read rather than skipped.
 *
 * Null means "no watermark" (first sync, a different profile, a forced full
 * resync): the pull is unfiltered, exactly as before.
 */
internal object SyncWatermark {

    /**
     * How far before the watermark a pull starts, to cover transaction skew.
     *
     * Ten minutes is far longer than any sync write's transaction and far
     * shorter than a browsing session's interval, so the overlap costs one
     * re-read of a few recent rows and never a lost one.
     */
    const val OVERLAP_MS = 10L * 60L * 1000L

    /** The ISO-8601 timestamp a pull starts at, or null for a full read. */
    fun windowStart(storedThroughMillis: Long?): String? =
        storedThroughMillis?.let { through ->
            OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(through - OVERLAP_MS),
                ZoneOffset.UTC
            ).toString()
        }

    /**
     * The watermark after a cycle that applied [serverTimestamps]: the newest
     * one seen, or [current] when this cycle carried nothing newer.
     *
     * Never moves backwards, and never moves at all without an answer.
     */
    fun nextThrough(current: Long?, serverTimestamps: List<String>): Long? {
        var newest = current
        for (raw in serverTimestamps) {
            val at = parseMillis(raw) ?: continue
            val known = newest
            if (known == null || at > known) newest = at
        }
        return newest
    }

    /**
     * A PostgREST `updated_at` as epoch millis, or null when it will not parse.
     *
     * PostgREST emits `2026-11-23T01:22:25.123456+00:00`; [OffsetDateTime]
     * takes both that and a `Z` suffix.
     */
    fun parseMillis(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return runCatching {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
