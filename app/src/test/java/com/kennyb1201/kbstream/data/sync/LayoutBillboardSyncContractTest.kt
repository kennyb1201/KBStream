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

    // ── F2/F3: the gate is receiver-side, and the carrier PER-PROFILE ───────
    //
    // Spec 2026-11-15 reversed the carrier: the six keys are no longer one
    // box's arrangement, they are each profile's own. What does NOT move is the
    // TV gate - a phone still neither publishes nor adopts them.

    @Test
    fun `only a TV publishes them, and it reads them from the blob's own profile`() {
        val builder = payload.substringAfter("fun buildDisplayPrefs(context: Context): JsonObject")
            .substringBefore("fun buildAddons(")
        assertTrue(
            "the builder still gates the six on the form factor - a phone publishes none",
            builder.contains("if (key in TV_SCOPED_SYNC_KEYS &&") &&
                builder.contains("!com.kennyb1201.kbstream.FormFactor.isTv")
        )
        assertTrue(
            "and a TV-scoped key comes from the same place as every other key: this " +
                "profile's file",
            builder.contains("all[key]") && !builder.contains("deviceScopedValue")
        )
        assertFalse(
            "with no device-store detour left in the publish path",
            builder.contains("deviceValues") || builder.contains("devicePrefs(")
        )
    }

    @Test
    fun `only a TV adopts them, and the value lands in that profile`() {
        val apply = payload.substringAfter("private fun applyDisplayPrefs(")
            .substringBefore("private suspend fun applyAddons(")
        assertTrue(
            "a phone drops them - its layout and billboard are untouched by a TV sync",
            apply.contains("if (key in PrefsPayloadBuilder.TV_SCOPED_SYNC_KEYS &&") &&
                apply.contains("!com.kennyb1201.kbstream.FormFactor.isTv") &&
                apply.contains("return@forEach")
        )
        assertFalse(
            "and the TV writes through the ordinary per-profile editor: profile 1's Modern " +
                "reaches profile 1, and profile 2's Home is not touched",
            apply.contains("adoptDeviceScopedValue") ||
                apply.contains("devicePrefs(") ||
                apply.contains("AppPreferences")
        )
        assertTrue(
            "the trailer mode keeps its Int shape, or the Int reader throws on the Long",
            apply.contains("if (key == \"home_billboard_trailer_mode\")") &&
                apply.contains("editor.putInt(key, it.toInt())")
        )
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
                "$getter reads the profile's value, so a profile switch changes the Home",
                body.contains("layoutValue(context, ")
            )
        }
        listOf(
            "fun setHomeLayout(",
            "fun setHomeCardExpandMs(",
            "fun setHomeBillboardEnabled(",
            "fun setHomeBillboardFeeds(",
            "fun setHomeBillboardCycleMs(",
            "fun setHomeBillboardTrailerMode("
        ).forEach { setter ->
            val body = prefs.substringAfter(setter).substringBefore("}")
            assertTrue(
                "$setter writes the profile, never the device store",
                body.contains("prefs(context).edit()") &&
                    !body.contains("devicePrefs(context)")
            )
        }
        assertFalse(
            "and the device-scoped machinery is retired of itself, not just of its callers",
            prefs.contains("DEVICE_SCOPED_KEYS") ||
                prefs.contains("fun deviceScopedValue") ||
                prefs.contains("fun adoptDeviceScopedValue")
        )
        assertTrue(
            "the old device store is still named, because the migration seed reads it " +
                "once per profile so no Home flips on the update",
            prefs.contains("private const val DEVICE_PREFS_NAME = \"kbstream_device_prefs\"") &&
                prefs.contains("private fun layoutValue(context: Context, key: String): Any?") &&
                prefs.contains("devicePrefs(context).all[key]")
        )
    }

    // ── F4: last write wins per key, and an unset key publishes nothing ───

    @Test
    fun `an unset key is never published, so it cannot blank the other TV`() {
        val builder = payload.substringAfter("fun buildDisplayPrefs(context: Context): JsonObject")
            .substringBefore("fun buildAddons(")
        assertTrue(
            "the builder only publishes a key the profile actually holds",
            builder.contains("blobValue(key, stored(key))?.let { current[key] = it }") &&
                builder.contains("all[key]")
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
