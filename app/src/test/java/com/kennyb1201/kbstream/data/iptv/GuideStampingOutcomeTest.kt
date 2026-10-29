package com.kennyb1201.kbstream.data.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stamping follows the OUTCOME, on both import paths - the half of the blank-guide
 * recurrence fix that makes the recovery possible.
 *
 * The loop that produced the field report: neither path consulted what an import
 * answered. Both treated "did not throw" as "imported", so a `304`, a refused
 * empty feed and a zero-program promote each wrote the freshness marker - and a
 * fresh marker is what suppresses the entry-time and worker retries for up to
 * 12h. A blank guide therefore stayed blank (and each zero-yield refresh
 * extended the suppression) until someone pressed IMPORT EPG by hand.
 *
 * [GuideImportOutcome.landed] is the one rule; the wiring tests below pin that
 * the two call sites actually read it rather than counting completions.
 */
class GuideStampingOutcomeTest {

    // ── the rule ──────────────────────────────────────────────────────

    @Test
    fun `only an import that stored a readable guide has landed`() {
        assertTrue(
            "a promoted parse with programs is the ordinary landed import",
            GuideImportOutcome.Imported(programs = 128_400).landed
        )
        assertFalse(
            "a zero-program promote stored nothing that can be read - the blank case",
            GuideImportOutcome.Imported(programs = 0).landed
        )
        assertTrue(
            "a 304 over a guide that has programs in the window is honored, so the " +
                "guide on disk IS the server's answer - the same bar as honorNotModified",
            GuideImportOutcome.Unchanged(channels = 1_204, programsInWindow = 8_900).landed
        )
        assertFalse(
            "a 304 over an empty guide is not a guide: this is the two-second no-op the " +
                "field report kept hitting, and it must not stamp",
            GuideImportOutcome.Unchanged(channels = 1_204, programsInWindow = 0).landed
        )
        assertFalse(
            "a refused empty feed keeps the old guide but stores nothing new",
            GuideImportOutcome.EmptyFeedKeptLive.landed
        )
        assertFalse(
            "a failure is not an import",
            GuideImportOutcome.Failed("Guide download failed: HTTP 404").landed
        )
        assertFalse(
            "and a cancelled pass never promoted anything",
            GuideImportOutcome.Cancelled.landed
        )
    }

    @Test
    fun `the rule lives beside the outcome type both paths already share`() {
        val progress = flat(read(PROGRESS))
        assertTrue(
            "one predicate, next to the sealed interface, so a third caller cannot " +
                "invent its own idea of landed",
            progress.contains("internal val GuideImportOutcome.landed: Boolean") &&
                progress.indexOf("sealed interface GuideImportOutcome") <
                progress.indexOf("internal val GuideImportOutcome.landed")
        )
        assertTrue(
            "and it is derived from the outcome's own counts, not from a flag beside them",
            progress.contains("is GuideImportOutcome.Imported -> programs > 0") &&
                progress.contains("is GuideImportOutcome.Unchanged -> programsInWindow > 0")
        )
    }

    // ── the in-app loop ───────────────────────────────────────────────

    @Test
    fun `the in-app pass counts and stamps only what landed`() {
        val pass = flat(read(VIEW_MODEL))
            .substringAfter("private suspend fun importGuideInternal(")
            .substringBefore("private suspend fun guideDataEndsAt(")

        assertTrue(
            "the pass keeps the outcome it was already collecting instead of discarding it",
            pass.contains("val sourceOutcome = epgImportWatchdog.runWithStallRetry(url) { attempt ->") &&
                pass.contains("outcomes += sourceOutcome")
        )
        val gate = pass.indexOf("if (sourceOutcome.landed)")
        val counted = pass.indexOf("imported++")
        val stamped = pass.indexOf("GuideSourceStamps.recordSuccess(prefs, url)")
        assertTrue(
            "the landed gate must be before BOTH the count and the per-source stamp",
            gate >= 0 && counted > gate && stamped > counted
        )
        assertTrue(
            "a zero-landed pass leaves the global marker alone: it returns before the " +
                "marker is written",
            pass.contains("if (imported == 0) {") &&
                pass.indexOf("if (imported == 0) {") < pass.indexOf("markUpdated(KEY_EPG_UPDATED_AT)")
        )
        assertTrue(
            "and it still REPORTS the answer - an empty feed is a fact the viewer sees",
            pass.substringAfter("if (imported == 0) {")
                .substringBefore("if (lastError != null) {")
                .contains("outcome = mergeOutcomes(outcomes)")
        )
    }

    // ── the worker ────────────────────────────────────────────────────

    @Test
    fun `the worker stamps only a landed outcome and treats the rest as an empty run`() {
        val worker = flat(read(WORKER))

        assertTrue(
            "the outcome is no longer thrown away: the budget helper returns it",
            worker.contains("): GuideImportOutcome? = try {") &&
                worker.contains("withTimeout(WorkPolicies.GUIDE_SOURCE_BUDGET_MS) { " +
                    "repository.importGuide(url) }")
        )
        assertTrue(
            "a timeout is still \"did not import\" - null, not a landed outcome",
            worker.substringAfter("catch (timeout: TimeoutCancellationException) {")
                .take(400)
                .contains("null")
        )
        val gate = worker.indexOf("if (outcome != null && outcome.landed) {")
        val stamped = worker.indexOf("stampImportedSource(prefs, url, epgUrls)")
        assertTrue(
            "the stamp (and the empty-runs reset it carries) follows the landed gate",
            gate >= 0 && stamped > gate
        )
        assertTrue(
            "a zero-yield run now falls into the existing empty-run accounting: counted, " +
                "flagged at the bound, and retried rather than cancelling the schedule",
            worker.contains("val consecutive = prefs.getInt(KEY_EPG_EMPTY_RUNS, 0) + 1") &&
                worker.contains("if (imported == 0) {") &&
                worker.contains("Result.retry()")
        )
    }

    // ── the importer's guard ──────────────────────────────────────────

    @Test
    fun `the empty-feed refusal is zero-only and unconditional`() {
        val importer = flat(read(IMPORTER))

        assertTrue(
            "zero kept programs is a refusal whatever the live window holds - the " +
                "expired-window case is the one that used to be promoted",
            importer.contains("if (keptPrograms == 0) {") &&
                !importer.contains("keptPrograms == 0 && liveProgramsInWindow > 0")
        )
        assertTrue(
            "the refusal names both window facts, so an expired-window refusal reads " +
                "differently from a frozen-feed one in a capture",
            importer.contains("\"IMPORT EMPTY FEED refused source=") &&
                importer.contains("liveProgramsInWindow=\$liveProgramsInWindow") &&
                importer.contains("hadLiveGuide=\$hadLiveGuide")
        )
        assertTrue(
            "the refused stats still say a guide was kept, which is what the callers report",
            importer.contains("emptyFeedKeptLive = true")
        )
    }

    // ── source access ─────────────────────────────────────────────────

    private fun flat(source: String): String = source.replace(Regex("\\s+"), " ")

    private fun read(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun sourceRoot(): File {
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
        const val IPTV = "com/kennyb1201/kbstream/data/iptv/"
        const val PROGRESS = "${IPTV}GuideImportProgress.kt"
        const val IMPORTER = "${IPTV}XmltvImporter.kt"
        const val WORKER = "${IPTV}EpgRefreshWorker.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
    }
}
