package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Calendar's "it does not seem to cache" report (field 2026-10-28), and the
 * three changes that answer it.
 *
 * There is no device in CI and the ViewModel is an `AndroidViewModel`, so the
 * behaviour is pinned where it is decided: the wiring in
 * `LibraryCalendarViewModel.kt`. What must not drift is stated per test below.
 */
class LibraryCalendarSpeedupContractTest {

    // ── §1: watched badges arrive with the rows ─────────────────────────────

    @Test
    fun `the local-first pass is local and unions, and never waits on an id resolution`() {
        val pass = section(
            source(VIEW_MODEL),
            "private suspend fun refreshWatchedLocal(month: YearMonth) {",
            "/**\n     * Publishes a watched set"
        )

        assertTrue(
            "the pass reads the profile's completed rows once",
            pass.contains("val local = localWatchedRows()")
        )
        assertFalse(
            "no id resolution may run here - that is the network step this pass " +
                "exists to skip",
            pass.contains("resolveImdbId")
        )
        assertTrue(
            "the show's own id is canonicalized the same way the row ids are",
            pass.contains("local[TraktIdKeys.canonical(showId)]")
        )
        assertTrue(
            "and it publishes by UNION: a tracker-only badge already on screen " +
                "must survive a local pass",
            pass.contains("watched = current.watched + keys")
        )
        assertTrue(
            "a pass whose month is no longer on screen publishes nothing",
            pass.contains("if (state.month != month) return") &&
                pass.contains("if (_uiState.value.month != month) return")
        )
    }

    @Test
    fun `the local pass rides the existing publish gate and is not inline in noteResolved`() {
        val note = section(
            source(VIEW_MODEL),
            "private fun noteResolved(",
            "    /** Puts a built month on screen"
        )
        assertTrue(
            "it launches after the publish the gate already allowed",
            note.contains("launchLocalWatched(month)")
        )
        assertTrue(
            "launched after the publish the gate allowed, so it rides the same cadence",
            note.indexOf("launchLocalWatched(month)") >
                note.indexOf("build.lastPublishedAt = now")
        )
        assertTrue(
            "only one watched pass may be in flight, the buildJob discipline",
            source(VIEW_MODEL).contains("watchedJob?.cancel()")
        )
    }

    @Test
    fun `the terminal pass still replaces the set, so un-marking is picked up`() {
        val full = section(
            source(VIEW_MODEL),
            "suspend fun refreshWatched() {",
            "/**\n     * Pass A of the watched state"
        )
        assertTrue(
            "the full pass publishes the merged union",
            full.contains("publishWatched(")
        )
        val publish = section(
            source(VIEW_MODEL),
            "private fun publishWatched(keys: Set<String>) {",
            "/** Launches the terminal, replacing watched pass"
        )
        assertTrue(
            "and a terminal publish REPLACES - a union here would mask an un-mark",
            publish.contains("copy(watched = keys)")
        )

        val resolve = section(
            source(VIEW_MODEL),
            "private suspend fun resolveMonth(",
            "/**\n     * Files one resolved show"
        )
        assertTrue(
            "the end of a build cancels any incremental pass and replaces the set",
            resolve.contains("watchedJob?.cancel()") &&
                resolve.contains("refreshWatched()")
        )
    }

    // ── §2: the cached month paints before the show-set re-read ─────────────

    @Test
    fun `open publishes a cached month before the show set is re-read`() {
        val open = section(source(VIEW_MODEL), "fun open() {", "/** A day was focused or tapped")

        val cachedRead = open.indexOf("val cached = monthCache[state.month]")
        val publish = open.indexOf("publish(cached, landingDay = state.selected)")
        val backgroundRead = open.indexOf("refreshShowIdsInBackground()")

        assertTrue("the cached month is looked up", cachedRead >= 0)
        assertTrue("and published", publish > cachedRead)
        assertTrue(
            "the show-set re-read happens BEHIND that paint",
            backgroundRead > publish
        )
        assertTrue(
            "a first-ever open (no set, no cached month) is unchanged: read, then build",
            open.contains("if (showIds.isEmpty()) {") &&
                open.contains("loadShowIds()\n                loadMonth(")
        )
    }

