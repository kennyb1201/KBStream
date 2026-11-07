package com.kennyb1201.kbstream.data.mdblist

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every mark, unmark and completed stop used to delete the cached watched
 * snapshot AND its disk row, so the next read re-downloaded the whole paginated
 * history (`/sync/watched?limit=1000`, one request per page) to learn the ids
 * the write had just sent. That was the biggest spender of a 1,000/day key
 * three devices share.
 *
 * [MdbListSnapshotWrite] is that knowledge in set form: the write's own ids, the
 * exact set edits they make, and nothing else. The sets are driven directly
 * here; the client's wiring (which write applies which delta, and which writes
 * still deserve the blanket invalidation) is pinned on the source.
 */
class MdbListSnapshotWriteTest {

    private val base = MdbListWatchedSnapshot(
        movieKeys = setOf("tt100", "tmdb:7"),
        startedShowKeys = setOf("tt200", "tmdb:8"),
        episodeKeys = setOf("tt200:1:1", "tmdb:8:1:1", "tt200:2:5")
    )

    // ── spec T2: a mark adds exactly its keys ──────────────────────────

    @Test
    fun `a movie mark adds its own key and touches nothing else`() {
        val after = MdbListSnapshotWrite(addMovies = setOf("tt101")).applyTo(base)
        assertEquals(setOf("tt100", "tmdb:7", "tt101"), after.movieKeys)
        assertEquals(base.startedShowKeys, after.startedShowKeys)
        assertEquals(base.episodeKeys, after.episodeKeys)
    }

    @Test
    fun `an episode mark adds the show key and both forms of its episode key`() {
        val write = MdbListSnapshotWrite(
            addShows = mdbListIdKeys("tt200", 8),
            addEpisodes = mdbListEpisodeKeys("tt200", 8, 3, 4)
        )
        val after = write.applyTo(base)
        assertEquals(
            setOf("tt200:3:4", "tmdb:8:3:4"),
            after.episodeKeys - base.episodeKeys
        )
        assertTrue(after.startedShowKeys.containsAll(setOf("tt200", "tmdb:8")))
    }

    @Test
    fun `an episode mark of a new show starts the show`() {
        val after = MdbListSnapshotWrite(
            addShows = mdbListIdKeys("tt300", null),
            addEpisodes = mdbListEpisodeKeys("tt300", null, 1, 1)
        ).applyTo(base)
        assertEquals(setOf("tt300:1:1"), after.episodeKeys - base.episodeKeys)
        assertEquals(setOf("tt300"), after.startedShowKeys - base.startedShowKeys)
    }

    @Test
    fun `a whole-show mark adds only the show key`() {
        // The ids-only body is expanded server-side, so the local snapshot
        // cannot enumerate the episodes it created.
        val after = MdbListSnapshotWrite(
            addShows = mdbListIdKeys("tt400", 40)
        ).applyTo(base)
        assertEquals(base.episodeKeys, after.episodeKeys)
        assertTrue(after.startedShowKeys.containsAll(setOf("tt400", "tmdb:40")))
    }

    // ── spec T2: an unmark removes exactly its keys ────────────────────

    @Test
    fun `a movie unmark removes its own key and leaves the rest`() {
        val after = MdbListSnapshotWrite(removeMovies = setOf("tmdb:7")).applyTo(base)
        assertEquals(setOf("tt100"), after.movieKeys)
        assertEquals(base.episodeKeys, after.episodeKeys)
    }

    @Test
    fun `an episode unmark removes that episode and keeps the show started`() {
        val after = MdbListSnapshotWrite(
            removeEpisodes = mdbListEpisodeKeys("tt200", 8, 2, 5)
        ).applyTo(base)
        assertEquals(setOf("tt200:1:1", "tmdb:8:1:1"), after.episodeKeys)
        assertEquals(base.startedShowKeys, after.startedShowKeys)
    }

    @Test
    fun `a show unmark takes both of its key forms and every episode under them`() {
        // `/sync/watched/remove` with ids-only clears the whole show
        // server-side; a local snapshot that kept the episode keys would keep
        // painting eye badges for a show no longer on record.
        val after = MdbListSnapshotWrite(
            removeShows = mdbListIdKeys("tt200", 8)
        ).applyTo(base.copy(episodeKeys = base.episodeKeys + "tt900:1:1"))
        assertEquals(setOf("tt900:1:1"), after.episodeKeys)
        assertTrue(after.startedShowKeys.isEmpty())
    }

    @Test
    fun `a show unmark removes only the episodes under ITS keys`() {
        val after = MdbListSnapshotWrite(removeShows = setOf("tt200")).applyTo(base)
        assertEquals(setOf("tmdb:8:1:1"), after.episodeKeys)
        assertTrue("the other id form of the same show is a separate key", after.startedShowKeys.contains("tmdb:8"))
    }

    // ── the ids that become keys ───────────────────────────────────────

    @Test
    fun `only ids the history would carry become keys`() {
        assertEquals(emptySet<String>(), mdbListIdKeys(null, null))
        assertEquals(emptySet<String>(), mdbListIdKeys("", 0))
        assertEquals(
            "a non-tt imdb id is not a snapshot key (the walk's own rule)",
            setOf("tmdb:5"),
            mdbListIdKeys("abc123", 5)
        )
        assertEquals(setOf("tt5"), mdbListIdKeys("tt5", -1))
    }

