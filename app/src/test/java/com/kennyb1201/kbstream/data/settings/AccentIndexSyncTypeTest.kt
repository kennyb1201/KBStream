package com.kennyb1201.kbstream.data.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.DisplayPrefsRules
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The synced accent index must survive the type the applier can only guess.
 *
 * The display-prefs blob carries each value as a string, and the applier has no
 * type information - so a numeric pref (the accent index included) is written
 * with `putLong`. Reading that back with a raw `getInt` threw
 * `ClassCastException("java.lang.Long cannot be cast to java.lang.Integer")`
 * on launch for any device that had pulled the accent from another: Sentry
 * ANDROID-R, app 0.5, fatal. Every other synced Int already reads through the
 * tolerant helper; this pins that the accent does too.
 *
 * Runs under a no-op [Application] for the reason the other prefs tests do: the
 * real one starts background work on Dispatchers.IO from onCreate.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = AccentIndexSyncTypeTest.NoopApplication::class)
class AccentIndexSyncTypeTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a synced numeric accent index reads back instead of crashing`() {
        val now = System.currentTimeMillis()
        // Exactly what a pull stores: the value as a bare JSON number, so the
        // applier takes its numeric branch and writes a Long.
        val payload = buildJsonObject {
            put(DisplayPrefsRules.UPDATED_AT_FIELD, now)
            putJsonObject(DisplayPrefsRules.TIMESTAMPS_FIELD) {
                put("accent_index", now)
            }
            put("accent_index", 7)
        }

        runBlocking {
            PrefsPayloadApplier.apply(
                context,
                PrefsPayloadBuilder.KEY_DISPLAY_PREFS,
                payload
            )
        }

        // The read that used to throw; readIntPref falls back to getLong.
        assertEquals(7, AppPreferences.getAccentIndex(context))
    }

    @Test
    fun `an accent the device wrote itself still reads`() {
        AppPreferences.setAccentIndex(context, 12)
        assertEquals(12, AppPreferences.getAccentIndex(context))
    }
}
