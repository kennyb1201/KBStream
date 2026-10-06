package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.iptv.GuideMatchQuery
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
 * `epgChannelId` or no guide source (an unmatched M3U entry) has nothing in the
 * program tables and would only widen the query. The keys go through
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
        val guideId = channel.epgChannelId?.trim().orEmpty()
        if (guideId.isEmpty()) continue
        // Every configured source, not just the channel's own: programs are
        // stored under whichever guide matched the channel, and a channel
        // matched in a SECONDARY source read as "No guide data" in the player
        // while the guide screen - which reads and merges every source - was
        // fully populated. Asking a source that has nothing for this channel
        // costs one indexed read that returns no rows.
        for (sourceUrl in guideSourcesOf(channel)) {
            bySource.getOrPut(sourceUrl) { mutableListOf() }.add(epgProgramChannelKey(guideId))
        }
    }
    return bySource.flatMap { (sourceUrl, ids) ->
        ids.distinct().chunked(batchSize).map { batch -> GuideQuery(sourceUrl, batch) }
    }
}

/**
 * The guide sources to ask about [channel], primary first, no blanks and no
 * repeats.
 *
 * [LiveChannelZapRegistry.ZapChannel.epgUrls] is the published list of every
 * configured source; [LiveChannelZapRegistry.ZapChannel.epgUrl] is the single
 * value older lineups carry, and is used on its own when no list was
 * published, so both shapes work.
 */
internal fun guideSourcesOf(
    channel: LiveChannelZapRegistry.ZapChannel
): List<String> =
    (channel.epgUrls + listOfNotNull(channel.epgUrl))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

/**
 * Whether an entry still needs its guide match resolved before it can be
 * queried: it carries no guide channel id, and it has the identity (source URL
 * plus an id or name) to resolve one.
 *
 * The guide screen publishes each channel's `epgChannelId` only once it has
 * matched the playlist against an imported guide. Click into a channel before
 * that import lands and the entry is unmatched, which is why the in-player
 * guide has to be able to resolve the match itself.
 */
internal fun needsGuideMatch(channel: LiveChannelZapRegistry.ZapChannel): Boolean =
    channel.epgChannelId.isNullOrBlank() && guideMatchQueriesFor(channel).isNotEmpty()

/**
 * The match query for a lineup entry's own guide identity against the FIRST
 * source it could match in, or null when the entry has nothing to resolve with
 * (no guide source, or no id/name at all).
 *
 * The candidates mirror the guide screen's matcher exactly - ids first
 * (`tvg-id`), then names (`tvg-name`, the display name) - so a channel resolves
 * to the same guide channel whichever side ran the match.
 */
internal fun guideMatchQueryFor(
    channel: LiveChannelZapRegistry.ZapChannel
): GuideMatchQuery? =
    guideSourcesOf(channel)
        .firstOrNull()
        ?.let { source -> guideMatchQueryForSource(channel, source) }

/**
 * One match query per published source, in order: a channel may be matched in
 * ANY configured guide, so the resolver has to be able to try each of them
 * before the entry is given up on (see the player's resolveMissingGuideMatches,
 * which keeps the first source that answers).
 */
internal fun guideMatchQueriesFor(
    channel: LiveChannelZapRegistry.ZapChannel
): List<GuideMatchQuery> =
    guideSourcesOf(channel).mapNotNull { source ->
        guideMatchQueryForSource(channel, source)
    }

internal fun guideMatchQueryForSource(
    channel: LiveChannelZapRegistry.ZapChannel,
    epgUrl: String
): GuideMatchQuery? {
    if (epgUrl.isEmpty()) return null
    if (channel.tvgId.isNullOrBlank() &&
        channel.tvgName.isNullOrBlank() &&
        channel.name.isBlank()
    ) {
        return null
    }
    return GuideMatchQuery(
        key = channel.channelId,
        epgUrl = epgUrl,
        idCandidates = listOf(channel.tvgId),
        nameCandidates = listOf(channel.tvgName, channel.name)
    )
}
