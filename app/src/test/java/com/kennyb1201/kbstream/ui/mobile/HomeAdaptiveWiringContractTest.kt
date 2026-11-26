package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3's wiring, pinned where the rules already are.
 *
 * [MobileAdaptiveRules] owns the arithmetic and `MobileAdaptiveRulesTest` owns its
 * boundaries. What a unit test cannot see is whether the Home screen actually asks
 * for those answers - a rule nobody calls is a comment. These assertions are the
 * join: the billboard's height comes from `homeBillboardHeightDp`, the content
 * column from `homeContentMaxWidthDp`, and the TV's own fixed-height branch is
 * still exactly where it was.
 */
class HomeAdaptiveWiringContractTest {

    private val home: String by lazy { stripComments(source(HOME)) }

    @Test
    fun `the billboard asks the rule for its height`() {
        assertTrue(
            "Home no longer asks homeBillboardHeightDp, so a tablet gets the " +
                "16:10 slice of its width that the rule exists to cap",
            home.contains("MobileAdaptiveRules.homeBillboardHeightDp(")
        )
        assertTrue(
            "the rule's answer must be handed to the billboard as heroHeight, and " +
                "only above Compact (0 means \"keep the aspect ratio\")",
            home.contains("heroHeight = billboardHeightDp.takeIf { it > 0 }?.dp,")
        )
    }

    @Test
    fun `the content column is capped on an expanded window`() {
        assertTrue(
            "Home no longer asks homeContentMaxWidthDp, so rails run edge to edge " +
                "on a 13\" tablet",
            home.contains("MobileAdaptiveRules.homeContentMaxWidthDp(")
        )
        assertTrue(
            "the cap must be a widthIn on the list container, not per rail",
            home.contains("Modifier.widthIn(max = contentMaxWidthDp.dp)")
        )
        assertTrue(
            "and the capped column is centred, or the cap just leaves a gap at " +
                "the end of every rail",
            home.contains(".align(Alignment.TopCenter)")
        )
    }

    @Test
    fun `the television's fixed height branch is untouched`() {
        assertTrue(
            "the TV Home's own frame (a height handed down, full width) is not " +
                "this spec's to change",
            home.contains("Modifier.height(heroHeight)")
        )
        assertTrue(
            "and the compact fallback is still the aspect the phone ships " +
                "(16:11 since 2026-11-25)",
            home.contains("Modifier.aspectRatio(16f / 11f)")
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
        const val HOME = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
    }
}
