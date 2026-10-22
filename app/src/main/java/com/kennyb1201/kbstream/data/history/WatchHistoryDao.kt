package com.kennyb1201.kbstream.data.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * When a save may erase a completion that is already stored.
 *
 * The failure this closes: a save's verdict is decided on the main thread and
 * the write is dispatched to IO, so two saves of the same row are not ordered
 * by the order they were decided in. A pause-save decided an instant before the
 * episode's end (its `isCompleted` false, its position a few seconds from the
 * end) could therefore land AFTER the completion write and overwrite the
 * completed row with a resume row: the episode lost its watched marker and
 * went straight back onto Continue Watching with a progress bar at 99%. The
 * same thing is reachable through the shutdown save when the activity is torn
 * down mid-completion.
 *
 * The rule is about ORDER, not permission: a row that was completed and then
 * re-watched legitimately comes back as a resume row (the viewer chose to play
 * it again, in a later session), so a completion may not simply be permanent.
 * What it may not be overwritten by is a save that was DECIDED BEFORE the
 * completion was filed - and a save's own `updatedAt` is that decision time.
 * So: a resume row whose stamp is at or before the stored completion's stamp is
 * a stale save, and the completed row stands. Anything later than the
 * completion is a new session and writes over it exactly as it always did.
 *
 * A row completed before `completedAt` was tracked (null) has nothing to
 * compare and takes the write, as does one whose stored completion is newer
 * than every save that could race it.
 *
 * Pure, so it is pinned by [HistoryCompletionRulesTest] rather than through a
 * player.
 */
internal object HistoryCompletionRules {

    /**
     * True when [incomingUpdatedAt] belongs to a save that was decided before
     * the stored completion, i.e. when the stored completion must win.
     */
    fun staleResumeOverCompletion(
        storedCompletedAt: Long?,
        incomingUpdatedAt: Long
    ): Boolean = storedCompletedAt != null && incomingUpdatedAt <= storedCompletedAt
}

@Dao
interface WatchHistoryDao {
    @Upsert
    suspend fun upsertRaw(entry: WatchHistoryEntity)

    /**
     * Merge a playback row without allowing a partial caller to erase
     * metadata already stored for the same title/episode.
     */
    @androidx.room.Transaction
    suspend fun upsert(entry: WatchHistoryEntity) {
        val existing = getById(entry.id)
        // A save decided before the completion was filed must not overwrite it
        // (see [HistoryCompletionRules]): the write table is the last one to
        // land, not the last one to be decided.
        if (existing != null &&
            !entry.isCompleted &&
            HistoryCompletionRules.staleResumeOverCompletion(
                storedCompletedAt = existing.completedAt,
                incomingUpdatedAt = entry.updatedAt
            )
        ) {
            return
        }
        upsertRaw(
            entry.copy(
                name = entry.name.ifBlank { existing?.name.orEmpty() },
                episodeTitle = entry.episodeTitle ?: existing?.episodeTitle,
                overview = entry.overview ?: existing?.overview,
                clearLogo = entry.clearLogo ?: existing?.clearLogo,
                backdropUrl = entry.backdropUrl ?: existing?.backdropUrl,
                totalEpisodesInSeason =
                    entry.totalEpisodesInSeason ?: existing?.totalEpisodesInSeason,
                poster = entry.poster ?: existing?.poster,
                streamUrl = entry.streamUrl ?: existing?.streamUrl,
                season = entry.season ?: existing?.season,
                episode = entry.episode ?: existing?.episode,
                episodeStreamId = entry.episodeStreamId ?: existing?.episodeStreamId,
                // A completed row whose stamp is missing keeps the stamp the
                // stored row already had. The completion time is what the
                // continue-watching rules read, and a remote row can arrive
                // without it (the merge is not the thing that decides whether
                // an episode was finished) - taking the null erased the local
                // completion, and the late pause-save then re-opened the
                // episode at 99% of its length.
                completedAt = entry.completedAt
                    ?: existing?.completedAt?.takeIf { entry.isCompleted }
            )
        )
    }

