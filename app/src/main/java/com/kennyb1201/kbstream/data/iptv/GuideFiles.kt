package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.security.MessageDigest

/**
 * What a profile's guide file is CALLED — which is also the answer to how many
 * copies of one provider's guide the device keeps.
 *
 * Every guide file used to be named after the profile that owned it
 * (`<profileId>.iptv_epg.db`), and profiles are fully isolated from each other,
 * so three profiles meant three copies of the same provider's schedule: on the
 * field TV, 132 + 124 + 118 MB of guide files for a window that is exactly the
 * same rows each time.
 *
 * Those copies are shared data, not three sets of private rows. The guide is
 * stored per EPG source (`epg_programs`, `epg_channels`), and the parts that
 * differ between plans live beside it keyed by playlist
 * (`playlist_epg_matches` and `cached_playlist_channels` are both keyed by
 * `playlistUrl`), while the only per-profile filters — Kids Mode's channel
 * ceiling and the hidden-channel list — are applied by
 * [com.kennyb1201.kbstream.ui.iptv.IptvViewModel] to the channel list it starts
 * from, never to the stored rows. So two profiles configured with the same
 * playlist are holding the same guide, and one file serves both.
 *
 * The naming is deliberately conditional: a profile only moves to the shared
 * name when ANOTHER profile is configured with exactly the same playlist URLs
 * ([expectedNameByProfile]). Changing a file's name costs a re-import, because
 * the old file cannot simply be adopted — a SQLite write-ahead log is named
 * after the database's path, so renaming a possibly-open database and its
 * sidecars is not something a background pass should do — and so the rename is
 * only paid where it buys a whole copy back. Every other profile keeps the name
 * it already has.
 *
 * The name is therefore a function of EVERY profile's playlist, not of one
 * profile's own settings: making profile B match profile A moves A's guide to
 * the shared name too, and A pays the re-import above without its own
 * configuration having been touched. That is the price of the copy sharing
 * saves, and it is bounded to one import, on A's next visit to Live TV — a
 * marker written about the old file no longer matches the new one
 * ([markerMatchesGuideFile]), so the import cannot be missed.
 *
 * The one-time cost where it does apply is the bargain the idle sweep already
 * makes: a guide is a cache, and the refresh worker rebuilds it from the
 * provider.
 */
internal object GuideFiles {

    /**
     * The profile-scoped preferences store the IPTV playlist lives in.
     *
     * The same store [com.kennyb1201.kbstream.data.iptv.IptvRepository] and
     * `IptvViewModel` address — which is what makes a playlist (and so a
     * guide's identity) readable for a profile that is NOT the active one, as
     * the maintenance pass needs.
     */
    const val PREFS = "iptv_prefs"

    private const val KEY_PLAYLIST = "playlist_url"
    private const val KEY_EXTRA_PLAYLISTS = "extra_playlist_urls"

    /** The unreachable pre-profile name, for a device with no profile yet. */
    private const val NO_PROFILE = GuideStorage.LEGACY_DB_NAME

    /**
     * The guide file name for each profile on this device.
     *
     * Two profiles configured with the same set of playlist URLs share one name
     * (and so one file); every other profile is named after itself, exactly as
     * before. Called per database open, so it reads only playlist prefs —
     * profile ids come from the in-memory profile list (see
     * [ProfileManager.profileIds]).
     */
    fun expectedNameByProfile(context: Context): Map<String, String> {

        val ids = ProfileManager.profileIds(context)
        if (ids.isEmpty()) return emptyMap()

        val keys = ids.associateWith { id -> playlistKey(playlistUrls(context, id)) }
        val shared = keys.values
            .filterNotNull()
            .groupingBy { key -> key }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys

        return ids.associateWith { id -> guideNameFor(id, keys[id]?.takeIf { it in shared }) }
    }

    /**
     * The guide file the ACTIVE profile uses — the one
     * [com.kennyb1201.kbstream.data.iptv.db.IptvDatabase] opens, and the one
     * [GuideStorage] must never delete.
     */
    fun activeName(context: Context): String {
        val profileId =
            ProfileStorage.activeProfileId(context)
                ?: return NO_PROFILE
        return guideNameFor(profileId, sharedKeyFor(context, profileId))
    }

