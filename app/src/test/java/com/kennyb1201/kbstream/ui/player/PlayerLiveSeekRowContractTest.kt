package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live television has no scrub bar; VOD keeps it (field report 2026-11-12).
 *
 * The shared chrome always drew the seek row, even though the Exo engine already
 * answered `chromeSeekBarScrubbable() == false` on a live channel: a control that
 * could not be dragged, whose "elapsed" was time since tune-in and whose total
 * was blank, taking the D-pad's first stop away from the buttons the viewer
 * wanted.
 *
 * The behaviour is pinned in [PlayerChromeContractTest] against the inflated
 * layout (Robolectric); this file pins the wiring that gets it there, and the two
 * things a layout cannot show: that VOD is untouched, and that the live program
 * block's own read-only bar is not the thing being removed.
 */
class PlayerLiveSeekRowContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    /** A file under `src/main/res`, found the same way as a source file. */
    private fun resFile(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/res/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("res file not found: $relative")
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    // ── F1: live hides the row, VOD shows it ──────────────────────────────

    @Test
    fun `the live controls pass hides the scrub row for live only`() {
        val activity = source(ACTIVITY)
        val controls = between(activity, "private fun updateControlsInfo(", "private fun applyPillBackground(")

        assertTrue(
            "the row goes exactly where live/VOD is already decided",
            controls.contains("chrome.setSeekAvailable(!isLiveChannel)")
        )
        assertTrue(
            "and that is the function that shows the live-only controls",
            controls.contains("btnChannelUp?.visibility = liveVisibility")
        )
    }

    @Test
    fun `hiding the row makes it GONE, and showing it brings it back`() {
        val chrome = source(CHROME)
        val setter = between(chrome, "fun setSeekAvailable(", "private fun applyFocusUpTarget(")

        assertTrue(
            "GONE, not transparent: a hidden view leaves no gap and takes no focus",
            setter.contains("row.visibility = View.GONE")
        )
        assertTrue(
            "and a VOD session gets the row back",
            setter.contains("row.visibility = View.VISIBLE")
        )
        assertTrue(
            "the state is remembered for the bar's own bookkeeping",
            setter.contains("seekAvailable = available")
        )
    }

    @Test
    fun `the bar is not revived when the overlay is shown`() {
        val chrome = source(CHROME)
        val show = between(chrome, "fun show()", "fun hide()")

        assertTrue(
            "show() fades the row in for a scrubbable session...",
            show.contains("if (seekAvailable) KbViewMotion.fadeIn(seekRow)")
        )
        assertTrue(
            "...and leaves it GONE for live - an unconditional fade-in is what " +
                "would put the dead bar back on screen",
            show.contains("else seekRow?.visibility = View.GONE")
        )
    }

    @Test
    fun `a hidden bar does not hold the overlay open or take focus`() {
        val chrome = source(CHROME)
        val controls = between(chrome, "private fun controls()", "fun setSeekAvailable(")

        assertTrue(
            "the bar is only one of the 'keeps the chrome up' controls when the " +
                "session actually has one",
            controls.contains("if (seekAvailable) seekBar else null")
        )
    }

    // ── F2: UP never aims at the hidden bar ──────────────────────────────

    @Test
    fun `every control's UP target is re-pointed away from the hidden row`() {
        val chrome = source(CHROME)
        val apply = between(chrome, "private fun applyFocusUpTarget(", "private fun keepVisible(")

        assertTrue(
            "the target is the host's to name",
            apply.contains("val target = host.chromeFocusUpTargetId()")
        )
        assertTrue(
            "the whole bar is covered - play/pause, next, every picker, info and " +
                "settings - not a hand-picked few",
            apply.contains("playPauseButton,") &&
                apply.contains("episodesButton,") &&
                apply.contains("settingsButton")
        )
        assertTrue(
            "applied on hide...",
            between(chrome, "fun setSeekAvailable(", "private fun applyFocusUpTarget(")
                .contains("applyFocusUpTarget()")
        )
        assertTrue(
            "...and again on every show, because which stop exists can change",
            between(chrome, "fun show()", "fun hide()").contains("applyFocusUpTarget()")
        )
    }

    @Test
    fun `the default host names the scrub bar and the live engine names a control`() {
        val chrome = source(CHROME)
        val activity = source(ACTIVITY)

        assertTrue(
            "a session with a timeline keeps the layout's own target",
            chrome.contains("fun chromeFocusUpTargetId(): Int = R.id.chrome_seekbar")
        )
        assertTrue(
            "live answers with a control in the row...",
            activity.contains("btnChannelUp?.visibility == View.VISIBLE -> R.id.btn_channel_up")
        )
        assertTrue(
            "...or play/pause when there is no channel button",
            activity.contains("else -> R.id.chrome_btn_play_pause")
        )

        val liveAnswer = between(
            activity,
            "override fun chromeFocusUpTargetId()",
            "override fun chromeFocusPrimary()"
        )
        assertTrue(
            "and the bar is named ONLY for the VOD arm",
            liveAnswer.contains("!isLiveChannel -> R.id.chrome_seekbar") &&
                liveAnswer.split("R.id.chrome_seekbar").size == 2
        )
    }

    // ── F3: the program block's own bar stays ────────────────────────────

    @Test
    fun `the live program block's read-only bar is untouched`() {
        val xml = resFile("layout/player_exo_live_program.xml")

        assertTrue(
            "it still declares its own progress bar - that is the only thing " +
                "showing where the viewer is in the current programme (F3)",
            xml.contains("android:id=\"@+id/live_program_progress\"")
        )

        val chrome = source(CHROME)
        val setter = between(chrome, "fun setSeekAvailable(", "private fun applyFocusUpTarget(")
        assertFalse(
            "and hiding the scrub row never reaches the programme indicator - " +
                "it only touches the seek row",
            setter.contains("liveProgramProgress") || setter.contains("live_program_progress")
        )
    }

    @Test
    fun `the MPV engine has no separate scrub row to fall behind`() {
        val mpv = source(MPV)
        assertFalse(
            "the backup engine refuses live TV outright and shares this row, so " +
                "there is no second copy to hide",
            mpv.contains("setSeekAvailable") || mpv.contains("chrome_seekbar_row")
        )
        assertEquals(
            "the shared chrome is still what it drives",
            true,
            mpv.contains("PlayerChrome(")
        )
    }

    private companion object {
        const val CHROME = "com/kennyb1201/kbstream/ui/player/PlayerChrome.kt"
        const val ACTIVITY = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
