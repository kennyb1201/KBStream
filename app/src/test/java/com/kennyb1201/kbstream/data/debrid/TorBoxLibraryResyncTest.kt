package com.kennyb1201.kbstream.data.debrid

import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TorBox library sync's cure for the field symptom "the toggle is on, the
 * key is set, and the Library gains nothing".
 *
 * Three things were silent and irreversible: a mark written by a build whose
 * counting was wrong could never be revisited, a name that searched fine but
 * matched nothing was marked handled in ONE round, and every exit of the round
 * looked the same from a couch. This pins the three halves of the fix at the
 * seams a unit test can reach - the pure status-line wording and the no-match
 * budget directly, the rest as source contracts, because the round itself needs
 * Android, TMDB and a TorBox account.
 */
class TorBoxLibraryResyncTest {

    private fun source(path: String): String = File(sourceRoot(), path).readText()

    private fun normalized(path: String): String =
        source(path).replace(Regex("\\s+"), " ").trim()

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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    // ── The report the panes read ────────────────────────────────────────────

    @Test
    fun `a device that has never run a round says so`() {
        assertEquals("Not run yet on this device", torboxSyncStatusLine(null))
    }

    @Test
    fun `a caught-up round names the skips, an adding round names the adds`() {
        val zone = ZoneId.of("UTC")
        val at = Instant.parse("2026-10-23T14:02:00Z").toEpochMilli()

        assertEquals(
            "Last sync 14:02 — 132 in cloud, 3 added",
            torboxSyncStatusLine(
                round(scanned = 132, added = 3, skips = 129, at = at),
                zone
            )
        )
        assertEquals(
            "a caught-up round is the one that must not look like a broken one: " +
                "with nothing added, the skips are the fact worth naming",
            "Last sync 14:02 — 132 in cloud, 129 already handled",
            torboxSyncStatusLine(
                round(scanned = 132, added = 0, skips = 129, at = at),
                zone
            )
        )
        assertEquals(
            "a round that searched nothing at all says that too, rather than " +
                "reporting 0 added as if it had matched against something",
            "Last sync 14:02 — 4 in cloud, nothing added",
            torboxSyncStatusLine(
                round(scanned = 4, added = 0, skips = 0, at = at),
                zone
            )
        )
    }

    @Test
    fun `the gate exits say which gate stopped them`() {
        val zone = ZoneId.of("UTC")
        val at = Instant.parse("2026-10-23T14:02:00Z").toEpochMilli()

        assertEquals(
            "Last sync 14:02 — cloud is empty",
            torboxSyncStatusLine(outcomeRound(TorBoxLibrarySync.Outcome.CLOUD_EMPTY, at), zone)
        )
        assertEquals(
            "Last sync 14:02 — no TorBox key set",
            torboxSyncStatusLine(outcomeRound(TorBoxLibrarySync.Outcome.NO_KEY, at), zone)
        )
        assertEquals(
            "Last sync 14:02 — sync is off",
            torboxSyncStatusLine(outcomeRound(TorBoxLibrarySync.Outcome.DISABLED, at), zone)
        )
    }

    private fun round(scanned: Int, added: Int, skips: Int, at: Long) =
        TorBoxCloudStore.LastRound(
            outcome = TorBoxLibrarySync.Outcome.COMPLETED,
            scanned = scanned,
            added = added,
            handledSkips = skips,
            atMillis = at
        )

    private fun outcomeRound(outcome: TorBoxLibrarySync.Outcome, at: Long) =
        TorBoxCloudStore.LastRound(
            outcome = outcome,
            scanned = 0,
            added = 0,
            handledSkips = 0,
            atMillis = at
        )

    // ── The retry budget ─────────────────────────────────────────────────────

    @Test
    fun `a no-match is retried for the same three rounds an empty search gets`() {
        assertTrue(TorBoxCloudRules.retriesNoMatch(0))
        assertTrue(TorBoxCloudRules.retriesNoMatch(1))
        assertTrue(TorBoxCloudRules.retriesNoMatch(TorBoxCloudRules.EMPTY_SEARCH_ATTEMPTS - 1))
        assertFalse(
            "the third no-match is the one that marks the torrent, as for an " +
                "empty search",
            TorBoxCloudRules.retriesNoMatch(TorBoxCloudRules.EMPTY_SEARCH_ATTEMPTS)
        )
        assertEquals(
            "a blip budget and a no-match budget must not drift apart",
            TorBoxCloudRules.retriesEmptySearch(TorBoxCloudRules.EMPTY_SEARCH_ATTEMPTS - 1),
            TorBoxCloudRules.retriesNoMatch(TorBoxCloudRules.EMPTY_SEARCH_ATTEMPTS - 1)
        )
    }

