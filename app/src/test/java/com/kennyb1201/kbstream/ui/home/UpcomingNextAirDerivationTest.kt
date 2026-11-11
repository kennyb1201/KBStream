package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upcoming's stale-next-episode repair.
 *
 * `next_episode_to_air` comes out of a series detail cached for 12h in memory
 * and up to 30 days on disk, and it rolls every week for a show mid-season - so
 * a cached detail can still name the episode the viewer watched on Nov 5 while
 * the real next one airs Nov 11. Both Upcoming legs previously dropped the card
 * on that evidence, which is why the rail showed new-season premieres (a
 * premiere weeks out does not move) and no mid-season next episodes at all.
 *
 * The fix re-derives the next episode from the season episode list - the
 * fresher fetch Detail already shows - but only on the stale path. The pure
 * picker is pinned directly here; the builder wiring is pinned by reading the
 * source, the same way this tree pins the rest of the player/rail logic it
 * cannot launch (see SouthParkCaughtUpTwinTest).
 */
class UpcomingNextAirDerivationTest {

    private val today = LocalDate.of(2026, 11, 7)

    private fun episode(
        number: Int,
        airDate: String?,
        name: String? = "Episode $number"
    ) = ResolvedEpisode(
        streamId = "s2e$number",
        episodeNumber = number,
        name = name,
        overview = null,
        thumbnail = null,
        runtimeMinutes = null,
        airDate = airDate,
        voteAverage = null
    )

    private fun season2() = listOf(
        episode(1, "2026-10-21"),
        episode(2, "2026-11-04"),
        episode(3, "2026-11-11")
    )

    // ── The picker (§4) ─────────────────────────────────────────────────────

    @Test
    fun `the stick case derives the next episode after the watched run`() {
        val picked = firstUnairedEpisode(
            episodes = season2(),
            watchedEpisodes = setOf(1 to 1, 2 to 1, 2 to 2),
            seasonNumber = 2,
            today = today
        )

        assertEquals(3, picked?.episodeNumber)
        assertEquals("2026-11-11", picked?.airDate)
    }

    @Test
    fun `an episode with no air date is kept`() {
        val picked = firstUnairedEpisode(
            episodes = season2().map { it.copy(airDate = null) },
            watchedEpisodes = setOf(2 to 1, 2 to 2),
            seasonNumber = 2,
            today = today
        )

        assertEquals(
            "an unknown date is never on its own a reason to hide an episode",
            3,
            picked?.episodeNumber
        )
    }

    @Test
    fun `a past-dated episode is skipped for the next future one`() {
        val picked = firstUnairedEpisode(
            episodes = listOf(
                episode(2, "2026-11-04"),
                episode(3, "2026-11-01"),
                episode(4, "2026-11-11")
            ),
            watchedEpisodes = setOf(2 to 2),
            seasonNumber = 2,
            today = today
        )

        assertEquals(4, picked?.episodeNumber)
    }

    @Test
    fun `an episode airing today is still upcoming`() {
        val picked = firstUnairedEpisode(
            episodes = season2(),
            watchedEpisodes = setOf(2 to 1, 2 to 2),
            seasonNumber = 2,
            today = LocalDate.of(2026, 11, 11)
        )

        assertEquals(3, picked?.episodeNumber)
    }

    @Test
    fun `nothing future means no card`() {
        val picked = firstUnairedEpisode(
            episodes = listOf(
                episode(1, "2026-10-21"),
                episode(2, "2026-11-04"),
                episode(3, "2026-11-01")
            ),
            watchedEpisodes = setOf(2 to 1, 2 to 2),
            seasonNumber = 2,
            today = today
        )

        assertNull("a finished show must not get a phantom card", picked)
    }

    @Test
    fun `the walk starts at the tracker target`() {
        val picked = firstUnairedEpisode(
            episodes = season2(),
            watchedEpisodes = emptySet(),
            seasonNumber = 2,
            startEpisode = 3,
            today = today
        )

        assertEquals(
            "an unwatched episode BEFORE Simkl's next is not what the rail advertises",
            3,
            picked?.episodeNumber
        )
    }

    @Test
    fun `a season boundary is the second walk`() {
        // The local leg fetches the next season only when the detail says it
        // exists; this is that second list, seeded from the top.
        val picked = firstUnairedEpisode(
            episodes = listOf(episode(1, "2026-11-12")),
            watchedEpisodes = emptySet(),
            seasonNumber = 3,
            today = today
        )

        assertEquals(1, picked?.episodeNumber)
    }

    @Test
    fun `a row with no real episode number is never an episode`() {
        val picked = firstUnairedEpisode(
            episodes = listOf(episode(0, "2026-11-11", name = null), episode(1, "2026-11-12")),
            watchedEpisodes = emptySet(),
            seasonNumber = 2,
            today = today
        )

        assertEquals(1, picked?.episodeNumber)
    }

    // ── The local leg's wiring (§2) ─────────────────────────────────────────

    @Test
    fun `the local builder derives instead of refusing a stale next episode`() {
        val builder = activity.slice(
            "private suspend fun buildCaughtUpUpcomingCard(",
            "private suspend fun upcomingAirForCaughtUp("
        )

        assertTrue(
            "the card's air info now goes through the repair",
            builder.contains("upcomingAirForCaughtUp(detail, candidate)")
        )
        assertFalse(
            "the old `nextEpisodeToAir ?: return null` is the hole: it dropped every " +
                "mid-season card whose cached field had rolled",
            builder.contains("detail.nextEpisodeToAir")
        )
        assertTrue(
            "and the derived episode is what the card carries - or, when nothing can be " +
                "named, no pair at all, which the schedule builder dates from its second " +
                "source or drops with its own line (see UpcomingLocalNullAirCardContractTest)",
            builder.contains("season = air?.seasonNumber") &&
                builder.contains("episode = air?.episodeNumber") &&
                builder.contains("nextEpisodeAir = air")
        )
    }

