package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV engine's INFO panel: the main player's readout screen, on this engine.
 *
 * Two things are pinned, and they are the two the field can actually tell apart.
 *
 * The first is parity, structurally: the panel the MPV activity raises has to be
 * the same panel the main player raises - the same ids in its own layout, the
 * same shared `infoPanelDrawable` fill, the same Back/OK dismissal - because the
 * panel's whole purpose is a side-by-side read of one file on both engines. A
 * row that exists on one engine and not the other is the comparison failing. The
 * layout half is read out of both XML files rather than asserted by hand, so a
 * row added to the main player's panel without one here fails this test.
 *
 * The second is that the numbers come from mpv. This activity cannot be driven
 * from a JVM test, so what is checked is that the readout asks the engine which
 * is playing (the properties `fileReport()` reads) and that it goes through the
 * tested formatter for every row, instead of formatting properties inline where
 * a missing one becomes the string "null" on a TV. The rows themselves are
 * pinned behaviorally by [MpvInfoLinesTest].
 *
 * The old behaviour this replaces was a four-second toast
 * (`showToast("MPV • ${diagnosticsText()}")`): at TV distance it could not be
 * read, it could not be compared with the other engine's panel, and it was gone
 * before the first line was finished.
 */
class MpvInfoPanelContractTest {

    private val activity by lazy { read(MPV_ACTIVITY) }
    private val view by lazy { read(MPV_VIEW) }
    private val mainLayout by lazy { readLayout("activity_player.xml") }
    private val mpvLayout by lazy { readLayout("activity_mpv_player.xml") }

    // ── the layout is the main player's panel, id for id ──────────────────

    @Test
    fun `the MPV layout carries every id the main player's info panel has`() {
        val main = infoIds(mainLayout)
        val mpv = infoIds(mpvLayout)
        // Sanity: the scan reads the main player's panel at all.
        assertTrue("the main player's panel was not found: $main", main.contains("info_panel"))
        assertEquals(
            "the MPV panel is missing rows the main player's panel has: " +
                "${main.filterNot { it in mpv }}",
            emptyList<String>(),
            main.filterNot { it in mpv }
        )
    }

    @Test
    fun `the panel is one self-contained overlay, gone until the button is pressed`() {
        val panel = mpvLayout.substringAfter("android:id=\"@+id/info_panel\"")
            .substringBefore("android:id=\"@+id/info_subtitle\"")
        assertTrue("the panel starts hidden", panel.contains("android:visibility=\"gone\""))
        // It has to be able to hold the D-pad itself: nothing inside a readout
        // is focusable, so OK would never reach the panel's own key listener.
        assertTrue("the panel takes focus", panel.contains("android:focusable=\"true\""))
        assertTrue(
            "and focus in touch mode too, the way the other panels here do",
            panel.contains("android:focusableInTouchMode=\"true\"")
        )
    }

    @Test
    fun `the MPV panel adds exactly the one row the main player does not have`() {
        // SUBTITLE: mpv is the engine that typesets an embedded ASS/SSA track
        // with libass, so which track is being drawn is worth a line here. It is
        // deliberate, and it is the only difference - anything else drifting in
        // is a comparison that no longer lines up.
        assertEquals(
            "only SUBTITLE may be MPV-only",
            setOf("info_subtitle", "info_subtitle_header"),
            (infoIds(mpvLayout) - infoIds(mainLayout).toSet()).toSet()
        )
    }

    // ── the button raises the panel, not a toast ──────────────────────────

    @Test
    fun `the INFO button toggles the panel`() {
        assertTrue(
            "the chrome's INFO button no longer raises the panel",
            activity.contains("override fun onChromeOpenInfo() = toggleInfoPanel()")
        )
        assertTrue("the toggle exists", activity.contains("private fun toggleInfoPanel() {"))
        assertTrue(
            "and it is a toggle: the same press that raises the panel drops it",
            activity.contains("if (infoPanelVisible) hideInfoPanel() else showInfoPanel()")
        )
    }