    // ── The one-time forgiveness ─────────────────────────────────────────────

    @Test
    fun `the upgrade clears every mark once, guarded by a version in the same file`() {
        val store = normalized(STORE)

        assertTrue(
            "the guard is a VERSION, not a boolean: a future counting fix bumps it " +
                "instead of inventing a second flag",
            store.contains("private const val MARK_RESET_VERSION = 1") &&
                store.contains(
                    "private const val KEY_MARK_RESET_VERSION = " +
                        "\"torbox_library_mark_reset_version\""
                )
        )
        assertTrue(
            "and it is compared, so a store already past this version is not " +
                "cleared again (a downgrade, then an upgrade, must not wipe marks)",
            store.contains(
                "if (prefs.getInt(KEY_MARK_RESET_VERSION, 0) >= MARK_RESET_VERSION) return false"
            )
        )
        assertTrue(
            "the flag is written into the same file as the marks, which is " +
                "profile-scoped - so the clear is once per profile per device",
            store.contains("PREFS_NAME = \"kbstream_debrid\"") &&
                store.contains("ProfileStorage.prefsName(")
        )
        assertTrue(
            "it clears the handled set AND both retry streaks: a mark and a streak " +
                "written by the broken build are equally untrustworthy",
            store.contains("fun clearAllMarks()") &&
                store.contains(".remove(KEY_PROCESSED)") &&
                store.contains(".remove(KEY_EMPTY_ATTEMPTS)") &&
                store.contains(".remove(KEY_NO_MATCH_ATTEMPTS)")
        )
        assertTrue(
            "and the migration is the store's own, so the next round finishes it " +
                "even if this one returned before doing any work",
            store.contains("fun forgetPreCountingFixMarksOnce(): Boolean")
        )
    }

    @Test
    fun `the round runs the migration before it reads any mark`() {
        val sync = source(SYNC)
        val migration = sync.indexOf("store.forgetPreCountingFixMarksOnce()")
        val read = sync.indexOf("store.processed()")
        assertTrue("the migration must be called", migration >= 0)
        assertTrue("the round must still read the marks", read > 0)
        assertTrue(
            "clearing after the set was read would clear nothing this round",
            migration < read
        )
    }

    // ── The no-match budget, wired ──────────────────────────────────────────

    @Test
    fun `a torrent that matches nothing is retried instead of marked on sight`() {
        val sync = source(SYNC)
        val noMatch = sync.substringAfter("val match = TorBoxCloudRules.bestMatch(")

        assertTrue(
            "the streak is counted on the torrent's own record",
            noMatch.contains("store.noteNoMatch(torrent.id)")
        )
        assertTrue(
            "and it only marks through the budget rule",
            noMatch.indexOf("TorBoxCloudRules.retriesNoMatch(attempts)") in
                0..<noMatch.indexOf("store.markProcessed(torrent.id)")
        )
        assertTrue(
            "a torrent that starts matching loses its streak",
            noMatch.contains("store.clearNoMatch(torrent.id)")
        )

        val store = normalized(STORE)
        assertTrue(
            "the streak is stored apart from the handled set, like the empty one",
            store.contains("private const val KEY_NO_MATCH_ATTEMPTS = " +
                "\"torbox_library_no_matches\"")
        )
        assertTrue(
            "with the same 5 000 cap discipline",
            store.contains("private const val MAX_NO_MATCH_ATTEMPTS = 5000")
        )
        assertTrue(
            "and marking a torrent handled ends both streaks",
            store.substringAfter("fun markProcessed(")
                .substringBefore("fun clearAllMarks(")
                .let { it.contains("clearEmptySearch(torrentId)") && it.contains("clearNoMatch(torrentId)") }
        )
    }

    @Test
    fun `an unparseable name stays mark-on-sight, with the Resync named as its path`() {
        val sync = source(SYNC)
        val parseFailure = sync.substringAfter("if (parsed == null) {")
            .substringBefore("val candidates =")

        assertTrue(parseFailure.contains("store.markProcessed(torrent.id)"))
        assertTrue(
            "the asymmetry has to read as a decision: the input is immutable and " +
                "the parser deterministic, so retrying decides the same thing",
            parseFailure.contains("immutable") && parseFailure.contains("deterministic")
        )
        assertTrue(
            "and the Resync must be named as the way back in when the parser improves",
            parseFailure.contains("Resync")
        )
    }

