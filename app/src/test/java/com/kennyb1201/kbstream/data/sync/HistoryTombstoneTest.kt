package com.kennyb1201.kbstream.data.sync

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A watch-history delete has to STICK.
 *
 * Before tombstones, a local delete was the ABSENCE of a row: the cloud copy
 * won every merge (a deleted local row has no timestamp), a sibling that still
 * held the row re-uploaded it on its next full push, and realtime dropped the
 * DELETE event. The row came back. These tests pin the pure rules the pull,
 * realtime and push paths all share, so the fix is decided here rather than by
 * an integration that would need a live Supabase and two devices.
 */
class HistoryTombstoneTest {

    private fun row(
        id: String = "tt123:1:1",
        season: Int? = 1,
        episode: Int? = 1,
        positionMs: Long = 0L,
        updatedAt: Long = 1_000L
    ) = WatchHistoryEntity(
        id = id,
        parentId = "tt123",
        type = "series",
        name = "Show",
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        positionMs = positionMs,
        durationMs = 0L,
        updatedAt = updatedAt,
        isCompleted = true
    )

    @Test
    fun `a tombstone is recognised and carries the delete stamp as its write stamp`() {
        val tombstone = HistoryTombstoneRules.tombstone("tt123:1:1", 5_000L)

        assertTrue(HistoryTombstoneRules.isTombstone(tombstone))
        assertEquals(5_000L, HistoryTombstoneRules.deletedAt(tombstone))
        // The same instant as `updatedAt`, so an OLD merge rule already reads
        // the delete as the newest write.
        assertEquals(
            JsonPrimitive(5_000L).toString(),
            tombstone[HistoryRowRules.UPDATED_AT_FIELD].toString()
        )
    }

    @Test
    fun `a live row is not a tombstone`() {
        val live = HistoryRowRules.payload(row())

        assertFalse(HistoryTombstoneRules.isTombstone(live))
        assertEquals(null, HistoryTombstoneRules.deletedAt(live))
    }

    @Test
    fun `a tombstone removes an older local row but spares a newer one`() {
        // Deleted at t=5000; the local row was last written before that.
        assertTrue(HistoryTombstoneRules.tombstoneWins(5_000L, 4_000L))
        // The title was played AGAIN after the delete: the local row is newer
        // and must survive.
        assertFalse(HistoryTombstoneRules.tombstoneWins(5_000L, 6_000L))
    }

    @Test
    fun `a local row republishes only when it is newer than the cloud tombstone`() {
        assertFalse(
            "re-uploading the deleted row is exactly the resurrection being fixed",
            HistoryTombstoneRules.shouldPublishOverTombstone(
                localUpdatedAt = 4_000L,
                cloudDeletedAt = 5_000L
            )
        )
        assertTrue(
            "playing the title again after the delete is a newer write",
            HistoryTombstoneRules.shouldPublishOverTombstone(
                localUpdatedAt = 6_000L,
                cloudDeletedAt = 5_000L
            )
        )
    }

    @Test
    fun `a tombstone outranks a live row at the same millisecond`() {
        val live = HistoryRowRules.payload(row(updatedAt = 5_000L))
        val tombstone = HistoryTombstoneRules.tombstone("tt123:1:1", 5_000L)

        assertTrue(
            "an exact-ms collision must never resurrect a deleted title",
            HistoryTombstoneRules.mergeToken(tombstone) >
                HistoryTombstoneRules.mergeToken(live)
        )
        assertTrue(
            historyRemoteWins(
                remoteUpdated = 5_000L,
                remoteToken = HistoryTombstoneRules.mergeToken(tombstone),
                localUpdated = 5_000L,
                localToken = HistoryTombstoneRules.mergeToken(live)
            )
        )
    }

    @Test
    fun `a newer remote row still wins over a local row`() {
        val remote = HistoryRowRules.payload(row(updatedAt = 9_000L))
        val local = HistoryRowRules.payload(row(updatedAt = 1_000L))

        assertTrue(
            historyRemoteWins(
                remoteUpdated = 9_000L,
                remoteToken = HistoryTombstoneRules.mergeToken(remote),
                localUpdated = 1_000L,
                localToken = HistoryTombstoneRules.mergeToken(local)
            )
        )
        assertFalse(
            historyRemoteWins(
                remoteUpdated = 1_000L,
                remoteToken = HistoryTombstoneRules.mergeToken(local),
                localUpdated = 9_000L,
                localToken = HistoryTombstoneRules.mergeToken(remote)
            )
        )
    }

    @Test
    fun `equal stamps resolve to the SAME winner on both devices`() {
        // Two devices wrote the same row in the same millisecond with different
        // content. Each device sees its own copy as local and the other's as
        // remote; a deterministic tie-break must make exactly ONE of them adopt,
        // so the two converge on one row instead of each keeping its own.
        val writtenByA = HistoryRowRules.payload(row(positionMs = 111L, updatedAt = 7_000L))
        val writtenByB = HistoryRowRules.payload(row(positionMs = 222L, updatedAt = 7_000L))
        val tokenA = HistoryTombstoneRules.mergeToken(writtenByA)
        val tokenB = HistoryTombstoneRules.mergeToken(writtenByB)

        // Device A holds `writtenByA`, sees `writtenByB` from the cloud.
        val aAdoptsB = historyRemoteWins(7_000L, tokenB, 7_000L, tokenA)
        // Device B holds `writtenByB`, sees `writtenByA` from the cloud.
        val bAdoptsA = historyRemoteWins(7_000L, tokenA, 7_000L, tokenB)

        assertTrue(
            "exactly one side must adopt, or the two devices never converge",
            aAdoptsB != bAdoptsA
        )
    }

    @Test
    fun `a row with no tie token keeps the local copy on a tie`() {
        // A row written by a build that predates the tie-break: the old rule
        // still applies, so nothing regresses.
        assertFalse(
            historyRemoteWins(
                remoteUpdated = 7_000L,
                remoteToken = "{\"id\":\"tt123:1:1\"}",
                localUpdated = 7_000L,
                localToken = null
            )
        )
    }

    @Test
    fun `the row payload builder is the wire format the merge compares`() {
        val payload = HistoryRowRules.payload(row(updatedAt = 3_000L))

        assertEquals("tt123:1:1", payload["id"]?.let { it.toString().trim('"') })
        assertEquals("tt123", payload["parentId"]?.let { it.toString().trim('"') })
        assertEquals(JsonPrimitive(3_000L).toString(), payload["updatedAt"].toString())
    }
}
