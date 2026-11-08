package com.kennyb1201.kbstream.data.sports

/**
 * The one-line score a guide cell draws on its second line (round-2 F9 / F3).
 *
 * The hub's card and the guide's cell want the same sentence, so it is written
 * once here rather than in either surface: a live game reads
 * `TB 3 - 2 MIN · 2nd 14:32` (the two sides' own codes, the score, and ESPN's
 * clock), and a finished one reads `Final 3 - 2` - the clock is gone, because
 * there is no clock left to report.
 *
 * Null says "there is nothing to draw", and it is not an error: a game that has
 * not started has no score, and a feed that carries no score for a side must not
 * have one invented from a 0. The grid draws the reminder line in that case,
 * which is what it did before scores existed.
 *
 * Pure: no clock of its own, no I/O. The state and the strings are ESPN's, held
 * verbatim - the guide is a second surface for the hub's data, not a second
 * opinion about it.
 */
internal fun gameScoreLine(game: SportsGame): String? {
    // Only a game that is being played or has finished has a score to show. An
    // upcoming one would read "0 - 0", which is a lie dressed as a result.
    if (game.state == GameState.UPCOMING) return null

    val away = game.away.score?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val home = game.home.score?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    if (game.state == GameState.FINAL) return "Final $away – $home"

    // The codes are ESPN's own, and a sport whose "sides" are people can carry
    // none: then the score stands alone rather than reading as a bare gap.
    val awayCode = game.away.abbreviation.trim()
    val homeCode = game.home.abbreviation.trim()
    val teams = if (awayCode.isNotEmpty() && homeCode.isNotEmpty()) {
        "$awayCode $away – $home $homeCode"
    } else {
        "$away – $home"
    }
    val clock = game.statusDetail.trim().takeIf { it.isNotEmpty() }
    return if (clock == null) teams else "$teams · $clock"
}
