package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local Upcoming leg's null-air card: a show TMDB has no next episode for
 * still reaches the rail's builder, which is the one place the air date is
 * decided.
 *
 * Reported 2026-11-11: Universal Basic Guys was caught up on every tracker and
 * still missing from Upcoming, while the tracker-independence work said any
 * source feeds the rail. The union was right - the show WAS a candidate. The
 * gap was one step later, in this leg's card builder: `upcomingAirForCaughtUp`
 * knows only TMDB (`next_episode_to_air` is null, the S3 listing held the six
 * watched episodes, season 4 is not in the detail), it answered null, and the
 * builder refused the card. The TVmaze fallback lives in
 * [buildUpcomingSchedule], which only sees cards that REACH it - so a
 * TMDB-dateless local show was dropped before the second source could date it.
 *
 * The tracker leg never had this hole: [buildSimklUpNextItem] builds its card
 * with the item's own (possibly null) pair and a possibly-null capture. The fix
 * is the same shape on the local leg, and the rail's fallback, drop line, dedup
 * and one-per-show selection are unchanged - their wiring is pinned by
 * [UpcomingTvmazeFallbackContractTest] and is referenced here, not duplicated.
 *
 * Source-reading style, like the rest of this module's contracts: there is no
 * ViewModel/Compose harness here. Each test below fails against the 0.9.223
 * source, so this file pins the change rather than the neighbouring text.
 */
class UpcomingLocalNullAirCardContractTest {

    private val viewModel by lazy { source(VIEW_MODEL) }

    /** The local leg's card builder, whole. */
    private val builder: String by lazy {
        viewModel.slice(
            "private suspend fun buildCaughtUpUpcomingCard(",
            "private suspend fun upcomingAirForCaughtUp("
        )
    }

    /** TMDB's answer for one candidate - still the only source it consults. */
    private val derivation: String by lazy {
        viewModel.slice(
            "private suspend fun upcomingAirForCaughtUp(",
            "private suspend fun repairCaughtUpAirDates("
        )
    }

    /** The tracker leg's builder: the shape this fix mirrors. */
    private val trackerBuilder: String by lazy {
        viewModel.slice(
            "private suspend fun buildSimklUpNextItem(",
            "private suspend fun localHistoryParentIds("
        )
    }

    /** The tracker leg's fields, whitespace collapsed: they are stacked one per line. */
    private val trackerFields by lazy { trackerBuilder.replace(Regex("\\s+"), " ") }

    /** The second-source lookup the builder consults. */
    private val sources: String by lazy {
        viewModel.slice(
            "private suspend fun loadSourceAirDates(",
            "private suspend fun publishInstantUpNextSnapshot("
        )
    }

    /** The rail's builder, where the air date is finally decided. */
    private val schedule: String by lazy {
        viewModel.slice(
            "private suspend fun buildUpcomingSchedule(",
            "private suspend fun fallbackUpcomingEpisodeTitle("
        )
    }

    // ── F1: a null derivation is a card, not a refusal ─────────────────────

    @Test
    fun `a null next episode still builds a card with no pair and no date`() {
        assertFalse(
            "F1: the hole - `?: return null` on the derivation dropped Universal Basic " +
                "Guys before any second source could see it",
            builder.contains(
                "upcomingAirForCaughtUp(detail, candidate)\n                ?: return null"
            )
        )
        assertTrue(
            "F1: the derivation is asked and its answer kept as nullable, exactly like the " +
                "tracker leg keeps its capture",
            builder.contains("val air =\n            upcomingAirForCaughtUp(detail, candidate)")
        )
        assertTrue(
            "F1: the card carries no invented pair - null season and episode until a " +
                "source supplies them, so no phantom S1 E1 can come from this path",
            builder.contains("season = air?.seasonNumber") &&
                builder.contains("episode = air?.episodeNumber") &&
                builder.contains("nextEpisodeAir = air")
        )
    }

    @Test
    fun `the tracker leg already builds that card, and this mirrors it`() {
        assertTrue(
            "F1: the reference shape - Simkl's card takes the item's own pair, null when " +
                "the tracker has none",
            trackerFields.contains("season = resolvedSeason,") &&
                trackerFields.contains("episode = resolvedEpisode,") &&
                trackerFields.contains("nextEpisodeAir = capturedNextAirInfo,")
        )
        assertFalse(
            "F1: and the tracker leg never refuses a card for a null capture - the builder " +
                "below is where that is decided",
            trackerBuilder.contains("capturedNextAirInfo ?: return null")
        )
    }

    // ── F2: every guard that must still refuse, still refuses ──────────────

