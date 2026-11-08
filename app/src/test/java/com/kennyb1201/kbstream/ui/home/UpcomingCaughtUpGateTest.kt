package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.bingecat.BingeCatHistoryEpisodeEntry
import com.kennyb1201.kbstream.data.bingecat.BingeCatHistoryItem
import com.kennyb1201.kbstream.data.bingecat.BingeCatIds
import com.kennyb1201.kbstream.data.bingecat.BingeCatMedia
import com.kennyb1201.kbstream.data.bingecat.BingeCatWatchedSnapshot
import com.kennyb1201.kbstream.data.bingecat.bingeCatWatchedSnapshot
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.trakt.TraktShowWatch
import com.kennyb1201.kbstream.data.trakt.TraktWatchedSnapshot
import com.kennyb1201.kbstream.data.watched.LocalSeriesProgress
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Upcoming rail's caught-up gate and its union (spec F1-F3, T1-T7).
 *
 * The field report: MobLand (finished S2E8) and Tulsa King (S4E4) were both
 * missing from Upcoming although both finishes had reached Simkl and both shows
 * were on the newest build. Two gates were wrong, and this file pins both:
 *
 *  - the gate asked for EVERY aired episode (`LocalSeriesProgress.isCaughtUp`),
 *    which a pruned or partial row set can never prove - the decisive evidence,
 *    the last aired episode watched, was in the rows all along (T1, T4);
 *  - the rail's sources were treated as opinions that could veto each other,
 *    when the only question is whether ANY source proved the show caught up
 *    (T3-T7).
 *
 * The rule tests are behavioural; the wiring (which source feeds which leg, and
 * that no leg gates another) is pinned from the sources, because this module has
 * no ViewModel harness - S1-S6 are the field's own checks.
 */
class UpcomingCaughtUpGateTest {

    private fun source(relative: String): String {
        val root = findSourceRoot()
        val file = File(root, relative)
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
        throw AssertionError("source root not found")
    }

    private fun slides(rowId: String, parentId: String, season: Int, episode: Int) =
        WatchHistoryEntity(
            id = rowId,
            parentId = parentId,
            type = "series",
            name = parentId,
            poster = null,
            streamUrl = null,
            season = season,
            episode = episode,
            positionMs = 0L,
            durationMs = 1_000_000L,
            updatedAt = 1L,
            isCompleted = true,
            completedAt = 1L
        )

    private fun candidate(
        parentId: String,
        watched: Set<Pair<Int, Int>>,
        source: String,
        title: String? = null
    ) = CaughtUpShowCandidate(
        parentId = parentId,
        watchedEpisodes = watched,
        title = title,
        source = source,
        fromTracker = source != "local"
    )

    // ── T1: last-aired-watched is the rail's test ──────────────────────────

    @Test
    fun `the last aired episode watched is caught up, a sparse set is not`() {
        assertTrue(
            "T1/T4: MobLand's own evidence - S2E8 watched, last aired S2E8",
            LocalSeriesProgress.isCaughtUpOnLastAired(
                completedEpisodes = setOf(2 to 8),
                lastAiredSeason = 2,
                lastAiredEpisode = 8
            )
        )
        assertFalse(
            "T1/T7: the frontier episode itself unwatched is NOT caught up",
            LocalSeriesProgress.isCaughtUpOnLastAired(
                completedEpisodes = setOf(2 to 7),
                lastAiredSeason = 2,
                lastAiredEpisode = 8
            )
        )
        assertFalse(
            "T1: no evidence, no card",
            LocalSeriesProgress.isCaughtUpOnLastAired(
                completedEpisodes = emptySet(),
                lastAiredSeason = 2,
                lastAiredEpisode = 8
            )
        )
        assertFalse(
            "T1: an unknown frontier proves nothing either",
            LocalSeriesProgress.isCaughtUpOnLastAired(
                completedEpisodes = setOf(2 to 8),
                lastAiredSeason = null,
                lastAiredEpisode = 8
            )
        )
        assertFalse(
            "T1: neither does an episode number that cannot be an episode",
            LocalSeriesProgress.isCaughtUpOnLastAired(
                completedEpisodes = setOf(2 to 8),
                lastAiredSeason = 2,
                lastAiredEpisode = 0
            )
        )
    }

