package com.kennyb1201.kbstream.data.debrid

import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryOrigin
import com.kennyb1201.kbstream.data.library.LibrarySource
import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corrected attribution (arm B without the mark gate) and the review queue
 * that holds what no rule can decide.
 *
 * The field case these cover: Profile 1 held kids' titles that were in the
 * shared TorBox cloud, stamped by the sync, owned by NOBODY, with the kids' list
 * empty on the phone. Arm B used to require `!marked`, so those rows fell through
 * both arms and the round removed nothing; and where a sibling copy does not
 * exist, no rule can separate them from the viewer's own unwatched cloud titles,
 * so they have to reach the viewer instead of a guess.
 */
class TorBoxOwnershipReviewTest {

    private fun row(
        title: String,
        origin: LibraryOrigin = LibraryOrigin.MANUAL,
        mediaType: String = "series",
        imdbId: String = "tt1",
        tmdbId: Int = 1
    ) = LibraryItem(
        source = LibrarySource.LOCAL,
        mediaType = mediaType,
        title = title,
        year = null,
        posterUrl = null,
        imdbId = imdbId,
        tmdbId = tmdbId,
        origin = origin
    )

    // ── Arm B is mark-agnostic ───────────────────────────────────────────────

    @Test
    fun `a stamped cloud row with a sibling copy is a leak like an unmarked one`() {
        val cloud = setOf("bluey")

        assertTrue(
            "the sync wrote it and a sibling lists it: the mark says which build " +
                "wrote the row, never that the title is this profile's",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = emptyMap(),
                entry = row("Bluey", LibraryOrigin.TORBOX_SYNC),
                cloudTitles = cloud,
                siblingTitles = setOf("bluey"),
                liveProfileId = "profile-1"
            )
        )
        assertTrue(
            "and the unmarked shape it already handled still is one",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = emptyMap(),
                entry = row("Bluey"),
                cloudTitles = cloud,
                siblingTitles = setOf("bluey"),
                liveProfileId = "profile-1"
            )
        )
    }

    @Test
    fun `a stamped cloud row with no sibling copy is still not auto-removed`() {
        assertFalse(
            "that is the row no automatic rule can decide - it goes to the " +
                "review queue, not to the cleanup",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = emptyMap(),
                entry = row("Bluey", LibraryOrigin.TORBOX_SYNC),
                cloudTitles = setOf("bluey"),
                siblingTitles = emptySet(),
                liveProfileId = "profile-1"
            )
        )
    }

    @Test
    fun `the veto still outranks a stamped row with a sibling copy`() {
        assertFalse(
            "watched on THIS profile: it stays, and it must never reach the queue " +
                "either - its owner is recorded",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = mapOf("title:series:bluey" to "profile-1"),
                entry = row("Bluey", LibraryOrigin.TORBOX_SYNC),
                cloudTitles = setOf("bluey"),
                siblingTitles = setOf("bluey"),
                liveProfileId = "profile-1"
            )
        )
    }

    // ── The review queue's rule ──────────────────────────────────────────────

    @Test
    fun `only rows with no owner, in the cloud, and in no sibling list are queued`() {
        val entries = listOf(
            row("Bluey", LibraryOrigin.TORBOX_SYNC, imdbId = "tt1", tmdbId = 1),
            row("Moana", LibraryOrigin.MANUAL, imdbId = "tt2", tmdbId = 2),
            row("Encanto", LibraryOrigin.MANUAL, imdbId = "tt3", tmdbId = 3),
            row("Zootopia", LibraryOrigin.MANUAL, imdbId = "tt4", tmdbId = 4),
            row("Cars", LibraryOrigin.MANUAL, imdbId = "tt5", tmdbId = 5)
        )
        val queued = torboxUnattributableRows(
            ownerByTitleKey = mapOf(
                // Watched on this profile - the veto's, never a question.
                "title:series:encanto" to "profile-1",
                // Watched on another profile - arm A's, not a question either.
                "title:series:zootopia" to "profile-3"
            ),
            entries = entries,
            cloudTitles = setOf("bluey", "moana", "encanto", "zootopia"),
            siblingTitles = setOf("moana"),
            liveProfileId = "profile-1"
        )

        assertEquals(
            "the field's stamped kids title is the one that needs a decision: " +
                "unowned, stamped, in the cloud, no sibling copy",
            listOf("Bluey"),
            queued.map { it.title }
        )
    }

    @Test
    fun `a row whose title stopped resolving is not a candidate`() {
        assertTrue(
            "a nameless row cannot be attributed to the cloud by title either",
            torboxUnattributableRows(
                ownerByTitleKey = emptyMap(),
                entries = listOf(row("   ")),
                cloudTitles = setOf("bluey"),
                siblingTitles = emptySet(),
                liveProfileId = "profile-1"
            ).isEmpty()
        )
    }

    // ── The queue as the review shows it ─────────────────────────────────────

    @Test
    fun `the review pre-selects the sync's rows and drops keys that no longer resolve`() {
        val entries = listOf(
            row("Bluey", LibraryOrigin.TORBOX_SYNC, imdbId = "tt1", tmdbId = 1),
            row("Moana", LibraryOrigin.MANUAL, imdbId = "tt2", tmdbId = 2)
        )
        val rows = TorBoxReview.rows(
            entries = entries,
            keys = setOf(
                "series:tt1:1",
                "series:tt2:2",
                // Gone from My List since the round: not shown as a ghost.
                "series:tt9:9"
            )
        )

        assertEquals(
            "only rows that still exist, in My List order",
            listOf("Bluey", "Moana"),
            rows.map { it.item.title }
        )
        assertTrue(
            "the sync definitely wrote the stamped row, so it is ticked",
            rows.first { it.item.title == "Bluey" }.preSelected
        )
        assertFalse(
            "an unmarked row may be a hand-add, so the viewer ticks it",
            rows.first { it.item.title == "Moana" }.preSelected
        )
    }

    @Test
    fun `a confirmation removes through the tombstoning path and unqueues only what it removed`() {
        val review = source(REVIEW)
        assertTrue(
            "the same removal as the cleanup, so tombstones make the decision stick",
            review.contains("LocalLibraryStore.removeMyListEntries(context, rows.map { it.item })")
        )
        assertTrue(
            "and the confirmed keys leave the queue",
            review.contains("store.forgetReviewCandidates(rows.map { it.key }.toSet())")
        )
        assertTrue(
            "a kept row stays queued - it is still unattributable",
            source(STORE).contains("val remaining = reviewCandidates() - keys")
        )
    }

    // ── Filing, and the line ─────────────────────────────────────────────────

    @Test
    fun `only a completed round files the queue, after its own adds`() {
        val sync = source(SYNC)
        val record = sync.indexOf("store.recordReviewCandidates(")
        assertTrue(
            "the queue is written by the round",
            record > 0
        )
        assertTrue(
            "filed AFTER the adds: the add-time filter admits unowned titles, so " +
                "this round's own writes are exactly the rows that must be queued " +
                "in the same breath",
            record > sync.indexOf("if (wasAdded) added++")
        )
        assertTrue(
            "and after every gate, so a disabled or keyless round leaves the " +
                "previous queue intact instead of blanking it",
            record > sync.indexOf("outcome = Outcome.DEFERRED")
        )
    }

    @Test
    fun `the line names the pending review, and the missing sibling evidence`() {
        val zone = ZoneId.of("UTC")
        val at = Instant.parse("2026-10-24T09:15:00Z").toEpochMilli()

        val withSiblings = TorBoxCloudStore.LastRound(
            outcome = TorBoxLibrarySync.Outcome.COMPLETED,
            scanned = 1000,
            added = 0,
            handledSkips = 998,
            removed = 0,
            candidates = 2,
            siblingEvidence = true,
            atMillis = at
        )
        val noSiblings = withSiblings.copy(siblingEvidence = false)
        val cleared = withSiblings.copy(candidates = 0)

        assertEquals(
            "a round that found titles it could not attribute must not read like " +
                "a round with nothing to remove: this is the field's Library leak",
            "Last sync 09:15 — 1000 in cloud, 998 already handled · found 2 cloud " +
                "titles it could not attribute — see the review row",
            torboxSyncStatusLine(withSiblings, zone)
        )
        assertEquals(
            "and with no sibling list on this device, that is WHY they could not " +
                "be decided - the same line must not read as if it compared",
            "Last sync 09:15 — 1000 in cloud, 998 already handled · found 2 cloud " +
                "titles it could not attribute (no sibling library on this device " +
                "to compare) — see the review row",
            torboxSyncStatusLine(noSiblings, zone)
        )
        assertEquals(
            "one is a title",
            "Last sync 09:15 — 1000 in cloud, 998 already handled · found 1 cloud " +
                "title it could not attribute — see the review row",
            torboxSyncStatusLine(withSiblings.copy(candidates = 1), zone)
        )
        assertEquals(
            "and once the queue is empty the clause goes with it",
            "Last sync 09:15 — 1000 in cloud, 998 already handled",
            torboxSyncStatusLine(cleared, zone)
        )
        assertEquals(
            "the live count wins over the filed one: a confirmed removal must not " +
                "leave the line advertising rows that are gone",
            "Last sync 09:15 — 1000 in cloud, 998 already handled",
            torboxSyncStatusLine(withSiblings, zone, pendingReview = 0)
        )
    }

    @Test
    fun `the report carries the queue and the sibling evidence into the record`() {
        val store = source(STORE)
        assertTrue(
            "the round's report gains both facts",
            source(SYNC).contains("candidates = candidates.size") &&
                source(SYNC).contains(
                    "siblingEvidence = siblingTitles.isNotEmpty()"
                )
        )
        assertTrue(
            "and the record keeps them for the pans that read it later",
            store.contains("putInt(KEY_LAST_CANDIDATES, report.candidates)") &&
                store.contains("putBoolean(KEY_LAST_SIBLING, report.siblingEvidence)")
        )
    }

    // ── Wiring: the panes, live ──────────────────────────────────────────────

    @Test
    fun `both panes read the line live, resolve the queue, and offer the review`() {
        listOf(TV_SETTINGS, MOBILE_SETTINGS).forEach { pane ->
            val text = normalized(source(pane))
            assertTrue(
                "$pane must follow the latest FILED round, not a snapshot taken " +
                    "on entry - the field pressed Resync and saw the previous round",
                text.contains("TorBoxSyncStatus.revision") &&
                    text.contains(".collectAsStateWithLifecycle()")
            )
            assertTrue(
                "$pane must re-read on resume as well",
                text.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)")
            )
            assertTrue(
                "$pane must resolve the queue against My List as it stands now",
                text.contains("TorBoxReview.rows(context)")
            )
            assertTrue(
                "$pane must offer the review and remove through the shared path",
                text.contains("TorBoxReviewDialog(") &&
                    text.contains("TorBoxReview.remove(")
            )
            assertTrue(
                "$pane must show the live pending count on the line",
                text.contains("pendingReview = torboxReviewRows.size")
            )
            assertTrue(
                "$pane names the result on the spot",
                text.contains("Removed 1 title")
            )
        }
    }

    @Test
    fun `the status signal is published by the round and by a confirmed removal`() {
        assertTrue(
            "filing a round is news to an open pane",
            source(STORE).contains("TorBoxSyncStatus.publish()")
        )
        assertTrue(
            "and so is the reviewer's confirmation",
            source(REVIEW).contains("TorBoxSyncStatus.publish()")
        )
    }

    @Test
    fun `the remediation re-arms for the corrected pass while the marks and the queue stay`() {
        val store = normalized(source(STORE))
        assertTrue(
            "version 3: every profile gets one corrected pass on its next ordinary " +
                "round",
            store.contains("private const val OWNERSHIP_REMEDIATION_VERSION = 3")
        )
        val worker = normalized(source(WORKER))
        assertTrue(
            "Resync still re-arms it, and still clears the marks",
            worker.contains("clearOwnershipRemediation()") &&
                worker.contains("clearAllMarks()")
        )
        assertTrue(
            "the queue is NOT cleared by a mark reset: it is the round's own record " +
                "of what it could not attribute",
            !store.contains(
                "fun clearAllMarks() { prefs.edit() .remove(KEY_REVIEW_CANDIDATES)"
            )
        )
    }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun source(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/debrid/TorBoxLibrarySync.kt"
        const val STORE = "com/kennyb1201/kbstream/data/debrid/TorBoxCloudStore.kt"
        const val REVIEW = "com/kennyb1201/kbstream/data/debrid/TorBoxReview.kt"
        const val WORKER = "com/kennyb1201/kbstream/work/TorBoxLibraryWorker.kt"
        const val TV_SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SETTINGS =
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
    }
}
