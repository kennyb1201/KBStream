package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seasons and episodes in the Spotlight panel (spec 2026-11-15), as wiring.
 *
 * The counts are words in an existing line, so nothing here is loud: the quiet
 * failures are a panel that fetches its own detail (a second call per hover), a
 * panel that shows the PREVIOUS card's counts during a fast move, and a line that
 * takes the runtime's slot. Each case pins one of those.
 */
class SpotlightSeasonEpisodeContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val HOST = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val PREVIEW = "com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt"
        const val LINE = "com/kennyb1201/kbstream/ui/home/SpotlightMetaLine.kt"
    }

    @Test
    fun `the counts come from the hero detail the resolve already produced`() {
        val screen = source(SCREEN)
        assertTrue(
            "the counts are words off the settled hero detail, not a new fetch",
            screen.contains(
                "settledSeasonEpisodeCount =\n" +
                    "                    heroTmdbDetailState.value?.displaySeasonEpisodeCount()"
            )
        )
        val host = source(HOST)
        assertFalse(
            "the panel must not resolve or fetch anything of its own: it draws " +
                "what the host hands it",
            host.contains("FanartArtRepository") || host.contains("TmdbRepository")
        )
    }

    @Test
    fun `the host gates the counts on the settled title, exactly as it gates the backdrop`() {
        val host = source(HOST)
        val panelCall = host.substringAfter("SpotlightPreviewPanel(\n            snapshot = panel")
            .substringBefore("onTrailerPlayingChange = onTrailerPlayingChange")
        assertTrue(
            "the counts are passed through the same key check as the picture",
            panelCall.contains("seasonEpisodeCount = panel.heroTitleKey()") &&
                panelCall.contains("?.takeIf { it == settledTitleKey }") &&
                panelCall.contains("?.let { settledSeasonEpisodeCount }")
        )
        assertTrue(
            "and the backdrop keeps its own identical gate next to it",
            panelCall.contains("backdropUrl = panel.heroTitleKey()")
        )
    }

    @Test
    fun `the counts sit in the length slot, after the year and before the runtime`() {
        val line = source(LINE)
        val year = line.indexOf("year?.trim()")
        val length = line.indexOf("length?.trim()")
        val runtime = line.indexOf("runtime?.trim()")
        val rating = line.indexOf("rating?.trim()")
        val genre = line.indexOf("genre?.trim()")
        assertTrue("the year comes first", year > 0)
        assertTrue("then the length slot", length > year)
        assertTrue("then the runtime", runtime > length)
        assertTrue("then the rating", rating > runtime)
        assertTrue("then the genre", genre > rating)
        assertTrue(
            "a blank part is skipped rather than joined, so an absent count " +
                "leaves no gap",
            line.contains("takeIf { it.isNotEmpty() }")
        )
    }

    @Test
    fun `the panel passes the counts into the line and keeps its two lines`() {
        val preview = source(PREVIEW)
        assertTrue(
            "the panel's line takes the counts",
            preview.contains("spotlightMetaLine(meta, seasonEpisodeCount)")
        )
        val lineCall = preview.substringAfter("Text(\n            text = when {\n                metaFailed")
            .substringBefore("val overview =")
        assertTrue(
            "two lines: the counts wrap, they do not push the synopsis off",
            lineCall.contains("maxLines = 2")
        )
        assertTrue(
            "the panel takes the counts as a parameter rather than reading hero " +
                "state itself",
            preview.contains("seasonEpisodeCount: String? = null")
        )
    }

    @Test
    fun `a series and a movie diverge by the data, not by a flag`() {
        val preview = source(PREVIEW)
        val line = preview.substringAfter("internal fun spotlightMetaLine(")
            .substringBefore("/** How much bigger than its card")
        assertFalse(
            "no isMovie branch: the null from the formatter is the gate",
            line.contains("isMovie") || line.contains("== \"movie\"")
        )
    }
}
