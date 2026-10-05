package com.kennyb1201.kbstream.data.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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
                episodeStreamId = entry.episodeStreamId ?: existing?.episodeStreamId
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
     */
    @Query("SELECT * FROM watch_history WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<WatchHistoryEntity>

    @Query(
        """
        SELECT * FROM watch_history
        WHERE parentId = :parentId
          AND positionMs > 0
          AND isCompleted = 0
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun getResumeForParent(parentId: String): WatchHistoryEntity?

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
      AND updatedAt IN (
          SELECT MAX(updatedAt)
          FROM watch_history
          WHERE positionMs > 0
            AND isCompleted = 0
          GROUP BY parentId
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
      AND updatedAt IN (
          SELECT MAX(updatedAt)
          FROM watch_history
          WHERE positionMs > 0
            AND isCompleted = 0
          GROUP BY parentId
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

    @Query("UPDATE watch_history SET backdropUrl = :backdropUrl WHERE id = :id AND (backdropUrl IS NULL OR backdropUrl = '')")
    suspend fun updateBackdropIfMissing(id: String, backdropUrl: String)

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Bulk form of [deleteById], so a pull applies all its tombstones in one
     *  transaction instead of one per removed row. */
    @Query("DELETE FROM watch_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId = :parentId
          AND season = :season
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParentSeason(parentId: String, season: Int)

    /** Flavor-tolerant form of [deleteCompletedForParentSeason]: unmarking a
     *  season must clear rows written under either id flavor. */
    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId IN (:parentIds)
          AND season = :season
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParentsSeason(parentIds: List<String>, season: Int)

    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId = :parentId
          AND season = :season
          AND episode = :episode
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParentSeasonEpisode(
        parentId: String,
        season: Int,
        episode: Int
    )

    /** Flavor-tolerant form of [deleteCompletedForParentSeasonEpisode]. */
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

    @Query(
        """
        DELETE FROM watch_history
        WHERE parentId = :parentId
          AND isCompleted = 1
        """
    )
    suspend fun deleteCompletedForParent(parentId: String)

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
}
