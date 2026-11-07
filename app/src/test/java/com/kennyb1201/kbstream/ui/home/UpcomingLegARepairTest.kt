package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upcoming's leg (a): the Continue Watching cards' own air dates.
 *
 * Reported 2026-11-07: five shows sat on Continue Watching with the CORRECT next
 * episode on their cards - Stick S2E3, MobLand, The Simpsons, South Park, The
 * Unbelievable - and none of them appeared in Upcoming, while the rail's two
 * new-season chips still did and the Calendar tab listed all five correctly.
 *
 * The cards name the right episode because that pair is resolved by walking the
 * fresh season list; their `nextEpisodeAir` is the cached series detail's
 * `next_episode_to_air` verbatim, a field that rolls weekly mid-season out of a
 * detail cached for up to 30 days. [buildUpcomingSchedule] drops an item with no
 * air date, silently - so the cards were never given a date to publish.
 *
 * The earlier fix (the Stick one) repaired the Simkl and local caught-up legs and
 * never touched this one. The repair now sits in the Upcoming build, seeded
 * by each card's own pair, and reuses the derivation the other legs already use.
 *
 * The picker is pinned directly; the wiring is pinned by reading the source, the
 * same way this tree pins the rest of the rail logic it cannot launch (see
 * [UpcomingNextAirDerivationTest], whose style this file follows).
 */
class UpcomingLegARepairTest {

    private val today = LocalDate.of(2026, 11, 7)

    // ── T1: the Stick case ──────────────────────────────────────────────────

    @Test
    fun `a null air date on the card is derived from the card's own next episode`() {
        // The reported card: Stick S2E3, caught up on S2E1 (Oct 21) and S2E2
        // (Nov 4). The detail's field had rolled to null, so the card carried no
        // date at all and the rail dropped it.
        val picked = firstUnairedEpisode(
            episodes = season(
                1 to "2026-10-21",
                2 to "2026-11-04",
                3 to "2026-11-11"
            ),
            // The caught-up leg's own rule: the derivation never re-reads local
            // history, the card's pair IS the answer (see nextAirForResolvedTarget).
            watchedEpisodes = emptySet(),
            seasonNumber = 2,
            startEpisode = 3,
            today = today
        )

        assertEquals(3, picked?.episodeNumber)
        assertEquals("2026-11-11", picked?.airDate)
    }

    @Test
    fun `the repair is wired into the Upcoming build, seeded by the card's pair`() {
        val repair = repair()

        assertTrue(
            "the repair must hand the card's own season/episode to the derivation",
            repair.contains("resolvedSeason = season") &&
                repair.contains("resolvedEpisode = episode")
        )
        assertTrue(
            "and the card's stale field is what it repairs, not what it seeds from",
            repair.contains("captured = item.nextEpisodeAir")
        )
        assertTrue(
            "the repaired date is what the item carries on",
            repair.contains("item.copy(") &&
                repair.contains("nextEpisodeAir = nextAirForResolvedTarget(")
        )
    }

    // ── T2: the five-show case ──────────────────────────────────────────────

    @Test
    fun `every caught-up card derives its own next episode`() {
        // The five shows, each with its own upcoming episode and its own date -
        // and each seeded from the pair printed on its card.
        val shows = listOf(
            Triple("Stick", 2 to 3, "2026-11-11"),
            Triple("MobLand", 2 to 2, "2026-11-09"),
            Triple("The Simpsons", 38 to 6, "2026-11-08"),
            Triple("South Park", 29 to 2, "2026-11-12"),
            Triple("The Unbelievable", 2 to 2, "2026-11-19")
        )

        shows.forEach { (name, target, airDate) ->
            val (seasonNumber, episodeNumber) = target
            val picked = firstUnairedEpisode(
                episodes = season(
                    episodeNumber - 2 to "2026-10-26",
                    episodeNumber - 1 to "2026-11-02",
                    episodeNumber to airDate
                ),
                watchedEpisodes = emptySet(),
                seasonNumber = seasonNumber,
                startEpisode = episodeNumber,
                today = today
            )

            assertEquals("$name must not be dropped", episodeNumber, picked?.episodeNumber)
            assertEquals("$name's date is its own episode's", airDate, picked?.airDate)
        }
    }