    /**
     * Bulk form of [upsert] in ONE transaction: a whole-series "mark watched"
     * writes a row per episode, and hundreds of separate transactions is both
     * slow and a lot of churn on the SQLite writer.
     */
    @androidx.room.Transaction
    suspend fun upsertAll(entries: List<WatchHistoryEntity>) {
        entries.forEach { entry ->
            upsert(entry)
        }
    }

    /**
     * The one-shot root migration's apply step: every id that leaves and every
     * canonical row that replaces it, as ONE transaction.
     *
     * A renamed row is a delete plus an insert - Room cannot change a primary
     * key in place - and the two must not be observable apart: a reader finding
     * neither id sees the title vanish from Continue Watching, and one finding
     * both sees it twice with a progress bar on each. The deletes run first, so
     * a target id that another row's stale id vacates is free before its row is
     * written (and is not deleted back out from under it). [upsertAllRaw] is
     * the raw form on purpose: the migration writes the row it computed, not a
     * merge of it with the row it is replacing.
     */
    @Transaction
    suspend fun applyRootMigration(
        deletedIds: List<String>,
        writes: List<WatchHistoryEntity>
    ) {
        if (deletedIds.isNotEmpty()) deleteByIds(deletedIds)
        if (writes.isNotEmpty()) upsertAllRaw(writes)
    }

    /**
     * Bulk form of [upsertRaw] in ONE transaction, for writers that have no
     * existing rows to merge with.
     *
     * [upsertAll] is the merge form: it reads the stored row for each entry
     * before writing, so a partial caller cannot blank metadata it did not
     * carry. A backup RESTORE has just cleared the table, so every one of those
     * reads is guaranteed to miss - on a large history that is a database
     * round-trip per row for a result already known. This writes them directly,
     * still in a single transaction.
     */
    @androidx.room.Transaction
    suspend fun upsertAllRaw(entries: List<WatchHistoryEntity>) {
        entries.forEach { entry ->
            upsertRaw(entry)
        }
    }

