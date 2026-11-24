package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings rows read as one settings screen on both surfaces.
 *
 * Reported from the field: "can we make our settings not look so amateurish".
 * The screen had two problems that were both visible in a screenshot - the TV
 * showed a toggle's state as a text pill reading ON/OFF while the phone showed
 * a real switch, and every heading was tracked capitals - on top of the copy
 * problem, which is a separate pass.
 *
 * This pins the row design half: a real switch at the end of a toggle row, one
 * focus stop per row, the same rhythm as a navigation row, and headings that do
 * not shout. The switch colours are asserted to be the PHONE's, because the
 * point of the change is that the two surfaces no longer disagree.
 *
 * Read from the source: these are Compose modifiers, and the verdict that
 * matters is Kenny's on a television.
 */
class SettingsRowDesignContractTest {

    private val tv: String by lazy { source(TV) }
    private val mobile: String by lazy { source(MOBILE) }

    /** The TV toggle row's own composable, and nothing else in the file. */
    private val tvToggleRow: String by lazy {
        tv.substringAfter("private fun ToggleRow(")
            .substringBefore("private fun FrameRateDiagnosticRow(")
    }

    /** The TV navigation row's own composable. */
    private val tvNavRow: String by lazy {
        tv.substringAfter("private fun NavigationRow(")
            .substringBefore("private fun HiddenTitlesPane(")
    }

    private val mobileToggleRow: String by lazy {
        mobile.substringAfter("internal fun SettingsToggleMobile(")
            .substringBefore("private fun SettingsNavRowMobile(")
    }

    @Test
    fun `the TV toggle row ends in a real switch, not an ON-OFF pill`() {
        assertTrue(
            "the row draws the platform switch",
            tvToggleRow.contains("Switch(")
        )
        assertFalse(
            "the text pill the field called amateurish is gone: it said the " +
                "state in words where the phone showed a control",
            tvToggleRow.contains("\"ON\" else \"OFF\"") ||
                tvToggleRow.contains("text = if (displayChecked)")
        )
        assertTrue(
            "and the state it shows is still the overridable one a gated row " +
                "needs (checked = displayChecked)",
            tvToggleRow.contains("checked = displayChecked")
        )
    }

    @Test
    fun `the switch uses the phone's component and colours`() {
        // The two surfaces disagreeing was the report. Asserting the TV's
        // colours against the phone's own source is what keeps them from
        // drifting apart again.
        listOf(
            "checkedThumbColor = KBVoid",
            "checkedTrackColor = KBAccent",
            "uncheckedThumbColor = KBTextLo",
            "uncheckedTrackColor = KBTextHi.copy(alpha = 0.18f)"
        ).forEach { colour ->
            assertTrue("the TV switch is missing: $colour", tvToggleRow.contains(colour))
            assertTrue("the phone switch is missing: $colour", mobileToggleRow.contains(colour))
        }
    }

    @Test
    fun `a toggle row is one focus stop`() {
        // A switch with its own callback is its own focusable, which would put a
        // second D-pad stop at the end of every toggle row and visit the same
        // setting twice. The card owns OK; the switch is the picture of state.
        assertTrue(
            "the TV switch has no callback of its own",
            tvToggleRow.contains("onCheckedChange = null")
        )
        assertTrue(
            "and the row's card is still what toggles it",
            tvToggleRow.contains("onClick = { if (enabled) onToggle(!checked) }")
        )
    }

    @Test
    fun `toggle and navigation rows share one rhythm`() {
        // The two halves of a pane are the same list, so they cannot be padded
        // differently - that was the "stack of cards with uneven gaps" the
        // report described.
        val rhythm = "padding(horizontal = 14.dp, vertical = 10.dp)"
        assertTrue("the toggle row's rhythm", tvToggleRow.contains(rhythm))
        assertTrue("the navigation row's rhythm", tvNavRow.contains(rhythm))
        assertTrue(
            "and a navigation row still says it leads somewhere",
            tvNavRow.contains("Icons.Filled.ChevronRight")
        )
    }

    @Test
    fun `section headings are written, not shouted`() {
        // Tracked capitals on every group heading is the other half of what made
        // the screen read as a hobby project rather than as the platform's own
        // settings. Case is the row's own; nothing here forces it.
        assertFalse(
            "the TV settings file still uppercases a heading",
            tv.contains(".uppercase()")
        )
        assertFalse(
            "the phone settings file still uppercases a heading",
            mobile.contains(".uppercase()")
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

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
        const val TV = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
    }
}
