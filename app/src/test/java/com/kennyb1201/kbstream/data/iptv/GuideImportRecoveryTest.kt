package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.iptv.db.EpgChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgSearchIndexCallback
import com.kennyb1201.kbstream.data.iptv.db.IptvDao
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guide import's RECOVERY path: a `304` may only be honored for a guide the
 * screens can actually read, a manual press may not take the conditional path at
 * all, and a finished import has to say what happened.
 *
 * The field report this comes from: every channel read "No program data", the
 * provider's file had not changed, and every refresh - the 6-hourly one, the
 * entry one and the IMPORT EPG button - was satisfied by `304 Not Modified` in
 * about two seconds. The satisfaction test was `hasChannelsForSource`: the
 * channels were still in the database (programs had been pruned out of the
 * stored window), so the conditional fetch was honored forever, the recovery
 * branch that drops the validators never fired, and nothing surfaced a cause.
 *
 * The count queries run against a real (in-memory) database under Robolectric,
 * because the failure mode is SQL: "programs inside the window the guide reads"
 * is a different question from "programs", and only a database answers it.
 */
@RunWith(AndroidJUnit4::class)
class GuideImportRecoveryTest {

    private val guideUrl = "https://provider.example/xmltv.php?username=alice&password=secret"
    private val otherUrl = "https://other.example/epg.xml"

