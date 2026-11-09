package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue Watching's two new next-episode legs (Slice A: X1-X5).
 *
 * The rule that decides whether a tracker row becomes a card is pure and is
 * exercised here; the loaders themselves need a database, a tracker session and
 * a network, so the wiring that has to hold - every configured feed is read, the
 * Trakt slot arrives once, a failed read publishes nothing, BingeCat's history is
 * read once per build and folded into the watched union - is pinned from source.
 */
class ContinueWatchingTrackerLegsTest {

    // ── the admission rule (X1b / X3b) ─────────────────────────────────────

    @Test
    fun `a show with something left to watch is a candidate`() {
        assertFalse(
            "the walk answered a pair and the totals say there is more",
            ResolvedHomeSeriesTarget(
                season = 2,
                episode = 3,
                episodesWatched = 9,
                episodesTotal = 16
            ).isCaughtUpByTotals()
        )
    }

    @Test
    fun `a show the walk counted as fully watched is not a candidate`() {
        assertTrue(
            "every aired episode watched: the next appearance is Upcoming's, not CW's",
            ResolvedHomeSeriesTarget(
                season = 1,
                episode = 12,
                episodesWatched = 12,
                episodesTotal = 12
            ).isCaughtUpByTotals()
        )
    }

    @Test
    fun `an uncounted show keeps its card`() {
        // The resolver answers the pair it knows, with null totals, when the
        // watched state could not be read completely. An unreadable state cannot
        // prove a backlog is finished, and dropping here would hide a show the
        // viewer is in the middle of.
        assertFalse(
            "no totals is unknown, not zero",
            ResolvedHomeSeriesTarget(
                season = 3,
                episode = 4,
                episodesWatched = null,
                episodesTotal = null
            ).isCaughtUpByTotals()
        )
        assertFalse(
            "and a zero total is a season list TMDB did not answer",
            ResolvedHomeSeriesTarget(
                season = 1,
                episode = 1,
                episodesWatched = 0,
                episodesTotal = 0
            ).isCaughtUpByTotals()
        )
    }

    @Test
    fun `the new legs' cards are tracker cards, so the tracker rules apply to them`() {
        // The locally-finished filter and the just-completed suppression both key
        // off this predicate: a BingeCat card outside it would escape every rule
        // the other three trackers' cards obey.
        listOf("bingecat", "trakt", "simkl", "mdblist").forEach { prefix ->
            assertTrue(
                "a $prefix card is tracker-sourced",
                isTrackerSourcedCard(card("$prefix:next:tt123"))
            )
        }
        assertFalse(
            "a local card is not",
            isTrackerSourcedCard(card("history:tt123:1:1"))
        )
    }

    @Test
    fun `a BingeCat card's parent id survives the rail's id helper`() {
        // The card's own id carries the source and the suffix; the SHOW is named
        // by parentId, which must stay an id the other sources recognise.
        val card = card("bingecat:next:tt100", parentId = "tt100")
        assertEquals("tt100", upNextIdentifier(card.parentId))
        assertEquals(
            "and the card id is distinct from a paused Trakt session's",
            "tt100",
            upNextIdentifier(card.id.removePrefix("bingecat:next:"))
        )
    }

