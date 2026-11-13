package com.kennyb1201.kbstream.widget

import com.kennyb1201.kbstream.MainActivity.Screen
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.home.upNextPlayerDisplayName

/**
 * A widget row tap, resolved into the destination a Continue Watching card press
 * resolves into (spec 2026-11-13, F1).
 *
 * The destination is `Screen.Detail` with `pendingTarget` set: the Detail screen
 * treats a non-null target as "play now" (`isDeepLinkAutoPlay`), so the row goes
 * straight into the player at the stored playhead - the same one-tap resume the
 * Home rail's card press uses, not a copy of it. `returnTo = Home` is what Back
 * from the player lands on, exactly as it does from the rail.
 *
 * Pure on purpose: every value comes from the snapshot row's extras, so the
 * routing can be pinned by a test without an Activity.
 */
internal fun cwWidgetDetailScreen(
    parentType: String?,
    parentId: String,
    streamId: String?,
    showTitle: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    positionMs: Long,
    poster: String?
): Screen.Detail {
    val id = parentId.trim()
    // The history row's type is the app's media type; a launcher-style "tv" is
    // normalized the way the launcher route normalizes it, so a card that came
    // from a TV-sourced row still opens the series page.
    val type = when (parentType?.trim()?.lowercase()) {
        "tv", "series", null, "" -> "series"
        else -> "movie"
    }
    val named = showTitle?.trim()?.takeIf { it.isNotBlank() }
        ?: episodeTitle?.trim()?.takeIf { it.isNotBlank() }
        ?: id

    val composedTitle = if (type == "series" && season != null && episode != null && episode > 0) {
        "$named S$season E$episode"
    } else {
        named
    }

    return Screen.Detail(
        type = type,
        id = id,
        pendingTarget = StreamsTarget(
            contentType = type,
            // A film's stream id is the title's; an episode's is its own, and a
            // row that carried none falls back to the parent so the resume request
            // is still addressable rather than a blank stream.
            streamId = streamId?.trim()?.takeIf { it.isNotBlank() } ?: id,
            title = composedTitle,
            displayName = upNextPlayerDisplayName(
                cardTitle = named,
                showTitle = named,
                composedTitle = composedTitle,
                hasArtwork = !poster.isNullOrBlank()
            ),
            season = if (type == "series") season else null,
            episode = if (type == "series") episode else null,
            resumePositionMs = positionMs.coerceAtLeast(0L)
        ),
        itemPoster = poster?.takeIf { it.isNotBlank() },
        returnTo = Screen.Home
    )
}