    private lateinit var db: IptvDatabase
    private lateinit var dao: IptvDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java)
            .addCallback(EpgSearchIndexCallback)
            .allowMainThreadQueries()
            .build()
        dao = db.iptvDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── the decision the 304 branch makes ─────────────────────────────

    @Test
    fun `a 304 is honored only when the guide has channels AND programs in the window`() {
        assertTrue(
            "a healthy guide: the server's unchanged file is what this device holds",
            honorNotModified(channels = 1_204, programsInWindow = 8_900)
        )
        assertFalse(
            "channels with no schedule is the field bug: honoring this is the " +
                "two-second no-op that kept the guide empty",
            honorNotModified(channels = 1_204, programsInWindow = 0)
        )
        assertFalse(
            "no channels at all cannot satisfy a 304 either",
            honorNotModified(channels = 0, programsInWindow = 0)
        )
        assertFalse(
            "and a stray program with no channels is not a guide",
            honorNotModified(channels = 0, programsInWindow = 5)
        )
    }

    @Test
    fun `programs are counted inside the read window, per source`() = runBlocking {
        val sourceId = dao.ensureSourceId(guideUrl)
        val otherId = dao.ensureSourceId(otherUrl)
        dao.insertChannels(channels(sourceId, 2))
        dao.insertPrograms(
            listOf(
                // Inside the window.
                program(sourceId, start = 1_000L, end = 2_000L),
                // Fully before it, and fully after it: the rows a pruned/aged
                // guide leaves behind, which must NOT satisfy a 304.
                program(sourceId, start = 0L, end = 500L),
                program(sourceId, start = 9_000L, end = 9_500L)
            )
        )
        // Another source's schedule never counts for this one.
        dao.insertPrograms(listOf(program(otherId, start = 1_000L, end = 2_000L)))

        assertEquals(
            "two channels are still stored - which is exactly what the old test asked",
            2,
            dao.countChannelsForSource(guideUrl)
        )
        assertEquals(
            "only the row that overlaps the window counts",
            1,
            dao.countProgramsForSourceInWindow(guideUrl, windowStartMs = 900L, windowEndMs = 3_000L)
        )
        assertEquals(
            "and an empty window means nothing satisfies a 304",
            0,
            dao.countProgramsForSourceInWindow(guideUrl, windowStartMs = 10_000L, windowEndMs = 20_000L)
        )
        assertEquals(
            "a source that has never landed reads as nothing at all",
            0,
            dao.countChannelsForSource("https://never-imported.example/epg.xml")
        )
    }

    // ── the wiring: recovery, forced fetch, and the outcome ────────────

    @Test
    fun `the 304 branch drops the validators and refetches in full when the guide is unusable`() {
        val repository = flat(read(REPOSITORY))
        val branch = repository.substringAfter("IptvHttpClient.XmltvFetch.NotModified -> {")
            .substringBefore("waiter.complete(result)")

        assertTrue(
            "the decision must be channels AND programs in the read window",
            branch.contains("countChannelsForSource(normalizedUrl)") &&
                branch.contains(
                    "countProgramsForSourceInWindow( normalizedUrl, " +
                        "nowMs - EpgWindow.READ_PAST_MS, " +
                        "nowMs + EpgWindow.READ_FUTURE_MS )"
                ) &&
                branch.contains("honorNotModified(channels, programsInWindow)")
        )
        assertTrue(
            "a satisfied 304 must be reported as unchanged, with the counts",
            branch.contains("GuideImportOutcome.Unchanged(channels, programsInWindow)")
        )
        assertTrue(
            "an unusable 304 must drop the stored validators",
            branch.contains("GuideCacheHeaders.clear(appContext, normalizedUrl)")
        )
        assertTrue(
            "and fetch the whole file once, unconditionally",
            branch.replace(Regex("\\s+"), " ")
                .contains("IptvHttpClient.streamXmltvWithRetry( client, normalizedUrl )")
        )
        assertTrue(
            "the refetch's result is what the viewer is told about",
            branch.contains("GuideImportOutcome.Imported(fresh.value.keptPrograms)")
        )
    }

    @Test
    fun `a forced import sends no validators, and an automatic one still does`() {
        val repository = flat(read(REPOSITORY))

        assertTrue(
            "a manual press must not be answerable with a 304: it sends no validators",
            repository.contains(
                "val validators = if (forceFresh) null else " +
                    "GuideCacheHeaders.get(appContext, normalizedUrl)"
            )
        )
        // The request-shaping seam itself: no validators is no conditional
        // request headers at all.
        assertTrue(IptvHttpClient.cacheRequestHeaders(null).isEmpty())
        assertEquals(
            listOf("If-None-Match" to "\"abc\"", "If-Modified-Since" to "Wed, 21 Oct 2026 07:28:00 GMT"),
            IptvHttpClient.cacheRequestHeaders(
                GuideCacheValidators(
                    etag = "\"abc\"",
                    lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"
                )
            )
        )
        assertTrue(
            "and the response's validators still replace the stored ones",
            repository.contains("GuideCacheHeaders.put(")
        )
    }

    @Test
    fun `the manual button forces the fetch and the automatic paths do not`() {
        val vm = flat(read(VIEW_MODEL))

        val button = vm.substringAfter("fun importGuide() {").substringBefore("private fun launchGuideImport(")
        assertTrue(
            "IMPORT EPG is the recovery act - it must bypass the conditional fetch",
            button.contains("launchGuideImport(forceFresh = true)")
        )
        assertTrue(
            "the launcher defaults to the conditional fetch every automatic " +
                "caller keeps",
            vm.contains("private fun launchGuideImport(forceFresh: Boolean = false)")
        )
        assertTrue(
            "and the flag reaches the repository from the import pass",
            vm.contains("forceFresh = forceFresh")
        )
        assertTrue(
            "the auto-refresh path must keep calling it without forcing",
            vm.contains("if (canImportGuide) launchGuideImport()")
        )
    }

    @Test
    fun `a finished import reports its outcome instead of returning to zero`() {
        val vm = flat(read(VIEW_MODEL))
        val progress = flat(read(PROGRESS))

        assertTrue(
            "the pass must hand its outcome to the status line",
            vm.contains("GuideImportProgress.finish(outcome)")
        )
        assertTrue(
            "a failure says what failed, with the HTTP code when there is one",
            vm.contains("httpCode != null -> \"Guide download failed: \$httpCode\"")
        )
        assertTrue(
            "the outcome line is rendered BEFORE the live counters",
            progress.indexOf("state.outcome?.let { return outcomeLabel(it) }") in
                1 until progress.indexOf("if (state.phase == GuideImportPhase.SAVING)")
        )
        // No pass at all keeps the old behaviour: the line clears. A RUNNING
        // pass still renders its live counters, not an outcome.
        assertEquals("", GuideImportProgress.label(null, 0L))
        assertTrue(
            GuideImportProgress.label(GuideImportState(), 0L).contains("0 programs")
        )
    }

    // ── the wording, one case per class ───────────────────────────────

    @Test
    fun `each outcome class has one sentence`() {
        assertEquals(
            "Imported 128,400 programs",
            outcomeLabel(GuideImportOutcome.Imported(128_400))
        )
        assertEquals(
            "a satisfied 304 names the counts that satisfied it",
            "Guide unchanged on server (1,204 channels · 8,900 programs)",
            outcomeLabel(GuideImportOutcome.Unchanged(channels = 1_204, programsInWindow = 8_900))
        )
        assertEquals(
            "Guide download failed: HTTP 404",
            outcomeLabel(GuideImportOutcome.Failed("Guide download failed: HTTP 404"))
        )
        assertEquals(
            "the status line is what the two guide screens already render",
            "Imported 7 programs",
            GuideImportProgress.label(GuideImportState(outcome = GuideImportOutcome.Imported(7)), 0L)
        )
    }

    // ── diagnostics ──────────────────────────────────────────────────

    @Test
    fun `diagnostics prints channels, programs in window and validators per source`() {
        val diagnostics = flat(read(DIAGNOSTICS))

        assertTrue(
            "the report must carry the guide rows",
            diagnostics.contains("guideLine(app)?.let { report.appendLine(it) }")
        )
        val line = diagnostics.substringAfter("private suspend fun guideLine(")
            .substringBefore("/**")
        assertTrue(
            "channels, the programs inside the read window, and whether a " +
                "conditional fetch would be sent: the three facts that separate " +
                "the field states",
            line.contains("channels=\${row.channels}") &&
                line.contains("programsInWindow=\${row.programsInWindow}") &&
                line.contains("validators=\${if (row.hasValidators) \"yes\" else \"no\"}")
        )
        assertTrue(
            "read from the configured sources, so one that never landed still shows",
            flat(read(REPOSITORY)).contains("GuideFiles.guideUrls(appContext).map { url ->")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun outcomeLabel(outcome: GuideImportOutcome): String =
        GuideImportProgress.label(GuideImportState(outcome = outcome), 0L)

    private fun channels(sourceId: Long, count: Int): List<EpgChannelEntity> =
        (0 until count).map { index ->
            EpgChannelEntity(
                id = "ch$index",
                sourceId = sourceId,
                primaryDisplayName = "Channel $index",
                allDisplayNames = "Channel $index",
                iconUrl = null
            )
        }

    private fun program(sourceId: Long, start: Long, end: Long) = EpgProgramEntity(
        sourceId = sourceId,
        channelId = "ch0",
        title = "A program",
        description = null,
        category = null,
        startUtcMillis = start,
        endUtcMillis = end
    )

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
        const val REPOSITORY = "${IPTV}IptvRepository.kt"
        const val PROGRESS = "${IPTV}GuideImportProgress.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
    }
}
