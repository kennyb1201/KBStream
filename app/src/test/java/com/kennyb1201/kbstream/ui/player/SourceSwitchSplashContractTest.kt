package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An automatic source switch must not cover a live picture with the splash.
 *
 * Both players reset their first-play chrome on every source switch:
 * `NativePlayerActivity.switchToSource` cleared the first-play latch and raised
 * its full splash, and `MpvPlayerActivity.switchToSource` raised its full-screen
 * `mpv_loading` splash (backdrop + clear logo on black). Both have callers that
 * are not fresh loads: the main player's error ladder and rebuffer downshift via
 * `tryNextSource`, and mpv's downshift in `onMpvBufferingChanged`. The downshift
 * is the reported one - three stalls of two seconds inside five minutes, then
 * the next source - so a stall raised the switch and the splash covered the
 * video the viewer was watching.
 *
 * The fix is a flag on each signature: a MANUAL switch is still a fresh load
 * (splash), an AUTO switch keeps the small spinner and lets the picture stay up.
 * This is wiring inside Android activities, so a unit test cannot drive it
 * without a TV in the room. What it can pin is the flag, the guard, and that the
 * automatic ladder is the only caller that sets it.
 */
class SourceSwitchSplashContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
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

    /**
     * The text of the function starting at [signature] up to the next member.
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    // --- Native (ExoPlayer) ------------------------------------------------

    private val nativeSwitch: String by lazy {
        functionBody(
            NATIVE,
            "fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {"
        )
    }

    private val nativeLadder: String by lazy {
        functionBody(
            NATIVE,
            "private fun tryNextSource(delayMs: Long = 0L, statusText: String? = null): Boolean {"
        )
    }

    @Test
    fun `the native switch carries an auto-recovery flag defaulted off`() {
        assertTrue(
            "manually picking a source must still read as a fresh load",
            readSource(NATIVE).contains(
                "fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {"
            )
        )
    }

    @Test
    fun `an auto native switch keeps the latch and the picture once something played`() {
        assertTrue(
            "the splash and latch reset must be gated on the flag",
            nativeSwitch.contains("if (!isAutoRecovery || !hasPlayedOnce) {")
        )
        val guard = nativeSwitch.indexOf("if (!isAutoRecovery || !hasPlayedOnce) {")
        val reset = nativeSwitch.indexOf("hasPlayedOnce = false")
        assertTrue("the latch reset must sit inside the guard", guard in 0 until reset)
    }

    @Test
    fun `the native ladder is the automatic caller that sets the flag`() {
        assertTrue(
            "the error ladder and the downshift must mark their switches automatic",
            nativeLadder.contains("switchToSource(nextStream, isAutoRecovery = true)")
        )
        assertEquals(
            "both ladder branches must pass the flag",
            2,
            Regex("switchToSource\\(nextStream, isAutoRecovery = true\\)")
                .findAll(nativeLadder).count()
        )
        val manual = readSource(NATIVE)
            .substringAfter("onClick = { switchToSource(stream); dismissPicker() }")
            .substringBefore("\n")
        assertFalse(
            "a manual pick must not be marked automatic",
            manual.contains("isAutoRecovery")
        )
    }

    @Test
    fun `the reconnecting banner still precedes the native switch`() {
        val banner = nativeLadder.indexOf("reconnectingContainer.visibility = View.VISIBLE")
        val switch = nativeLadder.indexOf("switchToSource(nextStream, isAutoRecovery = true)")
        assertTrue("the ladder must raise the reconnecting banner", banner >= 0)
        assertTrue("the banner must be raised before the switch", banner in 0 until switch)
    }

    // --- MPV ---------------------------------------------------------------

    private val mpvSwitch: String by lazy {
        functionBody(
            MPV,
            "private fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {"
        )
    }

    private val mpvDownshift: String by lazy {
        functionBody(MPV, "private fun onMpvBufferingChanged(buffering: Boolean) {")
    }

    @Test
    fun `the mpv switch carries the same auto-recovery flag`() {
        assertTrue(
            "the mpv picker must still read as a fresh load",
            readSource(MPV).contains(
                "private fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {"
            )
        )
    }

    @Test
    fun `an auto mpv switch keeps the spinner instead of the full splash`() {
        assertTrue(
            "the splash must be gated on the flag",
            mpvSwitch.contains("if (isAutoRecovery) {")
        )
        val auto = mpvSwitch.indexOf("if (isAutoRecovery) {")
        val manual = mpvSwitch.indexOf("showLoading(\"Switching source…\")")
        assertTrue("the manual branch must still raise the splash", manual in (auto + 1) until mpvSwitch.length)
        // The auto branch hides the splash and keeps the small buffering
        // spinner; the manual branch is the one that raises the splash.
        val autoBlock = mpvSwitch.substring(auto, manual)
        assertTrue(
            "an auto switch must not raise the full-screen load splash",
            autoBlock.contains("loadingContainer?.visibility = View.GONE")
        )
        assertTrue(
            "an auto switch must keep the buffering spinner up",
            autoBlock.contains("bufferingView?.visibility = View.VISIBLE")
        )
    }

    @Test
    fun `the mpv downshift is the automatic caller that sets the flag`() {
        assertTrue(
            "the rebuffer downshift must mark its switch automatic",
            mpvDownshift.contains("switchToSource(next, isAutoRecovery = true)")
        )
        assertFalse(
            "the error card's next-source button stays a manual switch",
            readSource(MPV)
                .substringAfter("private fun tryNextSource() {")
                .substringBefore("\n    }")
                .contains("isAutoRecovery")
        )
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
