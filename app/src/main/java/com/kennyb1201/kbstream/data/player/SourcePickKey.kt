package com.kennyb1201.kbstream.data.player

/**
 * The two identities a remembered source pick needs (spec 2026-11-15): which
 * EPISODE the pick belongs to, and which ROW it was.
 *
 * Free of Android and of [com.kennyb1201.kbstream.data.addon.Stream] on purpose,
 * like [com.kennyb1201.kbstream.data.memory.LandscapeArtEmptyStore]: these are
 * the rules the spec calls most likely to be wrong - a key that names the wrong
 * episode leaks a pick into the next one, and one that names the wrong file is
 * worse than no memory at all - so they are the ones worth unit testing rather
 * than discovering on a TV.
 */
internal object SourcePickKey {

    /**
     * The episode a pick belongs to, or null when the show is unknown.
     *
     * A show with no id gets no key: a key that cannot name its episode would
     * answer for whichever episode came next. Season and episode are part of the
     * key, so S1E1's pick can never be read for S1E2; a movie (no season, no
     * episode) is just its own id, in a shape no episode key can collide with.
     */
    fun episodeKey(parentId: String?, season: Int?, episode: Int?): String? {
        val parent = parentId?.trim().orEmpty()
        if (parent.isEmpty()) return null
        val show = sanitize(parent)
        return if (season != null && episode != null) "$show|s$season|e$episode" else "$show|"
    }

    /**
     * The address that identifies a row across two resolves of the same
     * episode, or null for a row that has neither a URL nor a hash.
     *
     * A row with a URL keeps the URL - that covers direct HTTP rows and the
     * TorBox cloud rows, whose URL is the `kbstream-torbox-cloud://torrentId/
     * fileId` reference they are drawn from. Everything else identifies by
     * infohash plus file index, because one torrent holds every episode of a
     * season and the index is what says which file was picked.
     */
    fun identityOf(url: String?, infoHash: String?, fileIdx: Int?): String? {
        val link = url?.trim().orEmpty()
        if (link.isNotEmpty()) return "u:$link"
        val hash = infoHash?.trim().orEmpty()
        if (hash.isEmpty()) return null
        return "h:${hash.lowercase()}:${fileIdx ?: -1}"
    }

    /**
     * Whether [url] is the same file as the stored `u:` [identity], ignoring the
     * query and fragment - the part a host re-signs per session, which would
     * otherwise make a valid pick look gone.
     *
     * Deliberately narrow: only URL identities compare this way, and both sides
     * must have a host AND a path, so a bare host or a scheme alone can never
     * match two different files.
     */
    fun sameAddress(url: String?, identity: String): Boolean {
        if (!identity.startsWith("u:")) return false
        val mine = addressOf(url) ?: return false
        val theirs = addressOf(identity.removePrefix("u:")) ?: return false
        return mine == theirs
    }

    /** `scheme://host/path` of [url], query, fragment and trailing slash gone. */
    fun addressOf(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        val withoutQuery = value.substringBefore('?').substringBefore('#')
        val separator = withoutQuery.indexOf("://")
        if (separator <= 0) return null
        val afterScheme = withoutQuery.substring(separator + 3)
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return null
        val host = afterScheme.substring(0, slash)
        val path = afterScheme.substring(slash).trimEnd('/')
        if (host.isEmpty() || path.isEmpty()) return null
        return "${withoutQuery.substring(0, separator)}://$host$path"
    }

    private fun sanitize(value: String): String =
        value.replace('\u001F', ' ').replace('\n', ' ').replace('\r', ' ')
}
