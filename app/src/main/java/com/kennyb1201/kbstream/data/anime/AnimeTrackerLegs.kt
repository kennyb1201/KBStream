package com.kennyb1201.kbstream.data.anime

import org.json.JSONObject

/**
 * The read-only AniList and Kitsu tracker legs (spec 2026-11-13, F2).
 *
 * Both services hold a public profile's anime list: an entry per anime, with the
 * viewer's status and how many episodes they have watched. That is the same
 * information the Simkl and Trakt legs read from their accounts, so these legs
 * feed Continue Watching and Upcoming the same way - a leg, never a gate, and
 * dormant when no username is entered.
 *
 * v1 is READ-ONLY and account-free in the credential sense: a username, a public
 * list, no token. A private profile reads nothing, and the Settings row says so
 * rather than presenting the silence as an empty list.
 *
 * The entries are keyed by the SERVICE's id - which is what the caller uses to
 * recognise the same title across legs - and the TMDB bridge is a title match
 * (see [animeTmdbTitleMatch]): neither service has a TMDB id, so the title is the
 * only identity both ends share, and it is accepted only on an exact normalized
 * match.
 */
internal object AnimeTrackerLegs {

    /** AniList's answer for [username]'s public anime list. */
    suspend fun readAniList(username: String?): AnimeTrackerRead {
        val name = username?.trim().orEmpty()
        if (name.isEmpty()) return AnimeTrackerRead.NotConfigured

        val collection =
            runCatching { AniListClient.mediaListCollection(name) }.getOrElse { error ->
                return AnimeTrackerRead.Failed(error)
            } ?: return AnimeTrackerRead.Unreadable("AniList returned no list for '$name'")

        val lists = collection.optJSONArray("lists")
            ?: return AnimeTrackerRead.Unreadable("AniList returned no lists for '$name'")

        val entries = ArrayList<AnimeTrackerEntry>()
        for (index in 0 until lists.length()) {
            val list = lists.optJSONObject(index) ?: continue
            val listStatus = list.optString("status")
            val rows = list.optJSONArray("entries") ?: continue

            for (rowIndex in 0 until rows.length()) {
                parseAniListEntry(rows.optJSONObject(rowIndex), listStatus)?.let { entries += it }
            }
        }

        return AnimeTrackerRead.Ok(entries)
    }

    /** Kitsu's answer for [username]'s public anime library. */
    suspend fun readKitsu(username: String?): AnimeTrackerRead {
        val name = username?.trim().orEmpty()
        if (name.isEmpty()) return AnimeTrackerRead.NotConfigured

        val userId =
            runCatching { KitsuClient.userIdByName(name) }.getOrElse { error ->
                return AnimeTrackerRead.Failed(error)
            } ?: return AnimeTrackerRead.Unreadable("Kitsu has no user named '$name'")

        // Two pages is 40 entries: enough for the rail's cap, and Kitsu's
        // one-request-a-second spacing keeps a bigger crawl off the critical
        // path of a Home load.
        val entries = ArrayList<AnimeTrackerEntry>()
        for (page in 0 until KITSU_MAX_PAGES) {
            val body =
                runCatching { KitsuClient.libraryEntries(userId, offset = page * KitsuClient.PAGE_LIMIT) }
                    .getOrElse { error -> return AnimeTrackerRead.Failed(error) }
                    ?: return AnimeTrackerRead.Unreadable("Kitsu library read failed for '$name'")

            val included = includedAnimeById(body)
            val data = body.optJSONArray("data") ?: break

            for (index in 0 until data.length()) {
                parseKitsuEntry(data.optJSONObject(index), included)?.let { entries += it }
            }

            if (data.length() < KitsuClient.PAGE_LIMIT) break
        }

        return AnimeTrackerRead.Ok(entries)
    }

    private const val KITSU_MAX_PAGES = 2

    internal fun parseAniListEntry(row: JSONObject?, listStatus: String): AnimeTrackerEntry? {
        val entry = row ?: return null
        val media = entry.optJSONObject("media") ?: return null
        val titles = media.optJSONObject("title")

        val names =
            listOfNotNull(
                titles?.optString("english"),
                titles?.optString("romaji")
            ).filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return null

        return AnimeTrackerEntry(
            titles = names,
            progress = entry.optInt("progress", 0).coerceAtLeast(0),
            episodes = media.optInt("episodes", 0).takeIf { it > 0 },
            status = aniListStatus(entry.optString("status").ifBlank { listStatus }),
            nextAiringAtSeconds =
                media.optJSONObject("nextAiringEpisode")
                    ?.optLong("airingAt", 0L)
                    ?.takeIf { it > 0L }
        )
    }

    internal fun parseKitsuEntry(
        row: JSONObject?,
        included: Map<String, JSONObject>
    ): AnimeTrackerEntry? {
        val entry = row ?: return null
        val attributes = entry.optJSONObject("attributes") ?: return null

        val animeId =
            entry.optJSONObject("relationships")
                ?.optJSONObject("anime")
                ?.optJSONObject("data")
                ?.optString("id")
                ?.takeIf { it.isNotBlank() } ?: return null

        val anime = included[animeId] ?: return null
        val animeAttributes = anime.optJSONObject("attributes") ?: return null
        val titles = animeAttributes.optJSONObject("titles")

        val names =
            listOfNotNull(
                animeAttributes.optString("canonicalTitle"),
                titles?.optString("en"),
                titles?.optString("en_jp")
            ).filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return null

        return AnimeTrackerEntry(
            titles = names,
            progress = attributes.optInt("progress", 0).coerceAtLeast(0),
            episodes = animeAttributes.optInt("episodeCount", 0).takeIf { it > 0 },
            status = kitsuStatus(attributes.optString("status")),
            nextAiringAtSeconds = null
        )
    }

    /** Kitsu's `included` array, keyed by resource id (`anime` resources only). */
    internal fun includedAnimeById(body: JSONObject): Map<String, JSONObject> {
        val included = body.optJSONArray("included") ?: return emptyMap()
        val out = HashMap<String, JSONObject>(included.length())
        for (index in 0 until included.length()) {
            val resource = included.optJSONObject(index) ?: continue
            if (resource.optString("type") != "anime") continue
            val id = resource.optString("id").takeIf { it.isNotBlank() } ?: continue
            out[id] = resource
        }
        return out
    }
}