    @Query("SELECT * FROM watch_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WatchHistoryEntity?

    /**
     * Batch variant of [getById] for sync merges: one query for the whole
     * remote batch instead of one per row (N+1 made a large history pull
     * issue thousands of individual lookups on every sync).
     *
     * Chunked: the caller's list is a page of the CLOUD table, so its length is
     * whatever the account holds, and SQLite caps a statement's bound
     * parameters. Past that cap this query threw, the pull's catch reported the
     * table as unsynced, and history from an account with a few thousand rows
     * never landed at all - on every attempt, forever.
     */
    suspend fun getByIds(ids: List<String>): List<WatchHistoryEntity> =
        ids.chunked(com.kennyb1201.kbstream.data.db.SQLITE_MAX_QUERY_VARIABLES)
            .flatMap { getByIdsPage(it) }

    @Query("SELECT * FROM watch_history WHERE id IN (:ids)")
    suspend fun getByIdsPage(ids: List<String>): List<WatchHistoryEntity>

    /**
     * Flavor-tolerant form of [getResumeForParent].
     *
     * The same title is reachable under more than one id flavor - TMDB search
     * rows and the hardcoded kids rails carry "tmdb:<n>", add-on catalogs and
     * Continue Watching carry "tt..." - and a playback row is stored under
     * whichever flavor launched it. Looking up only the route's own flavor
     * therefore hides progress that was made from the other one (no RESUME
     * button, no progress bar on a title paused elsewhere in the app).
     * Callers pass the route id plus its resolved twin and get whichever row
     * exists.
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId IN (:parentIds)
          AND positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun getResumeForParents(parentIds: List<String>): WatchHistoryEntity?

    /**
     * The id flavors [parentId] is stored under: the id itself, plus its
     * resolved flavor twin from the offline resolution cache ("tt..." vs
     * "tmdb:<n>"), for a caller that has to read or clean up across BOTH.
     *
     * Offline by construction - it never issues a request, because its caller is
     * a save path. When the twin is not cached the raw id is all it answers,
     * exactly as before.
     */
    @Query(
        """
        SELECT :parentId AS parentId
        UNION
        SELECT 'tmdb:' || tmdbId FROM imdb_resolution_cache
          WHERE imdbId = :parentId AND tmdbId > 0
        UNION
        SELECT imdbId FROM imdb_resolution_cache
          WHERE key IN ('series::' || :parentId, 'series::' || REPLACE(:parentId, 'tmdb:', ''))
            AND imdbId LIKE 'tt%'
        """
    )
    suspend fun parentIdFlavors(parentId: String): List<String>

    /**
     * Every in-progress row for a parent, newest first. The detail screen
     * maps these by episodeStreamId so every episode card can show its own
     * progress bar / time left — [getResumeForParent] only carries the most
     * recent one, which left the other in-progress episodes without progress.
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getInProgressForParent(parentId: String): List<WatchHistoryEntity>

    /** Flavor-tolerant form of [getInProgressForParent] (see
     *  [getResumeForParents]). */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId IN (:parentIds)
          AND positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getInProgressForParents(parentIds: List<String>): List<WatchHistoryEntity>

    /**
     * Reactive form of [getInProgressForParents]: the same rows, re-emitted
     * whenever the table changes.
     *
     * The Detail screen's resume bar and per-episode progress bars used to read
     * these rows ONCE per load, so a progress write that landed after that read
     * (the player finishing an episode as the viewer leaves it) left the bars
     * on an episode that was already marked watched until the page was reopened.
     * Collecting them makes those bars follow the database instead of a snapshot
     * of it.
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId IN (:parentIds)
          AND positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        """
    )
    fun observeInProgressForParents(parentIds: List<String>): Flow<List<WatchHistoryEntity>>

    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND isCompleted = 1
        ORDER BY season ASC, episode ASC, updatedAt DESC
        """
    )
    suspend fun getCompletedForParent(parentId: String): List<WatchHistoryEntity>

    /** Flavor-tolerant form of [getCompletedForParent] (see
     *  [getResumeForParents]): episode checkmarks and per-season watched
     *  state must survive the title being opened from its other id flavor. */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId IN (:parentIds)
          AND isCompleted = 1
        ORDER BY season ASC, episode ASC, updatedAt DESC
        """
    )
    suspend fun getCompletedForParents(parentIds: List<String>): List<WatchHistoryEntity>

    /**
     * Completed rows whose parent has NO in-progress (resume) row left, updated
     * at or after [since].
     *
     * This is the read behind Home's "this profile just finished it" rule (see
     * `trackerCardJustCompletedByProfile`): a tracker card leaves the rail as
     * soon as local history proves the thing it points at was completed on
     * this device, instead of waiting for the tracker's own feed to catch up
     * (which is minutes, not seconds).
     *
     * [since] keeps the read small and keeps the rule off titles finished long
     * ago. The NOT IN clause is what makes the rule safe: a title the viewer
     * has since started again has an in-progress row, and its card is not this
     * rule's business.
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE isCompleted = 1
          AND updatedAt >= :since
          AND parentId NOT IN (
              SELECT parentId FROM watch_history
              WHERE positionMs > 0
                AND isCompleted = 0
          )
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getRecentlyCompletedWithoutResume(since: Long): List<WatchHistoryEntity>

    /**
     * Reactive form of [getCompletedForParents]: the same rows, re-emitted
     * whenever the table changes.
     *
     * The episode checkmarks had exactly the defect the progress bars did (see
     * [observeInProgressForParents]) and are fixed the same way. Completed rows
     * were read ONCE per load, so an episode the player finished as the viewer
     * left it did not tick its card until the page was reopened - while the
     * progress bar on that same card cleared immediately, because that path
     * already followed the table. Collecting these makes the markers follow it
     * too.
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId IN (:parentIds)
          AND isCompleted = 1
        ORDER BY season ASC, episode ASC, updatedAt DESC
        """
    )
    fun observeCompletedForParents(parentIds: List<String>): Flow<List<WatchHistoryEntity>>

