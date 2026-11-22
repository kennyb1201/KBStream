package com.kennyb1201.kbstream.data.iptv.db

data class EpgProgramRow(
    val channelId: String,
    val title: String,
    val description: String?,
    val category: String?,
    val startUtcMillis: Long,
    val endUtcMillis: Long
)

/**
 * Keeps the first [limit] rows of each channel, preserving the query's order.
 *
 * The guide's per-channel reads used to do this in SQL with a correlated
 * `COUNT(*)` on every candidate row: O(programs²) per channel, and the count was
 * re-run for every row even though the only thing that changed was the row being
 * tested. The DAO now reads one ordered index range for the batch and trims
 * here, which is the same answer with one pass. The query's `ORDER BY` is what
 * makes "first" mean earliest (or latest, for the recent read), so this must
 * stay a filter over the delivered order rather than a sort.
 *
 * A non-positive [limit] keeps nothing, matching the SQL predicate it replaces
 * (`COUNT(*) < limit` is never true when limit is zero or negative).
 */
internal fun List<EpgProgramRow>.takePerChannel(limit: Int): List<EpgProgramRow> {
    if (limit <= 0) return emptyList()
    val kept = HashMap<String, Int>()
    val out = ArrayList<EpgProgramRow>(minOf(size, 256))
    for (row in this) {
        val count = kept[row.channelId] ?: 0
        if (count >= limit) continue
        kept[row.channelId] = count + 1
        out += row
    }
    return out
}
