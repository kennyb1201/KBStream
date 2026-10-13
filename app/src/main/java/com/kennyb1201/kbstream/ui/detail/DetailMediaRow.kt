package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.tmdb.TmdbImageAsset
import com.kennyb1201.kbstream.data.tmdb.TmdbVideo
import com.kennyb1201.kbstream.data.tmdb.tmdbImage
import java.util.Locale

/**
 * The pages of the detail page's Media row, in the order the strip shows them.
 *
 * The row is ONE row of the vertical focus chain ([DetailRow.MEDIA]) that
 * happens to hold several rails, swapped by its tab strip. Which of them a
 * viewer is looking at is the row's own business; only UP and DOWN out of the
 * row are the chain's.
 */
internal enum class MediaTab(val label: String) {
    /** Every video TMDB lists for the title, in TMDB's own order. */
    TRAILERS("Trailers"),

    /** Every backdrop TMDB lists for the title, in TMDB's own order. */
    BACKDROPS("Backdrops"),

    /** Every poster TMDB lists for the title, in TMDB's own order. */
    POSTERS("Posters")
}

/**
 * One card of the Media row, whichever tab it belongs to.
 *
 * [video] and [fullImageUrl] are what separates the two kinds: a trailer card
 * hands its video to the trailer player, an artwork card (a backdrop or a
 * poster) opens itself fullscreen. Exactly one of them is set.
 */
internal data class MediaCardItem(
    /** Unique within the rail, for the lazy list's key. */
    val id: String,
    /** The thumbnail. Null when the video is not a YouTube one and TMDB sent
     * no thumbnail of its own. */
    val imageUrl: String?,
    /** The type badge (TRAILER, TEASER, CLIP...), or null for artwork. */
    val badge: String?,
    /** What a screen reader and a copy-to-clipboard name this card. */
    val title: String,
    /** The video this card plays, or null for an artwork card. */
    val video: TmdbVideo? = null,
    /** The image a fullscreen view shows, or null for a video card. */
    val fullImageUrl: String? = null,
    /**
     * Whether the card draws PORTRAIT artwork: the Posters page is the one
     * page of this row that is not a 16:9 still, and a poster shown as one
     * would be cropped through the middle of its face.
     */
    val portrait: Boolean = false,
    /**
     * TMDB's own `file_path` for the artwork, or null on a video card.
     *
     * What a long press stores as the title's choice (see
     * [com.kennyb1201.kbstream.data.images.ImageOverrides]): the URL above is
     * built for THIS card's size, and the surfaces that will draw the choice
     * want their own sizes, so the path is what travels.
     */
    val imagePath: String? = null
)

/**
 * One row of the long-press menu a Media card opens: what it says, and which
 * surface of the title it writes.
 *
 * A value rather than a built menu row, so which rows a card offers (and what
 * each one means) can be pinned without a device - see
 * [DetailMediaRow.overrideRows].
 */
internal data class ImageOverrideMenuItem(
    val label: String,
    val description: String,
    /**
     * The surface this row sets, or null for the reset row (which clears every
     * surface of the card's kind - see
     * [com.kennyb1201.kbstream.data.images.ImageOverrides.reset]).
     */
    val screen: String?,
    val isDestructive: Boolean = false
)

/**
 * The Media row's rules: which pages it has, which page Left/Right reaches, and
 * what a card of any of them actually is.
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
     * painted when at least one of the three lists is non-empty.
     */
    fun tabs(
        videoCount: Int,
        backdropCount: Int,
        posterCount: Int
    ): List<MediaTab> = buildList {
        if (videoCount > 0) add(MediaTab.TRAILERS)
        if (backdropCount > 0) add(MediaTab.BACKDROPS)
        if (posterCount > 0) add(MediaTab.POSTERS)
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
                fullImageUrl = tmdbImage(path, "w1280"),
                imagePath = path
            )
        }

    /**
     * The long-press rows [tab]'s cards offer, in the order they are drawn.
     *
     * Backdrops and posters are the two pages a viewer can CHOOSE from: a
     * still on the Backdrops page is a candidate for each of the surfaces
     * that draw a backdrop (see [ImageOverrides]), and a poster covers the one
     * that draws a poster everywhere. The Trailers page offers nothing here -
     * a video is not artwork, and there is no surface whose image a trailer
     * could stand in for.
     *
     * [hasOverride] adds the reset row, which appears only once there is
     * something to reset - the same rule the rest of the app's menus use for
     * rows that would otherwise do nothing.
     */
    fun overrideRows(tab: MediaTab?, hasOverride: Boolean): List<ImageOverrideMenuItem> =
        when (tab) {
            MediaTab.BACKDROPS -> {
                listOf(
                    ImageOverrideMenuItem(
                        label = "Set as Detail backdrop",
                        description = "Show this still on the title's detail page",
                        screen = ImageOverrides.SCREEN_DETAIL
                    ),
                    ImageOverrideMenuItem(
                        label = "Set as Streams backdrop",
                        description = "Show this still behind the source list",
                        screen = ImageOverrides.SCREEN_STREAMS
                    ),
                    ImageOverrideMenuItem(
                        label = "Set as Player splash",
                        description = "Show this still on the loading splash before playback",
                        screen = ImageOverrides.SCREEN_PLAYER_SPLASH
                    ),
                    ImageOverrideMenuItem(
                        label = "Set as Home hero backdrop",
                        description = "Show this still when the title is featured on Home",
                        screen = ImageOverrides.SCREEN_HOME_HERO
                    ),
                    ImageOverrideMenuItem(
                        label = "Set as Landscape card",
                        description = "Show this still on the title's landscape cards",
                        screen = ImageOverrides.SCREEN_LANDSCAPE_CARD
                    )
                ) + resetRow(hasOverride)
            }
            MediaTab.POSTERS -> {
                listOf(
                    ImageOverrideMenuItem(
                        label = "Set as Poster",
                        description = "Show this artwork wherever the title's poster appears",
                        screen = ImageOverrides.SCREEN_POSTER
                    )
                ) + resetRow(hasOverride)
            }
            MediaTab.TRAILERS, null -> emptyList()
        }

    private fun resetRow(hasOverride: Boolean): List<ImageOverrideMenuItem> =
        if (!hasOverride) {
            emptyList()
        } else {
            listOf(
                ImageOverrideMenuItem(
                    label = "Reset to default",
                    description = "Go back to the artwork TMDB offers",
                    screen = null,
                    isDestructive = true
                )
            )
        }

    /**
     * The poster page: every poster TMDB lists, in TMDB's order, at `w500` for
     * the card and `w780` for the fullscreen view.
     *
     * The one PORTRAIT page of this row (see [MediaCardItem.portrait]): a
     * poster is the 2:3 artwork the title is sold by, and the viewer who opens
     * this page is looking for exactly that, not for another still. The
     * title's own `poster_path` is left IN, the same call the backdrop page
     * makes for the hero's: this page is "all the art for this title".
     */
    fun posters(posters: List<TmdbImageAsset>): List<MediaCardItem> =
        posters.mapNotNull { asset ->
            val path = asset.filePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MediaCardItem(
                id = "poster:$path",
                imageUrl = tmdbImage(path, "w500"),
                badge = null,
                title = "Poster",
                fullImageUrl = tmdbImage(path, "w780"),
                portrait = true,
                imagePath = path
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
