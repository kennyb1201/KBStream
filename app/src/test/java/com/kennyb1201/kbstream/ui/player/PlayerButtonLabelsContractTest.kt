package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player buttons' labels (spec 2026-11-17).
 *
 * The ask: "can we add a popup on hover with the label of each button in player
 * does for people that dont know what the symbols mean." One shared chrome
 * serves all three players, so the change is one file pair:
 *
 *  - F1: every icon-only button has a short label, taken from its own
 *    contentDescription - Speed and Aspect gain one ("Playback speed",
 *    "Aspect ratio"), and Play/Pause follows state;
 *  - F2: the platform tooltip (`setTooltipText`) is the hover/long-press popup;
 *  - F3: on a D-pad there is no hover, so the label shows on focus in one view
 *    that takes the bar's existing spacer slot - it cannot move the bar, cover
 *    a button, or (being in the bar) cover the picture;
 *  - F5: Skip Intro is excluded, it already draws its own text.
 *
 * The composable-style source pins only; the behaviour itself (a focus dispatch
 * filling the label, a long-press tooltip) is covered against the inflated
 * layout in PlayerChromeContractTest, which needs Robolectric.
 */
class PlayerButtonLabelsContractTest {

    private val chrome by lazy { source(RES, "layout/player_chrome.xml") }
    private val code by lazy { source(MAIN, PLAYER_CHROME) }

    /** One view tag from its declaration id to the end of the tag. */
    private fun tag(xml: String, id: String): String =
        xml.substringAfter("@+id/$id").substringBefore("/>")

    private val labelled = listOf(
        "chrome_btn_play_pause" to "Play/Pause",
        "chrome_btn_next" to "Next episode",
        "chrome_btn_source" to "Change source",
        "chrome_btn_episodes" to "Episodes",
        "chrome_btn_audio" to "Audio track",
        "chrome_btn_subtitle" to "Subtitles",
        "chrome_btn_speed" to "Playback speed",
        "chrome_btn_aspect" to "Aspect ratio",
        "chrome_btn_player_switch" to "Switch player",
        "chrome_btn_player_external" to "Play in another app",
        "chrome_btn_info" to "Stream info",
        "chrome_btn_settings" to "Playback settings"
    )

    // ── F1/F7: one label per button, and it is the announced one ─────────

    @Test
    fun `all twelve icon-only buttons carry their label`() {
        assertEquals("the twelve the spec names", 12, labelled.size)
        labelled.forEach { (id, label) ->
            assertTrue(
                "$id has a label: $label",
                tag(chrome, id).contains("android:contentDescription=\"$label\"")
            )
        }
    }

    @Test
    fun `speed and aspect stop being unlabelled`() {
        listOf("chrome_btn_speed" to "Playback speed", "chrome_btn_aspect" to "Aspect ratio")
            .forEach { (id, label) ->
                assertTrue(
                    "$id is one of the two that had no contentDescription at all, so " +
                        "TalkBack and the new label needed the same string",
                    tag(chrome, id).contains("android:contentDescription=\"$label\"")
                )
            }
    }

    @Test
    fun `the tooltip is the button's own contentDescription, not a second copy`() {
        assertTrue(
            "one string serves the tooltip, the focus label and TalkBack (F7)",
            code.contains("button.setTooltipText(button.contentDescription)")
        )
        assertTrue(
            "and it is the platform tooltip, not custom UI (F2)",
            code.contains("private fun wireLabels()") &&
                !code.contains("PopupWindow")
        )
        assertTrue(
            "wired once, from the chrome's own init",
            code.contains("wireButtons()\n        wireLabels()")
        )
    }

    @Test
    fun `play pause names the action the press will perform`() {
        assertTrue(
            "the label is written from the host's state",
            code.contains("val label = if (host.chromeIsPlaying()) \"Pause\" else \"Play\"")
        )
        assertTrue(
            "contentDescription and tooltip move together, so F7 holds on a state change too",
            code.contains("button.contentDescription = label") &&
                code.contains("button.setTooltipText(label)")
        )
        assertTrue(
            "and every progress tick re-derives it, beside the glyph it matches",
            code.contains("syncPlayPauseLabel()") &&
                code.contains("R.drawable.ic_player_pause else R.drawable.ic_player_play")
        )
    }

