package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings screen's four minors (spec 2026-11-11).
 *
 * Each was a wiring gap rather than a design one, which is why they all failed
 * silently: a credential migrated into the wrong slot, a phone toggle that wrote
 * its pref and stopped, a gate the TV page has and the phone page did not, and
 * three settings panes with no search entries at all.
 *
 * Source-reading, like the rest of this screen's contracts: the rows live in two
 * Compose files and the migration in the preferences store, none of which this
 * module can compose in a unit test. Every pin below fails against the 0.9.223
 * source.
 */
class SettingsMinorsContractTest {

    private val prefs by lazy { source(PREFERENCES) }
    private val tv by lazy { source(TV_SCREEN) }
    private val mobile by lazy { source(MOBILE_SCREEN) }
    private val index by lazy { source(SEARCH) }
    private val theme by lazy { source(THEME) }

    // ── F1: the legacy ratings key lands under the MDBList name ────────────

    @Test
    fun `the migration has a cross-slot form that stamps the destination`() {
        assertTrue(
            "F1: a fromKey/toKey overload exists",
            prefs.contains("private fun migrateApiKey(context: Context, fromKey: String, toKey: String): String {")
        )
        val crossSlot = prefs
            .substringAfter("private fun migrateApiKey(context: Context, fromKey: String, toKey: String) {")
            .substringBefore("// ── Service credentials that travel with the account")
        assertTrue(
            "F1: it writes the secure store under the DESTINATION name",
            crossSlot.contains("apiKeyPrefs(context).edit().putString(toKey, legacy).apply()")
        )
        assertTrue(
            "F1: and stamps the destination, so the sync blob files the key under its own name",
            crossSlot.contains("stampApiKeyEdit(context, toKey, System.currentTimeMillis())")
        )
        assertTrue(
            "F1: the plaintext source slot is still dropped either way",
            crossSlot.contains("prefs(context).edit().remove(fromKey).apply()")
        )
        assertTrue(
            "F1: the same-name form every other getter uses is that overload with one name",
            prefs.contains(
                "private fun migrateApiKey(context: Context, keyName: String): String =\n" +
                    "        migrateApiKey(context, keyName, keyName)"
            )
        )
    }

    @Test
    fun `the MDBList fallback never lands under the OMDb key`() {
        assertTrue(
            "F1: the OMDb slot is migrated ACROSS to the MDBList slot",
            prefs.contains("migrateApiKey(context, KEY_OMDB_API_KEY, KEY_MDBLIST_API_KEY)")
        )
        assertFalse(
            "F1: the old same-name call put a ratings key under the OMDb name, which is what " +
                "broke MDBList on the second read and fed OMDb a foreign key",
            Regex("migrateApiKey\\(context, KEY_OMDB_API_KEY\\)").containsMatchIn(prefs)
        )
        assertTrue(
            "F1: the OMDb getter still reads its own slot, so a genuine key is served as itself",
            prefs.contains(
                "fun getOmdbApiKey(context: Context): String =\n" +
                    "        apiKeyPrefs(context).getString(KEY_OMDB_API_KEY, \"\")?.trim().orEmpty()"
            )
        )
        assertTrue(
            "F1: and pasting one still leaves the plaintext ratings slot alone, deliberate then " +
                "and now",
            prefs.contains(
                "// Deliberately NO prefs(context).edit().remove(KEY_OMDB_API_KEY) here:"
            ) &&
                prefs.contains("fun setOmdbApiKey(context: Context, key: String) {")
        )
        assertTrue(
            "F1: already-consumed devices are called out rather than guessed at",
            prefs.contains("cannot be repaired here") &&
                prefs.contains("indistinguishable from a genuine OMDb key")
        )
    }

    // ── F2: the phone toggles sync the workers, like the TV ones ───────────

