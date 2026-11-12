package com.kennyb1201.kbstream.data.reporting

import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COPY DIAGNOSTICS must not lie about having copied (spec 2026-11-12).
 *
 * The field symptom was a TCL pasting an hour-old report while the row said
 * "copied" after every press. [Diagnostics.build] stamps `generated:` fresh per
 * call, so the paste was provably not that press's report: `copyToClipboard`
 * swallowed the failure and never reported it. The clipboard half needs a
 * device; the naming, the pruning, the wording and the shared press path do
 * not, and they are what this pins.
 */
class DiagnosticsCopyContractTest {

    private val dir: File = kotlin.io.path.createTempDirectory("diag-capture").toFile()

    private val diagnostics: String by lazy { source(DIAGNOSTICS) }
    private val capture: String by lazy { source(CAPTURE) }

    // ── F1: the copy reports its real outcome ─────────────────────────────

    @Test
    fun `the clipboard copy returns an outcome, and still logs the failure`() {
        val copy = diagnostics.substringAfter("fun copyToClipboard(")
            .substringBefore("fun saveReport(")
        assertTrue(
            "the result type carries the reason, not just a boolean",
            diagnostics.contains("sealed interface CopyResult") &&
                diagnostics.contains("data class Failed(val reason: String)")
        )
        assertTrue("success is named", copy.contains("CopyResult.Copied"))
        assertTrue("failure is named", copy.contains("CopyResult.Failed("))
        assertTrue(
            "and the existing warning stays, because logcat is how it gets investigated",
            copy.contains("Log.w(LOGCAT_TAG, \"clipboard copy failed: ")
        )
        assertFalse(
            "it must not return Unit any more - that is what made the lie possible",
            copy.contains("fun copyToClipboard(context: Context, report: String) {")
        )
    }

    @Test
    fun `the status never says copied unless the clipboard took it`() {
        val at = 1_791_900_420_000L
        val timeOfDay = DiagnosticsCapture.timeOfDay(at)
        val copied = DiagnosticsCapture.copiedStatus(at = at, build = "0.9.245")
        val failed = DiagnosticsCapture.failedStatus(
            reason = "ClipboardService disabled",
            at = at,
            build = "0.9.245",
            file = File(dir, "kbstream-diagnostics-20261112-152700.txt")
        )
        assertTrue(copied, copied.startsWith("copied "))
        assertTrue("the time of day is on the row: $copied", copied.contains(timeOfDay))
        assertTrue("and the build: $copied", copied.contains("build 0.9.245"))
        assertTrue(copied.contains("also logged as DIAGNOSTICS"))

        assertFalse("a failure must never claim a copy: $failed", failed.contains("copied"))
        assertTrue("the reason is named: $failed", failed.contains("ClipboardService disabled"))
        assertTrue(
            "the file is named, so the fresh report is reachable: $failed",
            failed.contains("kbstream-diagnostics-20261112-152700.txt")
        )
        assertTrue("logcat still happened: $failed", failed.contains("logged as DIAGNOSTICS"))
        assertTrue("and the same time of day: $failed", failed.contains(timeOfDay))
    }

    @Test
    fun `a failed file write is said out loud, not pointed at an empty name`() {
        val failed = DiagnosticsCapture.failedStatus("boom", 1_791_900_420_000L, "0.9.245", null)
        assertTrue(failed, failed.contains("file write also failed"))
        assertFalse(failed, failed.contains("saved to null"))
    }

    // ── F2: every press writes a file, and the folder stays bounded ───────

    @Test
    fun `a press writes a timestamped file and the stable latest copy`() {
        val at = 1_791_900_420_000L
        val file = DiagnosticsCapture.save(dir, "report body", at)
        assertTrue("the save returns the file to name", file != null && file.isFile)
        assertEquals("report body", file!!.readText())
        assertTrue(
            "the name is identifiable and timestamped",
            file.name.matches(Regex("kbstream-diagnostics-\\d{8}-\\d{6}\\.txt"))
        )
        val latest = File(dir, DiagnosticsCapture.LATEST_NAME)
        assertTrue("and the stable always-current copy exists", latest.isFile)
        assertEquals("report body", latest.readText())
    }

