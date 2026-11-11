package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.airdates.AirDateCorrection
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upcoming's TVmaze fallback: a show TMDB has no dated next episode for still
 * reaches the rail when the second source knows when it airs.
 *
 * Reported 2026-11-10: Universal Basic Guys finished S3E6 and never appeared in
 * Upcoming, while The Simpsons, Animal Control and the other just-finished shows
 * did. The show was not the problem - TMDB's S3 listing held 6 episodes, its
 * declared count was 6, `next_episode_to_air` was null, and TVmaze had S3E7
 * (2026-11-15) and S3E8 (2026-11-22). The rail drops an item with no air date
 * before any second source is consulted, and the TVmaze lookup that feeds the
 * rail skipped null-air items as well - so the fallback machinery existed and the
 * one case that needed it could never reach it.
 *
 * The picker, the null-primary correction and the date parse are pinned by
 * [AirDateCorrectionTest]; this file pins the wiring: the fallback fires where
 * the date is read (F1), the lookup now includes the items that need it (F2), and
 * the move is visible in a paste (F3). Source-reading style, like the rest of the
 * rail's contracts - this module has no Compose/ViewModel harness.
 */
class UpcomingTvmazeFallbackContractTest {

    private val today = LocalDate.of(2026, 11, 10)

    private val viewModel by lazy { source(VIEW_MODEL) }

    /** The rail's builder, whole. */
    private val builder: String by lazy {
        viewModel.slice(
            "private suspend fun buildUpcomingSchedule(",
            "private suspend fun fallbackUpcomingEpisodeTitle("
        )
    }

    /** The second-source lookup behind it. */
    private val sources: String by lazy {
        viewModel.slice(
            "private suspend fun loadSourceAirDates(",
            "private suspend fun publishInstantUpNextSnapshot("
        )
    }

    // ── the composition itself ──────────────────────────────────────────────

    @Test
    fun `a TVmaze date with no TMDB date becomes the row's date`() {
        // The reported show's shape: TMDB says nothing about S3E7, TVmaze has it,
        // and the two rules the builder applies in sequence must return that date
        // unchanged - nextAiring picks it, correctAirDate has no primary to prefer.
        val sourceDates = mapOf(
            "3:6" to "2026-11-08",
            "3:7" to "2026-11-15",
            "3:8" to "2026-11-22"
        )

        val picked = AirDateCorrection.nextAiring(sourceDates, today)
        assertTrue("TVmaze knows a future episode for the show", picked != null)
        assertEquals(3, picked?.season)
        assertEquals(7, picked?.episode)
        assertEquals("the earliest one still ahead, not the season's last", "2026-11-15", picked?.airDate)

        val resolved = AirDateCorrection.correctAirDate(
            primary = null,
            secondary = sourceDates[AirDateCorrection.episodeKey(picked!!.season, picked.episode)],
            today = today
        )
        assertEquals(
            "a null primary already means 'use the secondary' - the correction needed no change",
            "2026-11-15",
            resolved
        )
        assertTrue(
            "and the builder's own parser accepts it, which is what puts it on the row",
            parseTmdbAirDate(resolved) != null && (parseTmdbAirDate(resolved) ?: 0) > 0
        )
    }

    @Test
    fun `a show the source does not know either still has nothing to date`() {
        assertNull(
            "no future episode in the source map -> no fallback -> the drop, as today",
            AirDateCorrection.nextAiring(emptyMap(), today)
        )
        assertNull(
            "and a source whose next episode already aired has none either: that show " +
                "belongs in Continue Watching, not Upcoming",
            AirDateCorrection.nextAiring(mapOf("3:7" to "2026-11-09"), today)
        )
    }

    // ── F1: the fallback, in the schedule builder ──────────────────────────

    @Test
    fun `a null next episode asks the source before the item is dropped`() {
        assertTrue(
            "F1: the fallback is taken from the same map the correction uses, and only " +
                "when TMDB named no episode - a show with a TMDB date never takes this path",
            builder.contains(
                "val fallback = if (air == null) {\n" +
                    "                AirDateCorrection.nextAiring(sourceDates, today)"
            )
        )
        assertTrue(
            "F1: before the drop, not after it - the drop is what used to end the item's " +
                "life before a second source was ever consulted",
            builder.indexOf("val fallback") in 0 until builder.indexOf("dropped: no next air")
        )
        assertTrue(
            "F1: and the drop is still exactly the drop when the source has nothing ahead " +
                "either, so an undatable show behaves as it did",
            builder.contains("if (air == null && fallback == null) {")
        )
    }