    // ── F3/S4/S5: the focus label ────────────────────────────────────────

    @Test
    fun `the focus label takes the bar's spacer slot, so nothing moves`() {
        val label = tag(chrome, "chrome_focus_label")
        assertTrue(
            "it is a view the chrome owns, inside the control bar",
            chrome.contains("android:id=\"@+id/chrome_focus_label\"")
        )
        assertTrue(
            "it is the spacer: zero width, weight 1 - the space was already empty, " +
                "so text appearing cannot shift the bar or cover a button",
            label.contains("android:layout_width=\"0dp\"") &&
                label.contains("android:layout_weight=\"1\"")
        )
        assertTrue(
            "one short phrase, ellipsized rather than wrapped",
            label.contains("android:maxLines=\"1\"") &&
                label.contains("android:ellipsize=\"end\"")
        )
        assertFalse(
            "it must never become a focus stop of its own",
            label.contains("android:focusable=\"true\"")
        )
        assertTrue(
            "and it is silent to TalkBack, which already reads the button (F7)",
            label.contains("android:importantForAccessibility=\"no\"")
        )
        assertTrue(
            "it uses the chrome's own text style",
            label.contains("fontFamily=\"@font/inter_medium\"")
        )
        assertFalse(
            "empty text is the hidden state, so visibility never changes the layout",
            label.contains("android:visibility=")
        )
    }

    @Test
    fun `focus shows the label and leaving or hiding the bar takes it away`() {
        assertTrue(
            "the focused control's label is shown on gain, and only that control's " +
                "blur clears it - focus moves old -> new in one dispatch",
            code.contains("if (focused) {") &&
                code.contains("handler.removeCallbacks(hideRunnable)") &&
                code.contains("showFocusLabel(control)") &&
                code.contains("clearFocusLabel(control)") &&
                code.contains("if (labelledButton !== button) return")
        )
        assertTrue(
            "the bar taking itself down takes the label with it",
            code.contains("fun hide() {") &&
                code.substringAfter("fun hide() {").substringBefore("fun restartAutoHide")
                    .contains("hideFocusLabel()")
        )
        assertTrue(
            "a control with no label (the seek bar) shows nothing",
            code.contains("if (label.isBlank()) return")
        )
    }

    @Test
    fun `the label list is the twelve buttons and never skips intro`() {
        val list = code.substringAfter("private fun labelledButtons(): List<View> = listOfNotNull(")
            .substringBefore(")")
        val fields = listOf(
            "playPauseButton",
            "nextButton",
            "sourceButton",
            "episodesButton",
            "audioButton",
            "subtitleButton",
            "speedButton",
            "aspectButton",
            "playerSwitchButton",
            "externalButton",
            "infoButton",
            "settingsButton"
        )
        assertEquals("twelve labels: $list", 12, fields.size)
        fields.forEach { field ->
            assertTrue("$field is labelled", list.contains(field))
        }
        assertFalse(
            "Skip Intro draws its own text, so a second label would duplicate it (F5)",
            list.contains("skipButton")
        )
    }

    // ── F6: the shared chrome only ───────────────────────────────────────

    @Test
    fun `the auto-hide timer and the pickers are untouched`() {
        assertTrue(
            "the six-second timeout is still the one both activities used",
            code.contains("const val AUTO_HIDE_MS = 6_000L")
        )
        assertTrue(
            "one focus listener per control still carries the hold",
            code.contains("if (focused) {") && code.contains("else {\n                    keepVisible()")
        )
        assertTrue(
            "the three players still share this one chrome",
            code.contains("class PlayerChrome(") &&
                code.contains("R.layout.player_chrome")
        )
    }

    private fun source(root: String, relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/$root")
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
        const val MAIN = "java"
        const val RES = "res"
        const val PLAYER_CHROME = "com/kennyb1201/kbstream/ui/player/PlayerChrome.kt"
    }
}