    @Test
    fun `the full-run rule keeps failing a sparse set for its own callers`() {
        assertFalse(
            "F1b: the badge's rule is unchanged - (2,8) alone is not a complete run",
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = setOf(2 to 8),
                seasonEpisodeCounts = mapOf(1 to 8, 2 to 9),
                lastAiredSeason = 2,
                lastAiredEpisode = 8
            )
        )
        assertTrue(
            "and it still answers its own question for a complete run",
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes = (1..8).map { 1 to it }.toSet() + (1..9).map { 2 to it }.toSet(),
                seasonEpisodeCounts = mapOf(1 to 8, 2 to 9),
                lastAiredSeason = 2,
                lastAiredEpisode = 8
            )
        )
    }

    // ── T4: the card builder reads the new rule, and the blob cannot veto ──

    @Test
    fun `the card builder gates on the last aired episode, not the full run`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        val card = viewModel.slice("private suspend fun buildCaughtUpUpcomingCard(", "val title =")

        assertTrue(
            "F1b: the builder asks the rail's own rule",
            card.contains("LocalSeriesProgress.isCaughtUpOnLastAired(")
        )
        assertFalse(
            "F1b: and no longer the badge's, which is what dropped both shows",
            card.contains("LocalSeriesProgress.isCaughtUp(")
        )
        assertFalse(
            "F1b: the per-season episode counts are no longer its input",
            card.contains("seasonEpisodeCounts")
        )
        assertTrue(
            "F3b: the Simkl leg is one contributor in the union, never a gate over it",
            viewModel.contains("repairCaughtUpAirDates(caughtUp) +\n") &&
                viewModel.contains("loadCaughtUpUpcomingItems() +\n") &&
                viewModel.contains("loadLocalCaughtUpUpcomingItems()")
        )
        assertFalse(
            "F3a: the local leg does not consult Simkl at all - which is why a stale " +
                "blob cannot hide a show it proves",
            viewModel
                .slice(
                    "private suspend fun loadLocalCaughtUpCandidates(",
                    "F2b/F3a: the union"
                )
                .contains("simklRepository")
        )
    }

    // ── T2: BingeCat is a leg of the same shape as Trakt's ─────────────────

    @Test
    fun `BingeCat's snapshot builds the same candidate Trakt's does`() {
        val snapshot = bingeCatWatchedSnapshot(
            listOf(
                BingeCatHistoryItem(
                    id = "row",
                    kind = "episodes",
                    type = "show",
                    show = BingeCatMedia(
                        title = "Tulsa King",
                        ids = BingeCatIds(imdb = "tt16308622")
                    ),
                    episodes = listOf(
                        BingeCatHistoryEpisodeEntry(season = 4, number = 4)
                    )
                )
            )
        )

        val fromBingeCat = bingeCatCaughtUpCandidates(snapshot, max = 25).single()
        val fromTrakt = traktCaughtUpCandidates(
            snapshot = TraktWatchedSnapshot(
                shows = listOf(
                    TraktShowWatch(
                        idKeys = setOf("tt16308622"),
                        episodes = setOf(4 to 4)
                    )
                )
            ),
            max = 25
        ).single()

        assertEquals("the same parent id form", fromTrakt.parentId, fromBingeCat.parentId)
        assertEquals("the same evidence", fromTrakt.watchedEpisodes, fromBingeCat.watchedEpisodes)
        assertEquals("the same tracker treatment", fromTrakt.fromTracker, fromBingeCat.fromTracker)
        assertEquals("F2c: and the trace can name which tracker it was", "bingecat", fromBingeCat.source)
        assertTrue(
            "and the local set the rule reads is the tracker's own evidence",
            LocalSeriesProgress.isCaughtUpOnLastAired(
                completedEpisodes = fromBingeCat.watchedEpisodes,
                lastAiredSeason = 4,
                lastAiredEpisode = 4
            )
        )
    }

    @Test
    fun `BingeCat unwired, unscoped or unreadable is a soft empty leg`() {
        assertTrue(
            "T2: no snapshot is no candidates - the rail is whatever else proved",
            bingeCatCaughtUpCandidates(BingeCatWatchedSnapshot(emptyList()), max = 25).isEmpty()
        )

        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        val leg = viewModel.slice("val bingecat =", "trackers = mdbList + trakt + bingecat")
        assertTrue(
            "F2a: a thrown read is caught, not propagated",
            leg.contains("runCatchingCancellable {") &&
                leg.contains("BingeCatRepository.getInstance(getApplication())") &&
                leg.contains(".watchedHistorySnapshot()") &&
                leg.contains(".orEmpty()")
        )

        val repository = source(
            "com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt"
        )
        val read = repository.slice("suspend fun watchedHistorySnapshot()", "private suspend fun")
        assertTrue(
            "F2a: isReady and the history:read scope gate the request itself",
            read.contains("if (!isReady()) return null") &&
                read.contains("if (!BingeCatWriteRules.hasHistoryReadScope(store.scope())) return null")
        )
        assertTrue(
            "F2a: and the scope it asks for is the contract's own history:read",
            source("com/kennyb1201/kbstream/data/bingecat/BingeCatConfig.kt")
                .contains("const val HISTORY_READ_SCOPE = \"history:read\"")
        )
        assertTrue(
            "F2a: the feed's episodes[] is what the fold reads - season + number",
            source("com/kennyb1201/kbstream/data/bingecat/BingeCatModels.kt")
                .contains("@Json(name = \"episodes\") val episodes: List<BingeCatHistoryEpisodeEntry>")
        )
    }

    // ── T3/T5/T6: the union is the rail ────────────────────────────────────

    @Test
    fun `with no Simkl the rail is still built from this profile's own rows`() {
        val local = localCaughtUpCandidates(
            rows = listOf(slides("e", "tt16308622", season = 4, episode = 4)),
            max = 25
        )
        val merged = mergeCaughtUpCandidates(local = local, trackers = emptyList())

        assertEquals("T3: one local candidate, no tracker needed", 1, merged.size)
        assertEquals("local", merged.single().source)
        assertTrue(
            "T3: and it passes the rail's gate on its own evidence",
            LocalSeriesProgress.isCaughtUpOnLastAired(
                completedEpisodes = merged.single().watchedEpisodes,
                lastAiredSeason = 4,
                lastAiredEpisode = 4
            )
        )
    }

    @Test
    fun `a finish only a tracker holds still becomes a candidate`() {
        val merged = mergeCaughtUpCandidates(
            local = emptyList(),
            trackers = listOf(candidate("tt13111078", setOf(2 to 8), "trakt"))
        )

        assertEquals("T5: no local row on this device is not a reason to hide it", 1, merged.size)
        assertEquals("tt13111078", merged.single().parentId)
        assertEquals("trakt", merged.single().source)
    }

    @Test
    fun `one show known by two sources is one candidate, and the local one wins`() {
        val local = candidate("tt13111078", setOf(2 to 8), "local", title = "MobLand")
        val merged = mergeCaughtUpCandidates(
            local = listOf(local),
            trackers = listOf(
                candidate("tt13111078", setOf(2 to 7), "trakt"),
                candidate("tt13111078", setOf(2 to 8), "bingecat"),
                candidate("tt99999999", setOf(1 to 1), "mdblist")
            )
        )

        assertEquals(
            "T6: three sources naming one show with the same id are ONE candidate",
            2,
            merged.size
        )
        assertEquals(
            "T6: the local candidate is the one kept, with this profile's title",
            "MobLand",
            merged.first().title
        )
        assertEquals("local", merged.first().source)
        assertEquals(
            "and an unrelated show from another tracker keeps its own card",
            "tt99999999",
            merged[1].parentId
        )
    }

    // ── T7: the refusals still refuse ──────────────────────────────────────

    @Test
    fun `a show whose last aired episode is unwatched earns no card from any source`() {
        val behind = setOf(1 to 1, 2 to 7)

        listOf(
            candidate("tt13111078", behind, "local"),
            candidate("tt13111078", behind, "mdblist"),
            candidate("tt13111078", behind, "trakt"),
            candidate("tt13111078", behind, "bingecat")
        ).forEach { show ->
            assertFalse(
                "T7: ${show.source} proves (2,7), the frontier is (2,8) - no card",
                LocalSeriesProgress.isCaughtUpOnLastAired(
                    completedEpisodes = show.watchedEpisodes,
                    lastAiredSeason = 2,
                    lastAiredEpisode = 8
                )
            )
        }
    }

    @Test
    fun `the rail's dismissals and kids gate still run over the merged set`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        val localLeg = viewModel.slice(
            "private suspend fun loadLocalCaughtUpUpcomingItems()",
            "private fun localCaughtUpUpcomingCache("
        )
        val candidates = viewModel.slice(
            "private suspend fun loadLocalCaughtUpCandidates(",
            "private suspend fun buildCaughtUpUpcomingCard("
        )

        assertTrue(
            "T7: a dropped show stays dropped - the shared gate runs after the build",
            localLeg.contains("applyContinueWatchingDismissals(")
        )
        assertTrue(
            "F2b: and the leg is one per-show list after the merge",
            candidates.contains("mergeCaughtUpCandidates(")
        )
    }

    // ── source helpers ────────────────────────────────────────────────────

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }
}