    @Test
    fun `the fallback dates the row through the existing tail`() {
        assertTrue(
            "F1: season and episode come from the aired episode, TMDB's own pair when it " +
                "had one - one pair of locals, reassigned the way the stale-date branch " +
                "already reassigns them, not a second row shape",
            builder.contains("var season = air?.seasonNumber ?: fallback?.season ?: continue") &&
                builder.contains("var episode = air?.episodeNumber ?: fallback?.episode ?: continue")
        )
        assertTrue(
            "F1: the date goes through the same correction with no primary to prefer - " +
                "which returns the source's date - and the same parse below it",
            builder.contains("primary = air?.airDate,") &&
                builder.contains("AirDateCorrection.episodeKey(season, episode)") &&
                builder.contains("var epochMs = parseTmdbAirDate(airDateText) ?: continue")
        )
        assertTrue(
            "F1: and then the tail is untouched: the gate, the label, one row per item",
            builder.contains("if (epochMs < startOfToday) continue") &&
                builder.contains("val airLabel = formatAirDateLabel(airDateText)") &&
                builder.count("rows.add(") == 1
        )
        assertTrue(
            "no new dedup rule or row type: the same one-per-show selection sorts the rail",
            builder.contains("return selectUpcomingPerShow(rows).sortedBy { it.airDateEpochMs }")
        )
    }

    @Test
    fun `TMDB still wins whenever it has a date`() {
        assertTrue(
            "the correction's one-directional rule is unchanged: TMDB's date is the " +
                "primary, the source only stands in when there is no primary at all",
            builder.contains("primary = air?.airDate,")
        )
        assertFalse(
            "the fallback is never consulted for a show TMDB already dated",
            builder.contains("if (air != null) {\n                AirDateCorrection.nextAiring")
        )
        assertTrue(
            "and the stale-date branch still runs its own picker for a TMDB date that has " +
                "already aired - the case that existed before this fix",
            builder.contains("if (epochMs < startOfToday && sourceDates.isNotEmpty()) {") &&
                builder.contains("AirDateCorrection.nextAiring(sourceDates, today) ?: continue")
        )
    }

    @Test
    fun `the fallback is downstream of the same admission`() {
        assertTrue(
            "no new admission: the builder's input is still the already-filtered union, and " +
                "nothing here consults the candidate rule",
            builder.contains("items: List<UpNextItem>") &&
                !builder.contains("isCaughtUpUpcomingCandidate")
        )
        assertFalse(
            "and the caught-up repair leg is untouched - it derives from TMDB's detail and " +
                "season list and its contract is that it never invents an episode",
            builder.contains("repairCaughtUpAirDates(")
        )
        val repair = viewModel.slice(
            "private suspend fun repairCaughtUpAirDates(",
            "private suspend fun nextAirForResolvedTarget("
        )
        assertFalse(
            "no TVmaze date was wired into that leg",
            repair.contains("nextAiring") || repair.contains("episodeAirDates")
        )
    }

    // ── F2: the lookup now covers the items that need it ───────────────────

    @Test
    fun `null-air items are looked up instead of skipped`() {
        assertFalse(
            "F2: the guard that made the fallback unreachable - a null-air item has no date " +
                "to correct, so it was skipped, and it is exactly the case the map rescues",
            sources.contains("if (item.nextEpisodeAir == null) continue")
        )
        assertTrue(
            "F2: parent id and the one fetch per show are unchanged",
            sources.contains("val parentId =\n                item.parentId?.takeIf { it.isNotBlank() } ?: continue") &&
                sources.contains("if (targets.containsKey(parentId)) continue")
        )
        assertTrue(
            "F2: the IMDb resolution is the same two-step, and still cached by the table",
            sources.contains("item.tmdbId?.let { tmdbId ->") &&
                sources.contains("tmdbRepository.resolveImdbId(")
        )
        assertTrue(
            "F2: concurrent, one per show, keyed by parent - no new source, key or cache",
            sources.contains("async {") && sources.contains(".awaitAll()") &&
                sources.contains("airDateRepository.episodeAirDates(imdbId)")
        )
        assertTrue(
            "F2: still fail-open per show: an empty map for the rail means TMDB's answer " +
                "stands and an undatable show drops",
            sources.contains("if (targets.isEmpty()) return emptyMap()")
        )
    }

    // ── F3: the fallback is visible in a paste ─────────────────────────────

    @Test
    fun `the fallback writes one line naming the episode and the date`() {
        assertTrue(
            "F3: the line exists, in the file's own diagnostics shape",
            builder.contains("\"fallback air TVmaze for '\$parentId' \"") &&
                builder.contains("S\${fallback.season}E\${fallback.episode} \${fallback.airDate}")
        )
        assertTrue(
            "F3: both sinks, gated like every other UPCOMING_DIAG line",
            builder.contains("if (fallback != null && UPCOMING_DIAGNOSTICS) {") &&
                builder.contains("Log.d(\"UPCOMING_DIAG\", fallbackLine)") &&
                builder.contains("UpcomingDiagTrace.note(fallbackLine)")
        )
        assertTrue(
            "and the drop line is still there for the show neither source can date, so a " +
                "paste distinguishes \"rescued\" from \"dropped\"",
            builder.contains("\"dropped: no next air for '\${item.parentId}' \"") &&
                builder.contains("UpcomingDiagTrace.note(droppedLine)")
        )
        assertTrue(
            "F3: written before the row is built, so a paste reads the rescue then the rail",
            builder.indexOf("fallbackLine") in 0 until builder.indexOf("rows.add(")
        )
    }

    // ── source access ───────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker is missing after $startMarker", end > start)
        return substring(start, end)
    }

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
