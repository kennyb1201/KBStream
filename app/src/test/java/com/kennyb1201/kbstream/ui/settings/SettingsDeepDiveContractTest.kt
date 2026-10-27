package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings deep dive's findings, pinned where a device would meet them.
 *
 * Four of the five are the same shape: a control that describes a real thing
 * and then says nothing about it. Import applied a file the viewer had just
 * picked (with no question and no undo); the device-local rows on both screens
 * kept the previous profile's values after a switch; FORCE FULL RESYNC went
 * back to its idle label whether it had worked or not; and the phone opened
 * Settings on the first pane every time while the TV remembered the last one.
 *
 * This module carries no Compose test harness, so the contracts are read from
 * source - the same approach SettingsSearchContractTest and the sync-scope tests
 * take. Every assertion here names a failure that is SILENT on a device: a
 * dialog that exists but is not wired to the import, a key added to one screen
 * and not the other, an outcome string that is never drawn.
 */
class SettingsDeepDiveContractTest {

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
        const val SCREEN = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val PROFILE_KEY = "com/kennyb1201/kbstream/ui/settings/ProfileRowKey.kt"
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
    }

    /** Everything between two anchors, both of which must exist. */
    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing anchor: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing anchor: $end after $start", to > from)
        return source.substring(from, to)
    }

    /**
     * One function's body, from its header to the brace that closes it.
     *
     * The parameter list is walked FIRST, and that is not a detail: a default
     * value can spell an empty lambda (`onResult: (String) -> Unit = {}`), and
     * counting from the first brace of the header would take that pair for the
     * body and hand back a two-line "function". The assertions below would then
     * fail on source that is perfectly correct - a false alarm is as expensive
     * as a missed one.
     */
    private fun functionBody(source: String, header: String): String {
        val start = source.indexOf(header)
        assertTrue("missing function: $header", start >= 0)
        var i = source.indexOf('(', start)
        assertTrue("missing parameter list: $header", i >= 0)
        var parens = 0
        while (i < source.length) {
            when (source[i]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) {
                        i++
                        break
                    }
                }
            }
            i++
        }
        var depth = 0
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return source.substring(start)
    }

    private fun count(source: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(source).count()

    // ── C1: Import asks first ────────────────────────────────────────

    @Test
    fun `a picked backup file waits for an answer instead of applying`() {
        listOf(
            Triple(SCREEN, "pendingImportUri", "fun launchImportBackup()"),
            Triple(MOBILE, "pendingImport", "PaneScroll {")
        ).forEach { (path, state, end) ->
            val src = source(path)
            val launcher = between(src, "ActivityResultContracts.OpenDocument()", end)
            assertTrue(
                "$path: the file pick must only record the URI - the pick used to " +
                    "run BackupManager.import immediately, so a wrong file replaced a " +
                    "profile's history, marks and prefs with no question and no undo",
                launcher.contains("$state = uri") &&
                    !launcher.contains("BackupManager.import(")
            )
            // Exactly one place applies a file: the dialog's answer.
            assertTrue(
                "$path: the import must be reachable from ONE place - the confirm, " +
                    "not the launcher: ${count(src, "BackupManager.import(context, uri)")}",
                count(src, "BackupManager.import(context, uri)") == 1
            )
        }
    }

    @Test
    fun `the import dialog names what it replaces, and offers an export first`() {
        val tv = source(SCREEN)
        val tvDialog = functionBody(tv, "private fun SettingsImportBackupDialog(")
        listOf(
            "Import this backup?",
            "watch history",
            "cannot be undone",
            "EXPORT FIRST"
        ).forEach { phrase ->
            assertTrue("the TV dialog must name it: $phrase", tvDialog.contains(phrase))
        }
        assertTrue(
            "CANCEL takes focus in the TV dialog, so a double OK cannot import",
            tvDialog.contains("focusRequester(cancelFocusRequester)") &&
                tvDialog.contains("cancelFocusRequester.requestFocus()")
        )
        assertTrue(
            "the dialog's IMPORT is wired to the apply that the launcher no longer does",
            tvDialog.contains("onConfirm: () -> Unit") &&
                tv.indexOf("applyImport(uri)") > tv.indexOf("SettingsImportBackupDialog(")
        )
        assertTrue(
            "EXPORT FIRST answers the wrong-pick case with the export launcher",
            tv.contains("onExportFirst = {") &&
                between(tv, "onExportFirst = {", "onConfirm = {")
                    .contains("exportLauncher.launch(")
        )

        val mobile = source(MOBILE)
        listOf(
            "Import this backup?",
            "watch history",
            "cannot be undone",
            "EXPORT FIRST"
        ).forEach { phrase ->
            assertTrue("the phone dialog must name it: $phrase", mobile.contains(phrase))
        }
        val phonePane = functionBody(mobile, "private fun DataPaneMobile(")
        assertTrue(
            "the phone's confirm is the only path to the apply",
            phonePane.substringAfter("Dialog(onDismissRequest = { pendingImport = null })")
                .contains("applyImport(uri)")
        )
    }

    // ── C2: device-local rows follow the profile ─────────────────────

    @Test
    fun `the profile key is the one shared rule, and it reads the active profile`() {
        val helper = source(PROFILE_KEY)
        assertTrue(
            "the key is the active profile id",
            helper.contains("ProfileManager.activeProfile") &&
                helper.contains(".value?.id")
        )
        assertTrue(
            "and it exists because the store behind these rows is per profile",
            helper.contains("ProfileStorage.prefsName")
        )
        assertTrue(
            "it is internal to the module, not a public API",
            helper.contains("internal fun rememberProfileRowKey()")
        )
    }

    @Test
    fun `device-local rows re-seed on a profile switch on both screens`() {
        val tv = source(SCREEN)
        assertTrue(
            "the TV page binds the key once, at the top",
            tv.contains("val profileRowKey = rememberProfileRowKey()")
        )
        assertTrue(
            "and keys every device-local row on it: ${count(tv, "remember(profileRowKey)")}",
            count(tv, "remember(profileRowKey)") >= 25
        )
        // The regression's shape, named: the bare seed of a device-local pref.
        listOf(
            "var audioDecoder by remember {",
            "var bufferMode by remember {",
            "var aspectRatio by remember {",
            "var showProfilePickerOnLaunch by remember {",
            "var mdbListKeyInput by remember {"
        ).forEach { shape ->
            assertFalse(
                "a bare seed keeps the previous profile's value until Settings is " +
                    "reopened: $shape",
                tv.contains(shape)
            )
        }

        val mobile = source(MOBILE)
        assertTrue(
            "the phone keys its device-local rows the same way: " +
                "${count(mobile, "remember(profileRowKey)")}",
            count(mobile, "remember(profileRowKey)") >= 18
        )
        listOf(
            "private fun PlaybackPaneMobile()",
            "private fun VideoPaneMobile()"
        ).forEach { header ->
            assertTrue(
                "$header draws device-local rows, so it must re-seed on the profile",
                functionBody(mobile, header).contains("remember(profileRowKey)")
            )
        }
        assertTrue(
            "the integrations pane's profile-picker row is chosen per profile",
            functionBody(mobile, "private fun IntegrationsPaneMobile(")
                .contains("var showProfilePickerOnLaunch by remember(profileRowKey) {")
        )
        assertTrue(
            "an API-key card re-seeds from the stored value the caller passes for this " +
                "profile, so a switch cannot leave the previous key in the field",
            mobile.contains("var value by remember(loadedValue) {") &&
                mobile.contains("var saved by remember(loadedValue) {")
        )
        assertTrue(
            "the badge pack URL is per profile too",
            mobile.contains("var badgePackInput by remember(activeProfile?.id) {")
        )
    }

    // ── C4: a resync says what happened ──────────────────────────────

    @Test
    fun `force full resync answers every press, including the ones that do nothing`() {
        val sync = source(SYNC)
        val resync = functionBody(sync, "fun forceFullResync(")
        assertTrue(
            "the press takes a result callback",
            resync.contains("onResult: (String) -> Unit")
        )
        listOf(
            "RESYNC_SKIPPED",
            "RESYNC_ALREADY_RUNNING",
            "RESYNC_DONE",
            "RESYNC_NOTHING_ANSWERED"
        ).forEach { outcome ->
            assertTrue("$outcome must be reported, not swallowed", resync.contains(outcome))
        }
        assertTrue(
            "a failed run reports the failure rather than only logging it",
            resync.contains("onResult(\"\$RESYNC_FAILED")
        )
        assertTrue(
            "a pass in which no table answered is not a completed resync - the " +
                "timestamps only move when something was actually attempted",
            resync.contains("_lastSyncAtMs.value != syncedBefore")
        )
        listOf("RESYNC_DONE", "RESYNC_FAILED", "RESYNC_SKIPPED", "RESYNC_NOTHING_ANSWERED")
            .forEach { constant ->
                assertTrue(
                    "$constant is a shared constant, so both screens describe the same run",
                    sync.contains("const val $constant")
                )
            }
    }

    @Test
    fun `both screens draw the resync outcome`() {
        val tv = functionBody(source(SCREEN), "private fun SyncHealthSection(")
        assertTrue(
            "the TV card hands the outcome to state",
            tv.contains("forceFullResync(context) { outcome -> resyncStatus = outcome }")
        )
        assertTrue("and draws it", tv.contains("resyncStatus?.let { outcome ->"))
        assertTrue(
            "a failure reads as a failure, not as body copy",
            tv.contains("outcome.startsWith(SupabaseSync.RESYNC_FAILED) -> KBDanger")
        )

        val mobile = functionBody(source(MOBILE), "private fun SyncPaneMobile(")
        assertTrue(
            "the phone does the same",
            mobile.contains("forceFullResync(context) { outcome -> resyncStatus = outcome }") &&
                mobile.contains("resyncStatus?.let { outcome ->")
        )
        assertTrue(
            "with the same colour rule",
            mobile.contains("outcome.startsWith(SupabaseSync.RESYNC_FAILED) -> KBDanger")
        )
    }

    // ── C5: the phone remembers its pane ─────────────────────────────

    @Test
    fun `the phone reopens on the pane last read, with the tv fallback`() {
        val mobile = functionBody(source(MOBILE), "fun SettingsScreenMobile(")
        assertTrue(
            "the stored pane is read on open",
            mobile.contains("AppPreferences.getLastSettingsPane(context)")
        )
        assertTrue(
            "through the same stale-name fallback the TV page uses, so a pane " +
                "dropped by a later build cannot take out the screen",
            mobile.contains("SettingsPane.fromStored(it)")
        )
        assertTrue(
            "and written when a pane is opened",
            mobile.contains("AppPreferences.setLastSettingsPane(context, opened.name)")
        )
        assertFalse(
            "the first pane is no longer forced on open",
            mobile.contains("mutableStateOf<SettingsPane?>(null)")
        )
    }

    // ── C1, same class, lower stakes: unhide-all ─────────────────────

    @Test
    fun `unhide-all says it is one-way before the press`() {
        listOf(SCREEN, MOBILE).forEach { path ->
            val src = source(path)
            assertTrue(
                "$path: putting titles back is one press per title, so the row says " +
                    "so - no dialog, because nothing is lost",
                src.contains("— hiding them") &&
                    src.contains("is one press per title")
            )
        }
    }
}
