package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import com.kennyb1201.kbstream.data.iptv.db.takePerChannel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guide's hot reads must stay index seeks, not scans, and the in-memory
 * per-channel trim must be the same "first N" the old correlated count gave.
 *
 * The plan is measured from the same Room schema the device opens, through
 * [EpgQueryPlans], so a missing or renamed index fails here rather than only in
 * a field paste. The plan is not a row assertion: it never costs a guide read.
 */
@RunWith(AndroidJUnit4::class)
class EpgQueryPlanContractTest {

    private lateinit var db: IptvDatabase

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val dao = db.iptvDao()
            dao.ensureSourceId("http://guide.test/xmltv")
            dao.insertPrograms(
                listOf(
                    program("A", "a1", 100, 200),
                    program("A", "a2", 200, 300),
                    program("A", "a3", 300, 400),
                    program("B", "b1", 100, 200)
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the guide's hot reads use indexes and have no correlated count`() {
        val line = EpgQueryPlans.line(db.openHelper.writableDatabase)

        assertTrue("lite read plan: $line", line.contains("lite=SEARCH p1 USING INDEX"))
        assertTrue("recent read plan: $line", line.contains("recent=SEARCH p1 USING INDEX"))
        assertTrue("full read plan: $line", line.contains("full=SEARCH p1 USING INDEX"))
        assertFalse("the correlated count is gone: $line", line.contains("CORRELATED SCALAR SUBQUERY"))
        assertTrue(
            "the end-time index serves MAX(endUtcMillis): $line",
            line.contains("maxEnd=SEARCH epg_programs USING COVERING INDEX") &&
                line.contains("sourceId_endUtcMillis")
        )
    }

    @Test
    fun `the trim keeps the first N per channel in query order`() {
        val rows = listOf(
            row("A", "a1", 100),
            row("A", "a2", 200),
            row("A", "a3", 300),
            row("B", "b1", 100),
            row("B", "b2", 200)
        )

        assertEquals(
            listOf("A|a1", "A|a2", "B|b1", "B|b2"),
            rows.takePerChannel(2).map { "${it.channelId}|${it.title}" }
        )
        assertEquals(
            listOf("A|a1", "B|b1"),
            rows.takePerChannel(1).map { "${it.channelId}|${it.title}" }
        )
        assertEquals("a non-positive limit keeps nothing", emptyList<Any>(), rows.takePerChannel(0))
    }

    private fun program(channelId: String, title: String, start: Long, end: Long) =
        com.kennyb1201.kbstream.data.iptv.db.EpgProgramEntity(
            sourceId = 1,
            channelId = channelId,
            title = title,
            description = null,
            category = null,
            startUtcMillis = start,
            endUtcMillis = end
        )

    private fun row(channelId: String, title: String, start: Long) = EpgProgramRow(
        channelId = channelId,
        title = title,
        description = null,
        category = null,
        startUtcMillis = start,
        endUtcMillis = start + 100
    )
}
