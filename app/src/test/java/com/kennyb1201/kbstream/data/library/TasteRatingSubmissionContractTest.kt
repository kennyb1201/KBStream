package com.kennyb1201.kbstream.data.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracker-submission wiring, read out of the sources it lives in.
 *
 * Every promise this feature makes is an insertion at an existing seam (two API
 * services, one store hook, the two connect flows, the diagnostics report), and an
 * insertion is exactly what a later refactor can drop without a compiler
 * complaint. The pins, in the order the spec states them:
 *
 *  - two endpoints per tracker, an add and a remove, and no inbound ratings READ
 *    (v1 never reads a rating it did not write) and no MDBList call at all;
 *  - the existing write discipline reused, not reinvented: the token first, then
 *    the leg's own guard, then the repository's OWN id resolution (no second id
 *    grammar), then the call, then `noteRetryAfter` on every answer;
 *  - failures are silent to the viewer: no toast, no snackbar, no worker;
 *  - the store is the ONE enqueue point, and no taste surface (thumbs row, menu,
 *    dialog) knows the trackers exist;
 *  - a server-requested pause is not an attempt: the PAUSED branches do nothing,
 *    and only a real no-answer advances an entry;
 *  - both legs are behind their landing-gate constant, and that constant is the
 *    only switch: the live probe has not been run, so the legs are ON by the
 *    account owner's decision (2026-10-29) and OFF again is the same one line.
 */
class TasteRatingSubmissionContractTest {

    // ── the endpoints ────────────────────────────────────────────────

    @Test
    fun `each tracker gains an add and a remove, and nothing else`() {
        listOf(TRAKT_API, SIMKL_API).forEach { path ->
            val src = source(path)
            assertEquals(
                "$path must post exactly one add",
                1,
                count(src, "@POST(\"sync/ratings\")")
            )
            assertEquals(
                "$path must post exactly one remove",
                1,
                count(src, "@POST(\"sync/ratings/remove\")")
            )
            assertFalse(
                "$path must not read ratings: v1 never reads a rating it did not write",
                src.contains("@GET(\"sync/ratings")
            )
        }
    }

    @Test
    fun `no OAuth scope is requested, because there is none to request`() {
        val request = source(TRAKT_MODELS)
            .substringAfter("data class TraktDeviceCodeRequest(")
            .take(200)
        assertFalse(
            "Trakt's device-code body carries a client id only; a scope field would be new",
            request.contains("scope")
        )
    }

    @Test
    fun `MDBList gains nothing, having no rating-write surface`() {
        val mdb = source(MDBLIST)
        assertFalse("no ratings write is added to MDBList", mdb.contains("sync/ratings"))
        assertFalse(mdb.contains("addRatings"))
        assertFalse(mdb.contains("removeRatings"))
    }

    // ── the reused write discipline ──────────────────────────────────

    @Test
    fun `the Trakt leg reuses the token, the guard and the id grammar`() {
        val src = source(TRAKT_WRITES)

        assertTrue("the token comes first, refreshed under the repository's lock",
            src.contains("validAccessToken()"))
        assertTrue("every call spends a guard slot", src.contains("guard.tryAcquire()"))
        assertTrue("a denial is a pause, not a failure",
            src.contains("if (!guard.tryAcquire()) return TraktRatingOutcome.PAUSED"))
        assertTrue("the repository's OWN id resolution, not a second grammar",
            src.contains("idsFor(parentId, tmdbId)"))
        assertTrue("a spent budget or 429 parks every Trakt path",
            src.contains("noteRetryAfter(response)"))
        assertTrue("cancellation is rethrown, never recorded as a failure",
            src.contains("runCatchingCancellable"))
        // ON without a live probe, on the owner's call (2026-10-29); flipping
        // this one constant back to false is the whole rollback.
        assertTrue("the landing gate is the only switch, and it is open",
            src.contains("TRAKT_RATINGS_ENABLED = true"))
        assertFalse("and no production code path keeps a second, closed gate",
            src.contains("TRAKT_RATINGS_ENABLED = false"))
    }