    // ── Every exit files the report ─────────────────────────────────────────

    @Test
    fun `every exit of the round is recorded, gates included, with its outcome`() {
        val sync = normalized(SYNC)

        listOf(
            "Outcome.DISABLED",
            "Outcome.NO_KEY",
            "Outcome.DEFERRED",
            "Outcome.CLOUD_EMPTY",
            "outcome = Outcome.COMPLETED"
        ).forEach { outcome ->
            assertTrue("$outcome is never reported", sync.contains(outcome))
        }
        assertTrue(
            "each exit must go through the one place that files the report and " +
                "logs the line, or a path can return a report nobody ever sees",
            sync.contains("private fun file(store: TorBoxCloudStore, report: Report): Report")
        )
        assertEquals(
            "five exits, and each one files: the four gates (off, keyless, no " +
                "live profile, empty cloud) and the completed round",
            5,
            Regex("return file\\s*\\(").findAll(sync).count()
        )
        assertTrue(
            "the log line has to carry the outcome and the skip count, or the " +
                "field cannot tell \"all already handled\" from \"nothing matched\"",
            sync.contains("outcome=\${report.outcome}") &&
                sync.contains("\${report.handledSkips} already handled")
        )
        val store = source(STORE)
        assertTrue(
            "and the store must persist all five facts the line and the field " +
                "report need",
            store.contains("fun recordRound(report: TorBoxLibrarySync.Report)")
        )
        val recorded = store.substringAfter("fun recordRound(")
            .substringBefore("private fun readCounts(")
        listOf(
            "KEY_LAST_OUTCOME",
            "KEY_LAST_SCANNED",
            "KEY_LAST_ADDED",
            "KEY_LAST_HANDLED",
            "KEY_LAST_AT"
        ).forEach { key ->
            assertTrue("the round record is missing $key", recorded.contains(key))
        }
    }

    @Test
    fun `the skips are counted before the search, not inferred after it`() {
        val sync = source(SYNC)
        val skip = sync.substringAfter("if (torrent.id in handled) {")
            .substringBefore("val parsed =")
        assertTrue("a handled torrent is counted", skip.contains("skips++"))
        assertTrue("and skipped without spending a search", skip.contains("continue"))
        assertTrue(
            "the completed report carries the count",
            sync.contains("handledSkips = skips")
        )
    }

    // ── The Resync row, on both panes ───────────────────────────────────────

    @Test
    fun `a Resync clears the store and runs a round through the existing work`() {
        val worker = source(WORKER)
        val resync = worker.substringAfter("fun resyncNow(context: Context) {")
            .substringBefore("fun syncScheduleForPrefs(")

        assertTrue(
            "the clear comes first, or the round re-marks everything it already knew",
            resync.indexOf("clearAllMarks()") in 0..<resync.indexOf("syncScheduleForPrefs(")
        )
        assertTrue(
            "and it fires the same one-shot the toggle does, so REPLACE policy is " +
                "the existing one, not a second queue",
            resync.contains("runImmediate = true")
        )

        listOf(TV_SETTINGS, MOBILE_SETTINGS).forEach { pane ->
            val text = normalized(pane)
            assertTrue(
                "$pane must offer the Resync through that single path",
                text.contains("TorBoxLibraryWorker.resyncNow(")
            )
            assertTrue(
                "$pane must show the last round's line from the store's record",
                text.contains("torboxSyncStatusLine(") &&
                    text.contains("TorBoxCloudStore(context).lastRound()")
            )
            assertTrue(
                "$pane's row is gated exactly as the worker is",
                text.contains("AppPreferences.getTorboxLibrarySync(context) && " +
                    "AppPreferences.getTorboxApiKey(context).isNotBlank()")
            )
            assertTrue(
                "$pane reads the line on the sync revision, not a frozen first " +
                    "composition - a round on another box must move it",
                text.contains("remember(syncedRevision)")
            )
            assertTrue(
                "$pane must draw the exact row label the spec names",
                text.contains("Resync TorBox cloud") ||
                    text.contains("RESYNC TORBOX CLOUD")
            )
        }
    }

    private companion object {
        const val STORE = "com/kennyb1201/kbstream/data/debrid/TorBoxCloudStore.kt"
        const val SYNC = "com/kennyb1201/kbstream/data/debrid/TorBoxLibrarySync.kt"
        const val WORKER = "com/kennyb1201/kbstream/work/TorBoxLibraryWorker.kt"
        const val TV_SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SETTINGS =
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
    }
}
