package com.kennyb1201.kbstream.data.iptv

/**
 * The free lineup a profile with no playlist can start from.
 *
 * iptv-org publishes, in the public domain (Unlicense), the streams broadcasters
 * themselves put online - national news, local affiliates, FAST channels - with
 * an automated tester keeping the list clean. Its endpoints are stable GitHub
 * Pages URLs, so they are constants here like any other API base: nothing is
 * hosted by KBStream, and the app only links the streams those broadcasters
 * publish. If iptv-org ever re-schemes, an app update re-points these two URLs.
 *
 * It installs as an ORDINARY EXTRA SOURCE, never as the primary playlist: the
 * extras already merge with namespaced channel ids (exactly as a second provider
 * playlist does), so the free channels sit alongside anything the viewer adds
 * later and no id can collide with theirs.
 *
 * What it is NOT is a replacement for a real playlist. Cable and RSN games stay
 * "not in your playlist" on this lineup, and that is correct: the country list
 * carries free broadcasters, not the regional sports networks a local game
 * lives on.
 *
 * The install and removal rules are pure string functions on purpose - both
 * prefs are single newline/semicolon-separated strings, and the whole point of
 * this type is that installing writes BOTH in one edit and removing takes both
 * back out, with no half-installed state in between.
 */
object FreeLineup {

    /** The full US list: affiliates, national news, FAST, free sports. */
    const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/us.m3u"

    /**
     * iptv-org's aggregated guide, keyed by the same `tvg-id`s the playlist
     * carries. It is a global file, but the importer already prunes imported
     * programs to the channels the playlist knows (see `playlistMatchKeys`), so
     * what reaches the database stays proportional to this lineup.
     */
    const val EPG_URL = "https://iptv-org.github.io/epg/guides/all.xml"

    /** The display name the lineup's channels are grouped under. */
    const val NAME = "Free TV"

    /** The extras entry as stored: `<url>|<display name>`. */
    val ENTRY: String = "$PLAYLIST_URL|$NAME"

    /**
     * The label the setup form shows for an installed lineup.
     *
     * "(built-in)" is the honest word: these channels are not the viewer's
     * provider, and the row has to be distinguishable from a source they typed
     * in themselves.
     */
    const val INSTALLED_LABEL = "$NAME (built-in)"

    /** True when either the playlist or the EPG half of the install is present. */
    fun isInstalled(extraPlaylistUrls: String): Boolean =
        entries(extraPlaylistUrls).any { urlOf(it).equals(PLAYLIST_URL, ignoreCase = true) }

    /**
     * Both prefs after installing, ready for ONE `SharedPreferences` edit.
     *
     * Idempotent: an entry that is already there is left exactly as written
     * (a viewer who renamed it keeps their name), and the two strings are
     * returned untouched when both halves are already present.
     */
    fun install(
        extraPlaylistUrls: String,
        extraEpgUrls: String
    ): Pair<String, String> {
        val playlists = if (isInstalled(extraPlaylistUrls)) {
            extraPlaylistUrls
        } else {
            append(extraPlaylistUrls, ENTRY)
        }
        val guides = if (hasEpg(extraEpgUrls)) {
            extraEpgUrls
        } else {
            append(extraEpgUrls, EPG_URL)
        }
        return playlists to guides
    }

    /**
     * Both prefs after removal.
     *
     * Removes the playlist entry AND the guide entry together - a guide for a
     * lineup that is no longer in the sources is dead weight the importer would
     * keep downloading. Everything else the viewer configured is kept.
     */
    fun remove(
        extraPlaylistUrls: String,
        extraEpgUrls: String
    ): Pair<String, String> {
        val playlists = entries(extraPlaylistUrls)
            .filterNot { urlOf(it).equals(PLAYLIST_URL, ignoreCase = true) }
            .joinToString("\n")
        val guides = entries(extraEpgUrls)
            .filterNot { it.equals(EPG_URL, ignoreCase = true) }
            .joinToString("\n")
        return playlists to guides
    }

    private fun hasEpg(extraEpgUrls: String): Boolean =
        entries(extraEpgUrls).any { it.equals(EPG_URL, ignoreCase = true) }

    /**
     * Appends one entry, keeping the separator the field already uses: both
     * ';' and newlines are accepted on read (see IptvViewModel), and rewriting a
     * viewer's single-line field into a multi-line one for a one-tap install
     * would be a surprise in their own editor.
     */
    private fun append(raw: String, entry: String): String {
        val existing = raw.trim()
        if (existing.isEmpty()) return entry
        val separator = when {
            existing.contains('\n') -> '\n'
            existing.contains(';') -> ';'
            else -> '\n'
        }
        return existing + separator + entry
    }

    private fun urlOf(entry: String): String =
        entry.substringBefore('|').trim().ifEmpty { entry.trim() }

    private fun entries(raw: String): List<String> =
        raw.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
}
