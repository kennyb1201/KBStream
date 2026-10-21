package com.kennyb1201.kbstream.data.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_history",
    indices = [
        // Hot path: getResumeForParent / getInProgressForParent /
        // getCompletedForParent all filter by parentId on every Detail
        // screen load and Up Next build. Without the index SQLite scans
        // the whole table.
        androidx.room.Index("parentId")
    ]
)
data class WatchHistoryEntity(
    @PrimaryKey val id: String,
    val parentId: String,
    val type: String,
    val name: String,
    val episodeTitle: String? = null,
    val overview: String? = null,
    val clearLogo: String? = null,
    val backdropUrl: String? = null,
    val totalEpisodesInSeason: Int? = null,
    val poster: String?,
    val streamUrl: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeStreamId: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    /**
     * True when this completed row exists ONLY to record a mark ("Mark as
     * Watched", a season chip, a sync-ed marker) rather than a playback that
     * was actually watched.
     *
     * The marker row is load-bearing - badges, Continue Watching, resume and
     * sync all read it - but it is not a measured watch: its duration is a
     * 1 ms sentinel and its position is reset to zero. The Viewing Stats reads
     * therefore exclude it, or a bulk-marked season reports hundreds of
     * "finished episodes" at 2.5 minutes each and fabricates completion days
     * for the streak.
     *
     * An explicit flag rather than a predicate over the sentinel: the players
     * CAN produce that same shape for a completion whose duration is unknown
     * (`effectiveDuration = position.coerceAtLeast(1L)` with the playhead at
     * 0/1 ms), and a real finish must not be silently uncounted. The three mark
     * writers set this; a later real completion upserts the same row through
     * PlaybackHistoryWriter with the field defaulted back to false, so a marked
     * episode that is played to the end starts counting then - exactly once.
     *
     * Stored with a default so the 13 -> 14 migration can add it in place; the
     * migration backfills the rows already on disk from the sentinel, which is
     * the only place the sentinel is used to classify.
     */
    @ColumnInfo(defaultValue = "0")
    val markedWatched: Boolean = false
)

/**
 * True when a completed row's fields carry the mark path's sentinel shape.
 *
 * Used in exactly two places, both historical: the 13 -> 14 migration (rows
 * already on disk, written before the explicit flag existed) and the sync
 * appliers (a payload pushed by a build that predates the flag). New rows
 * never rely on it - [WatchHistoryEntity.markedWatched] is set by the mark
 * paths themselves - because a playback completion with an unknown duration can
 * produce this same shape, and that one must count.
 */
internal fun looksLikeMarkedWatchedRow(
    isCompleted: Boolean,
    durationMs: Long,
    positionMs: Long
): Boolean = isCompleted && durationMs <= 1L && positionMs == 0L
