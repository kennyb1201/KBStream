package com.kennyb1201.kbstream.data.watched

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watched badges: the checkmark means ENDED and fully completed in history
 * (spec 2026-11-17), pinned from source.
 *
 * The rule's arithmetic is covered by [SeriesCompletionRulesTest]; this is the
 * wiring around it, which is where the reported bug lived - the tile was always
 * right, and the resolution had no local path to the check.
 */
class WatchedBadgeResolutionContractTest {

    private val repository = source(
        "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
    )

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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
        throw AssertionError("main source root not found from " + System.getProperty("user.dir"))
    }

    /** The series `watched` branch: from its last `"series" -> {` to the eye's. */
    private fun seriesCompletedBranch(): String {
        val eye = repository.indexOf("Eye badge: a series the user has started")
        val start = repository.lastIndexOf("\"series\" -> {", eye)
        assertTrue("the series watched branch is where the eye's comment follows it", start in 0 until eye)
        return repository.substring(start, eye)
    }

    @Test
    fun `the check is awarded from local history when the show is ended and complete`() {
        val branch = seriesCompletedBranch()
        assertTrue(
            "the series completed set asks the local proof",
            branch.contains("localSeriesEvidenceOnce().completed")
        )
        assertTrue(
            "and still honours the manual mark",
            branch.contains("manuallyWatched")
        )
        assertTrue(
            "and Simkl's completed keys",
            branch.contains("remoteSnapshot.simklShowKeys") &&
                branch.contains("remoteSnapshot.simklShowTmdbKeys")
        )
    }

    @Test
    fun `the eye still comes from activity, and is not the check's mirror`() {
        val partial = repository.substringAfter("val partialShow =")
            .substringBefore("WatchedStatusEntity(")
        assertTrue(
            "the local half of the eye is plain activity",
            partial.contains("localSeriesEvidenceOnce().started")
        )
        assertTrue(
            "and the check still wins over it",
            partial.contains("!watched")
        )
    }

    @Test
    fun `the local proof survives a missing detail cache`() {
        val proof = repository.substringAfter("private suspend fun isLocallyCompletedSeries(")
            .substringBefore("suspend fun isWatchedCached(")
        assertTrue(
            "the cached detail is tried first",
            proof.contains("cachedDetailByTmdbId(tmdbId, \"series\")")
        )
        assertTrue(
            "and a miss resolves the detail instead of giving up",
            proof.contains("?: tmdbRepository.getDetailByTmdbId(tmdbId, \"series\")")
        )
        assertTrue(
            "the proof asks the shared rule, not a local copy of it",
            proof.contains("SeriesCompletionRules.isCompleted(")
        )
        assertTrue(
            "status, seasons and the airing frontier all come from that detail",
            proof.contains("status = detail.status") &&
                proof.contains("lastAiredSeason = detail.lastEpisodeToAir?.seasonNumber")
        )
    }

    @Test
    fun `the fetch stays bounded to a series with completed rows`() {
        val evidence = repository.substringAfter("private suspend fun localSeriesEvidence(")
            .substringBefore("private suspend fun isLocallyCompletedSeries(")
        // A resume row returns before any detail work, and an empty completed
        // list returns before it too - so no title with no local activity and no
        // in-progress show ever pays for the fetch.
        val resumeReturn = evidence.indexOf("started = true, completed = false")
        val completedReturn = evidence.indexOf("started = false, completed = false")
        val proofCall = evidence.indexOf("isLocallyCompletedSeries(")
        assertTrue("a resume row short-circuits", resumeReturn > 0 && resumeReturn < proofCall)
        assertTrue("no rows short-circuits", completedReturn > 0 && completedReturn < proofCall)
    }

    @Test
    fun `the started-only keys stay partial-only`() {
        val branch = seriesCompletedBranch()
        assertTrue(
            "MDBList started shows must never award the check",
            !branch.contains("mdbListStartedShowKeys")
        )
        assertTrue(
            "nor Trakt started shows",
            !branch.contains("traktStartedShowKeys")
        )
        val partial = repository.substringAfter("val partialShow =")
            .substringBefore("WatchedStatusEntity(")
        assertTrue(
            "both remain in the eye's set",
            partial.contains("mdbListStartedShowKeys") &&
                partial.contains("traktStartedShowKeys")
        )
    }

    @Test
    fun `the tile's precedence is untouched`() {
        val tile = source("com/kennyb1201/kbstream/ui/components/PosterCard.kt")
        assertTrue(
            "the check still wins over the eye",
            tile.contains("isWatched") && tile.contains("isPartiallyWatched")
        )
        assertFalse(
            "and nothing new gates the check on the eye's toggle",
            repository.contains("getPosterPartialWatchBadge")
        )
    }
}
