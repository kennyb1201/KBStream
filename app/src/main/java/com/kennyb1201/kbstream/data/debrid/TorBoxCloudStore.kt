package com.kennyb1201.kbstream.data.debrid

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which of the account's TorBox torrents the library sync has already handled,
 * per profile.
 *
 * The sync runs on a schedule and on every app launch, so without this it would
 * re-search TMDB for every torrent in the account each time. An id is recorded
 * once it has been dealt with — successfully added, or found to name nothing
 * TMDB can match — so a round only costs work for torrents that are new since
 * the last one.
 *
 * Profile-scoped like every other on-device store and deliberately NOT in the
 * synced prefs allow-list: which torrents this device has processed is local
 * bookkeeping, not account state (the Library entries it produces DO sync, via
 * LocalLibraryStore's own blob).
 *
 * Three things sit here that are not marks:
 *
 *  - the retry streaks (empty search, no confident match), which are the
 *    opposite of a mark: evidence that the torrent should be looked at again;
 *  - the last round's report, which is what the Settings panes show so a round
 *    that added nothing is visibly different from a round that never ran;
 *  - the review queue: the de-dupe keys of My List rows the last COMPLETED
 *    round could attribute to nobody (see [TorBoxLibrarySync]). Not a mark and
 *    not a verdict, just the rows the rules refuse to guess about.
 */
internal class TorBoxCloudStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        ProfileStorage.prefsName(context.applicationContext, PREFS_NAME),
        Context.MODE_PRIVATE
    )

    /** Torrent ids already dealt with by a previous round. */
    @Synchronized
    fun processed(): Set<Long> =
        prefs.getStringSet(KEY_PROCESSED, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    /**
     * Records [torrentId] as handled. Synchronized because the library sync can
     * run from more than one place (launch, the schedule, a manual "sync now"),
     * and the read-modify-write here would otherwise drop one round's ids when
     * two overlap.
     */
    @Synchronized
    fun markProcessed(torrentId: Long) {
        val updated = processed().toMutableSet()
        if (!updated.add(torrentId)) return
        // Cap the set so a very large library cannot grow the persisted set
        // without bound. Ids are unordered in a StringSet, so the overflow is
        // dropped arbitrarily: those torrents are simply re-checked once.
        val capped =
            if (updated.size > MAX_PROCESSED) {
                updated.toList().takeLast(MAX_PROCESSED).toMutableSet()
            } else {
                updated
            }
        prefs.edit()
            .putStringSet(KEY_PROCESSED, capped.map { it.toString() }.toSet())
            .apply()
        // The torrent is dealt with, so its retry streaks are over: left behind
        // they would be re-counted against a torrent that is no longer looked up
        // at all.
        clearEmptySearch(torrentId)
        clearNoMatch(torrentId)
    }

    /**
     * Forgets EVERY mark and retry streak this device holds.
     *
     * The manual Resync's half of the cure (see [TorBoxLibrarySync]), and the
     * escape for a name the parser could not read: a torrent marked on sight
     * because its release name was unparseable is revisited the moment the
     * parser improves. The last round's report is deliberately NOT cleared - it
     * is being replaced by the round this triggers, and until that round runs
     * the line should keep showing the truth about the previous one.
     */
    @Synchronized
    fun clearAllMarks() {
        prefs.edit()
            .remove(KEY_PROCESSED)
            .remove(KEY_EMPTY_ATTEMPTS)
            .remove(KEY_NO_MATCH_ATTEMPTS)
            .apply()
    }

    /**
     * The one-time forgiveness for marks written before the count fix (v0.9.33,
     * 88e4fa0e): clears every mark ONCE for this profile on this device, then
     * records that it has been done, so later rounds keep their marks.
     *
     * The reason it is a migration rather than a button: a mark written by an
     * earlier build is indistinguishable from a good one - the pre-fix code
     * marked a torrent on its first failed search, so one bad round could have
     * marked a whole cloud as handled and nothing in the app could ever revisit
     * it. Re-checking costs one round of TMDB searches, and a name that truly
     * matches nothing simply marks again through the now retry-bounded paths.
     *
     * Returns true when this call did the clear, for the round's log.
     */
    @Synchronized
    fun forgetPreCountingFixMarksOnce(): Boolean {
        if (prefs.getInt(KEY_MARK_RESET_VERSION, 0) >= MARK_RESET_VERSION) return false
        clearAllMarks()
        prefs.edit()
            .putInt(KEY_MARK_RESET_VERSION, MARK_RESET_VERSION)
            .apply()
        return true
    }

    /**
     * Whether the one-time ownership remediation (see
     * [TorBoxLibrarySync]) still has to run for this profile.
     *
     * Its own version flag, not the mark-reset one: the two migrations answer
     * different questions ("the counting was wrong then" vs "the adds were not
     * attributed"), and a profile can need one without the other.
     */
    @Synchronized
    fun ownershipRemediationPending(): Boolean =
        prefs.getInt(KEY_OWNERSHIP_REMEDIATION_VERSION, 0) < OWNERSHIP_REMEDIATION_VERSION

    /** Records that this profile's library has been through the remediation. */
    @Synchronized
    fun markOwnershipRemediated() {
        prefs.edit()
            .putInt(KEY_OWNERSHIP_REMEDIATION_VERSION, OWNERSHIP_REMEDIATION_VERSION)
            .apply()
    }

    /**
     * The de-dupe keys of the rows the last completed round could not
     * attribute, for the Settings review row.
     *
     * Keys rather than copies of the rows: the queue is a statement about the
     * last round, and the titles, years and types shown in the review are read
     * from the CURRENT My List. A row removed since the round simply stops
     * resolving, which is the behaviour the reviewer expects - they are not
     * being shown a ghost. Filed only by a COMPLETED round, so a gate exit
     * leaves the previous queue intact instead of blanking it.
     */
    @Synchronized
    fun reviewCandidates(): Set<String> =
        prefs.getStringSet(KEY_REVIEW_CANDIDATES, emptySet()).orEmpty().toSet()

    /** Replaces this profile's review queue with [keys] (empty clears it). */
    @Synchronized
    fun recordReviewCandidates(keys: Set<String>) {
        prefs.edit()
            .apply {
                if (keys.isEmpty()) remove(KEY_REVIEW_CANDIDATES)
                else putStringSet(KEY_REVIEW_CANDIDATES, keys)
            }
            .apply()
    }

    /**
     * Drops [keys] from the review queue after the reviewer confirmed them.
     * A kept row stays queued: it is still a row no rule can attribute, and
     * hiding it would be the same silence this queue was built to end.
     */
    @Synchronized
    fun forgetReviewCandidates(keys: Set<String>) {
        val remaining = reviewCandidates() - keys
        recordReviewCandidates(remaining)
    }

    /**
     * Re-arms the one-time ownership cleanup for this profile.
     *
     * The manual Resync's other half. Clearing the processed marks lets the
     * round look at every torrent again, but a cleanup that had already spent
     * its single pass - however little it removed - would never run again, so
     * the escape hatch could not reach it. Both are reset together, which is
     * what makes "RESYNC TORBOX CLOUD" a repeatable answer to "the leaked rows
     * are still there".
     */
    @Synchronized
    fun clearOwnershipRemediation() {
        prefs.edit().remove(KEY_OWNERSHIP_REMEDIATION_VERSION).apply()
    }

    /**
     * How many rounds running this torrent's TMDB search has come back empty.
     *
     * Kept apart from [processed] because the two answer opposite questions:
     * an empty result is retried a few rounds (it is as likely to be a blip as
     * a missing title), and only then is the torrent recorded as handled. See
     * [TorBoxCloudRules.EMPTY_SEARCH_ATTEMPTS].
     */
    @Synchronized
    fun emptySearchAttempts(torrentId: Long): Int =
        readCounts(KEY_EMPTY_ATTEMPTS)[torrentId] ?: 0

    /**
     * Records one more empty search for [torrentId] and returns the running
     * count, so the caller can tell "retry next round" from "record it and
     * stop looking" without a second read.
     */
    @Synchronized
    fun noteEmptySearch(torrentId: Long): Int =
        bumpCount(KEY_EMPTY_ATTEMPTS, MAX_EMPTY_ATTEMPTS, torrentId)

    /**
     * Forgets [torrentId]'s empty-search record: a search that produced
     * candidates is evidence the name resolves, so any earlier streak is over.
     */
    @Synchronized
    fun clearEmptySearch(torrentId: Long) {
        clearCount(KEY_EMPTY_ATTEMPTS, MAX_EMPTY_ATTEMPTS, torrentId)
    }

    /**
     * How many rounds running this torrent has searched FINE and matched
     * nothing confidently.
     *
     * The sibling of [emptySearchAttempts], for the other half of the same
     * question. It used to be marked handled on its first no-match, which made a
     * single round's TMDB coverage final for a title TMDB had not indexed yet
     * (or spelled differently that day). It now gets the same budget as an empty
     * search before the torrent is recorded as handled.
     */
    @Synchronized
    fun noMatchAttempts(torrentId: Long): Int =
        readCounts(KEY_NO_MATCH_ATTEMPTS)[torrentId] ?: 0

    /** Records one more no-match round for [torrentId]; returns the total. */
    @Synchronized
    fun noteNoMatch(torrentId: Long): Int =
        bumpCount(KEY_NO_MATCH_ATTEMPTS, MAX_NO_MATCH_ATTEMPTS, torrentId)

    /** Forgets [torrentId]'s no-match streak: it matched a title this round. */
    @Synchronized
    fun clearNoMatch(torrentId: Long) {
        clearCount(KEY_NO_MATCH_ATTEMPTS, MAX_NO_MATCH_ATTEMPTS, torrentId)
    }

    /**
     * What the last round here did, or null when this device has never run one.
     *
     * Persisted rather than kept in memory because the line that reads it is on
     * a Settings pane the viewer opens long after the round ran - and the round
     * itself usually runs at launch, in a worker, with no screen anywhere.
     */
    @Synchronized
    fun lastRound(): LastRound? {
        val outcome = prefs.getString(KEY_LAST_OUTCOME, null) ?: return null
        // An outcome this build does not know (a downgrade, or a half-written
        // file) is treated as "nothing recorded" rather than crashing a pane.
        val parsed = runCatching { TorBoxLibrarySync.Outcome.valueOf(outcome) }.getOrNull()
            ?: return null
        return LastRound(
            outcome = parsed,
            scanned = prefs.getInt(KEY_LAST_SCANNED, 0),
            added = prefs.getInt(KEY_LAST_ADDED, 0),
            handledSkips = prefs.getInt(KEY_LAST_HANDLED, 0),
            removed = prefs.getInt(KEY_LAST_REMOVED, 0),
            candidates = prefs.getInt(KEY_LAST_CANDIDATES, 0),
            siblingEvidence = prefs.getBoolean(KEY_LAST_SIBLING, false),
            atMillis = prefs.getLong(KEY_LAST_AT, 0L)
        )
    }

    /** Files [report] as this device's last round, stamped with the time it ended. */
    @Synchronized
    fun recordRound(report: TorBoxLibrarySync.Report) {
        prefs.edit()
            .putString(KEY_LAST_OUTCOME, report.outcome.name)
            .putInt(KEY_LAST_SCANNED, report.scanned)
            .putInt(KEY_LAST_ADDED, report.added)
            .putInt(KEY_LAST_HANDLED, report.handledSkips)
            .putInt(KEY_LAST_REMOVED, report.removed)
            .putInt(KEY_LAST_CANDIDATES, report.candidates)
            .putBoolean(KEY_LAST_SIBLING, report.siblingEvidence)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
        // A pane that is OPEN while the round finishes (the field's Resync, the
        // schedule, the launch round) reads the line from here, so the record
        // has to say it changed. Before this, the line was a snapshot taken on
        // entering Settings and a round that filed while the viewer watched
        // showed nothing until they left and came back.
        TorBoxSyncStatus.publish()
    }

    /**
     * The `id:count` streak set filed under [key], as a map. One reader for
     * both streaks, so the empty-search and no-match budgets cannot decode the
     * same stored shape differently.
     */
    private fun readCounts(key: String): Map<Long, Int> =
        prefs.getStringSet(key, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val id = entry.substringBefore(':').toLongOrNull()
                val count = entry.substringAfter(':', "").toIntOrNull()
                if (id == null || count == null) null else id to count
            }
            .toMap()

    private fun bumpCount(key: String, max: Int, torrentId: Long): Int {
        val counts = readCounts(key).toMutableMap()
        val next = (counts[torrentId] ?: 0) + 1
        counts[torrentId] = next
        writeCounts(key, max, counts)
        return next
    }

    private fun clearCount(key: String, max: Int, torrentId: Long) {
        val counts = readCounts(key).toMutableMap()
        if (counts.remove(torrentId) != null) writeCounts(key, max, counts)
    }

    private fun writeCounts(key: String, max: Int, counts: Map<Long, Int>) {
        // Bounded with the same policy as the processed set: the oldest entries
        // are dropped first (the map is rebuilt in insertion order), and a
        // torrent dropped this way is simply re-checked once.
        val bounded =
            if (counts.size > max) {
                counts.entries.toList().takeLast(max)
                    .associate { (id, count) -> id to count }
            } else {
                counts
            }
        prefs.edit()
            .putStringSet(key, bounded.map { (id, count) -> "$id:$count" }.toSet())
            .apply()
    }

    /**
     * What the last round did, as the Settings panes read it. [atMillis] is when
     * the round ended, so the line can say when.
     */
    internal data class LastRound(
        val outcome: TorBoxLibrarySync.Outcome,
        val scanned: Int,
        val added: Int,
        val handledSkips: Int,
        /** Rows the one-time cleanup took out of this profile's list. */
        val removed: Int,
        /**
         * Rows this round could attribute to nobody and left in the review
         * queue (see [reviewCandidates]). Zero on a round with nothing to say
         * about attribution, which includes every gate exit.
         */
        val candidates: Int = 0,
        /**
         * Whether any OTHER profile on this device has a My List row at all -
         * arm B's raw material (see [TorBoxLibrarySync]). False means the round
         * had no sibling copy to compare against, which is a different fact
         * from "nothing was leaking" and is named on the line.
         */
        val siblingEvidence: Boolean = false,
        val atMillis: Long
    )

    companion object {
        private const val PREFS_NAME = "kbstream_debrid"
        private const val KEY_PROCESSED = "torbox_library_processed"
        private const val MAX_PROCESSED = 5000
        private const val KEY_EMPTY_ATTEMPTS = "torbox_library_empty_searches"
        private const val MAX_EMPTY_ATTEMPTS = 5000
        private const val KEY_NO_MATCH_ATTEMPTS = "torbox_library_no_matches"
        private const val MAX_NO_MATCH_ATTEMPTS = 5000

        /**
         * Which one-time mark reset this build carries. Bumping it (for a future
         * counting fix) makes the next round clear marks again, once, on every
         * profile that has run an older version.
         */
        private const val MARK_RESET_VERSION = 1
        private const val KEY_MARK_RESET_VERSION = "torbox_library_mark_reset_version"

        /**
         * The one-time ownership remediation's version.
         *
         * Bumped when the rule it applies changes (a new source of ownership
         * evidence), which re-runs it once for every profile that has already
         * been through an older version.
         */
        private const val OWNERSHIP_REMEDIATION_VERSION = 3
        private const val KEY_OWNERSHIP_REMEDIATION_VERSION =
            "torbox_library_ownership_remediation_version"

        private const val KEY_LAST_OUTCOME = "torbox_library_last_outcome"
        private const val KEY_LAST_SCANNED = "torbox_library_last_scanned"
        private const val KEY_LAST_ADDED = "torbox_library_last_added"
        private const val KEY_LAST_HANDLED = "torbox_library_last_handled"
        private const val KEY_LAST_REMOVED = "torbox_library_last_removed"
        private const val KEY_LAST_CANDIDATES = "torbox_library_last_candidates"
        private const val KEY_LAST_SIBLING = "torbox_library_last_sibling"
        private const val KEY_REVIEW_CANDIDATES = "torbox_library_review_candidates"
        private const val KEY_LAST_AT = "torbox_library_last_at"
    }
}