    @Test
    fun `a healthy next episode takes no season fetch`() {
        val repair = activity.slice(
            "private suspend fun upcomingAirForCaughtUp(",
            "private suspend fun nextAirForResolvedTarget("
        )

        val happyReturn = repair.indexOf("return detailAir")
        val derivation = repair.indexOf("deriveNextUpcomingAir(")

        assertTrue("the usable field must be returned", happyReturn >= 0)
        assertTrue("the derivation must exist", derivation >= 0)
        assertTrue(
            "the happy path has to return BEFORE the derivation, so a healthy card pays " +
                "no extra fetch",
            happyReturn < derivation
        )
        assertTrue(
            "TMDB's own rule decides usability",
            repair.contains("nextAirStillUpcoming(")
        )
    }

    @Test
    fun `a null next episode seeds from the last aired season`() {
        val repair = activity.slice(
            "private suspend fun upcomingAirForCaughtUp(",
            "private suspend fun nextAirForResolvedTarget("
        )

        assertTrue(
            "the stale field's season, else where a null field leaves the show",
            repair.contains("detailAir?.seasonNumber") &&
                repair.contains("?: detail.lastEpisodeToAir?.seasonNumber")
        )
        assertTrue(
            "the season fetch is addressed by IMDb id, from the card or the cached resolution",
            repair.contains("startsWith(\"tt\", ignoreCase = true)") &&
                repair.contains("resolveImdbId(detail.id, \"series\")")
        )
    }

    @Test
    fun `the derivation is bounded to two seasons and gated`() {
        val derive = activity.slice(
            "private suspend fun deriveNextUpcomingAir(",
            "private suspend fun seriesDetailFor("
        )

        assertTrue(
            "the next season is tried only when the detail says it exists",
            derive.contains("(startSeason + 1)") &&
                derive.contains("detail.seasons.any { it.seasonNumber == number }") &&
                derive.contains("listOfNotNull(startSeason, nextSeason)")
        )
        assertEquals(
            "one season list per tried season, and never a third",
            1,
            Regex("getSeasonEpisodes\\(").findAll(derive).count()
        )
        assertTrue(
            "the fetch is gated like every other TMDB lookup on this path",
            derive.indexOf("tmdbLookupSemaphore.withPermit {") in 0 until derive.indexOf(
                "getSeasonEpisodes("
            )
        )
    }

    // ── The Simkl leg's wiring (§3) ─────────────────────────────────────────

    @Test
    fun `the tracker leg repairs its air info against the resolved target`() {
        val repair = activity.slice(
            "private suspend fun nextAirForResolvedTarget(",
            "private suspend fun deriveNextUpcomingAir("
        )

        assertTrue(
            "the detail's field is taken only when it names the target and still passes",
            repair.contains("captured.seasonNumber == targetSeason") &&
                repair.contains("captured.episodeNumber == targetEpisode") &&
                repair.contains("nextAirStillUpcoming(")
        )
        assertTrue(
            "and a leg with no resolved target derives nothing",
            repair.contains("resolvedSeason ?: return captured") &&
                repair.contains("resolvedEpisode ?: return captured")
        )
        assertTrue(
            "otherwise the season list is asked from the target onward",
            repair.contains("startEpisode = targetEpisode")
        )
        assertTrue(
            "and nothing found means the old capture stands - the leg never invents an episode",
            repair.contains("?: captured")
        )
    }

    @Test
    fun `the tracker card keeps its pair and carries the repaired air info`() {
        val builder = activity.slice(
            "private suspend fun buildSimklUpNextItem(",
            "private suspend fun localHistoryParentIds("
        )

        val repairCall = builder.indexOf("nextAirForResolvedTarget(")
        val cardAir = builder.indexOf("nextEpisodeAir = capturedNextAirInfo")

        assertTrue("the repair must be wired in", repairCall >= 0)
        assertTrue("the card must still carry the capture", cardAir >= 0)
        assertTrue(
            "the repair runs after the target pair is resolved, before the card is built",
            repairCall < cardAir
        )
        assertTrue(
            "the card's pair stays Simkl's own answer",
            builder.contains("season =\n                                resolvedSeason") ||
                builder.contains("resolvedSeason,") ||
                builder.contains("season = resolvedSeason")
        )
    }

    // ── Untouched (§5) ──────────────────────────────────────────────────────

    @Test
    fun `the detail caches and the schedule builder are not changed by this fix`() {
        val repository = source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")

        assertTrue(
            "the 12h memory copy stays",
            repository.contains("private val detailCacheTtlMs = 12L * 60L * 60L * 1000L")
        )
        assertTrue(
            "and so does the 30-day disk copy: this fix works WITH the stale cache",
            repository.contains("private val detailCacheDiskTtlMs = 30L * 24L * 60L * 60L * 1000L")
        )
        assertTrue(
            "the schedule builder still gates on the card's air info",
            activity.contains("val air = item.nextEpisodeAir") &&
                activity.contains("if (air == null) {")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private val activity: String by lazy {
        source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
    }

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
}
