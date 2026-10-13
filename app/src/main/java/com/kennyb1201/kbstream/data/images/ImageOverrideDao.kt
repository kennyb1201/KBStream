package com.kennyb1201.kbstream.data.images

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The `image_overrides` table (see [ImageOverrideEntity]).
 *
 * One row per (title, kind, surface), which is why [set] replaces: choosing a
 * second backdrop for the same surface is the viewer changing their mind, not
 * adding a second choice.
 */
@Dao
interface ImageOverrideDao {

    /** The choice for one surface, or null when the surface still shows TMDB's. */
    @Query(
        """
        SELECT * FROM image_overrides
        WHERE tmdbId = :tmdbId AND type = :type AND screen = :screen
        """
    )
    suspend fun get(tmdbId: Int, type: String, screen: String): ImageOverrideEntity?

    /** Every choice, for the in-memory mirror the UI reads (see [ImageOverrides]). */
    @Query("SELECT * FROM image_overrides")
    suspend fun getAll(): List<ImageOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(override: ImageOverrideEntity)

    @Query(
        """
        DELETE FROM image_overrides
        WHERE tmdbId = :tmdbId AND type = :type AND screen = :screen
        """
    )
    suspend fun clear(tmdbId: Int, type: String, screen: String)
}
