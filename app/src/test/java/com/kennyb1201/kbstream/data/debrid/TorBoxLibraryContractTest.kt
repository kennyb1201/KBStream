package com.kennyb1201.kbstream.data.debrid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "add TorBox cloud files to Library" wiring, asserted at the seams a unit
 * test cannot reach.
 *
 * The decisions (how a name parses, which TMDB match) are pure and covered by
 * [TorBoxCloudRulesTest] and [TorBoxCloudListTest]. The rest is wiring: the
 * toggle has to exist, sync, arm a worker, and the round has to actually feed
 * the account's torrents into the Library. Each absent line fails silently.
 */
class TorBoxLibraryContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/debrid/TorBoxLibrarySync.kt"
        const val STORE = "com/kennyb1201/kbstream/data/debrid/TorBoxCloudStore.kt"
        const val WORKER = "com/kennyb1201/kbstream/work/TorBoxLibraryWorker.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val PREFS_PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SETTINGS =
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val APP = "com/kennyb1201/kbstream/MainApplication.kt"
    }

    @Test
    fun `the round adds the account's cloud torrents to the Library`() {
        val sync = source(SYNC)
        assertTrue("the round must read the TorBox cloud", sync.contains("cloudTorrents("))
        assertTrue("resolved titles go to My List", sync.contains("LibraryMirror.addToLibrary("))
        assertTrue(sync.contains("TorBoxCloudRules.bestMatch("))
        assertTrue("a key or toggle gate must short-circuit the round", sync.contains("getTorboxLibrarySync"))
    }

    @Test
    fun `the toggle exists and syncs`() {
        assertTrue(source(PREFS).contains("fun getTorboxLibrarySync("))
        assertTrue(source(PREFS).contains("fun setTorboxLibrarySync("))
        assertTrue(
            "a library preference belongs in the synced allow-list",
            source(PREFS_PAYLOAD).contains("\"torbox_library_sync\"")
        )
        assertTrue(
            "the setting needs a control in Settings",
            source(SETTINGS).contains("Add TorBox Cloud to Library")
        )
    }

    @Test
    fun `an empty search is retried before the torrent is recorded as handled`() {
        val sync = source(SYNC)
        assertTrue(
            "the round must count the empty answer instead of dropping it",
            sync.contains("store.noteEmptySearch(torrent.id)")
        )
        assertTrue(
            "and give up only through the budget rule",
            sync.contains("TorBoxCloudRules.retriesEmptySearch(attempts)")
        )
        assertTrue(
            "a search that produced candidates clears the streak",
            sync.contains("store.clearEmptySearch(torrent.id)")
        )

        val store = source(STORE)
        assertTrue(
            "the streak is kept apart from the processed set - the two answer " +
                "opposite questions",
            store.contains("torbox_library_empty_searches") &&
                store.contains("torbox_library_processed")
        )
        assertTrue(
            "and it is bounded like every other set this device keeps",
            store.contains("private const val MAX_EMPTY_ATTEMPTS = ")
        )
        assertTrue(
            "recording a torrent as handled ends its streak",
            store.substringAfter("fun markProcessed(").contains("clearEmptySearch(torrentId)")
        )
    }

    @Test
    fun `the worker is armed from the toggle and at startup`() {
        val worker = source(WORKER)
        assertTrue(worker.contains("fun syncScheduleForPrefs("))
        assertTrue(worker.contains("TorBoxLibrarySync.sync("))
        assertTrue(
            "the settings toggle must arm the round",
            source(SETTINGS).contains("TorBoxLibraryWorker")
        )
        assertTrue(
            "startup must re-arm the round, and run one there too: only the " +
                "periodic window used to be armed, so a launch after turning the " +
                "switch on still showed an empty Library until that fired",
            source(APP).contains("syncScheduleForPrefs(this, runImmediate = true)")
        )
    }

    @Test
    fun `both settings panes arm the round, not just write the switch`() {
        // The phone's switch was the field report: it wrote the preference and
        // nothing else, so turning it on armed no work at all - the first round
        // was up to a schedule window away, and only if the app was launched
        // again. The library stayed exactly as it was, with no error anywhere.
        assertTrue(
            "the TV switch arms the round as it is turned on (through the one " +
                "apply path the untoggle question also uses, so a switch that " +
                "asked first still arms or disarms exactly once)",
            source(SETTINGS).contains("syncScheduleForPrefs(context, runImmediate = enabled)") &&
                source(SETTINGS).contains("fun applyTorboxLibrarySync(enabled: Boolean)") &&
                source(SETTINGS).contains("applyTorboxLibrarySync(enabled)")
        )
        assertTrue(
            "and the phone switch must arm it too",
            source(MOBILE_SETTINGS).contains("syncScheduleForPrefs(ctx, runImmediate = enabled)")
        )
        listOf(SETTINGS, MOBILE_SETTINGS).forEach { path ->
            val pane = source(path)
            assertTrue(
                "$path: a key CHANGED after the switch was already on arms the " +
                    "round too - the switch's own call skipped it while the key " +
                    "was blank, and nothing else looked again until a launch",
                pane.contains("if (changed && key.isNotBlank() &&") &&
                    pane.contains("AppPreferences.getTorboxLibrarySync(context)")
            )
            assertTrue(
                "$path: and only a real change arms it, so tabbing through the " +
                    "field does not fire a round",
                pane.contains("val changed = key != AppPreferences.getTorboxApiKey(context)")
            )
        }
    }
}
