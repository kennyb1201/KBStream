package com.kennyb1201.kbstream.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poison detection for the one-time sweep.
 *
 * The only rows the sweep may delete are the same local write duplicated into
 * a second profile scope (the mid-switch race). The dangerous failure mode is
 * the opposite one: deleting a profile's REAL watched state or resume
 * position. These tests pin both directions.
 */
class PoisonDetectorTest {

    private val pidA = "aaaa"
    private val pidB = "bbbb"
    private val pidC = "cccc"
    private val order = listOf(pidA, pidB, pidC)
    private val local = setOf(pidA, pidB, pidC)

    private fun payload(updatedAt: Long, extra: String? = null): JsonObject =
        buildJsonObject {
            put("updatedAt", updatedAt)
            extra?.let { put("name", it) }
        }

    private fun row(key: String, pid: String, payload: JsonObject) =
        PoisonDetector.Row("p:$pid:$key", payload)

    // ── same-write fingerprint ──────────────────────────────────────

    @Test
    fun `identical payloads are one write`() {
        assertTrue(PoisonDetector.sameWrite(payload(1000L), payload(1000L)))
    }

    @Test
    fun `identical timestamps count even when other fields differ`() {
        // Older/newer app versions round-trip different field sets; the
        // timestamp is what a real second playback cannot accidentally share.
        assertTrue(PoisonDetector.sameWrite(payload(1000L, "ep"), payload(1000L)))
    }

    @Test
    fun `different timestamps are different sessions`() {
        assertFalse(PoisonDetector.sameWrite(payload(1000L), payload(2000L)))
        assertFalse(PoisonDetector.sameWrite(payload(1000L, "a"), payload(1000L + 1, "b")))
    }

    @Test
    fun `a missing timestamp cannot match on timestamp alone`() {
        val noStamp = buildJsonObject { put("name", "x") }
        assertFalse(PoisonDetector.sameWrite(noStamp, payload(1000L)))
        // Identical content still counts as one write, timestamp or not.
        assertTrue(PoisonDetector.sameWrite(noStamp, buildJsonObject { put("name", "x") }))
    }

    // ── cross-scope duplicates ──────────────────────────────────────

    @Test
    fun `duplicate under a newer profile is deleted, the owner kept`() {
        val rows = listOf(
            row("movie::tt1", pidA, payload(1000L)),
            row("movie::tt1", pidB, payload(1000L))
        )
        assertEquals(listOf("p:$pidB:movie::tt1"), PoisonDetector.crossScopeDuplicates(rows, order, local))
    }

    @Test
    fun `a real second watch on another profile is never deleted`() {
        val rows = listOf(
            row("movie::tt1", pidA, payload(1000L)),
            row("movie::tt1", pidB, payload(9_000_000L))
        )
        assertTrue(PoisonDetector.crossScopeDuplicates(rows, order, local).isEmpty())
    }

    @Test
    fun `the same item in three scopes keeps only the oldest`() {
        val rows = listOf(
            row("show::tt9", pidC, payload(500L)),
            row("show::tt9", pidA, payload(500L)),
            row("show::tt9", pidB, payload(500L))
        )
        val doomed = PoisonDetector.crossScopeDuplicates(rows, order, local)
        assertEquals(
            setOf("p:$pidB:show::tt9", "p:$pidC:show::tt9"),
            doomed.toSet()
        )
    }

    @Test
    fun `unrelated keys are left alone`() {
        val rows = listOf(
            row("movie::tt1", pidA, payload(1000L)),
            row("movie::tt2", pidB, payload(2000L))
        )
        assertTrue(PoisonDetector.crossScopeDuplicates(rows, order, local).isEmpty())
    }

    @Test
    fun `legacy unscoped rows are never touched`() {
        val rows = listOf(
            PoisonDetector.Row("movie::tt1", payload(1000L)),
            row("movie::tt1", pidB, payload(1000L))
        )
        assertTrue(PoisonDetector.crossScopeDuplicates(rows, order, local).isEmpty())
    }

    @Test
    fun `rows of a profile that only exists on another device are safe`() {
        val remoteOnly = setOf(pidA)
        val rows = listOf(
            row("movie::tt1", pidA, payload(1000L)),
            row("movie::tt1", pidB, payload(1000L))
        )
        assertTrue(PoisonDetector.crossScopeDuplicates(rows, order, remoteOnly).isEmpty())
    }

    // ── local cross-profile history duplicates ──────────────────────

    private fun write(id: String, updatedAt: Long) =
        PoisonDetector.LocalWrite(id, updatedAt)