    @Test
    fun `old presses are pruned, and the latest copy is never pruned`() {
        val base = 1_791_900_000_000L
        repeat(9) { i -> DiagnosticsCapture.save(dir, "press $i", base + i * 60_000L) }
        val kept = dir.listFiles()!!.map { it.name }
        assertEquals(
            "five per-press files plus the stable copy, however many presses happened",
            DiagnosticsCapture.MAX_KEPT + 1,
            kept.size
        )
        assertTrue("the stable copy survives", kept.contains(DiagnosticsCapture.LATEST_NAME))
        val newest = DiagnosticsCapture.timestampedName(base + 8 * 60_000L)
        assertTrue("the newest press is kept: $newest", kept.contains(newest))
        val oldest = DiagnosticsCapture.timestampedName(base)
        assertFalse("the oldest press is gone: $oldest", kept.contains(oldest))
        assertEquals(
            "and the stable copy holds the NEWEST report, not whichever survived",
            "press 8",
            File(dir, DiagnosticsCapture.LATEST_NAME).readText()
        )
    }

    @Test
    fun `the time of day and the file stamp come from one instant, in the device zone`() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val at = 1_791_900_420_000L
        assertEquals(
            "the stamp and the status time are the same moment",
            DiagnosticsCapture.timeOfDay(at, zone).replace(":", ""),
            DiagnosticsCapture.timestampedName(at, zone).substringAfter("diagnostics-")
                .substringBefore(".").substringAfter("-").take(4)
        )
        assertEquals(
            "and both are in the DEVICE's zone, not UTC",
            "160700",
            DiagnosticsCapture.timestampedName(at, zone).substringAfter("diagnostics-")
                .substringBefore(".").substringAfter("-")
        )
    }

    // ── F3: the row answers "is this fresh?" ─────────────────────────────

    @Test
    fun `the report carries the instant its generated line was formatted from`() {
        assertTrue(
            "the epoch is captured once and both the line and the status read it",
            diagnostics.contains("val generatedAt = System.currentTimeMillis()") &&
                diagnostics.contains(
                    "report.appendLine(\"generated: " +
                        "\${DateFormats.time(generatedAt, DateFormats.DATE_TIME_SECONDS)}\")"
                )
        )
        assertTrue(
            "and it travels back with the text",
            diagnostics.contains("data class Report(val text: String, val generatedAt: Long)") &&
                diagnostics.contains("return Report(text, generatedAt)")
        )
    }

    @Test
    fun `both Settings pages take the one press path`() {
        val tv = source(SETTINGS_TV)
        val mobile = source(SETTINGS_MOBILE)
        listOf(tv to "tv", mobile to "mobile").forEach { (src, name) ->
            assertTrue(
                "the $name page asks Diagnostics.press for its status",
                src.contains(".press(context)")
            )
            assertFalse(
                "and no longer synthesises a copied status of its own",
                src.contains("also logged as DIAGNOSTICS\\\"")
            )
        }
        val press = diagnostics.substringAfter("suspend fun press(")
            .substringBefore("private fun deviceLine(")
        val saved = press.indexOf("saveReport(context, report)")
        val copied = press.indexOf("copyToClipboard(context, report.text)")
        assertTrue("the file is written on EVERY press", saved > 0)
        assertTrue(
            "the file is written before the clipboard, so a clipboard failure " +
                "still names a fresh file",
            copied > saved
        )
        assertTrue(
            "the status comes from the outcome, never from having survived to the next line",
            press.contains("when (val outcome = copyToClipboard(context, report.text))")
        )
        assertTrue(
            "logcat stays untouched: it is written inside build(), on every press",
            diagnostics.contains("text.trimEnd().chunked(LOGCAT_CHUNK).forEach { Log.i(LOGCAT_TAG, it) }")
        )
    }

    @Test
    fun `the static description says the file exists before it is ever needed`() {
        listOf(source(SETTINGS_TV), source(SETTINGS_MOBILE)).forEach { src ->
            val description = src.substringAfter("?\n").substringBefore("COPY DIAGNOSTICS")
            assertTrue(
                "the row says copied, logcat AND a file",
                src.contains("copied to the clipboard") &&
                    src.contains("tag DIAGNOSTICS)") &&
                    src.contains("saved to a file in") &&
                    src.contains("diagnostics folder")
            )
            assertFalse("nothing about the old two-channel wording is left", description.isBlank())
        }
    }

    private fun source(relative: String): String {
        var root: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (root != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(root, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            root = root.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val CAPTURE = "com/kennyb1201/kbstream/data/reporting/DiagnosticsCapture.kt"
        const val SETTINGS_TV = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val SETTINGS_MOBILE = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
    }
}
