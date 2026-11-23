package com.kennyb1201.kbstream.data.trakt

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.json.JSONArray
import org.json.JSONObject
import com.kennyb1201.kbstream.data.settings.prefs.Prefs

/**
 * The built watched snapshot, on disk (F1, spec 2026-11-20).
 *
 * Why: the snapshot is account-wide and every cold start used to pay for it
 * again, because the only cache was a field in memory. One heavy account times
 * four devices is eight full `/sync/watched` walks an evening for data that did
 * not change. With the snapshot here, a launch reads the last one, asks
 * `/sync/last_activities` once, and fetches a section only when its stamp
 * moved.
 *
 * What is stored: the snapshot itself (movie keys and per-show episode sets),
 * the stamps it was built from, and a fingerprint of the access token it
 * belongs to. Per-profile by construction - [ProfileStorage.prefsName] gives
 * each profile its own file, the same arrangement the scrobble queue uses - so
 * a profile switch can neither read nor overwrite another profile's history.
 *
 * The fingerprint is a hash, never the token: the file needs to know whether it
 * still belongs to the account in hand, not to hold a credential.
 */
internal class TraktWatchedSnapshotCache(private val context: Context?) {

    /** One profile's snapshot plus the stamps it answered. */
    internal data class Entry(
        val stamps: TraktWatchedStamps,
        val movieKeys: Set<String>,
        val shows: List<TraktShowWatch>,
        val savedAtMs: Long
    )

    private fun prefs() = context?.applicationContext?.let { app ->
        Prefs.file(app, ProfileStorage.prefsName(app, PREFS_NAME))
    }

    /**
     * The stored entry for [token], or null when there is none or it belongs to
     * another token (a reconnected account).
     *
     * Every read is `opt*` and unreadable rows are skipped, on the same rule the
     * scrobble queue states: one corrupt element must not discard the rest.
     */
    @Synchronized
    fun read(token: String): Entry? {
        val raw = prefs()?.getString(KEY_ENTRY, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("v", 0) != FORMAT_VERSION) return null
            if (obj.optString("fp") != fingerprint(token)) return null
            val stamps = TraktWatchedStamps.parse(obj.optString("stamps")) ?: return null
            val movieArray = obj.optJSONArray("movies") ?: JSONArray()
            val movies = buildSet {
                for (index in 0 until movieArray.length()) {
                    movieArray.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            val showArray = obj.optJSONArray("shows") ?: JSONArray()
            val shows = buildList {
                for (index in 0 until showArray.length()) {
                    val show = showArray.optJSONObject(index) ?: continue
                    val keys = show.optJSONArray("ids") ?: continue
                    val idKeys = buildSet {
                        for (keyIndex in 0 until keys.length()) {
                            keys.optString(keyIndex)
                                .takeIf { it.isNotBlank() }
                                ?.let { add(it) }
                        }
                    }
                    val episodes = show.optJSONArray("episodes") ?: JSONArray()
                    val watched = buildSet {
                        for (episodeIndex in 0 until episodes.length()) {
                            val pair = episodes.optString(episodeIndex)
                                .split(':')
                                .takeIf { it.size == 2 } ?: continue
                            val season = pair[0].toIntOrNull() ?: continue
                            val episode = pair[1].toIntOrNull() ?: continue
                            add(season to episode)
                        }
                    }
                    add(TraktShowWatch(idKeys = idKeys, episodes = watched))
                }
            }
            Entry(
                stamps = stamps,
                movieKeys = movies,
                shows = shows,
                savedAtMs = obj.optLong("savedAt", 0L)
            )
        }.getOrNull()
    }

    /** Replaces the profile's entry. A write that cannot land is not an error. */
    @Synchronized
    fun write(token: String, entry: Entry) {
        runCatching {
            val shows = JSONArray()
            entry.shows.forEach { show ->
                shows.put(
                    JSONObject()
                        .put("ids", JSONArray(show.idKeys.toList()))
                        .put(
                            "episodes",
                            JSONArray(
                                show.episodes.map { (season, episode) -> "$season:$episode" }
                            )
                        )
                )
            }
            val obj = JSONObject()
                .put("v", FORMAT_VERSION)
                .put("fp", fingerprint(token))
                .put("stamps", entry.stamps.wire())
                .put("savedAt", entry.savedAtMs)
                .put("movies", JSONArray(entry.movieKeys.toList()))
                .put("shows", shows)
            prefs()?.edit()?.putString(KEY_ENTRY, obj.toString())?.apply()
        }
    }

    /**
     * Forgets the profile's entry.
     *
     * Called when this device writes watch state: the snapshot in hand is now
     * known-stale, and the old behaviour (re-read on the next pass) is the one
     * to keep for our OWN writes - the activities stamp would catch it a
     * moment later, but Continue Watching is not a place for a moment.
     */
    @Synchronized
    fun clear() {
        runCatching { prefs()?.edit()?.remove(KEY_ENTRY)?.apply() }
    }

    /** A stable, non-reversible id for the token this entry belongs to. */
    private fun fingerprint(token: String): String =
        Integer.toHexString(token.hashCode())

    private companion object {
        const val PREFS_NAME = "TraktWatchedSnapshot"
        const val KEY_ENTRY = "snapshot"
        const val FORMAT_VERSION = 1
    }
}
