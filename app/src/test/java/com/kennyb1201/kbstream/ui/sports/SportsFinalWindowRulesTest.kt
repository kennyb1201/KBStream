package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a final stays on the hub (spec 2026-11-15, Sports Hub Favorites FINAL).
 *
 * The field: the 2026 World Series final (October 28) was still the first card in
 * Favorites' FINAL section on November 15. The section had no age-out at all - it
 * drew every FINAL game the section carried, and the section carries whatever the
 * feed still answers for a league whose season has ended.
 */
class SportsFinalWindowRulesTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    private fun game(daysAgo: Long, state: GameState = GameState.FINAL) = SportsGame(
        id = "g$daysAgo$state",
        league = "baseball/mlb",
        name = "Away at Home",
        dateMs = now - daysAgo * day,
        state = state,
        statusDetail = "Final",
        away = SportsTeam(
            abbreviation = "MIL",
            displayName = "Brewers",
            logoUrl = null,
            score = "4",
            isHome = false,
            record = null
        ),
        home = SportsTeam(
            abbreviation = "TB",
            displayName = "Rays",
            logoUrl = null,
            score = "1",
            isHome = true,
            record = null
        ),
        broadcastNames = emptyList()
    )

    @Test
    fun `an eighteen-day-old final is dropped`() {
        // The field's game, exactly: the World Series final was 18 days old.
        val expired = game(daysAgo = 18)
        assertTrue(SportsFinalWindowRules.isExpiredFinal(expired, now))
        assertEquals(
            "and it does not survive the merge",
            emptyList<SportsGame>(),
            SportsFinalWindowRules.keepCurrent(listOf(expired), now)
        )
    }

    @Test
    fun `yesterday's final is kept`() {
        val yesterday = game(daysAgo = 1)
        assertFalse(SportsFinalWindowRules.isExpiredFinal(yesterday, now))
        assertEquals(
            listOf(yesterday),
            SportsFinalWindowRules.keepCurrent(listOf(yesterday), now)
        )
    }

    @Test
    fun `the window is seven days, and the boundary is inclusive`() {
        assertEquals(7L, SportsFinalWindowRules.FINAL_WINDOW_DAYS)
        assertEquals(7 * day, SportsFinalWindowRules.finalWindowMs)
        assertFalse(
            "a final dated exactly seven days ago is still a score to check",
            SportsFinalWindowRules.isExpiredFinal(game(daysAgo = 7), now)
        )
        assertTrue(
            "one millisecond past it is not",
            SportsFinalWindowRules.isExpiredFinal(
                game(daysAgo = 7).copy(dateMs = now - 7 * day - 1),
                now
            )
        )
    }

    @Test
    fun `live and upcoming are never aged out, however old their date looks`() {
        listOf(GameState.LIVE, GameState.UPCOMING).forEach { state ->
            val old = game(daysAgo = 30, state = state)
            assertFalse("$state is never history", SportsFinalWindowRules.isExpiredFinal(old, now))
            assertEquals(
                "$state survives the filter",
                listOf(old),
                SportsFinalWindowRules.keepCurrent(listOf(old), now)
            )
        }
    }

    @Test
    fun `a final dated in the future is not dropped by accident`() {
        val future = game(daysAgo = 3).copy(dateMs = now + 3 * day)
        assertFalse(SportsFinalWindowRules.isExpiredFinal(future, now))
    }

    @Test
    fun `the kept games hold their order`() {
        val games = listOf(
            game(daysAgo = 1),
            game(daysAgo = 20),
            game(daysAgo = 2),
            game(daysAgo = 9)
        )
        assertEquals(
            "the survivors, in the order the section had them",
            listOf(games[0], games[2]),
            SportsFinalWindowRules.keepCurrent(games, now)
        )
    }

    @Test
    fun `the final partition is capped at twenty, newest first`() {
        assertEquals(20, SportsFinalWindowRules.FINAL_CARD_CAP)
        val many = (0 until 30).map { index -> game(daysAgo = index.toLong()).copy(id = "g$index") }
        val capped = SportsFinalWindowRules.finalCards(many)
        assertEquals(20, capped.size)
        assertEquals("newest first", "g0", capped.first().id)
        assertEquals("the newest twenty, not the oldest", "g19", capped.last().id)
    }

    @Test
    fun `the merge drops the final and the section draws the cap`() {
        // The two call sites the rules exist for: the section's games are built in
        // the ViewModel (so every tab is covered), and the FINAL partition in the
        // shared section body is capped.
        val viewModel = read("ui/sports/SportsHubViewModel.kt")
        assertTrue(
            "the age-out runs where a section's games are built",
            viewModel.contains("SportsFinalWindowRules.keepCurrent(section.games")
        )
        assertEquals(
            "once, in the one place every tab goes through",
            1,
            Regex("keepCurrent\\(").findAll(viewModel).count()
        )
        val screen = read("ui/sports/SportsHubScreen.kt")
        assertTrue(
            "the cap is on the FINAL partition, which both tabs draw through",
            screen.contains(
                "SportsFinalWindowRules.finalCards(section.games.filter { it.state == GameState.FINAL })"
            )
        )
        assertFalse(
            "and nothing else filters a final by age at draw time - the merge owns it",
            screen.contains("isExpiredFinal(")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/src/main/java/com/kennyb1201/kbstream/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
