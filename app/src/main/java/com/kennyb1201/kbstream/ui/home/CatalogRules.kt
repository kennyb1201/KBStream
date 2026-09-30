package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import java.time.LocalDate

/**
 * The catalog rules Home applies to whatever an addon hands back: how
 * a raw catalog name is spelled, what media type a source claims to
 * be, and which titles are not out yet. Extracted from HomeViewModel
 * so the same three answers cannot drift between the rails, the
 * catalog grid and the pinned rows.
 */

internal fun formatCatalogName(
    name: String
): String {

    return name
        .replace(
            "_",
            " "
        )
        .split(" ")
        .joinToString(" ") { word ->

            word.lowercase()
                .replaceFirstChar {
                    it.uppercase()
                }
        }
}

internal fun normalizeMediaType(
    type: String?
): String? =

    when (
        type?.lowercase()
    ) {

        "movie" ->
            "movie"

        "series",
        "show",
        "tv" ->
            "series"

        else ->
            null
    }

/**
 * Drops titles whose release date (from the catalog's releaseInfo field,
 * e.g. "2026-12-25T00:00:00.000Z") is in the future. Titles with no or
 * unparseable release info are always kept — the filter only removes
 * items we can positively tell are not out yet.
 */
internal fun filterUpcoming(
    metas: List<MetaPreview>
): List<MetaPreview> {

    val today = LocalDate.now()

    return metas.filter { meta ->

        val raw = meta.releaseInfo
            ?.trim()
            .orEmpty()

        if (raw.isEmpty()) {
            return@filter true
        }

        parseReleaseDate(raw)?.let { date ->

            !date.isAfter(today)

        } ?: run {

            // Bare year (the common catalog shape, e.g. "2026"):
            // hide only when the year is entirely in the future —
            // the current year is ambiguous, so keep it. Anything
            // unparseable is kept too.
            val year = raw.toIntOrNull()

            year == null || year <= today.year
        }
    }
}
