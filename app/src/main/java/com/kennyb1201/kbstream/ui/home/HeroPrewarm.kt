package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.tmdb.HeroArtwork
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail

/*
 * What the Home hero pre-warms, in one place: the trailer key its inline player
 * arms on, and the image targets its prefetch fills Coil's cache with.
 *
 * Both are pure and both are read from two places in HomeViewModel - the key by
 * the early publish and by the final one, the targets by the prefetch - so
 * neither rule can drift into two spellings that quietly disagree.
 */

/**
 * The YouTube key the hero's inline trailer should play for [detail], or null
 * when TMDB holds no playable trailer for the title.
 *
 * Deliberately STRICTER than [com.kennyb1201.kbstream.data.tmdb.TrailerPick.best],
 * which the Detail page's trailer button uses: the hero AUTOPLAYS whatever it
 * finds, over the artwork a viewer is browsing, so only a real "Trailer"
 * qualifies. A teaser or a clip playing by itself there reads as the app having
 * put up the wrong video, which is why the broader ranking stays with the
 * button that only plays on request.
 *
 * YouTube only, and the key must be usable (the player resolves a
 * youtube.com/watch?v=<key> URL). TMDB's own order decides between several
 * candidates, so the same title always picks the same one.
 */
internal fun heroTrailerKeyOf(detail: TmdbDetail?): String? =
    detail?.videos?.results
        ?.firstOrNull { video ->
            video.site.equals("YouTube", ignoreCase = true) &&
                video.type.equals("Trailer", ignoreCase = true) &&
                video.key.isNotBlank()
        }
        ?.key

/**
 * How many of the prefetched titles have their hero art warmed as IMAGES.
 *
 * The URL prefetch above it covers a rail's worth of cards for almost nothing
 * (TMDB JSON, disk-cached), but warming image bytes is real traffic, so it is
 * bounded to the cards a viewer reaches first - the prefetch list is handed
 * over in rail order, so these are the top rows' items. Everything past the
 * limit still warms its URLs and pays for its artwork on focus, exactly as it
 * did before.
 */
internal const val HERO_ART_IMAGE_WARM_LIMIT = 10

/*
 * The decode sizes the warm uses. They are NOT the sizes the hero draws: the
 * point of the warm is the bytes Coil's DISK cache now holds (that cache is
 * keyed by URL, not by size), and decoding a thumbnail is the cheapest way to
 * get them there. A decode at the hero's own 1280x720 would cost ~8 MB of heap
 * per title and evict the art actually on screen from the memory cache.
 */
private const val HERO_BACKDROP_WARM_WIDTH = 640
private const val HERO_BACKDROP_WARM_HEIGHT = 360
private const val HERO_LOGO_WARM_WIDTH = 400
private const val HERO_LOGO_WARM_HEIGHT = 110

/** One image the hero will draw, and the size to warm it at. */
internal data class HeroArtWarmTarget(
    val url: String,
    val width: Int,
    val height: Int
)

/**
 * The images to warm for one prefetched title, backdrop first.
 *
 * Missing and blank URLs are dropped: the hero's own fallbacks (the add-on
 * backdrop, then an alternate poster) are different images, and warming a blank
 * string would ask Coil for nothing. An artwork object with neither is simply
 * empty, which is the common case for a title TMDB has no art for.
 */
internal fun heroArtWarmTargets(artwork: HeroArtwork?): List<HeroArtWarmTarget> =
    listOfNotNull(
        artwork?.backdropUrl
            ?.takeIf { it.isNotBlank() }
            ?.let {
                HeroArtWarmTarget(
                    url = it,
                    width = HERO_BACKDROP_WARM_WIDTH,
                    height = HERO_BACKDROP_WARM_HEIGHT
                )
            },
        artwork?.logoUrl
            ?.takeIf { it.isNotBlank() }
            ?.let {
                HeroArtWarmTarget(
                    url = it,
                    width = HERO_LOGO_WARM_WIDTH,
                    height = HERO_LOGO_WARM_HEIGHT
                )
            }
    )
