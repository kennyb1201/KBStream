package com.kennyb1201.kbstream.ui.home

/**
 * The expanded card's meta line, assembled from its parts (spec 2026-11-15):
 * year • length • runtime • rating • genre.
 *
 * Pure strings on purpose. The line is the one place this spec could go wrong in
 * a way review would not catch - an empty count still taking a slot (`, , `), the
 * counts landing after the runtime instead of in the length slot, or a movie's
 * line changing because a null was passed as a string - and none of that needs a
 * Composable, a `Meta` or a device to pin.
 *
 * A blank part is SKIPPED, never joined: "no counts" must leave the line exactly
 * as it was, not a doubled separator where the counts would have been.
 */
internal fun spotlightMetaLineParts(
    year: String?,
    length: String?,
    runtime: String?,
    rating: String?,
    genre: String?
): String {
    val parts = ArrayList<String>(5)
    year?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    // The length slot: the settled title's "8 Seasons • 164 Episodes", where the
    // Detail page's own line keeps it (seasons • episodes • runtime).
    length?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    runtime?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    rating?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "\u2605 $it" }
    genre?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    return parts.joinToString(" \u00b7 ")
}
