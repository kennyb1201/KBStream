package com.kennyb1201.kbstream.ui.player

import android.content.Context
import com.kennyb1201.kbstream.data.fanart.FanartArtRepository
import com.kennyb1201.kbstream.data.fanart.FanartKind
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The clearlogo for a player launch that arrived without one (spec 2026-11-22,
 * F4).
 *
 * Both engines had their own copy of this lookup and both stopped at TMDB's
 * English logo: a title whose only wordmark is fanart's - exactly the case
 * Detail's own fallback exists for - could never heal in the player, so its
 * splash and overlay stayed on the plain title even after the header showed a
 * logo.
 *
 * One chain, TMDB first: a title with a TMDB wordmark never reaches the fanart
 * call, so no logo is ever re-ranked or swapped, and the fanart step stays the
 * step BELOW. Movies ask fanart by TMDB id; series resolve their TVDB twin
 * first (fanart's TV endpoint is keyed by it), the same way Detail does.
 *
 * Silent on failure, like everything else on this path: a logo is decoration,
 * and the caller's name-text fallback is already on screen. The callers keep
 * their one-lookup-per-session latch - this function does not remember
 * anything.
 */
internal suspend fun resolveClearLogoUrl(
    context: Context,
    parentId: String,
    type: String
): String? {
    val normalized = type.lowercase()
    if (normalized != "movie" && normalized != "series") return null

    val repository = TmdbRepository.getInstance(context)
    val detail = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            repository.fetchEnrichedMetaCached(parentId, normalized)
        }
    }.getOrNull()

    detail?.bestLogoPath()?.takeIf { it.isNotBlank() }?.let { path ->
        return TmdbRepository.LOGO_BASE + path
    }

    // The fanart twin: fanart has no collection endpoint and no answer for a
    // title TMDB could not name, which is why this needs the detail's own id.
    val artId = detail?.id?.takeIf { it > 0 } ?: return null
    val isMovie = TmdbRepository.normalizeMediaType(normalized) != "series"
    val fanartTwin = if (isMovie) {
        artId
    } else {
        runCatchingCancellable {
            withContext(Dispatchers.IO) { repository.resolveTvdbId(artId, "series") }
        }.getOrNull()
    } ?: return null

    return runCatchingCancellable {
        withContext(Dispatchers.IO) {
            FanartArtRepository.getInstance(context).clearlogo(
                kind = if (isMovie) FanartKind.Movie else FanartKind.Tv,
                id = fanartTwin
            )
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
