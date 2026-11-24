package com.kennyb1201.kbstream.ui.player

import android.content.Context
import com.kennyb1201.kbstream.data.fanart.FanartArtRepository
import com.kennyb1201.kbstream.data.fanart.FanartKind
import com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace
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
 * anything. It also notes one `LOGO:` line per step into the playback session
 * trace; see [resolveClearLogoChain], which is the ladder itself and the only
 * place a step is decided.
 */
internal suspend fun resolveClearLogoUrl(
    context: Context,
    parentId: String,
    type: String
): String? {
    val normalized = type.lowercase()
    if (normalized != "movie" && normalized != "series") return null

    val repository = TmdbRepository.getInstance(context)
    val fanart = FanartArtRepository.getInstance(context)

    return resolveClearLogoChain(
        parentId = parentId,
        type = normalized,
        logoBase = TmdbRepository.LOGO_BASE,
        trace = PlaybackSessionTrace::note,
        fetchDetail = { id ->
            var source: String? = null
            val fetched = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    // `onSource` is the repository's own answer to "where did
                    // this come from" (mem/disk/net/shared), threaded through
                    // rather than inferred from timings here.
                    repository.fetchEnrichedMetaCached(id, normalized) { source = it }
                }
            }
            val detail = fetched.getOrNull()
            LogoDetailAnswer(
                answered = detail != null,
                callFailed = fetched.isFailure,
                logoPath = detail?.bestLogoPath()?.takeIf { it.isNotBlank() },
                detailId = detail?.id?.takeIf { it > 0 } ?: 0,
                source = source
            )
        },
        resolveTvdbId = { artId ->
            runCatchingCancellable {
                withContext(Dispatchers.IO) { repository.resolveTvdbId(artId, "series") }
            }.getOrNull()
        },
        provenNoTvdbId = { artId ->
            // Read-only: the remembered-miss state is what tells "TMDB has
            // proven there is no twin" from "the call failed".
            repository.hasProvenNoTvdbMapping(artId, "series")
        },
        fanartLogo = { isMovie, fanartId ->
            // `clearlogoAnswer`, not `clearlogo`: "fanart has no logo for this
            // twin" and "the call failed" are different facts.
            val answer = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    fanart.clearlogoAnswer(
                        kind = if (isMovie) FanartKind.Movie else FanartKind.Tv,
                        id = fanartId
                    )
                }
            }.getOrNull()
            FanartLogoAnswer(
                url = answer?.url,
                answered = answer?.answered == true
            )
        }
    )
}