    @Test
    fun `a local history row copied into a newer profile is deleted`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(write("show::tt1:s1e2", 1000L)),
                pidB to listOf(write("show::tt1:s1e2", 1000L))
            )
        )
        assertEquals(listOf(pidB to "show::tt1:s1e2"), doomed)
    }

    @Test
    fun `a real second watch on another profile is kept`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(write("show::tt1:s1e2", 1000L)),
                pidB to listOf(write("show::tt1:s1e2", 9_000_000L))
            )
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `the same local write in three profiles keeps only the oldest`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(write("movie::tt9", 500L)),
                pidB to listOf(write("movie::tt9", 500L)),
                pidC to listOf(write("movie::tt9", 500L))
            )
        )
        assertEquals(
            setOf(pidB to "movie::tt9", pidC to "movie::tt9"),
            doomed.toSet()
        )
    }

    @Test
    fun `unstamped local rows are never attributed`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(write("movie::tt9", 0L)),
                pidB to listOf(write("movie::tt9", 0L))
            )
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `different titles on different profiles are left alone`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(write("movie::tt1", 1000L), write("show::tt2:s1e1", 1000L)),
                pidB to listOf(write("movie::tt3", 1000L))
            )
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a profile with no local rows contributes nothing`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to emptyList(),
                pidB to listOf(write("movie::tt1", 1000L))
            )
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `only the copied row of a mixed database is deleted`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(write("movie::tt1", 1000L)),
                pidB to listOf(
                    write("movie::tt1", 1000L),          // copied
                    write("show::tt7:s2e4", 4200L)        // genuinely profile B's
                )
            )
        )
        assertEquals(listOf(pidB to "movie::tt1"), doomed)
    }

    // ── local rows witnessed only by another scope's cloud row ──────

    @Test
    fun `a local row copied from an older profile's cloud row is deleted`() {
        val doomed = PoisonDetector.localCopiesOfOlderScopes(
            cloudRows = listOf(row("show::tt1:s1e2", pidA, payload(1000L))),
            perProfile = listOf(
                pidA to emptyList(),
                pidB to listOf(write("show::tt1:s1e2", 1000L))
            ),
            profileOrder = order
        )
        assertEquals(listOf(pidB to "show::tt1:s1e2"), doomed)
    }

    @Test
    fun `a local row matching its own cloud row is not a copy`() {
        val doomed = PoisonDetector.localCopiesOfOlderScopes(
            cloudRows = listOf(row("movie::tt1", pidB, payload(1000L))),
            perProfile = listOf(
                pidA to emptyList(),
                pidB to listOf(write("movie::tt1", 1000L))
            ),
            profileOrder = order
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a local row on the OLDEST profile is never attributed to a newer one`() {
        val doomed = PoisonDetector.localCopiesOfOlderScopes(
            cloudRows = listOf(row("movie::tt1", pidB, payload(1000L))),
            perProfile = listOf(
                pidA to listOf(write("movie::tt1", 1000L)),
                pidB to emptyList()
            ),
            profileOrder = order
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a different session under the older scope is not a copy`() {
        val doomed = PoisonDetector.localCopiesOfOlderScopes(
            cloudRows = listOf(row("movie::tt1", pidA, payload(1000L))),
            perProfile = listOf(
                pidA to emptyList(),
                pidB to listOf(write("movie::tt1", 5_000_000L))
            ),
            profileOrder = order
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `an unstamped cloud row is never a witness`() {
        val noStamp = buildJsonObject { put("name", "x") }
        val doomed = PoisonDetector.localCopiesOfOlderScopes(
            cloudRows = listOf(row("movie::tt1", pidA, noStamp)),
            perProfile = listOf(
                pidA to emptyList(),
                pidB to listOf(write("movie::tt1", 1000L))
            ),
            profileOrder = order
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a legacy unscoped cloud row never witnesses anything`() {
        val doomed = PoisonDetector.localCopiesOfOlderScopes(
            cloudRows = listOf(PoisonDetector.Row("movie::tt1", payload(1000L))),
            perProfile = listOf(
                pidA to emptyList(),
                pidB to listOf(write("movie::tt1", 1000L))
            ),
            profileOrder = order
        )
        assertTrue(doomed.isEmpty())
    }

    // ── watched-override sets ───────────────────────────────────────

    @Test
    fun `an override set copied from an older profile is cleared`() {
        val sets = listOf(
            pidA to setOf("movie::tt1", "show::tt2"),
            pidB to setOf("movie::tt1", "show::tt2")
        )
        assertEquals(listOf(pidB), PoisonDetector.duplicateOverrideOwners(sets))
    }

    @Test
    fun `genuinely different override sets are kept`() {
        val sets = listOf(
            pidA to setOf("movie::tt1"),
            pidB to setOf("movie::tt2")
        )
        assertTrue(PoisonDetector.duplicateOverrideOwners(sets).isEmpty())
    }

    @Test
    fun `empty sets are never treated as copies of each other`() {
        val sets = listOf(pidA to emptySet<String>(), pidB to emptySet<String>())
        assertTrue(PoisonDetector.duplicateOverrideOwners(sets).isEmpty())
    }

    @Test
    fun `a superset is not a copy`() {
        val sets = listOf(
            pidA to setOf("movie::tt1"),
            pidB to setOf("movie::tt1", "movie::tt2")
        )
        assertTrue(PoisonDetector.duplicateOverrideOwners(sets).isEmpty())
    }

    @Test
    fun `every copy of an earlier set is cleared, the first one kept`() {
        val same = setOf("movie::tt1")
        val sets = listOf(pidA to same, pidB to same, pidC to same)
        assertEquals(listOf(pidB, pidC), PoisonDetector.duplicateOverrideOwners(sets))
    }

    // ── playback fingerprint (v5) ───────────────────────────────────

    private val episodeId = "show::tt1:s1e2"
    private val sourceUrl = "https://cdn.example/e1.mkv"

    /** A local history row that records one specific playback. */
    private fun play(
        updatedAt: Long,
        position: Long = 60_000L,
        duration: Long = 3_600_000L,
        source: String? = sourceUrl,
        id: String = episodeId
    ) = PoisonDetector.LocalWrite(
        id = id,
        updatedAt = updatedAt,
        parentId = "show::tt1",
        season = 1,
        episode = 2,
        positionMs = position,
        durationMs = duration,
        isCompleted = false,
        streamUrl = source
    )

    private fun playbackPayload(
        updatedAt: Long,
        position: Long = 60_000L,
        duration: Long = 3_600_000L,
        source: String = sourceUrl
    ): JsonObject =
        buildJsonObject {
            put("updatedAt", updatedAt)
            put("parentId", "show::tt1")
            put("season", 1)
            put("episode", 2)
            put("positionMs", position)
            put("durationMs", duration)
            put("isCompleted", false)
            put("streamUrl", source)
        }

    @Test
    fun `a copy that was re-stamped on the way in is still deleted`() {
        // The case that survived every earlier version: the copy carries the
        // same playback but not the original write stamp.
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(play(1000L)),
                pidB to listOf(play(7777L))
            )
        )
        assertEquals(listOf(pidB to episodeId), doomed)
    }

    @Test
    fun `the same episode paused elsewhere from another source is kept`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(play(1000L)),
                pidB to listOf(play(7777L, position = 60_000L, source = "https://other/e1.mkv"))
            )
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a different resume position is a different playback`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(play(1000L)),
                pidB to listOf(play(7777L, position = 90_000L))
            )
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `rows too old to carry a source still match on the write stamp`() {
        val doomed = PoisonDetector.localHistoryDuplicates(
            listOf(
                pidA to listOf(write("movie::tt9", 500L)),
                pidB to listOf(write("movie::tt9", 500L))
            )
        )
        assertEquals(listOf(pidB to "movie::tt9"), doomed)
    }

    @Test
    fun `cloud history rows pair on playback, not on the stamp`() {
        val rows = listOf(
            row(episodeId, pidA, playbackPayload(1000L)),
            row(episodeId, pidB, playbackPayload(7777L))
        )
        val doomed = PoisonDetector.crossScopeDuplicates(
            rows,
            order,
            local,
            PoisonDetector::sameHistoryWrite
        )
        assertEquals(listOf("p:$pidB:$episodeId"), doomed)
    }

    @Test
    fun `cloud history rows from two different sources are both kept`() {
        val rows = listOf(
            row(episodeId, pidA, playbackPayload(1000L)),
            row(
                episodeId,
                pidB,
                playbackPayload(7777L, source = "https://other/e1.mkv")
            )
        )
        val doomed = PoisonDetector.crossScopeDuplicates(
            rows,
            order,
            local,
            PoisonDetector::sameHistoryWrite
        )
        assertTrue(doomed.isEmpty())
    }

    // ── cloud rows witnessed only by an older profile's LOCAL row ───

    @Test
    fun `a cloud row copied from an older profile's local row is deleted`() {
        // The mirror of localCopiesOfOlderScopes: here the owner's copy is
        // local, so nothing in the cloud pairs with it and it would re-download
        // on every pull.
        val doomed = PoisonDetector.cloudCopiesOfOlderLocalWitnesses(
            cloudRows = listOf(row(episodeId, pidB, playbackPayload(7777L))),
            perProfile = listOf(
                pidA to listOf(play(1000L)),
                pidB to emptyList()
            ),
            profileOrder = order
        )
        assertEquals(listOf(pidB to episodeId), doomed)
    }

    @Test
    fun `a cloud row witnessed by its OWN profile's local row is not a copy`() {
        val doomed = PoisonDetector.cloudCopiesOfOlderLocalWitnesses(
            cloudRows = listOf(row(episodeId, pidB, playbackPayload(7777L))),
            perProfile = listOf(
                pidA to emptyList(),
                pidB to listOf(play(7777L))
            ),
            profileOrder = order
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a cloud row only a NEWER profile witnesses is not a copy`() {
        val doomed = PoisonDetector.cloudCopiesOfOlderLocalWitnesses(
            cloudRows = listOf(row(episodeId, pidA, playbackPayload(1000L))),
            perProfile = listOf(
                pidA to emptyList(),
                pidB to listOf(play(1000L))
            ),
            profileOrder = order
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a cloud row whose playback differs from the witness is kept`() {
        val doomed = PoisonDetector.cloudCopiesOfOlderLocalWitnesses(
            cloudRows = listOf(row(episodeId, pidB, playbackPayload(7777L))),
            perProfile = listOf(
                pidA to listOf(play(1000L, position = 120_000L)),
                pidB to emptyList()
            ),
            profileOrder = order
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a legacy unscoped cloud row is never attributed`() {
        val doomed = PoisonDetector.cloudCopiesOfOlderLocalWitnesses(
            cloudRows = listOf(PoisonDetector.Row(episodeId, playbackPayload(7777L))),
            perProfile = listOf(
                pidA to listOf(play(1000L)),
                pidB to emptyList()
            ),
            profileOrder = order
        )
        assertTrue(doomed.isEmpty())
    }

    // ── eye-badge markers orphaned by a removed history row ─────────

    private fun marker(
        pid: String,
        imdb: String,
        watched: Boolean = false,
        partial: Boolean = true,
        type: String = "series"
    ) = PoisonDetector.Row(
        "p:$pid:$type::$imdb",
        buildJsonObject {
            put("imdbId", imdb)
            put("mediaType", type)
            put("isWatched", watched)
            put("isPartiallyWatched", partial)
            put("updatedAt", 1000L)
        }
    )

    @Test
    fun `an eye-badge marker left by a removed copy is deleted`() {
        val doomed = PoisonDetector.orphanedPartialMarkers(
            watchedRows = listOf(marker(pidB, "tt1")),
            orphanedParents = mapOf(pidB to setOf("show::tt1")),
            localPids = local
        )
        assertEquals(listOf("p:$pidB:series::tt1"), doomed)
    }

    @Test
    fun `a bare parent id matches too`() {
        val doomed = PoisonDetector.orphanedPartialMarkers(
            watchedRows = listOf(marker(pidB, "tt1")),
            orphanedParents = mapOf(pidB to setOf("tt1")),
            localPids = local
        )
        assertEquals(listOf("p:$pidB:series::tt1"), doomed)
    }

    @Test
    fun `a checkmark is never removed`() {
        val doomed = PoisonDetector.orphanedPartialMarkers(
            watchedRows = listOf(marker(pidB, "tt1", watched = true, partial = false)),
            orphanedParents = mapOf(pidB to setOf("tt1")),
            localPids = local
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a marker under another profile is never touched`() {
        val doomed = PoisonDetector.orphanedPartialMarkers(
            watchedRows = listOf(marker(pidA, "tt1")),
            orphanedParents = mapOf(pidB to setOf("tt1")),
            localPids = local
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a marker with no orphaned parent is kept`() {
        val doomed = PoisonDetector.orphanedPartialMarkers(
            watchedRows = listOf(marker(pidB, "tt9")),
            orphanedParents = mapOf(pidB to setOf("tt1")),
            localPids = local
        )
        assertTrue(doomed.isEmpty())
    }

    @Test
    fun `a remote-only profile is never cleaned`() {
        val doomed = PoisonDetector.orphanedPartialMarkers(
            watchedRows = listOf(marker("dddd", "tt1")),
            orphanedParents = mapOf("dddd" to setOf("tt1")),
            localPids = local
        )
        assertTrue(doomed.isEmpty())
    }
}
