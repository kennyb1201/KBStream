package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared long-press menu's row order and its scroll.
 *
 * Two reports underneath one component:
 *
 *  - The LAST row could not be reached. The panel's height was its content's,
 *    so a menu with more rows than fit (Home's title menu: the library rows, a
 *    reminder, the watch toggle and Hide) drew the last row - Hide - off the
 *    bottom of the screen, and the D-pad's edge clamp meant it could not be
 *    scrolled to. The panel is now capped to the window and its rows scroll.
 *  - The row order drifted between screens: a rail menu read "Add to Library,
 *    Open in Grid, Go to Details, …" while the Home continue-watching menu read
 *    "Go to Details, Play Manually, …". [contextMenuSectionRank] now puts every
 *    menu in the same section order.
 *
 * The taste rows ("Like this title" / "Dislike this title") joined that ladder
 * between the library rows and the reminder, and this file is deliberately not
 * re-derived from memory: the ranks below are the same ladder with one section
 * inserted, not a new scheme.
 *
 * The order is pure and pinned directly; the scroll is UI, so its wiring is
 * pinned against the source the way the other contract tests in this package do.
 */
class PosterMenuOrderAndScrollTest {

    private fun action(label: String, destructive: Boolean = false) =
        PosterContextAction(
            label = label,
            isDestructive = destructive,
            onClick = {}
        )

    // ── Section order ──────────────────────────────────────────────────────

    @Test
    fun `the sections run open, play, library, taste, reminder, watched, other, destructive`() {
        // One representative row per section, in the order a menu must draw
        // them. Ranks must also be non-decreasing, which is exactly what the
        // stable sort in the menu relies on.
        //
        // Taste was inserted BETWEEN library and reminder, so the reminder and
        // everything below it shifted up one step; the numbers are an ordering
        // device, and the order is what this test pins.
        val ranks = listOf(
            action("Go to Details"),
            action("Play Manually"),
            action("Add to Library"),
            action("Like this title"),
            action("Remind me when it airs"),
            action("Mark as Watched"),
            action("A Caller's Own Row"),
            action("Hide", destructive = true)
        ).map { contextMenuSectionRank(it) }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), ranks)
    }

    @Test
    fun `every label the menus actually use lands in its section`() {
        val expected = mapOf(
            "Go to Details" to 0,
            "Open in Grid" to 0,
            "Add to Home" to 0,
            "Play Manually" to 1,
            "Play from Beginning" to 1,
            "Add to Library" to 2,
            "In Library \u2713" to 2,
            "Add to list\u2026" to 2,
            "Like this title" to 3,
            "Liked \u2713" to 3,
            "Dislike this title" to 3,
            "Disliked \u2713" to 3,
            "Remind me when it airs" to 4,
            "Reminder on \u2713" to 4,
            "Mark as Watched" to 5,
            "Mark as Unwatched" to 5,
            "Mark Previous as Watched" to 5,
            "Mark Season as Watched" to 5,
            "Mark Entire Series as Watched" to 5
        )
        expected.forEach { (label, rank) ->
            assertEquals("wrong section for \"$label\"", rank, contextMenuSectionRank(action(label)))
        }
    }

    @Test
    fun `the taste pair sits between the library rows and the reminder`() {
        val library = contextMenuSectionRank(action("Add to list\u2026"))
        val like = contextMenuSectionRank(action("Like this title"))
        val dislike = contextMenuSectionRank(action("Disliked \u2713"))
        val reminder = contextMenuSectionRank(action("Remind me when it airs"))

        assertEquals(
            "both thumbs share one section, or the pair could be split by a row " +
                "between them",
            like,
            dislike
        )
        assertTrue("taste ranks below the library rows", like > library)
        assertTrue("and above the reminder", like < reminder)
    }

    @Test
    fun `a destructive row is last whatever it says`() {
        assertEquals(7, contextMenuSectionRank(action("Hide", destructive = true)))
        assertEquals(7, contextMenuSectionRank(action("Remove", destructive = true)))
        assertEquals(7, contextMenuSectionRank(action("Remove from Home", destructive = true)))
        // The same browse chip is "Add to Home" (an open/navigate row) while it
        // is OFF Home, and only becomes the destructive row when it is on.
        assertEquals(0, contextMenuSectionRank(action("Add to Home")))
    }

    @Test
    fun `an unrecognized caller row falls into the catch-all, not off the end`() {
        assertEquals(6, contextMenuSectionRank(action("Export to Simkl")))
    }

    // ── The scroll that makes the last row reachable ───────────────────────

    @Test
    fun `the panel is capped to the window and its rows scroll`() {
        val source = source(MENU)
        assertTrue(
            "the cap must come from the window, not a fixed height",
            source.contains("val maxPanelHeight = maxHeight * 0.92f")
        )
        assertTrue(
            "the panel must be bounded so an over-long menu cannot clip its last row",
            source.contains(".heightIn(max = maxPanelHeight)")
        )
        assertTrue(
            "the rows must live in a scroll region so the last one is reachable",
            source.contains(".verticalScroll(rememberScrollState())")
        )
        assertTrue(
            "the window-relative cap needs a constraints-aware container",
            source.contains("BoxWithConstraints(")
        )
    }

    @Test
    fun `the built rows are put in the canonical order`() {
        assertTrue(
            "the rows must be sorted by the section rank, or the order is whatever " +
                "each caller happened to list",
            source(MENU).contains(".sortedBy { contextMenuSectionRank(it) }")
        )
    }

    private fun source(path: String): String {
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

    private companion object {
        private const val MENU =
            "com/kennyb1201/kbstream/ui/components/PosterContextMenu.kt"
    }
}
