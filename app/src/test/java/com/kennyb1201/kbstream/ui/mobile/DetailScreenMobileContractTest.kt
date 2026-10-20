package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Detail half of Phase 2's screen rule: one `FormFactor` branch in the TV
 * file, the phone layout in a new tv-free file, and the same
 * `DetailViewModel` behind both.
 *
 * Pinned here because a JVM test cannot compose either layout (no
 * compose-ui-test harness in this module): the dispatch, the page's own load,
 * the tactile widget layer, the ViewModel reads that carry watched and resume
 * state, the two play paths, and each finished surface at its contract level.
 */
class DetailScreenMobileContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun detailScreen(): String =
        source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")

    private fun mobileDetail(): String =
        source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")

    private fun details(): String =
        source("com/kennyb1201/kbstream/ui/detail/DetailTrailerLaunch.kt")

    @Test
    fun `detail dispatches on the form factor at its entry point`() {
        val detail = detailScreen()
        val dispatch = detail.indexOf("if (!FormFactor.isTv)")
        val mobileCall = detail.indexOf("DetailScreenMobile(")
        assertTrue("DetailScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call DetailScreenMobile", mobileCall > dispatch)
        val callBlock = detail.substring(mobileCall)
            .substringBefore("val scope = rememberCoroutineScope()")
        assertTrue(
            "the branch must hand over the same type, id, callbacks and ViewModel",
            callBlock.contains("type = type") &&
                callBlock.contains("id = id") &&
                callBlock.contains("onNavigateStreams = onNavigateStreams") &&
                callBlock.contains("viewModel = viewModel")
        )
        assertTrue(
            "the route's deep link and its artwork must reach the phone (the " +
                "cover splash is painted from them, and the deep link is what " +
                "auto-plays)",
            callBlock.contains("initialTarget = initialTarget") &&
                callBlock.contains("initialPoster = initialPoster") &&
                callBlock.contains("initialBackdrop = initialBackdrop") &&
                callBlock.contains("initialClearLogo = initialClearLogo") &&
                callBlock.contains("initialOverview = initialOverview")
        )
        assertTrue(
            "and the two resolved-art hand-backs, so the splash can show the " +
                "title's own clearlogo and still",
            callBlock.contains("onClearLogoResolved = onClearLogoResolved") &&
                callBlock.contains("onBackdropResolved = onBackdropResolved")
        )
        assertTrue(
            "the branch must return, so the TV page never composes on a phone",
            callBlock.contains("return")
        )
        assertTrue(
            "the TV page must still be the focus-driven one it was",
            detail.substringAfter("val scope = rememberCoroutineScope()")
                .contains("playTargetFor")
        )
    }

    @Test
    fun `the mobile detail loads the shared view model itself`() {
        val mobile = mobileDetail()
        assertTrue(
            "a phone never composes the TV body, so the entry effect that loads " +
                "the page has to live here too - without it the screen sits on " +
                "its empty state forever",
            mobile.contains("viewModel.load(")
        )
        assertTrue(
            "the route's own art is offered as the initial meta, exactly as on TV",
            mobile.contains("initialMeta = Meta(")
        )
        assertTrue(
            "the deep link arms the auto-play flag (it also keeps the cover splash up)",
            mobile.contains("isDeepLinkAutoPlay = initialTarget != null")
        )
        assertTrue(
            "the resume season is the one the deep link names",
            mobile.contains("initialSeason = initialTarget?.season")
        )
    }

    @Test
    fun `the mobile detail is built on the touch twins and the mobile theme`() {
        val mobile = mobileDetail()
        assertTrue(
            "the screen must apply MobileTheme",
            mobile.contains("MobileTheme {")
        )
        assertTrue(
            "the primary action must be the touch button",
            mobile.contains("ui.components.touch.KBButton")
        )
        assertTrue(
            "More Like This must use the touch tile that follows the global " +
                "landscape switch",
            mobile.contains("ui.components.touch.TouchGlobalPosterCard")
        )
        assertTrue(
            "every long-press menu must be the touch sheet",
            mobile.contains("ui.components.touch.PosterContextMenu")
        )
        assertFalse(
            "the mobile detail must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
    }

    @Test
    fun `the mobile detail reads the tv detail view model`() {
        val mobile = mobileDetail()
        listOf(
            "viewModel.meta",
            "viewModel.tmdbDetail",
            "viewModel.episodes",
            "viewModel.loadedSeason",
            "viewModel.resumeInfo",
            "viewModel.targetEpisode",
            "viewModel.playButtonText",
            "viewModel.completedEpisodeIds",
            "viewModel.inProgressByStreamId",
            "viewModel.loadEpisodesForSeason(",
            "viewModel.watchedEpisodeKeys",
            "viewModel.simklWatchedEpisodes",
            "viewModel.resolvedPosterIds",
            "viewModel.allReviews",
            "viewModel.collection",
            "viewModel.simklPageUrl"
        ).forEach { read ->
            assertTrue("mobile Detail must read $read", mobile.contains(read))
        }
    }

    @Test
    fun `both play paths build the shared streams target`() {
        val mobile = mobileDetail()
        assertTrue(
            "a movie must wait for the verified root before opening streams",
            mobile.contains("viewModel.awaitStreamRootId(id)")
        )
        assertTrue(
            "the movie target must carry the resume position and TMDB runtime",
            mobile.contains("contentType = \"movie\"") &&
                mobile.contains("resumeInfo?.positionMs")
        )
        assertTrue(
            "a series must play the episode row's own resolved stream id",
            mobile.contains("contentType = \"series\"") &&
                mobile.contains("streamId = episode.streamId")
        )
        assertTrue(
            "the episode title must carry the Sx Ey marker the picker re-parses",
            mobile.contains("append(\" S\").append(season)") &&
                mobile.contains("append(\" E\").append(episode.episodeNumber)")
        )
        assertTrue(
            "starting over must be expressible (the TV's second path)",
            mobile.contains("startFromBeginning = fromStart") &&
                mobile.contains("openMovie(fromStart = true)")
        )
        assertTrue(
            "an episode play must hand the player the aired season size, as TV does",
            mobile.contains("totalEpisodesInSeason =") &&
                mobile.contains("airedEpisodesInSeason")
        )
        assertTrue(
            "the detail screen must still be able to go back without a new callback",
            mobile.contains("LocalOnBackPressedDispatcherOwner")
        )
    }

    @Test
    fun `the logo ladder is the tv page's, and its art is handed up`() {
        val mobile = mobileDetail()
        assertTrue(
            "the clearlogo is TMDB's English mark (the add-on logo is not a " +
                "fallback on TV and must not become one here)",
            mobile.contains("tmdbImage(tmdbDetail?.bestLogoPath(), \"w500\")")
        )
        assertTrue(
            "the resolved logo reaches the cover splash",
            mobile.contains("onClearLogoResolved)")
        )
        assertTrue(
            "only real widescreen art is reported as a backdrop, never the " +
                "poster fallback",
            mobile.contains("onBackdropResolved)")
        )
        assertTrue(
            "the chosen detail still is what the hero draws",
            mobile.contains("ImageOverrides.SCREEN_DETAIL")
        )
    }

    @Test
    fun `the deep link plays itself once the page has metadata`() {
        val mobile = mobileDetail()
        assertTrue(
            "the auto-play must be latched so it fires once",
            mobile.contains("autoPlayed = true") && mobile.contains("var autoPlayed")
        )
        assertTrue(
            "it opens the deep link's own target, not a re-derived one",
            mobile.contains("val target = initialTarget ?: return@LaunchedEffect") &&
                mobile.contains("target.copy(")
        )
        assertTrue(
            "a movie deep link waits for its resume row: the position is the " +
                "point of the open",
            mobile.contains("resumeInfo == null")
        )
    }

    @Test
    fun `the media row is the shared projection, with the viewer and override menu`() {
        val mobile = mobileDetail()
        assertTrue(
            "the pages come from the same rule object the TV row uses",
            mobile.contains("DetailMediaRow.tabs(") &&
                mobile.contains("DetailMediaRow.trailers(mediaVideos)") &&
                mobile.contains("DetailMediaRow.backdrops(mediaBackdrops)") &&
                mobile.contains("DetailMediaRow.posters(mediaPosters)")
        )
        assertTrue(
            "the pages are the payload the detail call already carries",
            mobile.contains("tmdbDetail?.videos?.results.orEmpty()") &&
                mobile.contains("tmdbDetail?.images?.backdrops.orEmpty()") &&
                mobile.contains("tmdbDetail?.images?.posters.orEmpty()")
        )
        assertTrue(
            "a trailer card plays through the shared launcher and an artwork " +
                "card opens the viewer",
            mobile.contains("DetailTrailerLaunch.play(context, scope, video)") &&
                mobile.contains("selectedBackdrop = card.fullImageUrl") &&
                mobile.contains("ImageViewerDialog(")
        )
        assertTrue(
            "a video the player cannot open says so instead of swallowing the press",
            mobile.contains("unplayable") && mobile.contains("\"UNAVAILABLE\"")
        )
        assertTrue(
            "the long press offers the surface rows and writes the choice",
            mobile.contains("DetailMediaRow .overrideRows(") &&
                mobile.contains("ImageOverrides.choose(") &&
                mobile.contains("ImageOverrides.reset(")
        )
        assertTrue(
            "the trailer intents live in one place, shared with the TV page",
            details().contains("fun intent(") &&
                details().contains("EXTRA_TRAILER_VIDEO_ID") &&
                detailScreen().contains("DetailTrailerLaunch.intent(context, source, videoId)")
        )
    }

    @Test
    fun `reviews and the collection are on the page`() {
        val mobile = mobileDetail()
        assertTrue(
            "the bundled reviews plus the extra pages the ViewModel fetched",
            mobile.contains("allReviews.ifEmpty { tmdbDetail?.reviews?.results.orEmpty() }")
        )
        assertTrue(
            "a review opens full, and a spoiler starts gated",
            mobile.contains("ReviewDialog(") && mobile.contains("REVEAL SPOILER")
        )
        assertTrue(
            "a collection part opens its own detail page by IMDb id, as on TV",
            mobile.contains("viewModel.resolveImdbId(part.id, \"movie\")")
        )
        assertTrue(
            "and a recommendation resolves its id the same way",
            mobile.contains("viewModel.resolveImdbId(item.id, type)")
        )
    }

    @Test
    fun `the touch menus cover episodes, seasons and posters`() {
        val mobile = mobileDetail()
        assertTrue(
            "an episode row raises the episode menu",
            mobile.contains("episodeMenu = EpisodeMenuTarget(")
        )
        assertTrue(
            "a season chip raises the season menu",
            mobile.contains("seasonMenu = SeasonMenuTarget(")
        )
        assertTrue(
            "the menus carry the TV rows",
            mobile.contains("viewModel.markEpisodeWatched(") &&
                mobile.contains("viewModel.markPreviousWatched(") &&
                mobile.contains("viewModel.markSeasonWatched(") &&
                mobile.contains("viewModel.markSeasonUnwatched(") &&
                mobile.contains("viewModel.markSeriesWatched(") &&
                mobile.contains("viewModel.markSeriesUnwatched()")
        )
        assertTrue(
            "a poster menu can add to the library and hide",
            mobile.contains("LibraryAddTarget(") && mobile.contains("hideTarget(")
        )
        assertTrue(
            "the menus are ordered by the shared sheet, and spoiler-free hides " +
                "an episode's name before it is played",
            mobile.contains("SpoilerFree.hidesIdentity(") &&
                mobile.contains("SpoilerFree.episodeLabel(")
        )
    }

    @Test
    fun `collection and More Like This posters carry the shared captions`() {
        val mobile = mobileDetail()

        val collection = mobile
            .substringAfter("items(collectionParts")
            .substringBefore("item(key = \"recommendations\")")
        assertTrue(
            "a collection part draws the caption block every other poster row has",
            collection.contains("TouchPosterCaptions(")
        )
        assertTrue(
            "with the part's own title, year and score",
            collection.contains("title = part.title ?: part.name") &&
                collection.contains("year = part.releaseDate?.take(4)") &&
                collection.contains("rating = part.voteAverage")
        )

        val tile = mobile
            .substringAfter("private fun RecommendationTile(")
            .substringBefore("private fun ReviewTile(")
        assertTrue(
            "a More Like This tile draws the same caption block",
            tile.contains("TouchPosterCaptions(")
        )
        assertTrue(
            "from the recommendation's own title, year and score",
            tile.contains("title = item.title ?: item.name") &&
                tile.contains("year = (item.releaseDate ?: item.firstAirDate)?.take(4)") &&
                tile.contains("rating = item.voteAverage")
        )
    }

    @Test
    fun `the MDBList ratings strip is drawn from the shared source rule`() {
        val mobile = mobileDetail()

        assertTrue(
            "the page reads the same ratings flow the TV page does",
            mobile.contains(
                "val mdbListRatings by viewModel.mdbListRatings.collectAsStateWithLifecycle()"
            )
        )
        assertTrue(
            "and builds the strip through the shared source rule",
            mobile.contains("mdbListRatingSources(")
        )
        assertTrue(
            "with TMDB's score as the fallback chip, exactly as on TV",
            mobile.contains("tmdbFallback = tmdbDetail?.voteAverage?.takeIf { it > 0.0 }")
        )
        assertTrue(
            "a title with no scores draws no empty strip",
            mobile.contains("if (ratingSources.isNotEmpty())") &&
                mobile.contains("MobileRatingsStrip(")
        )
        assertTrue(
            "and each brand mark keeps its own colour",
            mobile.contains("colorFilter = ColorFilter.tint(source.tint)")
        )
    }
}
