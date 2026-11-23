package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull watermark's wiring (perf spec 2026-11-22, item 9).
 *
 * [SyncWatermarkTest] pins the arithmetic; this pins the three places that use
 * it, because a cursor is only safe when every cycle reads through it and none
 * advances it without having applied. Source pins: the sync engine is
 * Android/Room-bound and its network paths cannot run on the JVM harness.
 */
class SyncWatermarkWiringContractTest {

    private val sync = source(SYNC)
    private val flat = squash(sync)

    @Test
    fun `the query carries the cursor to PostgREST`() {
        assertTrue(
            "the read accepts a starting timestamp",
            flat.contains("updatedSince:String?=null")
        )
        assertTrue(
            "and filters the server's own write time, not the payload's client clock",
            flat.contains("filter{gt(\"updated_at\",updatedSince)}")
        )
    }

    @Test
    fun `all three big tables read through their cursor`() {
        listOf(
            Triple("TABLE_HISTORY", "\"item_id\"", "TABLE_HISTORY"),
            Triple("TABLE_WATCHED", "\"item_key\"", "TABLE_WATCHED"),
            Triple("TABLE_OVERRIDES", "KEY_OVERRIDES", "TABLE_OVERRIDES")
        ).forEach { (table, key, callTable) ->
            assertTrue(
                "$table reads its watermark before the pull",
                flat.contains("readPullWatermark(context,$table,pid)")
            )
            assertTrue(
                "$table passes it as the window start",
                flat.contains("pullRows($callTable,$key,SyncWatermark.windowStart(watermark))")
            )
            assertTrue(
                "$table advances the mark from what it saw",
                flat.contains("writePullWatermark(context,$table,pid,")
            )
        }
        assertEquals(
            "one read of the cursor per table",
            3,
            Regex("readPullWatermark\\(context,").findAll(flat).count()
        )
        assertEquals(
            "and one advance per table",
            3,
            Regex("writePullWatermark\\(context,").findAll(flat).count()
        )
        assertTrue(
            "every advance is made from the rows that pull read",
            flat.contains("SyncWatermark.nextThrough(watermark,rows.map{row->row.updatedAt})")
        )
    }

    @Test
    fun `a cycle only advances a mark it applied under the profile it started with`() {
        val advances = Regex("if\\(currentProfileId\\(\\)==pid\\)\\{").findAll(flat).count()
        assertTrue(
            "each table guards its advance with the profile it started with (found $advances)",
            advances >= 3
        )
    }

    @Test
    fun `marks are per table and per profile, and a full resync drops them`() {
        assertTrue(
            "the key carries both, with an explicit legacy spelling for a null profile",
            flat.contains(":String=\"\$PULL_WATERMARK_PREFIX\${table}_\${pid?:\"legacy\"}\"")
        )
        val resyncAt = sync.indexOf("fun forceFullResync(")
        val clearAt = sync.indexOf("clearPullWatermarks(context)", resyncAt)
        val pullAt = sync.indexOf("pullAllNow(context)", resyncAt)
        assertTrue(
            "a full resync forgets them before it pulls",
            resyncAt >= 0 && clearAt > resyncAt && pullAt > clearAt
        )
    }

    private fun squash(source: String): String = source.replace(Regex("\\s+"), "")

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val SYNC = "data/sync/SupabaseSync.kt"
    }
}
