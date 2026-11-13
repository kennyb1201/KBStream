package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring that keeps Detail's watched markers whole (spec 2026-11-12).
 *
 * The rules themselves are tested in [com.kennyb1201.kbstream.data.watched.
 * WatchedKeysRebuildTest]; this pins that the DetailViewModel actually follows
 * them, because the field failure was a wiring failure: the complete set was
 * held in one field while a rebuild from a partial one replaced it.
 *
 *  - F1: the publish either replaces with a complete build or holds the last
 *    complete set (taking only the local slice's new keys meanwhile), and a
 *    tracker read that fails keeps the held set instead of emptying it - the
 *    silent `getOrDefault(emptySet())` is what turned one timed-out Simkl call
 *    into "only the last season is ticked".
 *  - F2: every optimistic local mark records its keys, and a rebuild is unioned
 *    with them rather than overwriting them.
 */
class DetailWatchedKeysRebuildContractTest {

    private val viewModel by lazy { source(VIEW_MODEL) }

    private val publish by lazy {
        viewModel.substringAfter("private fun publishWatchedEpisodeKeys(")
            .substringBefore("/**\n     * True when no tracker leg")
    }

    // ── F1: hold the complete set ────────────────────────────────────────

    @Test
    fun `the publish asks the rebuild rule before it replaces the set`() {
        assertTrue(
            "one decision point, named",
            publish.contains("WatchedKeysRebuild.shouldReplace(") &&
                publish.contains("sameTitle = sameTitle")
        )
        assertTrue(
            "a held publish keeps the set and takes only the local slice's new keys",
            publish.contains(
                "_watchedEpisodeKeys.value =\n" +
                    "                _watchedEpisodeKeys.value + localSlice - watchedKeyRemovalsInFlight"
            )
        )
        assertTrue(
            "the set the screen draws is the rule's merged build",
            publish.contains("_watchedEpisodeKeys.value = WatchedKeysRebuild.merge(") &&
                publish.contains("built = built") &&
                publish.contains("addedInFlight = watchedKeyAddsInFlight")
        )
    }

    @Test
    fun `what the flow holds is remembered, and so is what each leg has said`() {
        assertTrue(
            "the title the keys belong to, and whether they are complete",
            viewModel.contains("private var watchedKeysParentId: String? = null") &&
                viewModel.contains("private var watchedKeysComplete = false")
        )
        assertTrue(
            "one read marker per tracker leg",
            viewModel.contains("private var simklKeysReadFor: String? = null") &&
                viewModel.contains("private var mdbListKeysReadFor: String? = null") &&
                viewModel.contains("private var traktKeysReadFor: String? = null")
        )
        assertTrue(
            "and completeness is measured per show, not globally",
            viewModel.contains("private fun trackerLegsComplete(parentId: String): Boolean")
        )
    }

    @Test
    fun `a failed tracker read keeps the set it already had`() {
        val legs = viewModel.substringAfter("// 2. Fetch Simkl watch states")
            .substringBefore("// 3. Build merged keys")
        // Comments there quote the old line on purpose; only CODE counts.
        val code = legs.lines()
            .filterNot { line -> line.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "no tracker leg answers a failure with an empty set any more",
            code.contains("getOrDefault(emptySet())") ||
                code.contains("getOrElse { traktWatchedEpisodes }")
        )
        assertTrue(
            "it assigns and marks the leg read only when the read succeeded",
            viewModel.contains(
                "}.onSuccess { episodes ->\n" +
                    "                        _simklWatchedEpisodes.value = episodes\n" +
                    "                        _simklSeriesWatched.value = episodes.isNotEmpty()\n" +
                    "                        simklKeysReadFor = id"
            )
        )
        assertTrue(
            "and the two field-backed legs are only replaced on success",
            viewModel.contains("mdbListWatchedEpisodes = episodes\n                        mdbListKeysReadFor = id") &&
                viewModel.contains("traktWatchedEpisodes = episodes\n                        traktKeysReadFor = id")
        )
    }

    @Test
    fun `another show's legs are cleared before this page publishes`() {
        val adopt = viewModel.substringAfter("private fun adoptTrackerSetsFor(")
            .substringBefore("    /**")
        assertTrue(
            "a different title starts from nothing",
            adopt.contains("_simklWatchedEpisodes.value = emptySet()") &&
                adopt.contains("mdbListWatchedEpisodes = emptySet()") &&
                adopt.contains("traktWatchedEpisodes = emptySet()")
        )
        assertTrue(
            "and a return to the same title keeps what it holds",
            adopt.contains("if (trackerSetsFor == parentId) return")
        )
        assertTrue(
            "called before the local publish, on both the load and the fast path",
            count(viewModel, "adoptTrackerSetsFor(id)") >= 2
        )
    }

    // ── F2: the just-watched episode stays ───────────────────────────────

    @Test
    fun `every optimistic mark and un-mark is recorded for the rebuild in flight`() {
        assertTrue(
            "season mark",
            count(viewModel, "noteAddedKeys(addedKeys)") == 2
        )
        assertTrue(
            "season un-mark and episode un-mark",
            count(viewModel, "noteRemovedKeys(removeKeys)") == 2
        )
        assertTrue(
            "and the whole-show un-mark",
            count(viewModel, "noteRemovedKeys(removedSeriesKeys)") == 1
        )
        assertTrue(
            "the two records are opposites of each other",
            viewModel.contains("watchedKeyRemovalsInFlight -= keys") &&
                viewModel.contains("watchedKeyAddsInFlight += keys") &&
                viewModel.contains("watchedKeyAddsInFlight -= keys") &&
                viewModel.contains("watchedKeyRemovalsInFlight += keys")
        )
    }

    @Test
    fun `the in-flight marks belong to one show only`() {
        assertTrue(
            "a different title drops the previous show's in-flight marks",
            viewModel.contains(
                "if (!sameTitle) {\n" +
                    "            // In-flight marks belong to the show they were made on.\n" +
                    "            watchedKeyAddsInFlight.clear()\n" +
                    "            watchedKeyRemovalsInFlight.clear()"
            )
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
