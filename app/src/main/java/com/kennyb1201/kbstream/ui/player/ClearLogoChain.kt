package com.kennyb1201.kbstream.ui.player

/**
 * The clearlogo ladder for a player launch that arrived without a logo (spec
 * 2026-11-22 F4), made drivable so its diagnostics can be pinned (spec
 * 2026-11-23 F2).
 *
 * Nothing here touches Android, TMDB or fanart: every collaborator is a
 * parameter, which is what lets `PlayerClearLogoLadderTraceTest` run all the
 * outcome shapes on the JVM. The real adapters live in `resolveClearLogoUrl`,
 * one call away.
 *
 * ## The `LOGO:` trace
 *
 * A field report could show the logo in the Detail header and in the Continue
 * Watching row, and still get the plain title on both the splash and the
 * overlay of the player that launch opened. Every step of this chain fails
 * silently by design, so the report could not say WHICH step answered nothing,
 * and two days of tree-reading did not settle it. Every step therefore notes
 * one line, once per lookup, into the playback session trace that COPY
 * DIAGNOSTICS already prints:
 *
 * 1. `LOGO: tvdb <id|none> provenNone=<true|false>` - series only, the one
 *    step a movie does not have
 * 2. `LOGO: fanart <url|none> reason=<hit|no-logo|failed|no-twin>`
 * 3. `LOGO: applied <url>` or `LOGO: none text-stands`
 *
 * with the launch line and the TMDB step either side of them (see
 * [logoLaunchLine] and [resolveClearLogoChain]).
 *
 * Diagnostics only: the lines change no decision, no precedence and no
 * fallback, and nothing here can re-note the player's 150/500/1200ms policy
 * rechecks - the lookup itself is latched once per session by its callers, so
 * this function runs once per launch.
 */

/**
 * The launch half of the `LOGO:` trace: one line, once per player launch,
 * whatever the launch carried.
 *
 * `extra=present` is itself an answer - the chain never runs, so no further
 * `LOGO:` lines follow and the logo came with the intent. `parentId` is printed
 * as seen, not normalized: a Continue Watching launch handing the player
 * `tmdb:123` where Detail hands it `123` is exactly the kind of difference the
 * field report could not show.
 */
internal fun logoLaunchLine(
    engine: String,
    carriedLogo: Boolean,
    parentId: String,
    parentType: String
): String =
    "LOGO: start engine=$engine extra=${if (carriedLogo) "present" else "blank"} " +
        "parentId=${parentId.ifBlank { "blank" }} " +
        "parentType=${parentType.ifBlank { "blank" }}"

/**
 * What the TMDB step answered, in the shape the ladder reads.
 *
 * [answered] says a record came back at all; [callFailed] says the call threw
 * rather than TMDB having nothing, which is the difference the field report
 * needs between "a transient failure" and "no TMDB record for this id".
 */
internal data class LogoDetailAnswer(
    val answered: Boolean,
    val callFailed: Boolean,
    val logoPath: String?,
    val detailId: Int,
    val source: String?
)

/**
 * What the fanart step answered, and whether it answered at all.
 *
 * Mirrors the repository's own `LogoAnswer`: [url] null with `answered` true is
 * "fanart has no logo for this twin", `answered` false is "the call failed",
 * and only the first is a finished answer.
 */
internal data class FanartLogoAnswer(val url: String?, val answered: Boolean)

/**
 * The ladder itself.
 *
 * [type] is one of the two types the chain supports - `movie` or `series`; the
 * caller has already normalized it. [logoBase] is the TMDB image base a wordmark
 * path hangs off.
 */
internal suspend fun resolveClearLogoChain(
    parentId: String,
    type: String,
    logoBase: String,
    trace: (String) -> Unit,
    fetchDetail: suspend (String) -> LogoDetailAnswer,
    resolveTvdbId: suspend (Int) -> Int?,
    provenNoTvdbId: (Int) -> Boolean,
    fanartLogo: suspend (isMovie: Boolean, fanartId: Int) -> FanartLogoAnswer
): String? {
    val detail = fetchDetail(parentId)
    val detailLine = buildString {
        append("LOGO: tmdb ")
        append(if (detail.answered) "hit" else "miss")
        append(" source=").append(detail.source ?: "none")
        append(" detailId=").append(if (detail.detailId > 0) detail.detailId.toString() else "none")
        append(" logoPath=").append(detail.logoPath ?: "none")
        if (!detail.answered) {
            append(" reason=").append(if (detail.callFailed) "call-failed" else "no-record")
        }
    }
    trace(detailLine)

    detail.logoPath?.let { path ->
        val url = logoBase + path
        trace("LOGO: applied $url")
        return url
    }

    val isMovie = type != "series"
    val artId = detail.detailId.takeIf { it > 0 }
    if (artId == null) {
        // No detail, no twin: fanart has no endpoint that could answer this id.
        trace("LOGO: fanart none reason=no-twin")
        trace("LOGO: none text-stands")
        return null
    }

    val twin = if (isMovie) {
        artId
    } else {
        val resolved = resolveTvdbId(artId)
        trace("LOGO: tvdb ${resolved ?: "none"} provenNone=${resolved == null && provenNoTvdbId(artId)}")
        resolved?.takeIf { it > 0 }
    }
    if (twin == null) {
        trace("LOGO: fanart none reason=no-twin")
        trace("LOGO: none text-stands")
        return null
    }

    val answer = fanartLogo(isMovie, twin)
    val url = answer.url?.takeIf { it.isNotBlank() }
    trace(
        "LOGO: fanart ${url ?: "none"} reason=" +
            when {
                url != null -> "hit"
                answer.answered -> "no-logo"
                else -> "failed"
            }
    )
    if (url == null) {
        trace("LOGO: none text-stands")
        return null
    }
    trace("LOGO: applied $url")
    return url
}
