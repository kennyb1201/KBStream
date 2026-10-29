package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.iptv.db.EpgChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgSearchIndexCallback
import com.kennyb1201.kbstream.data.iptv.db.IptvDao
import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

    // ── the empty-feed guard, driven through the real importer ─────────

    @Test
    fun `an empty import never replaces a live guide that has programs`() = runBlocking {
        val importer = XmltvImporter(daoProvider = { dao })
        val now = System.currentTimeMillis()

        val first = importer.import(
            guideUrl,
            guideXml(withProgramme = true, nowMs = now).byteInputStream()
        )
        assertEquals("the first import is the guide everything else is judged against", 1, first.keptPrograms)
        assertEquals(2, dao.countChannelsForSource(guideUrl))
        val channelsBefore = dao.getChannelsBySource(guideUrl)
        val programsBefore = programRows(guideUrl)
        assertTrue(programsBefore.isNotEmpty())

        // The provider's guide is now frozen: its file still lists the channels
        // and no programme at all.
        val second = importer.import(
            guideUrl,
            guideXml(withProgramme = false, nowMs = now).byteInputStream()
        )

        assertTrue(
            "the caller must be told the existing guide was kept",
            second.emptyFeedKeptLive
        )
        assertEquals(0, second.keptPrograms)
        assertEquals("the empty file did parse its channels", 2, second.parsedChannels)
        assertEquals(
            "the live channels are untouched",
            channelsBefore,
            dao.getChannelsBySource(guideUrl)
        )
        assertEquals(
            "and so is every live program row",
            programsBefore,
            programRows(guideUrl)
        )
        assertEquals(
            "the discarded parse leaves nothing staged",
            0,
            dao.countChannelsForSource("$guideUrl@importing")
        )
        assertEquals(
            0,
            dao.countProgramsForSourceInWindow("$guideUrl@importing", 0L, Long.MAX_VALUE)
        )
    }

    @Test
    fun `a first import with no programs is refused too - nothing to promote, nothing to protect`() =
        runBlocking {
            // Behaviour change, pinned on purpose (kbstream-guide-blank-recurrence
            // fix, part A): the refusal used to also require a live guide with
            // programs in the window, so a first empty import - and every empty
            // import after the stored window had expired - was PROMOTED. That
            // promote deleted the live rows, re-keyed zero, and was reported to
            // the caller as a successful import, which stamped the guide fresh
            // and silenced every retry. The guard is zero-only now.
            val importer = XmltvImporter(daoProvider = { dao })
            val now = System.currentTimeMillis()
            val xml = guideXml(withProgramme = false, nowMs = now)

            val first = importer.import(guideUrl, xml.byteInputStream())

            assertTrue(
                "an empty feed is refused even with nothing stored yet - there is no " +
                    "guide to promote",
                first.emptyFeedKeptLive
            )
            assertEquals(0, first.keptPrograms)
            assertEquals(
                "and no live rows are created by a refused import, not even channels",
                0,
                dao.countChannelsForSource(guideUrl)
            )
            assertEquals(
                "the discarded parse leaves nothing staged",
                0,
                dao.countChannelsForSource("$guideUrl@importing")
            )

            // An already-empty guide is the same answer: the import is refused
            // rather than promoting an empty body over an empty guide.
            val again = importer.import(guideUrl, xml.byteInputStream())
            assertTrue(again.emptyFeedKeptLive)
            assertEquals(0, dao.countChannelsForSource(guideUrl))
        }

    @Test
    fun `an empty import over an EXPIRED window is refused, not promoted`() = runBlocking {
        // The blank-guide recurrence itself: the live guide still has channels
        // and programs, but none of them fall inside the window this import
        // covers (the stored schedule has run out - the state the viewer sees as
        // a blank grid). `liveProgramsInWindow` is therefore 0, which used to
        // DISARM the guard: the empty body was promoted, the live rows were
        // deleted and zero re-keyed, and the caller saw a success. The window
        // can be empty and the guard must still hold.
        val importer = XmltvImporter(daoProvider = { dao })
        val now = System.currentTimeMillis()

        val first = importer.import(guideUrl, guideXml(withProgramme = true, nowMs = now).byteInputStream())
        assertEquals(1, first.keptPrograms)
        val channelsBefore = dao.getChannelsBySource(guideUrl)
        val programsBefore = programRows(guideUrl)
        assertTrue(programsBefore.isNotEmpty())

        // A window a hundred days out: nothing the live guide holds overlaps it,
        // so the guard's old evidence reads zero while the guide itself is intact.
        val farStart = now + 100L * 24L * 60L * 60_000L
        val refused = importer.import(
            sourceUrl = guideUrl,
            input = guideXml(withProgramme = false, nowMs = now).byteInputStream(),
            windowStartMs = farStart,
            windowEndMs = farStart + 6L * 60L * 60_000L
        )

        assertTrue(
            "an empty feed over an expired window is refused, not promoted",
            refused.emptyFeedKeptLive
        )
        assertEquals(0, refused.keptPrograms)
        assertEquals(
            "the live channels are untouched",
            channelsBefore,
            dao.getChannelsBySource(guideUrl)
        )
        assertEquals(
            "and so is every live program row",
            programsBefore,
            programRows(guideUrl)
        )
        assertEquals(
            "the discarded parse leaves nothing staged",
            0,
            dao.countChannelsForSource("$guideUrl@importing")
        )
    }

    @Test
    fun `a smaller but non-empty import still promotes - the guard is zero-only`() = runBlocking {
        // The boundary is pinned rather than widened: a provider that keeps fewer
        // programs than last time is its own business, and refusing that would
        // stop a thinning-but-real guide from ever updating.
        val importer = XmltvImporter(daoProvider = { dao })
        val now = System.currentTimeMillis()

        val full = importer.import(
            guideUrl,
            guideXml(withProgramme = true, nowMs = now, programmes = 2).byteInputStream()
        )
        assertEquals(2, full.keptPrograms)

        val smaller = importer.import(
            guideUrl,
            guideXml(withProgramme = true, nowMs = now).byteInputStream()
        )

        assertFalse("a nonzero import is never a refusal", smaller.emptyFeedKeptLive)
        assertEquals(1, smaller.keptPrograms)
        assertEquals(
            "and it is promoted: the live guide now holds the smaller schedule",
            1,
            dao.countProgramsForSourceInWindow(guideUrl, 0L, Long.MAX_VALUE)
        )
    }

    @Test
    fun `a healthy import promotes and the guard stays inert`() = runBlocking {
        val importer = XmltvImporter(daoProvider = { dao })
        val now = System.currentTimeMillis()

        importer.import(guideUrl, guideXml(withProgramme = true, nowMs = now).byteInputStream())
        val second = importer.import(
            guideUrl,
            guideXml(withProgramme = true, nowMs = now + 60_000L).byteInputStream()
        )

        assertFalse(second.emptyFeedKeptLive)
        assertEquals(1, second.keptPrograms)
        assertEquals(1, dao.countProgramsForSourceInWindow(guideUrl, 0L, Long.MAX_VALUE))
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
            branch.contains("streamedOutcome(fresh.value)")
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
    fun `the repository reports the refusal and keeps the stored validators`() {
        val repository = flat(read(REPOSITORY))
        val streamed = repository.substringAfter("is IptvHttpClient.XmltvFetch.Streamed -> {")
            .substringBefore("IptvHttpClient.XmltvFetch.NotModified")

        assertTrue(
            "the response was current, so its validators are stored even when " +
                "the content was empty - otherwise a dead feed is re-downloaded " +
                "on every tick",
            streamed.indexOf("GuideCacheHeaders.put(") in
                0 until streamed.indexOf("streamedOutcome(fetch.value)")
        )
        assertTrue(
            "a refusal has its own outcome, not an import",
            repository.contains("if (stats.emptyFeedKeptLive)") &&
                repository.contains("return GuideImportOutcome.EmptyFeedKeptLive")
        )
        assertTrue(
            "nothing was promoted, so no live instance is told to re-read",
            repository.contains("importedFresh = !fetch.value.emptyFeedKeptLive") &&
                repository.contains("importedFresh = !fresh.value.emptyFeedKeptLive")
        )
    }

    @Test
    fun `a finished import reports its outcome instead of returning to zero`() {
        val vm = flat(read(VIEW_MODEL))
        val progress = flat(read(PROGRESS))

        assertTrue(
            "the pass must hand its outcome - and where the live schedule " +
                "stops - to the status line",
            vm.contains("GuideImportProgress.finish(passId, outcome, guideEndsAtMs)") &&
                vm.contains("guideEndsAtMs = guideDataEndsAt()")
        )
        assertTrue(
            "a failure says what failed, with the HTTP code when there is one",
            vm.contains("httpCode != null -> \"Guide download failed: \$httpCode\"")
        )
        assertTrue(
            "the outcome line is rendered INSTEAD of the live counters",
            progress.contains(
                "val text = state.outcome?.let { outcomeLabel(it) } ?: buildList {"
            )
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
            "a feed with no programs is a feed fact, not an app failure",
            "Feed returned no programs — kept your existing guide",
            outcomeLabel(GuideImportOutcome.EmptyFeedKeptLive)
        )
        assertEquals(
            "the status line is what the two guide screens already render",
            "Imported 7 programs",
            GuideImportProgress.label(GuideImportState(outcome = GuideImportOutcome.Imported(7)), 0L)
        )
    }

    @Test
    fun `a finished pass states when the guide's schedule has already ended`() {
        val now = 1_790_000_000_000L
        val endedAt = now - 6 * 60 * 60 * 1000L
        val ended = DateFormats.time(endedAt, DateFormats.MONTH_DAY_CLOCK_12H)

        assertEquals(
            "the feed's own emptiness comes first, then the reason it stays empty",
            "Feed returned no programs — kept your existing guide · Guide data ended $ended",
            GuideImportProgress.label(
                GuideImportState(
                    outcome = GuideImportOutcome.EmptyFeedKeptLive,
                    guideEndsAtMs = endedAt
                ),
                now
            )
        )
        assertEquals(
            "a schedule that runs ahead says nothing about ending",
            "Imported 12 programs",
            GuideImportProgress.label(
                GuideImportState(
                    outcome = GuideImportOutcome.Imported(12),
                    guideEndsAtMs = now + 60_000L
                ),
                now
            )
        )
        assertEquals(
            "and a guide that has never held a program has no end to report",
            "Imported 12 programs",
            GuideImportProgress.label(
                GuideImportState(outcome = GuideImportOutcome.Imported(12)),
                now
            )
        )
        assertEquals(
            "the staleness fact is past-only",
            "anything",
            GuideImportProgress.withStaleness("anything", now + 1L, now)
        )
    }

    // ── diagnostics ──────────────────────────────────────────────────

    @Test
    fun `the newest program end is per source, and null when there is none`() = runBlocking {
        assertEquals(
            "a source that never landed has no newest end",
            null,
            dao.maxProgramEndForSource(guideUrl)
        )
        val sourceId = dao.ensureSourceId(guideUrl)
        dao.insertPrograms(
            listOf(
                program(sourceId, start = 1_000L, end = 2_000L),
                program(sourceId, start = 5_000L, end = 9_000L)
            )
        )
        assertEquals(9_000L, dao.maxProgramEndForSource(guideUrl))
        assertEquals(
            "another source's programs never answer for this one",
            null,
            dao.maxProgramEndForSource(otherUrl)
        )
    }

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
                line.contains("newestProgramEnd=") &&
                line.contains("validators=\${if (row.hasValidators) \"yes\" else \"no\"}")
        )
        assertTrue(
            "a channels-only source prints its schedule as absent rather than " +
                "as a date",
            line.contains("?: \"none\"")
        )
        assertTrue(
            "read from the configured sources, so one that never landed still shows",
            flat(read(REPOSITORY)).contains("GuideFiles.guideUrls(appContext).map { url ->")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun outcomeLabel(outcome: GuideImportOutcome): String =
        GuideImportProgress.label(GuideImportState(outcome = outcome), 0L)

    /**
     * The live guide's program rows in a comparable shape: the import must leave
     * exactly these behind when it refuses an empty feed.
     */
    private fun programRows(sourceUrl: String): List<String> {
        val sql = "SELECT p.channelId, p.title, p.startUtcMillis, p.endUtcMillis " +
            "FROM epg_programs p JOIN epg_sources s ON s.id = p.sourceId " +
            "WHERE s.url = ? ORDER BY p.channelId, p.startUtcMillis"
        return db.openHelper.readableDatabase
            .query(sql, arrayOf<Any>(sourceUrl))
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            "${cursor.getString(0)}|${cursor.getString(1)}|" +
                                "${cursor.getLong(2)}|${cursor.getLong(3)}"
                        )
                    }
                }
            }
    }

    private fun guideXml(withProgramme: Boolean, nowMs: Long, programmes: Int = 1): String {
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
            append("<channel id=\"ch0\"><display-name>Channel 0</display-name></channel>")
            append("<channel id=\"ch1\"><display-name>Channel 1</display-name></channel>")
            if (withProgramme) {
                // Distinct hour slots, so N programmes are N rows rather than
                // one row overwritten N times.
                repeat(programmes) { index ->
                    val startsAt = xmltvDate(nowMs - 15 * 60_000L + index * 60L * 60_000L)
                    val endsAt = xmltvDate(nowMs + 45 * 60_000L + index * 60L * 60_000L)
                    append("<programme channel=\"ch0\" start=\"$startsAt\" stop=\"$endsAt\">")
                    append("<title>A program ${index + 1}</title></programme>")
                }
            }
            append("</tv>")
        }
    }

    private fun xmltvDate(atMs: Long): String =
        XMLTV_UTC.format(Instant.ofEpochMilli(atMs))

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
        val XMLTV_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

        const val IPTV = "com/kennyb1201/kbstream/data/iptv/"
        const val REPOSITORY = "${IPTV}IptvRepository.kt"
        const val PROGRESS = "${IPTV}GuideImportProgress.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
    }
}
