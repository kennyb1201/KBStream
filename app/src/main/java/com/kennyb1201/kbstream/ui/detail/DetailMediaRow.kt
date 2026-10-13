package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.tmdb.TmdbImageAsset
import com.kennyb1201.kbstream.data.tmdb.TmdbVideo
import com.kennyb1201.kbstream.data.tmdb.tmdbImage
import java.util.Locale

/**
 * The two pages of the detail page's Media row.
 *
 * The row is ONE row of the vertical focus chain ([DetailRow.MEDIA]) that
 * happens to hold two rails, swapped by its tab strip. Which of them a viewer
 * is looking at is the row's own business; only UP and DOWN out of the row are
 * the chain's.
 */
internal enum class MediaTab(val label: String) {
    /** Every video TMDB lists for the title, in TMDB's own order. */
    TRAILERS("Trailers"),

    /** Every backdrop TMDB lists for the title, in TMDB's own order. */
    BACKDROPS("Backdrops")
}

/**
 * One card of the Media row, whichever tab it belongs to.
 *
 * [video] and [fullImageUrl] are what separates the two kinds: a trailer card
 * hands its video to the trailer player, a backdrop card opens its still
 * fullscreen. Exactly one of them is set.
 */
internal data class MediaCardItem(
    /** Unique within the rail, for the lazy list's key. */
    val id: String,
    /** The thumbnail. Null when the video is not a YouTube one and TMDB sent
     * no thumbnail of its own. */
    val imageUrl: String?,
    /** The type badge (TRAILER, TEASER, CLIP...), or null for a backdrop. */
    val badge: String?,
    /** What a screen reader and a copy-to-clipboard name this card. */
    val title: String,
    /** The video this card plays, or null for a backdrop still. */
    val video: TmdbVideo? = null,
    /** The still a fullscreen view shows, or null for a video card. */
    val fullImageUrl: String? = null
)

/**
 * The Media row's rules: which pages it has, which page Left/Right reaches, and
 * what a card of either page actually is.
 *
 * Kept pure and separate from the screen for the same reason as
 * [DetailChipColumns] and [DetailFocusChain]: what uses it is a key handler and
 * a lazy list, neither of which a unit test can press. The projection is here
 * rather than inline in the screen so the thumbnail rule (a YouTube key is the
 * only thing that gives a YouTube thumbnail URL) can be pinned without a
 * device.
 */
internal object DetailMediaRow {

    /**
     * The pages this title has, in the order the strip shows them.
     *
     * A page with nothing on it is not offered at all: an empty tab is a
     * surface whose only outcome is a press that does nothing, and it is also
     * what keeps the strip's DOWN always landing on a card - the strip is only
     * painted when at least one of the two lists is non-empty.
     */
    fun tabs(videoCount: Int, backdropCount: Int): List<MediaTab> = buildList {
        if (videoCount > 0) add(MediaTab.TRAILERS)
        if (backdropCount > 0) add(MediaTab.BACKDROPS)
    }

    /**
     * The index `step` places away from [index], CLAMPED to the strip rather
     * than wrapped.
     *
     * Left on the first tab is a press with nowhere to go, and a D-pad that
     * wrapped would make it jump to the last one - a different page from the
     * one the viewer was reading, from a key that reads as "back". Clamping
     * leaves the active page where it is; the caller still consumes the press,
     * so the focus cannot leave the strip sideways either.
     */
    fun switched(index: Int, step: Int, count: Int): Int =
        if (count <= 0) 0 else (index + step).coerceIn(0, count - 1)

    /**
     * The trailer page: every video TMDB lists, in TMDB's order - the same list
     * the header's trailer button picks its one "best" entry out of (see
     * [com.kennyb1201.kbstream.data.tmdb.TrailerPick]), which is exactly what
     * this page exists to show past.
     */
    fun trailers(videos: List<TmdbVideo>): List<MediaCardItem> =
        videos.mapIndexed { index, video ->
            val type = video.type.trim()
            MediaCardItem(
                // Keyed by index as well as by the YouTube key: TMDB lists the
                // same key more than once on a re-upload (a 4K and an HD copy
                // of one trailer), and a lazy list's keys have to be unique.
                id = "video:$index:${video.key}",
                imageUrl = thumbnailUrl(video),
                badge = type.uppercase(Locale.US).takeIf { it.isNotEmpty() },
                title = video.name?.trim()?.takeIf { it.isNotEmpty() } ?: type,
                video = video
            )
        }

    /**
     * The backdrop page: every backdrop TMDB lists, in TMDB's order, at
     * `w780` for the card and `w1280` for the fullscreen view.
     *
     * The hero's own `backdrop_path` is left IN: this page is "all the art for
     * this title", and a viewer who came here to look at stills is not helped
     * by the one they have already seen on the hero being missing from the
     * set.
     */
    fun backdrops(backdrops: List<TmdbImageAsset>): List<MediaCardItem> =
        backdrops.mapNotNull { asset ->
            val path = asset.filePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MediaCardItem(
                id = "backdrop:$path",
                imageUrl = tmdbImage(path, "w780"),
                badge = null,
                title = "Backdrop",
                fullImageUrl = tmdbImage(path, "w1280")
            )
        }

    /**
     * The YouTube still for a video, [`hqdefault.jpg`], which the video host
     * serves for every id it knows - no API key, no resolve, one URL.
     *
     * A video on any other SITE has no such URL, so it falls back to whatever
     * thumbnail TMDB sent with the video itself. The site check is what keeps
     * a Vimeo key from being pasted into a YouTube path and painted as a
     * broken-image tile.
     */
    fun youtubeThumbnailUrl(video: TmdbVideo): String? =
        if (video.site.equals("YouTube", ignoreCase = true) && video.key.isNotBlank()) {
            "https://img.youtube.com/vi/${video.key}/hqdefault.jpg"
        } else {
            null
        }

    private fun thumbnailUrl(video: TmdbVideo): String? =
        youtubeThumbnailUrl(video) ?: video.thumbnail?.takeIf { it.isNotBlank() }
}