    @Test
    fun `the Simkl leg reuses its own helpers and carries its own small guard`() {
        val src = source(SIMKL_WRITES)

        assertTrue("the repository's own id resolution, not a second grammar",
            src.contains("parsePlaybackIds(parentId, tmdbId)"))
        assertTrue("the same per-call auth header every other Simkl write uses",
            src.contains("trackedAuthHeader()"))
        assertTrue("unconfigured or unauthenticated is a pause, never a failure",
            src.contains("if (!isConfigured() || !hasToken()) return SimklRatingOutcome.PAUSED"))
        assertTrue("a politeness floor, local to this leg",
            src.contains("minSpacingMs: Long = 2_000L"))
        assertTrue("a 429 without a header waits the Trakt default",
            src.contains("DEFAULT_RETRY_AFTER_SECONDS = 60L"))
        // Same for Simkl: enabled by the owner, not by a probe verdict.
        assertTrue("the landing gate is the only switch, and it is open",
            src.contains("SIMKL_RATINGS_ENABLED = true"))
        assertFalse("and no production code path keeps a second, closed gate",
            src.contains("SIMKL_RATINGS_ENABLED = false"))
    }

    // ── one enqueue point, and silence the viewer never sees ─────────

    @Test
    fun `the store is the only thing that enqueues`() {
        assertEquals(
            "exactly one enqueue call site, in the store's own write path",
            1,
            count(source(TASTE_SIGNALS), "TasteRatingSubmission.onSignalWritten(")
        )
        assertFalse(
            "the store goes through the submission hook, never the queue directly",
            source(TASTE_SIGNALS).contains("TasteRatingQueue.enqueue(")
        )
    }

    @Test
    fun `no taste surface knows the trackers exist`() {
        listOf(
            "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt",
            "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt",
            "com/kennyb1201/kbstream/ui/components/PosterContextMenu.kt",
            "com/kennyb1201/kbstream/ui/components/touch/TouchContextMenu.kt",
            "com/kennyb1201/kbstream/ui/components/TitleRatingDialog.kt"
        ).forEach { path ->
            assertFalse(
                "$path must reach the trackers only by writing through the store",
                source(path).contains("TasteRatingSubmission")
            )
        }
    }

    @Test
    fun `a failed submission is never shown to the viewer`() {
        listOf(TRAKT_WRITES, SIMKL_WRITES, SUBMISSION).forEach { path ->
            val src = source(path)
            assertFalse("$path must not toast", src.contains("Toast"))
            assertFalse("$path must not snack a failure", src.contains("Snackbar"))
            assertFalse("$path must not schedule a worker", src.contains("WorkManager"))
            assertFalse("$path must not poll", src.contains("PeriodicWorkRequest"))
        }
    }

    // ── the retry rules ──────────────────────────────────────────────

    @Test
    fun `a server-requested pause does nothing and is never an attempt`() {
        val src = source(SUBMISSION)

        assertEquals(
            "both legs answer PAUSED by sending nothing at all",
            2,
            count(src, "PAUSED -> Unit")
        )
        assertEquals(
            "and an attempt is recorded only where there was no usable answer",
            2,
            count(src, "TasteRatingQueue.markAttempt(")
        )
        assertTrue(
            "a refused grant closes the leg rather than being retried into",
            src.contains("closeLeg(app, TasteRatingTracker.TRAKT)") &&
                src.contains("closeLeg(app, TasteRatingTracker.SIMKL)")
        )
        assertTrue(
            "and the wait is bounded: a long park is left to the next trigger",
            src.contains("FOLLOW_UP_MAX_WAIT_MS")
        )
    }

    @Test
    fun `a drain never crosses a profile switch`() {
        val src = source(SUBMISSION)
        assertTrue(
            "entries belong to the profile that queued them",
            src.contains("if (activeProfileId() != profileAtStart) return")
        )
        assertTrue(
            "and a connect is a drain trigger on both trackers",
            src.contains("fun onTrackerConnected(")
        )
        assertTrue(
            "as is a disconnect, which drops that leg's entries and only that leg's",
            src.contains("fun onTrackerDisconnected(") &&
                src.contains("TasteRatingQueue.clearTracker(app, tracker)")
        )
    }