    @Test
    fun `the readout toast it replaced is gone`() {
        assertFalse(
            "the INFO button still answers with the old toast",
            activity.contains("showToast(\"MPV  \\u2022  \${diagnosticsText()}\"")
        )
        // ...but the diagnostics line itself is NOT gone: the settings panel's
        // stream row reads it, and that row is how a viewer already inside the
        // panel sees the same summary.
        assertTrue(
            "diagnosticsText() was deleted with the toast, but the settings row reads it",
            activity.contains("internal fun diagnosticsText(): String =")
        )
    }

    // ── the panel is a takeover, like the side panels ─────────────────────

    @Test
    fun `the panel is filled before it is shown, and the bar goes down under it`() {
        val show = activity.substringAfter("private fun showInfoPanel() {")
            .substringBefore("\n    private fun hideInfoPanel()")
        val build = show.indexOf("buildInfoPanel()")
        val visible = show.indexOf("panel.visibility = View.VISIBLE")
        val hideControls = show.indexOf("setControlsVisible(View.GONE)")
        assertTrue("the panel is built", build >= 0)
        assertTrue("then shown", visible > build)
        assertTrue("with the overlay out of its way", hideControls > visible)
        assertTrue(
            "and it takes the focus, which is what makes OK close it",
            show.contains("panel.requestFocus()")
        )
    }

    @Test
    fun `hiding the panel hands the remote back to the bar`() {
        val hide = activity.substringAfter("private fun hideInfoPanel() {")
            .substringBefore("\n    /**")
        assertTrue("the bar comes back", hide.contains("setControlsVisible(View.VISIBLE)"))
        assertTrue("focused where the side panels leave it", hide.contains("playPauseButton?.requestFocus()"))
        assertTrue("with its auto-hide running again", hide.contains("keepControlsVisible()"))
        // The same panel the main player's INFO uses: the XML fill is only the
        // pre-theme fallback, and this is the one surface meant to be READ.
        assertTrue(
            "the panel has to carry the shared opaque fill",
            activity.contains("infoPanel?.background = infoPanelDrawable(this)")
        )
    }

    @Test
    fun `OK and Back close the panel from inside it`() {
        val listener = activity.substringAfter("infoPanel?.setOnKeyListener {")
            .substringBefore("\n        }")
        assertTrue("the panel installs a key listener", listener.isNotBlank())
        assertTrue("Back closes it", listener.contains("KeyEvent.KEYCODE_BACK"))
        assertTrue("so does OK", listener.contains("KeyEvent.KEYCODE_DPAD_CENTER"))
        assertTrue("and ENTER with it", listener.contains("KeyEvent.KEYCODE_ENTER"))
        assertTrue("by hiding the panel", listener.contains("hideInfoPanel()"))
    }

    @Test
    fun `Back drops the readout before anything else it could close`() {
        val info = activity.indexOf("infoPanelVisible -> hideInfoPanel()")
        val nextUp = activity.indexOf("nextUpPanel?.visibility == View.VISIBLE -> dismissNextUpPanel()")
        val picker = activity.indexOf("pickerOpen -> dismissPicker()")
        val settings = activity.indexOf("settingsOpen -> hideSettingsPanel()")
        assertTrue("the readout owns Back", info >= 0)
        assertTrue("and it is the first arm of the chain", info < nextUp)
        assertTrue("before the side panels", info < picker && info < settings)
    }

    @Test
    fun `no key reaches playback while the readout is up`() {
        val settingsGate = activity.indexOf("if (settingsOpen) return super.dispatchKeyEvent(event)")
        val infoGate = activity.indexOf("if (infoPanelVisible) return super.dispatchKeyEvent(event)")
        assertTrue("the panel gates the key path", infoGate > settingsGate)
    }

