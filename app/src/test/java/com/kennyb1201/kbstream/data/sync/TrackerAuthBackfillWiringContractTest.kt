package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enrollment backfill's wiring (spec 2026-11-14).
 *
 * The rules are unit tested in [TrackerAuthBackfillRulesTest]. This pins the
 * three things a JVM test cannot exercise but every silent failure lives in:
 * WHEN the backfill runs (after a pull that actually ran, so a fresh device
 * cannot offer its blanks before ingesting the cloud), WHAT it does (enqueue into
 * the ordinary outbox - never a second push path), and WHEN a marker is written
 * (only on a chunk that really uploaded).
 *
 * Pinned by reading the source, like the rest of the sync contracts: the layer
 * talks to Supabase, SharedPreferences and the active profile, so nothing here
 * can be driven from a plain JVM test.
 */
class TrackerAuthBackfillWiringContractTest {

    private val sync by lazy { readSource(SYNC) }
    private val payload by lazy { readSource(PAYLOAD) }

    // ── F2: the ordering ──────────────────────────────────────────────────

    @Test
    fun `the backfill is asked after a pull that ran, and nowhere else`() {
        val pull = functionBody(sync, "private suspend fun pullAllNow(context: Context) {")
        val ask = pull.indexOf("runCatchingCancellable { backfillUnpublishedTrackerAuth(context) }")
        assertTrue("the pull must ask: $pull", ask >= 0)
        assertTrue(
            "after the four pulls, so a fresh device has already ingested the cloud " +
                "before it offers anything of its own",
            pull.indexOf("pullOverrides(context)") in 0 until ask
        )
        assertTrue(
            "and after the all-failed guard: a pull in which every table threw is not " +
                "a pull to hand a device's sessions to",
            pull.indexOf("if (ran.none { it }) return") in 0 until ask
        )
        assertEquals(
            "one trigger, on the one path every sign-in / start / Sync now pull " +
                "goes through - no second, unsequenced caller",
            1,
            countOf(sync, "backfillUnpublishedTrackerAuth(context)")
        )
    }

    // ── F1: enqueue only, over the enrolled set ───────────────────────────

    @Test
    fun `the backfill enqueues into the ordinary outbox`() {
        val body = functionBody(
            sync,
            "private suspend fun backfillUnpublishedTrackerAuth(context: Context) {"
        )
        assertTrue(
            "the existing queue, so the push rides the existing flush",
            body.contains("enqueuePrefs(context, prefKey, payload, pid)")
        )
        listOf("upsert(", "pushPrefsBlobs(", "pushAllNow(", "pullAllNow(", "postgrest")
            .forEach { forbidden ->
                assertFalse(
                    "the backfill must not grow a second path ($forbidden): it enqueues, " +
                        "and the flush pushes",
                    body.contains(forbidden)
                )
            }
        assertTrue(
            "and it refuses a signed-out or client-less start before touching anything",
            body.contains("if (!isSignedIn() || client == null) return")
        )
    }

    @Test
    fun `the set it reads is the enrolled trackers, with their own publish gates`() {
        val enrolled = payload.substringAfter("private val ENROLLED_TRACKER_AUTH =")
            .substringBefore("fun isTrackerAuthKey")
        listOf("KEY_KITSU_AUTH", "KEY_MAL_AUTH", "KEY_ANILIST_AUTH").forEach { key ->
            assertTrue(
                "the backfill is written over the enrolled set, so a newly enrolled " +
                    "tracker ($key) is carried without changing the backfill",
                enrolled.contains(key)
            )
        }
        val entries = functionBody(
            payload,
            "fun trackerAuthBackfillEntries("
        )
        listOf(
            "hasKitsuAuthToPublish(context)",
            "hasMalAuthToPublish(context)",
            "hasAnilistAuthToPublish(context)"
        ).forEach { gate ->
            assertTrue(
                "each session's gate travels with its blob ($gate): a profile that never " +
                    "connected publishes nothing, so no blank can be seeded",
                entries.contains(gate)
            )
        }
        assertTrue(
            "and the fingerprint is taken from the same blob the gate was asked about",
            entries.contains("TrackerAuthBackfillRules.fingerprint(payload)")
        )
    }

