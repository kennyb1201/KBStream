package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An automatic source switch must not cover a live picture with the splash.
 *
 * `switchToSource` reset the first-play latch and raised the full splash
 * unconditionally, because its callers were "a fresh load". Two of them are
 * not: the error ladder and `maybeDownshiftOnRebuffer` both arrive through
 * `tryNextSource`, and both run while the viewer is already watching. The
 * downshift is the reported one - three stalls of two seconds inside five
 * minutes, then the next source - so a stall raised the reconnecting banner,
 * `tryNextSource` cleared it with `hideBufferingSpinner` and raised the splash,
 * and the splash's own contract ("never stack the spinner or the reconnecting
 * banner on top of it") kept both off the screen while the video was covered.
 *
 * The fix is a flag: a MANUAL switch is still a fresh load (splash), an AUTO
 * switch only shows the splash when nothing has played yet (a source that would
 * not open at launch). This is wiring inside an Android activity, so a unit
 * test cannot drive it without a TV in the room. What it can pin is the flag on
 * the signature, the guard around the latch reset and the splash, and that the
 * ladder - the only automatic caller - passes it.
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
    private fun functionBody(signature: String): String {
        val src = readSource(NATIVE)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private val switchBody: String by lazy {
        functionBody("fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {")
    }

    @Test
    fun `the switch carries an auto-recovery flag defaulted off`() {
        assertTrue(
            "manually picking a source must still read as a fresh load",
            readSource(NATIVE).contains(
                "fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {"
            )
        )
    }

    @Test
    fun `an auto switch keeps the latch and the picture once something played`() {
        assertTrue(
            "the splash and latch reset must be gated on the flag",
            switchBody.contains("if (!isAutoRecovery || !hasPlayedOnce) {")
        )
        // The old unconditional pair must be gone: it lives inside the guard
        // now, and an unguarded `hasPlayedOnce = false` would re-raise the
        // splash on every automatic switch.
        val guard = switchBody.indexOf("if (!isAutoRecovery || !hasPlayedOnce) {")
        val reset = switchBody.indexOf("hasPlayedOnce = false")
        assertTrue("the latch reset must sit inside the guard", guard in 0 until reset)
    }

    @Test
    fun `the automatic ladder is the only caller that sets the flag`() {
        val ladder = functionBody("private fun tryNextSource(delayMs: Long = 0L, statusText: String? = null): Boolean {")
        assertTrue(
            "the error ladder and the downshift must mark their switches automatic",
            ladder.contains("switchToSource(nextStream, isAutoRecovery = true)")
        )
        // Both branches - the delayed one and the immediate one - must carry it.
        assertEquals(
            "both ladder branches must pass the flag",
            2,
            Regex("switchToSource\\(nextStream, isAutoRecovery = true\\)").findAll(ladder).count()
        )
        // Manual callers (picker, zap) keep the default.
        val manual = readSource(NATIVE)
            .substringAfter("onClick = { switchToSource(stream); dismissPicker() }")
            .substringBefore("\n")
        assertFalse(
            "a manual pick must not be marked automatic",
            manual.contains("isAutoRecovery")
        )
    }

    @Test
    fun `the reconnecting banner still precedes the switch`() {
        val ladder = functionBody("private fun tryNextSource(delayMs: Long = 0L, statusText: String? = null): Boolean {")
        val banner = ladder.indexOf("reconnectingContainer.visibility = View.VISIBLE")
        val switch = ladder.indexOf("switchToSource(nextStream, isAutoRecovery = true)")
        assertTrue("the ladder must raise the reconnecting banner", banner >= 0)
        assertTrue("the banner must be raised before the switch", banner in 0 until switch)
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
