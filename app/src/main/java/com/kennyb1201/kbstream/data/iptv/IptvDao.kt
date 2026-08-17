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

    @Query("DELETE FROM epg_channels WHERE sourceUrl = :sourceUrl")
    suspend fun deleteChannelsBySource(sourceUrl: String)

    @Query("DELETE FROM epg_programs WHERE sourceUrl = :sourceUrl")
    suspend fun deleteProgramsBySource(sourceUrl: String)

    /**
     * Clears only one XMLTV guide source before a streamed replacement import.
     */
    @Transaction
    suspend fun clearGuideBySource(sourceUrl: String) {
        deleteProgramsBySource(sourceUrl)
        deleteChannelsBySource(sourceUrl)
    }

    /**
     * Use only for small, complete guides. Large XMLTV sources should be imported
     * in batches with clearGuideBySource(), insertChannels(), and insertPrograms().
     */
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
}²
