package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MDBList next-episode leg's wiring (Slice A extension, X1/X4).
 *
 * What a pure test cannot see is that the leg is reached at all, that it is
 * fed by the cached snapshot rather than a new request, that it resolves
 * through the SAME builder as the Trakt and BingeCat legs (no second card
 * shape), and that the slot arrives once with both halves - the parts a
 * compile cannot check and a device proves only with an account.
 *
 * The behaviour itself is the settlers' job: an MDBList-only profile with no
 * local history is the case that was impossible before this leg.
 */
class MdbListNextUpLegContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun count(haystack: String, needle: String): Int {
        var index = haystack.indexOf(needle)
        var found = 0
        while (index >= 0) {
            found++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return found
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val HOME_UP_NEXT = "com/kennyb1201/kbstream/ui/home/HomeUpNext.kt"
        const val ARRIVALS = "com/kennyb1201/kbstream/ui/home/ContinueWatchingArrivals.kt"
    }

    private fun loader(): String {
        val model = source(VIEW_MODEL)
        assertTrue(
            "the leg exists, or this slice is not the slice",
            model.contains("private suspend fun loadMdbListNextEpisodeUpNextItems(): MdbListNextUpResult {")
        )
        return model
            .substringAfter("private suspend fun loadMdbListNextEpisodeUpNextItems(): MdbListNextUpResult {")
            .substringBefore("    /**\n     * Builds one Continue Watching card from a paused MDBList session.")
    }

    // ── the gates and the read ────────────────────────────────────────────

    @Test
    fun `no key answers NotConfigured before any read`() {
        val leg = loader()
        val gate = leg.indexOf("if (!MdbListClient.isConfigured(appContext)) {")
        val read = leg.indexOf("getWatchedSnapshot")
        assertTrue("the paused loader's own guard", gate >= 0)
        assertTrue(
            "a keyless device must not even ask, and must not read the snapshot",
            read > gate && leg.contains("return MdbListNextUpResult.NotConfigured")
        )
    }

    @Test
    fun `the snapshot is the cached read, and one index is built per cycle`() {
        val leg = loader()
        assertTrue(
            "the app's cached snapshot, not a new endpoint (1 h memory / 6 h disk)",
            leg.contains("runCatchingCancellable { MdbListClient.getWatchedSnapshot(appContext) }")
        )
        assertEquals(
            "ONE index per cycle: a per-show prefix scan is fine once and pathological per poster",
            1,
            count(leg, "mdbListWatchedEpisodesByShow(")
        )
        assertTrue(
            "and the per-row build takes its episodes from that index",
            leg.contains("watchedEpisodes = byShow.getValue(showKey)")
        )
    }

    @Test
    fun `the leg enumerates series only, sorted, capped and bounded`() {
        val leg = loader()
        assertFalse(
            "a film has no next episode; movies stay with the paused leg",
            leg.contains("movieKeys")
        )
        assertTrue("deterministic order", leg.contains(".sorted()"))
        assertTrue(
            "the paused leg's own cap, from the ViewModel's companion",
            leg.contains(".take(MAX_MDBLIST_UP_NEXT_ITEMS)")
        )
        assertTrue(
            "and the paused leg's concurrency bound",
            leg.contains("Semaphore(MDBLIST_UP_NEXT_CONCURRENCY)")
        )
    }

    @Test
    fun `the leg writes nothing and adds no gate of its own`() {
        val leg = loader()
        assertFalse(
            "a tracker read must never write: the paused builder's scrobbleClear stays on its own path",
            leg.contains("scrobbleClear")
        )
        assertFalse("no local-history gate: the snapshot is the evidence", leg.contains("hasLocalProgress"))
        assertFalse("no local reads at all on this path", leg.contains("historyDao"))
    }

    // ── one card shape, shared with the other tracker legs ────────────────

    @Test
    fun `the leg resolves through the shared tracker builder`() {
        val leg = loader()
        assertTrue(
            "the Trakt/BingeCat builder, verbatim - no second card shape for this leg",
            leg.contains("buildTrackerNextEpisodeUpNextItem(")
        )
        assertTrue(
            "its own id prefix, so it can never collide with a paused card's id",
            leg.contains("idPrefix = \"mdblist:next:\"")
        )
        assertTrue(
            "its own resume source, which is also what seeds the slot it belongs to",
            leg.contains("resumeSource = ResumeSource.MDBLIST")
        )
        assertTrue("and its own diagnostic source", leg.contains("diagSource = \"mdblist\""))
        val model = source(VIEW_MODEL)
        assertFalse(
            "there is no bespoke MDBList next-episode builder to drift from the shared one",
            model.contains("buildMdbListNextEpisodeUpNextItem")
        )
    }