    @Query(
        """
        SELECT * FROM watch_history
        WHERE positionMs > 0
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getAll(): List<WatchHistoryEntity>

    /**
     * Every stored row, filters and all: what the one-shot root migration has
     * to see.
     *
     * Deliberately not [getAll], which is the launcher and viewing-stats read
     * and skips rows with no saved position. A stale root is exactly as wrong
     * on a row that never moved off 0, and the point of the migration is that
     * no row keeps an id the rest of the app no longer names.
     */
    @Query("SELECT * FROM watch_history")
    suspend fun getAllRows(): List<WatchHistoryEntity>

    /**
     * The rows the TV launcher's Continue watching rail can publish: in-progress
     * (not completed) with a saved position, newest first.
     *
     * Narrower than [getAll] on purpose. Every history save rebuilt the launcher
     * rail, and it did so from [getAll] - which drags back every COMPLETED row
     * too, the part of the table that grows without bound on a long-lived
     * install. Those rows can never become a launcher card (the publisher drops
     * completed rows), so reading them on each save was pure waste on the main
     * write path.
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getResumeRowsForLauncher(): List<WatchHistoryEntity>

    /**
     * Series rows that carry at least one COMPLETED episode, newest completion
     * first. Backs Home's local "next up" cards: a show whose episodes were
     * marked watched has no in-progress row left, and Continue Watching - built
     * only from those rows - dropped it even though unwatched episodes
     * remained. Callers group by parentId (one card per show) and cap the list.
     *
     * Live channels and movies are excluded: neither is something to continue
     * episode by episode.
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE isCompleted = 1
          AND lower(type) NOT IN ('movie', 'channel')
        ORDER BY completedAt DESC, updatedAt DESC
        """
    )
    suspend fun getCompletedSeriesRows(): List<WatchHistoryEntity>

    @Query(
        """
        SELECT * FROM watch_history
        WHERE positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        """
    )
    fun observeRecent(): Flow<List<WatchHistoryEntity>>

    @Query(
    """
    SELECT * FROM watch_history
    WHERE positionMs > 0
      AND isCompleted = 0
      AND id IN (
          -- One row per parent, by the latest stamp. The old form matched on
          -- updatedAt itself, so two resume rows for one show written in the
          -- same MILLISECOND (a bulk apply does exactly that) both matched and
          -- the rail showed the show twice. Selecting the id instead picks
          -- exactly one row per group (SQLite's bare column on MAX).
          SELECT id
          FROM watch_history
          WHERE positionMs > 0
            AND isCompleted = 0
          GROUP BY parentId
          HAVING updatedAt = MAX(updatedAt)
      )
    ORDER BY updatedAt DESC
    """
)
fun observeContinueWatchingParents(): Flow<List<WatchHistoryEntity>>

