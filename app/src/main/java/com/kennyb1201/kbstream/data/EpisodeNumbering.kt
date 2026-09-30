package com.kennyb1201.kbstream.data

/**
 * The episode number a source actually named, or null when it named none.
 *
 * A literal `0` is how an upstream source spells "no episode here", and the
 * app was reading it as a real one. It gets in from three directions:
 *
 *  - the tracker's playback payload carries `"episode": 0` for a paused
 *    session it could not place, and its `next_to_watch` / `last_watched`
 *    strings can read `"S2E0"`;
 *  - a Stremio-style add-on lists a show's videos with `"episode": 0` and no
 *    title, which is the shape its fallback episode browser serves;
 *  - TMDB sometimes files a `- Specials` row inside a regular season, numbered
 *    0, with no still and no name.
 *
 * The damage was visible on two screens at once, which is what made it look
 * like a data problem rather than a bug: the Continue Watching card formats its
 * label as `S%02d · E%02d` and so read "S02 · E00", the player said "Season 2
 * Episode 00", and every chip in the season browser read "EPISODE 0" over blank
 * artwork. Reported as "continue watching says season 2 episode 00, and when I
 * go to details all the episode chips are blank and say episode 0".
 *
 * No real episode is numbered 0, so the test is total and belongs in one place:
 * every boundary that takes a number from outside the app runs it through here,
 * and a 0 then falls back to whatever the app would have done if the source had
 * said nothing - which, for a resume card, is the next unwatched episode TMDB
 * knows about rather than a card labelled E00.
 *
 * Seasons are deliberately NOT treated this way: season 0 is the real,
 * TMDB-defined home of a show's specials, and a season number is never an
 * episode number.
 */
internal fun namedEpisodeNumber(value: Int?): Int? = value?.takeIf { it > 0 }
