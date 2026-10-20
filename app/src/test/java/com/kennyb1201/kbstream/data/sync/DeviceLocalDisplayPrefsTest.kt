package com.kennyb1201.kbstream.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Landscape cards, landscape posters and poster size are DEVICE-LOCAL
 * (decision, 2026-10-20).
 *
 * They are screen geometry, not identity: the useful tile size and card shape
 * depend on the screen (a 65" TV and a 6.7" phone want different answers), so
 * they stop riding the display-prefs blob while everything else in it keeps
 * syncing.
 *
 * Both halves of the change are pinned here:
 *  - nothing leaves the device — the three keys are out of
 *    [PrefsPayloadBuilder.SYNCED_PREF_KEYS], the manifest assertion lives in
 *    [SyncScopeTest], and a built blob carries none of them;
 *  - nothing arrives — the applier already drops any key outside that set, so
 *    a blob from an older build carrying them changes nothing here. That is
 *    also why no migration code exists: the filter IS the migration.
 *
 * The setters keep writing locally and keep ticking the local revision (a
 * Settings row still reshapes the tiles it changed); what they must no longer
 * do is enqueue a display blob.
 *
 * Runs under a no-op [Application] for the reason the other prefs tests do: the
 * real one starts background work on Dispatchers.IO from onCreate.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = DeviceLocalDisplayPrefsTest.NoopApplication::class)
class DeviceLocalDisplayPrefsTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val store
        get() = context.getSharedPreferences(
            ProfileStorage.prefsName(context, DISPLAY_STORE),
            Context.MODE_PRIVATE
        )

    @Before
    fun clearStore() {
        store.edit().clear().commit()
    }

    @Test
    fun `a built blob carries none of the three keys`() {
        // This device's answers, as a viewer who set them would leave them.
        store.edit()
            .putLong("poster_size", 2L)
            .putBoolean("home_landscape_cards", true)
            .putBoolean("landscape_posters", true)
            // ...and one pref that must still publish, so "absent" is a filter
            // and not a blob that stopped carrying anything.
            .putBoolean("poster_caption_year", false)
            .commit()

        val blob = PrefsPayloadBuilder.buildDisplayPrefs(context)

        DEVICE_LOCAL_KEYS.forEach { key ->
            assertNull("$key must not leave the device", blob[key])
        }
        assertNotNull(
            "the rest of the display prefs still sync",
            blob["poster_caption_year"]
        )
    }

    @Test
    fun `an incoming blob carrying the three keys applies nothing for them`() {
        // Defaults on this device: medium posters, both landscape switches off.
        assertEquals(1L, AppPreferences.getPosterSize(context))
        assertFalse(AppPreferences.getHomeLandscapeCards(context))
        assertFalse(AppPreferences.getLandscapePosters(context))

        // The row an older build still publishes: the three keys, stamped, plus
        // one synced pref that must land.
        val blob =
            buildJsonObject {
                put("poster_size", 2L)
                put("home_landscape_cards", true)
                put("landscape_posters", true)
                put("poster_caption_year", false)
                putJsonObject(DisplayPrefsRules.TIMESTAMPS_FIELD) {
                    DEVICE_LOCAL_KEYS.forEach { key -> put(key, 9_000L) }
                    put("poster_caption_year", 9_000L)
                }
            }

        runBlocking {
            PrefsPayloadApplier.apply(context, PrefsPayloadBuilder.KEY_DISPLAY_PREFS, blob)
        }

        assertEquals(
            "another device's poster size must not reshape this screen",
            1L,
            AppPreferences.getPosterSize(context)
        )
        assertFalse(AppPreferences.getHomeLandscapeCards(context))
        assertFalse(AppPreferences.getLandscapePosters(context))
        assertFalse(
            "an unrelated synced key in the same blob still lands",
            AppPreferences.getPosterCaptionYear(context)
        )
    }

    @Test
    fun `the three setters write locally and publish no display blob`() {
        AppPreferences.setPosterSize(context, 2L)
        AppPreferences.setHomeLandscapeCards(context, true)
        AppPreferences.setLandscapePosters(context, true)

        assertEquals(2L, AppPreferences.getPosterSize(context))
        assertTrue(AppPreferences.getHomeLandscapeCards(context))
        assertTrue(AppPreferences.getLandscapePosters(context))
        assertTrue("the Home rule reads the same two switches", AppPreferences.homeLandscapeActive(context))
        assertTrue(AppPreferences.landscapePostersActive(context))

        // The push is what had to go, and it is not observable from a test: the
        // enqueue is a no-op unless the device is signed in, and the outbox is a
        // singleton. Pin the seam instead.
        val source = readAppPreferences()
        listOf(
            "fun setPosterSize(" to "KEY_POSTER_SIZE",
            "fun setHomeLandscapeCards(" to "KEY_LANDSCAPE_CARDS",
            "fun setLandscapePosters(" to "KEY_LANDSCAPE_POSTERS"
        ).forEach { (signature, key) ->
            val body = functionBody(source, signature)
            assertTrue("$signature must still write $key locally: $body", body.contains(key))
            assertTrue(
                "$signature still has to tick the local revision, or the tiles it " +
                    "changed keep their first-composition size: $body",
                body.contains("notifyDisplayPrefChanged()")
            )
            assertFalse(
                "$signature must not push the display blob - these keys do not sync: $body",
                body.contains("syncDisplayPrefsBlob") || body.contains("enqueuePrefs")
            )
        }
    }

    /** A top-level member's body: [signature] up to its 4-space-indented brace. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("source missing: $signature", start >= 0)
        val brace = source.indexOf('{', start)
        assertTrue("no body for: $signature", brace >= 0)
        val rest = source.substring(brace + 1)
        val end = Regex("\\n {4}\\}").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun readAppPreferences(): String {
        val source = findSourceRoot()
        val file = File(source, "com/kennyb1201/kbstream/data/settings/AppPreferences.kt")
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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val DISPLAY_STORE = "kbstream_player_prefs"

        /** Screen geometry: the three keys that stopped syncing. */
        val DEVICE_LOCAL_KEYS = listOf(
            "poster_size",
            "home_landscape_cards",
            "landscape_posters"
        )
    }
}
