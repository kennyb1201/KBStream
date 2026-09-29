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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guide stores its source URL once, by id.
 *
 * A guide URL is a long string — an Xtream provider puts the account's username
 * and password in its query — and it used to be a column on EVERY channel and
 * program row, and the leading column of the programs index. On the playlists
 * this app is used with that is hundreds of thousands of copies per guide file,
 * tens of megabytes that say nothing. The rows now reference `epg_sources` by
 * integer.
 *
 * These run against a real (in-memory) database under Robolectric, because the
 * whole point is the SQL: the id resolution is a subquery inside every guide
 * query, so a mistake there reads the wrong source's guide rather than failing
 * to compile. Room validates the statements; only a real database says whether
 * they resolve to the right rows.
 */
@RunWith(AndroidJUnit4::class)
class GuideSourceKeyTest {

    private val guideA = "https://provider.example/xmltv.php?username=alice&password=secret"
    private val guideB = "https://other.example/epg.xml"

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

    // ── the storage shape ───────────────────────────────────────────

    @Test
    fun `the guide URL is not a column on the row tables`() {
        assertEquals(
            listOf("sourceId"),
            columnsOf("epg_programs").filter { it == "sourceId" || it == "sourceUrl" }
        )
        assertEquals(
            listOf("sourceId"),
            columnsOf("epg_channels").filter { it == "sourceId" || it == "sourceUrl" }
        )
        assertTrue("epg_sources must exist to hold the URL", "url" in columnsOf("epg_sources"))
    }

    @Test
    fun `many rows under one guide share a single source row`() = runBlocking {
        dao.insertChannels(channels(dao.ensureSourceId(guideA), 3))
        dao.insertPrograms(programs(dao.ensureSourceId(guideA), 3))

        assertEquals(1, countOf("epg_sources"))
        assertEquals(3, countOf("epg_channels"))
        assertEquals(3, countOf("epg_programs"))
    }

    @Test
    fun `a source id is stable for one URL and distinct across URLs`() = runBlocking {
        val first = dao.ensureSourceId(guideA)
        val again = dao.ensureSourceId(guideA)
        val other = dao.ensureSourceId(guideB)

        assertEquals(first, again)
        assertNotEquals(first, other)
        assertEquals(2, countOf("epg_sources"))
    }

    @Test
    fun `a URL is trimmed before it becomes an identity`() = runBlocking {
        // Guide URLs come from prefs a user typed, so a stray space must not
        // create a second source row that no query would ever read.
        assertEquals(
            dao.ensureSourceId(guideA),
            dao.ensureSourceId("  $guideA  ")
        )
        assertEquals(1, countOf("epg_sources"))
    }

    // ── reads resolve to the right guide ────────────────────────────

    @Test
    fun `each guide reads back only its own programs`() = runBlocking {
        val a = dao.ensureSourceId(guideA)
        val b = dao.ensureSourceId(guideB)
        dao.insertPrograms(programs(a, 2, title = "A-programme"))
        dao.insertPrograms(programs(b, 2, title = "B-programme"))

        val fromA = dao.getProgramsForChannelsInWindow(
            sourceUrl = guideA,
            channelIds = channelIds(),
            windowStart = 0L,
            windowEnd = Long.MAX_VALUE,
            perChannelLimit = 10
        )
        val fromB = dao.getProgramsForChannelsInWindow(
            sourceUrl = guideB,
            channelIds = channelIds(),
            windowStart = 0L,
            windowEnd = Long.MAX_VALUE,
            perChannelLimit = 10
        )

        assertEquals(2, fromA.size)
        assertEquals(2, fromB.size)
        assertTrue(fromA.all { it.title == "A-programme" })
        assertTrue(fromB.all { it.title == "B-programme" })
    }

    @Test
    fun `clearing one guide leaves the other intact`() = runBlocking {
        val a = dao.ensureSourceId(guideA)
        val b = dao.ensureSourceId(guideB)
        dao.insertChannels(channels(a, 2))
        dao.insertChannels(channels(b, 2))
        dao.insertPrograms(programs(a, 2))
        dao.insertPrograms(programs(b, 2))

        dao.clearGuideBySource(guideA)

        assertEquals(0, countOf("epg_programs", where = guideOf(guideA)))
        assertEquals(2, countOf("epg_programs", where = guideOf(guideB)))
        assertEquals(2, countOf("epg_channels", where = guideOf(guideB)))
        assertTrue(!dao.hasChannelsForSource(guideA))
        assertTrue(dao.hasChannelsForSource(guideB))
    }

