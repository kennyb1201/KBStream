package com.kennyb1201.kbstream.data.iptv.db

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The three per-channel guide reads, on a real SQLite, against the rows the old
 * query returned.
 *
 * They used to be `ROW_NUMBER() OVER (PARTITION BY channelId ORDER BY
 * startUtcMillis ...)` — a window function, which needs SQLite 3.25+. minSdk is
 * 26 (SQLite 3.19) and Fire OS on the Fire Cube ships 3.22, where SQLite parses
 * the window clause as a syntax error and throws on the main thread: opening the
 * guide force-closed the app, and Sentry often missed it because the process died
 * before the report flushed. The rewrite is a correlated count of the in-window
 * rows that start before (or after, for the "recent" read) the row being tested,
 * which is the same "first N per channel" on machinery every supported SQLite
 * has.
 *
 * Two things are asserted, because a compatibility rewrite has two ways to be
 * wrong:
 *  1. the delivered rows — exact expected lists per read, including the window
 *     edges (a program that starts before the window but overlaps it, one that
 *     starts exactly at `windowEnd`), the per-channel limit, and the tie-break,
 *     which is deterministic here (by `rowid`) and was whatever scan order the
 *     window function produced before;
 *  2. parity with the OLD window-function SQL on the same data — the strongest
 *     form of "same rows, same order", run against the host's SQLite. Where that
 *     host is older than 3.25 the old statement cannot run at all (the device
 *     that crashed), so that comparison is skipped rather than faked; the exact
 *     expectations above still cover the rows.
 *
 * A JVM test under Robolectric, so it runs in the same `./gradlew
 * testDebugUnitTest` gate as the rest of the suite — no device needed.
 */
@RunWith(AndroidJUnit4::class)
class GuideWindowQueryCompatibilityTest {

    private lateinit var db: IptvDatabase
    private lateinit var dao: IptvDao

