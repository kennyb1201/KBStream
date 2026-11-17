package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Year in Review header's back control (spec 2026-11-16).
 *
 * The phone showed the word BACK there while every other phone screen showed the
 * shared arrow: this page is one layout for both form factors, and it was never
 * moved onto [com.kennyb1201.kbstream.ui.mobile.MobileBackButton]. The TV keeps
 * the text button - there it is the D-pad-focusable affordance, and a television
 * has no touch back arrow.
 *
 * Pinned from the source, the way the other screen contract tests are: the header
 * is a composable and this harness has no Android runtime.
 */
class YearReviewBackControlContractTest {

    private val screen = source("com/kennyb1201/kbstream/ui/settings/YearReviewScreen.kt")

    /** The header Row's contents: everything between its spacing and the title. */
    private val backControl: String
        get() = screen.substringAfter("Arrangement.spacedBy(16.dp)").substringBefore("KBPageTitle(")

    @Test
    fun `the phone header draws the shared arrow`() {
        assertTrue(
            "the phone branch has to use the one back control, not its own",
            screen.contains("import com.kennyb1201.kbstream.ui.mobile.MobileBackButton")
        )
        assertTrue(
            "and it is what the phone branch draws",
            backControl.contains("MobileBackButton(onBack = onBack)")
        )
        assertTrue(
            "the arrow takes the same callback the text button did, not a second path",
            !backControl.contains("onBack = {")
        )
    }

    @Test
    fun `the TV header keeps its text button`() {
        assertTrue(
            "the TV branch is unchanged: a focusable text button",
            backControl.contains("KBButton(label = \"BACK\", onClick = onBack)")
        )
        assertTrue(
            "and the branch is on the form factor, where the padding already branches",
            backControl.contains("if (com.kennyb1201.kbstream.FormFactor.isTv)")
        )
    }

    @Test
    fun `the title and system back are untouched`() {
        assertTrue(
            "the title is still the shared page title",
            screen.contains("KBPageTitle(text = \"YOUR \${state.year} IN REVIEW\")")
        )
        assertTrue(
            "system back still goes through the screen's own handler",
            screen.contains("BackHandler(onBack = onBack)")
        )
        // The spec's phone-only scope: nothing about the rest of the page moved.
        assertFalse(
            "the year picker, the rows and the share button are not part of this",
            screen.contains("MobileBackButton(") &&
                screen.substringAfter("KBPageTitle(").contains("MobileBackButton(")
        )
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
}
