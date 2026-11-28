package com.kennyb1201.kbstream.data.iptv

/**
 * What a stopped guide import had already staged, so the next attempt can
 * continue instead of starting over.
 *
 * The field state this answers: the phone's import stops mid-import
 * (`outcome=stopped mid-import aborts=2`) and the only next chances were the 6h
 * tick, a playback-end catch-up, or a manual IMPORT GUIDE — and every one of
 * them re-downloaded the file and re-inserted every row from the first byte, so
 * a feed that needs longer than WorkManager's ~10-minute window could never
 * finish, however many times it was started. The 2026-11-20 paste quoted in
 * [com.kennyb1201.kbstream.work.WorkPolicies] is the same feed: 15,359 channels
 * and 103k programs, which "landed whenever the viewer ran it by hand".
 *
 * XML cannot be resumed mid-document — the guide is gzip, so a byte offset has
 * to be decompressed from the start anyway — so a resumed attempt re-walks the
 * file and SKIPS the rows it already has. What it saves is the work that made
 * the import not fit: the batch inserts and the [EpgWriteGate] holds. The parse
 * itself is the price of the format, and the final swap still re-keys the whole
 * staged set once - neither is something a checkpoint can skip.
 *
 * The counts are written AFTER each flush returns (see [GuideImportCheckpointSink]),
 * so the staged rows are always at least what the checkpoint claims. That
 * ordering is the whole safety argument: a checkpoint ahead of the database
 * would skip rows that were never stored.
 */
internal data class GuideImportCheckpoint(
    /** When the attempt that staged these rows started; the window is anchored to it. */
    val createdAtMs: Long,
    /** The window the staged rows were filtered by; a resumed attempt reuses it. */
    val windowStartMs: Long,
    val windowEndMs: Long,
    /** Channel rows already staged for this source. */
    val stagedChannels: Int,
    /** Program rows already staged for this source. */
    val stagedPrograms: Int,
    /** How far into the stream that attempt had read, for diagnostics. */
    val bytesRead: Long,
    /** The feed the staged rows came from, as the server identified it. */
    val etag: String?,
    val lastModified: String?,
    /** The playlist match keys the staged rows were filtered by. */
    val matchKeysFingerprint: String
) {
    val stagedRows: Int get() = stagedChannels + stagedPrograms
}

/**
 * How long a checkpoint may sit before it starts over.
 *
 * Six hours is the refresh cadence itself: a checkpoint older than the interval
 * that would have replaced it has no window worth reusing, and the next pass
 * imports a fresh one anyway.
 */
internal const val GUIDE_IMPORT_RESUME_MAX_AGE_MS = 6L * 60L * 60L * 1000L

/**
 * Where a resumed import picks up, or null when the staged rows cannot be
 * trusted for this attempt.
 *
 * Every condition is a way a resume could corrupt the guide rather than merely
 * save time — which matters because the swap re-keys whatever is staged:
 *
 *  - **Something is staged.** No rows, nothing to resume.
 *  - **The same feed.** The validators the server just sent must be the ones
 *    the staged attempt was reading. A provider that regenerated its file
 *    between attempts keeps the same URL and can move every row, so resuming
 *    would skip into a document that no longer lines up.
 *  - **The same filter.** The playlist's match keys decide which programmes
 *    are kept at all; a playlist edit changes what row N of the next parse is.
 *  - **A recent attempt.** The window is anchored to the attempt's own clock,
 *    so a checkpoint from days ago would store a window that is days stale at
 *    both ends. Past [maxAgeMs] the attempt starts over with a fresh window,
 *    which costs one import rather than a wrong guide.
 */
internal fun resumeCheckpoint(
    checkpoint: GuideImportCheckpoint?,
    nowMs: Long,
    etag: String?,
    lastModified: String?,
    matchKeysFingerprint: String,
    maxAgeMs: Long = GUIDE_IMPORT_RESUME_MAX_AGE_MS
): GuideImportCheckpoint? {
    if (checkpoint == null) return null
    if (checkpoint.stagedRows <= 0) return null
    if (nowMs - checkpoint.createdAtMs !in 0 until maxAgeMs) return null
    if (checkpoint.etag != etag || checkpoint.lastModified != lastModified) return null
    if (checkpoint.matchKeysFingerprint != matchKeysFingerprint) return null
    return checkpoint
}

