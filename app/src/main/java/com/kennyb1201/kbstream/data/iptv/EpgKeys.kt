package com.kennyb1201.kbstream.data.iptv

import java.util.Locale

/**
 * Channel-key normalization for EPG matching, lifted out of [IptvRepository] so
 * the rules can be unit tested.
 *
 * These functions decide whether a playlist channel matches a guide channel,
 * and a wrong answer is invisible: the channel simply shows no programmes (or,
 * worse, borrows another channel's). M3U and XMLTV spellers disagree about
 * everything — case, `[HD]`/`(US)` decorations, `&` vs "and", `+` vs "plus",
 * punctuation — so every one of those is stripped or expanded here rather than
 * left to a fuzzy comparison.
 *
 * Note the deliberate difference from [simplifyEpgChannelName]: this keeps dots
 * (channel ids like `bbc1.uk` stay addressable) while the alias builder drops
 * every non-alphanumeric character.
 */
internal fun epgLookupKey(value: String): String =
    value.trim()
        .lowercase(Locale.US)
        .replace(BRACKETED_TEXT, " ")
        .replace(PARENTHESIZED_TEXT, " ")
        .replace("&", " and ")
        .replace("+", " plus ")
        .replace(NON_LOOKUP_CHARACTERS, "")
        .trim()

/**
 * Every spelling under which a guide channel should be findable: the raw id, a
 * normalized id, each display name, its normalized form, and its simplified
 * form (decorations and quality qualifiers removed). Blank entries are dropped
 * and duplicates collapse, so a name repeated across spellings costs one key.
 */
internal fun epgAliasKeys(id: String, displayNames: List<String>): List<String> {
    val keys = LinkedHashSet<String>(2 + displayNames.size * 3)

    fun add(value: String?) {
        value?.trim()?.takeIf { it.isNotBlank() }?.let(keys::add)
    }

    add(id)
    add(normalizeEpgChannelKey(id))
    displayNames.forEach { name ->
        add(name)
        add(normalizeEpgChannelKey(name))
        add(simplifyEpgChannelName(name))
    }
    return keys.toList()
}

internal fun normalizeEpgChannelKey(value: String): String =
    value.trim().lowercase(Locale.US)

/**
 * The key an imported programme is STORED under, and therefore the only key a
 * program query may ask with.
 *
 * [XmltvImporter] writes `<programme channel=...>` through this function, so
 * `epg_programs.channelId` is always the trimmed, lowercased spelling. The
 * guide's own channel rows keep the raw `<channel id=...>` untouched, and the
 * DAO matches `channelId IN (:channelIds)` exactly (SQLite is case-sensitive
 * for TEXT unless a column says otherwise -- this one does not).
 *
 * So handing a program query the guide channel's raw id silently returns
 * nothing whenever the guide spells that id with any uppercase letter
 * (`ESPN.us`, `BBC.UK`, `Discovery.HD`), while its programmes are imported and
 * sitting in the database: the channel shows "No program data" forever. That
 * is the whole reason this function exists next to [epgLookupKey]: matching is
 * deliberately fuzzy, but reading back what was written has to be exact.
 */
internal fun epgProgramChannelKey(value: String): String =
    value.trim().lowercase(Locale.US)

/**
 * Aggressive form used only for alias keys: strips bracket/parenthesis
 * decorations, quality qualifiers (HD, 4K, HEVC, ...), expands `+`, and drops
 * everything that is not alphanumeric — "BBC One HD (East)" becomes "bbcone".
 * Returns null when nothing is left, so callers never key on an empty string.
 */
internal fun simplifyEpgChannelName(value: String): String? {
    val simplified = value
        .lowercase(Locale.US)
        .replace(BRACKETED_TEXT, " ")
        .replace(PARENTHESIZED_TEXT, " ")
        .replace(CHANNEL_QUALIFIERS, " ")
        .replace("+", " plus ")
        .replace(NON_ALPHANUMERIC, "")
        .trim()
    return simplified.ifBlank { null }
}

/** Which of the matcher's passes produced a match. */
internal enum class EpgMatchKind { ID, NAME, SIMPLIFIED }

/**
 * Picks the guide channel for one playlist channel, trying the spellings in the
 * order that loses the least information first.
 *
 *  1. **The channel's own id** (`tvg-id` / provider id) against the guide's
 *     channel ids — exact, so it wins whenever it hits.
 *  2. **The display names as spelled**, normalized (case/decoration/punctuation
 *     only). This keeps `Sky Sports 1` separate from `Sky Sports 2`.
 *  3. **The names simplified**, with quality qualifiers stripped, so
 *     `BBC One HD` still finds `BBC One`. This pass is last on purpose: it
 *     folds `HD`, `US`, `4K` and the like, which can rarely collide two
 *     genuinely different channels, so it only runs when nothing above matched.
 *
 * Without pass 3 the alias keys built by [epgAliasKeys] were half-wired: the
 * guide's simplified spelling was stored, but the playlist side was only ever
 * looked up by its normalized spelling, so a channel carrying a qualifier the
 * guide did not (`ESPN2 HD` vs `ESPN2`) showed "no program data" forever.
 *
 * Returns the matched value together with the pass that matched it, or null.
 */
