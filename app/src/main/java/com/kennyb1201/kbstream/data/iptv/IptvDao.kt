package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Guide storage.
 *
 * Every guide row references its source by INTEGER id ([EpgSourceEntity]) rather
 * than repeating the source URL — see that class for what the string cost. The
 * API here is still keyed by URL, because that is the only thing callers have:
 * each query resolves it with the uncorrelated scalar subquery
 * `(SELECT id FROM epg_sources WHERE url = :sourceUrl)`, which SQLite evaluates
 * once and then uses the `sourceId` index for. No caller has to know an id.
 */
@Dao
interface IptvDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<EpgChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(programs: List<EpgProgramEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedPlaylistChannels(
        channels: List<CachedPlaylistChannelEntity>
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistEpgMatches(
        matches: List<PlaylistEpgMatchEntity>
    )

    // ── Guide source identity ───────────────────────────────────────

    /**
     * Inserts [source], or nothing when its URL is already known. Returns the
     * new row id, or -1 when the unique `url` index rejected it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: EpgSourceEntity): Long

    @Query("SELECT id FROM epg_sources WHERE url = :url")
    suspend fun sourceIdOf(url: String): Long?

    /**
     * The id of [url]'s source row, creating it if this is the first write for
     * that guide. The insert-then-select shape is what makes this safe to call
     * from concurrent imports: the URL is UNIQUE, so whoever loses the race
     * reads the winner's id instead of creating a second one.
     *
     * The failure message deliberately carries no URL: a guide URL can be an
     * Xtream account's credentials, and this string reaches logs and Sentry.
     */
    @Transaction
    suspend fun ensureSourceId(url: String): Long {
        val normalized = url.trim()
        val inserted = insertSource(EpgSourceEntity(url = normalized))
        if (inserted != -1L) return inserted
        return sourceIdOf(normalized) ?: error("guide source row not found after insert")
    }

    // ── Guide rows ──────────────────────────────────────────────────

    @Query(
        "DELETE FROM epg_channels " +
            "WHERE sourceId = (SELECT id FROM epg_sources WHERE url = :sourceUrl)"
    )
    suspend fun deleteChannelsBySource(sourceUrl: String)

    @Query(
        "DELETE FROM epg_programs " +
            "WHERE sourceId = (SELECT id FROM epg_sources WHERE url = :sourceUrl)"
    )
    suspend fun deleteProgramsBySource(sourceUrl: String)

    @Query("DELETE FROM cached_playlist_channels WHERE playlistUrl = :playlistUrl")
    suspend fun deleteCachedPlaylistChannels(playlistUrl: String)

    @Query("DELETE FROM playlist_epg_matches WHERE playlistUrl = :playlistUrl")
    suspend fun deletePlaylistEpgMatchesByPlaylist(playlistUrl: String)

    @Query("DELETE FROM playlist_epg_matches WHERE epgUrl = :epgUrl")
    suspend fun deletePlaylistEpgMatchesByGuide(epgUrl: String)

    @Query(
        """
        SELECT
            playlistUrl,
            playlistChannelId,
            epgUrl,
            epgChannelId,
            matchType,
            updatedAtUtcMillis
        FROM playlist_epg_matches
        WHERE playlistUrl = :playlistUrl
          AND epgUrl = :epgUrl
          AND playlistChannelId IN (:playlistChannelIds)
        """
    )
    suspend fun getPlaylistEpgMatches(
        playlistUrl: String,
        epgUrl: String,
        playlistChannelIds: List<String>
    ): List<PlaylistEpgMatchEntity>

    @Query(
        """
        SELECT
            playlistUrl,
            position,
            id,
            name,
            displayName,
            streamUrl,
            groupTitle,
            logoUrl,
            tvgId,
            tvgName,
            tvgChno,
            catchup,
            catchupDays,
            catchupSource,
            providerChannelId,
            headersText
        FROM cached_playlist_channels
        WHERE playlistUrl = :playlistUrl
        ORDER BY position ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getCachedPlaylistChannelPage(
        playlistUrl: String,
        limit: Int,
        offset: Int
    ): List<CachedPlaylistChannelRow>

    @Transaction
    suspend fun replaceCachedPlaylistChannels(
        playlistUrl: String,
        channels: List<CachedPlaylistChannelEntity>
    ) {
        deleteCachedPlaylistChannels(playlistUrl)
        deletePlaylistEpgMatchesByPlaylist(playlistUrl)

        if (channels.isNotEmpty()) {
            insertCachedPlaylistChannels(channels)
        }
    }

    @Transaction
    suspend fun clearGuideBySource(sourceUrl: String) {
        deleteProgramsBySource(sourceUrl)
        deleteChannelsBySource(sourceUrl)
        deletePlaylistEpgMatchesByGuide(sourceUrl)
    }

    @Transaction
    suspend fun replaceGuide(
        sourceUrl: String,
        channels: List<EpgChannelEntity>,
        programs: List<EpgProgramEntity>
    ) {
        clearGuideBySource(sourceUrl)

        if (channels.isNotEmpty()) {
            insertChannels(channels)
        }

        if (programs.isNotEmpty()) {
            insertPrograms(programs)
        }
    }

    @Query(
        "SELECT EXISTS(SELECT 1 FROM epg_channels " +
            "WHERE sourceId = (SELECT id FROM epg_sources WHERE url = :sourceUrl) LIMIT 1)"
    )
    suspend fun hasChannelsForSource(sourceUrl: String): Boolean

    @Query(
        "UPDATE epg_channels " +
            "SET sourceId = (SELECT id FROM epg_sources WHERE url = :newUrl) " +
            "WHERE sourceId = (SELECT id FROM epg_sources WHERE url = :oldUrl)"
    )
    suspend fun rekeyChannelsSource(oldUrl: String, newUrl: String)

    @Query(
        "UPDATE epg_programs " +
            "SET sourceId = (SELECT id FROM epg_sources WHERE url = :newUrl) " +
            "WHERE sourceId = (SELECT id FROM epg_sources WHERE url = :oldUrl)"
    )
    suspend fun rekeyProgramsSource(oldUrl: String, newUrl: String)

    /**
     * Atomic import promotion: a successful parse staged rows under a
     * temporary source key; this moves them onto the real one inside a
     * single transaction, so readers never observe an empty or partially
     * imported guide. Re-keying is a plain UPDATE on the `sourceId` column
     * (no row reload into memory), and the live rows were already cleared
     * inside this same transaction so the composite keys never collide.
     *
     * Both source rows are ensured first: the staging key is created by the
     * import that is being promoted, and the live key may be brand new on a
     * first import — a re-key to or from a missing id would silently move
     * nothing (SQLite evaluates the subquery to NULL and matches no rows).
     */
    @Transaction
    suspend fun swapStagedGuideIntoLive(sourceUrl: String, stagingUrl: String) {
        ensureSourceId(sourceUrl)
        ensureSourceId(stagingUrl)

        clearGuideBySource(sourceUrl)

        rekeyChannelsSource(oldUrl = stagingUrl, newUrl = sourceUrl)
        rekeyProgramsSource(oldUrl = stagingUrl, newUrl = sourceUrl)
    }

    @Query(
        """
        SELECT *
        FROM epg_channels
        WHERE sourceId = (SELECT id FROM epg_sources WHERE url = :sourceUrl)
        """
    )
    suspend fun getChannelsBySource(sourceUrl: String): List<EpgChannelEntity>

    /**
     * The first [perChannelLimit] programs per channel in the window: the guide's
     * own rows, without the description/category the full read carries.
     *
     * Written as a correlated count rather than the partitioned row-numbering
     * window function it used to be, which is the same "first N per channel" but
     * on machinery every SQLite this app can run has: window functions need
     * 3.25+, minSdk is 26 (SQLite 3.19), and Fire OS on the Fire Cube ships 3.22 —
     * where SQLite rejects the window clause outright as a syntax error and
     * throws on the main thread, so opening the guide force-closed the app
     * (Sentry often missed it, because the process died before the report
     * flushed). [GuideWindowQueryCompatibilityTest] pins both the equivalence and
     * the absence of the old syntax. A row is kept when FEWER than
     * [perChannelLimit] in-window programs on its channel start before it,
     * which is exactly the row-number test; equal starts are broken by `rowid`
     * so the answer is deterministic rather than whatever the scan order was.
     *
     * The window predicates are repeated inside the count so it measures the
     * same set the outer query filters — the subquery is correlated on
     * (sourceId, channelId), which is the coverage this table already had, and
     * n is programs-per-channel inside a time window (tens, not thousands), the
     * same bound the window function worked within.
     */
    @Query(
        """
        SELECT
            p1.channelId AS channelId,
            p1.title AS title,
            '' AS description,
            '' AS category,
            p1.startUtcMillis AS startUtcMillis,
            p1.endUtcMillis AS endUtcMillis
        FROM epg_programs p1
        WHERE p1.sourceId = (SELECT id FROM epg_sources WHERE url = :sourceUrl)
          AND p1.endUtcMillis > :windowStart
          AND p1.startUtcMillis < :windowEnd
          AND p1.channelId IN (:channelIds)
          AND (
              SELECT COUNT(*)
              FROM epg_programs p2
              WHERE p2.sourceId = p1.sourceId
                AND p2.channelId = p1.channelId
                AND p2.endUtcMillis > :windowStart
                AND p2.startUtcMillis < :windowEnd
                AND (
                    p2.startUtcMillis < p1.startUtcMillis
                    OR (p2.startUtcMillis = p1.startUtcMillis AND p2.rowid < p1.rowid)
                )
          ) < :perChannelLimit
        ORDER BY p1.channelId ASC, p1.startUtcMillis ASC
        """
    )
    suspend fun getProgramsForChannelsInWindowLite(
        sourceUrl: String,
        channelIds: List<String>,
        windowStart: Long,
        windowEnd: Long,
        perChannelLimit: Int
    ): List<EpgProgramRow>

    /**
     * The [perChannelLimit] LATEST programs per channel that have already aired,
     * newest first — the guide's "what just finished" rows.
     *
     * The DESC twin of [getProgramsForChannelsInWindowLite]'s correlated count
     * (see there for why window functions are not an option on this app's
     * devices): the count now measures how many in-window programs start AFTER
     * this one, so keeping count < [perChannelLimit] keeps the N latest. The
     * window predicates are the same two the outer query filters on, `:nowMillis`
     * included, and equal starts stay in `rowid` order — the same tie-break the
     * ascending twin uses, so neither read depends on scan order.
     */
    @Query(
        """
        SELECT
            p1.channelId AS channelId,
            p1.title AS title,
            p1.description AS description,
            p1.category AS category,
            p1.startUtcMillis AS startUtcMillis,
            p1.endUtcMillis AS endUtcMillis
        FROM epg_programs p1
        WHERE p1.sourceId = (SELECT id FROM epg_sources WHERE url = :sourceUrl)
          AND p1.endUtcMillis <= :nowMillis
          AND p1.endUtcMillis > :windowStart
          AND p1.channelId IN (:channelIds)
          AND (
              SELECT COUNT(*)
              FROM epg_programs p2
              WHERE p2.sourceId = p1.sourceId
                AND p2.channelId = p1.channelId
                AND p2.endUtcMillis <= :nowMillis
                AND p2.endUtcMillis > :windowStart
                AND (
                    p2.startUtcMillis > p1.startUtcMillis
                    OR (p2.startUtcMillis = p1.startUtcMillis AND p2.rowid < p1.rowid)
                )
          ) < :perChannelLimit
        ORDER BY p1.channelId ASC, p1.startUtcMillis DESC
        """
    )
    suspend fun getRecentProgramsForChannels(
        sourceUrl: String,
        channelIds: List<String>,
        nowMillis: Long,
        windowStart: Long,
        perChannelLimit: Int
    ): List<EpgProgramRow>

    /**
     * The first [perChannelLimit] programs per channel in the window, with the
     * description and category the in-player guide shows.
     *
     * The same correlated count as [getProgramsForChannelsInWindowLite], on the
     * same argument (a window clause is a syntax error on the SQLite Fire OS
     * ships), with this read's own column list: [perChannelLimit] limits how many
     * rows a channel contributes, never which columns they carry.
     */
    @Query(
        """
        SELECT
            p1.channelId AS channelId,
            p1.title AS title,
            p1.description AS description,
            p1.category AS category,
            p1.startUtcMillis AS startUtcMillis,
            p1.endUtcMillis AS endUtcMillis
        FROM epg_programs p1
        WHERE p1.sourceId = (SELECT id FROM epg_sources WHERE url = :sourceUrl)
          AND p1.endUtcMillis > :windowStart
          AND p1.startUtcMillis < :windowEnd
          AND p1.channelId IN (:channelIds)
          AND (
              SELECT COUNT(*)
              FROM epg_programs p2
              WHERE p2.sourceId = p1.sourceId
                AND p2.channelId = p1.channelId
                AND p2.endUtcMillis > :windowStart
                AND p2.startUtcMillis < :windowEnd
                AND (
                    p2.startUtcMillis < p1.startUtcMillis
                    OR (p2.startUtcMillis = p1.startUtcMillis AND p2.rowid < p1.rowid)
                )
          ) < :perChannelLimit
        ORDER BY p1.channelId ASC, p1.startUtcMillis ASC
        """
    )
    suspend fun getProgramsForChannelsInWindow(
        sourceUrl: String,
        channelIds: List<String>,
        windowStart: Long,
        windowEnd: Long,
        perChannelLimit: Int
    ): List<EpgProgramRow>

    /**
     * Guide-wide program search: title match on every channel, limited to
     * programs that have not finished yet ("what's on with X tonight").
     *
     * This is the SUBSTRING half of the search and the slower one: a leading
     * wildcard cannot use an index, so it scans and sorts every program that
     * has not finished yet. It runs only when the indexed search (below) found
     * nothing, and it is what lets a mid-word fragment ("waii" for "Hawaii
     * Five-0") still match. [pattern] is a `%q%` pattern built by
     * `likeContainsPattern`, which escapes `%`, `_` and `\` so those are
     * matched literally; the ESCAPE clause must match that escaping.
     *
     * Deliberately NOT filtered by source or channel: the caller drops
     * hits whose channel is hidden and maps the rest through the guide's own
     * channel list, which is the only place that knows what is visible.
     * SQLite's LIKE is case-insensitive for ASCII, so no COLLATE is needed.
     */
    @Query(
        """
        SELECT
            channelId,
            title,
            description,
            category,
            startUtcMillis,
            endUtcMillis
        FROM epg_programs
        WHERE title LIKE :pattern ESCAPE '\'
          AND endUtcMillis > :fromMillis
        ORDER BY startUtcMillis ASC
        LIMIT :limit
        """
    )
    suspend fun searchProgramsByTitleLike(
        pattern: String,
        fromMillis: Long,
        limit: Int
    ): List<EpgProgramRow>

    /**
     * Indexed half of the guide search: [query] carries an FTS4 `MATCH`
     * expression against `epg_programs_fts`, joined back to `epg_programs` on
     * the program id so an index entry whose program is gone cannot surface.
     * Throws when the index is missing, which the caller treats as "use the
     * LIKE scan". Raw because the FTS table lives outside Room's schema
     * (see EpgSearchIndex for why it is not a `@Fts4` entity).
     */
    @RawQuery
    suspend fun searchProgramsByTitleFts(query: SupportSQLiteQuery): List<EpgProgramRow>
}