    @Test
    fun `the mobile notification toggles run the same sync as the TV rows`() {
        val rows = mobile
            .substringAfter("SettingsSectionMobile(\"Notifications\", first = true)")
            .substringBefore("SettingsSectionMobile(\"Theme\")")
        assertTrue(
            "F2: the new-episode toggle cancels on off and runs an immediate round on on",
            rows.contains("com.kennyb1201.kbstream.work.NewEpisodeWorker.syncScheduleForPrefs(") &&
                rows.contains("runImmediate = it")
        )
        assertTrue(
            "F2: the reminders toggle cancels every armed alert on off and re-arms on on",
            rows.contains("com.kennyb1201.kbstream.work.ReminderWorker.syncSchedule(")
        )
        assertTrue(
            "F2: both stay inside the setter's own write, like the TV bodies",
            rows.indexOf("AppPreferences.setNewEpisodeNotifications(context, it)") <
                rows.indexOf("NewEpisodeWorker.syncScheduleForPrefs") &&
                rows.indexOf("AppPreferences.setLiveReminderNotifications(context, it)") <
                rows.indexOf("ReminderWorker.syncSchedule")
        )
        assertTrue(
            "F2: and the calls are guarded, so a WorkManager failure cannot crash a toggle",
            rows.contains("runCatching {")
        )
    }

    @Test
    fun `the TV rows and the launch sync points are untouched`() {
        assertTrue(
            "F2: TV still makes both calls",
            tv.contains("com.kennyb1201.kbstream.work.NewEpisodeWorker.syncScheduleForPrefs(") &&
                tv.contains("com.kennyb1201.kbstream.work.ReminderWorker.syncSchedule(")
        )
        assertTrue(
            "F2: the pref itself is unchanged - the sync is the fix, not a new stored value",
            prefs.contains("fun setNewEpisodeNotifications(context: Context, enabled: Boolean)") &&
                prefs.contains("fun setLiveReminderNotifications(context: Context, enabled: Boolean)")
        )
    }

    // ── F3: the phone's pure-black row is gated on AMOLED ──────────────────

    @Test
    fun `the mobile pure-black row is gated the way the TV row is`() {
        val row = mobile
            .substringAfter("label = \"Pure black surfaces\",")
            .substringBefore("// 24-hour clock is a display-format choice")
        assertTrue("F3: it is disabled with AMOLED off", row.contains("enabled = amoled,"))
        assertTrue(
            "F3: and it SHOWS off, so a stored ON cannot look active on default surfaces",
            row.contains("checked = pureBlack && amoled,")
        )
        assertTrue(
            "F3: the requirement is stated, in the TV page's own words",
            row.contains("Requires AMOLED Black.")
        )
        assertTrue(
            "F3: the stored value is still written when the row is moved",
            row.contains("AppPreferences.setPureBlackSurface(context, it)")
        )
    }

    @Test
    fun `the theme still needs both switches and the TV row still gates on them`() {
        assertTrue(
            "F3: the rule the gate mirrors",
            theme.contains("kbAmoledBlackState.value") &&
                theme.contains("kbPureBlackSurfaceState.value")
        )
        assertTrue(
            "F3: TV's own gate is unchanged",
            tv.contains("checked = pureBlackSurface && amoledBlack") &&
                tv.contains("checkedOverride = if (amoledBlack) null else false")
        )
    }

    // ── F4: search covers VIDEO, LANGUAGE and SUBTITLES ────────────────────

