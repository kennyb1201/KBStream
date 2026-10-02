package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.epgProgramChannelKey

/**
 * What the guide overlay shows for one channel: the program airing now and the
 * one after it. Either half is null when the guide does not reach far enough
 * (a gap between programs, or a channel whose guide stops at "now").
 */
internal data class ChannelNowNext(
    val now: EpgProgramRow?,
    val next: EpgProgramRow?
)

/**
 * Buckets guide rows into one now/next pair per channel.
 *
 * Pure, so the rule the overlay paints by is testable without a database. The
 * rows are whatever a window query returned for a set of channels and may hold
 * more per channel than is wanted (the DAO's per-channel limit exists to bound
 * the read, not to pick the pair) - and they may arrive unordered, so each
 * channel's rows are sorted first.
 *
 * `now` is the program whose window CONTAINS the instant; `next` is the first
 * program that starts after it. A gap therefore reads as "nothing on now, this
 * is up next" rather than mislabelling the upcoming program as current, which
 * is what a simpler "first row" rule would do.
 */
internal fun nowNextByChannel(
    rows: List<EpgProgramRow>,
    nowMillis: Long
): Map<String, ChannelNowNext> {
    val out = HashMap<String, ChannelNowNext>()
    for ((channelId, channelRows) in rows.groupBy { it.channelId }) {
        val sorted = channelRows.sortedBy { it.startUtcMillis }
        out[channelId] = ChannelNowNext(
            now = sorted.firstOrNull {
                nowMillis >= it.startUtcMillis && nowMillis < it.endUtcMillis
            },
            next = sorted.firstOrNull { it.startUtcMillis > nowMillis }
        )
    }
    return out
}

/**
 * How many channel ids one guide query is handed at once.
 *
 * The read binds one variable per id plus two for the window, and SQLite caps
 * bind variables (999 on the older builds this app still supports), so a whole
 * playlist cannot go in one statement. Batching also keeps a large lineup's
 * read from being one enormous cursor build.
 */
internal const val CHANNEL_GUIDE_QUERY_BATCH = 120

/** One guide read: the EPG source to query and the channel keys to ask about. */
internal data class GuideQuery(
    val sourceUrl: String,
    val channelIds: List<String>
)

/**
 * Turns a zap lineup into the guide reads needed to fill the overlay.
 *
 * Only channels matched to a guide can be asked about at all: a channel with no
 * `epgChannelId`/`epgUrl` (an unmatched M3U entry) has nothing in the program
 * tables and would only widen the query. The keys go through
 * [epgProgramChannelKey] because that is the spelling the importer wrote - a
 * guide channel id carrying an uppercase letter reads back nothing otherwise.
 *
 * Queries are grouped per source, because the DAO resolves the source by URL
 * inside the statement, and batched per [CHANNEL_GUIDE_QUERY_BATCH] (overridable
 * for tests). Duplicate keys within a source collapse to one, so two lineup
 * entries matched to the same guide channel do not spend two bind variables on
 * identical rows.
 */
internal fun planGuideQueries(
    channels: List<LiveChannelZapRegistry.ZapChannel>,
    batchSize: Int = CHANNEL_GUIDE_QUERY_BATCH
): List<GuideQuery> {
    if (batchSize <= 0) return emptyList()
    val bySource = LinkedHashMap<String, MutableList<String>>()
    for (channel in channels) {
        val sourceUrl = channel.epgUrl?.trim().orEmpty()
        val guideId = channel.epgChannelId?.trim().orEmpty()
        if (sourceUrl.isEmpty() || guideId.isEmpty()) continue
        bySource.getOrPut(sourceUrl) { mutableListOf() }.add(epgProgramChannelKey(guideId))
    }
    return bySource.flatMap { (sourceUrl, ids) ->
        ids.distinct().chunked(batchSize).map { batch -> GuideQuery(sourceUrl, batch) }
    }
}
