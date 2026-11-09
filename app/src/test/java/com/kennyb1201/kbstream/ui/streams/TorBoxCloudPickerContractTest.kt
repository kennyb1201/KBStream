package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seams the TorBox cloud-file rows are wired through (spec T2, T3, T8, T11,
 * F0a-F0e, F4, F5b).
 *
 * The rules themselves are pure tests ([com.kennyb1201.kbstream.data.debrid
 * .TorBoxCloudPickerTest]); what is left is the part a pure test cannot see:
 * which gates stand in front of the fetch, that OFF is a true no-op, that the
 * offer row is the only thing OFF ever adds, and that neither the add-on hash
 * path nor the library sync grew a second opinion.
 */
class TorBoxCloudPickerContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/streams/StreamsViewModel.kt"
        const val TV_SCREEN = "com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt"
        const val MOBILE_SCREEN = "com/kennyb1201/kbstream/ui/mobile/StreamsScreenMobile.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val TV_SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SETTINGS = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val PICKER = "com/kennyb1201/kbstream/data/debrid/TorBoxCloudPicker.kt"
        const val RESOLVE = "com/kennyb1201/kbstream/data/debrid/TorBoxResolve.kt"
        const val WALK = "com/kennyb1201/kbstream/domain/streamengine/AutoplayWalk.kt"
        const val SYNC = "com/kennyb1201/kbstream/data/debrid/TorBoxLibrarySync.kt"
        const val GATES = "com/kennyb1201/kbstream/data/download/DownloadGates.kt"
    }

    // ── F0 / T8: the gates, in front of the fetch ────────────────────────────

    @Test
    fun `no key means no fetch, no rows and no offer`() {
        val model = source(VIEW_MODEL)
        val request = model.substringAfter("private fun requestCloudRows(")
            .substringBefore("    /**\n     * Makes sure")
        assertTrue(
            "a keyless device must not even ask (spec F0a)",
            request.contains("if (!hasDebridServiceConfigured()) return")
        )
        assertTrue(
            "live TV has no title to match against",
            request.contains("if (contentType != \"series\" && contentType != \"movie\") return")
        )
        assertTrue(
            "the ask is on the cache, never a blocking call in the publish path",
            request.contains("TorBoxCloudCache.fresh(getApplication())")
        )
        assertTrue(
            "and a superseded answer may not publish itself",
            request.contains("if (ask != cloudAsk || cloudTargetKey != key) return@launch")
        )
    }

    // ── F4 / T2 / T3: OFF is a no-op except the one offer ────────────────────

    @Test
    fun `cloud rows are published only while the per-profile toggle is on`() {
        val model = source(VIEW_MODEL)
        val publish = model.substringAfter("private fun publishEffective()")
            .substringBefore("    /**\n     * Asks for the account's cloud files")
        assertTrue(
            "the toggle is the merge's only switch",
            publish.contains(
                "AppPreferences.getTorboxCloudRows(getApplication()) && cloudMatches.isNotEmpty()"
            )
        )
        assertTrue(
            "and the placement is the rule's, after the TorBox rows",
            publish.contains("TorBoxCloudPicker.insertIndex(baseStreams)")
        )
        assertTrue(
            "the add-on names stay aligned with the rows",
            publish.contains("List(rows.size) { null }")
        )
    }

    @Test
    fun `the offer row appears only when a file matches and the feature is off`() {
        val model = source(VIEW_MODEL)
        val request = model.substringAfter("private fun requestCloudRows(")
            .substringBefore("    /**\n     * Makes sure")
        assertTrue(
            "one row, only when there is something to offer (spec F4c) - and never " +
                "when the rows are already on (F4d)",
            request.contains("_cloudOffer.value = matches.isNotEmpty() &&") &&
                request.contains("!AppPreferences.getTorboxCloudRows(getApplication())")
        )
        val reveal = model.substringAfter("fun revealCloudRows()")
            .substringBefore("\n    }")
        assertTrue(
            "the press switches the profile's toggle on",
            reveal.contains("AppPreferences.setTorboxCloudRows(getApplication(), true)")
        )
        assertTrue(
            "and reveals the rows already in hand rather than fetching again",
            reveal.contains("_cloudOffer.value = false") && reveal.contains("publishEffective()")
        )
        assertFalse("no I/O in the reveal", reveal.contains("fresh("))
    }

    @Test
    fun `a new target drops the previous target's cloud rows before publishing`() {
        val model = source(VIEW_MODEL)
        val reset = model.substringAfter("fun load(")
            .substringBefore("val debugLines")
        val cleared = reset.indexOf("cloudMatches = emptyList()")
        val published = reset.indexOf("publishStreams(emptyList(), emptyList())")
        assertTrue("both halves are cleared", cleared >= 0 && reset.contains("cloudTargetKey = null"))
        assertTrue(
            "cleared BEFORE the publish: afterwards, the old file would paint on the new episode",
            cleared < published
        )
    }

    // ── both surfaces ───────────────────────────────────────────────────────

    @Test
    fun `both pickers draw the offer from the shared ViewModel and prepare cloud rows`() {
        listOf(source(TV_SCREEN), source(MOBILE_SCREEN)).forEach { screen ->
            assertTrue(
                "the offer is the ViewModel's state, not a screen-side guess",
                screen.contains("viewModel.cloudOffer.collectAsStateWithLifecycle()")
            )
            assertTrue(
                "pressing it is the ViewModel's action",
                screen.contains("viewModel.revealCloudRows()")
            )
            assertTrue(
                "and a cloud row goes through the resolver on the press, like a hash row",
                screen.contains("TorBoxResolve.needsPreparation(selected)")
            )
            assertTrue(
                "unpressed cloud rows never reach the player as addresses",
                screen.contains("TorBoxCloudPicker.forPlayer(")
            )
        }
    }

    @Test
    fun `both settings panes offer the toggle and the cloud list`() {
        assertTrue(
            "the TV pane renders the switch beside the other TorBox rows",
            source(TV_SETTINGS).contains("label = \"TorBox cloud files in stream picker\"") &&
                source(TV_SETTINGS).contains("AppPreferences.setTorboxCloudRows(context, it)")
        )
        assertTrue(
            "and the list itself is reachable from the same pane",
            source(TV_SETTINGS).contains("label = \"TorBox cloud files\"") &&
                source(TV_SETTINGS).contains("TorBoxCloudListDialog(")
        )
        assertTrue(
            "the phone pane has the same switch and the same list",
            source(MOBILE_SETTINGS).contains("label = \"TorBox cloud files in stream picker\"") &&
                source(MOBILE_SETTINGS).contains("AppPreferences::setTorboxCloudRows") &&
                source(MOBILE_SETTINGS).contains("TorBoxCloudListDialog(")
        )
    }

    // ── F4a: off by default, per profile, synced ────────────────────────────

    @Test
    fun `the toggle is off by default and follows the profile`() {
        val prefs = source(PREFS)
        assertTrue(
            "default OFF, by the one constant a later flip would change",
            prefs.contains("KEY_TORBOX_CLOUD_ROWS = \"torbox_cloud_rows\"") &&
                prefs.contains("getBoolean(KEY_TORBOX_CLOUD_ROWS, false)")
        )
        val setter = prefs.substringAfter("fun setTorboxCloudRows(")
            .substringBefore("\n    }")
        assertTrue(
            "and every write reports the change, which is the picker's cue to re-merge",
            setter.contains("syncDisplayPrefsBlob(context)")
        )
        val payload = source(PAYLOAD)
        val start = payload.indexOf("internal val SYNCED_PREF_KEYS = setOf(")
        val end = payload.indexOf("\n    )", start)
        assertTrue(
            "a per-profile viewing preference, so it rides the profile's blob",
            payload.substring(start, end).contains("\"torbox_cloud_rows\"")
        )
    }

    // ── the player and the resolver ─────────────────────────────────────────

    @Test
    fun `auto-play never treats a cloud reference as a ready link`() {
        val walk = source(WALK)
        assertTrue(
            "the walk prepares a cloud row like a hash row",
            walk.contains(
                "!candidate.url.isNullOrBlank() && !TorBoxCloudPicker.isCloudRow(candidate)"
            )
        )
        assertTrue(
            "and clears every other cloud reference out of the hand-off",
            walk.contains("TorBoxCloudPicker.forPlayer(sourcesFor(")
        )
        val resolve = source(RESOLVE)
        assertTrue(
            "the resolver mints the link through the same requestdl path",
            resolve.contains("playCloudFile(context, stream, ref)") &&
                resolve.contains("requestDownloadUrl(apiKey, ref.torrentId, ref.fileId)")
        )
        assertTrue(
            "and a download of a cloud row resolves it first",
            source(GATES).contains("TorBoxCloudPicker.isCloudRow(stream) ||")
        )
    }

    // ── F1d / F3b / F5a: refresh, laziness, and no Library writes ───────────

    @Test
    fun `the cache is bounded and shared with the sync`() {
        val picker = source(PICKER)
        assertTrue(
            "15 minutes, as the spec says",
            picker.contains("const val TTL_MS = 15 * 60_000L")
        )
        assertTrue(
            "a failed request is not cached as an empty cloud",
            picker.contains("if (answer == null) return@withLock held.orEmpty()")
        )
        assertTrue(
            "the sync's own read feeds the picker's cache",
            source(SYNC).contains("TorBoxCloudCache.seed(torrents)")
        )
        assertTrue(
            "and the files are only read for names that already matched a title",
            picker.contains("if (!TorBoxCloudRules.confidentMatch(parsed, wantedTitle, year)) continue")
        )
    }

    @Test
    fun `this feature writes nothing to the Library`() {
        val picker = source(PICKER)
        listOf("LocalLibraryStore", "markProcessed", "TitleProfileOwnership", "tombstone")
            .forEach { marker ->
                assertFalse(
                    "the cloud picker must not touch the Library or the sync's marks: $marker",
                    picker.contains(marker)
                )
            }
        assertTrue(
            "the sync still reads the same torrent list it always did",
            source(SYNC).contains("val torrents = TorBoxClient.cloudTorrents(context)")
        )
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
