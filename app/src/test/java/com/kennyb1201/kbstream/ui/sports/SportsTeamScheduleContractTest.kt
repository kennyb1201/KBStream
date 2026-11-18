package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The team view, pinned where a device would find it (spec audit 2026-11-18,
 * Sports M2's second half).
 *
 * The audit's finding was that there is NO team-centric view: the Favorites tab
 * is a copy of the current slate, so a followed team's next fixture existed
 * nowhere in the app unless it happened to be on today's board. The rule itself
 * is JVM-tested in [SportsTeamScheduleRulesTest]; this is the wiring - a
 * repository read with its own failure key, a ViewModel that owns exactly one
 * open team sheet, and both surfaces drawing the season from it.
 */
class SportsTeamScheduleContractTest {

    @Test
    fun `the repository reads one team's season`() {
        val repo = source(REPO)
        val fn = repo.substringAfter("suspend fun teamSchedule(")
            .substringBefore("fun lastTeamScheduleFailed(")
        assertTrue(
            "the season is the team's own endpoint, not a scoreboard window",
            fn.contains("\"/teams/\" + team + \"/schedule\"")
        )
        assertTrue(
            "the schedule body is the scoreboard's own events shape, so the one " +
                "parser covers it",
            fn.contains("parseScoreboardOrNull(it, leaguePath)")
        )
        assertTrue(
            "a team read is cached on its own key and window - a game in play is " +
                "in this list, and its score moves",
            fn.contains("val key = teamKey(leaguePath, team)") &&
                fn.contains("TEAM_SCHEDULE_TTL_MS") &&
                repo.contains("private const val TEAM_SCHEDULE_TTL_MS = 5L * 60_000L")
        )
        assertTrue(
            "a failed read keeps the previous season rather than blanking the sheet",
            fn.contains("return cached?.value.orEmpty()")
        )
        assertTrue(
            "a team failure is NOT the league's failure: it must not paint " +
                "\"Couldn't reach ESPN\" over a tab that answered fine",
            fn.contains("teamFailed += key") && fn.contains("teamFailed -= key") &&
                !fn.contains("failed += leaguePath")
        )
    }

    @Test
    fun `the viewmodel owns exactly one open team sheet`() {
        val vm = source(VM)
        assertTrue(
            "the sheet's state is null until a team is opened",
            vm.contains("private val _teamSchedule = MutableStateFlow<TeamScheduleState?>(null)") &&
                vm.contains("val teamSchedule: StateFlow<TeamScheduleState?> = _teamSchedule.asStateFlow()")
        )

        val open = vm.substringAfter("fun openTeamSchedule(")
            .substringBefore("fun closeTeamSchedule(")
        assertTrue(
            "only a side with an id and a league has a season to read",
            open.contains("if (leaguePath.isEmpty() || teamId.isEmpty()) return")
        )
        assertTrue(
            "a second open cancels the first read",
            open.contains("teamScheduleJob?.cancel()")
        )
        assertTrue(
            "and an answer that lands after the sheet moved on is dropped",
            open.contains("if (_teamSchedule.value?.team?.favoriteKey != team.favoriteKey) return@launch")
        )
        assertTrue(
            "an empty season is not an error; only a failed read is",
            open.contains("failed = games.isEmpty() && espn.lastTeamScheduleFailed(leaguePath, teamId)")
        )
        assertTrue(
            "the season is not run through the channel matcher: matching a season " +
                "is the 40-60s pass the hub spends on a SLATE",
            !open.contains("resolveMatches")
        )

        val close = vm.substringAfter("fun closeTeamSchedule(")
            .substringBefore("fun setLeagueEnabled(")
        assertTrue(
            "closing drops the read, so a late answer cannot reopen the sheet",
            close.contains("_teamSchedule.value = null")
        )
    }

    @Test
    fun `a game's sheet offers either side's season`() {
        val tv = source(TV)
        val phone = source(PHONE)

        assertTrue(
            "the TV sheet takes the callback, defaulting to nothing so every old " +
                "caller still compiles",
            tv.contains("onTeamSchedule: (SportsTeam) -> Unit = {},")
        )
        assertTrue(
            "the TV chips are drawn only for sides that HAVE a season endpoint",
            tv.contains("SportsTeamScheduleRules.canOpen(it)")
        )
        assertTrue(
            "the mobile sheet takes the same callback",
            phone.contains("onTeamSchedule: (SportsTeam) -> Unit = {},")
        )
        assertTrue(
            "and filters by the same rule",
            phone.contains("SportsTeamScheduleRules.canOpen(it)")
        )

        listOf(TV, PHONE).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path opens the season through the ViewModel, not a fetch of its own",
                screen.contains("viewModel.openTeamSchedule(team)")
            )
            assertTrue(
                "$path draws the sheet from the ViewModel's state",
                screen.contains("teamSchedule?.let { state ->")
            )
        }
    }

    @Test
    fun `both surfaces draw the whole season, results included`() {
        val tv = source(TV).substringAfter("private fun TeamScheduleSheet(")
            .substringBefore("private fun LazyListScope.scheduleGroup(")
        val phone = source(PHONE).substringAfter("private fun TeamScheduleSheetMobile(")
            .substringBefore("private fun LazyListScope.teamScheduleGroupMobile(")

        listOf(TV to tv, PHONE to phone).forEach { (path, sheet) ->
            assertTrue(
                "$path splits the season with the rule itself, not with a second " +
                    "opinion of its own",
                sheet.contains("val groups = SportsTeamScheduleRules.groups(state.games)")
            )
            assertTrue(
                "$path draws the results group",
                sheet.contains("groups.final")
            )
            assertTrue(
                "$path keeps a failed read apart from an empty season",
                sheet.contains("state.failed") && sheet.contains("groups.isEmpty")
            )
            assertFalse(
                "$path must NOT age finals out of a season the way the scoreboard " +
                    "does - results are the content here",
                sheet.contains("SportsFinalWindowRules")
            )
        }
        assertTrue(
            "the TV season groups as LIVE, UPCOMING and RESULTS",
            tv.contains("scheduleGroup(\"LIVE\"") &&
                tv.contains("scheduleGroup(\"UPCOMING\"") &&
                tv.contains("scheduleGroup(\"RESULTS\"")
        )
        assertTrue(
            "and so does the phone",
            phone.contains("teamScheduleGroupMobile(\"LIVE\"") &&
                phone.contains("teamScheduleGroupMobile(\"UPCOMING\"") &&
                phone.contains("teamScheduleGroupMobile(\"RESULTS\"")
        )
    }

    @Test
    fun `a fixture opens the ordinary game sheet, and the season closes`() {
        listOf(TV, PHONE).forEach { path ->
            val screen = source(path)
            val open = screen.substringAfter("onOpenGame = { game ->")
                .substringBefore("onClose = { viewModel.closeTeamSchedule() }")
            assertTrue(
                "$path closes the season before raising the game sheet - one sheet " +
                    "at a time, the hub's own contract",
                open.contains("viewModel.closeTeamSchedule()") && open.contains("detailGame = game")
            )
        }
    }

    @Test
    fun `a team can be followed from its own page`() {
        // Following a team is also the reminder opt-in, and this is the one screen
        // where the team is the subject - so it is the one place a team that is
        // not playing today can be followed from the team's own page.
        listOf(TV, PHONE).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path wires the season's FOLLOW control to the same toggle the " +
                    "cards use",
                screen.contains("onToggleFavorite = { on -> viewModel.setTeamFavorite(state.team, on) }")
            )
            assertTrue(
                "$path asks the follow set for the current state",
                screen.contains("favorite = state.team.favoriteKey in favoriteKeys")
            )
        }
    }

    @Test
    fun `a team with no game in the window is reachable from its league table`() {
        // The cards only ever name the teams PLAYING in the three days on
        // screen, so the season sheet's own entry (a game's side) cannot reach a
        // team in the offseason or on a bye week. The table names every team in
        // the league, so it is the door: OK opens that team's season from the row.
        val tv = source(TV)
        assertTrue(
            "the TV table opens a season for the team the row names",
            tv.contains("onOpenTeam = { team -> viewModel.openTeamSchedule(team) }") &&
                tv.contains("onOpenTeam: ((SportsTeam) -> Unit)? = null") &&
                tv.contains("onOpenSeason = {")
        )
        val phone = source(PHONE)
        assertTrue(
            "and so does the phone's",
            phone.contains("onOpenTeam = { team -> viewModel.openTeamSchedule(team) }") &&
                phone.contains("onOpenSeason = { onOpenTeam(team) }")
        )
        assertTrue(
            "the gesture split is the cards' own: tap opens, hold follows - a row " +
                "that only opened would lose the follow it just gained",
            phone.contains("onClick = onOpenSeason,\n        onLongClick = onToggleFollow,")
        )
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