    // ── the wiring (X1a/X1c/X2/X3/X5) ──────────────────────────────────────

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

    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        return text.substring(from, if (to > from) to else text.length)
    }

    private fun viewModel() = source(VIEW_MODEL)

    private fun traktLaunch() =
        slice(viewModel(), "// Trakt arrives ONCE", "// BingeCat's watched history")

    private fun bingeCatLaunch() =
        slice(viewModel(), "// BingeCat's watched history", "// MDBList arrives ONCE")

    private fun bingeCatLoader() =
        slice(viewModel(), "private suspend fun loadBingeCatUpNextItems()", "\n    /**")

    private fun traktLoader() = slice(
        viewModel(),
        "private suspend fun loadTraktNextEpisodeUpNextItems()",
        "\n    /**"
    )

    private fun sharedBuilder() = slice(
        viewModel(),
        "private suspend fun buildTrackerNextEpisodeUpNextItem(",
        "\n    private suspend fun buildSimklUpNextItem("
    )

    @Test
    fun `the Trakt feed is read whenever Trakt is configured`() {
        val launch = traktLaunch()
        assertFalse(
            "the resume authority must not decide whether a feed is read",
            launch.contains("ResumeAuthority.TRAKT")
        )
        assertFalse(
            "and the old gate is gone from the merge",
            viewModel().contains("if (resumeAuthority == ResumeAuthority.TRAKT)")
        )
        assertTrue(
            "the leg's own NotConfigured is what stops the read",
            traktLoader().contains("!traktRepository.isConfigured() || !traktRepository.hasToken()")
        )
        assertTrue(
            "and the authority is still read once per merge, for the winner rule",
            viewModel().contains("val resumeAuthority =\n                            AppPreferences.getResumeAuthority(getApplication())")
        )
    }

    @Test
    fun `the Trakt slot arrives once, with both halves`() {
        val launch = traktLaunch()
        assertEquals(
            "one launch for the slot: two launches into one slot would replace " +
                "each other",
            1,
            launch.split("launch {").size - 1
        )
        assertEquals(
            "and one arrive per answer branch (Success, NotConfigured), so the " +
                "whole slot is written by one writer",
            2,
            launch.split("arrivals.arrive(").size - 1
        )
        assertTrue(
            "the paused sessions and the next-episode cards are one answer",
            launch.contains("paused + next.items")
        )
        assertTrue(
            "and the paused half is unchanged",
            launch.contains("val paused =\n                                    loadTraktUpNextItems()")
        )
    }

    @Test
    fun `a failed read publishes nothing, an unconfigured one clears the slot`() {
        val launch = traktLaunch()
        val failed = slice(launch, "is TraktNextUpResult.Failed ->", "\n                                }")
        assertTrue(
            "no answer is not an empty answer",
            failed.contains("preserving existing Up Next data")
        )
        assertFalse(
            "so the whole slot keeps its previous cards",
            failed.contains("arrivals.arrive")
        )
        assertTrue(
            "an account that is gone is a different state: the stale cards go",
            launch.contains("TraktNextUpResult.NotConfigured ->")
        )
    }

    @Test
    fun `the Trakt leg reads the watched snapshot and enumerates started shows`() {
        val loader = traktLoader()
        assertTrue(
            "the cached watched snapshot, not a new read of the account",
            loader.contains("traktRepository.watchedSnapshot()")
        )
        assertTrue(
            "a row with no watched episode proves nothing",
            loader.contains("filter { row -> row.episodes.isNotEmpty() }")
        )
        val identity = slice(
            viewModel(),
            "private fun traktNextEpisodeParentId(",
            "\n    /**"
        )
        assertTrue(
            "identity is IMDb first, then tmdb:, then any canonical form",
            identity.contains("startsWith(\"tt\")") &&
                identity.contains("startsWith(\"tmdb:\")") &&
                identity.contains("?: row.idKeys.firstOrNull()")
        )
        assertTrue(
            "and a row with no usable id is skipped, never guessed at",
            identity.contains("String?")
        )
        assertTrue(
            "the paused leg's own cap and concurrency",
            loader.contains("take(MAX_TRAKT_UP_NEXT_ITEMS)") &&
                loader.contains("Semaphore(TRAKT_UP_NEXT_CONCURRENCY)")
        )
        assertTrue(
            "one unresolvable show drops that row only",
            loader.contains("runCatchingCancellable {") &&
                loader.contains(".getOrNull()")
        )
        assertTrue(
            "and the leg logs the Simkl line's shape",
            loader.contains("\"TRAKT next-up load succeeded: \"") &&
                loader.contains("raw=\${snapshot.shows.size}")
        )
    }

    @Test
    fun `the BingeCat leg checks its own gates before it reads`() {
        val loader = bingeCatLoader()
        val gatesAt = loader.indexOf("if (!repository.isReady())")
        val scopeAt = loader.indexOf("hasHistoryReadScope(repository.accountScope())")
        val readAt = loader.indexOf("watchedHistorySnapshot()")

        assertTrue("isReady is checked", gatesAt >= 0)
        assertTrue("the history:read scope is checked", scopeAt >= 0)
        assertTrue(
            "both gates are asked BEFORE the request - the repository answers " +
                "null for a gate and for a failed page alike, so past the gates " +
                "a null can only be a failure",
            gatesAt < readAt && scopeAt < readAt
        )
        assertTrue(
            "a gate is NotConfigured, so no request earns a 403 it would swallow",
            loader.contains("return BingeCatNextUpResult.NotConfigured")
        )
        assertTrue(
            "a null after the gates is a failed read",
            loader.contains("BingeCatNextUpResult.Failed(")
        )
        assertEquals(
            "ONE paged read per build: the feed has no cache of its own",
            1,
            loader.split("watchedHistorySnapshot()").size - 1
        )
        assertTrue(
            "same cap and concurrency as the other legs",
            loader.contains("take(MAX_BINGECAT_UP_NEXT_ITEMS)") &&
                loader.contains("Semaphore(BINGECAT_UP_NEXT_CONCURRENCY)")
        )
        assertTrue(
            "and the same log shape",
            loader.contains("\"BINGECAT next-up load succeeded: \"")
        )
    }

    @Test
    fun `the BingeCat feed arrives in its own slot, last in the merge`() {
        val vm = viewModel()
        assertTrue(
            "the slot exists",
            vm.contains("arrivals.arrive(\n                                            TrackerFeed.BINGECAT")
        )
        assertTrue(
            "an unconfigured mapping clears it",
            bingeCatLaunch().contains("BingeCatNextUpResult.NotConfigured ->")
        )
        assertTrue(
            "a failed read preserves it",
            bingeCatLaunch().contains("preserving existing Up Next data")
        )
        assertEquals(
            "one launch and one arrive per answer branch: a single writer for " +
                "the slot",
            1,
            bingeCatLaunch().split("launch {").size - 1
        )
        assertEquals(
            2,
            bingeCatLaunch().split("arrivals.arrive(").size - 1
        )
    }

    @Test
    fun `both legs build one card shape`() {
        val builder = sharedBuilder()
        assertTrue(
            "the legs share a builder rather than inventing a second card shape",
            viewModel().contains("idPrefix = \"trakt:next:\",") &&
                viewModel().contains("idPrefix = \"bingecat:next:\",")
        )
        assertTrue(
            "the card's id is distinct from a paused session's",
            builder.contains("id = \"\$idPrefix\$parentId\"")
        )
        assertTrue(
            "the walk's pair, not the tracker's, is what the card points at",
            builder.contains("season = resolvedTarget.season")
        )
        assertTrue(
            "no timestamp is invented from snapshot order",
            builder.contains("recencyTimestamp = 0L")
        )
        assertTrue(
            "the TMDB id is carried: twins and Upcoming key on it",
            builder.contains("tmdbId = detail?.id")
        )
        assertTrue(
            "the zombie gate is the shared one",
            builder.contains("dropsZombieCard(")
        )
        assertTrue(
            "next-air is repaired before Upcoming reads it",
            builder.contains("nextAirForResolvedTarget(")
        )
        assertTrue(
            "a caught-up show is dropped here too",
            builder.contains("if (resolvedTarget.isCaughtUpByTotals()) return null")
        )
        assertTrue(
            "and an unresolved show is no card at all",
            builder.contains(") ?: return null")
        )
    }

    @Test
    fun `BingeCat's plays reach the watched union without a second read`() {
        val vm = viewModel()
        val preload = slice(
            vm,
            "private suspend fun preloadWatchedEpisodeStateForShow(",
            "\n    private"
        )
        assertTrue(
            "the preload takes the read the leg already made",
            preload.contains("bingeCatHistory: BingeCatHistoryRead? = null")
        )
        assertTrue(
            "this show's episodes come out of that snapshot",
            preload.contains("bingeCatHistory.snapshot.watchedEpisodesFor(showParentIds)")
        )
        assertTrue(
            "a failed read is no evidence, marked like a failed tracker read",
            preload.contains("BingeCatHistoryRead.Failed ->") &&
                preload.contains("trackerReadFailed = true")
        )
        assertTrue(
            "and nothing is read here: the snapshot is the read",
            !preload.contains("watchedHistorySnapshot()")
        )
        assertTrue(
            "the four trackers' sets are one union",
            preload.contains("traktCompletedEpisodes +") &&
                preload.contains("bingeCatCompletedEpisodes")
        )
        assertTrue(
            "the leg hands its snapshot to each card's preload",
            vm.contains("bingeCatHistory =\n                                            BingeCatHistoryRead.Read(snapshot)")
        )
    }

    @Test
    fun `the authority gains no BingeCat value`() {
        assertTrue(
            "BingeCat is a card source, not a resume authority",
            source(RESUME_SOURCE_ENUM).contains("BINGECAT")
        )
        assertFalse(
            "and the viewer is never offered it as one",
            source(RESUME_AUTHORITY).contains("BINGECAT")
        )
    }

    @Test
    fun `the merge order keeps the older feeds first`() {
        assertTrue(
            "BingeCat is appended last",
            source(ARRIVALS).contains(
                "TrackerFeed.SIMKL,\n            TrackerFeed.MDBLIST,\n            TrackerFeed.TRAKT,\n            TrackerFeed.BINGECAT"
            )
        )
        assertTrue(
            "and each feed is seeded from its own previous cards",
            source(ARRIVALS).contains("card.resumeSource.toTrackerFeed()")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun card(id: String, parentId: String? = "tt123") = UpNextItem(
        id = id,
        title = "A Show",
        poster = null,
        badge = UpNextBadge.NEXT_UP,
        parentId = parentId,
        parentType = "series",
        season = 1,
        episode = 2,
        resumeSource = if (id.startsWith("history:")) {
            ResumeSource.LOCAL
        } else {
            ResumeSource.TRAKT
        }
    )

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val ARRIVALS = "com/kennyb1201/kbstream/ui/home/ContinueWatchingArrivals.kt"
        const val RESUME_SOURCE_ENUM = "com/kennyb1201/kbstream/ui/home/HomeUpNext.kt"
        const val RESUME_AUTHORITY = "com/kennyb1201/kbstream/data/settings/ResumeAuthority.kt"
    }
}