    /** The index's declared keys, in order. */
    private val declaredKeys: List<String> by lazy {
        Regex("SettingSearchEntry\\(\\s*\\n\\s*\"([^\"]+)\"")
            .findAll(index)
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `every audio, picture, language and subtitle row has an index entry`() {
        val missing = listOf(
            "playback.engine",
            "video.audio-decoder",
            "video.audio-output",
            "video.audio-downmix",
            "video.volume-boost",
            "video.network-buffer",
            "video.tunneling",
            "video.pip",
            "video.aspect-ratio",
            "language.audio",
            "subtitles.language",
            "subtitles.mode",
            "subtitles.clean-sdh",
            "subtitles.size",
            "subtitles.background",
            "subtitles.position"
        ).filterNot { declaredKeys.contains(it) }
        assertTrue("F4: rows drawn but searchable by nothing: $missing", missing.isEmpty())
    }

    @Test
    fun `the three panes are in the index and phrased the way a viewer types`() {
        assertTrue(
            "F4: VIDEO rows say which pane they live in",
            index.contains("\"video.audio-decoder\",\n            SettingsPane.VIDEO,") &&
                index.contains("\"video.aspect-ratio\",\n            SettingsPane.VIDEO,")
        )
        assertTrue(
            "F4: and so does LANGUAGE",
            index.contains("\"language.audio\",\n            SettingsPane.LANGUAGE,")
        )
        listOf("subtitles.language", "subtitles.mode", "subtitles.size").forEach { key ->
            assertTrue(
                "F4: $key is declared under the Subtitles pane",
                index.contains("\"$key\",\n            SettingsPane.SUBTITLES,")
            )
        }
        // The words the spec's settlers type, against the entry that has to
        // answer: the hunt is for the ROW, not for a pane heading.
        listOf(
            "decoder" to "video.audio-decoder",
            "tunneling" to "video.tunneling",
            "subtitle size" to "subtitles.size"
        ).forEach { (term, key) ->
            val entry = index
                .substringAfter("\"$key\",")
                .substringBefore("),")
            assertTrue(
                "F4: searching \"$term\" must reach $key, but its keywords do not carry it",
                entry.contains(term, ignoreCase = true)
            )
        }
    }

    @Test
    fun `the phone's own wording finds those rows by key, and the TV's chips carry anchors`() {
        listOf(
            "video.audio-decoder" to "Decoder priority",
            "video.audio-output" to "Output",
            "video.audio-downmix" to "Downmix",
            "video.volume-boost" to "Volume boost",
            "video.network-buffer" to "Network buffer",
            "video.aspect-ratio" to "Default aspect ratio",
            "playback.engine" to "Engine",
            "language.audio" to "Preferred audio language",
            "subtitles.language" to "Preferred subtitle language",
            "subtitles.size" to "Default size",
            "subtitles.background" to "Default background"
        ).forEach { (key, label) ->
            val start = mobile.indexOf("label = \"$label\",")
            assertTrue("F4: the phone row \"$label\" is gone", start >= 0)
            val window = mobile.substring(start, minOf(start + 300, mobile.length))
            assertTrue(
                "F4: the phone row \"$label\" does not name $key, so its entry can never be " +
                    "found there",
                window.contains("searchKey = \"$key\"")
            )
        }
        assertTrue(
            "F4: a chip row is anchored at its own call site, or a result scrolls nowhere",
            tv.contains("SettingsSearchIndex.entryForLabel(\"Audio Decoder\")") &&
                tv.contains("SettingsSearchIndex.entryForLabel(\"Mode\")") &&
                tv.contains("SettingsSearchIndex.entryForLabel(\"Playback engine\")")
        )
        assertTrue(
            "F4: the custom tuning rows bind by their own label, like the primitives",
            tv.contains(".searchAnchor(SettingsSearchIndex.entryForLabel(label))")
        )
        assertTrue(
            "F4: the tunneled row is TV-only, so the phone's results never name a row it lacks",
            index.substringAfter("internal val TV_ONLY_SEARCH_KEYS: Set<String> = setOf(")
                .substringBefore("\n)")
                .contains("\"video.tunneling\"")
        )
    }

    @Test
    fun `the matching rule and the row order are not the change`() {
        assertTrue(
            "F4: still a case-insensitive substring of label or keywords",
            index.contains("entry.label.contains(q, ignoreCase = true)") &&
                index.contains("entry.keywords.contains(q, ignoreCase = true)")
        )
        assertTrue(
            "F4: the cap and the flash window are untouched",
            index.contains("SETTINGS_SEARCH_MAX_RESULTS = 30") &&
                index.contains("SETTINGS_SEARCH_FLASH_MS = 1_200L")
        )
    }

    // ── source plumbing ────────────────────────────────────────────────────

    private fun source(relative: String): String {
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
        const val PREFERENCES = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val TV_SCREEN = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SCREEN = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt"
        const val THEME = "com/kennyb1201/kbstream/ui/theme/Theme.kt"
    }
}