    /** The seeded guide's own row id, read back rather than assumed to be 1. */
    private var sourceId = 0L

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.iptvDao()
        runBlocking {
            dao.ensureSourceId(SOURCE_URL)
            sourceId = dao.sourceIdOf(SOURCE_URL)!!
            dao.insertPrograms(programs())
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── The exact rows ──────────────────────────────────────────────

    @Test
    fun `the lite read keeps the first N in-window programs per channel`() = runBlocking {
        assertEquals(
            // A: 500 then 1000 (1500 is the channel's third); B: 1000 then 4000;
            // C: its only program. Channel order is the ORDER BY, not the seed.
            listOf("A|a5|500", "A|a1|1000", "B|b1|1000", "B|b3|4000", "C|c1|2000"),
            dao.getProgramsForChannelsInWindowLite(SOURCE_URL, CHANNELS, WINDOW_START, WINDOW_END, 2)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
        assertEquals(
            "one per channel, not two overall",
            listOf("A|a5|500", "B|b1|1000", "C|c1|2000"),
            dao.getProgramsForChannelsInWindowLite(SOURCE_URL, CHANNELS, WINDOW_START, WINDOW_END, 1)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
        assertEquals(
            "a limit past the window's own size keeps every in-window row",
            listOf(
                "A|a5|500", "A|a1|1000", "A|a2|1500", "A|a3|3000", "A|a4|9000",
                "B|b1|1000", "B|b3|4000", "B|b4|6000",
                "C|c1|2000"
            ),
            dao.getProgramsForChannelsInWindowLite(SOURCE_URL, CHANNELS, WINDOW_START, WINDOW_END, 10)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
    }

    @Test
    fun `the lite read carries the same lite columns it did before`() = runBlocking {
        val rows = dao.getProgramsForChannelsInWindowLite(
            SOURCE_URL, listOf("C"), WINDOW_START, WINDOW_END, 2
        )

        assertEquals(1, rows.size)
        assertEquals("", rows[0].description)
        assertEquals("", rows[0].category)
    }

    @Test
    fun `the full read keeps the first N and its own columns`() = runBlocking {
        val rows = dao.getProgramsForChannelsInWindow(SOURCE_URL, CHANNELS, WINDOW_START, WINDOW_END, 2)

        assertEquals(
            listOf("A|a5|500", "A|a1|1000", "B|b1|1000", "B|b3|4000", "C|c1|2000"),
            rows.map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
        // This is the read that carries the description and category, where the
        // lite one selects blanks - the rewrite must not have swapped the two.
        assertEquals("a5 detail", rows[0].description)
        assertEquals("a5 cat", rows[0].category)
    }

    @Test
    fun `the recent read keeps the LATEST N that have aired`() = runBlocking {
        assertEquals(
            // A: 3000 then 1500 - the two latest, not the two earliest; B: 4000
            // then 1000; C: its only aired program.
            listOf("A|a3|3000", "A|a2|1500", "B|b3|4000", "B|b1|1000", "C|c1|2000"),
            dao.getRecentProgramsForChannels(SOURCE_URL, CHANNELS, NOW, WINDOW_START, 2)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
        assertEquals(
            "one per channel, newest first",
            listOf("A|a3|3000", "B|b3|4000", "C|c1|2000"),
            dao.getRecentProgramsForChannels(SOURCE_URL, CHANNELS, NOW, WINDOW_START, 1)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
    }

    @Test
    fun `an equal start time is broken by rowid, not by scan order`() = runBlocking {
        // d1 and d2 both start at 1000. The window function's order between them
        // was unspecified, and in practice it was scan order (rowid ascending);
        // the count makes that explicit, so which of the two a limit keeps is the
        // same answer on every run and every plan - and the same one the device
        // that supports window functions produced.
        // Both rows when both fit: the ORDER BY cannot separate them, and
        // neither could the window version's, so the pair is asserted as a set.
        assertEquals(
            listOf("D|d1|1000", "D|d2|1000"),
            dao.getProgramsForChannelsInWindowLite(SOURCE_URL, listOf("D"), WINDOW_START, WINDOW_END, 2)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
                .sorted()
        )
        assertEquals(
            "the ascending read's limit keeps the earlier row",
            listOf("D|d1|1000"),
            dao.getProgramsForChannelsInWindowLite(SOURCE_URL, listOf("D"), WINDOW_START, WINDOW_END, 1)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
        assertEquals(
            listOf("D|d1|1000", "D|d2|1000"),
            dao.getRecentProgramsForChannels(SOURCE_URL, listOf("D"), NOW, WINDOW_START, 2)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
                .sorted()
        )
        assertEquals(
            "and the recent read's is the same one: the tie-break does not flip " +
                "with the sort direction",
            listOf("D|d1|1000"),
            dao.getRecentProgramsForChannels(SOURCE_URL, listOf("D"), NOW, WINDOW_START, 1)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
    }

    @Test
    fun `the window edges are the same ones the WHERE clause always used`() = runBlocking {
        val rows = dao.getProgramsForChannelsInWindowLite(SOURCE_URL, listOf("A"), WINDOW_START, WINDOW_END, 10)
            .map { it.title }

        // a5 starts BEFORE the window but overlaps it, so it is in-window by
        // `endUtcMillis > windowStart`; a7 ends exactly at windowStart and is
        // not; a4 starts before windowEnd and is; a6 starts exactly at
        // windowEnd and is not.
        assertEquals(listOf("a5", "a1", "a2", "a3", "a4"), rows)
    }

    @Test
    fun `another guide's programs are never counted`() = runBlocking {
        val other = "http://other-guide.test/xmltv"
        dao.ensureSourceId(other)
        // Same channel, inside the same window, and EARLIER than every row the
        // seeded guide has: without the sourceId filter inside the count they
        // would take the first two places of this guide's own channel A.
        dao.insertPrograms(
            listOf(
                program(other, "A", "other1", 100, 2_000),
                program(other, "A", "other2", 300, 2_500)
            )
        )

        assertEquals(
            "the sourceId filter is in the outer query AND inside the count",
            listOf("A|a5|500", "A|a1|1000"),
            dao.getProgramsForChannelsInWindowLite(SOURCE_URL, listOf("A"), WINDOW_START, WINDOW_END, 2)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
        assertEquals(
            listOf("A|other1|100", "A|other2|300"),
            dao.getProgramsForChannelsInWindowLite(other, listOf("A"), WINDOW_START, WINDOW_END, 2)
                .map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }
        )
    }

    // ── Parity with the query this replaced ─────────────────────────

    @Test
    fun `the lite read returns what the window-function version returned`() = runBlocking {
        assumeWindowFunctions()

        assertEquals(
            oldQuery(LITE_OLD_SQL, arrayOf(SOURCE_URL, WINDOW_START, WINDOW_END), limit = 2),
            dao.getProgramsForChannelsInWindowLite(SOURCE_URL, CHANNELS, WINDOW_START, WINDOW_END, 2).rows()
        )
        assertEquals(
            oldQuery(LITE_OLD_SQL, arrayOf(SOURCE_URL, WINDOW_START, WINDOW_END), limit = 3),
            dao.getProgramsForChannelsInWindowLite(SOURCE_URL, CHANNELS, WINDOW_START, WINDOW_END, 3).rows()
        )
    }

    @Test
    fun `the full read returns what the window-function version returned`() = runBlocking {
        assumeWindowFunctions()

        assertEquals(
            oldQuery(FULL_OLD_SQL, arrayOf(SOURCE_URL, WINDOW_START, WINDOW_END), limit = 2),
            dao.getProgramsForChannelsInWindow(SOURCE_URL, CHANNELS, WINDOW_START, WINDOW_END, 2).rows()
        )
    }

    @Test
    fun `the recent read returns what the window-function version returned`() = runBlocking {
        assumeWindowFunctions()

        assertEquals(
            oldQuery(RECENT_OLD_SQL, arrayOf(SOURCE_URL, NOW, WINDOW_START), limit = 2),
            dao.getRecentProgramsForChannels(SOURCE_URL, CHANNELS, NOW, WINDOW_START, 2).rows()
        )
        assertEquals(
            oldQuery(RECENT_OLD_SQL, arrayOf(SOURCE_URL, NOW, WINDOW_START), limit = 4),
            dao.getRecentProgramsForChannels(SOURCE_URL, CHANNELS, NOW, WINDOW_START, 4).rows()
        )
    }

    /**
     * Runs one of the pre-rewrite statements and returns the same `channel|title|
     * start` identity the assertions above use, in the order it was written.
     *
     * The statement is the historical one, verbatim: the point of this file is
     * that the new query reproduces it, so it must not be tidied up here.
     */
    private fun oldQuery(sql: String, args: Array<Any?>, limit: Int): List<String> {
        val bound = args + limit
        val cursor = try {
            db.openHelper.writableDatabase.query("$sql", bound)
        } catch (e: SQLiteException) {
            // The device this change exists for: the host SQLite has no window
            // functions, so the OLD statement cannot be run and there is nothing
            // to compare against. Skip the comparison; the expected rows are
            // asserted on the new query above, which is what has to be right.
            assumeTrue("host SQLite rejects the old window clause: ${e.message}", false)
            return emptyList()
        }
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add("${it.getString(0)}|${it.getString(1)}|${it.getLong(2)}")
                }
            }
        }
    }

    private fun List<EpgProgramRow>.rows(): List<String> =
        map { "${it.channelId}|${it.title}|${it.startUtcMillis}" }

    /**
     * Skips a parity test when the host SQLite is older than window functions,
     * saying so in the report rather than passing silently.
     */
    private fun assumeWindowFunctions() {
        val version = sqliteVersion()
        val parts = version.split(".").map { it.toIntOrNull() ?: 0 }
        val supported = (parts.getOrElse(0) { 0 } > 3) ||
            (parts.getOrElse(0) { 0 } == 3 && parts.getOrElse(1) { 0 } >= 25)
        assumeTrue(
            "SQLite $version on this host has no window functions (3.25+ needed), " +
                "which is the very case this rewrite is for - the old statement " +
                "cannot be replayed here",
            supported
        )
    }

    private fun sqliteVersion(): String =
        db.openHelper.writableDatabase.query("SELECT sqlite_version()").use {
            if (it.moveToFirst()) it.getString(0) else "0.0"
        }

    /**
     * The guide DAO must not go back to a window function: the crash is silent on
     * every device that supports one, so a reappearing `OVER (` is only found on
     * a Fire Cube in the field.
     */
    @Test
    fun `no window function is left anywhere under the iptv data package`() {
        val offenders = iptvSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> "OVER (" in line.uppercase() }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertTrue(
            "a window function needs SQLite 3.25+, and this app ships to devices " +
                "with less: $offenders",
            offenders.isEmpty()
        )
    }

    private fun iptvSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/data/iptv")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("iptv source root not found from ${System.getProperty("user.dir")}")
    }

    // ── Fixture ─────────────────────────────────────────────────────

    /**
     * The seed, one channel per shape of answer:
     *  - A overlaps the window on both edges and holds more programs than the
     *    limit, so the limit and the edges are both visible;
     *  - B gives the per-channel limit something to cut (three in-window rows);
     *  - C has a single program, the case the count must not drop;
     *  - D has two programs starting at the same instant, the tie.
     * Programs that fall outside the window are seeded too (a7 ends exactly at
     * `windowStart`, a6 starts exactly at `windowEnd`) so an off-by-one in the
     * rewritten predicate cannot pass.
     */
    private fun programs(): List<EpgProgramEntity> {
        // Explicit ids, well clear of 0: an autoGenerate key of 0 means "let
        // SQLite choose", and a chosen id colliding with a later explicit one
        // would REPLACE that row instead of inserting it.
        var id = 1_000L
        fun row(channel: String, title: String, start: Long, end: Long, description: String? = null) =
            EpgProgramEntity(
                id = id++,
                sourceId = sourceId,
                channelId = channel,
                title = title,
                description = description,
                category = description?.let { "$title cat" },
                startUtcMillis = start,
                endUtcMillis = end
            )

        return listOf(
            row("A", "a5", 500, 1_500, "a5 detail"),
            row("A", "a1", 1_000, 2_000, "a1 detail"),
            row("A", "a2", 1_500, 2_500, "a2 detail"),
            row("A", "a7", 0, WINDOW_START),
            row("A", "a3", 3_000, 4_000, "a3 detail"),
            row("A", "a4", 9_000, 11_000, "a4 detail"),
            row("A", "a6", WINDOW_END, WINDOW_END + 1_000),
            row("B", "b1", 1_000, 2_000, "b1 detail"),
            row("B", "b3", 4_000, 5_000, "b3 detail"),
            row("B", "b4", 6_000, 9_000, "b4 detail"),
            row("C", "c1", 2_000, 3_000, "c1 detail"),
            row("D", "d1", 1_000, 2_000, "d1 detail"),
            row("D", "d2", 1_000, 3_000, "d2 detail")
        )
    }

    private fun program(sourceUrl: String, channel: String, title: String, start: Long, end: Long) =
        EpgProgramEntity(
            // 0 = let SQLite assign it: these are seeded one at a time, and a
            // generated id is always max(rowid) + 1, never a collision.
            id = 0,
            sourceId = sourceIdFor(sourceUrl),
            channelId = channel,
            title = title,
            description = "$title detail",
            category = "$title cat",
            startUtcMillis = start,
            endUtcMillis = end
        )

    private fun sourceIdFor(sourceUrl: String): Long = runBlocking { dao.sourceIdOf(sourceUrl)!! }

    private companion object {
        const val SOURCE_URL = "http://guide.test/xmltv"
        const val WINDOW_START = 1_000L
        const val WINDOW_END = 10_000L
        const val NOW = 5_000L
        val CHANNELS = listOf("A", "B", "C")

        /**
         * The three statements as they stood before the rewrite, with
         * `:perChannelLimit` left as a trailing bind (appended by [oldQuery]).
         * `IN (:channelIds)` is spelled out here because a raw query takes the
         * list expanded, exactly as Room expanded it.
         */
        const val CHANNEL_LIST = "'A', 'B', 'C'"

        val LITE_OLD_SQL = """
            SELECT channelId, title, startUtcMillis, endUtcMillis FROM (
                SELECT
                    channelId,
                    title,
                    startUtcMillis,
                    endUtcMillis,
                    ROW_NUMBER() OVER (
                        PARTITION BY channelId
                        ORDER BY startUtcMillis ASC
                    ) AS rowNumber
                FROM epg_programs
                WHERE sourceId = (SELECT id FROM epg_sources WHERE url = ?)
                  AND endUtcMillis > ?
                  AND startUtcMillis < ?
                  AND channelId IN ($CHANNEL_LIST)
            )
            WHERE rowNumber <= ?
            ORDER BY channelId ASC, startUtcMillis ASC
        """.trimIndent()

        val FULL_OLD_SQL = """
            SELECT channelId, title, startUtcMillis, endUtcMillis FROM (
                SELECT
                    channelId,
                    title,
                    startUtcMillis,
                    endUtcMillis,
                    ROW_NUMBER() OVER (
                        PARTITION BY channelId
                        ORDER BY startUtcMillis ASC
                    ) AS rowNumber
                FROM epg_programs
                WHERE sourceId = (SELECT id FROM epg_sources WHERE url = ?)
                  AND endUtcMillis > ?
                  AND startUtcMillis < ?
                  AND channelId IN ($CHANNEL_LIST)
            )
            WHERE rowNumber <= ?
            ORDER BY channelId ASC, startUtcMillis ASC
        """.trimIndent()

        val RECENT_OLD_SQL = """
            SELECT channelId, title, startUtcMillis, endUtcMillis FROM (
                SELECT
                    channelId,
                    title,
                    startUtcMillis,
                    endUtcMillis,
                    ROW_NUMBER() OVER (
                        PARTITION BY channelId
                        ORDER BY startUtcMillis DESC
                    ) AS rowNumber
                FROM epg_programs
                WHERE sourceId = (SELECT id FROM epg_sources WHERE url = ?)
                  AND endUtcMillis <= ?
                  AND endUtcMillis > ?
                  AND channelId IN ($CHANNEL_LIST)
            )
            WHERE rowNumber <= ?
            ORDER BY channelId ASC, startUtcMillis DESC
        """.trimIndent()
    }
}
