package com.kennyb1201.kbstream.data.debrid

import com.kennyb1201.kbstream.data.addon.Stream

/**
 * The cached-only rule for the picker's list: a row survives when TorBox said
 * it holds the row's torrent, or when the row has no infohash to ask about.
 *
 * Pure, and shared by the listing (the ViewModel's badge-pass site) and its
 * tests. Two deliberate shapes:
 *
 *  - **Hash rows only.** A row with no infohash is a direct link, an AIOStreams
 *    row, or a debrid add-on that already resolved the torrent to a CDN link -
 *    something that plays as it stands. Hiding it would remove a working source,
 *    and the check never judged it (see [TorBoxClient.checkCached]'s own note
 *    about a chip-less picker).
 *  - **A hash the check would not look up is not a hash.** [lookupHash] is the
 *    same normalization the check itself applies, so a row carrying a
 *    malformed/short "hash" is treated as a direct row: it cannot be looked up,
 *    so it was never judged, so it is not hidden.
 *
 * Deliberately says nothing about WHEN it applies: the setting, the check's
 * availability and the answer's generation are the caller's business (see
 * StreamsViewModel's publish path), so switching the setting off cannot leave a
 * half-filtered list behind.
 */
internal object TorBoxCachedOnly {

    /** A hash shorter than this cannot be a torrent infohash; the check skips it. */
    private const val MIN_HASH_LENGTH = 32

    /**
     * The hash this row's infohash normalizes to, or null when the check would
     * never query it (blank, absent, or too short to be an infohash).
     */
    fun lookupHash(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.length >= MIN_HASH_LENGTH }

    /**
     * Whether [stream] is shown once [cachedHashes] (a TorBox ANSWERED set) is
     * applied. Rows the check could not judge are kept.
     */
    fun keeps(stream: Stream, cachedHashes: Set<String>): Boolean {
        val hash = lookupHash(stream.infoHash) ?: return true
        return hash in cachedHashes
    }

    /** [streams] with the rule applied, order preserved. */
    fun rows(streams: List<Stream>, cachedHashes: Set<String>): List<Stream> =
        streams.filter { keeps(it, cachedHashes) }
}
