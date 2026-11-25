package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7a's wiring. The arithmetic is `MobileAdaptiveRules.detailColumnMaxWidthDp`'s
 * and `MobileAdaptiveRulesTest` already pins it at 599/839dp and Compact/Medium/
 * Expanded. What is pinned here is that the Detail page asks for it, caps the
 * list container (not a section), and keeps the header art tied to the column so
 * the art shrinks with it instead of being cropped in a full-width frame.
 */
class DetailColumnCapContractTest {

    private val detail: String by lazy { stripComments(source(DETAIL)) }

    @Test
    fun `the column cap comes from the shared rule`() {
        assertTrue(
            "Detail no longer asks detailColumnMaxWidthDp, so a Medium window " +
                "keeps an overview paragraph running the full 840dp",
            detail.contains("MobileAdaptiveRules.detailColumnMaxWidthDp(")
        )
        assertTrue(
            "the cap must be a widthIn on the page's own column, not on a section",
            detail.contains("Modifier.widthIn(max = detailColumnCapDp.dp)")
        )
        assertTrue(
            "and the capped column is centred - otherwise the cap just leaves a " +
                "gap down the right-hand side of the page",
            detail.contains(".align(Alignment.TopCenter)")
        )
    }

    @Test
    fun `the compact path is the old full-size list`() {
        assertTrue(
            "the uncapped branch must still be the plain container: the rule " +
                "answers 0 below Medium and that path has to remain today's layout",
            detail.contains("if (detailColumnCapDp > 0) {") &&
                detail.contains(".fillMaxWidth()")
        )
        assertTrue(
            "the header art is still 16:9 of its own width, which is what makes " +
                "the capped column cap the art with it",
            detail.contains("aspectRatio(16f / 9f)")
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun findMainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
    }
}
