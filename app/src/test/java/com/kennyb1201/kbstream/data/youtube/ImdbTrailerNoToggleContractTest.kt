package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMDb-first is the behaviour, not a setting (spec 2026-11-12).
 *
 * The toggle was a safety valve from the probe spec (0.9.214): IMDb's MP4 first
 * when ON, YouTube alone when OFF. Kenny's point in the field is that OFF can
 * only make a trailer worse - the resolver already floors at 720p and falls back
 * to YouTube when IMDb has nothing - so a preference that is on by default and
 * strictly harmful off should not exist. The switch goes; the order stays.
 *
 * What this file pins is the ABSENCE, which is the part a compile cannot check:
 * no row on either settings surface, no search entry, no pref (not even a hidden
 * one defaulted to true), and the launcher's IMDb leg still first with nothing
 * gating it.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out app/src/test/java/.../ImdbTrailerNoToggleContractTest.kt, then
 * JUnitCore with -Duser.dir at the repository root.
 */
class ImdbTrailerNoToggleContractTest {

    private val settingsTv: String by lazy { read(SETTINGS_TV) }
    private val settingsPhone: String by lazy { read(SETTINGS_PHONE) }
    private val search: String by lazy { read(SEARCH) }
    private val prefs: String by lazy { read(PREFS) }
    private val sync: String by lazy { read(SYNC_PAYLOAD) }
    private val launcher: String by lazy { read(LAUNCHER) }

    // ── (a) no row, on either surface, and no search entry ─────────────────

    @Test
    fun `no settings surface offers an IMDb Trailers row`() {
        assertTrue(
            "the TV's HERO TRAILERS section has no source row - the behaviour is " +
                "the rule now",
            !settingsTv.contains("\"IMDb Trailers\"") &&
                !settingsTv.contains("setImdbTrailerEnabled") &&
                !settingsTv.contains("getImdbTrailerEnabled")
        )
        assertTrue(
            "and the phone's settings do not either",
            !settingsPhone.contains("\"IMDb Trailers\"") &&
                !settingsPhone.contains("setImdbTrailerEnabled") &&
                !settingsPhone.contains("getImdbTrailerEnabled")
        )
        assertTrue(
            "the section still exists and still names its remaining rows",
            settingsTv.contains("SettingsSectionHeader(\"Hero Trailers\")") &&
                settingsTv.contains("\"Hero Trailer Autoplay\"") &&
                settingsPhone.contains("SettingsSectionMobile(\"Hero Trailers\")")
        )
    }

    @Test
    fun `no search entry names the removed row`() {
        assertTrue(
            "a search result that jumps to a row which no longer exists lands nowhere",
            !search.contains("\"IMDb Trailers\"") &&
                !search.contains("interface.imdb-trailers") &&
                !search.contains("imdb trailer mp4 source")
        )
        assertTrue(
            "while the hero trailer rows it sits among are still indexed",
            search.contains("\"Hero Trailer Autoplay\"")
        )
    }

    // ── (b) no pref, and nothing reads one ────────────────────────────────

    @Test
    fun `the pref is deleted rather than defaulted, and nothing in the tree reads it`() {
        assertTrue(
            "no key, no getter, no setter: a hidden kill-switch defaulted to true " +
                "is the toggle by another name",
            !prefs.contains("KEY_IMDB_TRAILERS") &&
                !prefs.contains("getImdbTrailerEnabled") &&
                !prefs.contains("setImdbTrailerEnabled")
        )
        assertTrue(
            "and it is out of the sync payload, so no device pushes a dead key",
            !sync.contains("imdb_trailers")
        )
        val readers = sourceFilesUnderSourceRoot()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { file ->
                val text = file.readText()
                text.contains("getImdbTrailerEnabled") ||
                    text.contains("setImdbTrailerEnabled") ||
                    text.contains("KEY_IMDB_TRAILERS")
            }
            .map { it.relativeTo(findSourceRoot()).path }
        assertEquals(
            "these files still read the removed pref",
            emptyList<String>(),
            readers
        )
    }

    // ── (c) IMDb first, YouTube when it has nothing ────────────────────────

    @Test
    fun `the launcher still tries IMDb's MP4 first and falls back to YouTube`() {
        assertTrue(
            "the IMDb leg is asked for on every resolve",
            launcher.contains("val imdb = resolveImdbSource(imdbId, tmdbId, type, excludeClients, videoId)")
        )
        assertTrue(
            "the play press gets the fast service exchange, not the page",
            launcher.contains("ImdbTrailerResolver.resolve(id, allowPage = false)")
        )
        assertTrue(
            "the prefetch is unconditional too - the cache is only worth warming " +
                "because the play always asks now",
            launcher.contains("ImdbTrailerResolver.prefetch(id)") &&
                !launcher.contains("getImdbTrailerEnabled")
        )
        val body = launcher.substringAfter("suspend fun resolvePlayableUrl(")
        val imdbPlay = body.indexOf("logResolved(\"IMDb \" + imdb.kindLabel, source)")
        val youtube = body.indexOf("InnerTubeExtractor.extractPlaybackSourceWithClient(")
        assertTrue("the IMDb leg returns its own source", imdbPlay >= 0)
        assertTrue("the YouTube legs are still the fallback", youtube >= 0)
        assertTrue(
            "and the order is IMDb then YouTube - the spec changes the switch, " +
                "not which source wins",
            imdbPlay < youtube
        )
        val beforeImdb = body.substringBefore("val imdb = resolveImdbSource(")
        assertFalse(
            "no preference is consulted before the IMDb leg - the only earlier " +
                "refusal is the failure/refusal map, not a setting",
            beforeImdb.contains("AppPreferences.") || beforeImdb.contains("getImdbTrailerEnabled")
        )
    }

    private fun sourceFilesUnderSourceRoot(): List<File> =
        findSourceRoot().walkTopDown().toList()

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
        const val SETTINGS_TV = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val SETTINGS_PHONE = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val SYNC_PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val LAUNCHER = "com/kennyb1201/kbstream/data/youtube/TrailerPlayerLauncher.kt"
    }
}
