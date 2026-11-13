package com.kennyb1201.kbstream.data.mal

import android.content.Context
import com.kennyb1201.kbstream.data.anime.AnimeTrackerEntry
import com.kennyb1201.kbstream.data.anime.AnimeTrackerRead
import com.kennyb1201.kbstream.data.anime.AnimeTrackerStatus
import org.json.JSONObject

/**
 * The MyAnimeList tracker leg (spec 2026-11-13, F2).
 *
 * MAL holds the same information the Simkl and Trakt legs read - an entry per
 * anime with the viewer's status and how many episodes they have watched - so it
 * hands the rails the same [AnimeTrackerEntry] shape the AniList and Kitsu legs
 * do, and joins the merge like every other tracker: a leg, never a gate, dormant
 * when this profile has not connected, and never able to veto another leg.
 *
 * The one structural difference: MAL's list is per-ACCOUNT and the read is
 * OAuth-only (`/users/@me/animelist`), because MAL has no public username read
 * even for a public profile. So the leg's "not configured" is this profile's
 * connection, not a username.
 *
 * Titles come from the entry's own `alternative_titles` (MAL's `en`, then the
 * romanized synonyms) because the TMDB bridge is a title match - neither service
 * carries a TMDB id. The MAL id itself is the entry's native key and stays with
 * the node, so a write never has to look it up again.
 */
internal object MalLegs {

    /** This profile's list, mapped to the rail's entry shape. */
    suspend fun readMyList(context: Context): AnimeTrackerRead {
        val read = MalApiClient.myAnimeList(context)

        return when (read) {
            is MalListRead.Ok -> AnimeTrackerRead.Ok(read.entries.mapNotNull { parseEntry(it) })
            MalListRead.NotConfigured -> AnimeTrackerRead.NotConfigured
            MalListRead.NotConnected -> AnimeTrackerRead.NotConfigured
            MalListRead.NeedsReconnect -> AnimeTrackerRead.Unreadable("MAL needs reconnecting")
            MalListRead.Failed -> AnimeTrackerRead.Failed(IllegalStateException("MAL list read failed"))
        }
    }

    /**
     * One `data[]` row.
     *
     * `list_status.status` is MAL's own vocabulary and maps one-to-one onto the
     * rail's three buckets: `watching` progresses, `completed` is watched-out
     * (an Upcoming candidate, never a Continue Watching card), and `on_hold`,
     * `dropped` and `plan_to_watch` are neither.
     */
    internal fun parseEntry(row: JSONObject?): AnimeTrackerEntry? {
        val entry = row ?: return null
        val node = entry.optJSONObject("node") ?: return null
        val status = entry.optJSONObject("list_status") ?: return null

        val alternative = node.optJSONObject("alternative_titles")
        val synonyms = alternative?.optJSONArray("synonyms")
        val names = ArrayList<String>(4)
        node.optString("title").takeIf { it.isNotBlank() }?.let { names += it }
        alternative?.optString("en")?.takeIf { it.isNotBlank() }?.let { names += it }
        if (synonyms != null) {
            for (index in 0 until synonyms.length()) {
                synonyms.optString(index).takeIf { it.isNotBlank() }?.let { names += it }
            }
        }
        if (names.isEmpty()) return null

        return AnimeTrackerEntry(
            titles = names.distinct(),
            progress = status.optInt("num_watched_episodes", 0).coerceAtLeast(0),
            episodes = node.optInt("num_episodes", 0).takeIf { it > 0 },
            status = malTrackerStatus(status.optString("status")),
            // MAL carries a broadcast slot, not the next air date; TMDB supplies
            // the date for the rail, exactly as it does for Kitsu.
            nextAiringAtSeconds = null
        )
    }

    /** MAL's list status → the rail's three buckets. */
    internal fun malTrackerStatus(raw: String?): AnimeTrackerStatus =
        when (raw.orEmpty().trim().lowercase(java.util.Locale.ROOT)) {
            "watching", "current", "rewatching" -> AnimeTrackerStatus.IN_PROGRESS
            "completed" -> AnimeTrackerStatus.COMPLETED
            else -> AnimeTrackerStatus.OTHER
        }
}
