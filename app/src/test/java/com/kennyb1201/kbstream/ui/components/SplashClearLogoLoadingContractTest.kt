package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splash's logo slot is empty while the clearlogo loads.
 *
 * Field report: the title's text flashed for a frame right before the pulsing
 * logo appeared, on both the "Finding sources" splash and the player's own
 * first-load splash. The name stood in until Coil reported a painted art; the
 * stand-in was deliberate once (a cold image cache read as a failed screen),
 * but the flash is worse, so the name is now only the FAILED-fetch fallback.
 *
 * A Composable's loading frame cannot be rendered in this JVM suite, so this
 * pins the three-state wiring from the source, as the rest of the splash
 * contracts do.
 */
class SplashClearLogoLoadingContractTest {

    private val splash by lazy {
        read("com/kennyb1201/kbstream/ui/components/AutoPlayLoadSplash.kt")
    }

    /** The PulsingClearLogo composable and everything after it. */
    private val logo by lazy { splash.substringAfter("fun PulsingClearLogo(") }

    @Test
    fun `the slot starts empty and names itself only on failure`() {
        assertTrue(
            "a new title starts in the loading state",
            logo.contains("mutableStateOf(ClearLogoState.LOADING)")
        )
        assertTrue(
            "the name is gated on the failed state",
            logo.contains("if (artState == ClearLogoState.FAILED) {")
        )
        val failure = logo.substringAfter("if (artState == ClearLogoState.FAILED) {")
            .substringBefore("AsyncImage(")
        assertTrue("the failed state draws the name", failure.contains("Text("))
        assertTrue("with the title", failure.contains("text = title"))

        val beforeGate = logo.substringBefore("if (artState == ClearLogoState.FAILED) {")
        assertFalse(
            "nothing else in the slot draws text during loading or after painting",
            beforeGate.contains("Text(")
        )
    }

    @Test
    fun `coil success paints the art and failure falls back to the name`() {
        assertTrue(
            "a painted image is the only path to the pulsing art",
            logo.contains("onSuccess = { artState = ClearLogoState.PAINTED }")
        )
        assertTrue(
            "a failed fetch is the one path that brings the name back",
            logo.contains("onError = { artState = ClearLogoState.FAILED }")
        )
    }

    @Test
    fun `the name handover animation is gone`() {
        assertFalse("the text fade has nothing left to fade", splash.contains("NAME_HANDOVER_MS"))
        assertFalse("and no alpha state drives the old stand-in", splash.contains("animateFloatAsState"))
        assertFalse("nor a nameAlpha layer", logo.contains("nameAlpha"))
        assertTrue(
            "Coil's own art fade is still the only fade in the slot",
            splash.contains("crossfade(ART_FADE_MS)")
        )
    }

    @Test
    fun `the no-URL branch still prints the name immediately`() {
        val branch = splash.substringAfter("if (resolvedLogo != null) {")
            .substringBefore("if (!subtitle.isNullOrBlank())")

        assertTrue("the no-URL branch is still an else", branch.contains("} else {"))
        val noUrl = branch.substringAfter("} else {")
        assertTrue("it draws text", noUrl.contains("Text("))
        assertTrue("with the title", noUrl.contains("text = title"))
        assertFalse(
            "and no logo slot is composed without a URL",
            noUrl.contains("PulsingClearLogo(")
        )
    }

    @Test
    fun `the slot is keyed on the URL`() {
        assertTrue(
            "a splash for a different title must not inherit the previous art state",
            logo.contains("remember(url)")
        )
    }

    private fun read(path: String): String {
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
        throw AssertionError("main source root not found")
    }
}
