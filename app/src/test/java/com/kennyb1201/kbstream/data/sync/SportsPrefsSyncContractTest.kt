package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sports hub's arrangement follows the viewer's account: which leagues are
 * on, the tab order they were moved into, and the teams they follow.
 *
 * Reported problem: all three stayed on the TV they were set on, so the other
 * set came up with a default hub. Two things have to hold for a setter to
 * actually sync, and this pins both:
 *
 *  - the key has to be in [PrefsPayloadBuilder.SYNCED_PREF_KEYS], or the push
 *    is a silent no-op - exactly how the subtitle/language settings once "did
 *    not sync" while their setters looked wired up; and
 *  - the value has to be representable in the primitive-only display blob, or
 *    it never leaves. The two sets are stored as newline-separated Strings for
 *    that reason ([AppPreferences.readSportsSet] reads a legacy StringSet back,
 *    so an upgrading install is unaffected).
 */
class SportsPrefsSyncContractTest {

    @Test
    fun `the sports hub's arrangement keys sync`() {
        val synced = PrefsPayloadBuilder.SYNCED_PREF_KEYS
        assertTrue("sports_enabled_leagues must sync", "sports_enabled_leagues" in synced)
        assertTrue("sports_league_order must sync", "sports_league_order" in synced)
        assertTrue("sports_favorite_teams must sync", "sports_favorite_teams" in synced)
        // None of them describes what THIS device can do, so none may sit on
        // the per-device exclusion list.
        val excluded = PrefsPayloadBuilder.EXCLUDED_PREF_KEYS
        assertFalse("sports_enabled_leagues must not be device-local", "sports_enabled_leagues" in excluded)
        assertFalse("sports_league_order must not be device-local", "sports_league_order" in excluded)
        assertFalse("sports_favorite_teams must not be device-local", "sports_favorite_teams" in excluded)
    }

    @Test
    fun `each sports setter pushes the display blob`() {
        val source = readSource()
        listOf(
            "fun setSportsEnabledLeagues(",
            "fun setSportsLeagueOrder(",
            "fun setSportsFavoriteTeams("
        ).forEach { signature ->
            assertTrue(
                "$signature must enqueue the display blob, or the write stays on this TV",
                functionBody(source, signature).contains("syncDisplayPrefsBlob(context)")
            )
        }
    }

    @Test
    fun `the two sets are stored in a form the blob can carry`() {
        // A StringSet has no representation in the primitive-only payload, so a
        // regression back to putStringSet would silently stop syncing again.
        val source = readSource()
        assertTrue(
            "enabled leagues must be written as a String for the blob to carry them",
            functionBody(source, "fun setSportsEnabledLeagues(").contains("putString")
        )
        assertTrue(
            "favorite teams must be written as a String for the blob to carry them",
            functionBody(source, "fun setSportsFavoriteTeams(").contains("putString")
        )
        assertTrue(
            "the enabled reader must go through the shared string reader, which also reads a legacy StringSet",
            source.contains("readSportsSet(context, KEY_SPORTS_LEAGUES)")
        )
        assertTrue(
            "and so must the favorites reader",
            source.contains("readSportsSet(context, KEY_SPORTS_FAVORITE_TEAMS)")
        )
    }

    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun readSource(): String {
        val file = File(findSourceRoot(), PREFS)
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
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
    }
}
