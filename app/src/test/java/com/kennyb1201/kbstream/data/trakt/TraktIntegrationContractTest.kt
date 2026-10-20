package com.kennyb1201.kbstream.data.trakt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring promises of phases B-E1, read out of the sources they live in.
 *
 * Each phase is a small additive insertion at an existing seam (three players,
 * Home's watched union, the KB loader, the Detail review rail), and an
 * insertion is exactly what a later refactor can drop without a compiler
 * complaint. These assertions name the seam and the call.
 */
class TraktIntegrationContractTest {

    private fun mainRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun main(relative: String): String = File(mainRoot(), relative).readText()

    private val playerPaths = listOf(
        "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
        "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
        "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    )

    @Test
    fun `all three players scrobble and record completion on Trakt`() {
        playerPaths.forEach { path ->
            val src = main(path)

            assertTrue("$path scrobbles to Trakt", src.contains("scrobbleTrakt("))
            assertTrue("$path records completion on Trakt", src.contains("syncCompletionToTrakt("))
            assertTrue(
                "$path guards on the client id and the profile's token",
                src.contains("trakt.isConfigured()") && src.contains("trakt.hasToken()")
            )
            assertTrue(
                "$path resolves the active profile at fire time",
                src.contains("TraktRepository.getInstance(this)")
            )
        }
    }

    @Test
    fun `Home unions all three trackers' watched episodes`() {
        val src = main("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

        assertTrue("Trakt is read", src.contains("traktCompletedEpisodes"))
        assertTrue("and unioned with the other two", src.contains("+ traktCompletedEpisodes"))
        assertTrue(
            "a failed read is distinguished from nothing watched",
            src.contains("traktRepository.isConfigured()") &&
                src.contains("trackerReadFailed = true")
        )
    }

    @Test
    fun `the watched badges read Trakt's snapshot on the shared cadence`() {
        val src = main("com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt")

        assertTrue("the refresh exists", src.contains("refreshTraktSetsIfNeeded("))
        assertTrue("it is called from preload", src.contains("refreshTraktSetsIfNeeded(\n"))
        assertTrue("movie badges read it", src.contains("remoteSnapshot.traktMovieKeys"))
        assertTrue("show badges read it", src.contains("remoteSnapshot.traktStartedShowKeys"))
        assertTrue(
            "it is cleared with the other remote sets",
            src.contains("traktFetchedAt =\n                    0L")
        )
        assertTrue("marks push", src.contains("pushWatchedMovie("))
        assertTrue("unmarks remove", src.contains("removeWatchedMovie("))
    }

    @Test
    fun `KB trakt list sources are no longer dead`() {
        val src = main("com/kennyb1201/kbstream/data/kb/KBContentLoader.kt")

        assertTrue("the provider is routed", src.contains("\"trakt\" -> loadTraktSource("))
        assertTrue(
            "the list is read through the connected account",
            src.contains("traktRepository.listItems(")
        )
        assertTrue(
            "the source's own addressing fields are parsed",
            src.contains("TraktListRefParser.fromSource(")
        )
    }

    @Test
    fun `KB catalog sources page to the end instead of truncating`() {
        val kb = main("com/kennyb1201/kbstream/data/kb/KBContentLoader.kt")
        val repo = main("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")

        assertFalse("the 40-row cap is gone", kb.contains("MAX_ITEMS_PER_SOURCE"))
        assertTrue("add-on catalogs are paged", kb.contains("loadAllAddonPages("))
        assertTrue(
            "the add-on walk ends on an empty page",
            kb.contains("if (page.isEmpty()) break")
        )
        assertTrue("Trakt list reads walk every page", repo.contains("readAllPages {"))
        assertTrue(
            "using Trakt's own page count",
            repo.contains("X-Pagination-Page-Count")
        )
        assertTrue(
            "and stopping when a page adds nothing (an ignored page param)",
            repo.contains("if (added == 0) break")
        )
    }

    @Test
    fun `Detail merges Trakt comments into the review rail`() {
        val enrichment = main("com/kennyb1201/kbstream/ui/detail/DetailRatingEnrichment.kt")
        val vm = main("com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt")

        assertTrue(
            "the read is wired",
            enrichment.contains("fun traktComments(") &&
                enrichment.contains("vm.traktRepository.commentsFor(")
        )
        assertTrue("the load calls it", vm.contains("fetchTraktComments(normalizedType)"))
        assertTrue("and merges rather than replaces", vm.contains("internal fun addAllReviews("))
        assertTrue(
            "the episode list unions Trakt's watched state too",
            vm.contains("traktWatchedEpisodes")
        )
        assertTrue(
            "spoiler-flagged comments stay flagged",
            enrichment.contains("spoiler = spoiler")
        )
    }

    @Test
    fun `no phase ships a client secret`() {
        val models = main("com/kennyb1201/kbstream/data/trakt/TraktModels.kt")
        val repo = main("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")

        assertFalse(models.contains("@Json(name = \"client_secret\")"))
        assertFalse(models.contains("val clientSecret"))
        assertFalse(repo.contains("clientSecret"))
    }
}
