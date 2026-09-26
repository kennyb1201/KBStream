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

    @Query(
        """
        SELECT * FROM watch_history
        WHERE positionMs > 0
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getAll(): List<WatchHistoryEntity>

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
    )

    @Query("UPDATE watch_history SET backdropUrl = :backdropUrl WHERE id = :id AND (backdropUrl IS NULL OR backdropUrl = '')")
    suspend fun updateBackdropIfMissing(id: String, backdropUrl: String)

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteById(id: String)

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