    @Test
    fun `the five reported shows all reach the repair as caught-up cards`() {
        // The undercount fork is dead (the cards named the right episodes), which
        // is what makes the drop leg (a)'s fault rather than the filter's: a
        // caught-up card passes isCaughtUpForUpcoming and still had no date.
        listOf(26 to 26, 10 to 10, 700 to 700).forEach { (watched, total) ->
            assertTrue(
                "a card at $watched of $total is caught up and belongs on the rail",
                isCaughtUpForUpcoming(
                    UpNextItem(
                        id = "caughtup:tt1",
                        title = "A show",
                        poster = null,
                        badge = UpNextBadge.NEXT_UP,
                        episodesWatched = watched,
                        episodesTotal = total
                    )
                )
            )
        }
    }

    // ── T3: the happy path costs no fetch ───────────────────────────────────

    @Test
    fun `a captured date that names the target is still upcoming and is kept`() {
        // The fast path's own condition, with the target pair: this is the
        // majority of cards on any given week, and it is answered without
        // touching the network.
        assertTrue(
            nextAirStillUpcoming(
                seasonNumber = 2,
                episodeNumber = 3,
                airDate = "2026-11-11",
                watchedEpisodes = emptySet(),
                today = today
            )
        )
    }

    @Test
    fun `the repair itself fetches nothing`() {
        val repair = repair()

        assertFalse(
            "the repair must not read a season list of its own - the derivation it " +
                "delegates to owns that, and only on the stale path",
            repair.contains("getSeasonEpisodes(") || repair.contains("deriveNextUpcomingAir(")
        )
        assertTrue(
            "it delegates to the same function the Simkl leg uses",
            repair.contains("nextAirForResolvedTarget(")
        )
    }

    @Test
    fun `the delegated derivation returns the usable field before fetching`() {
        val derivation = source(SLICE).slice(
            "private suspend fun nextAirForResolvedTarget(",
            "private suspend fun deriveNextUpcomingAir("
        )

        val happyReturn = derivation.indexOf("return captured")
        val fetch = derivation.indexOf("deriveNextUpcomingAir(")

        assertTrue("the usable field must be returned", happyReturn >= 0)
        assertTrue("the derivation must exist", fetch >= 0)
        assertTrue(
            "the happy path returns BEFORE the season walk, so a healthy card pays no fetch",
            happyReturn < fetch
        )
    }

    // ── T4: a genuinely dateless episode stays out ──────────────────────────

    @Test
    fun `an undated next episode is never given a fabricated date`() {
        // The next season is announced but has no dates yet: the picker keeps the
        // episode (an unknown date is not evidence it has aired), and the date
        // it carries cannot become a row - the builder's parse gate drops it.
        val picked = firstUnairedEpisode(
            episodes = listOf(episode(1, airDate = null)),
            watchedEpisodes = emptySet(),
            seasonNumber = 3,
            today = today
        )

        assertEquals(1, picked?.episodeNumber)
        assertNull("no date means no date", parseTmdbAirDate(picked?.airDate))
        assertTrue(
            "and the schedule builder still refuses to publish an unparseable date",
            build().contains("parseTmdbAirDate(airDateText) ?: continue")
        )
    }

    // ── T5: an unresolvable id stays dropped, and nothing throws ─────────────

    @Test
    fun `an unresolvable imdb id leaves the card exactly as it was`() {
        val single = source(SLICE).slice(
            "private suspend fun repairCaughtUpAirDate(",
            "private suspend fun nextAirForResolvedTarget("
        )

        assertTrue(
            "the card's own imdb id is taken as-is when it has one",
            single.contains("startsWith(\"tt\", ignoreCase = true)")
        )

        val resolve = single.indexOf("resolveImdbId(detail.id, \"series\")")
        val giveUp = single.indexOf("?: return item", resolve.coerceAtLeast(0))

        assertTrue("the cached resolution must be asked", resolve >= 0)
        assertTrue(
            "and a missing id is never a reason to invent a date - the item returns " +
                "unchanged, to be dropped as today",
            giveUp > resolve
        )
    }

    @Test
    fun `a movie is never asked for a next episode`() {
        val single = source(SLICE).slice(
            "private suspend fun repairCaughtUpAirDate(",
            "private suspend fun nextAirForResolvedTarget("
        )

        assertTrue(
            "the gate is the app's own series rule, so every dialect of \"series\" " +
                "is repaired and a film is not",
            single.contains("NewEpisodeRules.isSeriesType(item.parentType)")
        )
    }

    @Test
    fun `one unreadable card cannot cost the others their entries`() {
        assertTrue(
            "the per-card repair fails open: a throw leaves that card as it came in, " +
                "and cancellation still propagates",
            repair().contains("safeSuspend { repairCaughtUpAirDate(item) } ?: item")
        )
    }

