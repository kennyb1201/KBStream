package com.kennyb1201.kbstream.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-shot history root migration's arithmetic: which id a stored row ends
 * up filed under, and - the part that decides whether the migration is safe to
 * run at all - which ids it deletes, which it tombstones, and which it leaves
 * alone.
 *
 * The field case: a series catalog hands out `tt3121722` for a show whose TMDB
 * record says `tt4873036`, so every row written before the root was verified
 * carries the catalog's id. The rail already re-roots such an id as it reads it;
 * this rewrites the rows themselves, so the id the account's other devices
 * receive is the one every surface names the show by.
 */
class HistoryRootMigrationRulesTest {

    private val verified = "tt4873036"
    private val stale = "tt3121722"

    private fun row(
        id: String,
        parentId: String = id,
        type: String = "series",
        season: Int? = 7,
        episode: Int? = 3,
        episodeStreamId: String? = null,
        positionMs: Long = 60_000L,
        updatedAt: Long = 1L,
        isCompleted: Boolean = false
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = type,
        name = "PAW Patrol",
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        episodeStreamId = episodeStreamId,
        positionMs = positionMs,
        durationMs = 1_200_000L,
        updatedAt = updatedAt,
        isCompleted = isCompleted
    )

    // ── the root a row asks about ────────────────────────────────────────────

    @Test
    fun `an episode row asks about the root its own id names`() {
        assertEquals(stale, rootOfStoredRow(row("$stale:7:3", parentId = stale)))
        // A movie (or any show-level row) is filed under its parent id, which IS
        // its root.
        assertEquals("tt0111161", rootOfStoredRow(row("tt0111161", type = "movie")))
        assertNull(rootOfStoredRow(row("", parentId = "")))
    }

    // ── the row's canonical form ─────────────────────────────────────────────

    @Test
    fun `a stale episode row is re-rooted, suffix and all`() {
        val migrated = canonicalHistoryRow(row("$stale:7:3", parentId = stale), verified)
        assertEquals("$verified:7:3", migrated.id)
        assertEquals(verified, migrated.parentId)
        // Nothing else about the row moved: this is not a write by the viewer.
        assertEquals(60_000L, migrated.positionMs)
        assertEquals(1L, migrated.updatedAt)
        assertEquals(7, migrated.season)
        assertEquals(3, migrated.episode)
    }

    @Test
    fun `the file suffix is the file that was played, never re-derived`() {
        // File numbering the add-ons answered, on a show whose files hold two
        // TMDB segments each: the row keeps file 6 even though its episode is 3.
        val migrated = canonicalHistoryRow(row("$stale:5:6", parentId = stale), verified)
        assertEquals("$verified:5:6", migrated.id)
    }

    @Test
    fun `the row's own episode stream id is re-rooted with the id`() {
        val migrated = canonicalHistoryRow(
            row("$stale:7:3", parentId = stale, episodeStreamId = "$stale:7:3"),
            verified
        )
        assertEquals("$verified:7:3", migrated.episodeStreamId)
    }

    @Test
    fun `a route-flavored parent is repaired even when the id is already canonical`() {
        // A kids-rail play: the id was built on TMDB's answer, the parent id is
        // the flavor the route was opened with.
        val migrated = canonicalHistoryRow(row("$verified:7:3", parentId = "tmdb:57532"), verified)
        assertEquals("$verified:7:3", migrated.id)
        assertEquals(verified, migrated.parentId)
    }

    @Test
    fun `a film filed as tmdb n becomes its imdb id`() {
        val migrated = canonicalHistoryRow(
            row("tmdb:550", type = "movie", season = null, episode = null),
            "tt0137523"
        )
        assertEquals("tt0137523", migrated.id)
        assertEquals("tt0137523", migrated.parentId)
    }

    @Test
    fun `a row that already names the verified root is untouched`() {
        val already = row("$verified:7:3", parentId = verified)
        assertTrue(
            "an id under the verified root is the answer, not work",
            canonicalHistoryRow(already, verified) === already
        )
        val film = row("tt0111161", type = "movie", season = null, episode = null)
        assertTrue(
            "a film under its own imdb id likewise",
            canonicalHistoryRow(film, "tt0111161") === film
        )
    }

    @Test
    fun `a root nothing can verify leaves the row exactly as it is`() {
        val stored = row("$stale:7:3", parentId = stale)
        // No TMDB key, an offline device, a title TMDB has no record for: the
        // stored id is the only id there is. See
        // TmdbRepository.streamRootIdFor, which answers the route's own id in
        // exactly these cases.
        assertTrue(canonicalHistoryRow(stored, "") === stored)
        assertTrue(canonicalHistoryRow(stored, "   ") === stored)
        assertTrue(canonicalHistoryRow(stored, stale) === stored)
    }

