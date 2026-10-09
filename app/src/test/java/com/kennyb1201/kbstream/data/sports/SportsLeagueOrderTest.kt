package com.kennyb1201.kbstream.data.sports

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.settings.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The viewer's own league order.
 *
 * Reported problem: the league list was fixed in catalog order, so a viewer who
 * cares about one league and not the others could not bring it forward. Pinned
 * here: an arrangement is honored for BOTH the panel and the hub's tabs, an
 * absent arrangement is the catalog order, and a stored order that names a path
 * this build does not know (or misses a league it added later) still resolves
 * to a complete, sane list rather than dropping leagues.
 */
@RunWith(AndroidJUnit4::class)
class SportsLeagueOrderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStore() {
        AppPreferences.setSportsLeagueOrder(context, emptyList())
    }

    @Test
    fun `no stored order is the catalog order`() {
        assertEquals(SportsLeagues.ALL, SportsLeagues.ordered(emptyList()))
    }

    @Test
    fun `a stored order comes first and the rest keep catalog order behind it`() {
        val ordered = SportsLeagues.ordered(listOf("hockey/nhl", "mma/ufc"))

        assertEquals("hockey/nhl", ordered.first().path)
        assertEquals("mma/ufc", ordered[1].path)
        assertEquals(
            "everything the order does not name still follows, in catalog order",
            SportsLeagues.ALL.filter { it.path !in setOf("hockey/nhl", "mma/ufc") },
            ordered.drop(2)
        )
        assertEquals(
            "and nothing is lost: the output is still the whole catalog",
            SportsLeagues.ALL.size,
            ordered.size
        )
    }

    @Test
    fun `an unknown path in the stored order is ignored, not fatal`() {
        val ordered = SportsLeagues.ordered(listOf("kabaddi/pro", "football/nfl"))

        assertTrue("the catalog is intact", ordered.containsAll(SportsLeagues.ALL))
        assertEquals("the known path still ranks", "football/nfl", ordered.first().path)
    }

    @Test
    fun `the enabled list is drawn in the viewer's order`() {
        val enabled = setOf("football/nfl", "hockey/nhl", "basketball/nba")

        val catalog = SportsLeagues.enabled(enabled)
        val rearranged = SportsLeagues.enabled(enabled, listOf("hockey/nhl", "basketball/nba"))

        assertEquals(
            "without an order it is the catalog's own",
            listOf("football/nfl", "basketball/nba", "hockey/nhl"),
            catalog.map { it.path }
        )
        assertEquals(
            "and with one it follows it, filtered to what is enabled",
            listOf("hockey/nhl", "basketball/nba", "football/nfl"),
            rearranged.map { it.path }
        )
    }

    @Test
    fun `the order round-trips through the preferences`() {
        AppPreferences.setSportsLeagueOrder(context, listOf("golf/pga", "mma/ufc"))

        assertEquals(
            listOf("golf/pga", "mma/ufc"),
            AppPreferences.getSportsLeagueOrder(context)
        )
    }

    @Test
    fun `clearing the order returns the catalog order`() {
        AppPreferences.setSportsLeagueOrder(context, listOf("golf/pga"))
        AppPreferences.setSportsLeagueOrder(context, emptyList())

        assertEquals(
            emptyList<String>(),
            AppPreferences.getSportsLeagueOrder(context)
        )
        assertEquals(SportsLeagues.ALL, SportsLeagues.ordered(AppPreferences.getSportsLeagueOrder(context)))
    }
}
