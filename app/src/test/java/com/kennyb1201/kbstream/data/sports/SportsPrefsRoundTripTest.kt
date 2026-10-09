package com.kennyb1201.kbstream.data.sports

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two sports sets the sync blob carries, at the storage layer.
 *
 * They were StringSets, which the primitive-only display blob cannot represent -
 * so they could never leave the device. They are now newline-separated Strings
 * (see [AppPreferences.readSportsSet] for why), and these pin the two things
 * that change has to keep true: a value written here reads back identical, and a
 * StringSet left behind by an older build is still read rather than dropped.
 */
@RunWith(AndroidJUnit4::class)
class SportsPrefsRoundTripTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val store by lazy {
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_player_prefs"),
            Context.MODE_PRIVATE
        )
    }

    @Before
    fun clear() {
        AppPreferences.setSportsEnabledLeagues(context, emptySet())
        AppPreferences.setSportsFavoriteTeams(context, emptySet())
    }

    @Test
    fun `enabled leagues round-trip through the synced string store`() {
        AppPreferences.setSportsEnabledLeagues(
            context,
            setOf("football/nfl", "hockey/nhl", "basketball/nba")
        )

        assertEquals(
            setOf("football/nfl", "hockey/nhl", "basketball/nba"),
            AppPreferences.getSportsEnabledLeagues(context)
        )
    }

    @Test
    fun `favorite teams round-trip through the synced string store`() {
        AppPreferences.setSportsFavoriteTeams(context, setOf("27", "18", "carlos alcaraz"))

        assertEquals(
            setOf("27", "18", "carlos alcaraz"),
            AppPreferences.getSportsFavoriteTeams(context)
        )
    }

    @Test
    fun `an unchanged set is still an enabled set, not the default`() {
        // A viewer who turned everything off must stay off - the read must not
        // fall back to "every league on" just because the value is empty.
        AppPreferences.setSportsEnabledLeagues(context, emptySet())

        assertTrue(AppPreferences.getSportsEnabledLeagues(context).isEmpty())
    }

    @Test
    fun `a set written by an older build still reads back`() {
        // The pre-sync format: a StringSet under the same key. Upgrading must
        // not silently lose the arrangement.
        store.edit().putStringSet("sports_enabled_leagues", setOf("golf/pga", "mma/ufc")).commit()
        store.edit().putStringSet("sports_favorite_teams", setOf("27")).commit()

        assertEquals(
            setOf("golf/pga", "mma/ufc"),
            AppPreferences.getSportsEnabledLeagues(context)
        )
        assertEquals(setOf("27"), AppPreferences.getSportsFavoriteTeams(context))
    }
}
