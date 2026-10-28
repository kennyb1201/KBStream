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
 * between the library rows and the reminder, and the watched row was later
 * hoisted to the TOP of it (the action a long press is most often opened to do),
 * and this file is deliberately not re-derived from memory: the ranks below are
 * the same ladder with one section moved, not a new scheme.
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
    fun `the sections run watched, open, play, library, taste, reminder, other, destructive`() {
        // One representative row per section, in the order a menu must draw
        // them. Ranks must also be non-decreasing, which is exactly what the
        // stable sort in the menu relies on.
        //
        // The WATCHED row leads: it is what a long press is most often opened
        // to do, and the first row is where focus lands. Everything else keeps
        // the ladder it had, one step lower, and destructive stays last.
        val ranks = listOf(
            action("Mark as Watched"),
            action("Go to Details"),
            action("Play Manually"),
            action("Add to Library"),
            action("Like this title"),
            action("Remind me when it airs"),
            action("A Caller's Own Row"),
            action("Hide", destructive = true)
        ).map { contextMenuSectionRank(it) }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), ranks)
        assertTrue(
            "nothing may outrank the watched row: the whole point is that it is " +
                "the first thing the menu offers",
            ranks.first() == ranks.minOrNull()
        )
    }

    @Test
    fun `a menu with a watched row draws it above every other action`() {
        // The reported shape: a poster menu carrying navigate, play, library,
        // thumbs, a reminder and the watch toggle. Sorted the way both menus
        // sort, the toggle has to be row 0 - and Hide, the one destructive row,
        // still the last.
        val rows = listOf(
            action("Add to Library"),
            action("Go to Details"),
            action("Play Manually"),
            action("Play from Beginning"),
            action("Like this title"),
            action("Dislike this title"),
            action("Rate this title"),
            action("Remind me when it airs"),
            action("Mark Entire Series as Watched"),
            action("Hide everywhere", destructive = true)
        ).sortedBy { contextMenuSectionRank(it) }

        assertEquals("Mark Entire Series as Watched", rows.first().label)
        assertEquals("Hide everywhere", rows.last().label)
        assertTrue(
            "and it is above the navigate and play rows it used to sit below",
            rows.indexOfFirst { it.label == "Mark Entire Series as Watched" } <
                rows.indexOfFirst { it.label == "Go to Details" }
        )
    }

    @Test
    fun `every label the menus actually use lands in its section`() {
        val expected = mapOf(
            "Mark as Watched" to 0,
            "Mark as Unwatched" to 0,
            "Mark Previous as Watched" to 0,
            "Mark Season as Watched" to 0,
            "Mark Entire Series as Watched" to 0,
            "Mark Entire Series as Unwatched" to 0,
            "Go to Details" to 1,
            "Open in Grid" to 1,
            "Add to Home" to 1,
            "Play Manually" to 2,
            "Play from Beginning" to 2,
            "Add to Library" to 3,
            "In Library \u2713" to 3,
            "Add to list\u2026" to 3,
            "Like this title" to 4,
            "Liked \u2713" to 4,
            "Dislike this title" to 4,
            "Disliked \u2713" to 4,
            "Remind me when it airs" to 5,
            "Reminder on \u2713" to 5
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
        assertEquals(1, contextMenuSectionRank(action("Add to Home")))
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
