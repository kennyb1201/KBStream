package com.kennyb1201.kbstream.ui.mobile

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import android.widget.Toast
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.download.DownloadIdentity
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.spoiler.SpoilerFree
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionPart
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRecommendationItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import com.kennyb1201.kbstream.data.tmdb.TrailerPick
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.list
import com.kennyb1201.kbstream.data.tmdb.tmdbImage
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.ui.components.BrandMarkImage
import com.kennyb1201.kbstream.ui.components.heroSharedElement
import com.kennyb1201.kbstream.ui.components.LibraryAdds
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.components.RatingChipSource
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.WatchedCheckBadge
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.imageOverrideBackdropUrl
import com.kennyb1201.kbstream.ui.components.mdbListRatingSources
import com.kennyb1201.kbstream.ui.components.posterBorderModifier
import com.kennyb1201.kbstream.ui.components.mobileRatingSources
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.rememberPosterTileWidth
import com.kennyb1201.kbstream.ui.components.touch.EpisodeRatingChip
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.MobileDivider
import com.kennyb1201.kbstream.ui.components.touch.KBEpisodeRailCardTouch
import com.kennyb1201.kbstream.ui.components.touch.KBEpisodeRailTouch
import com.kennyb1201.kbstream.ui.components.touch.MobileSectionHeader
import com.kennyb1201.kbstream.ui.components.touch.episodeRuntimeLabel
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterCard
import com.kennyb1201.kbstream.ui.components.touch.TouchStudioChip
import com.kennyb1201.kbstream.ui.components.touch.TouchPosterCaptions
import com.kennyb1201.kbstream.ui.components.KBSkeletonRail
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.touch.TouchChoiceChip
import com.kennyb1201.kbstream.ui.components.touch.TouchLinkChip
import com.kennyb1201.kbstream.ui.detail.DetailMediaRow
import com.kennyb1201.kbstream.data.youtube.InnerTubeExtractor
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerPool
import com.kennyb1201.kbstream.ui.detail.DetailTrailerLaunch
import com.kennyb1201.kbstream.ui.home.HeroInlineTrailerPlayer
import com.kennyb1201.kbstream.ui.home.HeroTrailerPlayback
import com.kennyb1201.kbstream.ui.detail.DetailViewModel
import com.kennyb1201.kbstream.ui.detail.MediaCardItem
import com.kennyb1201.kbstream.ui.detail.MediaTab
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.detail.formatEpisodeAirDate
import com.kennyb1201.kbstream.ui.detail.episodeRowKey
import com.kennyb1201.kbstream.ui.detail.isEpisodeUnavailable
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Detail, for a finger.
 *
 * The TV screen is a focus-driven page: a hero that the D-pad can reach, media
 * tabs navigated Left/Right, season and episode rows whose focus carries the
 * page. A phone gets the same information in one vertical scroll - backdrop
 * header, meta, the play/trailer calls to action, the overview, a season chip
 * row with the episode list under it, the cast, the reviews, the collection,
 * More Like This, and the Media row - with the same [DetailViewModel], so
 * watched state, resume data and the season loading are the TV screen's, not a
 * second copy.
 *
 * The page is the one the TV page is, not a subset:
 *
 *  - it LOADS the ViewModel itself (`load` with the route's `initialMeta` and
 *    `initialTarget`, which is also what arms the deep-link auto-play);
 *  - the clearlogo ladder is the TV's (TMDB's English mark, else the plain
 *    title - the add-on logo is deliberately not a fallback, see the TV
 *    screen), and the resolved logo and widescreen backdrop are handed up so
 *    the pre-playback cover splash can paint them;
 *  - the Media row offers the same pages ([DetailMediaRow.tabs]) with the same
 *    cards, the same fullscreen still viewer and the same artwork-override
 *    long-press menu;
 *  - reviews and the collection rail are drawn, with a full-screen review read
 *    and the collection part opening its own detail page by IMDb id;
 *  - the long-press menus cover the episode rows, the season chips and the
 *    More Like This / collection posters (watched toggles, previous/season/
 *    whole-series marks, play from beginning, library add, hide);
 *  - spoiler-free mode hides an unstarted episode's name on the row and in its
 *    menu, exactly as it does on TV;
 *  - the title's genres and its TMDB keywords are chips under the calls to
 *    action, and a tap opens that genre's or keyword's Tag page - the two rails
 *    the TV page draws, with the same route they push there.
 *
 * The Play button carries the TV's secondary action as a HOLD: a tap plays the
 * same episode START OVER would start over, and a hold opens the streams
 * picker for it without auto-selecting a source (see [openCurrentStreams]).
 * "Play from Beginning" stays the explicit START OVER button beside it - the
 * one half of the TV menu that a phone should not hide behind a gesture.
 *
 * Play: a movie waits for the verified stream root ([DetailViewModel.awaitStreamRootId])
 * and then opens the streams screen with the saved resume position, exactly the
 * target the TV's movie branch builds. A series plays the episode row's own
 * resolved `streamId` - the id the season listing was built under - instead of
 * re-deriving the file fallback the TV does from its season/episode pair. Taps
 * on any episode row play that episode the same way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreenMobile(
    type: String,
    id: String,
    onNavigateDetail: (String, String) -> Unit,
    onNavigateActor: (Int) -> Unit,
    // A network or production-company chip opens its Studio page: (id, name,
    // isNetwork), the same three arguments the TV page's chips hand over.
    onNavigateStudio: (Int, String, Boolean) -> Unit,
    onNavigateTag: (Int, String, Boolean, String) -> Unit,
    onNavigateStreams: (
        StreamsTarget,
        String,
        String,
        String?,
        String?,
        String?,
        String?,
        List<TmdbCastMember>
    ) -> Unit,
    initialTarget: StreamsTarget? = null,
    initialPoster: String? = null,
    initialBackdrop: String? = null,
    initialClearLogo: String? = null,
    initialOverview: String? = null,
    onClearLogoResolved: (String) -> Unit = {},
    onBackdropResolved: (String) -> Unit = {},
    viewModel: DetailViewModel
) {
    MobileTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        val today = remember { LocalDate.now() }

        // The page's own load. The TV body's entry effect does this; a phone
        // never composes that body, so without this the screen would sit on its
        // empty state forever. Same arguments, same freshness gate in the
        // ViewModel - and the deep-link flag is what keeps the loading splash
        // up for a Continue Watching open that is about to auto-play.
        LaunchedEffect(type, id) {
            viewModel.load(
                type = type,
                id = id,
                initialSeason = initialTarget?.season,
                initialMeta = Meta(
                    id = id,
                    type = type,
                    name = initialTarget?.displayName?.takeIf { it.isNotBlank() } ?: id,
                    poster = initialPoster,
                    background = initialBackdrop,
                    logo = initialClearLogo,
                    description = initialOverview
                ).takeIf {
                    !initialPoster.isNullOrBlank() ||
                        !initialBackdrop.isNullOrBlank() ||
                        !initialClearLogo.isNullOrBlank() ||
                        !initialOverview.isNullOrBlank()
                },
                isDeepLinkAutoPlay = initialTarget != null
            )
        }

        val meta by viewModel.meta.collectAsStateWithLifecycle()
        val tmdbDetail by viewModel.tmdbDetail.collectAsStateWithLifecycle()
        val episodes by viewModel.episodes.collectAsStateWithLifecycle()
        val loadedSeason by viewModel.loadedSeason.collectAsStateWithLifecycle()
        val episodesLoading by viewModel.episodesLoading.collectAsStateWithLifecycle()
        val resumeInfo by viewModel.resumeInfo.collectAsStateWithLifecycle()
        val targetEpisode by viewModel.targetEpisode.collectAsStateWithLifecycle()
        val playButtonText by viewModel.playButtonText.collectAsStateWithLifecycle()
        val completedEpisodeIds by
            viewModel.completedEpisodeIds.collectAsStateWithLifecycle()
        val inProgressByStreamId by
            viewModel.inProgressByStreamId.collectAsStateWithLifecycle()
        val watchedEpisodeKeys by
            viewModel.watchedEpisodeKeys.collectAsStateWithLifecycle()
        val simklWatchedEpisodes by
            viewModel.simklWatchedEpisodes.collectAsStateWithLifecycle()
        val simklPageUrl by viewModel.simklPageUrl.collectAsStateWithLifecycle()
        val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()

        // Whether this title is already on this phone, for the profile on
        // screen (spec §4.4). Declared HERE, at the top, because it is read by
        // the calls-to-action block far below a couple of early returns - and a
        // state subscription after a conditional return is a call that exists on
        // some frames and not others. Display only: the phone's download ACTION
        // lives on the streams picker's long-press menu.
        val downloads = rememberDownloadRecords()
        val partialWatchedKeys by
            viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
        val resolvedPosterIds by
            viewModel.resolvedPosterIds.collectAsStateWithLifecycle()
        val collection by viewModel.collection.collectAsStateWithLifecycle()
        val mdbListRatings by viewModel.mdbListRatings.collectAsStateWithLifecycle()
        // OMDB's awards text, the same flow the TV page reads. It is display-only
        // and arrives after the detail (a separate lookup), so it is a key of the
        // facts block rather than something the block waits for.
        val awards by viewModel.awards.collectAsStateWithLifecycle()
        val allReviews by viewModel.allReviews.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val error by viewModel.error.collectAsStateWithLifecycle()

        // Spoiler-free is a synced pref, so it is re-read on the sync revision
        // as well as the profile: a toggle made on another device lands here.
        val syncRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
        val spoilerFreeEnabled = remember(context, syncRevision) {
            AppPreferences.getSpoilerFree(context)
        }

        // The artwork the viewer chose for this title (the Media row's
        // long-press menu writes these; the detail hero reads them).
        val overridePaths = rememberImageOverridePaths()
        val overrideTmdbId = tmdbDetail?.id ?: ImageOverrides.tmdbIdOf(id)

        // The same type the TV page normalizes to before any branch reads it:
        // anime catalogs emit their own spellings, and "anime.movie" is a
        // movie, not a series.
        val normalizedType = when (type.lowercase()) {
            "tv", "show" -> "series"
            "anime", "anime.series" -> "series"
            "anime.movie" -> "movie"
            else -> type.lowercase()
        }

        val displayName = meta?.name
            ?: tmdbDetail?.title
            ?: tmdbDetail?.name
            ?: initialTarget?.displayName?.takeIf { it.isNotBlank() }
            ?: ""
        val posterUrl = meta?.poster
            ?: tmdbDetail?.posterPath?.let { TmdbRepository.POSTER_BASE + it }
            ?: initialPoster
        // The title's own Library row. Before this the page had no way to save
        // the title it is about - the Add to Library rows lived only on OTHER
        // titles' poster menus - so saving the thing being read meant going
        // back to a poster that offered it. Same target shape those menus
        // build, so it reaches the trackers with the id pair they key on.
        val libraryTarget = remember(normalizedType, id, tmdbDetail?.id, displayName, posterUrl) {
            LibraryAddTarget(
                mediaType = normalizedType,
                imdbId = id.takeIf { it.startsWith("tt") },
                tmdbId = tmdbDetail?.id,
                title = displayName.ifBlank { "Untitled" },
                posterUrl = posterUrl
            )
        }
        var inLibrary by remember(type, id) { mutableStateOf(false) }
        LaunchedEffect(libraryTarget) {
            inLibrary = LibraryAdds.inMyList(context, libraryTarget)
        }
        // The TV's ladder, not "the add-on's logo, else the title": TMDB's
        // English clearlogo or nothing (the add-on `logo` is a bare URL with no
        // language, so a localized catalog's wordmark could reach this header
        // and the splash it feeds).
        val clearLogoUrl = tmdbImage(tmdbDetail?.bestLogoPath(), "w500")
        LaunchedEffect(clearLogoUrl) {
            clearLogoUrl?.takeIf { it.isNotBlank() }?.let(onClearLogoResolved)
        }
        // Widescreen art only: the cover splash fills the frame, so a poster is
        // never handed up as a backdrop.
        val backdropArtUrl = remember(tmdbDetail, meta, initialBackdrop) {
            tmdbDetail?.backdropPath?.let {
                TmdbRepository.fullResBackdropUrl(TmdbRepository.BACKDROP_BASE + it)
            }
                ?: meta?.background
                ?: initialBackdrop
        }
        LaunchedEffect(backdropArtUrl) {
            backdropArtUrl?.takeIf { it.isNotBlank() }?.let(onBackdropResolved)
        }
        val backdropUrl = backdropArtUrl
            ?: meta?.poster
            ?: initialPoster
        val heroBackdropUrl = remember(backdropUrl, overridePaths, overrideTmdbId) {
            imageOverrideBackdropUrl(
                paths = overridePaths,
                tmdbId = overrideTmdbId,
                screen = ImageOverrides.SCREEN_DETAIL,
                fallbackUrl = backdropUrl
            )
        }
        val overview = meta?.description ?: tmdbDetail?.overview ?: initialOverview
        val cast = tmdbDetail?.credits?.cast.orEmpty()
        // The TV page's two tag rails: the title's genres, and the TMDB keywords
        // it also draws (the `#`-prefixed row). Both open the Tag screen for the
        // chip that was pressed, with the route's own media type - the same
        // arguments the TV chips hand `onNavigateTag`.
        val tmdbGenres = tmdbDetail?.genres.orEmpty()
        val keywords = remember(tmdbDetail) { tmdbDetail?.keywords?.list().orEmpty() }
        // The trailer the button OFFERS is the trailer it PLAYS (one pick, one
        // rule - see TrailerPick): a series files its promos as Teaser/Clip.
        val trailerVideo = remember(tmdbDetail) {
            TrailerPick.best(tmdbDetail?.videos?.results)
        }

        // The season on screen. Seeded from what the ViewModel loaded (which is
        // the resume season when there is one), then owned by the chip row - the
        // same `loadEpisodesForSeason` the TV chips call.
        //
        // KEYED by the title: this shell renders Detail from one unkeyed slot, so
        // navigating Detail -> Detail (More Like This, a collection part, a
        // recommendation) recomposes the same slot with a new ViewModel and
        // would otherwise keep the PREVIOUS show's season - the wrong chip, and
        // episode taps that played/marked show B's episode numbers under show
        // A's season.
        var season by remember(type, id) { mutableStateOf<Int?>(null) }
        LaunchedEffect(loadedSeason) {
            if (season == null) season = loadedSeason
        }
        // Specials (TMDB season 0) sort behind the numbered seasons: the chip
        // row is the show's run, not its promo bucket, and the default season
        // with nothing else to go on is then the first real one.
        val seasons = remember(tmdbDetail) {
            MobileDetailRules.seasonsInChipOrder(tmdbDetail?.seasons.orEmpty())
        }
        val currentSeason = season ?: loadedSeason ?: seasons.firstOrNull()?.seasonNumber ?: 1

        // ---- the watched-state arithmetic the menus share ----
        val staleSeasons = remember(tmdbDetail) {
            MobileDetailRules.staleSeasonDates(tmdbDetail)
        }
        val seasonNumbersFor = remember(
            episodes,
            loadedSeason,
            tmdbDetail,
            staleSeasons,
            today
        ) {
            { seasonNumber: Int ->
                val summary = tmdbDetail?.seasons
                    ?.firstOrNull { it.seasonNumber == seasonNumber }
                MobileDetailRules.seasonEpisodeNumbers(
                    season = seasonNumber,
                    loadedSeason = loadedSeason,
                    episodes = episodes,
                    episodeCount = summary?.episodeCount,
                    airDatesTrusted = seasonNumber !in staleSeasons,
                    today = today,
                    // The next unaired episode and the season's own premiere:
                    // the two facts that keep an unloaded, still-airing season
                    // from being marked watched to its scheduled end.
                    nextEpisodeToAir = tmdbDetail?.nextEpisodeToAir,
                    seasonAirDate = summary?.airDate
                )
            }
        }
        val wholeSeriesEpisodeNumbers = remember(seasons, seasonNumbersFor) {
            MobileDetailRules.wholeSeriesNumbers(seasons.map { it.seasonNumber }) {
                seasonNumbersFor(it)
            }
        }

        fun watchedSeasonSet(seasonNumber: Int): Set<Int> =
            WatchedEpisodeState.effectiveWatchedEpisodesForSeason(
                parentId = id,
                season = seasonNumber,
                simklWatchedEpisodes = simklWatchedEpisodes,
                watchedEpisodeKeys = watchedEpisodeKeys
            )

        val wholeSeriesWatched = remember(
            wholeSeriesEpisodeNumbers,
            watchedEpisodeKeys,
            simklWatchedEpisodes
        ) {
            MobileDetailRules.wholeSeriesWatched(wholeSeriesEpisodeNumbers) {
                watchedSeasonSet(it)
            }
        }

        // The episodes of the season on screen this profile has watched: Simkl,
        // Trakt and MDBList plus its own marks, i.e. the same set the season and
        // episode menus read before offering "Mark as Unwatched" - and the same
        // set the television's cards tick from. The cards read it (see
        // `episodeVisual`) instead of only the completed-stream ids, which is
        // why a watched episode marked from the menu (or synced from a tracker)
        // showed no checkmark until the page was reloaded: nothing had been
        // COMPLETED locally for it, only marked.
        val watchedInSeason = remember(
            id,
            currentSeason,
            simklWatchedEpisodes,
            watchedEpisodeKeys
        ) {
            WatchedEpisodeState.effectiveWatchedEpisodesForSeason(
                parentId = id,
                season = currentSeason,
                simklWatchedEpisodes = simklWatchedEpisodes,
                watchedEpisodeKeys = watchedEpisodeKeys
            )
        }

        // The aired size of the season on screen: what a history row written
        // from this page stores as its season total, and what the episode menu
        // hands the player. An unaired episode is in the listing but not in
        // this count (see [isEpisodeUnavailable]).
        val airedEpisodesInSeason = remember(episodes, currentSeason, staleSeasons, today) {
            seasonNumbersFor(currentSeason)?.size
                ?: episodes.size
        }

        fun openStreams(
            target: StreamsTarget,
            description: String?,
            playedCast: List<TmdbCastMember>,
            // The Play button's HOLD: the viewer asked for the source list,
            // not for a source, so the picker must not auto-select one. The
            // marker is keyed the way StreamsViewModel keys the streams it
            // loads (`type:id`), or the picker never matches it and Auto-select
            // jumps straight to the player. Same handoff the TV's "Play
            // Manually" row makes.
            manual: Boolean = false
        ) {
            if (manual) {
                ManualSourceSelection.request("${target.contentType}:${target.streamId}")
            }
            onNavigateStreams(
                target,
                id,
                type,
                posterUrl,
                backdropUrl,
                clearLogoUrl,
                description,
                playedCast
            )
        }

        fun openEpisode(
            episode: ResolvedEpisode,
            episodeSeason: Int,
            fromStart: Boolean = false,
            manual: Boolean = false
        ) {
            // The card gestures are gated, but the episode menu's "Play from
            // Beginning" / "Play Manually" rows reach this function directly.
            // An episode that has not aired has no sources to resolve, so no
            // caller gets to start one (the TV's click gate states the same
            // rule).
            if (
                episodeSeason !in staleSeasons &&
                isEpisodeUnavailable(episode.airDate, today)
            ) {
                return
            }
            val progress = inProgressByStreamId[episode.streamId]
            val resumeRow = resumeInfo?.takeIf {
                it.episodeStreamId == episode.streamId
            }
            // The show-level resume row is a real position source too: the
            // deep-link autoplay path in this file already uses
            // resumeInfo.positionMs, so an episode whose progress exists only
            // there started from 0 on a row tap while its card still showed it
            // as started.
            val resumePositionMs = if (fromStart) {
                0L
            } else {
                progress?.positionMs ?: resumeRow?.positionMs ?: 0L
            }
            openStreams(
                StreamsTarget(
                    contentType = "series",
                    streamId = episode.streamId,
                    title = episodeTargetTitle(
                        showName = displayName,
                        season = episodeSeason,
                        episode = episode
                    ),
                    displayName = displayName,
                    season = episodeSeason,
                    episode = episode.episodeNumber,
                    resumePositionMs = resumePositionMs,
                    startFromBeginning = fromStart,
                    // The season total only means something for the season it
                    // was counted from; a row from another season (a deep link)
                    // gets that season's own count, or none.
                    totalEpisodesInSeason = if (episodeSeason == currentSeason) {
                        airedEpisodesInSeason
                    } else {
                        seasonNumbersFor(episodeSeason)?.size
                    },
                    runtimeMinutes = progress?.durationMs
                        ?.div(60_000L)
                        ?.toInt()
                        ?.takeIf { it > 0 }
                        ?: resumeRow?.durationMs
                            ?.div(60_000L)
                            ?.toInt()
                            ?.takeIf { it > 0 }
                        ?: episode.runtimeMinutes?.takeIf { it > 0 }
                ),
                episode.overview ?: overview,
                cast,
                manual = manual
            )
        }

        // The movie target both the Play button and START OVER build. The root
        // is the verified one: for a catalog whose id is not the IMDb id the
        // stream add-ons key on, the route id is another title's.
        fun openMovie(fromStart: Boolean, manual: Boolean = false) {
            scope.launch {
                val root = runCatching { viewModel.awaitStreamRootId(id) }
                    .getOrDefault(id)
                val recordedMinutes =
                    resumeInfo?.durationMs?.div(60_000L)?.toInt()?.takeIf { it > 0 }
                openStreams(
                    StreamsTarget(
                        contentType = "movie",
                        streamId = root,
                        title = displayName,
                        displayName = displayName,
                        season = null,
                        episode = null,
                        resumePositionMs = if (fromStart) 0L else resumeInfo?.positionMs ?: 0L,
                        startFromBeginning = fromStart,
                        runtimeMinutes = recordedMinutes
                            ?: tmdbDetail?.runtime?.takeIf { it > 0 }
                    ),
                    overview,
                    cast,
                    manual = manual
                )
            }
        }

        fun playCurrent() {
            if (normalizedType == "movie") {
                openMovie(fromStart = false)
                return
            }

            // A series plays the resume/next episode only when the season on
            // screen is the one that episode belongs to. The resolved target
            // carries no season of its own, so the resume row is what names it;
            // without a resume there is no "next up" to honour, and the
            // selected season's first episode is the right press.
            val resumeHere = targetEpisode != null &&
                resumeInfo?.season == currentSeason
            val episode = if (resumeHere) targetEpisode else episodes.firstOrNull()
            episode?.let { openEpisode(it, currentSeason) }
        }

        /**
         * The Play button's HOLD on a phone: the source picker for the very
         * episode a tap would start, and no auto-select, so it opens the list
         * instead of the player. This is the phone's answer to the TV's
         * long press on Play ("Play Manually"), which the port left out - the
         * only way to reach the picker from this page was to long-press an
         * episode row, which a movie does not have. START OVER remains the
         * from-the-beginning gesture.
         */
        fun openCurrentStreams() {
            if (normalizedType == "movie") {
                openMovie(fromStart = false, manual = true)
                return
            }
            val resumeHere = targetEpisode != null &&
                resumeInfo?.season == currentSeason
            val episode = if (resumeHere) targetEpisode else episodes.firstOrNull()
            episode?.let { openEpisode(it, currentSeason, manual = true) }
        }

        // ---- deep-link auto-play ----
        // The Continue Watching / Up Next open already carries a target, so the
        // page plays it rather than showing itself: the cover splash is up for
        // exactly this, and the viewer asked to resume, not to browse. Fires
        // once, after the metadata lands (and, for a movie deep link, after the
        // resume row exists - the position is the point of the open).
        var autoPlayed by remember(type, id) { mutableStateOf(false) }
        LaunchedEffect(initialTarget, isLoading, meta, tmdbDetail, resumeInfo) {
            val target = initialTarget ?: return@LaunchedEffect
            if (autoPlayed || isLoading) return@LaunchedEffect
            if (meta == null && tmdbDetail == null) return@LaunchedEffect
            if (
                target.contentType == "movie" &&
                !target.startFromBeginning &&
                resumeInfo == null
            ) {
                return@LaunchedEffect
            }
            autoPlayed = true
            val root = if (target.contentType == "movie") {
                runCatching { viewModel.awaitStreamRootId(id) }
                    .getOrDefault(target.streamId.ifBlank { id })
            } else {
                target.streamId
            }
            openStreams(
                target.copy(
                    streamId = root.ifBlank { target.streamId },
                    resumePositionMs = if (target.startFromBeginning) {
                        0L
                    } else {
                        val recorded = if (target.contentType == "movie") {
                            resumeInfo?.positionMs
                        } else {
                            resumeInfo
                                ?.takeIf { it.episodeStreamId == target.streamId }
                                ?.positionMs
                        }
                        maxOf(target.resumePositionMs, recorded ?: 0L)
                    }
                ),
                overview,
                cast
            )
        }

        // ---- a poster menu's "Play Manually" ----
        // The shared long-press sheet derives that row from the caller's own
        // "Go to Details" on every poster whose menu has none of its own (the
        // rails, Search, the browse pages, the catalog grid), so the one thing
        // it can ask for is THIS page - the TV's shape (see DetailScreen's
        // `manualPick`). The phone never read the request, so the row landed on
        // Details and stopped: the picker only opened after a second, Play
        // press, which is not what the row promised. Read once as this title's
        // page appears, then delivered once its own target exists (a series'
        // next episode is resolved here, not by the menu).
        var manualPick by remember(type, id) { mutableStateOf(false) }
        LaunchedEffect(type, id) {
            manualPick = ManualSourceSelection.consume()
        }
        var manualPickDelivered by remember(type, id) { mutableStateOf(false) }
        LaunchedEffect(manualPick, isLoading, meta, tmdbDetail, episodesLoading, episodes) {
            if (!manualPick || manualPickDelivered || isLoading) return@LaunchedEffect
            if (meta == null && tmdbDetail == null) return@LaunchedEffect
            // A series needs the listing before it can name an episode; a
            // listing that settled empty leaves the row where the Play button
            // would be too - this page's own target is its first file.
            if (normalizedType == "series" && episodesLoading) return@LaunchedEffect
            manualPickDelivered = true
            openCurrentStreams()
        }

        // ---- long-press subjects ----
        var episodeMenu by remember { mutableStateOf<EpisodeMenuTarget?>(null) }
        var seasonMenu by remember { mutableStateOf<SeasonMenuTarget?>(null) }

        // ---- the episode area's layout ----
        // Device-local screen geometry (see AppPreferences.getDetailEpisodeRail):
        // this page's shape says nothing about the television's, so the choice
        // never syncs. The switch lives in Settings → Posters & Browsing; the
        // revision is collected so a change there repaints this page even when
        // it is still on the back stack.
        val displayRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
        val episodeRail = remember(displayRevision) {
            AppPreferences.getDetailEpisodeRail(context)
        }

        // ---- the hero trailer ----
        // The trailer plays IN this page's hero, through the same pooled
        // ExoPlayer the TV Home hero draws with (HeroInlineTrailerPlayer),
        // instead of the fullscreen player a Media-row card still opens. The
        // trailer settings govern it exactly as they govern Home's hero:
        // autoplay decides whether it starts on its own, the dwell decides how
        // long the page settles first, and the mute switch decides whether it
        // is heard. All three are synced prefs, so the revision is the read -
        // a toggle on another device lands here, and the TRAILER button works
        // either way.
        val trailerMuted = remember(displayRevision) {
            AppPreferences.getHeroTrailerMuted(context)
        }
        val trailerAutoplay = remember(displayRevision) {
            AppPreferences.getHeroTrailerAutoplay(context)
        }
        val trailerDelayMs = remember(displayRevision) {
            AppPreferences.getHeroTrailerDelayMs(context)
        }
        val trailerKey = remember(trailerVideo) {
            trailerVideo?.key?.takeIf { it.isNotBlank() }
        }
        // PO-token pre-warm: the trailer here is a press (or a dwell) away, and
        // the cold WebView is what a resolve pays its whole mint budget for - the
        // 0.9.23 capture is exactly that (see
        // InnerTubeExtractor.prewarmPoTokens). Nothing on this screen waits for it.
        LaunchedEffect(trailerKey) {
            trailerKey?.let { key -> InnerTubeExtractor.prewarmPoTokens(key) }
        }
        // Null = the backdrop is showing. Keeping the source and its start
        // position in one value is what stops a fresh resolve inheriting a
        // dead position (see HeroTrailerPlayback).
        var heroTrailer by remember(trailerKey) { mutableStateOf<HeroTrailerPlayback?>(null) }
        // A play request from the viewer - the TRAILER button, or nothing.
        //
        // A COUNTER, not a `requested` flag the resolve clears. Clearing a
        // state that is one of the resolve's own LaunchedEffect keys recomposes
        // with a new key, and a new key CANCELS the coroutine that just started
        // - so every request (the button's, and every one the autoplay effect
        // made) was thrown away a frame into its resolve and the hero stayed on
        // the backdrop. That is the "trailers aren't autoplaying on mobile"
        // report. Bumping a counter only asks for one more resolve; nothing the
        // resolve does changes its own keys.
        var trailerRequest by remember(trailerKey) { mutableIntStateOf(0) }
        // The highest request the resolve effect has answered. Written INSIDE
        // the effect and deliberately not one of its keys: it is how a request
        // is consumed without the effect cancelling itself. Not part of the
        // gate either - a viewer who closed the trailer and then touched a
        // setting must not have it restart behind them.
        var trailerServed by remember(trailerKey) { mutableIntStateOf(0) }
        // The retry handoff: where a mid-stream failure died, read (and
        // cleared) by the resolve that retries it. Not a key either - it is
        // consumed, not requested.
        var trailerResumeMs by remember(trailerKey) { mutableLongStateOf(0L) }
        // One retry per trailer, the Home hero's own cap: a stale signed URL is
        // recoverable, a dead video is not. Only the failure callback moves it.
        var trailerAttempt by remember(trailerKey) { mutableIntStateOf(0) }

        fun stopHeroTrailer() {
            heroTrailer = null
            TrailerPlayerPool.releaseForReuse()
        }

        // The dwell AND the resolve, in one effect - the shape Home's hero uses
        // and for the same reason: a second effect that only sets a "go" flag
        // is a key the resolve would then have to change.
        //
        // With autoplay on the trailer starts itself after the same dwell Home
        // waits; with it off the backdrop stays up and the TRAILER button is
        // the only way in - which still plays here, not fullscreen. A press
        // while the dwell is running skips the rest of it: the request counter
        // changed, so this effect restarted and is past the wait.
        LaunchedEffect(trailerKey, trailerAutoplay, trailerDelayMs, trailerRequest) {
            val key = trailerKey ?: return@LaunchedEffect
            // Already playing the thing this effect would resolve.
            if (heroTrailer != null) return@LaunchedEffect
            // An unanswered request is the viewer asking right now (the button,
            // or a retry); no request means autoplay decides.
            val asked = trailerRequest > trailerServed
            if (!asked) {
                if (!trailerAutoplay) return@LaunchedEffect
                delay(trailerDelayMs)
                // Re-read AFTER the dwell: a press during the wait has already
                // resolved and started the trailer, and resolving again would
                // restart it mid-scene.
                if (heroTrailer != null) return@LaunchedEffect
            }
            trailerServed = trailerRequest
            val resumeFrom = trailerResumeMs
            trailerResumeMs = 0L
            // The shared launcher: its cache, its coalescing mutex, its
            // failure memory.
            val source = TrailerPlayerLauncher.resolvePlayableUrl(key).getOrNull()
            if (source != null) {
                heroTrailer = HeroTrailerPlayback(source, resumeFrom)
            }
        }

        // Leaving the page silences the pooled player. The composable's own
        // cleanup deliberately leaves that to its owner (during a hero
        // crossfade the outgoing instance must not stop the incoming one on the
        // shared player), so the owner is this page.
        DisposableEffect(Unit) {
            onDispose { TrailerPlayerPool.releaseForReuse() }
        }

        // One episode's state, derived once. BOTH layouts read this - the
        // vertical row and the rail card - so a watched tick, a progress
        // fraction, an unaired badge or a spoiler-free mask cannot be right in
        // one and missing from the other. These are the derivations the list
        // row already made; they moved here rather than being copied.
        fun episodeVisual(episode: ResolvedEpisode): EpisodeVisual {
            val started = inProgressByStreamId[episode.streamId] != null ||
                resumeInfo?.episodeStreamId == episode.streamId
            // Watched is the SEASON's watched set or a completed stream id:
            // the set is what the long-press menu marks into and what the
            // trackers deliver, and the id covers a row filed under another id
            // flavor of the same show (see WatchedEpisodeState).
            val isWatched =
                episode.episodeNumber in watchedInSeason ||
                    completedEpisodeIds.contains(episode.streamId)
            return EpisodeVisual(
                hiddenTitle = SpoilerFree.hidesIdentity(
                    enabled = spoilerFreeEnabled,
                    watched = isWatched,
                    started = started
                ),
                isWatched = isWatched,
                unavailable = currentSeason !in staleSeasons &&
                    isEpisodeUnavailable(episode.airDate, today),
                progress = inProgressByStreamId[episode.streamId]
                    ?.let { progressOf(it.positionMs, it.durationMs) }
            )
        }
        var posterMenu by remember { mutableStateOf<PosterMenuTarget?>(null) }
        var mediaImageMenu by remember { mutableStateOf<MediaCardItem?>(null) }
        var selectedBackdrop by remember { mutableStateOf<String?>(null) }
        var selectedReview by remember { mutableStateOf<TmdbReview?>(null) }
        // Same keying rule as `season`: the media row's selected tab belongs to
        // a title, and an unkeyed remember carried title A's tab onto title B.
        var mediaTab by remember(type, id) { mutableStateOf<MediaTab?>(null) }

        // The TV's page-1 reviews plus every extra page the ViewModel fetched
        // in the background, in the same order.
        val reviews = remember(allReviews, tmdbDetail) {
            allReviews.ifEmpty { tmdbDetail?.reviews?.results.orEmpty() }
        }
        // The title's other collection entries, minus itself (a part list that
        // includes the title being read draws it twice).
        val collectionParts = remember(collection, tmdbDetail?.id) {
            collection?.parts.orEmpty().filter { it.id != tmdbDetail?.id }
        }

        val mediaVideos = tmdbDetail?.videos?.results.orEmpty()
        val mediaBackdrops = tmdbDetail?.images?.backdrops.orEmpty()
        val mediaPosters = tmdbDetail?.images?.posters.orEmpty()
        val mediaTabs = remember(mediaVideos, mediaBackdrops, mediaPosters) {
            DetailMediaRow.tabs(
                mediaVideos.size,
                mediaBackdrops.size,
                mediaPosters.size
            )
        }
        // The page shown survives the tab list being rebuilt (a late detail
        // response adds a page); a page that is gone falls back to the first.
        val activeMediaTab = mediaTab?.takeIf { it in mediaTabs } ?: mediaTabs.firstOrNull()
        val mediaCards = remember(activeMediaTab, mediaVideos, mediaBackdrops, mediaPosters) {
            when (activeMediaTab) {
                MediaTab.TRAILERS -> DetailMediaRow.trailers(mediaVideos)
                MediaTab.BACKDROPS -> DetailMediaRow.backdrops(mediaBackdrops)
                MediaTab.POSTERS -> DetailMediaRow.posters(mediaPosters)
                null -> emptyList()
            }
        }
        // The same strip the TV page draws under the overview: MDBList's
        // sources when it answered, TMDB's own score as the fallback chip.
        val ratingSources = remember(mdbListRatings, tmdbDetail?.voteAverage) {
            mdbListRatingSources(
                ratings = mdbListRatings,
                tmdbFallback = tmdbDetail?.voteAverage?.takeIf { it > 0.0 }
            )
        }
        // The TV page's NETWORK and PRODUCTION rails: the companies behind the
        // title, each chip opening that company's Studio page. The phone had
        // neither, so a show's channel and a film's studios were unreachable
        // from here.
        val networks = tmdbDetail?.networks.orEmpty()
        val productionCompanies = tmdbDetail?.productionCompanies.orEmpty()
        // ...and the Movie / Show Details block: the same facts the TV page
        // prints as one meta line, laid out as labeled rows for a phone (see
        // MobileDetailFacts.kt, which owns the rules).
        val facts = remember(normalizedType, tmdbDetail, awards) {
            detailFacts(normalizedType, tmdbDetail, awards)
        }

        fun openCollectionPart(part: TmdbCollectionPart) {
            scope.launch {
                val imdbId = viewModel.resolveImdbId(part.id, "movie")
                if (imdbId != null) {
                    onNavigateDetail("movie", imdbId)
                } else {
                    // A press that resolves no IMDb id used to do nothing at
                    // all, which reads as a broken screen rather than a title
                    // TMDB cannot map. Same message as the TV rails.
                    Toast.makeText(
                        context,
                        "Couldn't open this title",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        fun openRecommendation(item: TmdbRecommendationItem) {
            scope.launch {
                val imdbId = viewModel.resolveImdbId(item.id, type)
                if (imdbId != null) {
                    onNavigateDetail(type, imdbId)
                } else {
                    Toast.makeText(
                        context,
                        "Couldn't open this title",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // The same two id flavors the TV rail badges read: the IMDb id this
        // screen resolved for the poster (the one the trackers key on), then
        // the poster's own `tmdb:<n>` one, for a title TMDB has no IMDb id for.
        fun posterIsWatched(tmdbId: Int, mediaType: String, keys: Set<String>): Boolean {
            val type = mediaType.lowercase()
            val imdbId = resolvedPosterIds[viewModel.posterLookupKey(tmdbId, type)]
            return (imdbId != null && viewModel.watchedKey(imdbId, type) in keys) ||
                viewModel.watchedKey(WatchedStatusKeys.tmdbKeyId(tmdbId), type) in keys
        }

        fun isPosterWatched(tmdbId: Int, mediaType: String): Boolean =
            posterIsWatched(tmdbId, mediaType, watchedKeys)

        fun isPosterPartiallyWatched(tmdbId: Int, mediaType: String): Boolean =
            posterIsWatched(tmdbId, mediaType, partialWatchedKeys)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            if (meta == null && tmdbDetail == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (isLoading) {
                        // Shaped like the page that is coming - hero still, then
                        // the rails - instead of a mask-sized spinner that then
                        // jolts everything upward when the page lands
                        // (improvement F1).
                        val posterSize = rememberTouchPosterSize()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .background(KBSurfaceRaised, KBShapeCard)
                            )
                            KBSkeletonRail(
                                posterWidth = posterSize.width,
                                posterHeight = posterSize.height,
                                horizontalPadding = 16.dp
                            )
                            KBSkeletonRail(
                                posterWidth = posterSize.width,
                                posterHeight = posterSize.height,
                                horizontalPadding = 16.dp
                            )
                        }
                    } else {
                        Text(
                            text = error ?: "This title could not be loaded.",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
                return@Box
            }

            // ---- the DOWNLOADED badge's title identity (spec §4.4) ----
            //
            // Display only. This page has no download action any more: the
            // phone's only Download lives on the streams picker's long-press
            // menu, which resolves nothing here and needs no 30 s silent
            // window. The badge still answers "is this title already on this
            // phone?", which is worth knowing before streaming it.
            //
            // The episode is the one the Play button would start, resolved by
            // the SAME rule playCurrent uses, so the badge belongs to the
            // episode this page is about to play rather than whichever one is
            // first in the season.
            val downloadResumeHere = targetEpisode != null && resumeInfo?.season == currentSeason
            val downloadEpisode = when {
                normalizedType == "movie" -> null
                downloadResumeHere -> targetEpisode
                else -> episodes.firstOrNull()
            }
            val downloadSeason = if (downloadEpisode != null) currentSeason else null
            val downloadEpisodeNumber = downloadEpisode?.episodeNumber
            val downloadMediaKey = DownloadIdentity.mediaKey(id, id)
            val downloadRecord = downloadFor(
                records = downloads,
                mediaKey = downloadMediaKey,
                season = downloadSeason,
                episode = downloadEpisodeNumber
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item(key = "header") {
                    MobileDetailHeader(
                        // The other end of the phone's poster -> Detail flight:
                        // the same type/id the card armed on press.
                        type = type,
                        id = id,
                        title = displayName,
                        backdropUrl = heroBackdropUrl,
                        logoUrl = clearLogoUrl,
                        metaLine = detailMetaLine(normalizedType, tmdbDetail),
                        trailerPlayback = heroTrailer,
                        trailerMuted = trailerMuted,
                        onTrailerEnded = { stopHeroTrailer() },
                        // The Home hero's own retry rule and cap: re-resolve a
                        // fresh signed URL once, from where it died, and no
                        // more than once for the same trailer.
                        onTrailerFailed = { positionMs ->
                            if (trailerAttempt < 1) {
                                trailerResumeMs = positionMs
                                trailerAttempt += 1
                                // A retry must re-sign: the cached source is
                                // the URL that just died.
                                trailerKey?.let { TrailerPlayerLauncher.invalidate(it) }
                                trailerRequest += 1
                            }
                        },
                        onCloseTrailer = { stopHeroTrailer() },
                        onBack = { backDispatcher?.onBackPressed() }
                    )
                }

                item(key = "cta") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            KBButton(
                                label = playButtonText,
                                modifier = Modifier.weight(1f),
                                // Hold for the source list (the TV's long
                                // press on this button); a tap still plays.
                                onLongClick = { openCurrentStreams() }
                            ) {
                                playCurrent()
                            }
                            // The trailer button plays in the hero above it,
                            // not in the fullscreen player: a second press (or
                            // the hero's own close mark) puts the backdrop
                            // back. Media-row cards still open the full player -
                            // they are the videos themselves, chosen one by one.
                            trailerVideo?.let {
                                KBButton(
                                    label = if (heroTrailer != null) {
                                        "STOP TRAILER"
                                    } else {
                                        "TRAILER"
                                    }
                                ) {
                                    if (heroTrailer != null) {
                                        stopHeroTrailer()
                                    } else {
                                        // A press is a request: bump the counter
                                        // the resolve is keyed on (see above -
                                        // a flag it cleared would cancel it).
                                        trailerAttempt = 0
                                        trailerResumeMs = 0L
                                        trailerRequest += 1
                                    }
                                }
                            }
                        }
                        if (
                            normalizedType == "movie" &&
                            resumeInfo?.positionMs?.let { it > 0 } == true
                        ) {
                            KBButton(
                                label = "START OVER",
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                openMovie(fromStart = true)
                            }
                        }

                        // Save the title being read. Hidden once it is on My
                        // List (this page offers no remove, matching the
                        // poster menus, which only add).
                        if (!inLibrary) {
                            KBButton(
                                label = "ADD TO LIBRARY",
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LibraryAdds.add(context, libraryTarget) { added ->
                                    inLibrary = added
                                    Toast.makeText(
                                        context,
                                        if (added) {
                                            "Added to Library"
                                        } else {
                                            "Couldn't add to Library"
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        // ---- offline download, display only (spec §4.4) ----
                        //
                        // The badge, when the title this page is showing is on
                        // the phone. No action: downloads are started from the
                        // streams picker's long-press menu, and managed on the
                        // Downloads screen in Settings. This page only tells
                        // the viewer the title is already here.
                        if (downloadRecord?.state?.isComplete == true) {
                            DownloadedBadge()
                        }
                    }
                }

                // The title's tags, directly under the calls to action where
                // the meta line's genre list used to sit: a genre or a keyword
                // is a control here, not a caption, and each one opens its Tag
                // page exactly as the TV chip does.
                if (tmdbGenres.isNotEmpty() || keywords.isNotEmpty()) {
                    item(key = "tags") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (tmdbGenres.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        tmdbGenres,
                                        key = { "genre:" + it.id },
                                        contentType = { "genre" }
                                    ) { genre ->
                                        TouchLinkChip(
                                            label = genre.name,
                                            onClick = {
                                                onNavigateTag(genre.id, genre.name, false, type)
                                            }
                                        )
                                    }
                                }
                            }
                            if (keywords.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        keywords,
                                        key = { "keyword:" + it.id },
                                        contentType = { "keyword" }
                                    ) { keyword ->
                                        TouchLinkChip(
                                            // The TV keyword chip's own mark: a
                                            // keyword is a tag, not a genre.
                                            label = "#" + keyword.name,
                                            emphasized = false,
                                            onClick = {
                                                onNavigateTag(
                                                    keyword.id,
                                                    keyword.name,
                                                    true,
                                                    type
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                overview?.takeIf { it.isNotBlank() }?.let { text ->
                    item(key = "overview") {
                        Text(
                            text = text,
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // Critic/audience scores, right under the overview exactly as
                // on the TV page. The phone page never read this flow, so an
                // MDBList account's ratings were missing here while the TV
                // showed them.
                if (ratingSources.isNotEmpty()) {
                    item(key = "ratings") {
                        MobileRatingsStrip(
                            sources = ratingSources,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                if (normalizedType != "movie" && seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSectionTitle(if (episodesLoading) "Episodes…" else "Episodes")
                            // The season chips own the row now. The episode
                            // area's LIST/RAIL choice is a Settings row
                            // (Settings → Posters & Browsing), not a control
                            // on this page: it is a viewing preference that
                            // applies to every season, so it belongs beside
                            // the other display choices.
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    seasons,
                                    key = { it.seasonNumber },
                                    contentType = { "season" }
                                ) { summary ->
                                    TouchChoiceChip(
                                        label = summary.name
                                            ?: "Season ${summary.seasonNumber}",
                                        selected = summary.seasonNumber == currentSeason,
                                        onClick = {
                                            season = summary.seasonNumber
                                            viewModel.loadEpisodesForSeason(summary.seasonNumber)
                                        },
                                        onLongClick = {
                                            seasonMenu = SeasonMenuTarget(
                                                seasonNumber = summary.seasonNumber,
                                                seasonName = summary.name
                                                    ?: "Season ${summary.seasonNumber}"
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (episodeRail) {
                        // The sideways rail: the television's episode card,
                        // the touch twin of it, filled from the same
                        // `episodeVisual` the list row below reads.
                        item(key = "episodeRail") {
                            KBEpisodeRailTouch(
                                episodes = episodes,
                                // Keyed by the episode, not the file that holds
                                // it: streamId is file numbering, so a show
                                // whose files carry two segments gives two rows
                                // one key and Compose throws on the duplicate.
                                // Same identity as the TV rail (episodeRowKey)
                                // and the list branch below.
                                key = { episode ->
                                    episodeRowKey(id, currentSeason, episode.episodeNumber)
                                }
                            ) { episode ->
                                val visual = episodeVisual(episode)
                                KBEpisodeRailCardTouch(
                                    episode = episode,
                                    hiddenTitle = visual.hiddenTitle,
                                    isWatched = visual.isWatched,
                                    unavailable = visual.unavailable,
                                    progress = visual.progress,
                                    // The TV card's own formatter, so the
                                    // date reads the same on both screens.
                                    airDate = episode.airDate
                                        ?.let { formatEpisodeAirDate(it) },
                                    onClick = { openEpisode(episode, currentSeason) },
                                    onLongClick = {
                                        episodeMenu =
                                            EpisodeMenuTarget(episode, currentSeason)
                                    }
                                )
                            }
                        }
                    } else {
                        items(
                            episodes,
                            // NOT the stream id: it is the FILE that holds the
                            // episode and is shared by every episode of a
                            // segmented show, so two rows carried one key and
                            // LazyColumn threw "Key ... was already used" -
                            // the crash the TV rail already fixed with
                            // episodeRowKey.
                            key = { episode ->
                                episodeRowKey(id, currentSeason, episode.episodeNumber)
                            },
                            contentType = { "episode" }
                        ) { episode ->
                            val visual = episodeVisual(episode)
                            // A season switch replaces the list; the
                            // survivor rows fade in and step where they
                            // belong instead of the page cutting.
                            EpisodeRow(
                                episode = episode,
                                hiddenTitle = visual.hiddenTitle,
                                isWatched = visual.isWatched,
                                unavailable = visual.unavailable,
                                progress = visual.progress,
                                onClick = { openEpisode(episode, currentSeason) },
                                onLongClick = {
                                    episodeMenu = EpisodeMenuTarget(episode, currentSeason)
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }

                if (cast.isNotEmpty()) {
                    item(key = "cast") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSectionTitle("Cast")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // One row per (person, character): TMDB repeats a
                                // performer who plays two roles in one title
                                // (routine for animation voice casts), and the
                                // bare id made LazyRow throw — the page was
                                // unopenable for those titles. Same composite
                                // key as the TV cast rail.
                                items(
                                    // The key's own identity, deduped: two
                                    // identical credit rows (TMDB does repeat
                                    // one (id, character) pair) still shared a
                                    // key and threw from LazyRow even though
                                    // two ROLES no longer did.
                                    cast.distinctBy { it.id to it.character.orEmpty() }
                                        .take(20),
                                    key = { member ->
                                        "person${member.id}${member.character.orEmpty()}"
                                    },
                                    contentType = { "cast" }
                                ) { member ->
                                    CastTile(
                                        member = member,
                                        onClick = { onNavigateActor(member.id) }
                                    )
                                }
                            }
                        }
                    }
                }

                // A series names its network(s); every title names its
                // production companies. Both are the TV page's own rails, in
                // its own order, with the chip that opens the Studio page.
                if (normalizedType != "movie" && networks.isNotEmpty()) {
                    item(key = "networks") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSectionTitle("Networks")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(
                                    networks,
                                    key = { "network:" + it.id },
                                    contentType = { "network" }
                                ) { network ->
                                    TouchStudioChip(
                                        name = network.name,
                                        logoPath = network.logoPath,
                                        onClick = {
                                            onNavigateStudio(network.id, network.name, true)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (productionCompanies.isNotEmpty()) {
                    item(key = "production") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSectionTitle("Production")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(
                                    productionCompanies,
                                    key = { "company:" + it.id },
                                    contentType = { "company" }
                                ) { company ->
                                    TouchStudioChip(
                                        name = company.name,
                                        logoPath = company.logoPath,
                                        onClick = {
                                            onNavigateStudio(company.id, company.name, false)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // The facts block, after the companies: Status (series only),
                // Release Info, Runtime, Certification, Origin Country.
                if (facts.isNotEmpty()) {
                    item(key = "facts") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSectionTitle(detailFactsTitle(normalizedType == "movie"))
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                facts.forEachIndexed { index, fact ->
                                    DetailFactRow(fact)
                                    if (index != facts.lastIndex) DetailFactDivider()
                                }
                            }
                        }
                    }
                }

                // The collection is derived above, with the rest of the page
                // state (a `remember` cannot live in the list DSL).
                if (collectionParts.isNotEmpty() && collection != null) {
                    item(key = "collection") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSectionTitle(
                                collection?.name ?: "Collection"
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
                                    collectionParts,
                                    key = { "part:" + it.id },
                                    contentType = { "part" }
                                ) { part ->
                                    // Shape follows Settings' global landscape
                                    // switch, as it does on every catalog row -
                                    // and its size follows Settings' Poster Size.
                                    val partSize = rememberTouchPosterSize()
                                    val tileWidth = rememberPosterTileWidth(partSize.width)
                                    Column(
                                        modifier = Modifier.width(tileWidth),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        TouchGlobalPosterCard(
                                            posterUrl = part.posterPath
                                                ?.let { TmdbRepository.POSTER_BASE + it },
                                            contentDescription = part.title,
                                            isWatched = isPosterWatched(part.id, "movie"),
                                            isPartiallyWatched =
                                                isPosterPartiallyWatched(part.id, "movie"),
                                            onClick = { openCollectionPart(part) },
                                            onLongClick = {
                                                posterMenu = PosterMenuTarget(
                                                    tmdbId = part.id,
                                                    mediaType = "movie",
                                                    name = part.title.orEmpty()
                                                )
                                            },
                                            artId = part.id.toString(),
                                            artType = "movie",
                                            posterWidth = partSize.width,
                                            posterHeight = partSize.height
                                        )
                                        // The caption block every other poster
                                        // row on the phone has always drawn;
                                        // this rail was the one that omitted
                                        // it, so its title/rating/year toggles
                                        // looked broken.
                                        TouchPosterCaptions(
                                            title = part.title ?: part.name ?: "Untitled",
                                            year = part.releaseDate?.take(4),
                                            rating = part.voteAverage,
                                            modifier = Modifier.width(tileWidth)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val recommendations = tmdbDetail?.recommendations?.results.orEmpty()
                if (recommendations.isNotEmpty()) {
                    item(key = "recommendations") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSectionTitle("More Like This")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
                                    recommendations.take(20),
                                    key = { "rec:" + it.id },
                                    contentType = { "recommendation" }
                                ) { item ->
                                    RecommendationTile(
                                        modifier = Modifier.animateItem(),
                                        item = item,
                                        mediaType = type,
                                        isWatched = isPosterWatched(item.id, type),
                                        onClick = { openRecommendation(item) },
                                        onLongClick = {
                                            posterMenu = PosterMenuTarget(
                                                tmdbId = item.id,
                                                mediaType = type,
                                                name = item.title
                                                    ?: item.name
                                                    ?: ""
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Reviews sit after the collection and More Like This, in the
                // order the television paints them (see DetailRow): the reviews
                // are an addendum to a title the viewer has already decided
                // about, while those two rails are how they find the next one.
                if (reviews.isNotEmpty()) {
                    item(key = "reviews") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSectionTitle("Reviews")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
                                    reviews.take(60),
                                    key = { it.id },
                                    contentType = { "review" }
                                ) { review ->
                                    ReviewTile(
                                        review = review,
                                        onClick = { selectedReview = review }
                                    )
                                }
                            }
                        }
                    }
                }

                // The Media row is last, under More Like This and the reviews,
                // exactly as on TV: the title's videos, its other stills and its
                // posters.
                if (mediaTabs.isNotEmpty()) {
                    item(key = "media") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MobileSectionTitle("Media")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    mediaTabs,
                                    key = { it.label },
                                    contentType = { "media-tab" }
                                ) { tab ->
                                    TouchChoiceChip(
                                        label = tab.label,
                                        selected = tab == activeMediaTab,
                                        onClick = { mediaTab = tab }
                                    )
                                }
                            }
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
                                    mediaCards,
                                    key = { it.id },
                                    contentType = { "media-card" }
                                ) { card ->
                                    MediaThumb(
                                        card = card,
                                        onClick = {
                                            val video = card.video
                                            when {
                                                // A video the player cannot open
                                                // says so on its face; the press
                                                // resolves nothing rather than
                                                // building a YouTube URL out of a
                                                // Vimeo key.
                                                video != null && !card.playable -> Unit
                                                video != null ->
                                                    DetailTrailerLaunch.play(context, scope, video)
                                                else ->
                                                    selectedBackdrop = card.fullImageUrl
                                            }
                                        },
                                        onLongClick = {
                                            // Only artwork offers a surface to
                                            // stand in for (see overrideRows).
                                            if (card.imagePath != null) {
                                                mediaImageMenu = card
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- overlays ----

            // The still, fullscreen, dismissed by Back, the scrim or CLOSE.
            selectedBackdrop?.let { imageUrl ->
                ImageViewerDialog(
                    imageUrl = imageUrl,
                    contentDescription = displayName,
                    onDismiss = { selectedBackdrop = null }
                )
            }

            selectedReview?.let { review ->
                ReviewDialog(
                    review = review,
                    onDismiss = { selectedReview = null }
                )
            }

            // The Media row's long press: which surface of this title the
            // pressed still becomes, one row per surface (see overrideRows).
            mediaImageMenu?.let { card ->
                val menuType = if (activeMediaTab == MediaTab.POSTERS) {
                    ImageOverrides.TYPE_POSTER
                } else {
                    ImageOverrides.TYPE_BACKDROP
                }
                PosterContextMenu(
                    title = displayName.ifBlank { id },
                    subtitle = activeMediaTab?.label,
                    actions = DetailMediaRow
                        .overrideRows(
                            tab = activeMediaTab,
                            hasOverride = ImageOverrides.hasOverride(
                                paths = overridePaths,
                                tmdbId = overrideTmdbId,
                                type = menuType
                            )
                        )
                        .map { row ->
                            PosterContextAction(
                                label = row.label,
                                description = row.description,
                                isDestructive = row.isDestructive,
                                onClick = {
                                    val path = card.imagePath
                                    val targetId = overrideTmdbId
                                    if (path != null && targetId != null) {
                                        val surface = row.screen
                                        if (surface == null) {
                                            // "Reset to default": every
                                            // surface of the kind, since a
                                            // viewer asking for TMDB's art
                                            // back is asking about the title.
                                            ImageOverrides.reset(
                                                context = context,
                                                tmdbId = targetId,
                                                type = menuType
                                            )
                                        } else {
                                            ImageOverrides.choose(
                                                context = context,
                                                tmdbId = targetId,
                                                type = menuType,
                                                screen = surface,
                                                imagePath = path
                                            )
                                        }
                                    }
                                    mediaImageMenu = null
                                }
                            )
                        },
                    onDismiss = { mediaImageMenu = null }
                )
            }

            // The season chip's long press: this season, or the whole show.
            seasonMenu?.let { target ->
                val numbers = seasonNumbersFor(target.seasonNumber).orEmpty()
                val watchedSet = watchedSeasonSet(target.seasonNumber)
                val isSeasonWatched =
                    MobileDetailRules.allWatched(numbers, watchedSet)
                val menuContext = context

                PosterContextMenu(
                    title = displayName.ifBlank { "Untitled" },
                    subtitle = target.seasonName,
                    actions = buildList {
                        add(
                            PosterContextAction(
                                label = if (isSeasonWatched) {
                                    "Mark as Unwatched"
                                } else {
                                    "Mark as Watched"
                                }
                            ) {
                                seasonMenu = null
                                if (isSeasonWatched) {
                                    viewModel.markSeasonUnwatched(
                                        target.seasonNumber,
                                        numbers
                                    )
                                } else {
                                    viewModel.markSeasonWatched(
                                        target.seasonNumber,
                                        numbers
                                    )
                                }
                            }
                        )
                        add(
                            PosterContextAction(
                                label = if (wholeSeriesWatched) {
                                    "Mark Entire Series as Unwatched"
                                } else {
                                    "Mark Entire Series as Watched"
                                },
                                description = if (wholeSeriesWatched) {
                                    "Clear every season on this device and your trackers"
                                } else {
                                    "Every season at once, not just this one"
                                }
                            ) {
                                seasonMenu = null
                                if (wholeSeriesWatched) {
                                    viewModel.markSeriesUnwatched()
                                } else {
                                    viewModel.markSeriesWatched(
                                        wholeSeriesEpisodeNumbers
                                    )
                                }
                            }
                        )
                    } + listOfNotNull(
                        // Simkl's attribution rule, at the one place where its
                        // data is what the menu is about: null means there is
                        // no page to link to (no account, no record, a movie).
                        simklPageUrl?.let { url ->
                            PosterContextAction(
                                label = "View on Simkl",
                                description = "Open this title's page on simkl.com"
                            ) {
                                seasonMenu = null
                                runCatching {
                                    menuContext.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(url)
                                        )
                                    )
                                }
                            }
                        }
                    ),
                    onDismiss = { seasonMenu = null }
                )
            }

            // The episode row's long press: the TV card menu's rows, in the
            // TV order.
            episodeMenu?.let { target ->
                val episode = target.episode
                val numbers = seasonNumbersFor(target.season).orEmpty()
                val watchedSet = watchedSeasonSet(target.season)
                val isEpisodeWatched = episode.episodeNumber in watchedSet
                val isSeasonWatched = MobileDetailRules.allWatched(numbers, watchedSet)
                val hidesTitle = SpoilerFree.hidesIdentity(
                    enabled = spoilerFreeEnabled,
                    watched = isEpisodeWatched,
                    started = inProgressByStreamId[episode.streamId] != null ||
                        resumeInfo?.episodeStreamId == episode.streamId
                )

                PosterContextMenu(
                    title = if (hidesTitle) {
                        SpoilerFree.episodeLabel(
                            hidden = true,
                            episodeNumber = episode.episodeNumber,
                            realTitle = null
                        )
                    } else {
                        episode.name?.takeIf { it.isNotBlank() }
                            ?: "Episode ${episode.episodeNumber}"
                    },
                    subtitle = "S${target.season.toString().padStart(2, '0')} · " +
                        "E${episode.episodeNumber.toString().padStart(2, '0')}",
                    actions = buildList {
                        add(
                            PosterContextAction(
                                label = if (isEpisodeWatched) {
                                    "Mark as Unwatched"
                                } else {
                                    "Mark as Watched"
                                }
                            ) {
                                episodeMenu = null
                                if (isEpisodeWatched) {
                                    viewModel.markEpisodeUnwatched(
                                        target.season,
                                        episode.episodeNumber
                                    )
                                } else {
                                    // The stream id is this episode's
                                    // progress-row identity, so the cleanup
                                    // matches even if the numbers disagree
                                    // with the source.
                                    viewModel.markEpisodeWatched(
                                        target.season,
                                        episode.episodeNumber,
                                        episode.streamId
                                    )
                                }
                            }
                        )
                        if (episode.episodeNumber > 1) {
                            add(
                                PosterContextAction(
                                    label = if (isEpisodeWatched) {
                                        "Mark Previous as Unwatched"
                                    } else {
                                        "Mark Previous as Watched"
                                    }
                                ) {
                                    episodeMenu = null
                                    if (isEpisodeWatched) {
                                        viewModel.markPreviousUnwatched(
                                            target.season,
                                            episode.episodeNumber
                                        )
                                    } else {
                                        viewModel.markPreviousWatched(
                                            target.season,
                                            episode.episodeNumber
                                        )
                                    }
                                }
                            )
                        }
                        add(
                            PosterContextAction(
                                label = if (isSeasonWatched) {
                                    "Mark Season as Unwatched"
                                } else {
                                    "Mark Season as Watched"
                                }
                            ) {
                                episodeMenu = null
                                if (isSeasonWatched) {
                                    viewModel.markSeasonUnwatched(
                                        target.season,
                                        numbers
                                    )
                                } else {
                                    viewModel.markSeasonWatched(
                                        target.season,
                                        numbers
                                    )
                                }
                            }
                        )
                        add(
                            PosterContextAction(
                                label = if (wholeSeriesWatched) {
                                    "Mark Entire Series as Unwatched"
                                } else {
                                    "Mark Entire Series as Watched"
                                },
                                description = if (wholeSeriesWatched) {
                                    "Clear every season on this device and your trackers"
                                } else {
                                    "Every season at once, not just this one"
                                }
                            ) {
                                episodeMenu = null
                                if (wholeSeriesWatched) {
                                    viewModel.markSeriesUnwatched()
                                } else {
                                    viewModel.markSeriesWatched(
                                        wholeSeriesEpisodeNumbers
                                    )
                                }
                            }
                        )
                        add(
                            PosterContextAction(
                                label = "Play from Beginning",
                                description =
                                    "Start this episode over from the beginning"
                            ) {
                                val selected = episode
                                episodeMenu = null
                                openEpisode(
                                    selected,
                                    target.season,
                                    fromStart = true
                                )
                            }
                        )
                        add(
                            PosterContextAction(
                                label = "Play Manually"
                            ) {
                                val selected = episode
                                episodeMenu = null
                                openEpisode(selected, target.season)
                            }
                        )
                    },
                    onDismiss = { episodeMenu = null }
                )
            }

            // A collection part or More Like This poster: go to its page, mark
            // it, add it to the library, hide it.
            posterMenu?.let { target ->
                val mediaType = target.mediaType.lowercase()
                val isWatched = isPosterWatched(target.tmdbId, mediaType)
                val imdbId = resolvedPosterIds[
                    viewModel.posterLookupKey(target.tmdbId, mediaType)
                ]
                PosterContextMenu(
                    title = target.name.ifBlank { "Untitled" },
                    libraryTarget = LibraryAddTarget(
                        mediaType = target.mediaType,
                        imdbId = imdbId,
                        tmdbId = target.tmdbId,
                        title = target.name.ifBlank { "Untitled" },
                        posterUrl = viewModel.currentPosterUrl()
                    ),
                    hideTarget = hideTarget(
                        target.name.ifBlank { "Untitled" },
                        target.mediaType,
                        viewModel.currentPosterUrl(),
                        listOf("tmdb:${target.tmdbId}", imdbId)
                    ),
                    actions = listOf(
                        PosterContextAction(
                            label = "Go to Details",
                            description = "Open this title's detail page"
                        ) {
                            val selected = target
                            posterMenu = null
                            scope.launch {
                                val resolved = viewModel.resolveImdbId(
                                    selected.tmdbId,
                                    selected.mediaType
                                )
                                if (resolved != null) {
                                    onNavigateDetail(selected.mediaType, resolved)
                                }
                            }
                        },
                        PosterContextAction(
                            label = if (isWatched) {
                                "Mark as Unwatched"
                            } else {
                                "Mark as Watched"
                            },
                            description = if (isWatched) {
                                "Clear this title's watched state"
                            } else {
                                "Mark this title watched everywhere"
                            }
                        ) {
                            val selected = target
                            posterMenu = null
                            if (isWatched) {
                                viewModel.markPosterUnwatched(
                                    selected.tmdbId,
                                    selected.mediaType
                                )
                            } else {
                                viewModel.markPosterWatched(
                                    selected.tmdbId,
                                    selected.mediaType
                                )
                            }
                        }
                    ),
                    onDismiss = { posterMenu = null }
                )
            }
        }
    }
}

/** The header: backdrop, clearlogo or title, and the meta line. */
@Composable
private fun MobileDetailHeader(
    type: String,
    id: String,
    title: String,
    backdropUrl: String?,
    logoUrl: String?,
    metaLine: String,
    /** The hero trailer, or null while the backdrop is what shows. */
    trailerPlayback: HeroTrailerPlayback?,
    trailerMuted: Boolean,
    onTrailerEnded: () -> Unit,
    onTrailerFailed: (positionMs: Long) -> Unit,
    onCloseTrailer: () -> Unit,
    onBack: () -> Unit
) {
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    ) {
        //
        // The backdrop fades in, like every other hero in the app.
        //
        // A bare `AsyncImage(model = url)` inherits the loader's global
        // `.crossfade(false)` - the rule for rail and grid TILES, which is
        // right there and wrong here. So the phone's Detail header popped in
        // while the television's Detail hero, Home's phone hero and every
        // desktop backdrop crossfaded: the worst single pop on the biggest
        // surface the phone has, and a one-line difference from parity.
        val context = LocalContext.current
        val backdropRequest = remember(context, backdropUrl) {
            ImageRequest.Builder(context)
                .data(backdropUrl)
                .crossfade(true)
                .build()
        }
        AsyncImage(
            model = backdropRequest,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            // The destination end of the flight. One-way by the same rule the
            // television uses (see KBHeroTransition): the hero drops out on the
            // way back rather than zooming a Crop image into a poster rect.
            modifier = Modifier
                .fillMaxSize()
                .heroSharedElement(type, id)
        )
        // The shared hero player, over the still: its shutter is transparent,
        // so until the first frame lands the backdrop above is exactly what a
        // viewer sees (the same hand-off Home's hero makes).
        if (trailerPlayback != null) {
            HeroInlineTrailerPlayer(
                source = trailerPlayback.source,
                muted = trailerMuted,
                startPositionMs = trailerPlayback.startPositionMs,
                onEnded = onTrailerEnded,
                onFailed = onTrailerFailed,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            KBVoid.copy(alpha = 0.35f),
                            KBVoid.copy(alpha = 0.95f)
                        )
                    )
                )
        )
        MobileBackButton(
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
        )
        // A playing trailer needs a way out that is not "press TRAILER again":
        // the hero is a video surface, so a close mark on it is the affordance
        // a thumb looks for.
        if (trailerPlayback != null) {
            IconButton(
                onClick = onCloseTrailer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Stop trailer",
                    tint = KBTextHi
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (logoUrl != null && !logoFailed) {
                BrandMarkImage(
                    url = logoUrl,
                    contentDescription = title,
                    onUnusable = { logoFailed = true },
                    modifier = Modifier
                        .height(38.dp)
                        .width(200.dp)
                )
            } else {
                Text(
                    text = title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (metaLine.isNotBlank()) {
                Text(
                    text = metaLine,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One episode row: still, number and name, overview, runtime, the episode's
 * IMDb score and air date, watched state, and - when the season's dates can be
 * trusted - an UNAVAILABLE mark on an episode that has not aired yet. The
 * runtime and score come from the same helpers the sideways rail card uses, and
 * the date from the TV row's own formatter, so the layouts cannot word or
 * format the same episode differently. The name is replaced with
 * "Episode <n>" by spoiler-free mode for an unstarted episode (see
 * [SpoilerFree]), and a long press raises the episode menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: ResolvedEpisode,
    hiddenTitle: Boolean,
    isWatched: Boolean,
    unavailable: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(KBShapeCard)
            // Same gate as the rail card and the TV episode card: an unaired
            // episode draws an UNAVAILABLE mark, and pressing it must not open
            // source resolution (which fails with a source error) nor the
            // episode menu (whose mark would reach the trackers).
            .combinedClickable(
                onClick = { if (!unavailable) onClick() },
                onLongClick = if (unavailable) null else onLongClick
            )
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(132.dp)
                .height(74.dp)
                .clip(KBShapeCard)
                .background(KBSurface)
                // The same edge the rail card and the page's posters draw: a
                // row of dark stills on a near-black page runs together without
                // it. The card shape, not the poster edge - this still is not a
                // poster.
                .then(posterBorderModifier(KBShapeCard))
        ) {
            AsyncImage(
                model = episode.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // The same marker the poster tiles and the rail card draw
            // (`WatchedCheckBadge`), at the compact size this 132x74 still
            // leaves room for: the row and the rail must not say "watched" two
            // different ways. This used to be a tick of the row's own making.
            if (isWatched) {
                WatchedCheckBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    diameter = 18.dp
                )
            }
            progress?.let { fraction ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(KBVoid.copy(alpha = 0.7f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(KBAccent)
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = buildString {
                    append(episode.episodeNumber)
                    val name = SpoilerFree.episodeLabel(
                        hidden = hiddenTitle,
                        episodeNumber = episode.episodeNumber,
                        realTitle = episode.name
                    )
                    if (name.isNotBlank()) append(". ").append(name)
                },
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!hiddenTitle) {
                episode.overview?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                episodeRuntimeLabel(episode.runtimeMinutes, progress)?.let { label ->
                    Text(
                        text = "🕒 $label",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (unavailable) {
                    Text(
                        text = "UNAVAILABLE",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    // No score on an unaired episode, exactly as the TV card:
                    // a future episode's vote average is early-vote noise.
                    episode.voteAverage
                        ?.takeIf { it > 0.0 }
                        ?.let { EpisodeRatingChip(rating = it) }
                }
                // The television row's date, sat at the end of the line: it is
                // what tells a viewer whether the next episode is out yet, and
                // the TV card's formatter keeps the two screens in step.
                episode.airDate
                    ?.takeIf { it.isNotBlank() }
                    ?.let { airDate ->
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = formatEpisodeAirDate(airDate),
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
            }
        }
    }
}

/** One cast member: round portrait and name, tappable to the actor screen. */
@Composable
private fun CastTile(
    member: TmdbCastMember,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(84.dp)
            .clip(KBShapeCard)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AsyncImage(
            model = member.profilePath?.let { TmdbRepository.PROFILE_BASE + it },
            contentDescription = member.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(KBSurface)
        )
        Text(
            text = member.name,
            color = KBTextHi,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // Who they play, under the name: the TV cast card's own line, in the
        // same slot and type. A blank character is TMDB answering with an
        // empty string, which is not a name - the field report was a cast row
        // that showed only the actor, and an empty line here would be the same
        // row with a gap under it.
        member.character?.takeIf { it.isNotBlank() }?.let { character ->
            Text(
                text = character,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * One More Like This tile: the touch poster twin, with the watched badge the
 * rail draws on TV and a long press for its menu.
 */
@Composable
private fun RecommendationTile(
    item: TmdbRecommendationItem,
    /** The page's own type: recommendations carry no media type of their own. */
    mediaType: String,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val posterSize = rememberTouchPosterSize()
    val tileWidth = rememberPosterTileWidth(posterSize.width)
    Column(
        modifier = modifier.width(tileWidth),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TouchGlobalPosterCard(
            posterUrl = item.posterPath?.let { TmdbRepository.POSTER_BASE + it },
            backdropUrl = item.backdropPath?.let { TmdbRepository.BACKDROP_BASE + it },
            contentDescription = item.title ?: item.name,
            isWatched = isWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            artId = item.id.toString(),
            artType = mediaType,
            posterWidth = posterSize.width,
            posterHeight = posterSize.height
        )
        // More Like This was the second rail missing the shared caption block;
        // the title/rating/year toggles apply here exactly as they do on the
        // browse rows (which this tile is otherwise the twin of).
        TouchPosterCaptions(
            title = item.title ?: item.name ?: "Untitled",
            year = (item.releaseDate ?: item.firstAirDate)?.take(4),
            rating = item.voteAverage,
            modifier = Modifier.width(tileWidth)
        )
    }
}

/**
 * One review tile: author, the author's star rating, and a preview - never the
 * text of a spoiler-flagged review, which shows the fixed gate notice instead
 * (the full text is only revealed in [ReviewDialog] after opt-in).
 */
@Composable
private fun ReviewTile(
    review: TmdbReview,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = KBShapeCard,
        color = KBSurface,
        contentColor = KBTextHi,
        modifier = Modifier.width(240.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = review.author,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                review.authorDetails?.rating?.let { rating ->
                    Text(
                        text = "★ %.1f".format(rating),
                        color = KBAccent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (review.spoiler) {
                Text(
                    text = "Spoiler — tap to reveal",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic
                )
            } else {
                Text(
                    text = review.content,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The full review, in a dialog. A spoiler-flagged review starts gated: the
 * gate is a button, and only a deliberate second tap shows the text.
 */
@Composable
private fun ReviewDialog(
    review: TmdbReview,
    onDismiss: () -> Unit
) {
    var revealed by remember(review.id) { mutableStateOf(!review.spoiler) }

    // The app's dialog plate, not a hand-built Surface: this one had grown its
    // own inset, corner and close row, which made it the only dialog on a phone
    // that did not look like a dialog. The panel owns the heading (the author,
    // in the panel's accent caps) and the spacing between children.
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Review", subtitle = review.author) {
            review.authorDetails?.rating?.let { rating ->
                Text(
                    text = "★ %.1f".format(rating),
                    color = KBAccent,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            if (revealed) {
                Text(
                    text = review.content,
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            } else {
                Text(
                    text = "This review contains spoilers.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic
                )
                KBButton(label = "REVEAL SPOILER") {
                    revealed = true
                }
            }
        }
    }
}

/**
 * The fullscreen still, over the void: the one destination-less zoom on this
 * page. Back, a tap anywhere, or CLOSE dismisses it.
 */
@Composable
private fun ImageViewerDialog(
    imageUrl: String,
    contentDescription: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid.copy(alpha = 0.96f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = KBTextHi
                )
            }
        }
    }
}

/**
 * One card of the Media row: a video (with its type badge, and an UNAVAILABLE
 * mark when the player cannot open it) or a still/poster.
 *
 * The portrait page draws portrait: the poster page is the one page that is
 * not a 16:9 still, and cropping it into one is what the row exists to avoid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumb(
    card: MediaCardItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val width = if (card.portrait) 120.dp else 240.dp
    val height = if (card.portrait) 180.dp else 135.dp
    val unplayable = card.video != null && !card.playable

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(KBShapeCard)
            .background(KBSurface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        card.badge?.takeIf { it.isNotBlank() }?.let { label ->
            Surface(
                shape = KBShapeCard,
                color = KBVoid.copy(alpha = 0.78f),
                contentColor = KBAccent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        if (unplayable) {
            Text(
                text = "UNAVAILABLE",
                color = KBAccent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(KBVoid.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** The shared rail heading, so this page cannot drift from the others. */
@Composable
private fun MobileSectionTitle(text: String) {
    MobileSectionHeader(text)
}

/**
 * The title's critic/audience scores on ONE line - the touch twin of the TV
 * page's RATINGS row, trimmed to what a phone can read at a glance.
 *
 * The sources and their order come from the shared [mdbListRatingSources]
 * rule, so the phone and the TV cannot disagree about which score belongs to
 * which brand; the phone takes the head of that list through the shared
 * [mobileRatingSources] (IMDb, Rotten Tomatoes, TMDB, Metacritic), because
 * seven chips became two lines under a paragraph of synopsis and read as a
 * second block of body text. The marks are tinted with each source's own
 * colour and the values use the accent, as on the TV.
 *
 * The row scrolls sideways rather than wrapping: a larger font scale can widen
 * four chips past a narrow screen, and a strip that scrolls a little still
 * reads as one line where a wrapped one kicks a stray chip onto a second.
 */
@Composable
private fun MobileRatingsStrip(
    sources: List<RatingChipSource>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        mobileRatingSources(sources).forEach { source ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(KBShapeChip)
                    .background(KBSurfaceRaised)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Image(
                    painter = painterResource(id = source.icon),
                    contentDescription = "${source.name} rating",
                    colorFilter = ColorFilter.tint(source.tint),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = source.value,
                    color = KBAccent,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * One row of the Movie / Show Details block: the label in the muted colour on
 * the left, the value right-aligned, wrapping when it has to.
 *
 * The wrap is what the Awards row needs: OMDB answers with a whole sentence
 * ("Won 2 Oscars. 159 wins & 220 nominations total") and the television shows
 * it whole rather than truncated, so this row grows a line instead of ellipsing
 * a fact away.
 */
@Composable
private fun DetailFactRow(fact: DetailFact) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = fact.label,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = fact.value,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f)
        )
    }
}

/** The hairline between two facts, at the app's one divider weight. */
@Composable
private fun DetailFactDivider() {
    MobileDivider()
}

/** One season chip's long-press subject. */
private data class SeasonMenuTarget(
    val seasonNumber: Int,
    val seasonName: String
)

/** One episode row's long-press subject. */
private data class EpisodeMenuTarget(
    val episode: ResolvedEpisode,
    val season: Int
)

/**
 * What one episode's card needs from the page's state, resolved in one place
 * ([com.kennyb1201.kbstream.ui.mobile.DetailScreenMobile]'s own `episodeVisual`)
 * so the vertical list and the sideways rail cannot disagree about an episode.
 */
private data class EpisodeVisual(
    val hiddenTitle: Boolean,
    val isWatched: Boolean,
    val unavailable: Boolean,
    val progress: Float?
)

/** One collection part / More Like This poster's long-press subject. */
private data class PosterMenuTarget(
    val tmdbId: Int,
    val mediaType: String,
    val name: String
)

/** `★ 8.2 · 2024 · 2h 10m`, from whatever TMDB answered. */
private fun detailMetaLine(type: String, detail: TmdbDetail?): String {
    if (detail == null) return ""
    val year = (detail.releaseDate ?: detail.firstAirDate)?.take(4)?.takeIf { it.length == 4 }
    val runtime = if (type == "movie") {
        detail.runtime?.takeIf { it > 0 }?.let { minutes ->
            if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
        }
    } else {
        detail.episodeRunTime.firstOrNull()?.takeIf { it > 0 }?.let { "${it}m" }
    }
    // The genres used to be a comma-joined tail here. They are chips now (see
    // the page's `tags` item), so naming them twice - once as text that goes
    // nowhere and once as controls that open the Tag page - read as though the
    // plain one were the interactive one.
    return listOfNotNull(
        detail.voteAverage?.takeIf { it > 0.0 }
            // Locale.US, like every other rating readout (audit B3).
            ?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) },
        year,
        runtime
    ).joinToString("  ·  ")
}

/** The canonical `Show S2 E5 • Name` the streams picker re-parses. */
private fun episodeTargetTitle(
    showName: String,
    season: Int,
    episode: ResolvedEpisode
): String = buildString {
    append(showName)
    append(" S").append(season)
    append(" E").append(episode.episodeNumber)
    episode.name?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
}

/** Resume fraction for a history row, or null when there is nothing to show. */
private fun progressOf(positionMs: Long, durationMs: Long): Float? {
    if (durationMs <= 0L || positionMs <= 0L) return null
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