/**
 * Which rows of the document a resumed parse already has, in document order.
 *
 * The re-parse walks the whole file, so this decides row by row: the first
 * `stagedChannels` channels and the first `stagedPrograms` kept programmes are
 * already in the staging table and are dropped on the floor; everything after
 * them is inserted as usual. Channels and programmes are counted separately
 * because the importer batches them separately — a channel block's flush does
 * not move the programme count.
 *
 * Pure, and mutable on purpose: the rule is unit tested directly (see
 * `GuideImportCheckpointTest`) instead of only through a Robolectric import.
 */
internal class GuideImportResumeRows(checkpoint: GuideImportCheckpoint?) {

    private var channelsLeft = checkpoint?.stagedChannels ?: 0
    private var programsLeft = checkpoint?.stagedPrograms ?: 0

    /** Whether this parse resumes anything at all. */
    val resumed: Boolean = checkpoint != null && checkpoint.stagedRows > 0

    /** True when this channel was staged by the previous attempt and must not be re-inserted. */
    fun channelAlreadyStaged(): Boolean {
        if (channelsLeft <= 0) return false
        channelsLeft--
        return true
    }

    /** True when this kept programme was staged by the previous attempt. */
    fun programAlreadyStaged(): Boolean {
        if (programsLeft <= 0) return false
        programsLeft--
        return true
    }
}

/**
 * Where an import publishes what it has staged, so a stop leaves it behind.
 *
 * Called AFTER the batch's insert returns, never before: see
 * [GuideImportCheckpoint]. Wired by `IptvRepository` to the profile-scoped
 * prefs the rest of the guide bookkeeping already uses.
 */
internal fun interface GuideImportCheckpointSink {
    fun staged(channels: Int, programs: Int, bytesRead: Long)
}

/**
 * The checkpoint as one durable string, and the playlist fingerprint that
 * decides whether one may be resumed at all.
 *
 * Separate from the prefs store ([GuideImportCheckpointStore]) so the format and
 * the fingerprint are unit tested on the JVM, where a `SharedPreferences` is not
 * available. Every field is a number or an HTTP header value, neither of which
 * can contain the unit separator.
 */
internal object GuideImportCheckpointCodec {

    private const val SEP = '\u001f'
    private const val FIELD_COUNT = 9

    fun encode(checkpoint: GuideImportCheckpoint): String =
        listOf(
            checkpoint.createdAtMs,
            checkpoint.windowStartMs,
            checkpoint.windowEndMs,
            checkpoint.stagedChannels,
            checkpoint.stagedPrograms,
            checkpoint.bytesRead,
            checkpoint.etag.orEmpty(),
            checkpoint.lastModified.orEmpty(),
            checkpoint.matchKeysFingerprint
        ).joinToString(SEP.toString())

    /** The inverse of [encode]; null for a record this build cannot read. */
    fun decode(encoded: String): GuideImportCheckpoint? {
        val fields = encoded.split(SEP)
        if (fields.size != FIELD_COUNT) return null
        val createdAt = fields[0].toLongOrNull() ?: return null
        val windowStart = fields[1].toLongOrNull() ?: return null
        val windowEnd = fields[2].toLongOrNull() ?: return null
        val channels = fields[3].toIntOrNull() ?: return null
        val programs = fields[4].toIntOrNull() ?: return null
        val bytes = fields[5].toLongOrNull() ?: return null
        return GuideImportCheckpoint(
            createdAtMs = createdAt,
            windowStartMs = windowStart,
            windowEndMs = windowEnd,
            stagedChannels = channels,
            stagedPrograms = programs,
            bytesRead = bytes,
            etag = fields[6].takeIf(String::isNotEmpty),
            lastModified = fields[7].takeIf(String::isNotEmpty),
            matchKeysFingerprint = fields[8]
        )
    }

    /**
     * A stable-enough fingerprint of the playlist match keys for [resumeCheckpoint].
     *
     * `String.hashCode` is specified by the JVM and stable on-device, which is
     * all this needs: the value is written and read by the same install.
     */
    fun fingerprint(matchKeys: Set<String>): String =
        matchKeys.sorted().joinToString("\u001f").hashCode().toUInt().toString(16)
}
