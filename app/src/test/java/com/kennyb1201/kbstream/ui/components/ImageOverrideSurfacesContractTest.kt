package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That every surface a chosen image is supposed to reach actually reads it.
 *
 * The rules are pinned by [com.kennyb1201.kbstream.data.images.ImageOverridesRulesTest]
 * and the table by its DAO test; what neither can see is whether a screen
 * bothers to ask. A surface that keeps drawing TMDB's art - because the lookup
 * was never wired, or was wired to the wrong screen constant - looks exactly
 * like a feature that works until a viewer picks a still on a rail card and
 * nothing on the hero changes.
 *
 * Read from the source: these are Composables and one route, none of which a
 * unit test can press or navigate.
 */
class ImageOverrideSurfacesContractTest {

    private fun source(path: String): String =
        normalized(File(findMainSourceRoot(), path).readText())

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `the detail hero draws the still the viewer chose for it`() {
        val detail = source(DETAIL)
        assertTrue(
            "the hero's backdrop must consult SCREEN_DETAIL, which is the " +
                "surface the menu's \"Set as Detail backdrop\" row writes",
            detail.contains(
                "screen = ImageOverrides.SCREEN_DETAIL,")
        )
        assertTrue(
            "and it must read the choices as state, so a pick repaints the " +
                "hero rather than waiting for a reopen",
            detail.contains("val overridePaths = rememberImageOverridePaths()")
        )
    }

    @Test
    fun `a media card's long press writes the row it chose`() {
        val detail = source(DETAIL)
        assertTrue(
            "the rows come from the row's own rules rather than being spelled " +
                "out in the screen",
            detail.contains("DetailMediaRow .overrideRows(")
        )
        assertTrue(
            "a row that names a surface stores the choice",
            detail.contains("ImageOverrides.choose(")
        )
        assertTrue(
            "and the reset row clears every surface of the kind",
            detail.contains("ImageOverrides.reset(")
        )
        assertTrue(
            "only artwork opens the menu: a trailer card carries no path to store",
            detail.contains("if (card.imagePath != null) {") &&
                detail.contains("mediaImageMenu = card")
        )
        assertTrue(
            "and the card that raised it is remembered, so closing the menu hands the focus " +
                "back to it instead of leaving the D-pad at the top of the page",
            detail.contains("mediaImageMenuCardFocusRequester =") &&
                detail.contains("fun dismissMediaImageMenu()")
        )
    }

    @Test
    fun `the streams screen draws the backdrop the viewer chose for it`() {
        val streams = source(STREAMS)
        assertTrue(
            "the picker takes the title's TMDB id, since the backdrop the " +
                "route carries is only TMDB's own still",
            streams.contains("tmdbId: Int? = null,")
        )
        assertTrue(
            streams.contains("screen = ImageOverrides.SCREEN_STREAMS,")
        )
        assertTrue(
            "and the screen draws that image, not the route's own",
            streams.contains("if (!backdrop.isNullOrBlank())")
        )
    }

    @Test
    fun `the route hands the streams screen the id the choices are filed under`() {
        assertTrue(
            source(MAIN_ACTIVITY).contains("tmdbId = ImageOverrides.tmdbIdOf(current.parentId),")
        )
    }

    @Test
    fun `the home hero and home rails draw the chosen art`() {
        val home = source(HOME)
        assertTrue(home.contains("screen = ImageOverrides.SCREEN_HOME_HERO,"))
        assertTrue(
            "portrait rails take the poster choice - one choice covers every " +
                "surface a poster appears on",
            home.contains("posterUrl = imageOverridePosterUrl(")
        )
        assertTrue(
            "and landscape rails take the landscape-card choice",
            home.contains("backdropUrl = imageOverrideCardBackdropUrl(")
        )
    }

    @Test
    fun `landscape cards and posters everywhere read the choices`() {
        assertTrue(
            "every poster surface in the app converges on GlobalPosterCard, so " +
                "the two choices are applied there",
            source(GLOBAL_POSTER_CARD).contains("posterUrl = imageOverridePosterUrl(") &&
                source(GLOBAL_POSTER_CARD).contains("backdropUrl = imageOverrideCardBackdropUrl(")
        )
        assertTrue(
            "and the landscape resolver the rails and folders share honors a " +
                "chosen still as the alternate TMDB image it prefers",
            source(LANDSCAPE_ART).contains("screen = ImageOverrides.SCREEN_LANDSCAPE_CARD")
        )
    }

    @Test
    fun `the pre-playback splash draws the still the viewer chose for it`() {
        val splash = source(AUTO_PLAY_LOAD_SPLASH)
        assertTrue(
            "the splash must consult SCREEN_PLAYER_SPLASH, the surface the " +
                "menu's \"Set as Player splash\" row writes",
            splash.contains("screen = ImageOverrides.SCREEN_PLAYER_SPLASH,")
        )
        assertTrue(
            "and it must read the choices as state, so a pick repaints the " +
                "splash on the next hand-off",
            splash.contains("val overridePaths = rememberImageOverridePaths()")
        )
        assertTrue(
            "the choice is upgraded to master size, exactly as the splash " +
                "upgrades its own backdrop",
            splash.contains("fallbackUrl = TmdbRepository.fullResBackdropUrl(backdropUrl)")
        )
    }

