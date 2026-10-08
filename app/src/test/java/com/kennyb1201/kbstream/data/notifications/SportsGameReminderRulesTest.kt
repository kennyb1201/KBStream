package com.kennyb1201.kbstream.data.notifications

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets a "game starts soon" reminder, what it says, and the one-a-game
 * guarantee.
 *
 * The worker itself is a WorkManager round no CI can watch, so the decisions
 * that matter are pinned here: a game is announced once (the duplicate is the
 * complaint a viewer actually files), it is never announced after it started,
 * and a notification never fires for a team nobody follows.
 */
class SportsGameReminderRulesTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun team(abbreviation: String, name: String, id: String, shortName: String? = null) =
        SportsTeam(
            id = id,
            abbreviation = abbreviation,
            displayName = name,
            logoUrl = null,
            score = null,
            isHome = false,
            record = null,
            shortName = shortName,
        )

    private val yankees = team("NYY", "New York Yankees", "10", shortName = "Yankees")
    private val redSox = team("BOS", "Boston Red Sox", "11", shortName = "Red Sox")

    private fun game(
        id: String = "1",
        startsInMinutes: Long = 20L,
        state: GameState = GameState.UPCOMING,
        away: SportsTeam = redSox,
        home: SportsTeam = yankees,
    ) = SportsGame(
        id = id,
        league = "baseball/mlb",
        name = "Boston Red Sox at New York Yankees",
        dateMs = now + startsInMinutes * minute,
        state = state,
        statusDetail = "",
        away = away,
        home = home,
        broadcastNames = emptyList(),
    )

    @Test
    fun `a followed game starting inside the window is due`() {
        val due = SportsGameReminderRules.dueGames(listOf(game()), setOf("10"), now)
        assertEquals(listOf("1"), due.map { it.id })
    }

    @Test
    fun `a game further out than the window is not due yet`() {
        assertTrue(
            SportsGameReminderRules.dueGames(listOf(game(startsInMinutes = 45)), setOf("10"), now)
                .isEmpty()
        )
    }

    @Test
    fun `a game that has already started is never announced`() {
        assertTrue(
            SportsGameReminderRules.dueGames(
                listOf(game(startsInMinutes = -5, state = GameState.LIVE)),
                setOf("10"),
                now
            ).isEmpty()
        )
    }

    @Test
    fun `a game between two strangers is not due`() {
        assertTrue(
            SportsGameReminderRules.dueGames(listOf(game()), setOf("99"), now).isEmpty()
        )
    }

    @Test
    fun `the soonest game is announced first`() {
        val due = SportsGameReminderRules.dueGames(
            listOf(game(id = "late", startsInMinutes = 25), game(id = "soon", startsInMinutes = 5)),
            setOf("10"),
            now
        )
        assertEquals(listOf("soon", "late"), due.map { it.id })
    }

    @Test
    fun `a game is announced once, and only once, inside its TTL`() {
        // First sight: never announced, so it goes.
        assertTrue(SportsGameReminderRules.shouldNotify("1", null, now))
        // The next 30-minute tick: already announced, so it does not.
        assertFalse(SportsGameReminderRules.shouldNotify("1", now - 10 * minute, now))
        // Past the TTL the record has expired, which is only reachable for a
        // genuinely new game under the same id - never a second buzz for this
        // one - and is what keeps the store from growing.
        assertTrue(
            SportsGameReminderRules.shouldNotify("1", now - SportsGameReminderRules.DEDUPE_TTL_MS, now)
        )
    }

    @Test
    fun `pruning drops records past the TTL and keeps the rest`() {
        val pruned = SportsGameReminderRules.prune(
            mapOf(
                "fresh" to now - 5 * minute,
                "stale" to now - SportsGameReminderRules.DEDUPE_TTL_MS - 1
            ),
            now
        )
        assertEquals(setOf("fresh"), pruned.keys)
    }

    @Test
    fun `the round exists only while somebody is followed`() {
        assertFalse(SportsGameReminderRules.shouldSchedule(emptySet()))
        assertTrue(SportsGameReminderRules.shouldSchedule(setOf("10")))
    }

    @Test
    fun `the notification names the two sides and the minutes until kick-off`() {
        val fixture = game(startsInMinutes = 15)
        // ESPN's own short names, so Boston reads "Red Sox" and not "Sox".
        assertEquals("Red Sox @ Yankees", SportsGameReminderRules.reminderTitle(fixture))
        assertEquals("starts in 15 minutes", SportsGameReminderRules.reminderBody(fixture, now))
    }

    @Test
    fun `the headline falls back to the last word when the feed has no short name`() {
        val fixture = game(
            away = team("BOS", "Boston Red Sox", "11"),
            home = team("NYY", "New York Yankees", "10")
        )
        assertEquals("Sox @ Yankees", SportsGameReminderRules.reminderTitle(fixture))
    }

    @Test
    fun `minutes are rounded up so a reminder never says zero`() {
        assertEquals(20, SportsGameReminderRules.minutesUntil(now + 19 * minute + 30_000L, now))
        assertEquals(0, SportsGameReminderRules.minutesUntil(now - minute, now))
        assertEquals("starting now", SportsGameReminderRules.reminderBody(game(startsInMinutes = 0), now))
    }

    @Test
    fun `the notification id is namespaced away from the other alerts`() {
        val id = SportsGameReminderRules.notificationId("401")
        assertEquals(id, SportsGameReminderRules.notificationId("401"))
        assertTrue(id != SportsGameReminderRules.notificationId("402"))
    }
}
