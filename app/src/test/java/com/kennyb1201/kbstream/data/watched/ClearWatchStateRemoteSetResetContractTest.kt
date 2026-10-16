package com.kennyb1201.kbstream.data.watched

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Clear Continue Watching" clears the REMOTE snapshots too, not just one of
 * them.
 *
 * The repository answers watched questions from two cached snapshots: Simkl's
 * sets and MDBList's. [WatchedStatusRepository.clearLocalWatchState] emptied
 * the Room table and the in-memory cache and reset the Simkl stamp - and left
 * MDBList's three sets and its stamp untouched, so a title MDBList still
 * reported as watched came straight back on the next read and the state the
 * viewer had just cleared looked like it had not taken (for
 * [WatchedStatusRepository.REMOTE_SET_TTL_MS], then it re-fetched).
 *
 * This is the same one-caller-and-not-the-second shape
 * [WatchedStatusRepository.invalidateRemoteWatchSets] documents, which resets
 * that exact four-field MDBList group - so the pin is that the two reset sites
 * name the same fields.
 */
class ClearWatchStateRemoteSetResetContractTest {

    private val repo by lazy { source(WATCHED) }

    private val clear by lazy {
        repo.substringAfter("suspend fun clearLocalWatchState(")
            .substringBefore("private suspend fun watchedKeysFromCache(")
    }

    private val invalidate by lazy {
        repo.substringAfter("suspend fun invalidateRemoteWatchSets(")
            .substringBefore("suspend fun refreshRemoteWatchStateIfNeeded(")
    }

    @Test
    fun `clearing local watch state resets every remote snapshot it caches`() {
        listOf(
            "simklSetsFetchedAt",
            "mdbListMovieKeys",
            "mdbListEpisodeKeys",
            "mdbListStartedShowKeys",
            "mdbListFetchedAt"
        ).forEach { field ->
            assertTrue(
                "$field is still live after \"Clear Continue Watching\": the " +
                    "snapshot it holds is served for the whole TTL and the " +
                    "cleared state comes back",
                clear.contains("$field =\n                    ") || clear.contains("$field =")
            )
        }
    }

    @Test
    fun `the stamp-only sibling keeps its own narrower contract`() {
        // [invalidateRemoteWatchSets] is the OTHER reset: it is raised when a
        // single mark/unmark has just been pushed, and its answer is "re-read
        // the account" - both providers' STAMPS, with the sets themselves left
        // to the refetch that replaces them wholesale. Clearing the sets there
        // too would be a change to that path's meaning, not a fix to this one,
        // so the pin says what it actually resets.
        assertTrue(
            "the Simkl stamp",
            invalidate.contains("simklSetsFetchedAt =")
        )
        assertTrue(
            "and MDBList's, which is the half that was missing before",
            invalidate.contains("mdbListFetchedAt =")
        )
        listOf("mdbListMovieKeys", "mdbListEpisodeKeys", "mdbListStartedShowKeys")
            .forEach { field ->
                assertFalse(
                    "$field belongs to the refetch that follows, not to this reset",
                    invalidate.contains(field)
                )
            }
    }

    @Test
    fun `the clear still resets the Simkl stamp and the cache it was written for`() {
        assertTrue(
            "the Simkl stamp (the behaviour this fix must not drop)",
            clear.contains("simklSetsFetchedAt =")
        )
        assertTrue(
            "the in-memory cache and the Room table",
            clear.contains("cache.clear()") && clear.contains("watchedStatusDao.clearAll()")
        )
        assertTrue(
            "and the account link only when the caller asked for it - the " +
                "Settings action keeps Simkl's own scrobble history",
            clear.contains("if (clearSimklAuth) {") &&
                clear.contains("simklRepository.clearAuth()")
        )
    }

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findMainSourceRoot(): File {
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

    private companion object {
        const val WATCHED = "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
    }
}
