package com.kennyb1201.kbstream.data.player

import android.content.Context
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch

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
/**
 * A STORED episode stream id, re-rooted under the root the app resolved for its
 * show.
 *
 * A stored id is not an id the app built: it is whatever the session that filed
 * it named its request by, and a row written before the root was verified
 * carries the CATALOG's id instead of TMDB's (see
 * [com.kennyb1201.kbstream.data.tmdb.preferredStreamRootId] for the field case -
 * a series catalog handing out `tt3121722` for a show whose TMDB record says
 * `tt4873036`). Two surfaces read such an id back: the Continue Watching rail
 * hands it to the player, the streams picker and on into the player's own
 * next-episode walk - the read the detail screen's guard cannot cover, because
 * "Play Manually" skips the detail screen entirely - and the one-shot history
 * root migration rewrites the rows themselves (see
 * [com.kennyb1201.kbstream.data.history.HistoryRootMigration]). Unre-rooted,
 * every episode of such a show asks for another title's streams.
 *
 * Only the ROOT is swapped. The `season:episode` suffix is kept exactly as the
 * row was filed with, because it names the FILE that was played - it is the
 * file numbering the add-ons answered then, and re-deriving it from the TMDB
 * episode would run the learned numbering over a file the viewer has already
 * watched (that is [fileEpisodeStreamId]'s job on a freshly resolved listing,
 * where the mapping IS the point).
 *
 * An id that names no episode is returned untouched, and so is one whose
 * resolved root is blank or the same as the stored one: `resolvedRoot` is
 * "nothing to verify" there - a title TMDB has no record for, a missing key, an
 * offline device - and the stored id is then the only id there is, which is the
 * same answer [com.kennyb1201.kbstream.data.tmdb.TmdbRepository.streamRootIdFor]
 * gives its own callers.
 */
internal fun rerootedEpisodeStreamId(
    storedStreamId: String,
    resolvedRoot: String
): String {
    val storedRoot =
        EpisodeMatch.seriesRootFrom(storedStreamId)
            ?: return storedStreamId

    val verified =
        resolvedRoot.trim()

    if (verified.isEmpty() || verified == storedRoot) return storedStreamId

    return verified + storedStreamId.removePrefix(storedRoot)
}

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