    @Test
    fun `a background re-read adopts only when the set changed, and only for the current month`() {
        val background = section(
            source(VIEW_MODEL),
            "private fun refreshShowIdsInBackground() {",
            "/**\n     * A profile switch invalidates everything"
        )

        assertTrue(
            "an unchanged set repaints nothing",
            background.contains(
                "if (showIds == previousIds && priorityIds == previousPriority) return@launch"
            )
        )
        assertTrue(
            "a changed one drops the month ON SCREEN and rebuilds it",
            background.contains("val month = _uiState.value.month") &&
                background.contains("monthCache.remove(month)") &&
                background.contains("loadMonth(month, landingDay = _uiState.value.selected)")
        )
        assertFalse(
            "and it keeps the interrupted build's progress, so the rebuild resumes",
            background.contains("progress.remove") || background.contains("progress.clear")
        )
        assertTrue(
            "a re-read that is no longer the latest drops its result",
            background.contains("if (generation != showSetGeneration) return@launch")
        )
        assertTrue(
            "the generation is bumped by every read and by throwing the set away",
            source(VIEW_MODEL).contains("showSetGeneration = generation") &&
                source(VIEW_MODEL).contains("showSetGeneration += 1")
        )
    }

    // ── §4: bounded parallelization ────────────────────────────────────────

    @Test
    fun `the covering seasons are fetched concurrently and assembled in order`() {
        val show = section(
            source(VIEW_MODEL),
            "private suspend fun calendarShowFor(",
            "/**\n     * The detail pointer as an episode"
        )
        assertTrue(
            "one child per covering season",
            show.contains("val fetched = seasonNumbers.map { season ->") &&
                show.contains("async {")
        )
        assertTrue(
            "awaited, so a failed season cannot fail the show",
            show.contains(".awaitAll()") &&
                show.contains("}.getOrNull().orEmpty()")
        )
        assertTrue(
            "and assembled by the INPUT order, never by completion order",
            show.contains("for ((season, episodes) in fetched)") &&
                show.contains("val ordered = LinkedHashMap<Int, List<ResolvedEpisode>>()")
        )
        assertTrue(
            "the calendar's own concurrency constant is untouched",
            source(VIEW_MODEL).contains("const val CALENDAR_CONCURRENCY = 4")
        )
    }

    @Test
    fun `id resolution is concurrent and bounded, and only that step is`() {
        val merge = section(
            source(VIEW_MODEL),
            "private suspend fun mergeWatched(",
            "/** Every canonical id form one show answers to"
        )
        assertTrue(
            "the keys are resolved up front, concurrently",
            merge.contains("val keysByShow = coroutineScope {") &&
                merge.contains("async { showId to permits.withPermit { keysFor(showId) } }")
        )
        assertTrue(
            "under the TagViewModel bound",
            merge.contains("Semaphore(KEYS_RESOLVE_CONCURRENCY)") &&
                source(VIEW_MODEL).contains("const val KEYS_RESOLVE_CONCURRENCY = 8")
        )
        assertTrue(
            "the per-show union itself stays sequential",
            merge.contains("for (showId in showIds) {") &&
                merge.contains("val keys = keysByShow[showId].orEmpty()")
        )
        assertTrue(
            "and so do the tracker reads, which is why they are not parallelized too",
            merge.contains("simklRepository.getWatchedEpisodesForShowByImdb(imdb, tmdb)")
        )
    }

    // ── the sources ─────────────────────────────────────────────────────────

    private fun section(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing landmark: $from", start >= 0)
        val rest = src.substring(start + from.length)
        val end = rest.indexOf(to)
        assertTrue("missing landmark after $from", end >= 0)
        return rest.substring(0, end)
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/library/LibraryCalendarViewModel.kt"
    }
}
