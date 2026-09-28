package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository

/**
 * The key one item's landscape-card artwork is filed under.
 *
 * Four separate places share this string and none of them is compiled against
 * the others' use of it: the rail builders write `Rail.landscapeArt` under it,
 * HomeScreen's landscape card reads it back, the view model files the art it has
 * already resolved under it (see `previousLandscapeArt` and the per-build memo
 * beside it), and the KB folder screens - the app's second place that resolves
 * landscape art - both write and read their own map with it. A spelling that
 * drifted in any one of them would neither fail to compile nor throw: the lookup
 * would return null, every card would fall back to its add-on backdrop, and
 * every rebuild would resolve artwork it had already resolved. That is a silent
 * failure wearing a plausible symptom ("the landscape cards look plain"), so the
 * format is built here, in one place, and pinned by `LandscapeArtKeyTest`.
 *
 * The type in the key is the type the artwork was looked up BY
 * ([TmdbRepository.normalizeMediaType]), not the add-on's raw string, because
 * that is the only spelling two different sources of the same title can be
 * expected to agree on: an add-on calling a show "tv" and another calling it
 * "series" - or "anime.series" - have to file one entry or the second spelling
 * pays for a second lookup. It is also what makes a film's backdrop
 * unreachable from a show's card, since a TMDB and an add-on id can be the same
 * string.
 *
 * [id] is whatever the add-on called the title and is deliberately not parsed
 * here - for TMDB-sourced rails it already carries its own namespace
 * ("tmdb:603"), so the result has more than one colon in it. The key is opaque:
 * only ever built and compared, never taken apart.
 */
internal fun landscapeArtKey(type: String, id: String): String =
    "${TmdbRepository.normalizeMediaType(type)}:$id"

/**
 * [landscapeArtKey] for a caller holding the item rather than its parts, which
 * is what the rail builders, the card, the view model and the KB folder layouts
 * all have.
 *
 * One spelling of the format, not two: this must stay a delegation.
 */
internal fun MetaPreview.landscapeArtKey(): String = landscapeArtKey(type, id)