    /** The playlist URLs a profile is configured with, in the order it wrote them. */
    fun playlistUrls(context: Context, profileId: String?): List<String> {

        // The active profile's store is resolved through the context (the
        // legacy un-prefixed name when no profile exists yet); any other
        // profile's is named after it directly, which is how a profile-scoped
        // store is read without switching profiles.
        val name =
            when {
                profileId == null -> ProfileStorage.prefsName(context, PREFS)
                profileId == ProfileStorage.activeProfileId(context) ->
                    ProfileStorage.prefsName(context, PREFS)
                else -> ProfileStorage.prefsName(profileId, PREFS)
            }

        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

        return playlistUrlsOf(
            playlistUrl = prefs.getString(KEY_PLAYLIST, ""),
            extraPlaylistUrls = prefs.getString(KEY_EXTRA_PLAYLISTS, "")
        )
    }
}

/**
 * The playlist URLs held in one `playlist_url` / `extra_playlist_urls` pair.
 *
 * The one spelling of that parsing: the import reads the same pair to decide
 * which channels a guide may feed
 * ([IptvRepository.playlistGuideMatchKeys]), and a guide's identity
 * ([GuideFiles]) is derived from it, so a difference between the two would mean
 * a file named after a playlist that is not the one being imported.
 */
internal fun playlistUrlsOf(
    playlistUrl: String?,
    extraPlaylistUrls: String?
): List<String> = buildList {
    playlistUrl.orEmpty()
        .trim()
        .takeIf(String::isNotEmpty)
        ?.let(::add)
    addAll(
        extraPlaylistUrls.orEmpty()
            .split('\n', ';')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    )
}.distinct()

/**
 * A stable key for a set of playlist URLs, or null when there are none.
 *
 * Sorted and de-duplicated, so the same playlists listed in a different order
 * are the same guide — which is the point of the key. The URLs themselves are
 * compared verbatim (not lowercased, not stripped of their query string): two
 * URLs that differ are two playlists until proven otherwise, and treating them
 * as one would share a guide across providers.
 *
 * SHA-256 truncated to [PLAYLIST_KEY_BYTES] bytes, i.e. 12 hex characters: this
 * only ever names a file on one device between a handful of profiles, so a
 * collision would mean two profiles sharing a guide that is not the same guide.
 * There is no adversary here, just a label.
 */
internal fun playlistKey(urls: List<String>): String? {

    val normalized = urls.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
    if (normalized.isEmpty()) return null

    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.joinToString("\n").toByteArray(Charsets.UTF_8))

    return digest.take(PLAYLIST_KEY_BYTES)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

private const val PLAYLIST_KEY_BYTES = 6

/**
 * The name a profile's guide takes: after the shared playlist when it has one,
 * and after the profile otherwise.
 */
internal fun guideNameFor(profileId: String, sharedKey: String?): String =
    "${sharedKey ?: profileId}${GuideStorage.DB_SUFFIX}"

/**
 * The guide file a freshness marker refers to when the marker did not record
 * one: the file named after the profile, which is the only name a guide had
 * before profiles could share one ([guideNameFor]).
 */
internal fun recordedGuideName(recordedName: String?, profileId: String?): String {
    if (recordedName != null) return recordedName
    return if (profileId == null) {
        GuideStorage.LEGACY_DB_NAME
    } else {
        guideNameFor(profileId, null)
    }
}

/**
 * Whether a guide's freshness marker still describes the guide the app will
 * read.
 *
 * The marker (`epg_updated_at`) says "this profile's guide was imported
 * recently", and until profiles could share a guide the file that meant was
 * unambiguous: the profile's own. It is not any more — a profile whose playlist
 * is shared reads `<playlistKey>.iptv_epg.db` — and the file that name points at
 * starts EMPTY, because merely opening it creates it. Marker recent plus new
 * file empty is the one state a staleness check cannot see through: it skips the
 * import and draws a guide with nothing in it, for as long as the marker stays
 * fresh. So the marker records which file it marked, and a marker about a
 * different file than the one the app would open means the one-time re-import
 * that sharing costs has not happened yet.
 */
internal fun markerMatchesGuideFile(
    recordedName: String?,
    activeName: String,
    profileId: String?
): Boolean = recordedGuideName(recordedName, profileId) == activeName

/** The shared playlist key for [profileId], or null when it stands alone. */
private fun sharedKeyFor(context: Context, profileId: String): String? {

    val key = playlistKey(GuideFiles.playlistUrls(context, profileId)) ?: return null

    val shared = ProfileManager.profileIds(context)
        .filter { id -> id != profileId }
        .any { other -> playlistKey(GuideFiles.playlistUrls(context, other)) == key }

    return key.takeIf { shared }
}
