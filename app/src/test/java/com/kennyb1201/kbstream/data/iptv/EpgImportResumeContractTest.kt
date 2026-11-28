package com.kennyb1201.kbstream.data.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the stopped-import retry and the resume are wired (2026-11-27).
 *
 * The field state: the phone's guide import stops mid-import
 * (`outcome=stopped mid-import aborts=2`) and the pass is never continued - the
 * next chance is the 6h tick, a playback catch-up, or a manual press, and each
 * of them re-downloaded and re-inserted the whole file, so a feed longer than
 * WorkManager's window could never finish.
 *
 * The decisions themselves are unit tested where they live
 * ([GuideImportCheckpointTest], [com.kennyb1201.kbstream.work.WorkPoliciesTest],
 * [EpgRefreshDiagnosticsTest]); this file pins that the code paths still ASK
 * them, because a `CoroutineWorker` and a streaming importer cannot be run
 * without a device.
 */
class EpgImportResumeContractTest {

    private val worker = source(IPTV + "EpgRefreshWorker.kt")
    private val scheduler = source(IPTV + "EpgRefreshScheduler.kt")
    private val importer = source(IPTV + "XmltvImporter.kt")
    private val repository = source(IPTV + "IptvRepository.kt")
    private val httpClient = source(IPTV + "IptvHttpClient.kt")
    private val checkpointStore = source(IPTV + "GuideImportCheckpointStore.kt")

    // ── Part 1: a stop is continued, not waited out ─────────────────────────

    @Test
    fun `a stopped pass queues its own one-time run`() {
        assertTrue(
            "the stop-retry has a unique work name of its own",
            scheduler.contains("const val STOP_RETRY_WORK_NAME = \"iptv_epg_refresh_stop_retry\"")
        )
        assertTrue(
            "and not the catch-up's, which would drop the retry whenever a catch-up " +
                "was already pending",
            scheduler.contains("const val CATCH_UP_WORK_NAME = \"iptv_epg_refresh_catch_up\"")
        )
        assertTrue(
            "REPLACE, not KEEP: the enqueue comes from inside the run being " +
                "stopped, and KEEP would drop its own continuation",
            scheduler.contains("STOP_RETRY_WORK_NAME,\n            ExistingWorkPolicy.REPLACE,")
        )
        assertTrue(
            "the run is flagged, so the worker knows to skip what already landed",
            scheduler.contains("const val INPUT_STOP_RETRY = \"stop_retry\"") &&
                scheduler.contains("workDataOf(INPUT_STOP_RETRY to true)")
        )
    }

    @Test
    fun `the stop path records the pass and asks for the continuation`() {
        // Comments stripped: the block's own KDoc names Result.retry() to say
        // why it cannot be used, which would read as the thing it forbids.
        val catchBlock = worker.substringAfter("catch (cancelled: CancellationException)")
            .substringBefore("/**\n     * Asks for the one-time run")
            .lines().joinToString("\n") { it.substringBefore("//") }
        assertTrue("the pass is still recorded as stopped", catchBlock.contains("recordStoppedPass()"))
        assertTrue(
            "and the continuation is asked for here - Result.retry() cannot work, " +
                "because WorkManager discards a stopped worker's result",
            catchBlock.contains("enqueueStopRetryIfBounded()")
        )
        assertFalse(
            "so the stop path must not pretend a return value is the retry",
            catchBlock.contains("Result.retry()")
        )
        assertTrue("the cancellation still propagates", catchBlock.contains("throw cancelled"))
    }

    @Test
    fun `the retry stops being queued at the manual-recovery bound`() {
        val bounded = worker.substringAfter("private fun enqueueStopRetryIfBounded()")
            .substringBefore("/** One guide-refresh pass")
        assertTrue(
            "the same abort streak that drives `failing` bounds the retries",
            bounded.contains("EpgRefreshDiagnostics.KEY_ABORT_STREAK") &&
                bounded.contains("if (streak >= MAX_EMPTY_RUNS)")
        )
        assertTrue(
            "and the last word is the manual one, exactly as the empty-run branch says",
            bounded.contains("manual IMPORT GUIDE is the recovery")
        )
        assertTrue(
            "below the bound a continuation is queued",
            bounded.contains("EpgRefreshScheduler.enqueueStopRetry(applicationContext)")
        )
    }

    @Test
    fun `a stop names the source it was cut in, with the cut detail`() {
        val stopped = worker.substringAfter("private fun recordStoppedPass()")
            .substringBefore("private fun iptvPrefs")
        assertTrue(
            "the source that was mid-import is named, not just the ones that finished",
            stopped.contains("inFlightIndex.takeIf { it > 0 }") &&
                stopped.contains("EpgRefreshDiagnostics.SourceOutcome.STOPPED")
        )
        assertTrue(
            "with the same where-it-stopped detail a timeout carries",
            stopped.contains("detail = guideCutDetail()")
        )
        assertTrue(
            "and the verdict says this pass was a stop-retry that may have resumed",
            stopped.contains("stopRetry = stopRetry") &&
                stopped.contains("resumed = resumedThisPass")
        )
    }