    // ── T6: the new-season chips are untouched ──────────────────────────────

    @Test
    fun `only the caught-up cards are repaired`() {
        val call = source(SLICE).slice(
            "val caughtUp = items.filter(::isCaughtUpForUpcoming)",
            "}.getOrElse { error ->"
        )

        assertTrue(
            "leg (a) goes through the repair",
            call.contains("repairCaughtUpAirDates(caughtUp)")
        )
        assertFalse(
            "and legs (b) and (c) are not wrapped: their cards are already repaired " +
                "by their own builders, and the new-season chips come from them",
            call.contains("repairCaughtUpAirDates(load")
        )
        assertTrue(
            "both untouched legs are still in the merge",
            call.contains("loadCaughtUpUpcomingItems()") &&
                call.contains("loadLocalCaughtUpUpcomingItems()")
        )
    }

    // ── T7: one entry per show, still ───────────────────────────────────────

    @Test
    fun `a repaired card and a tracker card for one show still collapse`() {
        val repaired = routineCard(
            id = "caughtup:tt0903747",
            parentId = "tt0903747",
            season = 2,
            episode = 3,
            airDate = "2026-11-11"
        )
        val tracker = routineCard(
            id = "simkl:caughtup:tmdb:1396",
            parentId = "tmdb:1396",
            season = 2,
            episode = 3,
            airDate = "2026-11-13"
        )

        val rows = listOf(
            repaired to episodeRow(repaired, airDateEpochMs = epoch(2026, 11, 11)),
            tracker to episodeRow(tracker, airDateEpochMs = epoch(2026, 11, 13))
        )

        val selected = selectUpcomingPerShow(rows)

        assertEquals("a show has exactly one next episode", 1, selected.size)
        assertEquals(
            "the earlier date wins, exactly as before this repair",
            epoch(2026, 11, 11),
            selected.single().airDateEpochMs
        )
    }

    // ── F3: the drop is no longer silent ────────────────────────────────────

    @Test
    fun `a card that still has no air date says so in the log`() {
        val builder = build()

        assertTrue(
            "the silent drop now names the item, under the existing diagnostics flag",
            builder.contains("if (UPCOMING_DIAGNOSTICS)") &&
                builder.contains("\"dropped: no next air for") &&
                builder.contains("\${item.parentId}") &&
                builder.contains("\${item.season}") &&
                builder.contains("\${item.episode}")
        )
        assertTrue(
            "and it is still a drop, not a fabricated row",
            builder.contains("continue")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun repair(): String = source(SLICE).slice(
        "private suspend fun repairCaughtUpAirDates(",
        "private suspend fun nextAirForResolvedTarget("
    )

    private fun build(): String = source(SLICE).slice(
        "private suspend fun buildUpcomingSchedule(",
        "private suspend fun loadSourceAirDates("
    )

    private fun episode(number: Int, airDate: String?): ResolvedEpisode =
        ResolvedEpisode(
            streamId = "s_${number}_$airDate",
            episodeNumber = number,
            name = "Episode $number",
            overview = null,
            thumbnail = null,
            runtimeMinutes = null,
            airDate = airDate,
            voteAverage = null
        )

    /** A season list, written as "episode number to its air date". */
    private fun season(vararg episodes: Pair<Int, String?>): List<ResolvedEpisode> =
        episodes.map { (number, airDate) -> episode(number, airDate) }

    private fun routineCard(
        id: String,
        parentId: String,
        season: Int,
        episode: Int,
        airDate: String
    ): UpNextItem = UpNextItem(
        id = id,
        title = "A show",
        poster = null,
        badge = UpNextBadge.NEXT_UP,
        parentId = parentId,
        parentType = "series",
        season = season,
        episode = episode,
        nextEpisodeAir = TmdbEpisodeAirInfo(
            seasonNumber = season,
            episodeNumber = episode,
            airDate = airDate
        ),
        tmdbId = 1396
    )

    private fun episodeRow(item: UpNextItem, airDateEpochMs: Long): UpcomingEpisode =
        UpcomingEpisode(
            id = item.id,
            parentId = item.parentId.orEmpty(),
            parentType = item.parentType.orEmpty(),
            title = item.title,
            poster = null,
            backdrop = null,
            season = item.season ?: 1,
            episode = item.episode ?: 1,
            airDateEpochMs = airDateEpochMs,
            airDateLabel = "In a few days"
        )

    private fun epoch(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
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
        const val SLICE = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
