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
 *
 * A last, REPORT-ONLY section counts guide channels whose names carry a `by
 * <network>` suffix and that nothing resolved, plus how many unmatched rows a
 * suffix strip would reach ([GuideNetworkSuffixRow]). Nothing in the matcher
 * reads it; it exists so a guide-side rule can be decided on numbers.
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
    val byName: Map<String, T>,
    /**
     * The guide channel's own id and display names, for the report-only
     * guide-side-suffix section (none of it is used to MATCH anything).
     *
     * A lambda rather than a constraint on `T` because attribution deliberately
     * keeps the repository's entity maps unconverted (see the class doc); null
     * means "this caller cannot describe its guide channels", and the section is
     * then simply left out.
     */
    val identityOf: ((T) -> GuideChannelIdentity)? = null
)

/** What the report needs to know about one guide channel, and nothing else. */
internal data class GuideChannelIdentity(
    val id: String,
    val displayNames: List<String>
)

/**
 * One guide channel whose display name carries a network suffix (`Storage Wars
 * by A&E`) and that no playlist row resolved, plus the playlist row the name
 * would reach if that suffix were stripped.
 *
 * REPORT ONLY. Nothing in the matcher strips a guide-side suffix and nothing
 * here changes a verdict - this exists so a follow-up can be judged on numbers
 * instead of the single case that started the question (2026-10-25 field note:
 * `Pluto TV Storage Wars` against the guide's `Storage Wars by A&E`).
 *
 * [playlistName] is null when no unmatched row would reach it even with the
 * suffix stripped, which is the honest "this one is not a by-suffix case" shape
 * rather than an omission.
 */
data class GuideNetworkSuffixRow(
    val guideName: String,
    val sourceUrl: String,
    val playlistName: String?
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
    val rows: List<GuideMatchRow>,
    /**
     * Guide channels nothing resolved whose names carry a `by <network>` suffix,
     * and the unmatched rows a suffix strip would reach. Empty for a caller that
     * did not describe its guide channels (see [GuideMatchSource.identityOf]).
     */
    val networkSuffixCandidates: List<GuideNetworkSuffixRow> = emptyList(),
    /** Playlist rows a guide-side suffix strip would resolve; see [networkSuffixCandidates]. */
    val networkSuffixRescues: Int = networkSuffixCandidates.count { it.playlistName != null }
) {
    val channelCount: Int get() = rows.size
    val matchedCount: Int get() = rows.count { it.sourceUrl != null }
    val unmatchedCount: Int get() = channelCount - matchedCount

    /** How many channels this source is the FIRST to resolve. */
    fun matchedBy(url: String): Int = rows.count { it.sourceUrl == url }

    /**
     * The section, as report lines. Nothing else goes in it: the counts, the
     * unmatched list and the report-only suffix count are the whole deliverable,
     * and a matched-channel list would triple a capture nobody reads for it.
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
        // Last, and REPORT ONLY: what a guide-side `by <network>` strip would
        // reach. The count is the deliverable (a follow-up on one field case
        // should be judged on numbers), the rows are the evidence for it.
        if (networkSuffixCandidates.isNotEmpty()) {
            add(
                "guide-side \"by\" suffix (report only): " +
                    "unmatched-guide=${networkSuffixCandidates.size} " +
                    "would-rescue-playlist=$networkSuffixRescues"
            )
            networkSuffixCandidates.take(NETWORK_SUFFIX_EXAMPLES).forEach { candidate ->
                val would = candidate.playlistName
                    ?.let { " | would match \"$it\"" }
                    .orEmpty()
                add("- ${candidate.guideName} (${redactSource(candidate.sourceUrl)})$would")
            }
            val rest = networkSuffixCandidates.size - NETWORK_SUFFIX_EXAMPLES
            if (rest > 0) add("- … +$rest more")
        }
    }

    private companion object {
        /**
         * How many of the suffix rows a capture carries. Bounded on purpose: the
         * report is pasted into a chat, and the COUNT above it is what a follow-up
         * is judged on.
         */
        const val NETWORK_SUFFIX_EXAMPLES = 10
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
): GuideMatchReport {
    // Which guide channels a playlist row actually reached, per source - the
    // "no playlist row resolves this one" half of the suffix section below.
    val resolvedGuideIds = HashMap<String, MutableSet<String>>()

    val rows = channels.map { channel ->
        val idCandidates = channel.epgIdCandidates()
        val nameCandidates = channel.epgNameCandidates()
        val callsignCandidates = channel.epgCallsignCandidates()
        val matched = sources.firstNotNullOfOrNull { source ->
            matchEpgChannel(
                idCandidates = idCandidates,
                nameCandidates = nameCandidates,
                byId = source.byId,
                byName = source.byName,
                callsignCandidates = callsignCandidates
            )?.let { source to it.first }
        }
        matched?.let { (source, guideChannel) ->
            source.identityOf?.invoke(guideChannel)?.let { identity ->
                resolvedGuideIds.getOrPut(source.url) { HashSet() }.add(identity.id)
            }
        }
        GuideMatchRow(
            displayName = channel.displayName.takeIf { it.isNotBlank() } ?: channel.name,
            tvgId = channel.tvgId?.trim()?.takeIf { it.isNotEmpty() },
            groupTitle = channel.groupTitle?.trim()?.takeIf { it.isNotEmpty() },
            sourceUrl = matched?.first?.url
        )
    }

    return GuideMatchReport(
        sources = sources.map { it.url },
        rows = rows,
        networkSuffixCandidates = networkSuffixCandidates(rows, sources, resolvedGuideIds)
    )
}

