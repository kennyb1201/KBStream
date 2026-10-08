package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back dismisses the end-of-episode "Up next" card; it does not exit the player.
 *
 * On both in-app engines the card's Back press used to fall through to the
 * exit path (Native's Back callback re-dispatched to `finish()`, mpv's callback
 * ended `else -> exitPlayer()`), so the one press a viewer makes to wave the
 * card away ended the session instead - at the end of the very episode they
 * were watching. The card is a question, not a destination.
 *
 * These assertions pin the dismissal in both engines, that the dismissal does
 * not exit, that the explicit EXIT button still does, and that the card's own
 * hint stops promising that Back leaves.
 */
class NextUpBackDismissContractTest {

    // ── native (Exo) ────────────────────────────────────────────────────

    @Test
    fun `native Back dismisses the card before it means leave`() {
        val n = normalized(NATIVE)
        assertTrue(
            "the dispatcher callback must handle the card first",
            n.contains(
                "::nextUpPanel.isInitialized && nextUpPanel.visibility == View.VISIBLE -> {\n" +
                    "dismissNextUpPanel()\n" +
                    "showControls()\n" +
                    "return\n" +
                    "}"
            )
        )
        // The card's branch sits ahead of the other panel branches.
        val card = n.indexOf("dismissNextUpPanel()")
        val guide = n.indexOf("isGuideShowing -> { dismissChannelGuide(); showControls(); return }")
        assertTrue("the card's branch must come before the guide's", card in 0 until guide)
    }

    @Test
    fun `native still exits when nothing is being dismissed`() {
        assertTrue(
            "an empty screen's Back must still leave the player",
            readSource(NATIVE).contains("isEnabled = false\n                onBackPressedDispatcher.onBackPressed()")
        )
    }

    @Test
    fun `a focused card button's Back is caught by the overlay listener too`() {
        val n = normalized(NATIVE)
        assertTrue(
            "the overlay's Back arm must dismiss the card rather than swallow it",
            n.contains(
                "if (::nextUpPanel.isInitialized && nextUpPanel.visibility == View.VISIBLE) {\n" +
                    "dismissNextUpPanel(); showControls(); true\n" +
                    "}"
            )
        )
    }

    @Test
    fun `the dismissal hides the card, disarms the handoff and never exits`() {
        val body = functionBody(NATIVE, "private fun dismissNextUpPanel() {")
        assertTrue(
            "the panel must come down",
            body.contains("nextUpPanel.visibility = View.GONE")
        )
        assertTrue(
            "the handoff must be disarmed so PLAY NEXT and the countdown cannot fire",
            body.contains("nextUpHandoffArmed = false")
        )
        assertTrue(
            "the countdown must be stopped",
            body.contains("nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)")
        )
        assertFalse(
            "dismissing must not end the session",
            body.contains("finish(")
        )
        assertFalse(body.contains("exitPlayer("))
    }

    @Test
    fun `the card's hint no longer says Back exits`() {
        val n = normalized(NATIVE)
        assertFalse(
            "Back now dismisses; the hint must not promise an exit",
            n.contains("or press BACK to exit")
        )
        assertTrue(
            "it says what Back does now",
            n.contains("or press BACK to dismiss")
        )
    }

    @Test
    fun `the explicit EXIT button is still an exit`() {
        // Dismissing the card deliberately does not remove the viewer's way
        // out: the card's own EXIT pill (and a second Back) still leave.
        assertTrue(
            readSource(NATIVE).contains("btnNextDismiss.setOnClickListener { finish() }")
        )
    }

    // ── mpv ─────────────────────────────────────────────────────────────

    @Test
    fun `mpv Back dismisses the card instead of exiting`() {
        val n = normalized(MPV)
        assertTrue(
            "mpv's callback must route the card to its dismissal, ahead of the exit",
            n.contains(
                "when {\n" +
                    "nextUpPanel?.visibility == View.VISIBLE -> dismissNextUpPanel()\n" +
                    "pickerOpen -> dismissPicker()"
            )
        )
        val card = n.indexOf("nextUpPanel?.visibility == View.VISIBLE -> dismissNextUpPanel()")
        val exit = n.indexOf("else -> exitPlayer()")
        assertTrue("the card must be handled before the exit", card in 0 until exit)
    }

    @Test
    fun `mpv's dismissal takes the card down and never exits`() {
        val body = functionBody(MPV, "private fun dismissNextUpPanel() {")
        assertTrue(
            "the card and its countdown come down through hideEndPanels",
            body.contains("hideEndPanels()")
        )
        assertFalse(
            "dismissing must not end the session",
            body.contains("exitPlayer()")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun normalized(path: String): String =
        readSource(path).lines().joinToString("\n") { it.trim() }

    private fun functionBody(path: String, signature: String): String {
        val src = readSource(path)
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
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

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
