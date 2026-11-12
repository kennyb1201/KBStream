package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six layout/billboard keys syncing TV to TV, never to mobile (spec
 * 2026-11-12).
 *
 * The 2026-10-20 decision made the layout prefs device-local, because screen
 * geometry should not follow the account. That stands for GEOMETRY. This
 * refines it for the Home-layout SELECTION and the billboard settings that go
 * with it: one television's arrangement is the other television's - and never a
 * phone's, in either direction.
 *
 * Source-reading, like the rest of this module's contracts: the payload builder
 * and the applier both need a Context, and what has to be pinned here is the
 * shape of the gate (which device may publish, which may adopt, and where the
 * value lands), not the JSON.
 */
class LayoutBillboardSyncContractTest {

    private val payload: String by lazy { read(SYNC_PAYLOAD) }
    private val prefs: String by lazy { read(APP_PREFERENCES) }

    private val six = listOf(
        "home_layout",
        "home_billboard_enabled",
        "home_billboard_feeds",
        "home_billboard_cycle_ms",
        "home_billboard_trailer_mode",
        "home_card_expand_ms"
    )

    // ── F1: the six join the sync, and the exclusion family is narrowed ────

    @Test
    fun `the six keys are allow-listed and no longer excluded`() {
        val synced = payload.substringAfter("internal val SYNCED_PREF_KEYS = setOf(")
            .substringBefore("internal val EXCLUDED_PREF_KEYS")
        val excluded = payload.substringAfter("internal val EXCLUDED_PREF_KEYS = setOf(")
            .substringBefore("internal val TV_SCOPED_SYNC_KEYS")
        six.forEach { key ->
            assertTrue("$key must be published", synced.contains("\"$key\""))
            assertFalse("$key must not be excluded any more", excluded.contains("\"$key\""))
        }
        val tvScoped = payload.substringAfter("internal val TV_SCOPED_SYNC_KEYS = setOf(")
            .substringBefore(")")
        six.forEach { key ->
            assertTrue("$key is the TV-scoped family", tvScoped.contains("\"$key\""))
        }
        assertTrue(
            "the exclusion comment names the geometry family as the ONE local layout family",
            payload.contains("`poster_size`, `home_landscape_cards` and `landscape_posters`") &&
                payload.contains("That family is now the ONLY local layout family")
        )
        assertTrue(
            "and the TV-scoped set says what it is, in one place",
            payload.contains("The six keys that sync TV to TV, and only TV to TV")
        )
    }

    // ── F2/F3: the gate is receiver-side, and the carrier device-level ────

    @Test
    fun `only a TV publishes them, and it reads them from the device store`() {
        val builder = payload.substringAfter("fun buildDisplayPrefs(context: Context): JsonObject")
            .substringBefore("fun buildAddons(")
        assertTrue(
            "the builder gates the six on the form factor - a phone publishes none of them",
            builder.contains("if (com.kennyb1201.kbstream.FormFactor.isTv)") &&
                builder.contains(".deviceScopedValue(context, key)")
        )
        assertTrue(
            "and it reads a TV-scoped key from that store, never the profile file",
            builder.contains("if (key in TV_SCOPED_SYNC_KEYS) deviceValues[key] else all[key]")
        )
    }

