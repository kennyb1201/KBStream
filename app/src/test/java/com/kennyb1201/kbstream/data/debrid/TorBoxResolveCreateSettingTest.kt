package com.kennyb1201.kbstream.data.debrid

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The switch that decides whether the app may ADD a torrent to the viewer's
 * TorBox account.
 *
 * It exists because that add is a write to an account the viewer pays for: it
 * shows up in their TorBox library, and a stale cached verdict turns it into a
 * real download against their quota. So it is opt-in, it says what it does on
 * the row, and it is synced - a viewer who switches it off does not want the
 * other TV in the house adding torrents either.
 *
 * Both halves are pinned here: the stored default, and the fact that it rides
 * the display-prefs blob (a key missing from that allow-list is a silent no-op
 * push - the switch would look wired up and only ever change one device).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TorBoxResolveCreateSettingTest.NoopApplication::class)
class TorBoxResolveCreateSettingTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `adding a torrent is off until the viewer turns it on`() {
        assertFalse(
            "an app must not put files in a paid account on its own initiative",
            AppPreferences.getTorboxResolveCreate(context)
        )
        AppPreferences.setTorboxResolveCreate(context, true)
        assertTrue(AppPreferences.getTorboxResolveCreate(context))
        AppPreferences.setTorboxResolveCreate(context, false)
        assertFalse(
            "and switching it back off is remembered, not re-armed next launch",
            AppPreferences.getTorboxResolveCreate(context)
        )
    }

    @Test
    fun `the switch rides the display-prefs blob`() {
        assertTrue(
            "torbox_resolve_create must sync, or the other device keeps adding",
            "torbox_resolve_create" in PrefsPayloadBuilder.SYNCED_PREF_KEYS
        )
        assertFalse(
            "and it must not be classified device-local",
            "torbox_resolve_create" in PrefsPayloadBuilder.EXCLUDED_PREF_KEYS
        )
    }

    @Test
    fun `the resolver asks before it writes`() {
        // The gate is what makes "off" mean nothing is added; a resolver that
        // read the pref and then created anyway would be the same silent
        // account write the switch exists to prevent.
        val resolver = readSource(RESOLVER)
        assertTrue(
            "the create path is gated on the preference",
            resolver.contains("AppPreferences.getTorboxResolveCreate(context)")
        )
        assertTrue(
            "and the gate is checked BEFORE the add",
            resolver.indexOf("if (!allowCreate) return null") in 0 until
                resolver.indexOf("val id = createTorrent(apiKey, hash)")
        )
    }

    @Test
    fun `both settings panes offer the row and the search index knows it`() {
        for (pane in listOf(TV_PANE, PHONE_PANE)) {
            val body = readSource(pane)
            assertTrue("$pane must draw the row", body.contains("Add missing torrents on play"))
            assertTrue(
                "$pane must write the preference",
                body.contains("getTorboxResolveCreate") && body.contains("setTorboxResolveCreate")
            )
        }
        val search = readSource(SEARCH)
        assertTrue(
            "a row the search cannot find is a row a ten-pane rail hides",
            search.contains("\"Add missing torrents on play\"")
        )
    }

    private fun readSource(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun sourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val RESOLVER = "com/kennyb1201/kbstream/data/debrid/TorBoxResolve.kt"
        const val TV_PANE = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val PHONE_PANE = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt"
    }
}
