package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview

/**
 * One Home card as the watched-marker pass sees it: the `(id, type)` pair the
 * watched caches are keyed by, or null when the card could not carry a marker
 * anyway.
 *
 * Accepts BOTH id forms the cards carry: IMDb `tt…` ids (add-ons, Continue
 * Watching, every tracker-backed Library row that has one) and `tmdb:<n>` ids
 * (KB/TMDB-discover rails, the hardcoded kids rails, a Library row with no IMDb
 * id). The old tt-only filter silently dropped every tmdb-keyed item, which is
 * why the kids profile showed no watched markers: its rails are 100% tmdb ids.
 * The repository stores and resolves both forms under the same `type::id` cache
 * key, so no conversion is needed.
 *
 * This is the ONE rule for every source that draws a Home card - catalog rails,
 * Top Picks and the pinned Library rails - so a title resolves the same way
 * however it reached the screen.
 */
internal fun watchedMarkerItem(
    meta: MetaPreview
): Pair<String, String>? {

    val id =
        meta.id
            .trim()
            .takeIf {
                it.startsWith("tt") ||
                    it.startsWith("tmdb:")
            }
            ?: return null

    val mediaType =
        normalizeMediaType(
            meta.type
        )
            ?: return null

    return id to mediaType
}

/**
 * Every title the marker pass must cover, from all three sources Home draws.
 *
 * One list, not three: it feeds the preload AND the sibling-ownership filter,
 * and the two must cover the same titles. A title another profile owns that
 * appears only on a pinned Library list (or only in Top Picks) was outside the
 * ownership set while it was built from the catalog rails alone, so the filter
 * had no id to strip and the PAW Patrol eye could be painted on the wrong
 * profile.
 *
 * Rows are deduped by content (`type`, `id`), so a title that is on a catalog
 * rail and a pinned list is preloaded once and cannot wear a double badge.
 */
internal fun watchedMarkerMetas(
    railItems: List<MetaPreview>,
    topPickItems: List<MetaPreview>,
    libraryItems: List<MetaPreview>
): List<MetaPreview> =
    (railItems + topPickItems + libraryItems)
        .distinctBy { meta -> rowKey(meta) }

/** [watchedMarkerMetas] as the preload input the repository takes, deduped. */
internal fun watchedMarkerPreload(
    metas: List<MetaPreview>
): List<Pair<String, String>> =
    metas
        .asSequence()
        .mapNotNull { meta -> watchedMarkerItem(meta) }
        .distinct()
        .toList()

/**
 * Content identity of a reported row set: the `(type, id)` pairs alone.
 *
 * The pinned rails are re-reported by both Home screens and by every recompose
 * that hands over new list instances, so a re-delivery must be recognisable as
 * the same rows. Artwork or description changes are not a new set - they cannot
 * change a marker - and comparing by list identity would start a refresh per
 * frame.
 */
internal fun libraryRailItemKeys(
    items: List<MetaPreview>
): Set<Pair<String, String>> {

    val keys =
        mutableSetOf<Pair<String, String>>()

    items.forEach { meta -> keys += rowKey(meta) }

    return keys
}

private fun rowKey(
    meta: MetaPreview
): Pair<String, String> =
    meta.type.trim().lowercase() to meta.id.trim()
