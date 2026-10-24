package com.kennyb1201.kbstream.data.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library half of the cleanup: what survives a local My List removal.
 *
 * The corrected TorBox spec owns the local rows (mark-agnostic arm B, the review
 * queue, the live status line). This pins the rest of the same field case: a
 * title removed on purpose came back into the Library wearing a tracker tag,
 * because the watchlist merge never consulted the removal record; the unattended
 * TorBox sync seeded the trackers in the first place; and a review confirmation
 * left the MDBList/Trakt copies behind.
 */
class LibraryCleanupContractTest {

    private fun item(
        source: LibrarySource,
        title: String = "Bluey",
        imdbId: String = "tt1",
        tmdbId: Int = 1
    ) = LibraryItem(
        source = source,
        mediaType = "series",
        title = title,
        year = 2018,
        posterUrl = null,
        imdbId = imdbId,
        tmdbId = tmdbId
    )

    // ── §1 the merge honours the removal record ──────────────────────────────

    @Test
    fun `a tracker row for a title removed on purpose stays out of the Library`() {
        val record = mapOf("series:tt1:1" to LibraryTombstone("series:tt1:1", 1_000L, true))
        val trackerRow = item(LibrarySource.SIMKL_WATCHLIST)

        assertTrue(
            "the field's leftover: local row gone, Simkl copy still fetched, and " +
                "the merge used to take it at face value",
            LibraryTombstones.removedFrom(listOf(trackerRow), record).isEmpty()
        )
        assertTrue(
            "a clearance is the viewer re-adding it, and it welcomes the row back",
            LibraryTombstones.removedFrom(
                listOf(trackerRow),
                mapOf("series:tt1:1" to LibraryTombstone("series:tt1:1", 2_000L, false))
            ).isNotEmpty()
        )
        assertTrue(
            "an empty record filters nothing",
            LibraryTombstones.removedFrom(listOf(trackerRow), emptyMap()).size == 1
        )
    }

    @Test
    fun `the watchlist merge is filtered after the de-dupe, before the ALL view`() {
        val viewModel = normalized(source(LIBRARY_VIEW_MODEL))

        assertTrue(
            "the filter names the watchlist merge and the profile's own record",
            viewModel.contains(
                "LibraryTombstones.removedFrom( " +
                    "mergeById(simklItems + mdbListWatchlist + traktWatchlist), " +
                    "LocalLibraryStore.tombstones(appContext) )"
            )
        )
        assertTrue(
            "the de-duped watchlist is what gets filtered, so one pass covers all " +
                "three tracker sources",
            viewModel.indexOf("LibraryTombstones.removedFrom(") <
                viewModel.indexOf("mergeById(simklItems + mdbListWatchlist + traktWatchlist)")
        )
        assertTrue(
            "and before the merged view is built from it",
            viewModel.indexOf("LibraryTombstones.removedFrom(") <
                viewModel.indexOf("canonicalAll = kidsFiltered(")
        )
    }

    @Test
    fun `personal lists and a hand re-add are not suppressed`() {
        val viewModel = normalized(source(LIBRARY_VIEW_MODEL))

        assertTrue(
            "the personal-list half of the merge is untouched: those are " +
                "deliberate collections, not watchlist rows",
            viewModel.contains(
                "mergeAllSources(listOf(localItems, watchlistMerged, localListItems))"
            )
        )
        assertFalse(
            "and the local rows themselves are never filtered - a manual re-add " +
                "has to reappear",
            viewModel.contains("removedFrom( localItems")
        )
    }

    // ── §2 the unattended sync writes My List only ──────────────────────────

    @Test
    fun `a TorBox sync add never mirrors, while a deliberate add still does`() {
        val mirror = normalized(source(MIRROR))

        assertTrue(
            "the cloud round is a download sync into My List, not a watchlist add",
            mirror.contains(
                "if (origin != LibraryOrigin.TORBOX_SYNC) launchMirrors(context, r)"
            )
        )
        assertTrue(
            "every menu entry point still defaults to MANUAL, so its mirrors fire",
            mirror.contains("origin: LibraryOrigin = LibraryOrigin.MANUAL")
        )
    }

