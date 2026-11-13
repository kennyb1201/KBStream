package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expanded Spotlight panel follows the Poster Edge toggle (spec 2026-11-13).
 *
 * Reported on 0.9.285: with Poster Edge set to Pill the panel still had sharp
 * corners. The panel painted `KBShapeCard` by hand while every rail card went
 * through the shared `posterEdgeShape()`, so the one surface a viewer expands was
 * the one surface that ignored the setting - and the setting's own description
 * says "all poster surfaces".
 *
 * Pinned textually, like the other Spotlight contracts: there is no Compose
 * harness in this module, so what is checked is that the panel takes the SHARED
 * shape (not a copy of its radii), clips the whole container exactly once, and
 * leaves the panel's geometry alone.
 */
class SpotlightPanelEdgeContractTest {

    private val preview by lazy { source(PREVIEW) }
    private val posterEdge by lazy { source(POSTER_EDGE) }
    private val panel by lazy {
        val start = preview.indexOf("internal fun SpotlightPreviewPanel(")
        assertTrue("SpotlightPreviewPanel missing", start >= 0)
        val end = preview.indexOf("internal fun spotlightMetaLine(", start)
        assertTrue("the slice end marker is missing", end > start)
        preview.substring(start, end)
    }

    @Test
    fun `the panel takes the shared poster edge shape, not a shape of its own`() {
        assertTrue(
            "the panel reads the setting through the one composable the rail uses",
            panel.contains("val panelShape = posterEdgeShape()") &&
                panel.contains(".clip(panelShape)")
        )
        assertFalse(
            "and no radius or shape constant is copied into it",
            panel.contains("KBShapeSoftPill") ||
                panel.contains("KBCornerSoftPillDp") ||
                panel.contains("RoundedCornerShape")
        )
        assertFalse(
            "the hardcoded card shape Kenny saw is gone",
            panel.contains("KBShapeCard")
        )
        assertTrue(
            "the shape is the shared one, read through the sync revision so a " +
                "toggle changed elsewhere redraws it (PosterEdge.kt)",
            posterEdge.contains("fun posterEdgeShape(): Shape") &&
                posterEdge.contains("val syncedRevision = posterTileRevision()") &&
                posterEdge.contains("PosterEdge.fromStored(AppPreferences.getPosterEdge(context)).shape()")
        )
    }

    @Test
    fun `one clip on the container, so every layer inside it follows the corners`() {
        val container = panel.substringAfter("Column(")
            .substringBefore("verticalArrangement")
        assertTrue(
            "the container is clipped before it paints",
            container.contains(".clip(panelShape)") &&
                container.contains(".background(KBVoid)")
        )
        assertTrue(
            "the clip comes before the background, so the void cannot square the corners back",
            container.indexOf(".clip(panelShape)") < container.indexOf(".background(KBVoid)")
        )
        assertTrue(
            "the panel's children are not clipped individually: exactly ONE clip in " +
                "the whole panel, so the backdrop and the trailer live inside it",
            Regex("\\.clip\\s*\\(").findAll(panel).count() == 1
        )
    }

    @Test
    fun `the panel's geometry and its trailer are untouched`() {
        assertTrue(
            "same growth factor, same margin, same clamp",
            panel.contains("growth = PANEL_GROWTH") &&
                panel.contains("marginPx = marginPx") &&
                panel.contains("spotlightPanelBox(")
        )
        assertTrue(
            "the 16:9 art box is the same box",
            panel.contains(".aspectRatio(16f / 9f)")
        )
        assertTrue(
            "the trailer is mounted the same way",
            panel.contains("SpotlightPanelTrailer(") &&
                panel.contains("stillUrl = stillNow")
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val ROOT = "com/kennyb1201/kbstream/ui/"
        const val PREVIEW = "${ROOT}home/SpotlightPreview.kt"
        const val POSTER_EDGE = "${ROOT}components/PosterEdge.kt"
    }
}