    @Test
    fun `only a TV adopts them, and it writes them device-wide`() {
        val apply = payload.substringAfter("private fun applyDisplayPrefs(")
            .substringBefore("private suspend fun applyAddons(")
        assertTrue(
            "the apply step has its own branch for the six",
            apply.contains("if (key in PrefsPayloadBuilder.TV_SCOPED_SYNC_KEYS) {")
        )
        assertTrue(
            "a phone drops them - its layout and billboard are untouched by a TV sync",
            apply.contains("if (!com.kennyb1201.kbstream.FormFactor.isTv) return@forEach")
        )
        assertTrue(
            "a TV writes the DEVICE store, not a profile's file",
            apply.contains("com.kennyb1201.kbstream.data.settings.AppPreferences") &&
                apply.contains(".adoptDeviceScopedValue(context, key, adopted)")
        )
        val deviceKeys = prefs.substringAfter("internal val DEVICE_SCOPED_KEYS: Set<String> = setOf(")
            .substringBefore(")")
        listOf(
            "KEY_HOME_LAYOUT",
            "KEY_HOME_CARD_EXPAND_MS",
            "KEY_BILLBOARD_ENABLED",
            "KEY_BILLBOARD_FEEDS",
            "KEY_BILLBOARD_CYCLE_MS",
            "KEY_BILLBOARD_TRAILER_MODE"
        ).forEach { constant ->
            assertTrue(
                "the device store owns $constant",
                deviceKeys.contains(constant)
            )
        }
        listOf(
            "fun getHomeLayout",
            "fun getHomeCardExpandMs",
            "fun getHomeBillboardEnabled",
            "fun getHomeBillboardFeeds",
            "fun getHomeBillboardCycleMs",
            "fun getHomeBillboardTrailerMode"
        ).forEach { getter ->
            val body = prefs.substringAfter("$getter(").substringBefore("fun ")
            assertTrue(
                "$getter reads the device store, so a profile switch cannot change it",
                // Through deviceValue (spec 2026-11-12, render-path B1): the
                // per-key inheritance point, not the raw SharedPreferences read.
                body.contains("deviceValue(context, ")
            )
        }
        assertTrue(
            "the device store is un-namespaced - a profile's file is namespaced",
            prefs.contains("private const val DEVICE_PREFS_NAME = \"kbstream_device_prefs\"")
        )
        assertTrue(
            "and the move inherits from the profile store once, so no TV forgets its layout",
            prefs.contains("private fun deviceValue(context: Context, key: String): Any?")
        )
        assertTrue(
            "but only once a profile exists: before that the profile store is the legacy " +
                "global file and holds none of the six, so seeding from it would decide the " +
                "box's layout from an empty store (spec 2026-11-12, render-path B1)",
            prefs.contains("if (profileId.isNullOrBlank()) return null") &&
                prefs.contains("ProfileManager.activeProfileId(context)")
        )
    }

    // ── F4: last write wins per key, and an unset key publishes nothing ───

    @Test
    fun `an unset key is never published, so it cannot blank the other TV`() {
        val builder = payload.substringAfter("fun buildDisplayPrefs(context: Context): JsonObject")
            .substringBefore("fun buildAddons(")
        assertTrue(
            "the builder only publishes a key the device store actually holds",
            builder.contains(".deviceScopedValue(context, key)") &&
                builder.contains("?.let { value -> key to value }")
        )
        assertTrue(
            "and the payload loop skips an absent value instead of writing a default",
            payload.contains("SYNCED_PREF_KEYS.forEach { key ->\n                val value = stored(key) ?: return@forEach")
        )
        val feedsGetter = prefs.substringAfter("fun getHomeBillboardFeeds(")
            .substringBefore("fun setHomeBillboardFeeds(")
        assertFalse(
            "the trending-day fallback is a READ fallback, never written to the store - " +
                "publishing it would let a TV that never chose a feed clear the other's",
            feedsGetter.contains(".edit(")
        )
    }

    // ── F5: applying lands on the next Home entry ─────────────────────────

    @Test
    fun `the apply path does not try to rebuild a live Home`() {
        val apply = payload.substringAfter("private fun applyDisplayPrefs(")
            .substringBefore("private suspend fun applyAddons(")
        assertFalse(
            "no Home recomposition is forced from the sync apply: the layout and the " +
                "billboard are remember reads on Home entry (spec F5)",
            apply.contains("HomeViewModel") || apply.contains("recomposeHome")
        )
        assertTrue(
            "the display change still ticks the shared revision, so a screen that reads " +
                "it re-reads the new values",
            apply.contains("refreshThemeMirrors(context)") ||
                payload.contains("notifyLocalPrefChange()")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val SYNC_PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val APP_PREFERENCES = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
    }
}
