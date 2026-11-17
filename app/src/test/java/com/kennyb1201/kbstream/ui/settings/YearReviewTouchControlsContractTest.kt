package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Year in Review buttons on a phone (spec 2026-11-16).
 *
 * The report was "clicking back does nothing, no buttons work on that screen",
 * with two facts that pin the cause down: the page SCROLLED (a drag reached the
 * LazyColumn) and system back WORKED (the screen's own BackHandler). So touch
 * input arrived and the navigation callback was fine - only the on-screen
 * controls' tap was dead.
 *
 * The cause is the control, not the wiring: this shared page drew TELEVISION
 * controls. [com.kennyb1201.kbstream.ui.components.KBButton] is a
 * `components.KBCard`, i.e. a tv-material3 `Card(onClick)`, and the year chips
 * were a tv clickable `Surface` - focus surfaces, activated by the remote's
 * Select/Enter key (see `ui/components/touch/TouchCard.kt`'s own KDoc). A finger
 * produces no key event. The phone Home was never a counter-example: it imports
 * `ui.components.touch.KBCard`, not the TV one, and every working phone surface
 * uses the touch package.
 *
 * Pinned from the source, the way the other screen contract tests are: the
 * controls are composables and this harness has no Android runtime.
 */
class YearReviewTouchControlsContractTest {

    private val screen = source("com/kennyb1201/kbstream/ui/settings/YearReviewScreen.kt")

    @Test
    fun `the phone gets touch-native controls for every action`() {
        assertTrue(
            "the shared layout must not hand a phone the TV button",
            screen.contains("import com.kennyb1201.kbstream.ui.components.touch.KBButton as TouchKBButton")
        )
        assertTrue(
            "and the year chips get the chip that owns its tap",
            screen.contains("import com.kennyb1201.kbstream.ui.components.touch.TouchChoiceChip")
        )

        val action = screen.substringAfter("private fun ReviewAction(")
            .substringBefore("\n}")
        assertTrue(
            "RETRY and SHARE THIS YEAR both go through it",
            screen.contains("ReviewAction(label = \"RETRY\"") &&
                screen.contains("ReviewAction(label = \"SHARE THIS YEAR\"")
        )
        assertTrue("the phone branch is the Material 3 button", action.contains("TouchKBButton("))
        assertTrue("and the TV branch is the unchanged card", action.contains("KBButton(label = label"))
        assertTrue(
            "branched on the form factor",
            action.contains("if (com.kennyb1201.kbstream.FormFactor.isTv)")
        )

        val chip = screen.substringAfter("private fun YearChip(").substringBefore("\n@Composable")
        assertTrue(
            "the chip's phone branch is the touch chip, with the same selected state",
            chip.contains("TouchChoiceChip(") && chip.contains("selected = selected")
        )
        assertTrue(
            "and the TV surface is still there",
            chip.contains("Surface(") && chip.contains("ClickableSurfaceDefaults")
        )
    }

    @Test
    fun `the phone composes under the phone theme`() {
        // The touch twins read material3's typography and colour scheme. This
        // page was reaching a phone with only the TELEVISION theme around it, so
        // without this the controls would be the platform's defaults, not the
        // brand's - and the header arrow would be too.
        val entry = screen.substringAfter("fun YearReviewScreen(").substringBefore("\n@Composable")
        assertTrue(
            "the phone entry wraps the shared layout in MobileTheme",
            entry.contains("com.kennyb1201.kbstream.ui.theme.MobileTheme {")
        )
        assertTrue(
            "and still draws the same content either way",
            entry.contains("YearReviewContent(onBack = onBack, year = year, viewModel = viewModel)")
        )
        assertFalse(
            "there is no second copy of the layout",
            Regex("private fun YearReviewContent").findAll(screen).count() != 1
        )
    }

    @Test
    fun `the TV keeps its own controls and its own path`() {
        assertTrue(
            "the TV page still opens on the television theme",
            screen.contains("KBPageTitle(text = \"YOUR \${state.year} IN REVIEW\")")
        )
        assertTrue(
            "and the year picker still loads the year it names",
            screen.contains("onClick = { viewModel.load(candidate) }")
        )
        assertTrue(
            "with the TV chip unchanged",
            screen.contains("onClick = onClick,") && screen.contains("ClickableSurfaceDefaults.shape")
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
