package com.kennyb1201.kbstream.ui.kb

import com.kennyb1201.kbstream.data.tmdb.TmdbRepository

/**
 * The media type worth a TMDB artwork lookup for a KB folder item, or null when
 * the type cannot have one.
 *
 * Deliberately a different question from the artwork KEY, which
 * `ui/home/LandscapeArtKey.kt` answers for every item whatever its type: a
 * reader has to be able to derive the key of an entry the resolver filed even
 * when the resolver decided not to look anything up, or the entry is invisible
 * and the item is re-attempted forever. This one is only asked by the resolver,
 * and it says no for a type TMDB has nothing to say about - a channel, a folder
 * typed by some add-on's own vocabulary - so those cost no round trip at all,
 * just a filed "no artwork" answer.
 */
internal fun kbArtworkLookupType(type: String): String? =
    when (val normalized = TmdbRepository.normalizeMediaType(type)) {
        "movie", "series" -> normalized
        else -> null
    }
