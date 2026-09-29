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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A guide migration must take the full-text index with it.
 *
 * `epg_programs_fts` is a standalone FTS4 table — it keeps its own copy of
 * every title plus its term index, which is tens of megabytes on a real guide.
 * It is created by raw SQL rather than as a Room `@Fts4` entity (see
 * EpgSearchIndex for why), so Room's destructive migration does NOT know about
 * it: the programs are dropped and recreated and the index stays behind, full,
 * pointing at ids that no longer exist.
 *
 * That is the space the migration exists to reclaim, so two independent things
 * remove it — the callback on the migration itself, and this self-heal on the
 * next open for any path that does not fire the callback. Both end at "the
 * index is empty and rebuilt over the new table"; this test drives the second.
 */
@RunWith(AndroidJUnit4::class)
class EpgSearchIndexReclaimTest {

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
    fun `an index whose programs are gone is emptied and rebuilt`() = runBlocking {
        val sourceId = db.iptvDao().ensureSourceId("https://provider.example/epg.xml")
        db.iptvDao().insertPrograms(programs(sourceId))
        assertEquals("the insert trigger must index the programs", 3, countFts())

        orphanTheIndex()
        assertEquals(0, countPrograms())
        assertEquals(3, countFts())

        // What Room does on the next open.
        EpgSearchIndex.ensure(db.openHelper.writableDatabase)

        assertEquals("the stale title copies are gone", 0, countFts())

        // ...and the index still works afterwards, which is the half that would
        // silently rot if the triggers were not restored.
        db.iptvDao().insertPrograms(
            listOf(
                EpgProgramEntity(
                    sourceId = sourceId,
                    channelId = "ch9",
                    title = "After the rebuild",
                    description = null,
                    category = null,
                    startUtcMillis = 10_000L,
                    endUtcMillis = 20_000L
                )
            )
        )
        assertEquals(1, countFts())
    }

    @Test
    fun `a healthy index is left alone`() = runBlocking {
        val sourceId = db.iptvDao().ensureSourceId("https://provider.example/epg.xml")
        db.iptvDao().insertPrograms(programs(sourceId))

        EpgSearchIndex.ensure(db.openHelper.writableDatabase)

        // The reclaim must not fire just because the index has rows: it only
        // fires when there are no programs for them to belong to.
        assertEquals(3, countPrograms())
        assertEquals(3, countFts())
    }

    /**
     * Reproduces the state a destructive migration leaves: the programs table
     * empty, the index still full. Dropping the delete trigger first is what
     * makes the delete leave the index alone, exactly as dropping the whole
     * table does.
     */
    private fun orphanTheIndex() {
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("DROP TRIGGER IF EXISTS ${EpgSearchIndex.TABLE}_ad")
        sqlite.execSQL("DELETE FROM epg_programs")
    }

    private fun programs(sourceId: Long): List<EpgProgramEntity> =
        (0 until 3).map { index ->
            EpgProgramEntity(
                sourceId = sourceId,
                channelId = "ch$index",
                title = "program $index",
                description = null,
                category = null,
                startUtcMillis = index * 1_000L,
                endUtcMillis = index * 1_000L + 500L
            )
        }

    private fun countFts(): Int = countOf(EpgSearchIndex.TABLE)

    private fun countPrograms(): Int = countOf("epg_programs")

    private fun countOf(table: String): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
