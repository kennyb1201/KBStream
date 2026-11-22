package com.kennyb1201.kbstream.data.iptv

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The query plans behind the guide's hot reads, compact enough for a paste.
 *
 * The guide's per-channel reads were changed from a correlated `COUNT(*)` per
 * candidate row to one ordered index range plus [takePerChannel]. The plan is
 * the evidence that the SQL still seeks instead of scanning, and it is reported
 * beside the guide's state because a plan that says `SCAN epg_programs` is a
 * different field bug from an empty or stale guide.
 *
 * This is a measurement, not a query result: it runs `EXPLAIN QUERY PLAN` only,
 * never the reads themselves, so a diagnostics capture does not pay the 129MB
 * guide's row cost. The SQL mirrors the DAO's statements; the plan labels name
 * which one is which. Keep them in step when a read changes — a plan of an old
 * statement would say the new one is healthy when it is not.
 */
internal object EpgQueryPlans {

    /** One compact `label=detail` section per hot read. */
    fun line(db: SupportSQLiteDatabase): String {
        val started = System.nanoTime()
        val parts = QUERIES.map { (label, sql) ->
            "$label=${plan(db, sql) ?: "unavailable"}"
        }
        val ms = (System.nanoTime() - started) / 1_000_000L
        return "guide: plan ${parts.joinToString(" | ")} | explainMs=$ms"
    }

    private fun plan(db: SupportSQLiteDatabase, sql: String): String? {
        val details = ArrayList<String>(4)
        return runCatching {
            db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                val detailIndex = cursor.getColumnIndex("detail").takeIf { it >= 0 } ?: 3
                while (cursor.moveToNext()) {
                    cursor.getString(detailIndex)?.let(details::add)
                }
            }
            details.joinToString("; ").ifBlank { null }
        }.getOrNull()
    }

    /**
     * `'plan'` stands in for a channel id: the plan depends on the shape of the
     * `IN` list, not its values. `SELECT id FROM epg_sources LIMIT 1` stands in
     * for the URL scalar subquery for the same reason.
     */
    private val QUERIES: List<Pair<String, String>> = listOf(
        "lite" to """
            SELECT
                p1.channelId AS channelId,
                p1.title AS title,
                '' AS description,
                '' AS category,
                p1.startUtcMillis AS startUtcMillis,
                p1.endUtcMillis AS endUtcMillis
            FROM epg_programs p1
            WHERE p1.sourceId = (SELECT id FROM epg_sources LIMIT 1)
              AND p1.endUtcMillis > 0
              AND p1.startUtcMillis < 1
              AND p1.channelId IN ('plan')
            ORDER BY p1.channelId ASC, p1.startUtcMillis ASC, p1.rowid ASC
        """.trimIndent(),
        "recent" to """
            SELECT
                p1.channelId AS channelId,
                p1.title AS title,
                p1.description AS description,
                p1.category AS category,
                p1.startUtcMillis AS startUtcMillis,
                p1.endUtcMillis AS endUtcMillis
            FROM epg_programs p1
            WHERE p1.sourceId = (SELECT id FROM epg_sources LIMIT 1)
              AND p1.endUtcMillis <= 1
              AND p1.endUtcMillis > 0
              AND p1.channelId IN ('plan')
            ORDER BY p1.channelId ASC, p1.startUtcMillis DESC, p1.rowid ASC
        """.trimIndent(),
        "full" to """
            SELECT
                p1.channelId AS channelId,
                p1.title AS title,
                p1.description AS description,
                p1.category AS category,
                p1.startUtcMillis AS startUtcMillis,
                p1.endUtcMillis AS endUtcMillis
            FROM epg_programs p1
            WHERE p1.sourceId = (SELECT id FROM epg_sources LIMIT 1)
              AND p1.endUtcMillis > 0
              AND p1.startUtcMillis < 1
              AND p1.channelId IN ('plan')
            ORDER BY p1.channelId ASC, p1.startUtcMillis ASC, p1.rowid ASC
        """.trimIndent(),
        "maxEnd" to """
            SELECT MAX(endUtcMillis) FROM epg_programs
            WHERE sourceId = (SELECT id FROM epg_sources LIMIT 1)
        """.trimIndent()
    )
}
