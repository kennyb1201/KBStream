package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F6's three promises, pinned from source.
 *
 * 1. The two-pane layout is Expanded only - Compact and Medium keep the push
 *    model, which is the layout every phone shipped with.
 * 2. The pane body exists ONCE. Two copies of the twelve-callback `when` would
 *    drift on the first pane anyone adds.
 * 3. Back on two panes exits Settings. There is no page to pop when both panes
 *    are on screen, and the pushed model's `pane != null -> pane = null` arm must
 *    not run there (it would look like a dead Back).
 */
class SettingsTwoPaneContractTest {

    private val screen: String by lazy { stripComments(source(SCREEN)) }

    @Test
    fun `the two-pane layout is gated on the expanded class`() {
        assertTrue(
            "Settings no longer asks settingsTwoPaneApplies, so a tablet keeps " +
                "pushing panes over the list",
            screen.contains("MobileAdaptiveRules.settingsTwoPaneApplies(widthClass)")
        )
        assertTrue(
            "the list pane must take the rule's width, not a literal",
            screen.contains("MobileAdaptiveRules.SETTINGS_LIST_WIDTH_DP.dp")
        )
    }

    @Test
    fun `the push model is still the fall-through`() {
        assertTrue(
            "the compact/medium branch must still push the pane over the list",
            screen.contains("} else if (open == null) {") &&
                screen.contains("} else if (downloadsOpen) {")
        )
    }

    @Test
    fun `one pane body, rendered by both layouts`() {
        assertEquals(
            "the pane `when` is duplicated: one copy per layout means the next " +
                "pane added lands in only one of them",
            1,
            Regex("SettingsPane\\.INTEGRATIONS -> IntegrationsPaneMobile\\(")
                .findAll(screen)
                .count()
        )
        assertEquals(
            "the pane header is drawn once, inside the shared body",
            1,
            Regex("PaneHeaderMobile\\(pane = ").findAll(screen).count()
        )
    }

    @Test
    fun `back leaves Settings when both panes are visible`() {
        assertTrue(
            "the two-pane Back arm is missing, so Back falls through to " +
                "`pane != null -> pane = null` and reads as a dead press",
            screen.contains("twoPane -> if (downloadsOpen) downloadsOpen = false else onBack()")
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
        const val SCREEN = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
    }
}
