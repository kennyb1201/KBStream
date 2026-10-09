package com.kennyb1201.kbstream.data.sports

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the hub remembers about the viewer's own channel corrections.
 *
 * Reported problem: the matcher guessed a channel, the viewer knew it was wrong
 * and picked another, and the correction evaporated - the next game with that
 * team went back to the same wrong guess. Pinned here: a pick comes back, a
 * pick whose channel is gone does NOT (so the caller falls through to the
 * tiers), one manual pick records BOTH teams, and the store stays bounded.
 */
@RunWith(AndroidJUnit4::class)
class SportsChannelMemoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStore() {
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, "sports_channel_memory"),
            Context.MODE_PRIVATE
        )

    private fun channel(id: String, name: String = id) = IptvChannel(
        id = id,
        name = name,
        displayName = name,
        streamUrl = "http://playlist.test/$id",
        groupTitle = null,
        logoUrl = null,
        tvgId = null,
        tvgName = null,
        tvgChno = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        providerChannelId = null,
    )

    private fun team(abbreviation: String, name: String, home: Boolean) = SportsTeam(
        abbreviation = abbreviation,
        displayName = name,
        logoUrl = null,
        score = null,
        isHome = home,
        record = null,
    )

    private fun game(away: SportsTeam, home: SportsTeam) = SportsGame(
        id = "401",
        league = "baseball/mlb",
        name = "${away.displayName} at ${home.displayName}",
        dateMs = 1_700_000_000_000L,
        state = GameState.UPCOMING,
        statusDetail = "",
        away = away,
        home = home,
        broadcastNames = emptyList(),
    )

    @Test
    fun `a remembered pick comes back for the same team`() {
        val remembered = channel("ch2100", "Lightning Channel")
        SportsChannelMemory.remember(context, "TBL", remembered.id)

        val hit = SportsChannelMemory.recall(context, "TBL", listOf(channel("other", "Other"), remembered))

        assertEquals(remembered, hit)
    }

    @Test
    fun `nothing remembered recalls null`() {
        assertNull(SportsChannelMemory.recall(context, "TBL", listOf(channel("ch2100"))))
    }

    @Test
    fun `a remembered channel that is gone recalls null`() {
        SportsChannelMemory.remember(context, "TBL", "ch2100")

        // The playlist no longer carries ch2100: it must not hand back a dead
        // id, so the caller falls through to the tiers instead.
        assertNull(SportsChannelMemory.recall(context, "TBL", listOf(channel("ch9", "Renumbered"))))
    }

    @Test
    fun `a manual pick records both teams`() {
        val panthers = team("FLA", "Florida Panthers", home = true)
        val lightning = team("TB", "Tampa Bay Lightning", home = false)
        val chosen = channel("ch2100", "Lightning Channel")

        SportsChannelMemory.rememberPick(context, game(lightning, panthers), chosen.id)

        assertEquals(chosen, SportsChannelMemory.recall(context, lightning.favoriteKey, listOf(chosen)))
        assertEquals(chosen, SportsChannelMemory.recall(context, panthers.favoriteKey, listOf(chosen)))
    }

    @Test
    fun `a manual tournament pick is remembered under the event's own name`() {
        // A tournament has no teams, so its pick hangs off the event's stable
        // name (see TournamentEvent.favoriteKey) - the missing half of the
        // tournament correction-memory fix.
        val golf = channel("golf", "Golf Channel")
        val event = TournamentEvent(
            id = "t1",
            league = "golf/pga",
            name = "Baycurrent Classic",
            dateMs = 1_700_000_000_000L,
            state = GameState.LIVE,
            statusDetail = "",
            leaders = emptyList(),
            broadcastNames = emptyList(),
        )

        SportsChannelMemory.rememberTournamentPick(context, event, golf.id)

        assertEquals(golf, SportsChannelMemory.recall(context, event.favoriteKey, listOf(golf)))
        assertNull(
            "and a tournament the viewer never picked recalls nothing",
            SportsChannelMemory.recall(context, "some other event", listOf(golf))
        )
    }

    @Test
    fun `the newest correction for a team wins`() {
        SportsChannelMemory.remember(context, "TBL", "ch1")
        SportsChannelMemory.remember(context, "TBL", "ch2")

        val hit = SportsChannelMemory.recall(context, "TBL", listOf(channel("ch1"), channel("ch2")))

        assertEquals("ch2", hit?.id)
    }

    @Test
    fun `the store is capped and evicts the oldest correction`() {
        val teams = (1..SportsChannelMemory.MAX_ENTRIES + 1).map { "T$it" }
        teams.forEachIndexed { index, key ->
            SportsChannelMemory.remember(context, key, "ch${index + 1}")
        }

        val all = teams.mapIndexed { index, _ -> channel("ch${index + 1}") }
        assertNull(
            "the first correction is over the cap and must be the one evicted",
            SportsChannelMemory.recall(context, teams.first(), all)
        )
        assertEquals(
            "and the newest is kept",
            "ch${teams.size}",
            SportsChannelMemory.recall(context, teams.last(), all)?.id
        )
    }

    @Test
    fun `clear forgets every mapping`() {
        SportsChannelMemory.remember(context, "TBL", "ch2100")
        SportsChannelMemory.remember(context, "FLA", "ch2200")

        SportsChannelMemory.clear(context)

        assertNull(SportsChannelMemory.recall(context, "TBL", listOf(channel("ch2100"))))
        assertNull(SportsChannelMemory.recall(context, "FLA", listOf(channel("ch2200"))))
    }
}
