package com.kennyb1201.kbstream.data.iptv

/**
 * The sources Playlist Setup lists, and the rule that takes one back out.
 *
 * A view over the storage that already exists - the primary `playlist_url` /
 * `playlist_name` pair and the `extra_playlist_urls` blob - so nothing here is
 * persisted and no migration rides on it. The text fields stay the add/edit
 * path; the rows are how a viewer SEES what is installed and removes one without
 * hand-editing a blob (field, 2026-10-24: "there was no way to remove a
 * playlist" - the only affordances were editing the text or CLEAR, which blanks
 * every field at once).
 *
 * The free lineup is deliberately NOT a row: it is an entry in the same extras
 * blob, but its own block in the form administers it (install state, and a
 * Remove that also strips its guide entry). Listing it here as well would give
 * it two Remove buttons with different behaviour.
 */
object PlaylistSources {

    /**
     * One installed source, as a row.
     *
     * @property url what the load path keys off - the primary's URL, or the
     * extras entry's URL.
     * @property label what the row says: the typed name when there is one,
     * else the URL (a source with no name is still a source).
     * @property isMain true for the primary slot, false for an extra.
     */
    data class Source(
        val url: String,
        val label: String,
        val isMain: Boolean
    )

    /**
     * Every installed source, primary first, then the extras in stored order.
     *
     * Blanks contribute nothing (an empty slot is not a source), and the free
     * lineup's entry is dropped - see the type's note on why it is not a row.
     */
    fun rows(
        playlistUrl: String,
        playlistName: String,
        extraPlaylistUrls: String
    ): List<Source> {
        val primaryUrl = playlistUrl.trim()
        val rows = ArrayList<Source>()
        if (primaryUrl.isNotEmpty()) {
            val name = playlistName.trim()
            rows += Source(
                url = primaryUrl,
                label = name.ifEmpty { primaryUrl },
                isMain = true
            )
        }
        entries(extraPlaylistUrls).forEach { entry ->
            if (FreeLineup.isFreeLineupEntry(entry)) return@forEach
            val url = urlOf(entry)
            if (url.isEmpty()) return@forEach
            val name = nameOf(entry)
            rows += Source(
                url = url,
                label = name ?: url,
                isMain = false
            )
        }
        return rows
    }

    /**
     * The extras blob after removing the source at [url], or the blob unchanged
     * when it names nothing.
     *
     * Every entry carrying that URL goes, not just the first: two identical lines
     * are one source to every reader of this blob (channels are namespaced by
     * URL), and a Remove that left the duplicate behind would leave the row
     * standing - the report, again, one tap later.
     *
     * The separator is the blob's own (';' or newlines, both accepted on read),
     * so removing one source does not rewrite the viewer's field into a shape
     * they did not type. A blob holding exactly one entry becomes empty.
     */
    fun removeEntry(extraPlaylistUrls: String, url: String): String {
        val wanted = url.trim()
        if (wanted.isEmpty()) return extraPlaylistUrls
        val raw = extraPlaylistUrls.trim()
        if (raw.isEmpty()) return extraPlaylistUrls
        val separator = when {
            raw.contains('\n') -> '\n'
            raw.contains(';') -> ';'
            else -> null
        }
        val parts = if (separator == null) listOf(raw) else raw.split(separator)
        val kept = parts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { entry -> urlOf(entry).equals(wanted, ignoreCase = true) }
        if (kept.size == parts.count { it.trim().isNotEmpty() }) return extraPlaylistUrls
        return if (separator == null) "" else kept.joinToString(separator.toString())
    }

    private fun entries(raw: String): List<String> =
        raw.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** The URL half of a `<url>|<name>` entry. */
    private fun urlOf(entry: String): String =
        entry.substringBefore('|').trim()

    /** The `|name` half, or null when the entry carries none. */
    private fun nameOf(entry: String): String? =
        entry.substringAfter('|', "").trim().takeIf { it.isNotEmpty() }
}
