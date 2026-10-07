package com.kennyb1201.kbstream.data.player

import android.content.Context

/**
 * Remembers the [EpisodeScheme] detected for a show, so the NEXT session is
 * exact from the first frame it plays instead of re-learning the mapping from
 * the file it happens to open.
 *
 * Global, not profile-scoped, and that is deliberate: which file holds which
 * episode is a property of the show's release set on the addons, not of the
 * person watching it. Two profiles on one device watching Paw Patrol must not
 * each pay to rediscover it, and a wrong scheme for one viewer is a wrong
 * scheme for every viewer.
 *
 * Local-only prefs, never synced: the player falls back to 1:1 without it, so a
 * device that has never seen the show is no worse off than the app was before
 * this existed, and there is nothing here another device needs.
 */
internal object EpisodeSchemeStore {

    private const val PREFS = "kbstream_episode_scheme"
    private const val KEY_PREFIX = "episode_scheme_"

    /**
     * The id a show's scheme is filed under.
     *
     * The IMDB flavor when the show has one - the same canonical form the
     * playback-history rows use, so the id the player files its history under
     * and the id its own readers look the scheme up with are the same string.
     * A show reachable only as `tmdb:<n>` is filed under that.
     *
     * Null when nothing usable was offered, which means "no entry" - never a
     * key built from a blank id, which would file every such show together.
     */
    fun stableShowId(imdbId: String?, tmdbId: Int?): String? {
        val imdb = imdbId?.trim().orEmpty()
        if (imdb.startsWith("tt") && imdb.length > 2) return imdb
        return tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" }
    }

    /** The stored scheme for [stableShowId], or 1:1 when there is none. */
    fun get(context: Context, stableShowId: String?): EpisodeScheme {
        val key = keyFor(stableShowId) ?: return EpisodeScheme.ONE_TO_ONE
        val raw = runCatching {
            prefs(context).getString(key, null)
        }.getOrNull()
        return EpisodeScheme.decode(raw)
    }

    /**
     * Files [scheme] as this show's, overwriting whatever was there: the last
     * thing detected against a real file is the best evidence the app has.
     *
     * A 1:1 detection clears the entry rather than storing it - "no entry" is
     * how the format spells 1:1, and a stored one would make a show that was
     * once misdetected sticky when a re-detection says otherwise.
     */
    fun put(context: Context, stableShowId: String?, scheme: EpisodeScheme) {
        val key = keyFor(stableShowId) ?: return
        runCatching {
            val edit = prefs(context).edit()
            val encoded = scheme.encode()
            if (encoded == null) edit.remove(key) else edit.putString(key, encoded)
            edit.apply()
        }
    }

    private fun keyFor(stableShowId: String?): String? =
        stableShowId?.trim()?.takeIf { it.isNotBlank() }?.let { KEY_PREFIX + it }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The stream id to hand the addons for one TMDB episode: the FILE identity,
 * which is what a source resolves against.
 *
 * A stream id is `"<show id>:<season>:<episode>"`, and the app keeps two
 * different numbers for the same episode - the TMDB one every label, history
 * row and tracker push speaks, and the file one the addons name. This is where
 * the first becomes the second, so the three callers that build an episode id
 * out of a TMDB number (the TMDB season listing, the synthetic addon-video
 * listing, and the tracker-derived resume rows) cannot disagree about it.
 *
 * Under 1:1 - no entry, or no scheme detectable - this is the plain
 * `"$root:$season:$tmdbEpisode"` the app always built.
 */
internal fun fileEpisodeStreamId(
    context: Context,
    rootId: String,
    stableShowId: String?,
    season: Int,
    tmdbEpisode: Int
): String {
    val fileEpisode = if (stableShowId.isNullOrBlank()) {
        tmdbEpisode
    } else {
        EpisodeSchemeStore.get(context, stableShowId).fileForTmdbEpisode(tmdbEpisode)
    }
    return "$rootId:$season:$fileEpisode"
}

/**
 * [fileEpisodeStreamId] for a caller holding the raw parent id and the TMDB
 * number rather than a stable id: the store key is derived from the pair, so
 * the builders cannot each pick a different one for the same show.
 */
internal fun fileEpisodeStreamId(
    context: Context,
    rootId: String,
    tmdbId: Int?,
    season: Int,
    tmdbEpisode: Int
): String = fileEpisodeStreamId(
    context = context,
    rootId = rootId,
    stableShowId = EpisodeSchemeStore.stableShowId(rootId, tmdbId),
    season = season,
    tmdbEpisode = tmdbEpisode
)
