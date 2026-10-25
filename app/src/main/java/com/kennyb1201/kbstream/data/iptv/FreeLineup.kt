package com.kennyb1201.kbstream.data.iptv

import java.util.Locale

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
     * The US guide the playlist itself declares, keyed by the same `tvg-id`s it
     * carries. `us.m3u`'s own `x-tvg-url` header names it, so this address
     * comes from the source rather than from a search result: community-hosted
     * (kanya.io), gzipped, and verified live 2026-10-24. The importer already
     * sniffs the gzip magic bytes (see `IptvHttpClient`) and prunes imported
     * programs to the channels the lineup knows, so what reaches the database
     * stays proportional to this lineup.
     *
     * A re-point starts HERE: fetch the playlist and read its `url-tvg` header.
     */
    const val EPG_URL = "https://epg.kanya.io/us/guide.xml.gz"

    /**
     * The guide address earlier builds installed, which iptv-org no longer
     * serves (every guide path on that host is a 404). Named rather than
     * forgotten because installs in the field still carry the line: it counts
     * as the lineup's own entry everywhere ([remove], [install], the repair),
     * so a bare re-point cannot orphan it into a URL that is fetched, 404s, and
     * can never be removed.
     */
    const val LEGACY_EPG_URL = "https://iptv-org.github.io/epg/guides/all.xml"

    /**
     * The US LOCAL stations guide, installed beside [EPG_URL] rather than as
     * the primary one.
     *
     * kanya answers the national feeds a `us.m3u` `tvg-id` names, and nothing
     * for a local affiliate - on 2026-10-25 every local sampled (ABC KERO-TV,
     * KGTV, KGUN-TV, KITV ...; CBS KMTV-TV, KUTV ...) read "No program data"
     * after a kanya-only import. This file carries them, keyed by callsign
     * (`WFTV-DT.us_locals1`).
     *
     * Provenance, stated because it differs from [EPG_URL]: this address is NOT
     * in `us.m3u`'s header. It comes from a public free-EPG listing
     * (free-codecs.com, "Free EPG URLs", 2026-10-07), and was verified live by
     * downloading it on 2026-10-25 (channels `WFTV-DT.us_locals1`,
     * `WTVQ-DT.us_locals1`, `WDIO-DT.us_locals1`, with programmes dated
     * 20261024-20261025). It is community-hosted and tens of MB; if epgshare01
     * ever re-schemes, an app update re-points THIS constant, exactly as with
     * the other two. Nothing here is hosted by KBStream.
     */
    const val LOCALS_EPG_URL =
        "https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz"

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

    /**
     * The country an entry's `tvg-id` names, lowercased, or null when the id
     * does not carry one.
     *
     * iptv-org spells a channel's id as `<name>.<country>@<feed>`
     * (`ABC.us@WFTV`, `TNTInternational.ru@SD`), so the country is the segment
     * after the LAST dot of the part before the `@` - the feed half is a
     * callsign, `SD`/`HD` or a regional marker and is never a country.
     *
     * Null is "unknown", not "not US": an id with no dot, an empty tail, or no
     * id at all says nothing about where a channel is from.
     */
    fun countryOf(tvgId: String?): String? {
        val id = tvgId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutFeed = id.substringBefore('@')
        return withoutFeed.substringAfterLast('.', "")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.US)
    }

    /**
     * Whether one parsed entry belongs in the built-in lineup - "just US
     * stuff", by the country in its `tvg-id`.
     *
     * `us.m3u` is not cleanly US: it carries a handful of international feeds
     * (LBC International `.lb`, Star Channel International `.gr`, TNT
     * International `.ru`) beside the US entries. The lineup's promise is US
     * CHANNELS, which is a country question and never a language one - a US
     * channel broadcasting in French or Spanish (`3ABNFrench.us@SD`) stays.
     *
     * The rule is positive evidence only: a 2-letter code other than `us` drops
     * the entry, and anything unprovable - no `tvg-id`, no dot, an empty tail -
     * is KEPT. Losing a real US affiliate because its provider left the id
     * malformed costs the viewer a channel; keeping an unidentifiable foreign
     * one costs a row they can hide.
     */
    fun isUsChannel(channel: IptvChannel): Boolean {
        val country = countryOf(channel.tvgId) ?: return true
        return country == "us"
    }

    /** True when either the playlist or the EPG half of the install is present. */
    fun isInstalled(extraPlaylistUrls: String): Boolean =
        entries(extraPlaylistUrls).any(::isFreeLineupEntry)

    /**
     * Whether ONE stored extras entry is the free lineup's playlist.
     *
     * Public because the source list in Playlist Setup has to leave this entry
     * out of its rows: the free lineup is administered by its own block (install
     * state, and a Remove that takes the guide entry with it), and showing it
     * twice - once there and once as an ordinary extra - would give the viewer
     * two Remove buttons that behave differently.
     */
    fun isFreeLineupEntry(entry: String): Boolean =
        urlOf(entry).equals(PLAYLIST_URL, ignoreCase = true)

    /**
     * Whether ONE stored extra-EPG entry belongs to the free lineup.
     *
     * True for the living address, the locals one and the legacy one, so every
     * path that detects, strips or deduplicates the lineup's guides treats an
     * install from any build as this lineup's entry - which is what makes
     * [remove] complete on a not-yet-repaired install, and what takes the
     * locals guide back out with the playlist it exists for.
     *
     * The locals rule follows [remove]'s existing precedent: a kanya line the
     * viewer typed by hand is removed with the lineup too. A viewer who wants
     * the locals guide for some other playlist after removing Free TV re-adds
     * it in one paste.
     */
    fun isFreeLineupEpgEntry(entry: String): Boolean {
        val url = entry.trim()
        return url.equals(EPG_URL, ignoreCase = true) ||
            url.equals(LOCALS_EPG_URL, ignoreCase = true) ||
            url.equals(LEGACY_EPG_URL, ignoreCase = true)
    }

    /**
     * Both prefs after installing, ready for ONE `SharedPreferences` edit.
     *
     * Idempotent PER LINE, not per install: an entry that is already there is
     * left exactly as written (a viewer who renamed it keeps their name), and
     * each guide line is appended only when that line is missing. So a field
     * holding kanya but not locals gains only locals, one holding locals but
     * not kanya gains only kanya, and one holding both is returned untouched -
     * including a viewer who pasted the locals guide FIRST, whose order is not
     * rewritten into the canonical one.
     *
     * A lone dead legacy line is still repaired in place ([repairEpgEntry]) and
     * then counted as kanya, so an older install is brought forward and gains
     * locals in the same tap.
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
        val repaired = repairEpgEntry(playlists, extraEpgUrls) ?: extraEpgUrls
        // Kanya first, locals second. Order is load-bearing: guide sources are
        // read primary first and each channel keeps the FIRST source that
        // resolves it, so kanya must stay ahead of locals - the locals guide
        // only ADDS the affiliates kanya cannot answer.
        val guides = INSTALLED_GUIDES.fold(repaired) { blob, url ->
            if (hasGuide(blob, url)) blob else append(blob, url)
        }
        return playlists to guides
    }

    /** What [localsGuideDecision] decided for one profile entry. */
    internal sealed interface LocalsGuideDecision {
        /** Leave both prefs alone: already applied, or Free TV is not installed. */
        data object Skip : LocalsGuideDecision

        /** The line is already in the field: record it, write nothing. */
        data object RecordOnly : LocalsGuideDecision

        /** Write this blob (the line appended), then record it. */
        data class Append(val extraEpgUrls: String) : LocalsGuideDecision
    }

    /**
     * The one-time locals-guide migration's decision for one profile entry.
     *
     * Pure on purpose, so every branch of the spec is testable off-device:
     *
     *  - already applied -> [LocalsGuideDecision.Skip], which is what keeps a
     *    line the viewer deleted from coming back;
     *  - Free TV not installed -> [LocalsGuideDecision.Skip], so a profile with
     *    its own playlist is untouched;
     *  - the line is already there (pasted by hand, as the field report did) ->
     *    [LocalsGuideDecision.RecordOnly], never a duplicate or a move;
     *  - otherwise -> [LocalsGuideDecision.Append].
     *
     * The "guide import in progress" guard is the caller's: it is transient
     * state, and the flag must stay unset so the next entry retries.
     *
     * Adding the line imports NOTHING: the locals stay empty until a guide
     * import (or the next background refresh) re-reads the source list.
     */
    internal fun localsGuideDecision(
        extraPlaylistUrls: String,
        extraEpgUrls: String,
        alreadyApplied: Boolean
    ): LocalsGuideDecision = when {
        alreadyApplied -> LocalsGuideDecision.Skip
        !isInstalled(extraPlaylistUrls) -> LocalsGuideDecision.Skip
        hasGuide(extraEpgUrls, LOCALS_EPG_URL) -> LocalsGuideDecision.RecordOnly
        else -> LocalsGuideDecision.Append(append(extraEpgUrls, LOCALS_EPG_URL))
    }

    /**
     * The extra-EPG blob with the legacy guide line swapped for [EPG_URL], or
     * null when there is nothing to swap.
     *
     * The pairing is the fingerprint: a legacy line is only replaced when the
     * free PLAYLIST entry is installed. Standing alone it may be a viewer's own
     * manual entry, and this repair does not edit what it cannot attribute. The
     * swap is idempotent and touches no other line.
     */
    fun repairEpgEntry(extraPlaylistUrls: String, extraEpgUrls: String): String? {
        if (!isInstalled(extraPlaylistUrls)) return null
        val entries = entries(extraEpgUrls)
        if (entries.none { it.equals(LEGACY_EPG_URL, ignoreCase = true) }) return null
        // Only the lineup's own lines are rewritten, and only once: an install
        // that somehow carries both addresses keeps one, at the position the
        // first of them occupied. Every other line is passed through as typed.
        var seen = false
        val rewritten = entries.mapNotNull { entry ->
            when {
                entry.equals(LEGACY_EPG_URL, ignoreCase = true) -> {
                    if (seen) null else EPG_URL.also { seen = true }
                }
                entry.equals(EPG_URL, ignoreCase = true) -> {
                    if (seen) null else entry.also { seen = true }
                }
                else -> entry
            }
        }
        return rewritten.joinToString(separatorOf(extraEpgUrls).toString())
    }

    /**
     * Both prefs after removal.
     *
     * Removes the playlist entry AND both guide entries together (kanya and the
     * locals file, plus a not-yet-repaired legacy line) - a guide for a lineup
     * that is no longer in the sources is dead weight the importer would keep
     * downloading. Everything else the viewer configured is kept.
     */
    fun remove(
        extraPlaylistUrls: String,
        extraEpgUrls: String
    ): Pair<String, String> {
        val playlists = entries(extraPlaylistUrls)
            .filterNot(::isFreeLineupEntry)
            .joinToString("\n")
        val guides = entries(extraEpgUrls)
            .filterNot(::isFreeLineupEpgEntry)
            .joinToString("\n")
        return playlists to guides
    }

    /** Whether one exact guide URL is among the extra-EPG lines. */
    private fun hasGuide(extraEpgUrls: String, url: String): Boolean =
        entries(extraEpgUrls).any { it.equals(url, ignoreCase = true) }

    /** The guides an install writes, in the order they must be read in. */
    private val INSTALLED_GUIDES = listOf(EPG_URL, LOCALS_EPG_URL)

    /**
     * Appends one entry, keeping the separator the field already uses: both
     * ';' and newlines are accepted on read (see IptvViewModel), and rewriting a
     * viewer's single-line field into a multi-line one for a one-tap install
     * would be a surprise in their own editor.
     */
    private fun append(raw: String, entry: String): String {
        val existing = raw.trim()
        if (existing.isEmpty()) return entry
        return existing + separatorOf(raw) + entry
    }

    /** The separator the field already uses: ';' or newlines are both read. */
    private fun separatorOf(raw: String): Char = when {
        raw.contains('\n') -> '\n'
        raw.contains(';') -> ';'
        else -> '\n'
    }

    private fun urlOf(entry: String): String =
        entry.substringBefore('|').trim().ifEmpty { entry.trim() }

    private fun entries(raw: String): List<String> =
        raw.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
}
