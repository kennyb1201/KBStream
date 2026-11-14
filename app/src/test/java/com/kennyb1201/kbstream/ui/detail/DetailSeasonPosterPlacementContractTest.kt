package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season poster sits beside the EPISODES heading, not at the row's far edge
 * (detail season-poster placement, F1-F2, 2026-11-13).
 *
 * The field photo (TCL, Upload, Season 3) showed the small portrait thumb
 * pushed against the right edge of the header row, detached from the section it
 * belongs to, with the arrow running all the way back to EPISODES. The cause was
 * the row's own construction: the heading took `weight(1f)`, so it ate the free
 * width and shoved the poster out to the edge.
 *
 * Pinned here: the heading sizes to its own text (F1); the poster is the row's
 * second child, directly after it, with a start gap instead of the edge padding
 * (F1); a trailing spacer holds the row's width so nothing in the column shifts
 * and the heading stays at the screen edge (F1/F2); a season with no poster of
 * its own still draws nothing and substitutes nothing (F1); and the poster is
 * still not a focus or press target (F2). The art source, size and shape are
 * pinned as unchanged - only the placement moved.
 *
 * Source-reading style, like this page's other contract tests.
 */
class DetailSeasonPosterPlacementContractTest {

    private val detail by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The EPISODES header item, through to the season chips that follow it. */
    private val header: String by lazy {
        detail.substringAfter("item(key = \"episodesheader\"")
            .substringBefore("item(key = SEASON_ROW_KEY")
    }

    // ── F1: title, poster, spacer ──────────────────────────────────────────

    @Test
    fun `the poster is the second child, directly after the heading`() {
        assertTrue("the header item must exist", header.isNotEmpty())
        val title = header.indexOf("KBSectionHeader(")
        val poster = header.indexOf("AsyncImage(")
        val spacer = header.indexOf("Spacer(Modifier.weight(1f))")
        assertTrue(
            "child order is heading, poster, spacer - the poster comes to the heading",
            title >= 0 && title < poster && poster < spacer
        )
        assertFalse(
            "the heading keeps its edge and the poster comes to it",
            header.substring(0, poster).contains("weight(")
        )
        assertTrue(
            "and it keeps its own leading edge padding and top gap",
            header.substring(title, poster).contains("KBScreenEdge") &&
                header.substring(title, poster).contains("top = 14.dp")
        )
        assertTrue(
            "the poster carries a start gap instead of the row's end padding",
            header.substring(poster, spacer).contains(".padding(start = 12.dp, top = 14.dp)")
        )
        assertFalse(
            "nothing is left against the far edge",
            header.contains("end = KBScreenEdge")
        )
    }

    @Test
    fun `only the placement moved - same art, size, shape and crop`() {
        assertTrue(
            "the season's own art is still the only model",
            header.contains(".data(poster)")
        )
        assertTrue(
            "and the loader resolves it from the same fanart season lookup",
            detail.contains("fanart.seasonPoster(") &&
                detail.contains("seasonPosterUrl = fanart.seasonPoster(")
        )
        listOf(
            ".width(54.dp)",
            ".aspectRatio(2f / 3f)",
            ".clip(KBShapeSmall)",
            "contentScale = ContentScale.Crop",
            "contentDescription = null",
            ".size(Size(300, 450))"
        ).forEach { pin ->
            assertTrue("$pin is unchanged", header.contains(pin))
        }
    }

    @Test
    fun `a season with no poster draws nothing, and substitutes nothing`() {
        assertTrue(
            "the poster is drawn only behind the season poster's own value",
            header.contains("seasonPosterUrl?.let { poster ->")
        )
        assertEquals(
            "one header, one poster slot",
            1,
            normalized(
                stripComments(read(DETAIL_FILE))
            ).count("seasonPosterUrl?.let")
        )
        assertEquals("one image in the row", 1, header.count("AsyncImage("))
        listOf("posterPath", "backdrop", "R.drawable").forEach { fallback ->
            assertFalse(
                "never a fallback art source ($fallback): an absent poster leaves the row as the heading",
                header.contains(fallback)
            )
        }
    }

    @Test
    fun `the row still fills the width, and the heading stays at the edge`() {
        assertTrue(
            "the row keeps filling the width, centered on the heading's line",
            header.contains("modifier = Modifier.fillMaxWidth()") &&
                header.contains("verticalAlignment = Alignment.CenterVertically")
        )
        val poster = header.indexOf("AsyncImage(")
        val spacer = header.indexOf("Spacer(Modifier.weight(1f))")
        assertTrue("the spacer trails the poster", spacer > poster)
        assertFalse(
            "the spacer is the row's last child - nothing is pushed to the edge after it",
            header.substring(spacer).contains("AsyncImage(") ||
                header.substring(spacer).contains("KBSectionHeader(")
        )
        assertTrue(
            "and the season chips still follow the header",
            detail.indexOf("item(key = SEASON_ROW_KEY") > detail.indexOf("item(key = \"episodesheader\"")
        )
        assertEquals(
            "one header item serves both form factors",
            1,
            detail.count("item(key = \"episodesheader\"")
        )
    }

    @Test
    fun `the poster is still not a focus or press target`() {
        listOf("focusable", "FocusRequester", "onClick", "clickable").forEach { pin ->
            assertFalse(
                "the header row stays out of the focus chain ($pin)",
                header.contains(pin)
            )
        }
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun read(fileName: String): String =
        File(
            findMainSourceRoot(),
            "com/kennyb1201/kbstream/ui/detail/$fileName"
        ).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
    }

    private fun findMainSourceRoot(): File {
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
        const val DETAIL_FILE = "DetailScreen.kt"
    }
}