/**
 * The "last round filed" signal the Settings panes read the status line on.
 *
 * A process-wide counter rather than a store field because the panes and the
 * worker hold different [TorBoxCloudStore] instances for the same profile, and
 * the line has to change while a pane is already open: the round the field
 * pressed for finishes with no navigation at all, and a snapshot taken when the
 * pane composed is exactly what left it showing the previous round's counts.
 * Nothing is read from here except the fact that something was filed - the
 * line itself still comes from the store's own record.
 */
object TorBoxSyncStatus {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Called by the round that files a report (see [TorBoxCloudStore.recordRound]). */
    fun publish() {
        _revision.value += 1
    }
}

/**
 * The one line the Settings panes draw under the TorBox library switch, from
 * whatever [TorBoxCloudStore.lastRound] holds.
 *
 * Pure, so the wording and the branch that picks it are pinned by a JVM test
 * rather than by a device: the whole point of the line is that a round which
 * added nothing no longer looks like a round that never ran, and "which branch
 * did that" is exactly the part that must not be re-derived differently on the
 * TV page and the phone.
 */
internal fun torboxSyncStatusLine(
    round: TorBoxCloudStore.LastRound?,
    zone: ZoneId = ZoneId.systemDefault(),
    // The queue as it stands NOW, not as the round filed it: the reviewer can
    // empty it from the row below, and a line still claiming N titles were
    // found after they were removed would send them looking for a row that is
    // gone. Defaulted to the round's own count so the pure line and its tests
    // keep one meaning.
    pendingReview: Int = round?.candidates ?: 0
): String {
    if (round == null) return "Not run yet on this device"
    val at = DateTimeFormatter.ofPattern("HH:mm")
        .format(Instant.ofEpochMilli(round.atMillis).atZone(zone))
    var base = when (round.outcome) {
        // A gate exit is still a round: it says why nothing happened, which is
        // more than the silent return it replaced.
        TorBoxLibrarySync.Outcome.NO_KEY -> "Last sync $at — no TorBox key set"
        TorBoxLibrarySync.Outcome.DISABLED -> "Last sync $at — sync is off"
        // A round that ran with nobody signed in at the profile level wrote
        // nothing on purpose; saying so is the difference between "it ran and
        // added nothing" and "it never got a profile to add to".
        TorBoxLibrarySync.Outcome.DEFERRED ->
            "Last sync $at — no profile active, adds deferred"
        TorBoxLibrarySync.Outcome.CLOUD_EMPTY -> "Last sync $at — cloud is empty"
        TorBoxLibrarySync.Outcome.COMPLETED ->
            // Adds are the news whenever there are any - that is the round the
            // viewer was waiting for. With nothing added, the skips are what has
            // to be named instead: a caught-up round must not read as a round
            // that did nothing. And a round that added nothing and skipped
            // nothing (every name unparseable, or every search failed) says so
            // rather than reporting "0 added" as if it had searched.
            if (round.added > 0) {
                "Last sync $at — ${round.scanned} in cloud, ${round.added} added"
            } else if (round.handledSkips > 0) {
                "Last sync $at — ${round.scanned} in cloud, " +
                    "${round.handledSkips} already handled"
            } else {
                "Last sync $at — ${round.scanned} in cloud, nothing added"
            }
    }
    // The cleanup's own outcome, when it had one. Appended rather than folded
    // into the branches: a round's adds and a cleanup's removals are different
    // facts, and "the round ran" must not read the same as "the cleanup found
    // nothing" - which is exactly how the field's still-leaking Library looked
    // from Settings while the pass was silently keeping every row.
    if (round.removed > 0) {
        val titles = if (round.removed == 1) "title" else "titles"
        base = "$base · removed ${round.removed} $titles owned by another profile"
    }
    // The rows no rule could attribute are the third fact, and the one the
    // field's Library leak was invisible as: a completed round with nothing
    // removed read exactly like a round with nothing to remove. Named with the
    // missing sibling evidence when there is none, because on that device "no
    // sibling copy" is why they could not be decided, not a sign they are fine.
    if (pendingReview > 0) {
        val titles = if (pendingReview == 1) "title" else "titles"
        val why = if (round.siblingEvidence) {
            ""
        } else {
            " (no sibling library on this device to compare)"
        }
        base = "$base · found $pendingReview cloud $titles it could not " +
            "attribute$why — see the review row"
    }
    return base
}
