package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame-rate diagnostics row says what is actually true about this device,
 * including when the feature is not switched on.
 *
 * Reported: with "Match Content Frame Rate" OFF, the row read "nothing has
 * played on this device yet" forever - the wording claimed no title had ever
 * been played on the TV, which is false and alarming, and the panel line above
 * it still listed the display's modes, so the two lines contradicted each other.
 *
 * The cause is structural rather than a wording slip: the request half of the
 * report is written by [FrameRateMatcher], and the matcher is only ever built
 * when the preference is on (NativePlayerActivity / MpvPlayerActivity gate its
 * construction on `AppPreferences.getMatchFrameRate`). With the toggle off the
 * app never asks the panel for a rate, so there is genuinely no request to
 * show - but "nothing has played" is the wrong reason for an empty line.
 *
 * The panel half is read live from `displayReport(context)`, so it stays
 * present either way; only the request line's wording has to depend on the
 * toggle. These are silent failures on a TV - a diagnostic line that quietly
 * changes meaning is worse than none - so the wording is pinned by reading the
 * source.
 */
class FrameRateDiagnosticsRowContractTest {

    private fun source(path: String): String {
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

    private fun screen(): String = source(SCREEN)

    /** The body of the diagnostics row composite, up to the line helper below it. */
    private fun row(): String {
        val src = screen()
        val start = src.indexOf("private fun FrameRateDiagnosticRow(")
        assertTrue("the diagnostics row is gone from SettingsScreen.kt", start >= 0)
        val end = src.indexOf("private fun FrameRateDiagnosticLine(", start)
        assertTrue("the diagnostics line helper is gone", end > start)
        return src.substring(start, end)
    }

    /** Whitespace-insensitive haystack, so indentation is not the test. */
    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun `the row is told whether matching is on`() {
        // Without the flag the row has no way to tell "off" from "on but not
        // played yet", which is the whole bug.
        assertTrue(
            "the row must take the toggle's value",
            row().contains("private fun FrameRateDiagnosticRow(enabled: Boolean)")
        )
        assertTrue(
            "the call site must pass the live toggle, not a constant",
            screen().contains("FrameRateDiagnosticRow(enabled = matchFrameRate)")
        )
        // ...and the toggle it passes is the one the switch writes.
        assertTrue(
            "matchFrameRate is not the state the toggle row flips",
            screen().contains("AppPreferences.setMatchFrameRate(context, it)")
        )
    }

    @Test
    fun `an empty request line never claims nothing has ever been played`() {
        // The literal is the regression: it is false on any TV that has played
        // something with matching off, which is exactly the reported case.
        assertFalse(
            "the row must not claim no title has ever been played on this device",
            screen().contains("nothing has played on this device yet")
        )
        // And the fallback is genuinely conditional on the flag, rather than a
        // single replacement string that would read the same either way.
        val fallback = squash(row())
        assertTrue(
            "the empty request line must be chosen by whether matching is on",
            fallback.contains("value = report.request ?: if (enabled) {")
        )
    }

    @Test
    fun `with matching off the row says the panel is never asked`() {
        val body = squash(row())
        assertTrue(
            "the off wording must say the app does not ask the panel for a rate, " +
                "which is why there is nothing to report",
            body.contains("\"matching is off, so the panel is never asked for a rate\"")
        )
    }

    @Test
    fun `with matching on the row is honest about the report's lifetime`() {
        // FrameRateDiagnostics holds the report in memory for the life of the
        // process, so with matching ON and a fresh app the line is "nothing has
        // played since startup" - not "nothing has played", which would be a
        // different and equally false claim after a restart.
        val body = squash(row())
        assertTrue(
            "the on wording must scope its claim to this app run",
            body.contains("\"nothing has played since KBStream started\"")
        )
    }

    @Test
    fun `the panel line stays live with the toggle either way`() {
        // The panel half is read from the display, so it answers "does this TV
        // offer 24 Hz" before anything plays and regardless of the toggle. Only
        // the request half is gated by whether the matcher ever ran.
        val body = squash(row())
        assertTrue(
            "the panel line must be drawn from the live display read",
            body.contains("value = report.panel ?: livePanel ?: \"this screen has no display to ask\"")
        )
        assertTrue(
            "the live panel is still read from the display helper",
            body.contains("val livePanel = remember(context) { displayReport(context) }")
        )
    }

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
    }
}
