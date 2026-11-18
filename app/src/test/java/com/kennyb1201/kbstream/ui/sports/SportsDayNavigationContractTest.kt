package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub's date navigation, pinned where a device would find it (spec audit
 * 2026-11-18, Sports M2).
 *
 * The rule itself is JVM-tested in [SportsDayRulesTest]; this is the WIRING, the
 * half a source pin is good for: the repository has a per-day window, the
 * ViewModel asks it for the chosen day and stands the live tick down on a day
 * that cannot move, and both surfaces draw the same three chips and the
 * day-aware empty sentence. Each of those is a silent half-fix if it goes
 * missing - a chip that reloads nothing, a tick polling a frozen slate, an empty
 * pane saying "right now" about a day the viewer picked.
 */
class SportsDayNavigationContractTest {

    @Test
    fun `the repository can read one chosen day`() {
        val repo = source(REPO)
        val fn = repo.substringAfter("suspend fun scoreboardForDay(")
            .substringBefore("suspend fun tournamentEvents(")
        assertTrue(
            "the window is the dates= parameter, not the default window",
            fn.contains("fetch(leaguePath, day)")
        )
        assertTrue(
            "cached per (league, day) on its own TTL - a future slate can move",
            fn.contains("val key = \"\$leaguePath@\$day\"") &&
                fn.contains("DAY_WINDOW_TTL_MS") &&
                repo.contains("private const val DAY_WINDOW_TTL_MS = 5L * 60_000L")
        )
        assertTrue(
            "an empty answer is the honest answer and is cached, while a failed " +
                "read keeps the previous list",
            fn.contains("if (fetched == null) {") && fn.contains("return cached?.value.orEmpty()")
        )
        assertTrue(
            "college football's FCS half is read for the same day",
            fn.contains("EspnCacheRules.mergeSubdivision(fetched, fcsWindow(day).orEmpty())")
        )
    }

    @Test
    fun `the viewmodel asks for the chosen day and nothing else`() {
        val vm = source(VM)
        assertTrue(
            "the day is an offset, so tomorrow is still tomorrow after midnight",
            vm.contains("private val _selectedDay = MutableStateFlow(SportsDayRules.TODAY)") &&
                vm.contains("val selectedDay: StateFlow<Int> = _selectedDay.asStateFlow()")
        )
        val setDay = vm.substringAfter("fun setDay(").substringBefore("fun pauseLivePoll()")
        assertTrue(
            "the previous day's cards go rather than sitting under the new chip",
            setDay.contains("_sections.value = emptyList()") &&
                setDay.contains("_matches.value = emptyMap()")
        )
        assertTrue("and the day is reloaded", setDay.contains("refresh()"))

        val fetch = vm.substringAfter("private suspend fun fetchSection(")
            .substringBefore("val kept = SportsFinalWindowRules")
        assertTrue(
            "today keeps the old call with its carry-over windows",
            fetch.contains("espn.scoreboard(league.path)")
        )
        assertTrue(
            "any other day goes through the per-day window",
            fetch.contains("espn.scoreboardForDay(") &&
                fetch.contains("SportsDayRules.dayParam(day, System.currentTimeMillis())")
        )
        assertTrue(
            "TheSportsDB answers a round, so another day filters it here",
            fetch.contains("SportsDayRules.containsMs(day, now, game.dateMs)")
        )
        assertTrue(
            "a tournament is read by clock and has no per-day window, so another " +
                "day shows none rather than today's sessions",
            fetch.contains("day != SportsDayRules.TODAY -> {")
        )

        val poll = vm.substringAfter("private fun syncLivePoll()")
            .substringBefore("private suspend fun refreshLeagues(")
        assertTrue(
            "a day that cannot have a game in play arms no live tick",
            poll.contains("if (!SportsDayRules.pollsLive(_selectedDay.value)) return")
        )
    }

    @Test
    fun `both surfaces draw the same three chips`() {
        listOf(TV to "private fun SportsDayRow(", PHONE to "private fun SportsDayRowMobile(")
            .forEach { (path, header) ->
                val screen = source(path)
                assertTrue("$path must define its day row", screen.contains(header))
                val row = screen.substringAfter(header).substringBefore("@Composable")
                assertTrue(
                    "$path draws exactly the rule's three days, in its order",
                    row.contains("SportsDayRules.offsets")
                )
                assertTrue(
                    "$path wires the row to the day switch",
                    screen.contains("viewModel.setDay(offset)")
                )
                assertTrue(
                    "$path scopes the tabs under the day chips",
                    screen.contains("selectedOffset = selectedDay")
                )
            }
    }

    @Test
    fun `a day switch shows the wait, not tomorrow's empty sentence`() {
        // The switch empties the sections; the favourites tab's section is
        // DERIVED, so it is non-null even when empty and would otherwise fall
        // straight through to "none of your teams are playing tomorrow" for the
        // length of the load.
        listOf(TV, PHONE).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path gives an in-flight day switch its own loading branch",
                screen.contains("isLoading && section.games.isEmpty() ->")
            )
        }
        val phone = source(PHONE)
        assertTrue(
            "and the wait names the day it is waiting for",
            phone.contains("Fetching \${SportsDayRules.label(selectedDay).lowercase()}'s games…")
        )
    }

    @Test
    fun `an empty slate names the day, on both surfaces`() {
        listOf(TV, PHONE).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path asks the rule for the empty sentence rather than hardcoding " +
                    "\"right now\"",
                screen.contains("SportsDayRules.emptyMessage(") &&
                    screen.contains("SportsDayRules.emptyFavoritesMessage(")
            )
            assertFalse(
                "$path must not keep the old day-blind league sentence",
                screen.contains("\"No \${section.league.label} games on the schedule \"")
            )
        }
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
        throw IllegalStateException("no src/main/java above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val VM = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val REPO = "com/kennyb1201/kbstream/data/sports/EspnSportsRepository.kt"
        const val TV = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
        const val PHONE = "com/kennyb1201/kbstream/ui/mobile/SportsHubScreenMobile.kt"
    }
}