/**
 * The guide channels a `by <network>` suffix would explain, for the report's
 * last section.
 *
 * Counted, never applied: the matcher is untouched, so this can mislead nobody
 * about a channel that already has programs. For each source that can describe
 * its channels, it takes the channels nothing resolved whose display name ends in
 * ` by <something>`, strips that suffix, and asks whether any UNMATCHED playlist
 * row's own name forms would reach the stripped base - so
 * `Pluto TV Storage Wars` counts as reachable from `Storage Wars by A&E` while a
 * row with no relationship to the base does not.
 *
 * Produced only when at least one row is unmatched: the section explains a blank
 * row, and inventorying a healthy profile's guide would be noise in a capture
 * somebody reads on a phone.
 */
private fun <T> networkSuffixCandidates(
    rows: List<GuideMatchRow>,
    sources: List<GuideMatchSource<T>>,
    resolvedGuideIds: Map<String, Set<String>>
): List<GuideNetworkSuffixRow> {
    // Each unmatched row's key forms once, not once per candidate: a big guide
    // carries enough `by` names that recomputing them per candidate turned this
    // into a visible pause in a capture somebody is waiting to paste.
    val unmatched = rows.asSequence()
        .filter { it.sourceUrl == null }
        .map { it.displayName to it.playlistKeys() }
        .toList()
    if (unmatched.isEmpty()) return emptyList()

    val out = ArrayList<GuideNetworkSuffixRow>()
    sources.forEach { source ->
        val identityOf = source.identityOf ?: return@forEach
        val resolved = resolvedGuideIds[source.url].orEmpty()
        // One row per guide channel, however many names it is indexed under.
        val seen = HashSet<String>()
        source.byName.values.forEach { guideChannel ->
            val identity = identityOf(guideChannel)
            if (identity.id in resolved || !seen.add(identity.id)) return@forEach
            if (identity.displayNames.isEmpty()) return@forEach
            val suffixMatch = identity.displayNames.firstNotNullOfOrNull { name ->
                TRAILING_NETWORK_SUFFIX.find(name.trim())?.let { match ->
                    name.trim().substring(0, match.range.first).trim().takeIf { it.isNotBlank() }
                }
            } ?: return@forEach
            val baseKeys = buildSet {
                add(epgLookupKey(suffixMatch))
                simplifyEpgChannelName(suffixMatch)?.let(::add)
            }
            val reachable = unmatched.firstOrNull { (_, keys) -> keys.any { it in baseKeys } }
            out += GuideNetworkSuffixRow(
                guideName = identity.displayNames.first(),
                sourceUrl = source.url,
                playlistName = reachable?.first
            )
        }
    }
    return out
}

/**
 * The key forms an unmatched playlist row brings to the matcher, used ONLY to
 * answer "would a suffix-stripped guide name reach this row?".
 *
 * Same forms the matcher probes (name as spelled, simplified, numbered and
 * affix-stripped), spelled through the same helpers - a report that disagreed
 * with the matcher about what a row's name is would answer the wrong question.
 */
private fun GuideMatchRow.playlistKeys(): Set<String> = buildSet {
    add(epgLookupKey(displayName))
    simplifyEpgChannelName(displayName)?.let(::add)
    addAll(numberedVariantKeys(displayName))
    addAll(platformAffixKeys(displayName))
}

/** ` by <network>` at the END of a guide name - the prefix before it is the base. */
private val TRAILING_NETWORK_SUFFIX = Regex("""\s+by\s+\S.*$""", RegexOption.IGNORE_CASE)

/** `-` for a field the playlist left empty, so every row keeps its shape. */
private fun String?.orAbsent(): String = this?.takeIf { it.isNotBlank() } ?: "-"