    /**
     * One-shot suspend snapshot of [observeContinueWatchingParents]. Used by
     * HomeViewModel's instant Continue Watching seed so the rail can render
     * from local rows immediately instead of waiting for the enrichment
     * pipeline or a Simkl round-trip.
     */
    @Query(
    """
    SELECT * FROM watch_history
    WHERE positionMs > 0
      AND isCompleted = 0
      AND id IN (
          -- One row per parent, by the latest stamp. The old form matched on
          -- updatedAt itself, so two resume rows for one show written in the
          -- same MILLISECOND (a bulk apply does exactly that) both matched and
          -- the rail showed the show twice. Selecting the id instead picks
          -- exactly one row per group (SQLite's bare column on MAX).
          SELECT id
          FROM watch_history
          WHERE positionMs > 0
            AND isCompleted = 0
          GROUP BY parentId
          HAVING updatedAt = MAX(updatedAt)
      )
    ORDER BY updatedAt DESC
    """
)
suspend fun getContinueWatchingParentsSnapshot(): List<WatchHistoryEntity>

    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId = :parentId
          AND positionMs > 0
          AND isCompleted = 0
        """
    )
    suspend fun deleteResumeRowsForParent(parentId: String)

    /**
     * [getInProgressForParent] and [deleteResumeRowsForParent] as ONE
     * transaction, returning the ids it removed. The read and the delete used
     * to be two statements, so a completion landing between them was deleted
     * as if it were still a resume row - and its caller then filed a cloud
     * tombstone for a row that had just become a completed marker, letting a
     * sibling lose the watched state. Room's @Transaction is what closes the
     * window; the two statements cannot be observed apart.
     */
    @Transaction
    suspend fun removeResumeRowsForParentReturningIds(parentId: String): List<String> {
        val ids = getInProgressForParent(parentId).map { it.id }
        deleteResumeRowsForParent(parentId)
        return ids
    }

    /** Flavor-tolerant form of [deleteResumeRowsForParent]: clearing the
     *  resume rows of a title that has been fully marked watched must reach
     *  the rows written under its other id flavor too. */
    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId IN (:parentIds)
          AND positionMs > 0
          AND isCompleted = 0
        """
    )
    suspend fun deleteResumeRowsForParents(parentIds: List<String>)

    /**
     * [getInProgressForParents] and [deleteResumeRowsForParents] as ONE
     * transaction, returning the ids it removed - the plural form of
     * [removeResumeRowsForParentReturningIds], and for the same reason.
     *
     * Every caller of the pair TOMBSTONES the ids it gets back (a row deleted
     * only locally is re-inserted by the next pull). Asking "which rows are
     * gone" AFTER a delete is what made that unsafe: a completion that reused a
     * resume row's id survives the delete as the completed marker, and a
     * tombstone filed for it - the row that stays - erases the watched state on
     * every other device. The answer is the transaction's own, so the caller no
     * longer has to ask.
     */
    @Transaction
    suspend fun removeResumeRowsForParentsReturningIds(parentIds: List<String>): List<String> {
        val ids = getInProgressForParents(parentIds).map { it.id }
        deleteResumeRowsForParents(parentIds)
        return ids
    }

    /**
     * The episode's resume rows, matched by BOTH identities, removed in one
     * transaction that returns the ids that went.
     *
     * The two deletes are the season/episode match and the stream-id match (the
     * identities can disagree - an add-on's numbering against TMDB's), and the
     * rows they remove are exactly the in-progress rows of the same parents
     * that carry either identity, which is what the Kotlin-side filter below
     * reproduces. See [removeResumeRowsForParentsReturningIds] for why the ids
     * have to come from the same transaction as the delete.
     */
    @Transaction
    suspend fun removeResumeRowsForEpisodeReturningIds(
        parentIds: List<String>,
        streamIds: List<String>,
        season: Int,
        episode: Int
    ): List<String> {
        val ids = getInProgressForParents(parentIds)
            .filter { row ->
                (row.season == season && row.episode == episode) ||
                    (row.episodeStreamId != null && row.episodeStreamId in streamIds)
            }
            .map { it.id }
        deleteResumeRowsForParentsSeasonEpisode(
            parentIds = parentIds,
            season = season,
            episode = episode
        )
        if (streamIds.isNotEmpty()) {
            deleteResumeRowsForParentsStreamIds(parentIds = parentIds, streamIds = streamIds)
        }
        return ids
    }

    /**
     * Single-episode form of [deleteResumeRowsForParents]: marking one episode
     * - or a season's worth - watched has to take that episode's progress bar
     * with it, and the row holding that progress is not the completed marker
     * the mark writes.
     */
    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId IN (:parentIds)
          AND season = :season
          AND episode = :episode
          AND positionMs > 0
          AND isCompleted = 0
        """
    )
    suspend fun deleteResumeRowsForParentsSeasonEpisode(
        parentIds: List<String>,
        season: Int,
        episode: Int
    ): Int

    /**
     * Identity-based form of [deleteResumeRowsForParentsSeasonEpisode].
     *
     * Season/episode numbers are not the identity the rest of the app uses
     * for an episode's progress: DetailScreen maps an episode card to its
     * progress row by [WatchHistoryEntity.episodeStreamId], and the player
     * stores exactly the stream id it was launched with. Matching on that is
     * immune to the numbering disagreement a season/episode match can hit (an
     * add-on's numbering against TMDB's, specials, a 0-based source), which
     * is how a marked-watched episode could keep its resume bar.
     *
     * Returns the number of rows removed, so callers can tell "nothing to
     * clean" apart from "the match missed".
     */
    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId IN (:parentIds)
          AND episodeStreamId IN (:streamIds)
          AND positionMs > 0
          AND isCompleted = 0
        """
    )
    suspend fun deleteResumeRowsForParentsStreamIds(
        parentIds: List<String>,
        streamIds: List<String>
    ): Int

    /**
     * The in-progress rows a COMPLETION for one episode supersedes, by the
     * episode's identity.
     *
     * A completion is written as the same row the session has been saving - the
     * completed marker and the resume point are one row in this table - so the
     * everyday case clears the resume point by overwriting it (`positionMs = 0`,
     * `isCompleted = 1`) and there is nothing left to delete. The rows this
     * finds are the ones that overwrite does NOT reach: a resume row filed
     * under a different row id than the completion is - an add-on's video id
     * against the numbered key the launch resolved, or an earlier session that
     * saved under its own id flavor. Continue Watching and the Detail screen's
     * progress bar are both built from exactly these rows, so one of them left
     * behind is the "it still says 10 minutes left" report: the episode is
     * marked watched AND still offers to resume.
     *
     * The match is the identity pair the other resume-row deletes use - the
     * stream id when the launch carried one (immune to a numbering
     * disagreement), else the season/episode numbers - written once here rather
     * than re-derived in Kotlin. Either half may be absent, and a session that
     * carries neither (a movie) matches nothing, which is right: a movie's own
     * resume row IS the row the completion overwrites.
     *
     * Returns the ids so the caller can tombstone them in the cloud too - a
     * delete only local is re-inserted by the next pull (see
     * [com.kennyb1201.kbstream.data.sync.SupabaseSync.deleteHistoryRows]).
     */
    @Query(
        """
        SELECT id FROM watch_history
        WHERE parentId IN (:parentIds)
          AND positionMs > 0
          AND isCompleted = 0
          AND (
              (:streamId IS NOT NULL AND :streamId <> '' AND episodeStreamId = :streamId)
              OR (:season IS NOT NULL AND :episode IS NOT NULL
                  AND season = :season AND episode = :episode)
          )
        """
    )
    suspend fun getSupersededResumeIds(
        parentIds: List<String>,
        streamId: String?,
        season: Int?,
        episode: Int?
    ): List<String>

    @Query("UPDATE watch_history SET backdropUrl = :backdropUrl WHERE id = :id AND (backdropUrl IS NULL OR backdropUrl = '')")
    suspend fun updateBackdropIfMissing(id: String, backdropUrl: String)

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Bulk form of [deleteById], so a pull applies all its tombstones in one
     *  transaction instead of one per removed row. Chunked for the same reason
     *  [getByIds] is: the list comes from the cloud and has no bound. */
    suspend fun deleteByIds(ids: List<String>) {
        ids.chunked(com.kennyb1201.kbstream.data.db.SQLITE_MAX_QUERY_VARIABLES)
            .forEach { deleteByIdsPage(it) }
    }

    @Query("DELETE FROM watch_history WHERE id IN (:ids)")
    suspend fun deleteByIdsPage(ids: List<String>)

    /** Unmarking a season must clear rows written under either id flavor. */
    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId IN (:parentIds)
          AND season = :season
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParentsSeason(parentIds: List<String>, season: Int)

    /** Unmarking one episode must clear rows written under either id flavor. */
    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId IN (:parentIds)
          AND season = :season
          AND episode = :episode
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParentsSeasonEpisode(
        parentIds: List<String>,
        season: Int,
        episode: Int
    )

    /** Flavor-tolerant form of [deleteCompletedForParent]. */
    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId IN (:parentIds)
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParents(parentIds: List<String>)

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()

    // ── Viewing stats (read-only) ─────────────────────────────────────────────
    //
    // One row is updated in place per episode, so exact watch time is NOT
    // recoverable - a rewatch or a scrub overwrites the row. What these read is
    // the part the table honestly supports: finished runtime is the sum of the
    // durations of COMPLETED rows, and in-progress runtime is the saved
    // position of the rows still open. Nothing here writes.

    // ── Viewing stats ────────────────────────────────────────────────────
    //
    // Every read below EXCLUDES marked-watched rows (see
    // WatchHistoryEntity.markedWatched). A mark is a completed row with a 1 ms
    // sentinel duration and no position: counting it made a bulk-marked show
    // report hundreds of "finished episodes" at ~2.5 minutes each, and its
    // completion stamps fabricated streak days. The rows themselves are
    // untouched - badges, Continue Watching, resume and sync still read them.

    /** Titles (shows or movies) with at least one completed ROW of real watch. */
    @Query(
        "SELECT COUNT(DISTINCT parentId) FROM watch_history " +
            "WHERE isCompleted = 1 AND markedWatched = 0"
    )
    suspend fun finishedTitleCount(): Int

    /**
     * Completed episode rows.
     *
     * An episode row is anything that is NOT a movie or a live channel, which
     * is the same reading [getCompletedSeriesRows] takes and the only one that
     * matches what is actually stored: the row's `type` is the parent's media
     * type, so an episode is filed under "series" (or "tv", or an addon's own
     * flavour) — never under "episode". Counting `type = 'episode'` matched
     * nothing at all, which is how the Viewing stats screen reported zero
     * episodes finished while the top-shows list right below it named shows
     * with ten.
     */
    @Query(
        """
        SELECT COUNT(*) FROM watch_history
        WHERE isCompleted = 1
          AND markedWatched = 0
          AND lower(type) NOT IN ('movie', 'channel')
        """
    )
    suspend fun completedEpisodeCount(): Int

    /** Completed movie rows: the one media type that is a movie outright. */
    @Query(
        "SELECT COUNT(*) FROM watch_history " +
            "WHERE isCompleted = 1 AND markedWatched = 0 AND lower(type) = 'movie'"
    )
    suspend fun completedMovieCount(): Int

    /** Runtime of everything really watched: Σ durationMs over completed rows. */
    @Query(
        "SELECT COALESCE(SUM(durationMs), 0) FROM watch_history " +
            "WHERE isCompleted = 1 AND markedWatched = 0"
    )
    suspend fun finishedRuntimeMs(): Long

    /**
     * Every completion timestamp, newest first. The streak is computed in
     * Kotlin from these (see ViewingStats.streakDays) rather than in SQL, so
     * the UTC-day bucketing rule is pinned by a unit test.
     */
    @Query(
        """
        SELECT completedAt FROM watch_history
        WHERE isCompleted = 1 AND markedWatched = 0 AND completedAt IS NOT NULL
        ORDER BY completedAt DESC
        """
    )
    suspend fun completionTimes(): List<Long>

    /**
     * The ten titles with the most runtime: finished duration for a completed
     * row, the saved position for one still in progress, so a show being watched
     * now still ranks rather than only completed ones.
     */
    @Query(
        """
        SELECT parentId,
               name,
               poster,
               COALESCE(
                   SUM(
                       CASE WHEN isCompleted = 1 AND markedWatched = 0 THEN durationMs
                            ELSE positionMs END
                   ),
                   0
               ) AS ms,
               COUNT(CASE WHEN isCompleted = 1 AND markedWatched = 0 THEN 1 END) AS done
        FROM watch_history
        GROUP BY parentId
        ORDER BY ms DESC
        LIMIT 10
        """
    )
    suspend fun topShows(): List<TopShow>
}