    @Test
    fun `one unbuildable show drops that row only`() {
        val leg = loader()
        assertTrue(
            "per-row runCatchingCancellable().getOrNull(), as every tracker leg does",
            leg.contains("runCatchingCancellable {") &&
                leg.contains(".awaitAll()") &&
                leg.contains(".filterNotNull()")
        )
    }

    @Test
    fun `the dual-form twins are collapsed inside the leg`() {
        val leg = loader()
        assertTrue(
            "the funnel is a backstop, not the plan",
            leg.contains("dedupeTrackerNextEpisodeCards(built)")
        )
        val upNext = source(HOME_UP_NEXT)
        val dedupe = upNext
            .substringAfter("internal fun dedupeTrackerNextEpisodeCards(")
            .substringBefore("/** True when this card's parent id is the IMDb form")
        assertTrue(
            "IMDb-first, and deterministically - not whichever twin the funnel picked",
            dedupe.contains("!existing.isImdbFormCard() && card.isImdbFormCard() -> card")
        )
        assertTrue(
            "unpaired cards are kept, never guessed into a show",
            dedupe.contains("if (tmdbId <= 0) {") && dedupe.contains("unpaired += card")
        )
    }

    // ── tri-state and the single arrive ───────────────────────────────────

    @Test
    fun `the leg maps its three answers like Trakt's`() {
        val leg = loader()
        assertTrue("success carries the cards", leg.contains("MdbListNextUpResult.Success(items)"))
        assertTrue(
            "a throw is Failed, after the cancellation rethrow",
            leg.contains("throw e") &&
                leg.contains("MdbListNextUpResult.Failed(e)")
        )
        assertTrue(
            "and the leg has its own log line, distinct from the paused leg's",
            leg.contains("\"MDBList next-up load succeeded: \"") &&
                source(VIEW_MODEL).contains("\"MDBList load succeeded: raw=")
        )
    }

    @Test
    fun `one launch, one arrive, and a failure preserves the whole slot`() {
        val model = source(VIEW_MODEL)
        val block = model
            .substringAfter("// MDBList arrives ONCE for its whole slot")
            .substringBefore("} catch (e: kotlinx.coroutines.CancellationException)")

        assertTrue(
            "both halves are read in the one launch, paused first as the slot's own answer",
            block.contains("val paused =") && block.contains("loadMdbListUpNextItems()")
        )
        assertTrue(
            "Success arrives them together",
            block.contains("arrivals.arrive(") && block.contains("paused + next.items")
        )
        assertTrue(
            "no key clears the slot (the paused half is empty without one)",
            block.contains("MdbListNextUpResult.NotConfigured ->") &&
                count(block, "TrackerFeed.MDBLIST") == 2
        )
        val failed = block.substringAfter("is MdbListNextUpResult.Failed ->")
        assertTrue(
            "a failed read does NOT arrive: no answer is not an empty answer",
            failed.contains("preserving existing Up Next data")
        )
        assertFalse(
            "and it must not hand the slot an empty list",
            failed.contains("arrivals.arrive(")
        )
    }

    @Test
    fun `a previous MDBList card seeds its own slot`() {
        val arrivals = source(ARRIVALS)
        assertTrue(
            "MDBLIST cards seed the MDBLIST slot, which is what lets a Failed path preserve them",
            arrivals.contains("ResumeSource.MDBLIST -> TrackerFeed.MDBLIST")
        )
        assertTrue(
            "and the merge order is untouched by this slice",
            arrivals.contains("MERGE_ORDER")
        )
    }

    // ── the paused leg is only re-arrived, never changed ──────────────────

    @Test
    fun `the paused builder keeps its gate and its cleanup`() {
        val model = source(VIEW_MODEL)
        val paused = model
            .substringAfter("private suspend fun buildMdbListUpNextItem(")
            .substringBefore("    /**\n     * Trakt's paused sessions.")
        assertTrue("its local-progress gate stays (this slice does not move it)", paused.contains("if (hasLocalProgress) {"))
        assertTrue("and its stale-session cleanup stays", paused.contains("MdbListClient.scrobbleClear"))
        assertFalse(
            "the stale claim about the shared watched state is gone: the union now includes MDBList",
            paused.contains("MDBList's watched history is deliberately not in it")
        )
        assertTrue(
            "and it says so",
            paused.contains("union of local rows, Simkl, Trakt and MDBList")
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
