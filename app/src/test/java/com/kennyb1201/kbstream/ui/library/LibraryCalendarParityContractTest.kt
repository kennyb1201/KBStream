package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Calendar catching up with the Upcoming rail: four findings, one shape.
 *
 * The rail took three fixes in a week (the TVmaze fallback, the local-leg
 * null-air card, the finale vetoes) and the calendar took none of them, though
 * it reads the same data. This file pins each:
 *
 *  (a) episodes the second source dates and TMDB does not list are PLACED -
 *      Universal Basic Guys S3E7 (Nov 15) / E8 (Nov 22), which the rail had and
 *      November did not;
 *  (b) FINALE is labelled only when the payload's last episode is the season's
 *      DECLARED last;
 *  (c) a failed detail lookup is not an answer - it is retried on the next
 *      month build and never cached for the session;
 *  (d) a calendar row offers the same air reminder a poster's menu does.
 *
 * (a) and (b) are exercised through the real month builder, because they are
 * pure functions; (c) and (d) are pinned where they are decided, in the
 * ViewModel's wiring and the pane, the way the other calendar tests work.
 */
class LibraryCalendarParityContractTest {

    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)
    private val today = LocalDate.of(2026, 11, 10)

    private fun episode(number: Int, airDate: String?, name: String? = "Episode $number") =
        ResolvedEpisode(
            streamId = "s3e$number",
            episodeNumber = number,
            name = name,
            overview = null,
            thumbnail = null,
            runtimeMinutes = 40,
            airDate = airDate,
            voteAverage = null
        )

    /** The live case's shape: six episodes on TMDB, two more dated by TVmaze. */
    private fun universalBasicGuys(
        declaredCount: Int? = 6,
        nextEpisode: CalendarNextEpisode? = null
    ) = CalendarShow(
        showId = "tmdb:250307",
        openId = "tt32129202",
        title = "Universal Basic Guys",
        seasons = mapOf(
            3 to (1..6).map { number ->
                episode(number, airDate = "2026-10-%02d".format(number + 4))
            }
        ),
        sourceDates = mapOf(
            // A payload row: must not be placed twice.
            "3:1" to "2026-10-05",
            "3:7" to "2026-11-15",
            "3:8" to "2026-11-22",
            // Out of the month: must not be placed.
            "3:9" to "2026-12-06"
        ),
        nextEpisode = nextEpisode,
        declaredEpisodeCounts = declaredCount?.let { mapOf(3 to it) }.orEmpty()
    )

    // ── (a) the rail's fallback, for the calendar ───────────────────────────

    @Test
    fun `second-source episodes the payload does not list are placed, in the month`() {
        val days = calendarEpisodesFor(november, universalBasicGuys(), today)

        val seventh = days[LocalDate.of(2026, 11, 15)].orEmpty()
            .filter { it.season == 3 && it.episode == 7 }
        assertEquals(
            "S3E7 is dated by the second source and absent from TMDB's payload, " +
                "which is exactly the row that used to be missing while the rail " +
                "advertised it",
            1,
            seventh.size
        )
        assertEquals("2026-11-15", seventh.single().airDate.toString())
        assertNull(
            "the date map carries no titles, so the row must not invent one",
            seventh.single().episodeTitle
        )
        assertFalse(
            "and it is never a finale: nothing here knows how long the season runs",
            seventh.single().isFinale
        )

        assertNotNull(
            "every in-month episode is placed, not just the next one - the " +
                "calendar is a month view, and UBG's E8 airs on Nov 22",
            days[LocalDate.of(2026, 11, 22)].orEmpty()
                .firstOrNull { it.season == 3 && it.episode == 8 }
        )
    }

    @Test
    fun `the fallback adds rows and never changes a payload row`() {
        val payloadMonth = calendarEpisodesFor(october, universalBasicGuys(), today)
            .values.flatten()
            .filter { it.season == 3 }

        assertEquals(
            "one row per (season, episode): a second-source date for an episode " +
                "the payload already dates must not draw it twice",
            1,
            payloadMonth.count { it.episode == 1 }
        )
        assertNull(
            "an out-of-month second-source date is not placed, in either direction",
            calendarEpisodesFor(october, universalBasicGuys(), today)
                .values.flatten().firstOrNull { it.episode == 7 }
        )

        val rows = calendarEpisodesFor(november, universalBasicGuys(), today).values.flatten()
        assertEquals(
            "and November is exactly the two second-source rows",
            2,
            rows.size
        )
        assertNull(
            "an out-of-month second-source date is not placed",
            rows.firstOrNull { it.episode == 9 }
        )
    }

    @Test
    fun `an episode one is a second-source premiere, and the payload keeps its own badges`() {
        val show = universalBasicGuys().copy(
            seasons = emptyMap(),
            sourceDates = mapOf("4:1" to "2026-11-20")
        )
        val row = calendarEpisodesFor(november, show, today)
            .getValue(LocalDate.of(2026, 11, 20))
            .single()

        assertTrue(
            "an episode numbered 1 opens its season, wherever its date came from",
            row.isPremiere
        )
        assertFalse(row.isFinale)

        val payloadFinale = calendarEpisodesFor(october, universalBasicGuys(declaredCount = 6), today)
            .values.flatten()
            .first { it.episode == 6 }
        assertTrue(
            "a payload row whose season is complete keeps its FINALE - the " +
                "fallback adds placements, it does not re-label anything",
            payloadFinale.isFinale
        )
    }

    // ── (b) FINALE only for a season TMDB says is complete ──────────────────

    @Test
    fun `a payload short of the declared count is not a finale`() {
        assertFalse(
            "the payload ends at six of eight: TMDB is still adding episodes, so " +
                "the sixth is not the season's last",
            calendarSeasonFinale(season = 3, payloadLast = 6, declaredCount = 8, nextSeason = null)
        )
        assertFalse(
            "and the badge must not come back through the month builder",
            calendarEpisodesFor(
                october,
                universalBasicGuys(declaredCount = 8),
                today
            ).values.flatten().first { it.episode == 6 }.isFinale
        )
    }

    @Test
    fun `the declared last is a finale, and an unknown count keeps the old behaviour`() {
        assertTrue(
            "payload last == declared count: the season is over",
            calendarSeasonFinale(season = 3, payloadLast = 6, declaredCount = 6, nextSeason = null)
        )
        assertTrue(
            "no declared count means no veto - TMDB has told us nothing to weigh " +
                "the payload against",
            calendarSeasonFinale(season = 3, payloadLast = 6, declaredCount = null, nextSeason = null)
        )
        assertTrue(
            "and the month builder agrees with both",
            calendarEpisodesFor(october, universalBasicGuys(declaredCount = null), today)
                .values.flatten().first { it.episode == 6 }.isFinale
        )
    }

    @Test
    fun `a next pointer for the same season vetoes the finale`() {
        val pointer = CalendarNextEpisode(
            season = 3,
            episode = episode(number = 7, airDate = "2026-11-15")
        )
        assertFalse(
            "the detail still points at an episode of this season, which is TMDB " +
                "saying something later is coming - whatever the count says",
            calendarSeasonFinale(season = 3, payloadLast = 6, declaredCount = 6, nextSeason = 3)
        )
        assertTrue(
            "a pointer into the NEXT season does not veto this one's finale",
            calendarSeasonFinale(season = 3, payloadLast = 6, declaredCount = 6, nextSeason = 4)
        )
    }

    // ── (c) a failed show is not resolved for the session ───────────────────

    @Test
    fun `a failed lookup is left unresolved and counted`() {
        val note = section(
            VIEW_MODEL,
            "private fun noteResolved(",
            "/** Puts a built month on screen"
        )

        val guard = note.indexOf("if (result.isFailure) {")
        assertTrue("a result that failed is checked at all", guard >= 0)
        assertTrue(
            "a failed lookup must return BEFORE the candidate is filed as " +
                "answered: resolving it is what made one TMDB blip permanent " +
                "for the process",
            guard < note.indexOf("build.resolved += candidateId")
        )
        assertTrue(
            "and it is counted, so the finished month knows it lost shows",
            note.contains("build.failed++")
        )
        assertTrue(
            "the partial-publish gate is untouched - a retrying build still " +
                "composes at most every PARTIAL_PUBLISH_MS",
            note.contains("PARTIAL_PUBLISH_MS")
        )
    }

    @Test
    fun `a miss is no longer remembered for the session`() {
        val detail = section(
            VIEW_MODEL,
            "private suspend fun detailFor(",
            "     * The watched union for the shows on screen"
        )

        assertFalse(
            "the session cache must not hold a null: a transient failure read " +
                "back as \"TMDB has nothing\" for the life of the process",
            detail.contains("details[candidateId] = resolved")
        )
        assertTrue(
            "only a detail that came back is stored",
            detail.contains("resolved.getOrNull()?.let { details[candidateId] = it }")
        )
        assertTrue(
            "and the result is returned WITH its failure, so the caller can " +
                "tell a failed read from an answered one",
            detail.contains("): Result<TmdbDetail?>") &&
                detail.contains("return resolved")
        )
    }

    @Test
    fun `a month built with failures is not cached as complete`() {
        val resolve = section(
            VIEW_MODEL,
            "private suspend fun resolveMonth(",
            "     * Files one candidate's answer"
        )

        assertTrue(
            "failures are reset for this attempt, so a stale count cannot keep " +
                "a now-clean month out of the cache (or cache a failed one)",
            resolve.contains("build.failed = 0")
        )
        assertTrue(
            "the cache write and the progress drop are behind that gate - a " +
                "month that lost shows must land in loadMonth again and retry",
            resolve.contains("if (build.failed == 0) {") &&
                resolve.contains("monthCache[month] = built") &&
                resolve.contains("progress.remove(month)")
        )
        assertTrue(
            "the show set is still what fills the build: only unresolved " +
                "candidates are asked about again",
            resolve.contains("showIds.filterNot { it in build.resolved }")
        )
        assertTrue(
            "and the month that did land is still published",
            resolve.contains("publish(built, landingDay, keepSelection = moved)")
        )
    }

    @Test
    fun `the progress carries the failure count`() {
        val progress = section(
            VIEW_MODEL,
            "private class MonthProgress {",
            "private val progress = LinkedHashMap"
        )
        assertTrue(
            "MonthProgress is where a build's outcome lives, so the failure " +
                "count belongs beside resolved/shows",
            progress.contains("var failed = 0")
        )
    }

    // ── (d) the row offers the reminder its poster offers ───────────────────

    @Test
    fun `a calendar row's hold opens the same reminder the poster menu offers`() {
        val menu = section(
            PANE,
            "private fun CalendarEpisodeMenu(",
            " * One episode row's content, shared by the two form factors"
        )

        assertTrue(
            "the row's ids are put through the poster menu's own derivation",
            menu.contains("AirReminderRules.showIdFor(")
        )
        assertTrue(
            "the same store, read the same way: the sheet only exists while it " +
                "is open, so the ON state is as fresh as the row",
            menu.contains("AirReminderStore(context).isFlagged(reminderShowId)")
        )
        assertTrue(
            "the same toggle - per SHOW, so one flag covers future airings",
            menu.contains("AirReminderStore(context).toggle(reminderShowId)")
        )
        assertTrue(
            "the same two labels as the poster row",
            menu.contains("\"Reminder on ✓\"") &&
                menu.contains("\"Remind me when it airs\"")
        )
        assertTrue(
            "and the same re-arm of the new-episode round: the pref alone " +
                "schedules nothing",
            menu.contains(
                "NewEpisodeWorker.syncScheduleForPrefs(context, runImmediate = nowOn)"
            )
        )
        assertTrue(
            "offered as a menu row through the shared context menu, so the " +
                "sheet, its focus trap and its Back handling are the app's own",
            menu.contains("PosterContextAction(") && menu.contains("PosterContextMenu(")
        )
    }

    @Test
    fun `both form factors can hold the row open`() {
        val row = section(
            PANE,
            "private fun CalendarEpisodeRow(",
            " * The Calendar row's menu: the air reminder for the SHOW"
        )

        assertTrue(
            "a phone holds the row: same gesture as a poster, same combined clickable",
            row.contains("combinedClickable(onClick = onClick, onLongClick = onLongPress)")
        )
        assertTrue(
            "a television holds Select, through the shared helper for surfaces " +
                "that cannot use KBCard - no third gesture is invented",
            row.contains("rememberLongPressModifier(onLongClick = onLongPress)")
        )
        assertTrue(
            "the tap is untouched: it still opens Detail, the reminder is an " +
                "additional action",
            row.contains("onClick = onClick")
        )

        val list = section(
            PANE,
            "private fun CalendarDayList(",
            "/** A plain line of copy where a list would be. */"
        )
        assertTrue(
            "the menu state is held above both arrangements, because it is a " +
                "full-screen overlay",
            section(
                PANE,
                "private fun CalendarPaneLayout(",
                "/** The header, the weekday initials and the month grid, as one column. */"
            ).contains("var reminderEpisode by remember")
        )
        assertTrue(
            "and every row can ask for it",
            list.contains("onLongPress = { onEpisodeMenu(episode) }")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun source(path: String): String =
        File(findMainSourceRoot(), path).readText()

    /** The text between two anchors, so a slice cannot run into a neighbour. */
    private fun section(path: String, anchor: String, end: String): String {
        val source = source(path)
        val start = source.indexOf(anchor)
        assertTrue("anchor not found: $anchor", start >= 0)
        val to = source.indexOf(end, start + anchor.length)
        assertTrue("$end must follow $anchor", to > start)
        return source.substring(start, to)
    }

    private fun findMainSourceRoot(): File {
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
        const val PANE = "com/kennyb1201/kbstream/ui/library/LibraryCalendarPane.kt"
        const val VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/library/LibraryCalendarViewModel.kt"
    }
}
