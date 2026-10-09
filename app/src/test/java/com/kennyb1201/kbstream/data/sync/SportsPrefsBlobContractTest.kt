package com.kennyb1201.kbstream.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.settings.AppPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The sports hub's arrangement, at the blob boundary.
 *
 * Reported problem: an upgrading install read its leagues/order/followed teams
 * fine locally but the other TV never got them, until each setting was
 * re-saved. The three keys were migrated to newline-separated Strings so the
 * primitive-only display blob can carry them - but a device that had not
 * re-saved them still held the legacy StringSet, which has no representation
 * in the blob: `prefsValueString` saw no primitive, and `buildDisplayPrefs`
 * has no `is Set<*>` branch, so the key silently dropped out of both the
 * payload and the change snapshot.
 *
 * These pin the normalization at that boundary, and only there: the local read
 * keeps its dual-format behavior ([AppPreferences.readSportsSet]), and a
 * value already in the new format must come out byte-identical.
 *
 * Runs under a no-op [Application] for the reason the other prefs tests do: the
 * real one starts background work on Dispatchers.IO from onCreate.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = SportsPrefsBlobContractTest.NoopApplication::class)
class SportsPrefsBlobContractTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val store
        get() = context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_player_prefs"),
            Context.MODE_PRIVATE
        )

    @Before
    fun clearStore() {
        store.edit().clear().commit()
    }

    @Test
    fun `a legacy StringSet reaches the blob as the new string form`() {
        store.edit()
            .putStringSet("sports_enabled_leagues", setOf("golf/pga", "mma/ufc"))
            .commit()

        val blob = PrefsPayloadBuilder.buildDisplayPrefs(context)

        val emitted = blobText(blob, "sports_enabled_leagues")
        assertNotNull("the legacy set must not be dropped from the payload", emitted)
        assertEquals(setOf("golf/pga", "mma/ufc"), emitted!!.split("\n").toSet())
    }

    @Test
    fun `an unchanged legacy set is not re-stamped as an edit`() {
        store.edit()
            .putStringSet("sports_enabled_leagues", setOf("golf/pga", "mma/ufc"))
            .commit()
        // First observation: this is also what seeds the change snapshot.
        PrefsPayloadBuilder.buildDisplayPrefs(context)
        // A stamp the device already holds, as if the key had ever been edited.
        store.edit().putLong("__sync_ts__sports_enabled_leagues", 111L).commit()

        val second = PrefsPayloadBuilder.buildDisplayPrefs(context)

        // Fed through the same normalization as the payload, so an untouched
        // legacy value is not mistaken for a fresh local edit every build.
        assertEquals(111L, stampsOf(second)["sports_enabled_leagues"] ?: -1L)
        assertNotNull(
            "and it is still published",
            blobText(second, "sports_enabled_leagues")
        )
    }

    @Test
    fun `a new-format value is published exactly as stored`() {
        val stored = "hockey/nhl\nfootball/nfl"
        store.edit().putString("sports_league_order", stored).commit()

        val blob = PrefsPayloadBuilder.buildDisplayPrefs(context)

        // No reformatting and no reorder: the setters' String is already the
        // blob's form, and a device that has re-saved must emit what it had.
        assertEquals(stored, blobText(blob, "sports_league_order"))
    }

    @Test
    fun `a legacy set and a new-format value are both published`() {
        store.edit()
            .putStringSet("sports_enabled_leagues", setOf("golf/pga", "mma/ufc"))
            .commit()
        store.edit().putString("sports_favorite_teams", "27\n18").commit()

        val blob = PrefsPayloadBuilder.buildDisplayPrefs(context)

        val leagues = blobText(blob, "sports_enabled_leagues")
        assertNotNull(leagues)
        assertEquals(setOf("golf/pga", "mma/ufc"), leagues!!.split("\n").toSet())
        assertEquals("27\n18", blobText(blob, "sports_favorite_teams"))
    }

    @Test
    fun `an absent key and an empty legacy set stay out of the blob`() {
        store.edit().putStringSet("sports_enabled_leagues", emptySet()).commit()

        val blob = PrefsPayloadBuilder.buildDisplayPrefs(context)

        assertNull("a key this device never wrote has nothing to publish", blob["sports_favorite_teams"])
        assertNull("an empty legacy set normalizes to nothing, as an unset key is", blob["sports_enabled_leagues"])
    }

    @Test
    fun `the arrangement applied on the other device reads back as the same sets`() {
        store.edit()
            .putStringSet("sports_enabled_leagues", setOf("golf/pga", "mma/ufc"))
            .commit()
        store.edit().putStringSet("sports_league_order", setOf("mma/ufc", "golf/pga")).commit()
        store.edit().putStringSet("sports_favorite_teams", setOf("27", "18")).commit()

        val blob = PrefsPayloadBuilder.buildDisplayPrefs(context)

        // The other TV: same store, none of this arrangement.
        store.edit()
            .remove("sports_enabled_leagues")
            .remove("sports_league_order")
            .remove("sports_favorite_teams")
            .commit()

        runBlocking {
            PrefsPayloadApplier.apply(context, PrefsPayloadBuilder.KEY_DISPLAY_PREFS, blob)
        }

        assertEquals(
            setOf("golf/pga", "mma/ufc"),
            AppPreferences.getSportsEnabledLeagues(context)
        )
        assertEquals(
            setOf("mma/ufc", "golf/pga"),
            AppPreferences.getSportsLeagueOrder(context).toSet()
        )
        assertEquals(setOf("27", "18"), AppPreferences.getSportsFavoriteTeams(context))
    }

    private fun blobText(blob: JsonObject, key: String): String? =
        (blob[key] as? JsonPrimitive)?.content

    private fun stampsOf(blob: JsonObject): Map<String, Long> =
        (blob[DisplayPrefsRules.TIMESTAMPS_FIELD] as? JsonObject)
            ?.mapNotNull { (key, value) ->
                ((value as? JsonPrimitive)?.content?.toLongOrNull())?.let { key to it }
            }
            ?.toMap()
            .orEmpty()
}