    @Test
    fun `re-importing a channel updates it in place`() = runBlocking {
        val a = dao.ensureSourceId(guideA)
        dao.insertChannels(channels(a, 1))
        dao.insertChannels(
            listOf(
                EpgChannelEntity(
                    id = "ch0",
                    sourceId = a,
                    primaryDisplayName = "Renamed",
                    allDisplayNames = "renamed",
                    iconUrl = null
                )
            )
        )

        assertEquals(1, countOf("epg_channels"))
        assertEquals("Renamed", dao.getChannelsBySource(guideA).single().primaryDisplayName)
    }

    // ── the import's staging → live promotion ───────────────────────

    @Test
    fun `a staged import is promoted onto the live key`() = runBlocking {
        val staging = "$guideA@importing"
        val stagingId = dao.ensureSourceId(staging)
        dao.insertChannels(channels(stagingId, 2))
        dao.insertPrograms(programs(stagingId, 2))
        // The live guide already had rows from the previous import.
        val liveId = dao.ensureSourceId(guideA)
        dao.insertChannels(channels(liveId, 2, idPrefix = "old"))
        dao.insertPrograms(programs(liveId, 2, idPrefix = "old", title = "stale"))

        dao.swapStagedGuideIntoLive(sourceUrl = guideA, stagingUrl = staging)

        val liveChannels = dao.getChannelsBySource(guideA)
        assertEquals(listOf("ch0", "ch1"), liveChannels.map { it.id }.sorted())
        assertEquals("the promoted programs are the live guide", 2, countOf("epg_programs", where = guideOf(guideA)))
        assertEquals("nothing is left staged", 0, countOf("epg_programs", where = guideOf(staging)))
        assertEquals(0, countOf("epg_channels", where = guideOf(staging)))
    }

    @Test
    fun `a promotion onto a URL that has never been imported still lands`() = runBlocking {
        // First import for this guide: there is no live source row yet, so a
        // re-key that only knew the staging id would move nothing.
        val staging = "$guideB@importing"
        val stagingId = dao.ensureSourceId(staging)
        dao.insertChannels(channels(stagingId, 2))
        dao.insertPrograms(programs(stagingId, 2))

        dao.swapStagedGuideIntoLive(sourceUrl = guideB, stagingUrl = staging)

        assertEquals(2, dao.getChannelsBySource(guideB).size)
        assertEquals(0, countOf("epg_channels", where = guideOf(staging)))
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun channels(
        sourceId: Long,
        count: Int,
        idPrefix: String = "ch"
    ): List<EpgChannelEntity> = (0 until count).map { index ->
        EpgChannelEntity(
            id = "$idPrefix$index",
            sourceId = sourceId,
            primaryDisplayName = "$idPrefix$index",
            allDisplayNames = "$idPrefix$index",
            iconUrl = null
        )
    }

    private fun programs(
        sourceId: Long,
        count: Int,
        idPrefix: String = "ch",
        title: String = "programme"
    ): List<EpgProgramEntity> = (0 until count).map { index ->
        EpgProgramEntity(
            sourceId = sourceId,
            channelId = "$idPrefix$index",
            title = title,
            description = null,
            category = null,
            startUtcMillis = index * 1_000L,
            endUtcMillis = index * 1_000L + 500L
        )
    }

    private fun channelIds(): List<String> = listOf("ch0", "ch1", "ch2")

    /** A `sourceId IN (…)` predicate for the URLs given, for COUNT assertions. */
    private fun guideOf(vararg urls: String): String {
        val ids = urls.joinToString(", ") { url ->
            "(SELECT id FROM epg_sources WHERE url = '${url.replace("'", "''")}')"
        }
        return "sourceId IN ($ids)"
    }

    private fun columnsOf(table: String): List<String> =
        db.openHelper.readableDatabase
            .query("PRAGMA table_info($table)")
            .use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }

    private fun countOf(table: String, where: String? = null): Int {
        val sql = if (where == null) {
            "SELECT COUNT(*) FROM $table"
        } else {
            "SELECT COUNT(*) FROM $table WHERE $where"
        }
        return db.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }
}
