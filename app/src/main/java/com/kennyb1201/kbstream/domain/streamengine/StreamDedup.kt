package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream

/**
 * Collapses the same torrent, offered by more than one addon, into one row.
 *
 * Reported problem: a viewer configured several addons that all index the same
 * releases, and the picker showed the same file three times - once per addon -
 * with the same size, the same labels and the same hash. Nothing in the resolve
 * path deduplicated, so the list was three times as long as the choice it
 * offered, and moving between "rows" that are the same file wasted remote
 * presses.
 *
 * The identity is the TORRENT FILE, not the row: [Stream.infoHash] plus
 * [Stream.fileIdx]. Two addons serving the same info hash are serving the same
 * download, so their rows are the same choice; but the same hash with a
 * different `fileIdx` is a different file inside a multi-file torrent (episodes
 * of a season pack, a sample, an extra) and must stay a row of its own.
 *
 * A stream with no hash has no identity to share: a direct link is one host's
 * URL, not a swarm, and two of them are two different files whatever their
 * labels say. Those are never collapsed - each keeps its own place in the list.
 *
 * Nothing else is merged: two different torrents of the same release are still
 * two rows (they may differ in peers, in cache state, in whoever serves them),
 * and the survivor keeps its badges because it is the same row object.
 *
 * Applied in the resolve path AFTER the addon reorder (see
 * [SourceAddonPreference]), so the row that survives is the first in the order
 * the viewer would actually have picked from - the addon that last worked this
 * title, not whichever addon happened to answer first.
 */
internal object StreamDedup {

    /**
     * [streams] without the repeats of a torrent file already in it, in the
     * order given: the FIRST row of each torrent file survives.
     */
    fun collapse(streams: List<Stream>): List<Stream> {
        if (streams.size < 2) return streams
        return streams
            .withIndex()
            .distinctBy { (index, stream) -> torrentFileKey(stream, index) }
            .map { it.value }
    }

    /**
     * The identity of the torrent file a stream points at, or a key unique to
     * [index] when it points at none.
     *
     * The fallback is the stream's own position rather than an identity hash:
     * the contract here is "a hash-less stream can never collapse with another",
     * and a position is the one value in this list that is unique by
     * construction.
     */
    private fun torrentFileKey(stream: Stream, index: Int): Any {
        val hash = stream.infoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return hash?.let { "$it:${stream.fileIdx ?: 0}" } ?: index
    }
}