internal fun <T> matchEpgChannel(
    idCandidates: List<String?>,
    nameCandidates: List<String?>,
    byId: Map<String, T>,
    byName: Map<String, T>
): Pair<T, EpgMatchKind>? {
    for (candidate in idCandidates) {
        val key = candidate?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::epgLookupKey)
            ?: continue
        byId[key]?.let { return it to EpgMatchKind.ID }
    }

    for (candidate in nameCandidates) {
        val key = candidate?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::epgLookupKey)
            ?: continue
        byName[key]?.let { return it to EpgMatchKind.NAME }
    }

    for (candidate in nameCandidates) {
        val key = candidate?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::simplifyEpgChannelName)
            ?: continue
        byName[key]?.let { return it to EpgMatchKind.SIMPLIFIED }
    }

    return null
}

/**
 * Every key the matcher could look a playlist channel up under, in one set.
 *
 * [matchEpgChannel] probes a playlist channel's ids (tvg-id, provider id) and
 * its names (tvg-name, display name, name) against the guide's two indexes.
 * This collects the same probes in the same normalized forms, so the importer
 * can ask "could this guide channel ever be reached?" without a playlist in
 * hand ([guideChannelCanMatch]).
 *
 * An empty result means "no playlist known": callers must read that as "cannot
 * decide" and keep everything, never as "nothing matches".
 */
internal fun playlistEpgMatchKeys(channels: List<IptvChannel>): Set<String> {
    if (channels.isEmpty()) return emptySet()

    val keys = HashSet<String>(channels.size * 6)
    channels.forEach { channel ->
        // Pass 1: the ids, normalized exactly as the matcher normalizes them.
        listOf(channel.tvgId, channel.providerChannelId).forEach { candidate ->
            candidate?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { keys.add(epgLookupKey(it)) }
        }
        // Passes 2 and 3: the names as spelled, and simplified with quality
        // qualifiers stripped - the two spellings the matcher tries.
        listOf(channel.tvgName, channel.displayName, channel.name).forEach { candidate ->
            candidate?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { name ->
                    keys.add(epgLookupKey(name))
                    simplifyEpgChannelName(name)?.let(keys::add)
                }
        }
    }
    return keys
}

/**
 * Whether a guide channel could ever be matched by a playlist carrying
 * [playlistKeys], which is what decides whether its programmes are worth
 * importing. True when [playlistKeys] is empty - "cannot decide" keeps the
 * old keep-everything behaviour.
 *
 * Deliberately a SUPERSET test: it may keep a guide channel that would not
 * have matched, but it must never drop one that would. Both indexes the
 * matcher reads are mirrored here - `byId` is keyed by [epgLookupKey] of the
 * channel id, `byName` by [epgLookupKey] of every alias key - so a channel
 * whose ids or names intersect the playlist's probes is kept, and one whose do
 * not could only ever have shown programmes no playlist row could reach.
 */
internal fun guideChannelCanMatch(
    channelId: String,
    aliasKeys: List<String>,
    playlistKeys: Set<String>
): Boolean {
    if (playlistKeys.isEmpty()) return true
    if (epgLookupKey(channelId) in playlistKeys) return true
    return aliasKeys.any { alias -> epgLookupKey(alias) in playlistKeys }
}

/**
 * Fingerprint of everything a guide query reads out of a playlist: the source
 * URLs plus the identity of the channels currently loaded into the guide
 * window. A background playlist refresh almost always returns the same
 * channels, and treating it as a change wiped the loaded guide and re-ran the
 * whole lineup query (snapshot rebuild + every program batch) for rows that
 * could not have differed — visible as two identical `LINEUP QUERY` passes a
 * couple of seconds apart on guide entry.
 *
 * Callers compare the fingerprint taken before and after a refresh: equal
 * means the loaded guide is still valid. Returns null when nothing is loaded,
 * because then there is nothing to compare against (and the requery is cheap).
 */
internal fun guideWindowFingerprint(
    sourceUrl: String?,
    guideUrls: List<String>,
    channelIds: Set<String>,
    channels: List<IptvChannel>
): String? {
    if (channelIds.isEmpty()) return null

    val builder = StringBuilder(64 + channelIds.size * 64)
    builder.append(sourceUrl.orEmpty()).append(SEP_FIELD)
        .append(guideUrls.joinToString(",")).append(SEP_FIELD)

    var found = 0
    for (channel in channels) {
        if (channel.id !in channelIds) continue
        found++
        // Everything the matcher reads, and nothing else: a logo or stream URL
        // change must not invalidate programmes that are already loaded.
        builder.append(channel.id).append(SEP_PART)
            .append(channel.name).append(SEP_PART)
            .append(channel.displayName).append(SEP_PART)
            .append(channel.groupTitle.orEmpty()).append(SEP_PART)
            .append(channel.tvgId.orEmpty()).append(SEP_PART)
            .append(channel.tvgName.orEmpty()).append(SEP_FIELD)
    }

    // A requested channel that vanished from the playlist shrinks the window,
    // which is a real change even when every surviving channel is identical.
    builder.append(SEP_FIELD).append(found)
    return builder.toString()
}

private const val SEP_FIELD = '\u0000'
private const val SEP_PART = '\u0001'

private val BRACKETED_TEXT = Regex("""\[[^\]]*]""")
private val PARENTHESIZED_TEXT = Regex("""\([^)]*\)""")
private val NON_LOOKUP_CHARACTERS = Regex("""[^a-z0-9.]+""")
private val NON_ALPHANUMERIC = Regex("""[^a-z0-9]+""")
private val CHANNEL_QUALIFIERS = Regex(
    """\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps|usa|us|uk|ca|au)\b"""
)
