package com.kennyb1201.kbstream.data.trakt

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1's wiring, as source: the activities call exists, the snapshot is read from
 * disk before the network, each section is gated on its own stamp, and this
 * device's own writes still force a re-read.
 *
 * These are the parts a unit test cannot reach without an Android context, and
 * the parts whose absence is invisible in the field: the app keeps working, it
 * just spends the account's quota on answers it already had.
 */
class TraktDeltaSyncContractTest {

    private companion object {
        const val API = "com/kennyb1201/kbstream/data/trakt/TraktApiService.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/trakt/TraktRepository.kt"
        const val CACHE =
            "com/kennyb1201/kbstream/data/trakt/TraktWatchedSnapshotCache.kt"
        const val LAST_ACTIVITIES = "sync/last_activities"
        const val SNAPSHOT_FN = "suspend fun watchedSnapshot("
        const val INVALIDATE_FN = "private fun invalidateWatchedSnapshot()"
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `the activities call exists and is the cheap one`() {
        val api = source(API)

        assertTrue(
            "F1: without /sync/last_activities nothing can know what changed",
            api.contains("GET(\"$LAST_ACTIVITIES\")")
        )
        assertTrue(
            "F1: the answer is per section, so one section can be skipped",
            api.contains("suspend fun lastActivities(") &&
                api.contains("TraktLastActivities")
        )
    }

    @Test
    fun `a cold start answers from disk before the network`() {
        val repository = source(REPOSITORY)
        val body = repository.substringAfter(SNAPSHOT_FN)

        val diskRead = body.indexOf("snapshotCache.read(token)")
        val firstFetch = body.indexOf("api.watchedShows(")

        assertTrue("F1: the snapshot must have a disk home", diskRead >= 0)
        assertTrue(
            "F1: the disk snapshot has to be loaded before anything is fetched, " +
                "or a launch still walks the whole history",
            diskRead < firstFetch
        )
        assertTrue(
            "F1: and it must be written back, or the next launch starts cold again",
            repository.contains("snapshotCache.write(")
        )
    }

    @Test
    fun `each section is fetched only when its own stamp moved`() {
        val repository = source(REPOSITORY)
        val body = repository.substringAfter(SNAPSHOT_FN)

        assertTrue(
            "F1: an unchanged account must cost the activities call and nothing else",
            body.contains("stamps == previousStamps")
        )
        assertTrue(
            "F1: the shows side gates on its own stamp",
            body.contains("stamps.seriesMovedSince(previousStamps)")
        )
        assertTrue(
            "F1: the movies side gates on its own stamp",
            body.contains("stamps.moviesMovedSince(previousStamps)")
        )
        assertTrue(
            "F1: a failed or spent activities call must fall back to fetching, " +
                "never to serving a snapshot it cannot vouch for",
            body.contains("stamps == null ||") &&
                repository.contains("private suspend fun readLastActivities(")
        )
        assertTrue(
            "F1: the section that was skipped keeps what the previous snapshot had",
            body.contains("?: previous?.movieKeys.orEmpty()") &&
                body.contains("?: previous?.shows.orEmpty()")
        )
    }

    @Test
    fun `this device's own write still forces a re-read`() {
        val repository = source(REPOSITORY)
        val invalidate = repository.substringAfter(INVALIDATE_FN)

        assertTrue(
            "F1: a write of ours must drop the stamps too",
            invalidate.contains("cachedStamps = null")
        )
        assertTrue(
            "F1: and the disk entry, so Continue Watching cannot show a resume row " +
                "we just proved wrong",
            invalidate.contains("snapshotCache.clear()")
        )
    }

    @Test
    fun `the disk cache is per profile and never stores the token`() {
        val cache = source(CACHE)

        assertTrue(
            "F1: the prefs file must come from the profile, like the scrobble queue's",
            cache.contains("ProfileStorage.prefsName(")
        )
        assertTrue(
            "F1: an entry from another token must not be served",
            cache.contains("fingerprint(token)")
        )
        assertTrue(
            "F1: the file identifies the account by hash, never by credential",
            cache.contains("Integer.toHexString(token.hashCode())")
        )
    }
}