    @Test
    fun `episode keys need a real season and episode`() {
        assertEquals(emptySet<String>(), mdbListEpisodeKeys("tt1", 2, null, 3))
        assertEquals(emptySet<String>(), mdbListEpisodeKeys("tt1", 2, -1, 3))
        assertEquals(emptySet<String>(), mdbListEpisodeKeys("tt1", 2, 1, null))
        assertEquals(
            setOf("tt1:1:3", "tmdb:2:1:3"),
            mdbListEpisodeKeys("tt1", 2, 1, 3)
        )
        assertEquals(
            setOf("tt1:1:3", "tt1:1:4"),
            mdbListEpisodeKeys("tt1", null, 1, listOf(3, 4))
        )
    }

    @Test
    fun `a write that moves nothing is empty`() {
        assertTrue(MdbListSnapshotWrite().isEmpty)
        assertFalse(MdbListSnapshotWrite(addMovies = setOf("tt1")).isEmpty)
        assertFalse(MdbListSnapshotWrite(removeShows = setOf("tt1")).isEmpty)
    }

    @Test
    fun `applying a delta never mutates the snapshot it read`() {
        MdbListSnapshotWrite(addMovies = setOf("tt101")).applyTo(base)
        assertEquals(setOf("tt100", "tmdb:7"), base.movieKeys)
    }

    // ── spec T2: only a COMPLETED stop is a history write ──────────────

    @Test
    fun `a stop counts as a completion only at the API's own threshold`() {
        assertFalse(mdbListStopCompletes(0.0))
        assertFalse(mdbListStopCompletes(79.9))
        assertTrue(mdbListStopCompletes(80.0))
        assertTrue(mdbListStopCompletes(100.0))
        assertFalse(mdbListStopCompletes(Double.NaN))
    }

    // ── the client's wiring, pinned on the source ──────────────────────

    @Test
    fun `no history write deletes the snapshot any more`() {
        val client = source(CLIENT)
        assertFalse(
            "a marked write must move its own keys, not drop the blob",
            client.contains("if (ok) invalidateWatchedSnapshot()")
        )
        val postSync = client.substringAfter("private suspend fun postSync(")
            .substringBefore("POST /sync/watched — record several episodes")
        assertFalse(
            "postSync's success path used to drop the whole history",
            postSync.contains("invalidateWatchedSnapshot()")
        )
        assertTrue(
            "an accepted write applies exactly what it moved",
            postSync.contains("onAccepted()")
        )
    }

    @Test
    fun `every watched write applies its own delta`() {
        assertTrue(body("suspend fun pushWatched(", "POST /sync/watched — record a WHOLE show")
            .contains("if (ok) applySnapshotWrite(apiKey, write)"))
        assertTrue(body("suspend fun pushWatchedShow(", "Episode watch pushes/removes")
            .contains("MdbListSnapshotWrite(addShows = mdbListIdKeys(imdbId, tmdbId))"))
        assertTrue(body("suspend fun pushWatchedEpisodes(", "POST /sync/watched/remove — clear several")
            .contains("addEpisodes = mdbListEpisodeKeys(imdbId, tmdbId, season, episodes)"))
        assertTrue(body("suspend fun removeWatchedEpisodes(", "POST /sync/watched/remove — clear watched history")
            .contains("removeEpisodes = mdbListEpisodeKeys(imdbId, tmdbId, season, episodes)"))
        assertTrue(body("suspend fun removeWatched(", "POST /sync/watched/remove — clear a whole show")
            .contains("MdbListSnapshotWrite(removeMovies = mdbListIdKeys(imdbId, tmdbId))"))
        assertTrue(body("suspend fun removeWatchedShow(", "GET /sync/playback")
            .contains("MdbListSnapshotWrite(removeShows = mdbListIdKeys(imdbId, tmdbId))"))
    }

    @Test
    fun `a completed stop applies its keys and a partial one touches nothing`() {
        val stop = body("suspend fun scrobbleStop(", "Delete the paused scrobble session")
        assertTrue(
            "the stop's watched keys are applied only at the completion threshold",
            stop.contains("stopSnapshotWrite = if (mdbListStopCompletes(progress))")
        )
        val scrobble = body("private suspend fun postScrobble(", "Start (or resume) a scrobble session")
        assertTrue(
            "a partial stop drops neither the snapshot nor its disk row",
            scrobble.contains("\"stop\" -> if (stopSnapshotWrite != null) {") &&
                scrobble.contains("dropPlaybackCache()")
        )
        assertTrue(
            "clearing genuinely makes the snapshot unknown",
            scrobble.contains("\"clear\" -> invalidateWatchedSnapshot()")
        )
    }

    @Test
    fun `a delta lands in memory and on disk, and restarts the TTL`() {
        val write = body("private fun applySnapshotWrite(", "The profile switch's own drop")
        assertTrue(write.contains("cachedSnapshot = updated"))
        assertTrue(write.contains("cachedSnapshotAt = at"))
        assertTrue(
            "the disk row is rewritten, or a cold start serves the pre-write blob",
            write.contains("snapshotJsonAdapter.toJson(updated)") &&
                write.contains("jsonCacheDao?.upsert(")
        )
        assertTrue(
            "a fetch in flight must not write its pre-write view back",
            write.contains("snapshotGeneration += 1")
        )
        assertTrue(
            "a client with no snapshot in hand cannot patch one, so the row is " +
                "dropped rather than served stale",
            write.contains("jsonCacheDao?.deleteByKeys(listOf(diskKey))")
        )
    }

    // ── source helpers ────────────────────────────────────────────────

    private fun body(after: String, before: String): String {
        val client = source(CLIENT)
        assertTrue("missing marker: $after", client.contains(after))
        assertTrue("missing marker: $before", client.contains(before))
        return client.substringAfter(after).substringBefore(before)
    }

    private val sourceRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val CLIENT = "com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"
}
