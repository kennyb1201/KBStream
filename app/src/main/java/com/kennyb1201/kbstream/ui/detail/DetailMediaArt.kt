package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.fanart.FanartArt
import com.kennyb1201.kbstream.data.fanart.fanartBigPreviewUrl
import com.kennyb1201.kbstream.data.tmdb.TmdbImageAsset
import com.kennyb1201.kbstream.data.tmdb.tmdbImage

/**
 * The Media row's artwork from BOTH sources (spec 2026-11-15, fanart in the
 * Detail Media rail).
 *
 * The rail's Backdrops and Posters pages were TMDB-only: fanart.tv's bundle was
 * parsed and its logos and season posters used, but its `backgrounds` fed only
 * the full-screen backdrop fallback, and it had no poster list parsed at all.
 * This object merges what fanart does have INTO the pages TMDB already fills.
 *
 * ## The rules, which are the whole spec
 *
 * - **TMDB first, always.** Every TMDB image keeps its place and its order; the
 *   fanart entries are appended after them. Fanart never displaces, reorders or
 *   replaces a TMDB image - the same rule the clearlogo and backdrop fallbacks
 *   already follow, applied to a list instead of a single slot.
 * - **Deduped by URL.** An exact-dupe URL appears once. A near-dupe (the same
 *   picture served under two hosts) is accepted: two big images compared
 *   pixel-wise is not something a rail's card projection can afford, and the
 *   cost of being wrong is one duplicate tile.
 * - **Fail soft.** [fanart] is null for every reason at once - no key in the
 *   build, a proven miss, a failed call - and the answer is then exactly the
 *   TMDB-only pages the rail drew before. Nothing here waits, loads, or retries.
 * - **No badge, no label.** Fanart cards draw exactly like TMDB's: same card,
 *   same size, same tap. The rail has never labelled a source and this does not
 *   invent one.
 * - **Fanart cards carry no `imagePath`.** That field is TMDB's own
 *   `file_path`, and the long-press override menu stores it for the surfaces
 *   that build their own URLs from it. A fanart URL is not a path and cannot be
 *   one, so a fanart card simply offers no override rows - the menu row is
 *   drawn only when the card has a path (see the Media card's long press).
 * - **Card and full-size URLs.** A fanart image is 1920x1080 or larger and the
 *   card is a few hundred wide, so the card draws fanart's `bigpreview` (400px)
 *   and the fullscreen view draws the original - the same split
 *   [fanartBigPreviewUrl] exists for on the season poster.
 *
 * Pure: no Compose, no repository, no network, so the merge order is pinnable
 * from a JVM test.
 */
internal object DetailMediaArt {

    /**
     * One entry of a merged page: where it came from, and the two URLs a card
     * needs.
     *
     * [tmdbPath] is non-null only for a TMDB entry - it is what the override
     * menu stores, and its absence is what makes a fanart card offer none.
     */
    internal data class MediaArtSource(
        val tmdbPath: String?,
        val remoteUrl: String?,
        val cardUrl: String?,
        val fullUrl: String?
    ) {
        /** Stable, unique within the page: the path or the URL, never a size. */
        val identity: String
            get() = tmdbPath ?: remoteUrl.orEmpty()
    }

    /**
     * The merged Backdrops page: TMDB's backdrops in TMDB's order, then fanart's
     * `backgrounds`.
     *
     * `backgrounds4k` is deliberately NOT merged. It is full-resolution art for a
     * surface that draws the whole screen (the hero's backdrop fallback asks for
     * it explicitly - see `FanartArtRepository.backdrop(want4k = true)`), and the
     * rail draws small cards; adding it would spend a second full-size entry per
     * title to paint a tile that is already covered by the 1080p set. It is
     * consumed - by that fallback - so it is neither merged here nor dropped.
     */
    fun backdrops(
        tmdb: List<TmdbImageAsset>,
        fanart: FanartArt?
    ): List<MediaCardItem> = cards(
        sources = merge(
            tmdbPaths = tmdb.map { it.filePath },
            remoteUrls = fanart?.backgrounds.orEmpty().map { it.url },
            cardSize = BACKDROP_CARD_SIZE,
            fullSize = BACKDROP_FULL_SIZE
        ),
        idPrefix = "backdrop",
        title = "Backdrop",
        portrait = false
    )

    /** The merged Posters page, the same rules on the portrait page. */
    fun posters(
        tmdb: List<TmdbImageAsset>,
        fanart: FanartArt?
    ): List<MediaCardItem> = cards(
        sources = merge(
            tmdbPaths = tmdb.map { it.filePath },
            remoteUrls = fanart?.posters.orEmpty().map { it.url },
            cardSize = POSTER_CARD_SIZE,
            fullSize = POSTER_FULL_SIZE
        ),
        idPrefix = "poster",
        title = "Poster",
        portrait = true
    )

    /**
     * The merge itself: TMDB's entries first, in their order, then the remote
     * ones, deduped by URL.
     *
     * A blank or unbuildable TMDB path is skipped (it produces no card anyway),
     * and a remote URL that matches a TMDB entry's card or full-size URL exactly
     * is skipped - the one duplicate S5 names.
     */
    fun merge(
        tmdbPaths: List<String?>,
        remoteUrls: List<String>,
        cardSize: String,
        fullSize: String
    ): List<MediaArtSource> {
        val out = mutableListOf<MediaArtSource>()
        val seenUrls = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()

        tmdbPaths.forEach { raw ->
            val path = raw?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!seenPaths.add(path)) return@forEach
            val card = tmdbImage(path, cardSize) ?: return@forEach
            val full = tmdbImage(path, fullSize)
            seenUrls += normalizeUrl(card)
            full?.let { seenUrls += normalizeUrl(it) }
            out += MediaArtSource(
                tmdbPath = path,
                remoteUrl = null,
                cardUrl = card,
                fullUrl = full
            )
        }

        remoteUrls.forEach { raw ->
            val url = raw.trim().takeIf { it.isNotEmpty() } ?: return@forEach
            val normalized = normalizeUrl(url)
            if (!seenUrls.add(normalized)) return@forEach
            out += MediaArtSource(
                tmdbPath = null,
                remoteUrl = url,
                cardUrl = fanartBigPreviewUrl(url),
                fullUrl = url
            )
        }
        return out
    }

    /**
     * URL identity for the dedupe. Case and surrounding space are the only things
     * normalized: two URLs that differ by a query string or a size token are
     * different entries here, on purpose (a near-dupe is accepted, see the file
     * header).
     */
    private fun normalizeUrl(url: String): String = url.trim().lowercase()

    private fun cards(
        sources: List<MediaArtSource>,
        idPrefix: String,
        title: String,
        portrait: Boolean
    ): List<MediaCardItem> = sources.map { source ->
        MediaCardItem(
            id = "$idPrefix:${source.identity}",
            imageUrl = source.cardUrl,
            badge = null,
            title = title,
            fullImageUrl = source.fullUrl,
            portrait = portrait,
            imagePath = source.tmdbPath
        )
    }

    /** The rungs the rail's backdrop card and its fullscreen view draw at. */
    private const val BACKDROP_CARD_SIZE = "w780"
    private const val BACKDROP_FULL_SIZE = "w1280"

    /** The rungs the poster page's portrait card and its fullscreen view use. */
    private const val POSTER_CARD_SIZE = "w500"
    private const val POSTER_FULL_SIZE = "w780"
}
