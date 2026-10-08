package com.kennyb1201.kbstream.domain.streamengine

/**
 * The addons whose streams are debrid-served by construction.
 *
 * A debrid-first addon does not serve bytes: it hands over a torrent infoHash and
 * expects the viewer's own account to resolve it. That makes its entries
 * debrid-served in the only sense that matters to the ranker - the file will
 * come off a CDN the viewer already pays for - while leaving no debrid host in
 * the URL and no completion tag in the text, because the resolution has not
 * happened yet. Both of [StreamRanker]'s URL-based signals therefore stay silent,
 * the entry is scored on its labels alone, and it loses to a finicky hoster link
 * with a better-looking one (the reported case: AIOStreams 193 under
 * Flix-Streams 195).
 *
 * The list is deliberately tiny and deliberate: an addon goes in when it is
 * VERIFIED to be debrid-first, never because its name sounds like it. A wrong
 * entry here promotes links that may not resolve at all, which is worse than the
 * mis-ordering it was meant to fix. AIOStreams is the reported addon;
 * Torrentio-style addons are NOT listed because they hand back resolved links
 * that name the service themselves, so the URL rule already covers them, and the
 * many "debrid wrapper" manifests vary per instance and are not verified.
 *
 * Matching is on the normalized name (see
 * [SourceAddonPreference.normalize]), which is what the caller can supply: the
 * resolve path carries each stream's addon as the name the picker shows, and an
 * addon's manifest id and its display name normalize to the same key only when
 * they are the same word - "AIOStreams" and "aiostreams" do. A decorated display
 * name ("AIOStreams | ElfHosted") does not match, which is the safe way to be
 * wrong: it falls back to exactly today's behaviour.
 */
internal object DebridAddons {

    /**
     * Addon ids/names that are debrid-first, in the spelling their manifest uses.
     *
     * Normalization makes the case and punctuation of each entry irrelevant, so
     * one entry covers "AIOStreams", "aiostreams" and "AIO-Streams".
     */
    val KNOWN_DEBRID_ADDON_IDS: Set<String> = setOf("aiostreams")

    /**
     * The same ids in the form [StreamRanker.rank] compares against:
     * normalized, ready for a plain set-membership test.
     *
     * One function so the ranker's `debridAddons` parameter and any future
     * consumer cannot disagree about how a known addon is spelled.
     */
    fun normalizedIds(): Set<String> =
        KNOWN_DEBRID_ADDON_IDS.mapTo(mutableSetOf()) { SourceAddonPreference.normalize(it) }
}