    @Test
    fun `the backfill resolves the active profile and refuses a switch mid-read`() {
        val body = functionBody(
            sync,
            "private suspend fun backfillUnpublishedTrackerAuth(context: Context) {"
        )
        assertTrue(
            "the sessions are read from the active profile's stores",
            body.contains("val pid = currentProfileId()")
        )
        assertTrue(
            "and filed under it, after verifying it is still the active one - the same " +
                "rule the bulk push follows, so one profile's session cannot land on another",
            body.contains("PushScopeRules.scopeStillActive(pid, currentProfileId())")
        )
        assertTrue(
            "the marker is looked up by the SCOPED key, so two profiles are two answers",
            body.contains("lastPublishedTrackerAuth(context, scopedKey(entry.prefKey, pid))")
        )
    }

    // ── F1: the durable marker ────────────────────────────────────────────

    @Test
    fun `the marker is durable, not the in-memory diagnostics flow`() {
        assertTrue(
            "the answer has to survive the process that asks it: a StateFlow does not",
            sync.contains(
                "syncPrefs(context).getString(TrackerAuthBackfillRules.markerKey(storedKey), null)"
            )
        )
        val recorder = functionBody(
            sync,
            "private fun recordPublishedTrackerAuth(row: OutboxItem) {"
        )
        assertTrue(
            "and it is written into the same store",
            recorder.contains("syncPrefs(appContext).edit()") &&
                recorder.contains("TrackerAuthBackfillRules.markerKey(row.key)")
        )
        assertTrue(
            "keyed by the STORED (profile-scoped) key the flush uploaded",
            recorder.contains("PrefsPayloadBuilder.isTrackerAuthKey(unscopedKey(row.key))")
        )
    }

    @Test
    fun `the marker is written only by a push that landed`() {
        val flush = functionBody(sync, "private suspend fun flushOutbox(): Int {")
        val removedAt = flush.indexOf("if (outbox.remove(row)) {")
        val recordedAt = flush.indexOf("recordPublishedTrackerAuth(row)")
        assertTrue("the removal is the success path", removedAt >= 0)
        assertTrue(
            "and the marker rides it: a failed chunk leaves the marker alone, so the " +
                "session is still due on the next start",
            recordedAt > removedAt
        )
        assertFalse(
            "it must never be stamped anywhere a chunk merely STARTED",
            flush.substringBefore("recordPublishedTrackerAuth(row)").contains("anyChunkOk = true")
        )
    }

    @Test
    fun `the marker records the payload that was uploaded, not a rebuild`() {
        val recorder = functionBody(
            sync,
            "private fun recordPublishedTrackerAuth(row: OutboxItem) {"
        )
        assertTrue(
            "a rebuild would carry a new access token that never reached the cloud",
            recorder.contains("TrackerAuthBackfillRules.fingerprint(row.payload)")
        )
        assertFalse(
            "and the marker is a digest, never the session itself",
            recorder.contains("putString(") && recorder.contains("accessToken")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun readSource(path: String): String {
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

    private fun countOf(text: String, token: String): Int {
        var found = 0
        var from = text.indexOf(token)
        while (from >= 0) {
            found++
            from = text.indexOf(token, from + token.length)
        }
        return found
    }

    /** [header] through the function's own closing brace. */
    private fun functionBody(source: String, header: String): String {
        val start = source.indexOf(header)
        assertTrue("header not found: $header", start >= 0)
        val stop = source.indexOf("\n    }\n", start + header.length)
        assertTrue("body end not found for: $header", stop > start)
        return source.substring(start, stop)
    }

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
    }
}
