package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player picker's checkmarks are Detail's watched union (spec 2026-11-13).
 *
 * The field report was one sentence long: an episode is ticked on Detail and
 * blank on the same episode's row in the player picker. The cause was that the
 * picker read the union's LOCAL half only - Detail's set is local completed rows
 * plus Simkl, MDBList and Trakt - so anything watched on a tracker (or synced
 * from another device) was ticked in one place and not the other.
 *
 * Pinned here at the source, the way this tree's other player contracts are:
 *
 *  - F1: the picker builds its set with Detail's own legs and Detail's own
 *    builders, and a dead leg answers empty without blanking the others;
 *  - F1: the legs are read once per show and kept for the session, and no panel
 *    press (open, scroll, rebind, season switch back) reads a tracker;
 *  - F1: a clear made from the picker's own menu is fenced, so the cached legs
 *    cannot resurrect it on the next season load, and the fence is applied
 *    through the same merge Detail's rebuild uses;
 *  - F2: the season load is the refresh path, on both engines;
 *  - the number keying is untouched: watched state stays on TMDB's numbers.
 */
class PickerWatchedUnionContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    /**
     * The member starting at [signature], up to the next member: a line indented
     * by exactly four spaces that opens a declaration or a KDoc.
     */
    private fun bodyOf(path: String, signature: String): String {
        val src = source(path)
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start)
        val end = Regex("\\n {4}(?:/\\*\\*|private |internal |suspend |fun |val |class )")
            .find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    // ── F1: the union, leg for leg ─────────────────────────────────────────

    @Test
    fun `the picker's checkmarks are detail's union, leg for leg`() {
        val loader = flat(source(PLAYER_EPISODES))
        assertTrue(
            "the merged builder gets Simkl's pairs, not an empty set - that empty set was the bug",
            loader.contains("simklCompletedEpisodes = inputs.legs.simkl")
        )
        assertTrue(
            "Simkl: the same repository call Detail's page makes",
            loader.contains("repository.getWatchedEpisodesForShowByImdb(")
        )
        assertTrue(
            "MDBList: the same cached snapshot read",
            loader.contains("MdbListClient.watchedEpisodesForShow(")
        )
        assertTrue(
            "Trakt: the same snapshot read",
            loader.contains("repository.watchedEpisodesForShow(showKeys = historyIds)")
        )
        assertTrue(
            "and the shared builders are the only ones - no hand-rolled merge",
            loader.contains("WatchedEpisodeState.buildMergedWatchedKeys(") &&
                loader.contains("WatchedEpisodeState.effectiveWatchedEpisodesForSeason(") &&
                loader.contains("WatchedKeysRebuild.merge(")
        )
        assertFalse(
            "the local-only read is gone: the completed rows are read across every id flavor",
            loader.contains("getCompletedForParents(listOf(parentId))")
        )
    }

    @Test
    fun `a dead leg contributes nothing and never blanks the others`() {
        val legs = flat(bodyOf(PLAYER_EPISODES, "private suspend fun readWatchedLegs("))
        assertTrue("the leg reader must exist", legs.isNotEmpty())
        assertEquals(
            "each leg falls back to the empty set on its own - one dead tracker must not clear the rest",
            3,
            legs.split(".getOrDefault(emptySet())").size - 1
        )
        assertTrue(
            "Simkl is gated on the connection, exactly as Detail gates it",
            legs.contains(
                "if (!repository.isConfigured() || !repository.hasToken()) { " +
                    "emptySet<Pair<Int, Int>>() } else { repository.getWatchedEpisodesForShowByImdb("
            )
        )
        assertTrue(
            "and so is MDBList",
            legs.contains("if (!MdbListClient.isConfigured(context)) {")
        )
        assertTrue(
            "and Trakt",
            legs.contains("repository.watchedEpisodesForShow(showKeys = historyIds)")
        )
    }

    // ── F1: once per show, never per press ─────────────────────────────────

    @Test
    fun `the legs are read once per show and kept for the session`() {
        val legs = flat(bodyOf(PLAYER_EPISODES, "private suspend fun watchedLegs("))
        assertTrue("the cache must be checked BEFORE any read", legs.isNotEmpty())
        assertTrue(
            "cache first, read only on a miss",
            legs.contains("legsByParent[parentId] ?: readWatchedLegs(")
        )
        assertTrue(
            "and the answer is kept, keyed by the show",
            legs.contains("also { legs -> legsByParent[parentId] = legs }")
        )
    }

    @Test
    fun `no panel press reads a tracker`() {
        val watched = flat(bodyOf(PLAYER_EPISODES, "suspend fun watchedEpisodes("))
        assertTrue("the season read must exist", watched.isNotEmpty())
        listOf(
            "getWatchedEpisodesForShowByImdb",
            "watchedEpisodesForShow("
        ).forEach { token ->
            assertFalse(
                "the season read itself must not touch $token - the legs come from the cache",
                watched.contains(token)
            )
        }
        listOf(NATIVE, MPV).forEach { path ->
            val bind = flat(bodyOf(path, "private fun bindEpisodePicker() {"))
            assertTrue("$path's rebind must exist", bind.isNotEmpty())
            assertFalse(
                "$path's rebind (open, scroll, season switch) must not read watched state",
                bind.contains("watchedEpisodes(") || bind.contains("watchedEpisodePairs(")
            )
        }
    }

    // ── F2: the season load is the refresh path ────────────────────────────

    @Test
    fun `both engines load the union with the season`() {
        listOf(NATIVE, MPV).forEach { path ->
            val load = flat(bodyOf(path, "private fun loadEpisodeSeason(target: Int?) {"))
            assertTrue("$path's season load must exist", load.isNotEmpty())
            assertTrue(
                "$path asks the loader for the season's union",
                load.contains("PlayerEpisodes.watchedEpisodes(") &&
                    load.contains("parentType")
            )
            assertTrue(
                "$path publishes it exactly as the rows read it",
                load.contains("episodePickerWatched = watched")
            )
            assertTrue(
                "$path's whole-series label reads the same union",
                load.contains("PlayerEpisodes.watchedEpisodePairs(")
            )
        }
    }

    @Test
    fun `the rows paint before the tracker legs, and repaint in place`() {
        listOf(NATIVE, MPV).forEach { path ->
            val load = flat(bodyOf(path, "private fun loadEpisodeSeason(target: Int?) {"))
            assertTrue(
                "$path must paint the rows, then read the legs - a tracker read in front of " +
                    "the first paint would hold the panel behind its timeout",
                load.contains("bindEpisodePicker()") &&
                    load.contains("waitForTrackerLegs = true") &&
                    load.indexOf("bindEpisodePicker()") < load.indexOf("waitForTrackerLegs = true")
            )
            assertTrue(
                "$path repaints the leg's answer into the same rows, never a second bind",
                load.contains("repaintEpisodePickerRows()") &&
                    !load.substring(load.indexOf("waitForTrackerLegs = true"))
                        .contains("bindEpisodePicker()")
            )
            val repaint = flat(bodyOf(path, "private fun repaintEpisodePickerRows() {"))
            assertTrue(
                "$path's repaint hands the rows to the adapter it already has",
                repaint.isNotEmpty() && repaint.contains(".submit(episodePickerItems())")
            )
            val refresh = flat(
                bodyOf(path, "private fun refreshEpisodePickerWatched(restoreToEpisode: Int) {")
            )
            assertTrue(
                "$path's post-menu refresh repaints in place too - a new adapter would drop " +
                    "the focus hand-back",
                refresh.isNotEmpty() && refresh.contains("repaintEpisodePickerRows()") &&
                    !refresh.contains("bindEpisodePicker()")
            )
            val apply = flat(
                bodyOf(path, "private fun applyEpisodeMenuRow(row: PickerEpisodeMenuRow, pressed: ResolvedEpisode) {")
            )
            assertTrue(
                "$path's press-time ✓ is painted in place as well",
                apply.isNotEmpty() && apply.contains("repaintEpisodePickerRows()")
            )
        }
        val adapter = flat(source(ADAPTER))
        assertTrue(
            "the adapter replaces its items instead of being swapped out, so focus and scroll survive",
            adapter.contains("private var items: List<PickerItem>") &&
                adapter.contains("fun submit(newItems: List<PickerItem>)") &&
                adapter.contains("notifyDataSetChanged()")
        )
    }

    // ── F1: the menu's clear is fenced ─────────────────────────────────────

    @Test
    fun `a clear from the menu is fenced, and a mark lifts it`() {
        val marks = flat(source(MARKS))
        assertTrue(
            "the menu records its clears",
            marks.contains(
                "PlayerEpisodes.notePickerRemoval(target.parentId, season, numbers)"
            )
        )
        assertTrue(
            "and lifts the fence when the same episode is marked again",
            marks.contains(
                "PlayerEpisodes.notePickerMark(target.parentId, season, numbers)"
            )
        )
        val loader = flat(source(PLAYER_EPISODES))
        assertTrue(
            "the fence is applied through the same merge Detail's rebuild uses",
            loader.contains(
                "removedInFlight = trackedKeys(parentId, fencedPairs(parentId, localPairs))"
            )
        )
        assertTrue(
            "a local row wins over the fence, so a later mark from Detail re-ticks the episode",
            loader.contains("return removed.filterNot { pair -> pair in localPairs }.toSet()")
        )
        assertTrue(
            "the fence lives with the picker's own data, keyed by show",
            loader.contains(
                "fun notePickerRemoval(parentId: String, season: Int, episodes: Collection<Int>)"
            ) && loader.contains(
                "fun notePickerMark(parentId: String, season: Int, episodes: Collection<Int>)"
            )
        )
    }

    // ── the keying is untouched ────────────────────────────────────────────

    @Test
    fun `watched state stays keyed on tmdb numbers`() {
        val picker = flat(source(PICKER))
        assertTrue(
            "the row still ticks on the episode's TMDB number, never a shown number",
            picker.contains("watched = watched.contains(episode.episodeNumber)")
        )
        val loader = flat(source(PLAYER_EPISODES))
        assertTrue(
            "and every leg is keyed the same way: parentId:season:episode",
            loader.contains("WatchedEpisodeState.buildEpisodeKey(parentId, season, episode)")
        )
    }

    private companion object {
        const val PLAYER_EPISODES = "com/kennyb1201/kbstream/ui/player/PlayerEpisodes.kt"
        const val MARKS = "com/kennyb1201/kbstream/ui/player/PickerEpisodeMarks.kt"
        const val PICKER = "com/kennyb1201/kbstream/ui/player/PlayerEpisodePicker.kt"
        const val ADAPTER = "com/kennyb1201/kbstream/ui/player/PickerAdapter.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