    @Test
    fun `an unresolvable show and a show that is not caught up still get nothing`() {
        assertTrue(
            "F2: the show gate is first and unchanged - no detail, no card",
            builder.indexOf("seriesDetailFor(candidate.parentId)\n                ?: return null") in
                0 until builder.indexOf("val air =")
        )
        assertTrue(
            "F2: and the phantom-card guard runs on the null-air path too - it is the last " +
                "word before the card is assembled",
            builder.contains("LocalSeriesProgress.isCaughtUpOnLastAired(") &&
                builder.contains("if (!caughtUp) return null")
        )
        assertTrue(
            "F2: the caught-up verdict comes AFTER the air block, so a dateless show is " +
                "gated by the same rule as a dated one",
            builder.indexOf("val air =") in 0 until builder.indexOf("isCaughtUpOnLastAired(")
        )
        assertTrue(
            "F2: title resolution unchanged - still a refusal when nothing can be named",
            builder.contains("upNextDisplayTitleOrNull(\n                candidate.title,") &&
                builder.contains("?: return null")
        )
    }

    @Test
    fun `a stale or past TMDB air still refuses the card`() {
        assertTrue(
            "F2: the re-check survives, guarded only by the air being non-null - there is " +
                "nothing to check when TMDB named no episode",
            builder.contains("air != null &&\n            !nextAirStillUpcoming(")
        )
        assertTrue(
            "F2: the re-check still runs before the caught-up verdict, as it did",
            builder.indexOf("nextAirStillUpcoming(") in 0 until
                builder.indexOf("isCaughtUpOnLastAired(")
        )
    }

    @Test
    fun `the derivation itself is still TMDB-only`() {
        assertTrue(
            "invariant: the second source stays in the builder - this function knows the " +
                "detail and the season list, nothing else",
            !derivation.contains("nextAiring") &&
                !derivation.contains("episodeAirDates") &&
                !derivation.contains("sourceDates")
        )
    }

    // ── F3: the builder already dates it - verified, not rebuilt ───────────

    @Test
    fun `the card carries the identity the second-source lookup needs`() {
        assertTrue(
            "F3: parentId and parentType ride on the card, which is what puts it in the " +
                "lookup's target set",
            builder.contains("parentId = candidate.parentId") &&
                builder.contains("parentType = \"series\"")
        )
        assertTrue(
            "F3: and the resolved TMDB id does, so a non-`tt` parent still resolves to an " +
                "IMDb id at that one site - no second lookup was added",
            builder.contains("tmdbId = detail.id") &&
                sources.contains("item.tmdbId?.let { tmdbId ->") &&
                sources.contains("tmdbRepository.resolveImdbId(")
        )
        assertTrue(
            "F3: null-air items are targets, not skips - the F2 of the fallback commit, " +
                "still in place",
            !sources.contains("if (item.nextEpisodeAir == null) continue")
        )
    }

    @Test
    fun `the builder still dates the null-air card or names the drop`() {
        assertTrue(
            "F3: the fallback fires on exactly this card - a null air - at the one place " +
                "the date is read",
            schedule.contains("val fallback = if (air == null) {") &&
                schedule.contains("AirDateCorrection.nextAiring(sourceDates, today)")
        )
        assertTrue(
            "F3: and the drop stays for the show neither source can date: no card, one line",
            schedule.contains("if (air == null && fallback == null) {") &&
                schedule.contains("\"dropped: no next air for '\${item.parentId}' \"")
        )
        assertTrue(
            "F3: the pair the fallback supplies becomes the row's locals, so the null-air " +
                "card is a normal row once dated",
            schedule.contains("var season = air?.seasonNumber ?: fallback?.season ?: continue") &&
                schedule.contains("var episode = air?.episodeNumber ?: fallback?.episode ?: continue")
        )
        assertTrue(
            "F3: one row per show still collapses a null-air local card with a null-air " +
                "tracker card - the same selection as for two dated ones",
            schedule.contains("return selectUpcomingPerShow(rows).sortedBy { it.airDateEpochMs }")
        )
    }

    @Test
    fun `the stale-date repair pays nothing for a null-air card`() {
        val repair = viewModel.slice(
            "private suspend fun repairCaughtUpAirDates(",
            "private suspend fun nextAirForResolvedTarget("
        )
        assertTrue(
            "F3: the repair returns a pairless card unchanged, before any detail lookup or " +
                "season fetch - so admitting these cards adds no network cost",
            repair.indexOf("val season = item.season ?: return item") in
                0 until repair.indexOf("seriesDetailFor(")
        )
    }

    // ── F4: the stale contracts say what the code now does ─────────────────

    @Test
    fun `the docs no longer promise that a null air means no card`() {
        // Both docs sit ABOVE the functions, so the slices start below them.
        assertTrue(
            "F4: the builder's own doc refuses for 'not caught up' and 'cannot be resolved' " +
                "only",
            viewModel.contains("turns out not to be caught up or cannot be resolved") &&
                !viewModel.contains("has no next episode to air, or cannot be")
        )
        assertTrue(
            "F4: and the derivation's doc says where null now leads",
            viewModel.contains("the caller builds a null-air card") &&
                !viewModel.contains("so a finished show still gets no phantom card")
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
