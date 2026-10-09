package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgSearchIndex
import com.kennyb1201.kbstream.data.iptv.db.EpgSearchIndexCallback
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full-text index carries program descriptions, and an index built before
 * it did is widened on the next open.
 *
 * Tier 1b of the sports matcher reads a game's DESCRIPTION when no guide title
 * names both teams - a provider that titles a game "NHL Hockey" and names the
 * teams only in the synopsis is otherwise unmatched. That only works if the
 * description is in the index, so these pin both halves: description terms
 * resolve, the viewer-facing title scope does not see them, and a legacy
 * title-only index is rebuilt to hold them.
 */
@RunWith(AndroidJUnit4::class)
class EpgSearchDescriptionIndexTest {

    private lateinit var db: IptvDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java)
            .addCallback(EpgSearchIndexCallback)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a description term is searchable and a title-scoped term is not`() = runBlocking {
        val sourceId = db.iptvDao().ensureSourceId("https://provider.example/epg.xml")
        db.iptvDao().insertPrograms(
            listOf(
                EpgProgramEntity(
                    sourceId = sourceId,
                    channelId = "ch1",
                    // The title names no team; only the synopsis does - exactly
                    // the row tier 1b exists to find.
                    title = "NHL Hockey",
                    description = "The Tampa Bay Lightning visit the Florida Panthers.",
                    category = null,
                    startUtcMillis = 1_000L,
                    endUtcMillis = FUTURE
                )
            )
        )

        assertEquals(
            "the sports lookup reads both columns, so a description term must hit",
            listOf("NHL Hockey"),
            matching("panthers*")
        )
        assertTrue(
            "the viewer-facing guide search scopes to the title column and must not " +
                "start returning programs that only mention the words in their synopsis",
            matching("title:panthers*").isEmpty()
        )
        assertEquals(
            "and the title column still matches its own words",
            listOf("NHL Hockey"),
            matching("title:hockey*")
        )
    }

    @Test
    fun `a title-only index is widened to index descriptions on the next open`() = runBlocking {
        val sourceId = db.iptvDao().ensureSourceId("https://provider.example/epg.xml")
        db.iptvDao().insertPrograms(
            listOf(
                EpgProgramEntity(
                    sourceId = sourceId,
                    channelId = "ch1",
                    title = "NHL Hockey",
                    description = "The Tampa Bay Lightning visit the Florida Panthers.",
                    category = null,
                    startUtcMillis = 1_000L,
                    endUtcMillis = FUTURE
                )
            )
        )

        // Recreate the pre-upgrade shape: a title-only index and title-only
        // triggers, backfilled with the titles alone.
        asLegacyTitleOnlyIndex()
        assertTrue("the legacy index cannot answer a description term", matching("panthers*").isEmpty())

        EpgSearchIndex.ensure(db.openHelper.writableDatabase)

        assertEquals(
            "the rebuild must widen the schema AND backfill descriptions from the programs table",
            listOf("NHL Hockey"),
            matching("panthers*")
        )
    }

    private fun asLegacyTitleOnlyIndex() {
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("DROP TABLE IF EXISTS `${EpgSearchIndex.TABLE}`")
        listOf("ai", "ad", "au").forEach { suffix ->
            sqlite.execSQL("DROP TRIGGER IF EXISTS ${EpgSearchIndex.TABLE}_$suffix")
        }
        sqlite.execSQL("CREATE VIRTUAL TABLE `${EpgSearchIndex.TABLE}` USING FTS4(`title`)")
        sqlite.execSQL(
            "INSERT INTO `${EpgSearchIndex.TABLE}`(rowid, title) " +
                "SELECT id, title FROM `epg_programs`"
        )
        sqlite.execSQL(
            "CREATE TRIGGER ${EpgSearchIndex.TABLE}_ai AFTER INSERT ON `epg_programs` " +
                "BEGIN INSERT INTO `${EpgSearchIndex.TABLE}`(rowid, title) " +
                "VALUES (new.id, new.title); END"
        )
    }

    /** Titles the FTS index matches for [expression], unused ids excluded. */
    private fun matching(expression: String): List<String> {
        val sql = """
            SELECT p.title AS title
            FROM `${EpgSearchIndex.TABLE}`
            JOIN epg_programs AS p ON p.id = `${EpgSearchIndex.TABLE}`.rowid
            WHERE `${EpgSearchIndex.TABLE}` MATCH ?
              AND p.endUtcMillis > ?
            ORDER BY p.startUtcMillis ASC
        """.trimIndent()
        return db.openHelper.writableDatabase
            .query(sql, arrayOf<Any?>(expression, FAR_PAST))
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
    }

    private companion object {
        // Every program is already airing for the purposes of the search, which
        // filters on endUtcMillis > now.
        const val FAR_PAST = 0L
        const val FUTURE = 4_000_000_000_000L
    }
}
