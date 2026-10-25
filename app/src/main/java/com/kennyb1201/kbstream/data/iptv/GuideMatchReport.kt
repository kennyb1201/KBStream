package com.kennyb1201.kbstream.data.iptv

/**
 * The guide's per-channel verdicts, as COPY DIAGNOSTICS prints them.
 *
 * Field report (2026-10-25, phone, Free TV on the guest profile): after kanya +
 * locals + a hand-added Roku feed, Midsomer Murders HAD program data (the Roku
 * feed proven working) while American Horrors stayed blank. Answering "so what
 * are we missing?" took a per-channel test, because every guide line the report
 * could print was per SOURCE - channels stored, programs in the window - and
 * none of them said which playlist channels those programs belong to. A capture
 * of blank Guide rows cannot be turned into a list of missing channels from the
 * outside either: it would mean downloading the playlist and six gzipped
 * guides, which is neither the viewer's device nor the viewer's import state.
 *
 * This is the export instead - counts, the per-source attribution, and every
 * unmatched channel. It reuses [matchEpgChannel], the same matcher the lineup
 * query resolves a channel with, so a capture can never disagree with the grid
 * about what matched. It lists NO stream URL: an Xtream-style playlist puts the
 * account's username and password in the stream path, and this text is meant to
 * be pasted into a chat.
 */

/**
 * One configured guide source's two lookup indexes, exactly as the lineup query
 * builds them (`byId` keyed by [epgLookupKey] of the channel id, `byName` keyed
 * by the same of every alias key).
 *
 * Generic over the indexed value because attribution only needs [matchEpgChannel]'s
 * verdict, not the guide channel itself - the repository passes its own
 * [com.kennyb1201.kbstream.data.iptv.db.EpgChannelEntity] maps unconverted (the
 * alternative would be materialising 14k display-name lists to throw them away)
 * and a test passes whatever it likes.
 */
internal class GuideMatchSource<T>(
    val url: String,
    val byId: Map<String, T>,
    val byName: Map<String, T>
)

/** One playlist channel's verdict: which source resolved it, if any. */
data class GuideMatchRow(
    val displayName: String,
    val tvgId: String?,
    val groupTitle: String?,
    /** The first source in configured order that resolves this channel; null when none does. */
    val sourceUrl: String?
)

/**
 * What the guide matched, for the diagnostics section.
 *
 * [channels] is the playlist's own channel list - the same rows the Guide lists -
 * so `matched + unmatched == channels` by construction: every row is either
 * attributed to a source or is an unmatched row. Kept as one list rather than two
 * so the two counts cannot drift apart.
 */
data class GuideMatchReport(
    val sources: List<String>,
    val rows: List<GuideMatchRow>
) {
    val channelCount: Int get() = rows.size
    val matchedCount: Int get() = rows.count { it.sourceUrl != null }
    val unmatchedCount: Int get() = channelCount - matchedCount

    /** How many channels this source is the FIRST to resolve. */
    fun matchedBy(url: String): Int = rows.count { it.sourceUrl == url }

    /**
     * The section, as report lines. Nothing else goes in it: the counts and the
     * unmatched list are the whole deliverable, and a matched-channel list would
     * triple a capture nobody reads for it.
     *
     * [redactSource] is the caller's redaction (`Redaction.url`, the same one the
     * guide line above uses) - a source URL can carry an Xtream account in its
     * query string. Uniform [url]=N pairs are printed even for a source that
     * matched nothing, because that is how a dead or never-landed source shows up
     * instead of silently going absent.
     */
    fun lines(redactSource: (String) -> String = { it }): List<String> = buildList {
        add("guide-match: channels=$channelCount matched=$matchedCount unmatched=$unmatchedCount")
        add(
            "guide-match by source: " +
                sources.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { url -> "${redactSource(url)}=${matchedBy(url)}" }
                    .orEmpty()
                    .ifEmpty { "none" }
        )
        val unmatched = rows.filter { it.sourceUrl == null }
        if (unmatched.isNotEmpty()) {
            add("guide-match unmatched:")
            unmatched.forEach { row ->
                add("- ${row.displayName} | tvg-id=${row.tvgId.orAbsent()} | group=${row.groupTitle.orAbsent()}")
            }
        }
    }
}

/**
 * If no lineup is loaded there is nothing truthful to say beyond it - and the
 * section is never silently omitted, because "no channels at all" and "the
 * report forgot to look" would then read the same.
 */
internal const val NO_LINEUP_LINE = "guide-match: no lineup loaded"

/** The section's lines for [report], or the single line when there is no lineup. */
internal fun guideMatchSectionLines(
    report: GuideMatchReport?,
    redactSource: (String) -> String
): List<String> = report?.lines(redactSource) ?: listOf(NO_LINEUP_LINE)

/**
 * Attributes every playlist channel to the FIRST source that resolves it, in
 * configured order - the same first-match-wins rule [IptvRepository]'s lineup
 * query applies, so a channel kanya and Roku both carry counts under kanya once
 * and never twice.
 *
 * The matching itself is [matchEpgChannel], not a copy of it: this function only
 * builds the candidate lists (via the same helpers the lineup query uses) and
 * walks the sources. Anything else would be a second matcher that could disagree
 * with the grid about a channel the viewer is looking at.
 */
internal fun <T> attributeGuideMatches(
    channels: List<IptvChannel>,
    sources: List<GuideMatchSource<T>>
): GuideMatchReport = GuideMatchReport(
    sources = sources.map { it.url },
    rows = channels.map { channel ->
        val idCandidates = channel.epgIdCandidates()
        val nameCandidates = channel.epgNameCandidates()
        val callsignCandidates = channel.epgCallsignCandidates()
        val matchedBy = sources.firstOrNull { source ->
            matchEpgChannel(
                idCandidates = idCandidates,
                nameCandidates = nameCandidates,
                byId = source.byId,
                byName = source.byName,
                callsignCandidates = callsignCandidates
            ) != null
        }
        GuideMatchRow(
            displayName = channel.displayName.takeIf { it.isNotBlank() } ?: channel.name,
            tvgId = channel.tvgId?.trim()?.takeIf { it.isNotEmpty() },
            groupTitle = channel.groupTitle?.trim()?.takeIf { it.isNotEmpty() },
            sourceUrl = matchedBy?.url
        )
    }
)

/** `-` for a field the playlist left empty, so every row keeps its shape. */
private fun String?.orAbsent(): String = this?.takeIf { it.isNotBlank() } ?: "-"
