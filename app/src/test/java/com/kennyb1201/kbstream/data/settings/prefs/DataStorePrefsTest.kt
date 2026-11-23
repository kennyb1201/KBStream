package com.kennyb1201.kbstream.data.settings.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shim is only worth its weight if every promise `SharedPreferences` makes
 * still holds through it, because a thousand call sites depend on those
 * promises rather than on DataStore's.
 *
 * These cases are the contract: the old XML file is adopted on first open, a
 * write is durable in the DataStore format and readable immediately, `remove`
 * and `clear` behave, a string set round-trips, listeners fire on the key that
 * changed, two stores stay apart, and a deleted store comes back empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataStorePrefsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun store(name: String): SharedPreferences = Prefs.file(context, name)

    private fun legacy(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun datastoreFile(name: String): File =
        File(File(context.filesDir, "datastore"), "$name.preferences_pb")

    @Test
    fun `an existing xml store is adopted when the datastore first opens`() {
        legacy("kbstream_shim_adopt").edit()
            .putString("viewer", "kenny")
            .putInt("volume", 7)
            .putBoolean("kid_mode", true)
            .putLong("since", 42L)
            .putFloat("speed", 1.5f)
            .commit()

        val shim = store("kbstream_shim_adopt")

        assertEquals("string", "kenny", shim.getString("viewer", null))
        assertEquals("int", 7, shim.getInt("volume", 0))
        assertTrue("boolean", shim.getBoolean("kid_mode", false))
        assertEquals("long", 42L, shim.getLong("since", 0L))
        assertEquals("float", 1.5f, shim.getFloat("speed", 0f), 0f)
    }

    @Test
    fun `a commit writes the datastore file, not an xml one`() {
        val name = "kbstream_shim_write"

        assertTrue(store(name).edit().putString("token", "abc").commit())

        val file = datastoreFile(name)
        assertTrue("the write lands in the DataStore file", file.isFile)
        assertTrue("and it is not empty", file.length() > 0)
    }

    @Test
    fun `an applied write is visible to the next read`() {
        val shim = store("kbstream_shim_apply")

        shim.edit().putString("pane", "playback").apply()

        assertEquals("playback", shim.getString("pane", null))
    }

    @Test
    fun `remove drops one key and clear drops the rest`() {
        val shim = store("kbstream_shim_clear")
        shim.edit().putString("a", "1").putString("b", "2").commit()

        shim.edit().remove("a").commit()
        assertFalse("removed key", shim.contains("a"))
        assertTrue("other key", shim.contains("b"))

        shim.edit().clear().commit()
        assertFalse("cleared key", shim.contains("b"))
        assertTrue("nothing left", shim.all.isEmpty())
    }

    @Test
    fun `a string set round-trips and comes back detached`() {
        val shim = store("kbstream_shim_set")
        shim.edit().putStringSet("leagues", mutableSetOf("nfl", "nba")).commit()

        val read = shim.getStringSet("leagues", emptySet())
        assertEquals(setOf("nfl", "nba"), read)

        read?.add("mlb")
        assertEquals(
            "mutating the returned set must not edit the store behind its back",
            setOf("nfl", "nba"),
            shim.getStringSet("leagues", emptySet())
        )
    }

    @Test
    fun `a listener hears the key that changed`() {
        val shim = store("kbstream_shim_listener")
        val heard = mutableListOf<String>()
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard += key }

        shim.registerOnSharedPreferenceChangeListener(listener)
        shim.edit().putBoolean("kids", true).apply()
        shim.unregisterOnSharedPreferenceChangeListener(listener)
        shim.edit().putBoolean("kids", false).apply()

        assertEquals(listOf("kids"), heard)
    }

    @Test
    fun `two stores do not see each other`() {
        store("kbstream_shim_one").edit().putString("k", "one").commit()
        store("kbstream_shim_two").edit().putString("k", "two").commit()

        assertEquals("one", store("kbstream_shim_one").getString("k", null))
        assertEquals("two", store("kbstream_shim_two").getString("k", null))
        assertSame(
            "one store per name per process",
            store("kbstream_shim_one"),
            store("kbstream_shim_one")
        )
    }

    @Test
    fun `a deleted store comes back empty`() {
        val name = "kbstream_shim_delete"
        store(name).edit().putString("k", "v").commit()
        assertTrue(Prefs.names(context).contains(name))

        assertTrue(Prefs.delete(context, name))

        assertFalse("the file is gone", datastoreFile(name).exists())
        assertFalse("and so is the name", Prefs.names(context).contains(name))
        assertNull("a recreated store starts empty", store(name).getString("k", null))
    }
}