    @Test
    fun `every splash the route paints is handed the title's id`() {
        val activity = source(MAIN_ACTIVITY)
        assertTrue(
            "the deep-link cover, the autoselect overlay, and the frame the " +
                "Player route paints all go through the shared id resolution",
            activity.contains("tmdbId = splashTmdbId(detailAutoPlay.type, detailAutoPlay.id)") &&
                activity.contains("tmdbId = splashTmdbId(pending.parentType, pending.parentId)") &&
                activity.contains(
                    "tmdbId = splashTmdbId(playerSplash.parentType, playerSplash.parentId)"
                )
        )
        assertTrue(
            "and a live channel launch is left out: a channel id is its own " +
                "namespace and must never reach a TMDB title's choice",
            activity.contains(
                "if (parentType == \"channel\") null else ImageOverrides.tmdbIdOf(parentId)"
            )
        )
        assertTrue(
            "Detail's own deep-link cover resolves the id the same way its hero does",
            source(DETAIL).contains("tmdbId = overrideTmdbId")
        )
    }

    @Test
    fun `each surface resolves its URL at its own size`() {
        val art = source(IMAGE_OVERRIDE_ART)
        assertTrue(
            "the frame-filling surfaces upgrade a chosen path the way they " +
                "upgrade their own art",
            art.contains("TmdbRepository.fullResBackdropUrl(it)")
        )
        assertTrue(
            "a card draws a card-sized backdrop rather than a master-size one",
            art.contains("TmdbRepository.BACKDROP_BASE + it")
        )
        assertTrue(
            art.contains("TmdbRepository.POSTER_BASE + it")
        )
        assertTrue(
            "and the mirror's load is started by whoever reads it, so a screen " +
                "only has to remember the paths",
            art.contains("ImageOverrides.ensureLoaded(context)")
        )
    }

    @Test
    fun `the collection screen's header draws the choices too`() {
        val collection = source(COLLECTION)
        assertTrue(
            "the screen the report named: its header must read the choices as " +
                "state, so a pick made anywhere repaints it",
            collection.contains("val overridePaths = rememberImageOverridePaths()")
        )
        assertTrue(
            "the header's poster is the choice filed for the collection, not the " +
                "path TMDB sent",
            collection.contains("posterUrl = imageOverridePosterUrl(") &&
                collection.contains("rawId = collectionId.toString(),")
        )
        assertTrue(
            "and its still walks the same chain as every other backdrop, which is " +
                "what a collection - a page with no Media row of its own - has " +
                "instead of a row",
            collection.contains("backdropUrl = imageOverrideBackdropUrl(") &&
                collection.contains("tmdbId = collectionId,")
        )
    }

    @Test
    fun `a header with no row of its own draws the all-screens still`() {
        val actor = source(ACTOR)
        assertTrue(
            "the actor page's header is the surface nobody listed: it offers no " +
                "backdrop row, so the all-screens choice is what reaches it",
            actor.contains("screen = ImageOverrides.SCREEN_ALL,") &&
                actor.contains("tmdbId = topWorkCredit?.id,")
        )
        assertTrue(
            "and it keeps the top work's own TMDB still as the last fallback",
            actor.contains("fallbackUrl = topWorkBackdropUrl")
        )
    }

    @Test
    fun `the poster fans and the KB folder rails draw the chosen posters`() {
        listOf(TAG, DECADE).forEach { screen ->
            val fan = source(screen)
            assertTrue(
                "$screen's header fan must draw the poster a title actually has, " +
                    "not the path TMDB sent it",
                fan.contains("imageOverridePosterUrl(") &&
                    fan.contains("rawId = railItem.item.id.toString(),")
            )
        }

        val folders = source(KB_FOLDER_LAYOUTS)
        assertTrue(
            "a KB folder's cards draw their own artwork rather than going " +
                "through GlobalPosterCard, so both choices have to be applied " +
                "where they are drawn or a folder is the one rail left on TMDB's art",
            folders.contains("imageOverridePosterUrl(") &&
                folders.contains("imageOverrideCardBackdropUrl(")
        )
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val COLLECTION = "com/kennyb1201/kbstream/ui/collection/CollectionScreen.kt"
        const val ACTOR = "com/kennyb1201/kbstream/ui/actor/ActorScreen.kt"
        const val KB_FOLDER_LAYOUTS = "com/kennyb1201/kbstream/ui/kb/KBFolderLayouts.kt"
        const val TAG = "com/kennyb1201/kbstream/ui/tag/TagScreen.kt"
        const val DECADE = "com/kennyb1201/kbstream/ui/decade/DecadeScreen.kt"
        const val STREAMS = "com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt"
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val GLOBAL_POSTER_CARD = "com/kennyb1201/kbstream/ui/components/GlobalPosterCard.kt"
        const val LANDSCAPE_ART = "com/kennyb1201/kbstream/ui/components/LandscapeArt.kt"
        const val IMAGE_OVERRIDE_ART = "com/kennyb1201/kbstream/ui/components/ImageOverrideArt.kt"
        const val AUTO_PLAY_LOAD_SPLASH =
            "com/kennyb1201/kbstream/ui/components/AutoPlayLoadSplash.kt"
    }
}
