package com.kennyb1201.kbstream.data.badges

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The badge pack removal's wiring, read out of the source.
 *
 * [BadgePackSyncRules] decides what a payload means, but a decision nothing acts
 * on is not a fix: these pins hold the two halves the report was about - the
 * field that kept showing a removed URL, and the removal that never left this
 * device. The screens are Compose and the applier needs an Android Context, so
 * what is asserted is the wiring that would fail SILENTLY.
 */
class BadgePackRemovalContractTest {

    private val engine = source("com/kennyb1201/kbstream/data/badges/StreamBadges.kt")
    private val payload = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
    private val settingsTv = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
    private val settingsPhone =
        source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

    // ── F1: REMOVE clears the field it is standing next to ────────────────

    @Test
    fun `REMOVE clears the URL field on both screens`() {
        listOf("TV" to settingsTv, "phone" to settingsPhone).forEach { (form, src) ->
            val remove = flat(src)
                .substringAfter("StreamBadgeEngine.clearPack(context)")
                .substringBefore("}")
            assertTrue(
                "the $form REMOVE must clear the field: it is remembered state seeded " +
                    "once from the store, so clearing the store does not move it",
                remove.contains("badgePackInput = \"\"")
            )
            assertTrue(
                "and the removal still reports itself",
                remove.contains("badgeStatus = \"Badge pack removed\"")
            )
        }
    }

    // ── F2: the removal is a stamped fact, published now ──────────────────

    @Test
    fun `clearPack records the removal and publishes it immediately`() {
        val clear = flat(engine)
            .substringAfter("fun clearPack(context: Context) {")
            .substringBefore("fun wasRemoved(")
        assertTrue(
            "the pack still goes",
            clear.contains("prefs(context).edit().clear().apply()")
        )
        assertTrue(
            "and the removal is recorded as a stamp, which is what makes it " +
                "distinguishable from a device that never had a pack",
            clear.contains("putLong(KEY_CLEARED_AT, now)")
        )
        assertTrue(
            "the local stamp moves with it, so a stale sibling removal cannot undo it",
            clear.contains("putLong(KEY_SYNCED_AT, now)")
        )
        assertTrue(
            "published now rather than at the next bulk push",
            clear.contains("publish(context)")
        )
        assertTrue(
            "and Settings is told, so the open field empties",
            clear.contains("notifyPackChanged()")
        )
    }

    @Test
    fun `an import spends the removal record`() {
        val save = flat(engine)
            .substringAfter("fun savePack(context: Context, url: String, json: String): Boolean {")
            .substringBefore("suspend fun importFromUrl")
        assertTrue(
            "a pack exists again, so cleared_at must not survive to remove it on a sibling",
            save.contains("putLong(KEY_CLEARED_AT, 0L)")
        )
        assertTrue(
            "and the pack's own stamp moves, so a later removal is what wins",
            save.contains("putLong(KEY_SYNCED_AT, System.currentTimeMillis())")
        )
    }

    @Test
    fun `the payload carries the removal stamp`() {
        val build = flat(payload)
            .substringAfter("fun buildBadgePack(context: Context): JsonObject {")
            .substringBefore("fun buildHomeOrder(")
        assertTrue(
            "the stamp is what the other device decides on",
            build.contains("put( StreamBadgeEngine.KEY_CLEARED_AT,")
        )
        assertTrue(
            "and the pack itself is read through the store's own keys, not a second copy",
            build.contains("StreamBadgeEngine.PREFS_NAME") &&
                build.contains("getString(StreamBadgeEngine.KEY_PACK_URL") &&
                build.contains("getString(StreamBadgeEngine.KEY_PACK_JSON")
        )
    }

    @Test
    fun `the applier adopts a removal and clears the open field`() {
        val apply = flat(payload)
            .substringAfter("private fun applyBadgePack(")
            .substringBefore("private fun applyLibrary(")
        assertTrue(
            "the decision is the rules object's, not a second opinion here",
            apply.contains("BadgePackSyncRules.decide(")
        )
        assertTrue(
            "an adopted removal clears the pack in THIS profile's store",
            apply.contains("remove(StreamBadgeEngine.KEY_PACK_URL)") &&
                apply.contains("remove(StreamBadgeEngine.KEY_PACK_JSON)")
        )
        assertTrue(
            "records the sibling's stamp, never `now`: this device did not make the removal",
            apply.contains("putLong(StreamBadgeEngine.KEY_CLEARED_AT, clearedAt)") &&
                apply.contains("putLong(StreamBadgeEngine.KEY_SYNCED_AT, clearedAt)")
        )
        assertTrue(
            "an adopted pack spends the local removal record",
            apply.contains("putLong(StreamBadgeEngine.KEY_CLEARED_AT, 0L)")
        )
        assertTrue(
            "and both adoptions tell the open Settings field",
            apply.countOf("StreamBadgeEngine.notifyPackChanged()") == 2
        )
        assertFalse(
            "the never-configured guard must still be the rules object's, not an " +
                "early return that skips the removal branch",
            apply.contains("if (packJson.isBlank()) return")
        )
    }

    @Test
    fun `both fields re-seed on the pack's own revision`() {
        listOf("TV" to settingsTv, "phone" to settingsPhone).forEach { (form, src) ->
            val text = flat(src)
            assertTrue(
                "the $form field must re-seed when the pack changes, or a synced removal " +
                    "leaves the old URL on screen",
                text.contains(
                    "var badgePackInput by remember(profileRowKey, badgePackRevision) {"
                )
            )
            assertTrue(
                "seeded from the store, per profile",
                text.contains("mutableStateOf(StreamBadgeEngine.getPackUrl(context))")
            )
            assertTrue(
                "and the status line says a removal was a removal",
                text.contains("StreamBadgeEngine.wasRemoved(context) -> \"Badge pack removed\"")
            )
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun String.countOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
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
}