    // ── storage, diagnostics and gating ──────────────────────────────

    @Test
    fun `the queue is profile-scoped, versioned, and refuses a blob it cannot parse`() {
        val src = source(QUEUE)
        assertTrue("the same profile namespacing every per-profile store uses",
            src.contains("ProfileStorage.prefsName"))
        assertTrue("one blob, one version", src.contains("\"taste_rating_queue_v1\""))
        assertTrue("a read-modify-write refuses when the blob will not parse",
            src.contains("readOrNull(app) ?: return false"))
        assertTrue("and only one tracker's entries go on a disconnect",
            src.contains("fun clearTracker("))
    }

    @Test
    fun `both legs' open state is the landing gate, and diagnostics can say so`() {
        val src = source(SUBMISSION)
        assertTrue(src.contains("TasteRatingTracker.SIMKL -> SIMKL_RATINGS_ENABLED"))
        assertTrue(src.contains("TasteRatingTracker.TRAKT -> TRAKT_RATINGS_ENABLED"))
        assertTrue(
            "COPY DIAGNOSTICS carries the queue and the last failure per leg",
            source(DIAGNOSTICS).contains("TasteRatingSubmission.statusLines(app)")
        )
        assertTrue(
            "and the line names a gated-off leg, which is the report it exists for",
            src.contains("\"off (probe gate)\"")
        )
    }

    @Test
    fun `the connect flows are the only other trigger, and no pref carries a rating`() {
        listOf(TRAKT_VM, SIMKL_VM).forEach { path ->
            val src = source(path)
            assertTrue("$path drains on connect",
                src.contains("TasteRatingSubmission.onTrackerConnected("))
            assertTrue("$path drops its own leg on disconnect",
                src.contains("TasteRatingSubmission.onTrackerDisconnected("))
        }
        // A carrier DOES exist since the taste-signals sync landed - and the
        // submission spec added none of its own: the signals blob travels
        // whole, and the queue that talks to the trackers never reaches the
        // sync payload.
        assertFalse(
            "the submission spec adds no sync carrier of its own: the signals " +
                "blob is the only taste payload, and it is not this queue's",
            source(SUBMISSION).contains("KEY_TASTE_SIGNALS") ||
                source(SUBMISSION).contains("encodedForSync")
        )
        assertFalse(
            "and no new preference exists, because ratings are always on like thumbs",
            source(APP_PREFS).contains("taste_rating")
        )
    }

    // ── harness ──────────────────────────────────────────────────────

    private fun count(haystack: String, needle: String): Int =
        if (needle.isEmpty()) 0 else haystack.split(needle).size - 1

    private fun mainRoot(): File {
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

    private fun source(relative: String): String {
        val file = File(mainRoot(), relative)
        assertTrue("source missing: $relative", file.isFile)
        return file.readText()
    }

    private companion object {
        const val TRAKT_API = "com/kennyb1201/kbstream/data/trakt/TraktApiService.kt"
        const val SIMKL_API = "com/kennyb1201/kbstream/data/simkl/SimklApiService.kt"
        const val TRAKT_MODELS = "com/kennyb1201/kbstream/data/trakt/TraktModels.kt"
        const val TRAKT_WRITES = "com/kennyb1201/kbstream/data/trakt/TraktRatingWrites.kt"
        const val SIMKL_WRITES = "com/kennyb1201/kbstream/data/simkl/SimklRatingWrites.kt"
        const val SUBMISSION = "com/kennyb1201/kbstream/data/library/TasteRatingSubmission.kt"
        const val QUEUE = "com/kennyb1201/kbstream/data/library/TasteRatingQueue.kt"
        const val TASTE_SIGNALS = "com/kennyb1201/kbstream/data/library/TasteSignals.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val MDBLIST = "com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"
        const val SYNC_PREFS = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val APP_PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val TRAKT_VM = "com/kennyb1201/kbstream/ui/trakt/TraktViewModel.kt"
        const val SIMKL_VM = "com/kennyb1201/kbstream/ui/simkl/SimklViewModel.kt"
    }
}
