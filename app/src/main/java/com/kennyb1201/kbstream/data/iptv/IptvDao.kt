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

    @Query("DELETE FROM epg_channels WHERE sourceUrl = :sourceUrl")
    suspend fun deleteChannelsBySource(sourceUrl: String)

    @Query("DELETE FROM epg_programs WHERE sourceUrl = :sourceUrl")
    suspend fun deleteProgramsBySource(sourceUrl: String)

    @Query("DELETE FROM cached_playlist_channels WHERE playlistUrl = :playlistUrl")
    suspend fun deleteCachedPlaylistChannels(playlistUrl: String)

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
            providerChannelId
        FROM cached_playlist_channels
        WHERE playlistUrl = :playlistUrl
        ORDER BY position ASC
        """
    )
    suspend fun getCachedPlaylistChannels(
        playlistUrl: String
    ): List<CachedPlaylistChannelRow>

    @Transaction
    suspend fun replaceCachedPlaylistChannels(
        playlistUrl: String,
        channels: List<CachedPlaylistChannelEntity>
    ) {
        deleteCachedPlaylistChannels(playlistUrl)

        if (channels.isNotEmpty()) {
            insertCachedPlaylistChannels(channels)
        }
    }

    @Transaction
    suspend fun clearGuideBySource(sourceUrl: String) {
        deleteProgramsBySource(sourceUrl)
        deleteChannelsBySource(sourceUrl)
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
        """
        SELECT *
        FROM epg_channels
        WHERE sourceUrl = :sourceUrl
        """
    )
    suspend fun getChannelsBySource(sourceUrl: String): List<EpgChannelEntity>

    @Query(
        """
        SELECT channelId, title, description, category, startUtcMillis, endUtcMillis
        FROM epg_programs
        WHERE sourceUrl = :sourceUrl
          AND endUtcMillis > :windowStart
          AND startUtcMillis < :windowEnd
          AND channelId IN (:channelIds)
        ORDER BY startUtcMillis ASC
        LIMIT :limit
        """
    )
    suspend fun getProgramsForChannelsInWindow(
        sourceUrl: String,
        channelIds: List<String>,
        windowStart: Long,
        windowEnd: Long,
        limit: Int
    ): List<EpgProgramRow>
}
