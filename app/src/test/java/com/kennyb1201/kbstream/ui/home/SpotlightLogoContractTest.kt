package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clearlogo in the Spotlight panel (spec 2026-11-15).
 *
 * The failures this pins are all quiet ones: a logo for the title the hero left
 * behind, a second logo drawer with its own fit and fallback, a panel that grows
 * a new fetch for art the hero already resolved, and a title area that blanks
 * itself when the mark is missing or fails.
 */
class SpotlightLogoContractTest {

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
    }

    @Test
    fun `the logo is the hero's own, not a second resolve`() {
        val screen = source(SCREEN)
        assertTrue(
            "the panel takes the state the hero draws its own wordmark from",
            screen.contains("settledLogoUrl = heroLogoUrlState.value")
        )
        val host = source(HOST)
        assertFalse(
            "no art repository in the host: the logo rides resolveHeroMeta",
            host.contains("TmdbHeroArtworkRepository") ||
                host.contains("FanartArtRepository") ||
                host.contains("TmdbRepository")
        )
        val preview = source(PREVIEW)
        assertFalse(
            "no add-on or fanart fallback either - the panel follows the hero's rule",
            preview.contains("meta?.logo") || preview.contains("FanartArtRepository")
        )
    }

    @Test
    fun `the host gates the logo on the settled title, beside the backdrop and counts`() {
        val host = source(HOST)
        assertTrue("the host takes the settled logo", host.contains("settledLogoUrl: String? = null"))
        val panelCall = host.substringAfter("SpotlightPreviewPanel(\n            snapshot = panel")
            .substringBefore("trailerKey = trailerKey")
        assertTrue(
            "the same key check as the picture: never one title's logo under another's name",
            panelCall.contains("logoUrl = panel.heroTitleKey()") &&
                panelCall.contains("?.takeIf { it == settledTitleKey }") &&
                panelCall.contains("?.let { settledLogoUrl }")
        )
        assertTrue(
            "and the backdrop's gate is still there, unchanged",
            panelCall.contains("backdropUrl = panel.heroTitleKey()")
        )
    }

    @Test
    fun `the panel reuses the hero's drawer rather than building a second one`() {
        val preview = source(PREVIEW)
        assertTrue(
            "one logo drawer in the app",
            preview.contains("HeroClearLogo(") && preview.contains("url = settledLogo")
        )
        val screen = source(SCREEN)
        assertTrue(
            "and it is reachable: the hero's drawer is internal now, not private",
            screen.contains("internal fun HeroClearLogo(") &&
                !screen.contains("private fun HeroClearLogo(")
        )
        assertTrue(
            "its reuse does not change the hero: both new params default to the " +
                "hero's own behaviour",
            screen.contains("showPlaceholder: Boolean = true") &&
                screen.contains("onPainted: (Boolean) -> Unit = {}")
        )
    }

    @Test
    fun `the logo fills the title's box and the text keeps its place`() {
        val preview = source(PREVIEW)
        val box = preview.substringAfter("val titleText = meta?.name ?: snapshot.title.orEmpty()")
            .substringBefore("// Silence while the meta resolves")
        assertTrue(
            "the logo is measured against the title Text, so the box keeps its " +
                "height and nothing below it moves",
            box.contains("Modifier.matchParentSize()")
        )
        assertTrue(
            "the text is transparent once the art paints, never removed: removing " +
                "it would collapse the box and pull the meta line up",
            box.contains("color = if (logoPainted) Color.Transparent else KBTextHi")
        )
        assertTrue(
            "the drawer does not draw its own stand-in - the panel's Text is the " +
                "fallback",
            box.contains("showPlaceholder = false")
        )
        assertTrue(
            "the panel learns when the art painted",
            box.contains("onPainted = { logoPainted = it }")
        )
        assertTrue(
            "two lines is still the title's limit",
            box.contains("maxLines = 2")
        )
    }

    @Test
    fun `no logo is the text title, unchanged`() {
        val preview = source(PREVIEW)
        assertTrue(
            "the null gate is the logo's own null, so a title without a TMDB mark " +
                "draws exactly today's title",
            preview.contains("val settledLogo = logoUrl?.takeIf { it.isNotBlank() }") &&
                preview.contains("if (settledLogo != null) {")
        )
        assertTrue(
            "and the title Text is drawn unconditionally, which is what makes the " +
                "missing-mark case unchanged",
            preview.contains("text = titleText")
        )
    }

    @Test
    fun `a failed logo leaves the title up`() {
        val screen = source(SCREEN)
        val drawer = screen.substringAfter("internal fun HeroClearLogo(")
            .substringBefore("private fun isDarkMonochromeArtwork(")
        assertTrue(
            "artPainted stays false on failure, and the name stays drawn",
            drawer.contains("if (!artPainted && showPlaceholder)") &&
                drawer.contains("text = name")
        )
        assertTrue(
            "the panel's own Text is the fallback when the drawer's stand-in is off",
            source(PREVIEW).contains(
                "color = if (logoPainted) Color.Transparent else KBTextHi"
            )
        )
    }
}