    // ── the plan: what leaves, what is written, what is tombstoned ───────────

    /** The resolver a device with a working TMDB lookup answers with here: every
     *  root these fixtures use resolves to the show's verified `tt...`, and a
     *  kitsu-flavored one resolves to nothing at all. */
    private fun plan(vararg rows: WatchHistoryEntity) =
        planHistoryRootMigration(rows.toList()) { row ->
            when (val root = rootOfStoredRow(row)) {
                stale, "tmdb:57532" -> verified
                // A root TMDB verifies AND agrees with: the answer is the root
                // itself, which is what makes the row a no-op.
                else -> root?.takeIf { it.startsWith("tt") }
            }
        }

    @Test
    fun `a stale row is rewritten, and its old id leaves and is tombstoned`() {
        val plan = plan(row("$stale:7:3", parentId = stale))
        assertEquals(listOf("$verified:7:3"), plan.writes.map { it.id })
        assertEquals(verified, plan.writes.single().parentId)
        assertEquals(listOf("$stale:7:3"), plan.deletedIds)
        // Without the tombstone the next pull hands the stale row straight back
        // (see SupabaseSync.deleteHistoryRows).
        assertEquals(listOf("$stale:7:3"), plan.tombstonedIds)
        assertEquals(0, plan.unmigrated)
    }

    @Test
    fun `an in-place repair writes the row without deleting or tombstoning it`() {
        // Only the parent id is wrong - the id is already TMDB's - so the row is
        // rewritten where it stands: no local delete, and no tombstone, because
        // the cloud row under that id is the one this device is updating.
        val plan = plan(row("$verified:7:3", parentId = "tmdb:57532"))
        assertEquals(listOf("$verified:7:3"), plan.writes.map { it.id })
        assertEquals(verified, plan.writes.single().parentId)
        assertTrue(plan.deletedIds.isEmpty())
        assertTrue(plan.tombstonedIds.isEmpty())
    }

    @Test
    fun `the newer row wins a collision, and the loser's id is not tombstoned`() {
        // The same episode filed twice: the stale row (older) and the canonical
        // row a later session wrote. One episode, one row.
        val plan = plan(
            row("$stale:7:3", parentId = stale, updatedAt = 10L),
            row("$verified:7:3", parentId = verified, updatedAt = 20L)
        )
        assertTrue("the canonical row already holds the answer", plan.writes.isEmpty())
        assertEquals(listOf("$stale:7:3"), plan.deletedIds)
        assertEquals(listOf("$stale:7:3"), plan.tombstonedIds)
    }

    @Test
    fun `a newer stale row replaces the older canonical twin`() {
        val plan = plan(
            row("$verified:7:3", parentId = verified, updatedAt = 10L, positionMs = 10L),
            row("$stale:7:3", parentId = stale, updatedAt = 20L, positionMs = 90_000L)
        )
        assertEquals(listOf("$verified:7:3"), plan.writes.map { it.id })
        assertEquals(90_000L, plan.writes.single().positionMs)
        assertEquals(listOf("$stale:7:3", "$verified:7:3"), plan.deletedIds.sorted())
        // The id that is being written must NOT be tombstoned - the write is
        // what replaces that cloud row - while the abandoned stale id must be.
        assertEquals(listOf("$stale:7:3"), plan.tombstonedIds)
    }

    @Test
    fun `a row whose root cannot be resolved is left alone and counted`() {
        val plan = planHistoryRootMigration(listOf(row("kitsu:1:3", parentId = "kitsu:1"))) {
            null
        }
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unmigrated)
    }

    @Test
    fun `a history already filed under the verified roots needs no work`() {
        val plan = plan(
            row("$verified:7:3", parentId = verified),
            row("tt0111161", type = "movie", season = null, episode = null)
        )
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.unmigrated)
    }

    @Test
    fun `one unresolved row does not stop the others`() {
        val plan = planHistoryRootMigration(
            listOf(
                row("$stale:7:3", parentId = stale),
                row("kitsu:9:1", parentId = "kitsu:9")
            )
        ) { row -> if (rootOfStoredRow(row) == stale) verified else null }
        assertEquals(listOf("$verified:7:3"), plan.writes.map { it.id })
        assertEquals(1, plan.unmigrated)
    }
}
