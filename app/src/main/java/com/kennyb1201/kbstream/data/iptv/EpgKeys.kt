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

private val BRACKETED_TEXT = Regex("""\[[^\]]*]""")
private val PARENTHESIZED_TEXT = Regex("""\([^)]*\)""")
private val NON_LOOKUP_CHARACTERS = Regex("""[^a-z0-9.]+""")
private val NON_ALPHANUMERIC = Regex("""[^a-z0-9]+""")
private val CHANNEL_QUALIFIERS = Regex(
    """\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps|usa|us|uk|ca|au)\b"""
)
