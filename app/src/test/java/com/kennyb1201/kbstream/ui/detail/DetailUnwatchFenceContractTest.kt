package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An un-watch that has not landed yet survives a rebuild (spec 2026-11-12).
 *
 * Field report on 0.9.253: mark episode A unwatched, immediately mark B
 * unwatched, and A flips back to watched. Marking A again then sticks.
 *
 * What the tree showed: the per-episode and per-season paths take the keys out
 * of `_watchedEpisodeKeys` at once and fence them in `watchedKeyRemovalsInFlight`,
 * and every rebuild subtracts that fence - but the fence was cleared by the
 * first rebuild that ran while `watchedKeysComplete` was true. That flag only
 * says every tracker leg has ANSWERED for this show; the answers it cleared on
 * were reads taken before the un-watch's own writes. So B's un-watch rebuilt the
 * set from a snapshot that still had A, with nothing left to fence it.
 *
 * A second, quieter hole: the un-mark paths brought the local keys and Simkl's
 * pairs in line, but left MDBList's and Trakt's slices holding the episode - so
 * the rebuild had the key to union back even after the fence was gone.
 *
 * Both are pinned here: the fence is raised before the first write, it is
 * consumed by its own fan-out returning and by nothing else, and all three
 * tracker slices forget the removal at the same moment as the keys do.
 *
 * The rules themselves live in [com.kennyb1201.kbstream.data.watched.
 * WatchedKeysRebuild] and are covered by `WatchedKeysRebuildTest`; this is the
 * wiring, which is what the field failure was.
 */
class DetailUnwatchFenceContractTest {

    private val viewModel by lazy { source(VIEW_MODEL) }

    private val publish by lazy {
        viewModel.substringAfter("private fun publishWatchedEpisodeKeys(")
            .substringBefore("/**\n     * True when no tracker leg")
    }

    private fun body(fn: String): String =
        viewModel.substringAfter("    $fn")
            .substringBefore("\n    /**")

    // ── F1: a rebuild must not consume the fence ─────────────────────────

    @Test
    fun `only a title change drops the fence, never a rebuild`() {
        assertEquals(
            "one place clears the fence, and it is the title-change reset",
            1,
            count(viewModel, "watchedKeyRemovalsInFlight.clear()")
        )
        assertTrue(
            "a complete rebuild still takes the ADDS in (they are in `built`)...",
            publish.contains("watchedKeyAddsInFlight.clear()")
        )
        val afterComplete = publish.substringAfter("watchedKeysComplete) {")
        assertTrue(
            "...but it leaves the removals alone: an answered read is not a " +
                "confirmation that the writes behind a removal landed",
            !afterComplete.contains("watchedKeyRemovalsInFlight")
        )
        assertTrue(
            "the fence is dropped only by the removal's own fan-out returning",
            viewModel.contains(
                "private fun settleRemovedKeys(keys: Set<String>) {\n" +
                    "        if (keys.isEmpty()) return\n" +
                    "        watchedKeyRemovalsInFlight -= keys\n" +
                    "    }"
            )
        )
    }

    @Test
    fun `the un-watch is out of the set and fenced before its first write`() {
        listOf("fun markSeasonUnwatched(", "private fun markSpecificEpisodesUnwatched(")
            .forEach { fn ->
                val fnBody = body(fn)
                val fence = fnBody.indexOf("noteRemovedKeys(removeKeys)")
                val launch = fnBody.indexOf("viewModelScope.launch {")
                val delete = fnBody.indexOf("historyDao.delete")
                assertTrue("$fn fences its keys", fence > 0)
                assertTrue("$fn starts its writes", launch > 0)
                assertTrue("$fn deletes its rows", delete > launch)
                assertTrue(
                    "$fn: the fence is up before the coroutine that writes at all " +
                        "- the delete's collector rebuilds the set the instant it lands",
                    fence < launch
                )
                assertTrue(
                    "$fn: and the badge clears in the same breath",
                    fnBody.contains(
                        "_watchedEpisodeKeys.value = _watchedEpisodeKeys.value - removeKeys"
                    )
                )
            }

        val series = body("fun markSeriesUnwatched(")
        assertTrue(
            "the whole-show un-mark fences before its delete too",
            series.indexOf("noteRemovedKeys(removedSeriesKeys)") in
                1 until series.indexOf("viewModelScope.launch {")
        )
    }

    @Test
    fun `the fence is settled when the fan-out returns, on every exit`() {
        val season = body("fun markSeasonUnwatched(")
        assertEquals(
            "the season path settles on both of its exits - the tracker rewrite " +
                "branch and the full fan-out",
            2,
            count(season, "settleRemovedKeys(removeKeys)")
        )
        val episode = body("private fun markSpecificEpisodesUnwatched(")
        assertEquals(
            "and so does the per-episode / previous-episodes path",
            2,
            count(episode, "settleRemovedKeys(removeKeys)")
        )
        assertTrue(
            "the whole-show un-mark settles too",
            body("fun markSeriesUnwatched(").contains("settleRemovedKeys(removedSeriesKeys)")
        )
        // The last write of each path comes before its settle.
        assertTrue(
            "the season settles after its last tracker write",
            season.indexOf("settleRemovedKeys(removeKeys)", season.indexOf("removeWatchedSeason(")) > 0
        )
        assertTrue(
            "the episode path settles after its last tracker write",
            episode.indexOf("settleRemovedKeys(removeKeys)", episode.indexOf("trakt.removeWatchedEpisode(")) > 0
        )
    }

    // ── F1/F2: every tracker slice forgets the removal ───────────────────

    @Test
    fun `all three tracker slices forget an un-watch, not just Simkl`() {
        val forget = viewModel.substringAfter("private fun forgetTrackerPairs(")
            .substringBefore("\n    /**")
        assertTrue(
            "Simkl's pairs, and the series flag it drives",
            forget.contains("_simklWatchedEpisodes.value = _simklWatchedEpisodes.value - pairs") &&
                forget.contains("_simklSeriesWatched.value = _simklWatchedEpisodes.value.isNotEmpty()")
        )
        assertTrue(
            "MDBList's and Trakt's - the two that kept the episode and let the " +
                "rebuild union it back",
            forget.contains("mdbListWatchedEpisodes = mdbListWatchedEpisodes - pairs") &&
                forget.contains("traktWatchedEpisodes = traktWatchedEpisodes - pairs")
        )
        assertTrue(
            "both un-mark paths go through it",
            count(viewModel, "forgetTrackerPairs(removePairs)") == 2
        )
        assertTrue(
            "and the whole-show path clears all three, since it clears every key",
            body("fun markSeriesUnwatched(").contains("mdbListWatchedEpisodes =") &&
                body("fun markSeriesUnwatched(").contains("traktWatchedEpisodes =")
        )
        assertTrue(
            "no path leaves a hand-rolled Simkl-only removal behind",
            count(viewModel, "_simklWatchedEpisodes.value - removePairs") == 0
        )
    }

    @Test
    fun `a re-watch still beats an un-watch fence`() {
        assertTrue(
            "marking watched drops the key from the removals before adding it",
            viewModel.contains("watchedKeyRemovalsInFlight -= keys") &&
                viewModel.contains("watchedKeyAddsInFlight += keys")
        )
        assertTrue(
            "so settling a stale un-watch is a no-op for that key",
            viewModel.contains("watchedKeyRemovalsInFlight -= keys\n    }")
        )
    }

    private fun count(haystack: String, needle: String): Int {
        var found = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            found++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return found
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
    }
}
