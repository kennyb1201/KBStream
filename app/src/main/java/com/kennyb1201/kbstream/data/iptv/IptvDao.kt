package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

    @Query("DELETE FROM epg_channels WHERE sourceUrl = :sourceUrl")
    suspend fun deleteChannelsBySource(sourceUrl: String)

    @Query("DELETE FROM epg_programs WHERE sourceUrl = :sourceUrl")
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

    @Query("SELECT EXISTS(SELECT 1 FROM epg_channels WHERE sourceUrl = :sourceUrl LIMIT 1)")
    suspend fun hasChannelsForSource(sourceUrl: String): Boolean

    @Query("UPDATE epg_channels SET sourceUrl = :newUrl WHERE sourceUrl = :oldUrl")
    suspend fun rekeyChannelsSource(oldUrl: String, newUrl: String)

    @Query("UPDATE epg_programs SET sourceUrl = :newUrl WHERE sourceUrl = :oldUrl")
    suspend fun rekeyProgramsSource(oldUrl: String, newUrl: String)

    /**
     * Atomic import promotion: a successful parse staged rows under a
     * temporary source key; this moves them onto the real one inside a
     * single transaction, so readers never observe an empty or partially
     * imported guide. Re-keying is a plain UPDATE on the sourceUrl column
     * (no row reload into memory), and the live rows were already cleared
     * inside this same transaction so the composite keys never collide.
     */
    @Transaction
    suspend fun swapStagedGuideIntoLive(sourceUrl: String, stagingUrl: String) {
        clearGuideBySource(sourceUrl)

        rekeyChannelsSource(oldUrl = stagingUrl, newUrl = sourceUrl)
        rekeyProgramsSource(oldUrl = stagingUrl, newUrl = sourceUrl)
    }

    @Query(
        """
        SELECT *
        FROM epg_channels
        WHERE sourceUrl = :sourceUrl
        """
    )
    suspend fun getChannelsBySource(sourceUrl: String): List<EpgChannelEntity>

    @Query(
        """
        SELECT
            channelId,
            title,
            '' AS description,
            '' AS category,
            startUtcMillis,
            endUtcMillis
        FROM (
            SELECT
                channelId,
                title,
                startUtcMillis,
                endUtcMillis,
                ROW_NUMBER() OVER (
                    PARTITION BY channelId
                    ORDER BY startUtcMillis ASC
                ) AS rowNumber
            FROM epg_programs
            WHERE sourceUrl = :sourceUrl
              AND endUtcMillis > :windowStart
              AND startUtcMillis < :windowEnd
              AND channelId IN (:channelIds)
        )
        WHERE rowNumber <= :perChannelLimit
        ORDER BY channelId ASC, startUtcMillis ASC
        """
    )
    suspend fun getProgramsForChannelsInWindowLite(
        sourceUrl: String,
        channelIds: List<String>,
        windowStart: Long,
        windowEnd: Long,
        perChannelLimit: Int
    ): List<EpgProgramRow>

    @Query(
        """
        SELECT
            channelId,
            title,
            description,
            category,
            startUtcMillis,
            endUtcMillis
        FROM (
            SELECT
                channelId,
                title,
                description,
                category,
                startUtcMillis,
                endUtcMillis,
                ROW_NUMBER() OVER (
                    PARTITION BY channelId
                    ORDER BY startUtcMillis DESC
                ) AS rowNumber
            FROM epg_programs
            WHERE sourceUrl = :sourceUrl
              AND endUtcMillis <= :nowMillis
              AND endUtcMillis > :windowStart
              AND channelId IN (:channelIds)
        )
        WHERE rowNumber <= :perChannelLimit
        ORDER BY channelId ASC, startUtcMillis DESC
        """
    )
    suspend fun getRecentProgramsForChannels(
        sourceUrl: String,
        channelIds: List<String>,
        nowMillis: Long,
        windowStart: Long,
        perChannelLimit: Int
    ): List<EpgProgramRow>

    @Query(
        """
        SELECT
            channelId,
            title,
            description,
            category,
            startUtcMillis,
            endUtcMillis
        FROM (
            SELECT
                channelId,
                title,
                description,
                category,
                startUtcMillis,
                endUtcMillis,
                ROW_NUMBER() OVER (
                    PARTITION BY channelId
                    ORDER BY startUtcMillis ASC
                ) AS rowNumber
            FROM epg_programs
            WHERE sourceUrl = :sourceUrl
              AND endUtcMillis > :windowStart
              AND startUtcMillis < :windowEnd
              AND channelId IN (:channelIds)
        )
        WHERE rowNumber <= :perChannelLimit
        ORDER BY channelId ASC, startUtcMillis ASC
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
     * Deliberately NOT filtered by sourceUrl or channel: the caller drops
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
        WHERE title LIKE '%' || :query || '%'
          AND endUtcMillis > :fromMillis
        ORDER BY startUtcMillis ASC
        LIMIT :limit
        """
    )
    suspend fun searchProgramsByTitle(
        query: String,
        fromMillis: Long,
        limit: Int
    ): List<EpgProgramRow>
}