    @Test
    fun `a side panel replaces the readout rather than opening under it`() {
        val picker = activity.substringAfter("private fun showPicker(mode: PickerMode) {")
            .substringBefore("\n    private fun dismissPicker()")
        assertTrue(
            "opening the picker has to drop the readout",
            picker.indexOf("hideInfoPanel()") in 0 until picker.indexOf("settingsOpen = true")
        )
        val settings = activity.substringAfter("private fun showSettingsPanel() {")
            .substringBefore("\n    private fun hideSettingsPanel()")
        assertTrue(
            "and so does the settings panel",
            settings.indexOf("hideInfoPanel()") in 0 until settings.indexOf("settingsOpen = true")
        )
    }

    // ── the rows come from mpv, through the tested formatter ──────────────

    @Test
    fun `every row is built by the formatter, so no row can come out empty`() {
        val build = activity.substringAfter("private fun buildInfoPanel() {")
            .substringBefore("\n    private fun refreshSettings()")
        assertEquals("the file row", 1, count(build, "MpvInfoLines.fileRow("))
        assertEquals("the engine row", 1, count(build, "MpvInfoLines.ENGINE_LABEL"))
        assertEquals("the video block", 1, count(build, "MpvInfoLines.videoBlock("))
        assertEquals("the audio block", 1, count(build, "MpvInfoLines.audioBlock("))
        assertEquals("the subtitle row", 1, count(build, "MpvInfoLines.subtitleLine("))
        // The source row is the one value that is not mpv's: the addon name, or
        // the source's own label, and a dash when neither is known - never an
        // empty string, which is what a missing label used to leave behind.
        assertTrue("the source row has its own fallback", build.contains("?: \"—\""))
        // And the two blocks that are empty before the first frame say so in the
        // same words the settings panel's stream row uses for that state.
        assertTrue(
            "the pre-open state has a name",
            activity.contains("private const val WAITING_FOR_FILE = \"Waiting for the file to open\"")
        )
        assertEquals(
            "used for both blocks while nothing has opened",
            2,
            count(build, "?: WAITING_FOR_FILE")
        )
    }

    @Test
    fun `the readout asks mpv for the file's properties`() {
        // Every property the panel's rows are made of. A row built from anything
        // else (a container header, a guess from the URL) is not this engine's
        // account of the file, which is the whole value of the readout.
        val properties = listOf(
            "video-format",
            "video-params/w",
            "video-params/h",
            "hwdec-current",
            "video-params/pixelformat",
            "video-params/primaries",
            "video-params/gamma",
            "video-params/sig-peak",
            "video-bitrate",
            "audio-codec",
            "audio-params/channel-count",
            "audio-params/samplerate"
        )
        properties.forEach { property ->
            assertTrue(
                "the readout never asks mpv for $property",
                view.contains("getPropertyStringOrNull(\"$property\")") ||
                    view.contains("getPropertyIntOrNull(\"$property\")") ||
                    view.contains("getPropertyDoubleOrNull(\"$property\")")
            )
        }
    }

    @Test
    fun `the file report is not read off a dead instance`() {
        val report = view.substringAfter("fun fileReport(): FileReport? {")
            .substringBefore("\n    }")
        assertTrue(
            "reading a property off a handle that is not there yet is a native " +
                "call, not a Kotlin one, so it has to be guarded",
            report.contains("if (!initialized) return null")
        )
        // The subtitle row names the track that is actually selected, not the id
        // mpv has for it: the panel has to name it to the viewer.
        assertTrue(
            "the subtitle row comes from the selected track",
            report.contains("subtitleTracks().firstOrNull { it.selected }")
        )
    }

    private fun infoIds(xml: String): List<String> =
        Regex("android:id=\"@\\+id/(info_[a-z_]+)\"")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()

    private fun count(text: String, needle: String): Int = text.split(needle).size - 1

    private fun read(relative: String): String {
        val file = File(findMainSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun readLayout(name: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/res/layout/$name")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "layout not found walking up from " + System.getProperty("user.dir") + ": $name"
        )
    }

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
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
    }
}