    // ── §3 the review un-mirrors where the API allows it ────────────────────

    @Test
    fun `a confirmed review removes the tracker copies of each row`() {
        val review = normalized(source(REVIEW))

        assertTrue(
            "the same tracker removal the Library's own remove uses",
            review.contains("LibraryMirror.removeFromTrackers(")
        )
        assertTrue(
            "per row, naming the title so a surviving copy is findable",
            review.contains("title = row.item.title")
        )
        assertTrue(
            "the reported count stays the local tombstone count, whatever the " +
                "best-effort mirrors did",
            review.contains(
                "val removed = LocalLibraryStore.removeMyListEntries(context, " +
                    "rows.map { it.item })"
            )
        )
        assertFalse(
            "Simkl has no delete endpoint, and none is invented here",
            review.contains("SimklRepository")
        )

        val mirror = normalized(source(MIRROR))
        assertTrue(
            "the tracker half is split out so the review does not also re-delete " +
                "locally",
            mirror.contains("fun removeFromTrackers(") &&
                mirror.contains(
                    "LocalLibraryStore.removeFromMyList(context, mediaType, imdbId, " +
                        "tmdbId) removeFromTrackers(context, mediaType, imdbId, tmdbId)"
                )
        )
        assertTrue(
            "a failure is logged against the title",
            mirror.contains("failed for ${'$'}label")
        )
    }

    // ── §5 the queue's own UI, restated by the amendment ────────────────────

    @Test
    fun `both panes offer the review from the filed record, not only resolvable rows`() {
        listOf(TV_SETTINGS, MOBILE_SETTINGS).forEach { pane ->
            val text = normalized(source(pane))
            assertTrue(
                "$pane must keep offering the review while the record is non-empty",
                text.contains("TorBoxReview.filedCount(context)") &&
                    text.contains("if (torboxReviewFiled > 0)")
            )
            assertTrue(
                "$pane labels the row with the filed count, so it matches the record",
                text.contains("REVIEW ${'$'}torboxReviewFiled UNOWNED CLOUD TITLES") ||
                    text.contains("Review ${'$'}torboxReviewFiled unowned cloud titles")
            )
            assertTrue(
                "$pane hands that count to the dialog, which reconciles it",
                text.contains("filedCount = torboxReviewFiled")
            )
        }
    }

    @Test
    fun `the confirm row is pinned, counted and legible under focus`() {
        val dialog = normalized(source(DIALOG))

        assertTrue(
            "the action row is drawn over the list's last strip, not after it: a " +
                "long queue must never push it off the dialog",
            dialog.contains("align(Alignment.BottomCenter)") &&
                dialog.contains("contentPadding = PaddingValues(bottom = ROW_HEIGHT)")
        )
        assertTrue(
            "the list is capped against the room the dialog actually has",
            dialog.contains("BoxWithConstraints(") &&
                dialog.contains("heightIn(max = listCap)")
        )
        assertTrue(
            "the row states the live selection",
            dialog.contains("${'$'}{selected.size} selected")
        )
        assertTrue(
            "the destructive label takes the lit tone, because the muted red " +
                "washed out on the raised plate under the focus glow",
            dialog.contains("labelColor = KBDangerHi")
        )
        assertTrue(
            "the queue states the filed count and reconciles it with the rows shown",
            dialog.contains("filedCount: Int") &&
                dialog.contains("Showing ${'$'}shown of ${'$'}filed filed titles")
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
        const val LIBRARY_VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"
        const val MIRROR = "com/kennyb1201/kbstream/data/library/LibraryMirror.kt"
        const val REVIEW = "com/kennyb1201/kbstream/data/debrid/TorBoxReview.kt"
        const val DIALOG = "com/kennyb1201/kbstream/ui/settings/TorBoxReviewDialog.kt"
        const val TV_SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SETTINGS =
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
    }
}