    @Test
    fun `a stop-retry re-attempts only the sources that did not land`() {
        val loop = worker.substringAfter("epgUrls.forEachIndexed { index, url ->")
            .substringBefore("// Bounded per source")
        assertTrue(
            "the skip is gated on the run being a stop-retry and on the source's own stamp",
            loop.contains("stopRetry && WorkPolicies.epgSourceAlreadyLanded(") &&
                loop.contains("stampMs = stampsAtStart[url] ?: 0L")
        )
        assertTrue(
            "the stamps are read once, before the loop's own stamping writes them",
            worker.contains("val stampsAtStart = GuideSourceStamps.read(prefs)")
        )
        assertTrue(
            "and a skipped source is recorded as fresh, never as landed or failed",
            loop.contains("EpgRefreshDiagnostics.SourceOutcome.SKIPPED")
        )
        assertTrue(
            "a pass whose whole list was skipped ends as a success rather than " +
                "asking WorkManager to try the same nothing again",
            worker.contains("results.all {") &&
                worker.contains("it.outcome == EpgRefreshDiagnostics.SourceOutcome.SKIPPED") &&
                worker.contains("\"GUIDE REFRESH STOP-RETRY: every source already fresh\"")
        )
    }

    // ── Part 2: the stopped attempt's staged rows survive it ────────────────

    @Test
    fun `only a fresh attempt sweeps the staging table`() {
        assertTrue(
            "a resumed attempt keeps the rows the stopped one staged",
            importer.contains(
                "val resumeRows = GuideImportResumeRows(resume)\n" +
                    "        if (!resumeRows.resumed) {\n" +
                    "            dao.clearGuideBySource(stagingUrl)\n" +
                    "        }"
            )
        )
        assertTrue(
            "and the rows it already holds are not inserted again",
            importer.contains("if (!resumeRows.channelAlreadyStaged()) {") &&
                importer.contains("if (!resumeRows.programAlreadyStaged()) {")
        )
        assertTrue(
            "the pre-swap check that only a still-wanted parse may promote is untouched",
            importer.contains("currentCoroutineContext().ensureActive()")
        )
    }

    @Test
    fun `the checkpoint is written after a flush, never before it`() {
        // Ordering is the safety argument: a checkpoint ahead of the staging
        // table would skip rows that were never stored.
        assertTrue(
            "the flushed count comes from what the insert actually stored",
            importer.contains("flushedChannels += flushChannels(channelBatch)") &&
                importer.contains("flushedPrograms += flushPrograms(programBatch)")
        )
        val channelFlush = importer.substringAfter("suspend fun flushChannelBatch()")
            .substringBefore("suspend fun flushProgramBatch()")
        assertTrue(
            "and the count is added before it is published, not after",
            channelFlush.indexOf("flushedChannels += flushChannels(channelBatch)") in
                0 until channelFlush.indexOf("recordCheckpoint()")
        )
        assertTrue(
            "the sink only ever sees what the insert returned",
            importer.contains(
                "fun recordCheckpoint() {\n" +
                    "            checkpoint?.staged(flushedChannels, flushedPrograms, counting.totalBytes)"
            ) &&
                importer.indexOf("dao.insertChannels(batch)") in
                0 until importer.indexOf("return count")
        )
        assertTrue(
            "the flush reports how many rows it stored",
            importer.contains("private suspend fun flushChannels(batch: MutableList<EpgChannelEntity>): Int") &&
                importer.contains("private suspend fun flushPrograms(batch: MutableList<EpgProgramEntity>): Int")
        )
    }

    @Test
    fun `a resumed attempt reuses the window its staged rows were filtered by`() {
        val helper = repository.substringAfter("private suspend fun importStreamedGuide(")
            .substringBefore("/** What each configured guide source actually HOLDS")
        assertTrue(
            "the checkpoint is validated against the RESPONSE's validators",
            helper.contains("resumeCheckpoint(") &&
                helper.contains("etag = responseValidators.etag") &&
                helper.contains("lastModified = responseValidators.lastModified")
        )
        assertTrue(
            "the window and age stay the stopped attempt's",
            helper.contains("resume?.windowStartMs ?: windowStartMs") &&
                helper.contains("resume?.windowEndMs ?: windowEndMs") &&
                helper.contains("createdAtMs = resume?.createdAtMs ?: startedAtMs")
        )
        assertTrue(
            "and the response's validators are what the block receives",
            httpClient.contains("block: suspend (InputStream, GuideCacheValidators) -> T") &&
                httpClient.contains("block(input, responseValidators)")
        )
    }

    @Test
    fun `a checkpoint only outlives a stopped attempt`() {
        assertTrue(
            "a completed attempt clears it: promoted rows were re-keyed, refused ones swept",
            repository.contains("GuideImportCheckpointStore.clear(appContext, normalizedUrl)")
        )
        assertTrue(
            "a satisfied 304 clears it too - there is nothing to resume against an " +
                "unchanged feed",
            repository.substringAfter("if (honorNotModified(channels, programsInWindow)) {")
                .substringBefore("} else {")
                .contains("GuideImportCheckpointStore.clear(appContext, normalizedUrl)")
        )
        assertTrue(
            "removing a source removes its checkpoint",
            repository.contains("GuideImportCheckpointStore.clear(appContext, key)")
        )
        assertTrue(
            "the store is the profile-scoped guide prefs, like the validators",
            checkpointStore.contains("ProfileStorage.prefsName(context, GuideFiles.PREFS)")
        )
    }

    @Test
    fun `the verdict says a resumed import continued rather than started over`() {
        assertTrue(
            "the repository reports resume-ness from the importer's own stats",
            repository.contains("GuideImportOutcome.Imported(stats.keptPrograms, resumed = stats.resumed)")
        )
        assertTrue(
            "and the worker carries it onto the pass's verdict",
            worker.contains("if (outcome is GuideImportOutcome.Imported && outcome.resumed) {") &&
                worker.contains("resumed = resumedThisPass")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(mainSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val IPTV = "com/kennyb1201/kbstream/data/iptv/"

        fun mainSourceRoot(): File {
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
    }
}
