package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM epg_channels WHERE sourceUrl = :sourceUrl")
    suspend fun getChannelsBySource(sourceUrl: String): List<EpgChannelEntity>

    @Query(
        """
        SELECT channelId, title, description, category, startUtcMillis, endUtcMillis
        FROM epg_programs
        WHERE endUtcMillis > :windowStart
          AND startUtcMillis < :windowEnd
          AND channelId IN (:channelIds)
        ORDER BY startUtcMillis ASC
        LIMIT :limit
        """
    )
    fun observeProgramsForChannelsInWindow(
        channelIds: List<String>,
        windowStart: Long,
        windowEnd: Long,
        limit: Int
    ): Flow<List<EpgProgramRow>>
}
