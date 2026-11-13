package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.util.Log
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.kb.BrowseHomeRail
import com.kennyb1201.kbstream.data.kb.BrowseHomeShortcut
import com.kennyb1201.kbstream.data.kb.chipKey
import com.kennyb1201.kbstream.data.tmdb.BlendPick
import com.kennyb1201.kbstream.data.tmdb.BrowseShortcutArt
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.kb.KBCollectionProfile
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.kb.KBFolder
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.data.library.libraryRailTypeSuffix
// The pin's stable key, so two pinned rails of the same list cannot share one
// LazyColumn slot (the same extension the TV's rail keys itself by).
import com.kennyb1201.kbstream.data.library.key
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import com.kennyb1201.kbstream.data.reporting.CwFilterTrace
import com.kennyb1201.kbstream.data.reporting.SpotlightDiagTrace
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import com.kennyb1201.kbstream.data.spoiler.SpoilerFree
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.youtube.HeroTrailerResumeVerdict
import com.kennyb1201.kbstream.data.youtube.InnerTubeExtractor
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.PoTokenTrace
import com.kennyb1201.kbstream.data.youtube.TokenlessHoldException
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerPool
import com.kennyb1201.kbstream.data.youtube.deathKind
import com.kennyb1201.kbstream.data.youtube.googlevideoBytes
import com.kennyb1201.kbstream.data.youtube.googlevideoItag
import com.kennyb1201.kbstream.data.youtube.heroTrailerIdentityRefusal
import com.kennyb1201.kbstream.data.youtube.heroTrailerResolveFailurePoisons
import com.kennyb1201.kbstream.data.youtube.heroTrailerRetryPlan
import com.kennyb1201.kbstream.data.youtube.signedUrl
import com.kennyb1201.kbstream.ui.home.LibraryHomeRailUi
import com.kennyb1201.kbstream.ui.home.failedAddonsMessage
import com.kennyb1201.kbstream.ui.home.rememberBingeCatReconnectNotice
import com.kennyb1201.kbstream.ui.home.rememberLibraryHomeRails
import com.kennyb1201.kbstream.ui.components.BrandMarkImage
import com.kennyb1201.kbstream.ui.components.CarriedArt
import com.kennyb1201.kbstream.ui.components.InfiniteScrollEffect
import com.kennyb1201.kbstream.ui.components.armHeroSource
import com.kennyb1201.kbstream.ui.components.heroSourceOnPress
import com.kennyb1201.kbstream.ui.components.KB_PRESS_SPRING
import com.kennyb1201.kbstream.ui.components.KBProgressBar
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.posterBorderModifier
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.components.imageOverrideCardBackdropUrl
import com.kennyb1201.kbstream.ui.components.imageOverridePosterUrl
import com.kennyb1201.kbstream.ui.components.landscapeArtKey
import com.kennyb1201.kbstream.ui.components.landscapeRank
import com.kennyb1201.kbstream.ui.components.landscapeTileHeight
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.rememberGlobalLandscapeArt
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.rememberTitlePosterArt
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.LandscapeCard
import com.kennyb1201.kbstream.ui.components.touch.MobileSectionHeader
import com.kennyb1201.kbstream.ui.components.touch.MobileSpacing
import com.kennyb1201.kbstream.ui.components.touch.PosterCard
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.KBSkeletonRail
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchTileWidth
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.home.BILLBOARD_CONTENT_REVEAL_MS
import com.kennyb1201.kbstream.ui.home.BILLBOARD_CONTENT_RISE_DP
import com.kennyb1201.kbstream.ui.home.BILLBOARD_KEN_BURNS_MANUAL_MS
import com.kennyb1201.kbstream.ui.home.BILLBOARD_MAX_PAGES
import com.kennyb1201.kbstream.ui.home.BILLBOARD_SEGMENT_TRACK_DP
import com.kennyb1201.kbstream.ui.home.BillboardPage
import com.kennyb1201.kbstream.ui.home.BillboardPlaySelection
import com.kennyb1201.kbstream.ui.home.billboardHeroArt
import com.kennyb1201.kbstream.ui.home.billboardPageArt
import com.kennyb1201.kbstream.ui.home.BillboardSweep
import com.kennyb1201.kbstream.ui.home.HeroInlineTrailerPlayer
import com.kennyb1201.kbstream.ui.home.HeroTrailerPlayback
import com.kennyb1201.kbstream.ui.home.HomeViewModel
import com.kennyb1201.kbstream.ui.home.Rail
import com.kennyb1201.kbstream.ui.home.billboardChosenFeed
import com.kennyb1201.kbstream.ui.home.billboardSegmentFill
import com.kennyb1201.kbstream.ui.home.billboardSweep
import com.kennyb1201.kbstream.ui.home.catalogRailKey
import com.kennyb1201.kbstream.ui.home.UpNextBadge
import com.kennyb1201.kbstream.ui.home.UpNextItem
import com.kennyb1201.kbstream.ui.home.UpcomingEpisode
import com.kennyb1201.kbstream.ui.home.formatTimeLeft
import com.kennyb1201.kbstream.ui.home.hiddenDecision
import com.kennyb1201.kbstream.ui.home.hiddenIdentity
import com.kennyb1201.kbstream.ui.home.upNextEpisodeLabel as tvUpNextEpisodeLabel
import com.kennyb1201.kbstream.ui.home.upNextPlayerDisplayName
import com.kennyb1201.kbstream.ui.kb.HomeEntry
import com.kennyb1201.kbstream.ui.kb.KBHomeSlots
import com.kennyb1201.kbstream.ui.kb.KBHomeViewModel
import com.kennyb1201.kbstream.ui.kb.collectionRailKey
import com.kennyb1201.kbstream.ui.kb.collectionRailTiles
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBPlum
import com.kennyb1201.kbstream.ui.theme.KBRust
import com.kennyb1201.kbstream.ui.theme.KBSteel
import com.kennyb1201.kbstream.ui.theme.KBSuccess
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import com.kennyb1201.kbstream.ui.theme.InterFamily
import kotlin.random.Random
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home, for a finger.
 *
 * The TV screen is one focusable rail column with a hero that follows the
 * D-pad; a phone cannot use any of that. This variant keeps the *data* exactly
 * where it is - the same [HomeViewModel] flows, the same hidden-title filter,
 * the same watched keys, the same synced rail-label prefs - and rebuilds only
 * the presentation: a vertical scroll of full-width sections, a hero that is
 * always the first thing on the screen, and horizontal carousels for Continue
 * Watching, Upcoming and each catalog rail.
 *
 * It is a separate file by the port's hard rule: no TV composable is edited in
 * place. `HomeScreen` gets one branch at its entry that hands the same callbacks
 * here when [com.kennyb1201.kbstream.FormFactor.isTv] is false.
 *
 * The home is ARRANGED, not just listed: the entries come from the same
 * `KBHomeSlots.buildMergedEntries` the TV screen uses, so imported KB
 * collections, the Browse-chip rows and the catalog rails appear in the order
 * the home manager stores (and a hidden or unpinned row behaves the same on
 * both screens). Collections render as folder tiles sized by each folder's own
 * `tileShape`; Browse rows render as artwork cards (the category's backdrop and
 * clearlogo, as the TV row draws them) and a chip can be removed with a long
 * press; landscape mode (the synced pref) draws backdrop+clearlogo cards with
 * their rank. Every rail's tile follows Settings' Poster Size.
 *
 * A Continue Watching long press raises the TV card's menu (details, watched,
 * remove from the row, hide) and a tap re-verifies the stored resume root before
 * opening, bounded at five seconds like the TV path. Spoiler-free mode hides
 * episode identities on the cards and in that menu.
 *
 * The TV's top bar has no long-press variants to carry over: it is the content
 * of the Up-gesture overlay, not a menu. Tracked, with the rest of the port, in
 * `docs/mobile_port.md`.
 */
@Composable
fun HomeScreenMobile(
    onItemClick: (MetaPreview) -> Unit,
    onOpenDetailTarget: (MetaPreview, StreamsTarget, String?) -> Unit,
    // The Continue Watching menu's "Play Manually": the shell's own streams
    // route (the one the TV card uses), so a phone pick reaches the picker with
    // the same manual-selection marker and never auto-selects a source.
    onOpenStreams: (MetaPreview, StreamsTarget, String?) -> Unit,
    onOpenCatalogGrid: (Rail) -> Unit,
    onOpenKBFolder: (String) -> Unit = {},
    onOpenBrowseShortcut: (BrowseHomeShortcut) -> Unit = {},
    // Opens the rail manager (Add-ons' dialog, through the one-shot request).
    // Only the all-hidden card uses it: it is the one state where the manager
    // is the only way forward.
    onManageRails: () -> Unit = {},
    // The Upcoming header's press: Library, on the Calendar pane - the same
    // callback the TV rail's header gets (see the shell's wiring). Nullable so
    // a host that cannot navigate leaves the heading a plain caption.
    onOpenCalendar: (() -> Unit)? = null,
    viewModel: HomeViewModel
) {
    MobileTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // The screen's one vertical scroll, hoisted only so the bottom bar's
        // Home item can send it back to the top on a re-tap (see
        // [MobileHomeTopRequest]). Deliberately remembered here rather than at
        // the shell: Home still opens at the top after a trip to Search, which
        // is what this screen did before the bar existed.
        val listState = rememberLazyListState()
        val homeTopRequested by MobileHomeTopRequest.requests
            .collectAsStateWithLifecycle()
        LaunchedEffect(homeTopRequested) {
            // Consumed, so the request that a re-tap raises scrolls this screen
            // once and cannot follow the viewer into a later visit.
            if (MobileHomeTopRequest.consume()) listState.animateScrollToItem(0)
        }

        // The artwork the viewer chose for a title, resolved once for the whole
        // screen and applied per card - the same call the TV rail makes.
        val overridePaths = rememberImageOverridePaths()

        val activeProfileId by ProfileManager.activeProfile
            .collectAsStateWithLifecycle()
        val syncRevision by PrefsPayloadApplier.revision
            .collectAsStateWithLifecycle()
        // The rail subtitle switches are SYNCED prefs: keyed on the profile and
        // the revision, exactly as the TV screen reads them, so a rename or
        // toggle made on another device lands here.
        val showRailType = remember(activeProfileId?.id, syncRevision) {
            AppPreferences.getHomeRailShowCatalogType(context)
        }
        val showRailAddon = remember(activeProfileId?.id, syncRevision) {
            AppPreferences.getHomeRailShowAddonName(context)
        }
        // The rail card shape: the Home-rails switch OR the global "Landscape
        // Posters Everywhere" one, which is what [homeLandscapeActive] means.
        // Reading only the Home switch here is why turning the global setting
        // on reshaped the TV's Home and left the phone's on posters - the
        // ViewModel had already resolved every rail's backdrop + clearlogo, and
        // the cards ignored them.
        val landscapeCards = remember(activeProfileId?.id, syncRevision) {
            AppPreferences.homeLandscapeActive(context)
        }
        // The viewer's Poster Size, which the phone's own rails pinned (120dp
        // portrait, 224dp landscape) until now - so the Settings switch moved
        // the television's tiles and left every phone row alone.
        val railPosterSize = rememberTouchPosterSize()
        val railLandscapeWidth = landscapeTileWidth(railPosterSize.width)
        val railLandscapeHeight = landscapeTileHeight(railPosterSize.width)
        val railsBuiltForProfile by viewModel.railsProfileId.collectAsStateWithLifecycle()
        val railsRaw by viewModel.rails.collectAsStateWithLifecycle()
        val hiddenTitleKeys = rememberHiddenTitleKeys()
        // A rail built for the profile we are leaving must not paint for a
        // frame; hidden titles drop as Home reads its own data, so a Hide from
        // a card takes the title off every row at once (the same rule as TV).
        val rails = remember(
            railsRaw,
            railsBuiltForProfile,
            activeProfileId?.id,
            hiddenTitleKeys
        ) {
            val built = if (railsBuiltForProfile == activeProfileId?.id) railsRaw else emptyList()
            built.mapNotNull { rail ->
                val visible = rail.items.filterNot { meta ->
                    HiddenTitles.hides(
                        hiddenTitleKeys,
                        meta.type,
                        meta.name,
                        meta.yearOrNull,
                        meta.id
                    )
                }
                rail.takeIf { visible.isNotEmpty() }?.copy(items = visible)
            }
        }

        val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
        val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()

        // The add-ons whose catalogs did not answer in the last build (see
        // HomeViewModel.failedAddons and the notice below).
        val failedAddons by viewModel.failedAddons.collectAsStateWithLifecycle()
        // Pinned BingeCat rails wait on a reconnect instead of being unpinned
        // (see rememberBingeCatReconnectNotice).
        val bingecatNeedsReconnect = rememberBingeCatReconnectNotice()

        // Settings' hide-watched switch, applied to the catalog rails exactly as
        // the television applies it (see HomeScreen): finished titles leave the
        // rails, Continue Watching / Up Next / Upcoming keep them.
        val hideWatchedOnRails = remember(activeProfileId?.id, syncRevision) {
            AppPreferences.getHomeRailHideWatched(context)
        }
        val railsShown = remember(rails, watchedKeys, hideWatchedOnRails) {
            if (!hideWatchedOnRails) {
                rails
            } else {
                rails.mapNotNull { rail ->
                    val visible = rail.items.filterNot { meta ->
                        viewModel.watchedKey(meta.id, meta.type) in watchedKeys
                    }
                    rail.takeIf { visible.isNotEmpty() }?.copy(items = visible)
                }
            }
        }

        val upNextRaw by viewModel.upNext.collectAsStateWithLifecycle()
        // The show name of every Continue Watching parent this profile's history
        // holds, for a card that arrived without one (see upNextHiddenIdentity).
        val cwShowNames by
            viewModel.continueWatchingShowNames.collectAsStateWithLifecycle()
        // Continue Watching comes from history and Simkl rather than from the
        // rails, so it is filtered here as well (see HomeScreen) - the same
        // decision, from the same card identity, because this is the screen the
        // hidden-title report came from: the rails dropped the show and this
        // row kept it.
        // The same pass withholds a tracker card whose own episode has not aired
        // yet - see upNextUnairedForCW; TV and phone share the decision, and the
        // date is part of the pass's identity so a card withheld on its release
        // day returns by itself.
        val cwFilterDate = LocalDate.now(ZoneId.systemDefault())
        val cwFilterPass = remember(upNextRaw, hiddenTitleKeys, cwShowNames, cwFilterDate) {
            upNextRaw.map { item ->
                item to upNextContinueWatchingDecision(
                    item,
                    hiddenTitleKeys,
                    cwShowNames,
                    cwFilterDate
                )
            }
        }
        val upNext = remember(cwFilterPass) {
            cwFilterPass
                .filterNot { (_, decision) -> decision.hidden || decision.dropped }
                .map { (item, _) -> item }
        }
        // For COPY DIAGNOSTICS, after composition (spec F2, see CwFilterTrace).
        val cwFilterLines = remember(cwFilterPass) {
            cwFilterPass.map { (_, decision) -> decision.line }
        }
        SideEffect { CwFilterTrace.recordAll(cwFilterLines) }
        val upcomingRaw by viewModel.upcomingSchedule.collectAsStateWithLifecycle()
        // The Upcoming rail's schedule is its own flow - the filter on upNext
        // does not reach it - so a hidden show's next episode used to surface
        // here while the same show was gone from every other row. Same ids as
        // the TV screen's filter: the show's and the episode's.
        val upcoming = remember(upcomingRaw, hiddenTitleKeys) {
            upcomingRaw.filterNot { episode ->
                HiddenTitles.hides(
                    hiddenTitleKeys,
                    episode.parentType,
                    episode.title,
                    null,
                    episode.parentId,
                    episode.id
                )
            }
        }

        // KB collections (imported profiles), the Browse chips mirrored to
        // Home, and the rail arrangement that interleaves them with the
        // catalogs - the same slot builder the TV screen uses, so a rail lands
        // where the home manager says on both screens.
        val kbViewModel: KBHomeViewModel = viewModel()
        val kbState by kbViewModel.state.collectAsStateWithLifecycle()
        // The row the screen actually draws - everything below asks this, not
        // the raw schedule. Upcoming is a SCHEDULE, not a discovery rail, so it
        // is deliberately exempt from "Hide Unreleased Titles": the same
        // toggle used to remove the row on the phone while the television kept
        // it, so one setting produced two different Homes (the TV behaviour is
        // the intended one).
        val upcomingVisible = upcoming.isNotEmpty()
        // The personalized row's picks (see HomeViewModel's Top Picks build).
        // Empty means no header and no rail on the phone either.
        val topPicks by viewModel.topPicks.collectAsStateWithLifecycle()
        // ...and the artwork its landscape cards draw: the row is not a Rail,
        // so this is the only place its backdrop + clearlogo come from (see
        // [HomeViewModel.topPicksArt]).
        val topPicksArt by viewModel.topPicksArt.collectAsStateWithLifecycle()
        val builtinWithContent =
            remember(upNext.isNotEmpty(), upcomingVisible, topPicks.isNotEmpty()) {
                buildSet {
                    if (upNext.isNotEmpty()) add(KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING)
                    if (upcomingVisible) add(KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE)
                    if (topPicks.isNotEmpty()) {
                        add(KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU)
                    }
                }
            }
        // The viewer's own Library lists, fetched by the same loader the
        // television uses - one rule for which lists make a rail (no empty
        // heading, the kids ceiling, an id a card can be opened by), so the two
        // screens cannot disagree about what is pinned. It is read on each
        // composition rather than harnessed to the Library screen's own
        // revision tick: the pins are a prefs row, and a rail must appear (or
        // go) on the next frame after a pin, with no restart.
        //
        // Read BEFORE the merge: a pin is an entry in the ONE Home order now
        // (see KBHomeSlots), so the merge needs its key to place it, and the
        // column below needs its rows to draw it.
        val pinnedLibraryRails by rememberLibraryHomeRails()
        // The marker pass must see these rows as well as the catalog rails: a
        // title that exists only on a pinned list was never preloaded, which is
        // why pinned Trakt / MDBList cards wore no eye or checkmark here while
        // every catalog card did. Same report as the television's - one shared
        // HomeViewModel, and its setter ignores a re-delivery of the same rows,
        // so both screens reporting costs one refresh at most.
        LaunchedEffect(pinnedLibraryRails) {
            viewModel.setLibraryRailItems(
                pinnedLibraryRails.flatMap { rail -> rail.items }
            )
        }
        val homeEntries = remember(railsShown, kbState, builtinWithContent, pinnedLibraryRails) {
            KBHomeSlots.buildMergedEntries(
                context,
                railsShown,
                kbState,
                builtinWithContent,
                libraryPins = pinnedLibraryRails.map { rail -> rail.pin }
            )
        }

        // The two rows this screen draws itself, titled the way the television
        // titles them (see `HomeScreen.builtinRailTitle`): the name the home
        // manager stored for the rail beats the default, and a cleared name
        // falls back to it. Reading the renames from the collected
        // arrangement is what makes a rename arriving by sync re-title the row
        // instead of waiting for Home to be rebuilt - and it is the whole
        // reason these two literals could not stay literals: the manager
        // renames them like any other rail, and the phone kept drawing
        // "Continue Watching" / "Upcoming" regardless.
        val continueWatchingTitle = mobileBuiltinRailTitle(
            renames = kbState.arrangement.renames,
            key = KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING,
            fallback = "Continue Watching"
        )
        val upcomingTitle = mobileBuiltinRailTitle(
            renames = kbState.arrangement.renames,
            key = KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE,
            fallback = "Upcoming"
        )
        val topPicksTitle = mobileBuiltinRailTitle(
            renames = kbState.arrangement.renames,
            key = KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU,
            fallback = "Top Picks for You"
        )

        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val error by viewModel.error.collectAsStateWithLifecycle()

        // ── The billboard (what the hero slot now is) ─────────────────────
        //
        // The phone hero used to be an inert slot: `heroMeta` is fed by the TV
        // screen's focus-following resolver, and a phone has no focus, so
        // nothing ever drove one. A billboard is the driver instead - a pager
        // of up to twenty titles drawn with the SAME hero presentation, fed from
        // the rails below or (by default) from TMDB's trending of the day.
        //
        // Its four prefs are DEVICE-LOCAL, so they are deliberately NOT keyed
        // on the sync revision: that revision means "another device changed
        // something", and a billboard is a statement about this phone's Home
        // only (see PrefsPayloadBuilder's exclusion list, which pins it). They
        // still land without a relaunch because the place that edits them - the
        // Home manager - is a different destination: coming back to Home
        // re-enters this composable and these reads run again.
        val billboardEnabled = remember(activeProfileId?.id) {
            AppPreferences.getHomeBillboardEnabled(context)
        }
        val billboardFeeds = remember(activeProfileId?.id) {
            AppPreferences.getHomeBillboardFeeds(context)
        }
        val billboardCycleMs = remember(activeProfileId?.id) {
            AppPreferences.getHomeBillboardCycleMs(context)
        }
        val billboardTrailerMode = remember(activeProfileId?.id) {
            AppPreferences.getHomeBillboardTrailerMode(context)
        }
        // The delay and the mute are NOT billboard prefs: they are the existing
        // hero-trailer settings, already pickable in Settings, and the
        // billboard's trailer modes reuse them rather than growing a second
        // pair that could disagree with the TV's hero.
        val heroTrailerDelayMs = remember(activeProfileId?.id, syncRevision) {
            AppPreferences.getHeroTrailerDelayMs(context)
        }
        val heroTrailerMuted = remember(activeProfileId?.id, syncRevision) {
            AppPreferences.getHeroTrailerMuted(context)
        }
        // The switch that turns hero trailers off, honoured here as it is on the
        // TV hero and the phone's Detail header.
        val heroTrailerAutoplay = remember(activeProfileId?.id, syncRevision) {
            AppPreferences.getHeroTrailerAutoplay(context)
        }

        // Every rail the billboard may be fed from, keyed the way the Home
        // manager keys a rail - so a chip's key and this lookup cannot
        // disagree, and a chosen rail brings the SAME items, in the same
        // order, that its row on Home shows (already filtered for hidden
        // titles and hide-watched above).
        val billboardFeedSources = remember(railsShown, upNext, upcoming, topPicks) {
            buildMap<String, List<MetaPreview>> {
                // Top Picks is a row of BLEND PICKS, not rail items, so it is
                // converted through the same preview mapper its cards navigate
                // with - the ids stay the `tmdb:<id>` spelling the rest of the
                // app resolves, rather than a second id space for the hero.
                put(
                    KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU,
                    topPicks.map { pick -> mobileBlendPickPreview(pick) }
                )
                put(
                    KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING,
                    upNext.map { item -> item.billboardPreview() }
                )
                put(
                    KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE,
                    upcoming.map { item -> item.detail() }
                )
                railsShown.forEach { rail ->
                    rail.billboardFeedKey()?.let { key -> put(key, rail.items) }
                }
            }
        }

        val billboardTrendingDay by
            viewModel.billboardTrendingDay.collectAsStateWithLifecycle()
        // Keyed on the profile as well as the switch: the default feed is
        // ceiling-filtered for the profile that loads it, and a profile switch
        // empties it (see HomeViewModel's switch block) - so the profile that is
        // now on screen has to ask again, or a kids profile inherits the
        // previous profile's adult trending titles as its hero.
        LaunchedEffect(billboardEnabled, activeProfileId) {
            // Only when the slot is armed: a viewer who turned the billboard
            // off should not pay two TMDB calls for it on every launch.
            if (billboardEnabled) viewModel.loadBillboardTrendingDay()
        }

        val billboardFeed = remember(
            billboardFeedSources,
            billboardFeeds,
            billboardTrendingDay,
            hiddenTitleKeys
        ) {
            val merged = billboardChosenFeed(
                keys = billboardFeeds,
                sources = billboardFeedSources,
                defaultFeed = billboardTrendingDay
            ) ?: billboardTrendingDay
            merged
                .filterNot { item ->
                    HiddenTitles.hides(
                        hiddenTitleKeys,
                        item.type,
                        item.name,
                        item.yearOrNull,
                        item.id
                    )
                }
                .take(BILLBOARD_MAX_PAGES)
        }

        val billboardPages by viewModel.billboardPages.collectAsStateWithLifecycle()
        LaunchedEffect(billboardEnabled, billboardFeed) {
            if (billboardEnabled) {
                viewModel.resolveBillboard(billboardFeed)
            } else {
                viewModel.clearBillboard("billboard disabled")
            }
        }
        // The slot appears on the FIRST settled page and later pages fill in as
        // they land (see BillboardPage); an empty or all-failed feed renders
        // nothing at all, so the rails start at the top exactly as they do
        // today rather than leaving a blank box or an error banner.
        val billboardVisible = billboardEnabled && billboardPages.any { page -> page.settled }

        // The long-press menu the rails raise. Its subject is one rail card and
        // the rail itself (for "Open in Grid").
        var menuTarget by remember { mutableStateOf<RailMenuTarget?>(null) }
        // The Continue Watching long-press menu's subject (see the TV card menu).
        var continueWatchingMenu by remember { mutableStateOf<UpNextItem?>(null) }

        // Home owns the refresh beats the TV screen triggers: the first paint,
        // and every return from a player or detail page.
        LaunchedEffect(Unit) {
            viewModel.onHomeResumed()
            viewModel.refreshWatchedStatusForCurrentRails()
            kbViewModel.load()
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            viewModel.refreshUpNext()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                // Every item declares its content type: without one Compose
                // cannot reuse a slot between items of different kinds (a
                // rail, a status card, the hero), so a reorder after a home
                // manager change threw every composed slot away and rebuilt
                // it. The TV Home and Detail, and the phone's browse screens,
                // already carry them.
                item(key = "topBar", contentType = "topbar") {
                    MobileTopBar()
                }

                if (billboardVisible) {
                    item(key = "billboard", contentType = "hero") {
                        MobileBillboard(
                            pages = billboardPages,
                            cycleMs = billboardCycleMs,
                            trailerMode = billboardTrailerMode,
                            trailerDelayMs = heroTrailerDelayMs,
                            trailerMuted = heroTrailerMuted,
                            trailerAutoplay = heroTrailerAutoplay,
                            // Play asks the Detail page to press its own Play:
                            // the request carries this title's id, and Detail's
                            // existing handler does the resolving (see
                            // BillboardPlaySelection). Nothing here chooses a
                            // stream or an episode.
                            onPlay = { page ->
                                BillboardPlaySelection.request(page.preview.id)
                                onItemClick(page.preview)
                            },
                            onDetails = { page -> onItemClick(page.preview) }
                        )
                    }
                }

                // The arranged home: built-ins, imported collections, the
                // Browse-chip rows and the catalog rails in the order the home
                // manager stores, as the TV screen draws them.
                homeEntries.forEach { entry ->
                    when (entry) {
                        is HomeEntry.BuiltinRail -> when (entry.key) {
                            KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING -> {
                                if (upNext.isNotEmpty()) {
                                    item(key = "continueWatching", contentType = "section") {
                                        MobileSection(title = continueWatchingTitle) {
                                            LazyRow(
                                                contentPadding =
                                                    PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(12.dp)
                                            ) {
                                                items(
                                                    upNext,
                                                    key = { "cw:" + it.id },
                                                    contentType = { "continue-card" }
                                                ) { item ->
                                                    ContinueWatchingCard(
                                                        item = item,
                                                        onClick = {
                                                            openUpNextVerified(
                                                                item = item,
                                                                viewModel = viewModel,
                                                                scope = scope,
                                                                mode = UpNextOpen.RESUME,
                                                                onOpenDetails = onItemClick,
                                                                onOpenDetailTarget =
                                                                    onOpenDetailTarget,
                                                                onOpenStreams = onOpenStreams
                                                            )
                                                        },
                                                        onLongClick = {
                                                            continueWatchingMenu = item
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE -> {
                                if (upcomingVisible) {
                                    item(key = "upcoming", contentType = "section") {
                                        // The heading is the phone's way into the
                                        // whole schedule: one next episode per
                                        // show here, a year of them in
                                        // Library's Calendar pane.
                                        MobileSection(
                                            title = upcomingTitle,
                                            onClick = onOpenCalendar
                                        ) {
                                            LazyRow(
                                                contentPadding =
                                                    PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(12.dp)
                                            ) {
                                                items(
                                                    upcoming,
                                                    key = { "up:" + it.id },
                                                    contentType = { "upcoming-card" }
                                                ) { episode ->
                                                    UpcomingCard(
                                                        episode = episode,
                                                        onClick = {
                                                            onItemClick(episode.detail())
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU -> {
                                if (topPicks.isNotEmpty()) {
                                    item(key = "topPicks", contentType = "section") {
                                        MobileSection(title = topPicksTitle) {
                                            LazyRow(
                                                contentPadding =
                                                    PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(12.dp)
                                            ) {
                                                items(
                                                    topPicks,
                                                    // Deduped where the row is
                                                    // built (see
                                                    // mergeTopPicksSeeds).
                                                    key = { pick ->
                                                        "${pick.type}:${pick.tmdbId}"
                                                    },
                                                    contentType = { "top-picks-card" }
                                                ) { pick ->
                                                    val meta =
                                                        mobileBlendPickPreview(pick)
                                                    val watched =
                                                        viewModel.watchedKey(
                                                            meta.id,
                                                            meta.type
                                                        ) in watchedKeys
                                                    val partiallyWatched = !watched &&
                                                        viewModel.watchedKey(
                                                            meta.id,
                                                            meta.type
                                                        ) in partialWatchedKeys
                                                    // The same card shape the
                                                    // catalog rails draw: this
                                                    // row is a group of titles,
                                                    // not a second card shape -
                                                    // and it follows the same
                                                    // landscape switch they do.
                                                    // It used to draw posters
                                                    // whatever that switch said,
                                                    // with no wordmark once the
                                                    // row was reshaped (field
                                                    // 2026-10-28).
                                                    if (landscapeCards) {
                                                        val art = topPicksArt[
                                                            meta.landscapeArtKey()
                                                        ]
                                                        LandscapeCard(
                                                            backdropUrl =
                                                                imageOverrideCardBackdropUrl(
                                                                    paths = overridePaths,
                                                                    rawId = meta.id,
                                                                    fallbackUrl = art?.first
                                                                        ?: meta.background
                                                                        ?: meta.poster
                                                                ),
                                                            logoUrl = art?.second,
                                                            fallbackTitle = meta.name,
                                                            contentDescription = meta.name,
                                                            isWatched = watched,
                                                            isPartiallyWatched =
                                                                partiallyWatched,
                                                            onClick = {
                                                                armHeroSource(
                                                                    meta.type,
                                                                    meta.id
                                                                )
                                                                onItemClick(meta)
                                                            },
                                                            onLongClick = {
                                                                menuTarget =
                                                                    RailMenuTarget(meta)
                                                            },
                                                            modifier = Modifier
                                                                .heroSourceOnPress(
                                                                    meta.type,
                                                                    meta.id
                                                                )
                                                                .width(railLandscapeWidth)
                                                                .height(railLandscapeHeight)
                                                        )
                                                    } else {
                                                        PosterCard(
                                                            posterUrl =
                                                                imageOverridePosterUrl(
                                                                    paths = overridePaths,
                                                                    rawId = meta.id,
                                                                    fallbackUrl = meta.poster
                                                                ),
                                                            contentDescription = meta.name,
                                                            isWatched = watched,
                                                            isPartiallyWatched =
                                                                partiallyWatched,
                                                            onClick = {
                                                                armHeroSource(
                                                                    meta.type,
                                                                    meta.id
                                                                )
                                                                onItemClick(meta)
                                                            },
                                                            // No owning Rail: this row
                                                            // is not a catalog, so the
                                                            // menu offers its own
                                                            // actions and no "Open in
                                                            // Grid".
                                                            onLongClick = {
                                                                menuTarget =
                                                                    RailMenuTarget(meta)
                                                            },
                                                            modifier = Modifier
                                                                .heroSourceOnPress(
                                                                    meta.type,
                                                                    meta.id
                                                                )
                                                                .width(
                                                                    railPosterSize.width
                                                                )
                                                                .height(
                                                                    railPosterSize.height
                                                                )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Any other built-in key is a row the add-on loader
                            // supplies as a catalog; it arrives as an AddonRail.
                            else -> Unit
                        }

                        // A pinned Library list, in the slot the ONE Home
                        // order gave it - above, between or below the catalogs,
                        // exactly where the TV draws it for the same profile.
                        // The entry carries the PIN; the rows come from the
                        // loader above, and a list that answered nothing keeps
                        // its slot empty rather than drawing a bare heading.
                        is HomeEntry.LibraryRail -> {
                            val pinnedRail = pinnedLibraryRails.firstOrNull { rail ->
                                rail.pin.key == entry.pin.key
                            }
                            if (pinnedRail != null) {
                                item(
                                    key = "librail|" + entry.pin.key,
                                    contentType = "library-list-rail"
                                ) {
                                    MobileLibraryListRail(
                                        rail = pinnedRail,
                                        landscapeCards = landscapeCards,
                                        posterWidth = railPosterSize.width,
                                        posterHeight = railPosterSize.height,
                                        landscapeWidth = railLandscapeWidth,
                                        landscapeHeight = railLandscapeHeight,
                                        overridePaths = overridePaths,
                                        isWatched = { meta ->
                                            viewModel.watchedKey(meta.id, meta.type) in
                                                watchedKeys
                                        },
                                        isPartiallyWatched = { meta ->
                                            viewModel.watchedKey(meta.id, meta.type) in
                                                partialWatchedKeys
                                        },
                                        onOpen = onItemClick
                                    )
                                }
                            }
                        }

                        is HomeEntry.Collection -> item(
                            // id AND title: two imported collections can both
                            // have no id, and a bare title then collides when
                            // an import repeats one - LazyColumn throws on a
                            // duplicate key. The title is always present, so
                            // this is unique where `id ?: title` was not.
                            key = "kb:${entry.collection.id}:${entry.collection.title}",
                            contentType = "section"
                        ) {
                            MobileCollectionSection(
                                collection = entry.collection,
                                onOpenFolder = onOpenKBFolder
                            )
                        }

                        is HomeEntry.BrowseRail -> item(
                            key = "browse:" + entry.key,
                            contentType = "section"
                        ) {
                            MobileBrowseShortcutSection(
                                rail = entry.rail,
                                // The same resolved art the TV row draws: a
                                // backdrop spotlight still plus the brand's
                                // clearlogo, per chip key.
                                artByKey = kbState.browseShortcutArt,
                                onOpen = onOpenBrowseShortcut,
                                onLongPress = kbViewModel::removeBrowseShortcut
                            )
                        }

                        is HomeEntry.AddonRail -> {
                            val rail = entry.rail
                            // The rail's pagination identity, spelled by the
                            // one function the ViewModel keys its own
                            // bookkeeping with ([catalogRailKey]), so the
                            // phone's scroll sentinel below asks for exactly
                            // the rail it is drawing.
                            val railKey = catalogRailKey(
                                rail.addonName,
                                rail.catalogId,
                                rail.type
                            )
                            item(
                                key = "rail:" + rail.addonName + ":" + rail.catalogId +
                                    ":" + rail.type + ":" + entry.sourceIndex,
                                contentType = "section"
                            ) {
                                MobileSection(
                                    title = mobileRailTitle(
                                        // A rename from the home manager (a Top
                                        // Today row, a built catalog) beats
                                        // the rail's own name - the same
                                        // argument the TV rail passes. Without
                                        // it the manager's name reached both
                                        // platforms' builders and the TV's
                                        // Home, and not this one.
                                        catalogName = entry.titleOverride
                                            ?: rail.catalogName,
                                        addonName = rail.addonName,
                                        type = rail.type,
                                        showType = showRailType,
                                        showAddon = showRailAddon
                                    )
                                ) {
                                    // The row's own scroll state, so the
                                    // sentinel below can watch it.
                                    val railRowState = rememberLazyListState()

                                    // Infinite scroll, the television's own
                                    // rule: an add-on catalog is asked for
                                    // `skip=0` when the rail is built and for
                                    // the next page whenever the viewer comes
                                    // near the end of the row (see
                                    // HomeScreen's InfiniteRailPageHandler,
                                    // which is the same call into the same
                                    // ViewModel). The phone row had no trigger
                                    // at all, so it stopped dead at whatever
                                    // the add-on returned for its first page -
                                    // a 1000-title catalog was unreachable past
                                    // the first batch on a phone while the TV
                                    // walked the whole thing. Runtime-held and
                                    // self-guarded in the ViewModel
                                    // (loadingRails/exhaustedRails), so a fast
                                    // thumb asking twice is one fetch.
                                    InfiniteScrollEffect(
                                        listState = railRowState,
                                        itemCount = rail.items.size,
                                        onLoadMore = {
                                            viewModel.loadMoreForRail(railKey)
                                        }
                                    )

                                    LazyRow(
                                        state = railRowState,
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        itemsIndexed(
                                            items = rail.items,
                                            key = { _, meta ->
                                                "${meta.type}:${meta.id}:${rail.catalogId}"
                                            },
                                            contentType = { _, _ -> "rail-card" }
                                        ) { index, meta ->
                                            val watched = viewModel.watchedKey(
                                                meta.id,
                                                meta.type
                                            ) in watchedKeys
                                            val partiallyWatched = !watched &&
                                                viewModel.watchedKey(
                                                    meta.id,
                                                    meta.type
                                                ) in partialWatchedKeys
                                            if (landscapeCards) {
                                                // Landscape rows carry their own
                                                // resolved backdrop + clearlogo
                                                // (see Rail.landscapeArt), keyed
                                                // by `landscapeArtKey()` - the
                                                // map is "type:id", so a raw id
                                                // lookup found nothing and every
                                                // phone card drew without its
                                                // clearlogo.
                                                val art = rail.landscapeArt[
                                                    meta.landscapeArtKey()
                                                ]
                                                LandscapeCard(
                                                    // The viewer's chosen still wins
                                                    // over the rail's own art - the
                                                    // same chain the TV rail uses,
                                                    // including over a pinned
                                                    // "Top ... Today" row's blank
                                                    // marker (see LandscapeArt).
                                                    // Without it, "Set as landscape
                                                    // card" did nothing on a phone.
                                                    backdropUrl = imageOverrideCardBackdropUrl(
                                                        paths = overridePaths,
                                                        rawId = meta.id,
                                                        fallbackUrl = art?.first
                                                            ?: meta.background
                                                            ?: meta.poster
                                                    ),
                                                    logoUrl = art?.second,
                                                    fallbackTitle = meta.name,
                                                    contentDescription = meta.name,
                                                    isWatched = watched,
                                                    isPartiallyWatched =
                                                        partiallyWatched,
                                                    // A pinned "Top ... Today" row
                                                    // numbers its cards; no other
                                                    // rail has a standing to show.
                                                    // The same rule the TV rail
                                                    // reads (see landscapeRank).
                                                    rank = landscapeRank(
                                                        rail.ranked,
                                                        index
                                                    ),
                                                    onClick = {
                                                        // The phone's hero flight:
                                                        // a press IS the source
                                                        // signal (no D-pad focus
                                                        // to arm it with).
                                                        armHeroSource(meta.type, meta.id)
                                                        onItemClick(meta)
                                                    },
                                                    onLongClick = {
                                                        menuTarget =
                                                            RailMenuTarget(meta, rail)
                                                    },
                                                    modifier = Modifier
                                                        .heroSourceOnPress(meta.type, meta.id)
                                                        .width(railLandscapeWidth)
                                                        .height(railLandscapeHeight)
                                                )
                                            } else {
                                                PosterCard(
                                                    posterUrl = imageOverridePosterUrl(
                                                        paths = overridePaths,
                                                        rawId = meta.id,
                                                        fallbackUrl = meta.poster
                                                    ),
                                                    contentDescription = meta.name,
                                                    isWatched = watched,
                                                    isPartiallyWatched =
                                                        partiallyWatched,
                                                    onClick = {
                                                        armHeroSource(meta.type, meta.id)
                                                        onItemClick(meta)
                                                    },
                                                    onLongClick = {
                                                        menuTarget =
                                                            RailMenuTarget(meta, rail)
                                                    },
                                                    modifier = Modifier
                                                        .heroSourceOnPress(meta.type, meta.id)
                                                        .width(railPosterSize.width)
                                                        .height(railPosterSize.height)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Nothing is appended after the arranged rows any more: the
                // viewer's pinned Library lists are entries IN that order now
                // (see KBHomeSlots), so drawing them here as well would draw
                // every one of them twice.

                // ...and the add-ons whose catalogs did not answer, named. A
                // failed catalog produces no rail, so the only symptom used to
                // be a Home a few rows shorter than expected, with the failure
                // itself in logcat; the television shows this line and the
                // phone had no equivalent.
                if (failedAddons.isNotEmpty()) {
                    item(key = "failed_addons", contentType = "status") {
                        Text(
                            text = failedAddonsMessage(failedAddons),
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                        )
                    }
                }

                // ...and the pinned BingeCat rails that cannot load until the
                // session is reconnected. The pins are kept, so they reappear
                // without the viewer pinning anything again.
                if (bingecatNeedsReconnect) {
                    item(key = "bingecat_reconnect", contentType = "status") {
                        Text(
                            text = "BingeCat needs reconnecting: your pinned BingeCat rails " +
                                "are kept but cannot load until you sign in again " +
                                "(Settings > BingeCat).",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                        )
                    }
                }

                // Every rail the loader produced has been hidden by the viewer
                // (HD-P2-5). The merged list is empty while the pieces it is
                // built from are not, which separates this from a first run
                // with no catalogs at all - there, there is nothing to manage
                // yet. Without this card Home was the hero and nothing else.
                val allRailsHidden = !isLoading && homeEntries.isEmpty() &&
                    (railsShown.isNotEmpty() || upNext.isNotEmpty() || upcomingVisible)
                if (allRailsHidden) {
                    item(key = "all-hidden", contentType = "status") {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .clip(KBShapeCard)
                                .background(KBSurface)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "All rails are hidden.",
                                color = KBTextHi,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Bring one back in Home Manager.",
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            KBButton(label = "MANAGE RAILS", onClick = onManageRails)
                        }
                    }
                }

                if (isLoading && railsShown.isEmpty() && upNext.isEmpty() && !upcomingVisible) {
                    // Shaped placeholders, not a spinner (improvement F1): the
                    // phone reserved 180dp for a circle and then shoved the whole
                    // page down when the real hero and rails arrived. The TV
                    // skeleton is reused as-is - pocket-sized through the touch
                    // poster geometry and the phone's 16dp gutter, so the two
                    // surfaces cannot drift into different placeholder shapes.
                    item(key = "loading", contentType = "skeleton") {
                        val posterSize = rememberTouchPosterSize()
                        val tileWidth = rememberTouchTileWidth(posterSize)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .background(KBSurfaceRaised, KBShapeCard)
                            )
                            KBSkeletonRail(
                                posterWidth = tileWidth,
                                posterHeight = posterSize.height,
                                horizontalPadding = 16.dp
                            )
                            KBSkeletonRail(
                                posterWidth = tileWidth,
                                posterHeight = posterSize.height,
                                horizontalPadding = 16.dp
                            )
                        }
                    }
                }

                error?.let { message ->
                    item(key = "error", contentType = "status") {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .clip(KBShapeCard)
                                .background(KBSurface)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = message,
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            // m1: this card can be on screen WITH rails (a
                            // partial failure), and onHomeResumed() only rebuilds
                            // when the rails are empty or stale - so the press
                            // rebuilt nothing and the error stayed. The force
                            // refresh is what the TV page's rails-empty retry
                            // uses, and it is the only thing that answers a
                            // failure beside rails that are already up.
                            KBButton(label = "RETRY") {
                                viewModel.refreshRailsOnly()
                            }
                        }
                    }
                }
            }

            // Long-press menu on a Continue Watching card: the TV card's own
            // three rows, minus the library actions (those belong to the SHOW,
            // and live on its rail card and detail page).
            continueWatchingMenu?.let { item ->
                val mediaType = item.parentType ?: if (item.season != null) "series" else "movie"
                val meta = MetaPreview(
                    id = item.parentId ?: item.id,
                    type = mediaType,
                    name = item.showTitle ?: item.title,
                    poster = item.poster,
                    background = item.backdrop,
                    logo = item.clearLogo
                )
                val isWatched = viewModel.watchedKey(meta.id, mediaType) in watchedKeys
                val hideIdentity = item.hiddenIdentity(cwShowNames)
                val hidesTitle = rememberHidesEpisodeTitle(
                    inProgress = item.startPositionMs > 0
                )
                PosterContextMenu(
                    title = item.showTitle ?: item.title,
                    subtitle = if (hidesTitle) null else upNextEpisodeLabel(item),
                    // The TV card's own three playback rows, in the same order
                    // and through the same targets. The details row used to be
                    // the ONLY row here, and it opened the episode's play
                    // target - so a long press on "Go to Details" resumed the
                    // file instead of showing the page, and the menu's derived
                    // "Play Manually" / "Play from Beginning" rows (which run
                    // the details row's action) resumed it too. Each row now
                    // names what it does; see [UpNextOpen].
                    actions = listOf(
                        PosterContextAction(
                            label = "Go to Details",
                            description = "Open this title's detail page"
                        ) {
                            continueWatchingMenu = null
                            openUpNextVerified(
                                item = item,
                                viewModel = viewModel,
                                scope = scope,
                                mode = UpNextOpen.DETAILS,
                                onOpenDetails = onItemClick,
                                onOpenDetailTarget = onOpenDetailTarget,
                                onOpenStreams = onOpenStreams
                            )
                        },
                        PosterContextAction(
                            label = "Play Manually",
                            description = if (item.startPositionMs > 0L) {
                                "Open the streams picker - still resumes at your progress"
                            } else {
                                "Open the streams picker"
                            }
                        ) {
                            continueWatchingMenu = null
                            openUpNextVerified(
                                item = item,
                                viewModel = viewModel,
                                scope = scope,
                                mode = UpNextOpen.STREAMS,
                                onOpenDetails = onItemClick,
                                onOpenDetailTarget = onOpenDetailTarget,
                                onOpenStreams = onOpenStreams
                            )
                        },
                        PosterContextAction(
                            label = "Play from Beginning",
                            description = "Start this episode over from the beginning"
                        ) {
                            continueWatchingMenu = null
                            openUpNextVerified(
                                item = item,
                                viewModel = viewModel,
                                scope = scope,
                                mode = UpNextOpen.FROM_BEGINNING,
                                onOpenDetails = onItemClick,
                                onOpenDetailTarget = onOpenDetailTarget,
                                onOpenStreams = onOpenStreams
                            )
                        },
                        PosterContextAction(
                            label = watchedMenuLabel(isWatched, mediaType),
                            description = watchedMenuDescription(isWatched, mediaType)
                        ) {
                            continueWatchingMenu = null
                            if (isWatched) viewModel.markUnwatched(meta)
                            else viewModel.markAsWatched(meta)
                        },
                        PosterContextAction(
                            label = "Remove from Continue Watching",
                            description = "Keeps this show out of the row until you watch it " +
                                "again.",
                            isDestructive = true
                        ) {
                            continueWatchingMenu = null
                            viewModel.removeFromContinueWatching(item)
                        }
                    ),
                    // The filter's own identity, both ways: the keys this hide
                    // stores are the keys the rail matches on the next frame
                    // (see upNextHiddenIdentity).
                    hideTarget = hideTarget(
                        hideIdentity.title ?: item.title,
                        hideIdentity.mediaType,
                        item.poster,
                        // Minus this device's history row id: the hidden set is
                        // synced, and a row id means nothing on a sibling.
                        hideIdentity.ids.filter { it != item.historyRowId }
                    ),
                    onDismiss = { continueWatchingMenu = null }
                )
            }

            // Raised from a long press on a rail card, below the sheet's scrim.
            menuTarget?.let { target ->
                val isWatched =
                    viewModel.watchedKey(target.meta.id, target.meta.type) in watchedKeys
                val ids = LibraryIds.split(target.meta.id)
                PosterContextMenu(
                    title = target.meta.name,
                    subtitle = target.meta.releaseInfo,
                    // "Open in Grid" exists only when there is a catalog to open:
                    // a Top Picks card has none (see [RailMenuTarget.rail]).
                    actions = buildList {
                        target.rail?.let { rail ->
                            add(
                                PosterContextAction(
                                    label = "Open in Grid",
                                    description = "Browse this whole catalog as a poster grid"
                                ) {
                                    menuTarget = null
                                    onOpenCatalogGrid(rail)
                                }
                            )
                        }
                        add(
                            PosterContextAction(
                                label = "Go to Details",
                                description = "Open this title's detail page"
                            ) {
                                menuTarget = null
                                onItemClick(target.meta)
                            }
                        )
                        add(
                            PosterContextAction(
                                label = watchedMenuLabel(isWatched, target.meta.type),
                                description = watchedMenuDescription(isWatched, target.meta.type)
                            ) {
                                menuTarget = null
                                if (isWatched) {
                                    viewModel.markUnwatched(target.meta)
                                } else {
                                    viewModel.markAsWatched(target.meta)
                                }
                            }
                        )
                    },
                    hideTarget = hideTarget(
                        target.meta.name,
                        target.meta.type,
                        target.meta.poster,
                        listOf(
                            target.meta.id,
                            ids.imdbId,
                            ids.tmdbId?.toString()
                        ),
                        year = target.meta.yearOrNull
                    ),
                    libraryTarget = LibraryAddTarget(
                        mediaType = target.meta.type,
                        imdbId = ids.imdbId,
                        tmdbId = ids.tmdbId,
                        title = target.meta.name,
                        year = target.meta.yearOrNull,
                        posterUrl = target.meta.poster
                    ),
                    onDismiss = { menuTarget = null }
                )
            }
        }
    }
}

/** The verification budget for a resume root (the TV screen's own). */
private const val CW_ROOT_VERIFY_TIMEOUT_MS = 5_000L

/** Fire TV suppresses debug logs, so the billboard's trailer ladder is warnings. */
private const val TAG = "BILLBOARD_TRAILER"

/**
 * The retry a billboard death asked for, held until the resolve that answers it
 * COMPLETES: a run that is cancelled leaves the record in place instead of
 * losing the request, and the record carries the exclusions and start position
 * that belong to it.
 */
private data class BillboardTrailerRetry(
    val resumeAtMs: Long,
    val excludedClients: Set<String>,
    val attempt: Int,
    val change: String,
    val deadClient: String?,
    val deathPositionMs: Long,
    val durationMs: Long,
    /**
     * The DYING mount's own facts, captured when the death line was first
     * written. They ride along so the resolve that answers this request closes
     * the same line with them: the replacement's shape describes the fix, and
     * the chunk range (the reason a chunk read is suspected at all) only exists
     * on the death side.
     */
    val deadKind: String?,
    val deadItag: String?,
    val deadChunkOffsetBytes: Long?,
    val deadChunkTotalBytes: Long?
)

/**
 * An imported KB collection: the same folder tiles the TV rail draws, sized by
 * each folder's own `tileShape`.
 */
@Composable
private fun MobileCollectionSection(
    collection: KBCollectionProfile,
    onOpenFolder: (String) -> Unit
) {
    val tiles = remember(collection) { collectionRailTiles(collection.folders) }
    if (tiles.isEmpty()) return

    MobileSection(title = collection.title.ifBlank { "Collection" }) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = tiles,
                key = { index, tile -> collectionRailKey(index, tile) },
                contentType = { _, _ -> "collection-tile" }
            ) { _, tile ->
                CollectionFolderCard(
                    folder = tile.second,
                    onClick = { onOpenFolder(tile.first) }
                )
            }
        }
    }
}

@Composable
private fun CollectionFolderCard(folder: KBFolder, onClick: () -> Unit) {
    val width: Dp
    val height: Dp
    when (folder.tileShape?.uppercase()) {
        "POSTER" -> {
            width = 112.dp
            height = 168.dp
        }
        "SQUARE" -> {
            width = 120.dp
            height = 120.dp
        }
        else -> {
            width = 200.dp
            height = 112.dp
        }
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(KBShapeCard)
            .clickable(onClick = onClick)
            .background(KBSurface)
    ) {
        val cover = folder.coverImageUrl?.takeIf { it.isNotBlank() }
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = folder.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            folder.coverEmoji?.takeIf { it.isNotBlank() }?.let { emoji ->
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        // Title scrim + label, dropped together when the folder sets hideTitle
        // in the manifest - the same rule the TV tile draws by, so a collection
        // whose folders hide titles looks the same on both surfaces (and does
        // not paint a scrim over artwork it was told to leave bare).
        if (!folder.hideTitle) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, KBVoid.copy(alpha = 0.85f))
                        )
                    )
            )
            Text(
                text = folder.title,
                color = KBTextHi,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * The Browse chips the viewer mirrored to Home, as one card row per kind.
 *
 * A phone used to get text pills here while the television drew artwork tiles,
 * which read as a different feature: the pill named the door, the TV card showed
 * what was behind it. These are now cards (see [BrowseShortcutCard]) fed by the
 * same resolved art the TV row reads. A long press removes the chip from Home -
 * the TV's tile menu action, and the same pref blob either way.
 */
@Composable
private fun MobileBrowseShortcutSection(
    rail: BrowseHomeRail,
    artByKey: Map<String, BrowseShortcutArt>,
    onOpen: (BrowseHomeShortcut) -> Unit,
    onLongPress: (BrowseHomeShortcut) -> Unit
) {
    if (rail.shortcuts.isEmpty()) return

    val posterSize = rememberTouchPosterSize()
    val cardWidth = landscapeTileWidth(posterSize.width)
    val cardHeight = landscapeTileHeight(posterSize.width)

    MobileSection(title = rail.title.ifBlank { "Browse" }) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                rail.shortcuts,
                key = { it.categoryKey + ":" + it.id },
                contentType = { "shortcut" }
            ) { shortcut ->
                BrowseShortcutCard(
                    shortcut = shortcut,
                    art = artByKey[shortcut.chipKey()],
                    width = cardWidth,
                    height = cardHeight,
                    onClick = { onOpen(shortcut) },
                    onLongClick = { onLongPress(shortcut) }
                )
            }
        }
    }
}

/**
 * One Browse chip, as the television draws it: a landscape card wearing the
 * category's own artwork rather than a text pill.
 *
 * The phone had a pill here - the chip's name on an accent wash - which said
 * what the door was but nothing about what was behind it, on a screen where
 * every other row is artwork. This is the touch twin of the TV's
 * `BrowseShortcutTile`: the same resolved backdrop as a dimmed base (a spotlight
 * still for a genre/decade/studio, a collection's own art), the same legibility
 * scrim, and then the brand's clearlogo - walked across its candidate list so a
 * mark that cannot be drawn falls through to the next, exactly as the TV tile
 * does - with the chip's own name standing in when the category owns no mark
 * (a genre, a tag, a decade) or every candidate turned out unusable.
 */
@Composable
private fun BrowseShortcutCard(
    shortcut: BrowseHomeShortcut,
    art: BrowseShortcutArt?,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val logoUrls = art?.clearlogoUrls.orEmpty()
        .ifEmpty { listOfNotNull(art?.clearlogoUrl?.takeIf { it.isNotBlank() }) }
    // The index walks the candidates: a blank plate or artwork that never
    // arrives advances it, so a brand whose top mark is undrawable still shows
    // one of its own instead of an empty card.
    var logoIndex by remember(logoUrls) { mutableStateOf(0) }
    val logo = logoUrls.getOrNull(logoIndex)
    val backgroundUrl = art?.backdropUrl?.takeIf { it.isNotBlank() }

    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = posterEdgeShape()
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .background(KBSurface)
                // A soft accent wash under everything, so a chip with no
                // resolved artwork is still a warm card rather than a flat
                // grey rectangle (the TV tile's own base).
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            KBAccent.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
                .then(posterBorderModifier()),
            contentAlignment = Alignment.Center
        ) {
            if (backgroundUrl != null) {
                AsyncImage(
                    model = backgroundUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        // Texture, not a thumbnail: a full-strength still under a
                        // wordmark reads as a mismatched poster.
                        .graphicsLayer { alpha = 0.42f }
                )
            }

            // Legibility scrim: the mark or the name sits on it, so a bright
            // backdrop cannot swallow either.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                KBVoid.copy(alpha = 0.30f),
                                KBVoid.copy(alpha = 0.90f)
                            )
                        )
                    )
            )

            if (logo == null) {
                Text(
                    text = shortcut.name.uppercase(),
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                )
            } else {
                BrandMarkImage(
                    url = logo,
                    contentDescription = shortcut.name,
                    onUnusable = { logoIndex += 1 },
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(height * 0.42f)
                )
            }
        }
    }
}

/** One rail card and the rail it belongs to, for the long-press menu. */
private data class RailMenuTarget(
    val meta: MetaPreview,
    /**
     * The catalog this card came from, when it came from one.
     *
     * Null for a card on a row the app built itself (Home's "Top Picks for
     * You"), which has no catalog behind it to browse - the menu drops "Open in
     * Grid" there rather than offering a grid that could not load.
     */
    val rail: Rail? = null
)

/**
 * One blended pick as the card model the phone navigates with. `tmdb:<id>` is
 * the id spelling every app-built TMDB row uses, so the detail route, the
 * watch badges and the hero resolve a Top Picks card like any other.
 */
private fun mobileBlendPickPreview(pick: BlendPick): MetaPreview = MetaPreview(
    id = "tmdb:${pick.tmdbId}",
    type = pick.type,
    name = pick.name,
    poster = pick.posterPath?.let { TmdbRepository.POSTER_BASE + it },
    background = pick.backdropPath?.let { TmdbRepository.BACKDROP_BASE + it },
    description = pick.overview,
    tmdbRating = pick.voteAverage
)

/**
 * The phone top bar: the wordmark, and nothing else.
 *
 * It has given up both of its old jobs. The four destinations it led to
 * (search, live TV, library, settings) are the bottom bar's items, hosted by
 * the shell so they are one tap away from every top-level screen; the profile
 * chip followed them for the same reason - the switcher is reachable from all
 * five screens there, and from nowhere on the other four while it lived here
 * (see [MobileBottomNav]). One switcher, in one place.
 */
@Composable
private fun MobileTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "KBSTREAM",
            color = KBTextHi,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * One billboard page: the backdrop, the clearlogo (with the shared dark-surface
 * treatment) or the title, and the short meta line. Tapping it opens the title.
 *
 * One billboard page draws exactly this - the billboard is a pager of these,
 * not a second hero design - and a page whose trailer is playing hands the
 * shared inline player in through [trailerPlayback]. The player sits OVER the
 * still, and its shutter is transparent, so until its first frame lands the
 * backdrop above is what the viewer sees (the same hand-off the phone's Detail
 * header makes). The owner still owns the stops: [onTrailerEnded] and
 * [onStopTrailer] are the caller's callbacks, exactly as `HomeHero`'s are, and
 * its two death shapes - an ordinary failure and a refused identity - are
 * [onTrailerFailed] and [onTrailerIdentityBlocked], the same pair the Detail
 * header takes.
 */
@Composable
private fun MobileHero(
    meta: Meta,
    backdropUrl: String?,
    logoUrl: String?,
    /**
     * The page's Ken Burns sweep (spec 2.6), or null for a still backdrop - a
     * page that is not current, and every page when system animations are off.
     */
    backdropSweep: BillboardSweep? = null,
    /**
     * 0..1 through [backdropSweep], read INSIDE the graphics layer: a read in
     * composition would recompose the page every frame of the sweep.
     */
    backdropProgress: () -> Float = { 1f },
    /** 0..1 entrance of the content block, read inside its own layer. */
    contentReveal: () -> Float = { 1f },
    /** The trailer playing over this page, or null while the still shows. */
    trailerPlayback: HeroTrailerPlayback? = null,
    trailerMuted: Boolean = true,
    /** The trailer's video id, for the watchdog line and the chunk-death record. */
    trailerVideoId: String? = null,
    onTrailerEnded: () -> Unit = {},
    onStopTrailer: () -> Unit = {},
    /**
     * The media's own clock, reported on state changes only. The death plan
     * needs the clip's length: an identical replay's skip converts its chunk
     * into media time with it, and the death line cannot say "died at 20 s of
     * 140" without it.
     */
    onTrailerProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    /** Playback died mid-stream at [positionMs]: the owner plans the retry. */
    onTrailerFailed: (positionMs: Long) -> Unit = { onStopTrailer() },
    /**
     * YouTube refused the client identity. The owner's ladder decides, and its
     * answer is whether a replacement was requested - exactly as the shared
     * player asks `HomeHero`.
     */
    onTrailerIdentityBlocked: (positionMs: Long, durationMs: Long) -> Boolean =
        { _, _ -> false },
    /**
     * The page's Play / Details (and Manual mode's Trailer) pills, drawn INSIDE
     * the image under the meta line - which is the premium refresh's whole point
     * (spec 2.7): a control sitting on the art reads as part of the hero, while
     * the same control in a row under a banner reads as a toolbar bolted to one.
     * Null draws none, which is what a page that is not current passes.
     */
    actions: (@Composable () -> Unit)? = null,
    /**
     * A fixed frame height, for a screen that cannot size the hero by width.
     *
     * Null is the phone's own rule below (16:10 off the width). A TV Home is
     * wider than it is tall by a lot, so the same width math asks for a frame
     * taller than the viewport - see MobileBillboard's cap.
     */
    heroHeight: Dp? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    val logo = logoUrl?.takeIf { it.isNotBlank() }

    // The chrome fade (commissioned 2026-10-30 19:59): while a trailer has this
    // page, the copy that names the title and the pills that act on it step
    // aside and let the video be the hero. Keyed on PLAYBACK, not on the
    // trailer mode, so Auto and Manual behave the same - and because ended,
    // failed and stopped all null the playback the owner passes in, that one
    // condition is also the restore path for all three.
    //
    // Not drawn here: the close mark above and the segments below stay at full
    // alpha. They are the way out of the trailer and the page's position, not
    // decoration, and a fading exit is the one thing this must not hide.
    //
    // The entrance's own 450 ms, so the two read as one motion language; read
    // INSIDE the column's layer below, so a frame of it never recomposes the
    // block it is hiding.
    val chrome by animateFloatAsState(
        targetValue = if (trailerPlayback != null) 0f else 1f,
        animationSpec = tween(
            durationMillis = BILLBOARD_CONTENT_REVEAL_MS,
            easing = FastOutSlowInEasing
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (heroHeight != null) {
                    // The TV's frame: the height the Home handed down, full width,
                    // with the backdrop still ContentScale.Crop - so the art fills
                    // the frame and the 16:10 crop room becomes the sides'
                    // overflow instead of the bottom of the screen.
                    Modifier.height(heroHeight)
                } else {
                    // 16:10, not 16:9 (spec 2.8, chosen 2026-10-30): the billboard
                    // is the first thing on Home and at 16:9 inside the premium
                    // refresh's 16dp margins it read small. On a 390dp phone the
                    // frame is 358dp wide, so 16:10 is ~224dp tall - ~23dp more
                    // than 16:9 in the same margins, and a touch more than the old
                    // full-bleed 16:9 (~219dp), which is the point. A 16:9 backdrop
                    // under ContentScale.Crop here overflows on the SIDES (~11% of
                    // width), which is the room the Ken Burns pan sweeps.
                    Modifier.aspectRatio(16f / 10f)
                }
            )
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            // The hero is one of the few surfaces that keeps the crossfade
            // handover (see the TV hero's own note): it is a single image the
            // viewer is looking at, not a tile in a scrolling rail.
            model = remember(backdropUrl, meta) {
                ImageRequest.Builder(context)
                    .data(backdropUrl ?: meta.background ?: meta.poster)
                    .crossfade(true)
                    .build()
            },
            contentDescription = meta.name,
            contentScale = ContentScale.Crop,
            // The sweep is a graphics layer on the IMAGE only: a scale and a
            // translation are not layout, so the pager never re-measures and
            // the scrim, the clearlogo and the copy above it stay put.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val sweep = backdropSweep ?: return@graphicsLayer
                    val fraction = backdropProgress().coerceIn(0f, 1f)
                    val scale = sweep.startScale +
                        (sweep.endScale - sweep.startScale) * fraction
                    scaleX = scale
                    scaleY = scale
                    // A fraction of the image's own width, so the pan is the
                    // same move on a 360dp phone and a tablet.
                    translationX = (
                        sweep.startPanFraction +
                            (sweep.endPanFraction - sweep.startPanFraction) * fraction
                        ) * size.width
                }
        )
        // The shared hero-trailer stack, over the still. A trailer that fails to
        // resolve never reaches here at all (the owner keeps the backdrop and
        // shows no error UI): a failure DURING playback is what calls back, and
        // the owner answers it the same way, by dropping to the still.
        if (trailerPlayback != null) {
            HeroInlineTrailerPlayer(
                source = trailerPlayback.source,
                muted = trailerMuted,
                startPositionMs = trailerPlayback.startPositionMs,
                // Joins this mount's watchdog line to the death line the owner
                // writes for the same key.
                videoId = trailerVideoId,
                onProgress = onTrailerProgress,
                onEnded = onTrailerEnded,
                onFailed = onTrailerFailed,
                onIdentityBlocked = onTrailerIdentityBlocked,
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
                            // Lighter than the pre-refresh stops (0.45 / 0.94):
                            // the pills and the segments now live ON this scrim,
                            // and the heavier one was darkening the art to buy
                            // legibility for copy that has since left the page.
                            //
                            // Lightened again for the dim billboard trailer:
                            // the middle drops to 0.18 and the bottom stop to
                            // 0.65. The bottom does NOT go transparent and does
                            // NOT go light -- that edge is what melts the
                            // billboard into the rails below, so it stays a
                            // dark ramp, just a less black one. The pills and
                            // segments carry their own backgrounds on top of
                            // it, so their contrast is not this stop's job.
                            KBVoid.copy(alpha = 0.18f),
                            KBVoid.copy(alpha = 0.65f)
                        )
                    )
                )
        )
        // A playing trailer needs a way out that is not "press Trailer again":
        // the page is a video surface, so a close mark on it is the affordance
        // a thumb looks for (the same one the phone's Detail header draws).
        if (trailerPlayback != null) {
            IconButton(
                onClick = onStopTrailer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Stop trailer",
                    tint = KBTextHi
                )
            }
        }
        Column(
            // The content entrance (spec 2.6): the block fades in and rises on
            // page settle, once per page entry. A layer rather than a
            // recomposed offset, so the rise costs nothing per frame.
            modifier = Modifier
                .align(Alignment.BottomStart)
                // 40dp at the bottom clears the segments overlay drawn below (a
                // 24dp touch row + 10dp of overlay inset + 6dp of gap), so the
                // pills and the bar can never sit on each other.
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp)
                .graphicsLayer {
                    val reveal = contentReveal().coerceIn(0f, 1f)
                    // One layer carries both: the reveal says how far into the
                    // page this is, the chrome fade says whether the page's
                    // copy belongs on screen at all. Multiplied rather than
                    // sequenced, so a trailer that starts mid-entrance does
                    // not snap either half.
                    alpha = reveal * chrome
                    translationY = (1f - reveal) * BILLBOARD_CONTENT_RISE_DP.dp.toPx()
                },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (logo != null && !logoFailed) {
                BrandMarkImage(
                    url = logo,
                    contentDescription = meta.name,
                    onUnusable = { logoFailed = true },
                    modifier = Modifier
                        .height(40.dp)
                        .widthIn(max = 220.dp)
                )
            } else {
                Text(
                    text = meta.name,
                    color = KBTextHi,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = heroMetaLine(meta),
                // Near-white rather than the secondary gray: with the plot block
                // gone this line is the only copy left on the art, so it carries
                // the hero's identity instead of annotating it.
                color = KBTextHi.copy(alpha = 0.90f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The two-line description is GONE (spec 2.7): Details is one tap
            // away, and a gray block of plot above the pills was the densest,
            // most dated thing on the page.
            actions?.let { pills ->
                Row(
                    // 8dp under the meta line: the column's own 4dp gap plus 4.
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pills()
                }
            }
        }
    }
}

/**
 * The phone Home billboard: the hero slot as a pager of up to twenty titles.
 *
 * "Mobile billboard" = the film-strip hero every streaming app opens on. It is
 * the SAME [MobileHero] presentation per page (backdrop, clearlogo or title,
 * meta line, tap to open), framed as a rounded card on the page's own
 * background, with a Play and a Details pill ON the art and a segmented
 * progress bar along its bottom edge - and it advances on its own unless the
 * viewer is touching it, the app is in the background, or a trailer is holding
 * the page.
 *
 * No chevrons and no "N of M" line (spec 2.7): on a phone the chevrons sat on
 * the artwork and collided with the clearlogo, and the two ways a thumb actually
 * navigates - the swipe and the segments - were always enough. The count moved
 * to the segments row's semantics, which is where a screen reader needs it and
 * a sighted viewer does not (the fill says where the pager is).
 *
 * Every motion rule is a state the effect KEYS on, not a flag checked inside a
 * long-lived timer: a swipe, a segment press and an auto-advance all end in a
 * new `currentPage`, which cancels the pending hold and re-arms it from the
 * page that just became current. That is also why the timer needs no manual
 * "restart" anywhere.
 *
 * The trailer is the app's one hero-trailer stack (see [HeroInlineTrailerPlayer]
 * and TrailerPlayerLauncher), honouring the existing hero-trailer delay, mute
 * AND autoplay prefs; this composable only decides WHEN a page may play one (its
 * trailer mode). Off by default, because a page that swaps itself every few
 * seconds is a bad place to start video nobody asked for.
 *
 * [trailerAutoplay] is the Settings switch (`hero_trailer_autoplay`), which the
 * TV hero and the phone's Detail header both honour: a viewer who turned hero
 * trailers off still got them here, in AUTO mode, because this surface read only
 * the delay and the mute. Manual mode's Trailer pill is unaffected - that press
 * is the ask.
 */
@Composable
internal fun MobileBillboard(
    pages: List<BillboardPage>,
    cycleMs: Long,
    trailerMode: BillboardTrailerMode,
    trailerDelayMs: Long,
    trailerMuted: Boolean,
    trailerAutoplay: Boolean = true,
    /**
     * A fixed hero height, for the TV Home.
     *
     * The phone sizes the frame by width (16:10), which on a television asks
     * for more height than the viewport has and leaves the rails nothing: the
     * hero is the whole screen and the rail list is composed with a zero
     * viewport, so the D-pad has nowhere to go and nothing can reach the top
     * bar. A TV Home passes a viewport fraction here instead; null keeps the
     * phone's own math untouched.
     */
    heroHeight: Dp? = null,
    /**
     * Where focus lands when the billboard is the first thing on screen.
     *
     * The pills below are the billboard's only controls, so the TV Home points
     * this at the Play pill and parks focus there on entry - without it nothing
     * in the layout holds focus after the rails were given a viewport, and the
     * D-pad has no starting point.
     */
    firstPillFocusRequester: FocusRequester? = null,
    /**
     * True while ANOTHER surface owns the pooled player (Spotlight's expanded
     * card).
     *
     * The billboard then stops whatever it is playing through its own stop path
     * (the page-change / ON_PAUSE one), so its surface renders the still instead
     * of a video with no player behind it, and it starts nothing new until the
     * flag lifts. That handoff is a deliberate CUT, not a death: nothing is
     * recorded and no source is poisoned.
     */
    trailerSuppressed: Boolean = false,
    /**
     * Reports whether THIS surface currently has a trailer playing.
     *
     * The TV Home holds its idle overlay off while any surface plays, and the
     * billboard is one of those surfaces: without this the flag stayed false in
     * Spotlight and the overlay armed over a playing trailer. (The phone passes
     * nothing; it has no such overlay.)
     */
    onTrailerPlayingChange: (Boolean) -> Unit = {},
    onPlay: (BillboardPage) -> Unit,
    onDetails: (BillboardPage) -> Unit
) {
    if (pages.isEmpty()) return

    val scope = rememberCoroutineScope()
    // A fresh composition opens on a random page.
    //
    // The pool itself only changes when the feed's membership does - once a day,
    // on trending-day - so without this, two Home visits in one morning open on
    // the same title and swipe through the same sequence. This is the within-day
    // variety; the cycle ORDER stays the dealt order, so swiping never jumps.
    // Remembered, so an ordinary recomposition of this same composition does not
    // move the pager out from under the viewer.
    val billboardStartPage = remember { Random.nextInt(pages.size) }
    val pagerState = rememberPagerState(
        initialPage = billboardStartPage,
        pageCount = { pages.size }
    )
    val currentPage = pages.getOrNull(pagerState.currentPage)

    // What the render actually has in front of it (spec 2026-11-12, follow-up
    // F1): the page the pager is on, whether that page has art of its own, and
    // the settled tally. Written in a SideEffect so it never writes into a
    // composition - and it is the half of the render line the Home's list half
    // cannot see.
    SideEffect {
        val page = currentPage
        SpotlightDiagTrace.billboardRenderHero(
            composed = true,
            gate = null,
            pageKey = page?.key,
            art = when {
                page == null -> "no-page"
                !page.backdropUrl.isNullOrBlank() -> "backdrop"
                billboardPageArt(page) != null -> "own-art"
                billboardHeroArt(pages, pagerState.currentPage) != null -> "borrowed"
                else -> "none"
            },
            settled = pages.count { it.settled },
            total = pages.size
        )
    }

    // "Remove animations" (system animator duration scale 0): the house helper,
    // the same one the touch cards use. It turns OFF the sweep and the entrance
    // and leaves the progress bar showing position without motion - the
    // billboard is static, but it still advances on its hold.
    val reducedMotion = rememberReducedMotion()

    // ONE value drives the sweep and the current segment's fill: they cannot
    // drift apart, and "pause together, resume from the held fraction" is a
    // property of there being a single animation rather than two clocks that
    // have to agree. A new page resets it; a pause only stops it.
    val sweepProgress = remember { Animatable(0f) }
    val reveal = remember { Animatable(if (reducedMotion) 1f else 0f) }

    // The trailer on the CURRENT page, or null for the still. [playbackPageKey]
    // is the page that owns it - and it is set as soon as a trailer is ASKED
    // for, not when its source resolves, so a resolve that lands after the
    // viewer swiped away can tell it is late and drop its own result.
    var playback by remember { mutableStateOf<HeroTrailerPlayback?>(null) }
    var playbackPageKey by remember { mutableStateOf<String?>(null) }
    // The source that is mounted, kept past its own teardown: onEnded() runs
    // BEFORE onFailed()/onIdentityBlocked(), so by the time a death is planned
    // the playback that died is gone from state - and its shape (adaptive or
    // muxed, the signed URL's `clen`) is what the retry plan needs.
    var trailerMountedSource by remember { mutableStateOf<PlayableSource?>(null) }

    // The retry ladder (HeroTrailerRetryPlan), page-scoped like everything else
    // the billboard holds about a trailer: leaving a page starts the next one
    // clean, exactly as the phone's Detail hero does when its title changes.
    // This surface used to answer a death by dropping the still and letting
    // autoplay re-sign the SAME class from position 0 - the shape the
    // persistent-death work removed everywhere else (see heroTrailerRetryPlan).
    val ladderPageKey = pages.getOrNull(pagerState.currentPage)?.key
    var trailerExcludedClients by remember(ladderPageKey) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var trailerIdenticalReplays by remember(ladderPageKey) { mutableIntStateOf(0) }
    var trailerResumeAttempts by remember(ladderPageKey) { mutableIntStateOf(0) }
    // The retry a death asked for, held until the resolve that answers it
    // COMPLETES: the record carries the exclusions and the start position that
    // belong to it.
    var trailerPendingRetry by remember(ladderPageKey) {
        mutableStateOf<BillboardTrailerRetry?>(null)
    }
    // Deaths ask for their replacement through a REQUEST counter, never a direct
    // call: the player reports a death in two callbacks (onEnded, then
    // onFailed/onIdentityBlocked), so a start issued from the first would be
    // torn down by the stop in the second.
    var trailerRetryRequest by remember(ladderPageKey) { mutableIntStateOf(0) }
    // The clip's own length, from the player: the ordinary failure callback
    // reports only a position, and the identical replay's skip needs the length
    // to turn its chunk into media time.
    var trailerDurationMs by remember(ladderPageKey) { mutableLongStateOf(0L) }

    var backgrounded by remember { mutableStateOf(false) }

    fun stopTrailer() {
        playback = null
        // Deliberately NOT nulled here for the "still resolving" case: this is
        // the stop, so any resolve still in flight is stale by definition.
        playbackPageKey = null
        TrailerPlayerPool.releaseForReuse()
    }

    fun startTrailer(index: Int) {
        // Another surface owns the player: nothing starts here (see the flag's
        // doc - the viewer's mode comes back when the card collapses).
        if (trailerSuppressed) return
        val page = pages.getOrNull(index) ?: return
        val key = page.trailerKey?.takeIf { it.isNotBlank() } ?: return
        val ownerKey = page.key
        playbackPageKey = ownerKey
        // SNAPSHOT the retry this run answers; its exclusions and its start
        // position belong together, and it is cleared only by this resolve's
        // own outcome.
        val pending = trailerPendingRetry
        scope.launch {
            val result = TrailerPlayerLauncher.resolvePlayableUrl(
                key,
                excludeClients = pending?.excludedClients ?: emptySet(),
                imdbId = page.imdbId,
                tmdbId = page.tmdbId,
                type = page.preview.type
            )
            result.onSuccess { source ->
                if (playbackPageKey != ownerKey) return@onSuccess
                if (pages.getOrNull(pagerState.currentPage)?.key != ownerKey) return@onSuccess
                trailerPendingRetry = null
                trailerMountedSource = source
                if (pending != null) {
                    // The retry's outcome, in the trace's closed vocabulary: the
                    // replacement resolved, signed by <client>.
                    PoTokenTrace.recordTrailerDeath(
                        videoId = key,
                        client = pending.deadClient,
                        // The dying mount's facts, not this replacement's.
                        kind = pending.deadKind,
                        positionMs = pending.deathPositionMs,
                        durationMs = pending.durationMs,
                        resume = "client " +
                            (TrailerPlayerLauncher.signingClient(key)
                                ?: "unknown") + " ok",
                        itag = pending.deadItag,
                        attempt = pending.attempt,
                        change = pending.change,
                        chunkOffsetBytes = pending.deadChunkOffsetBytes,
                        chunkTotalBytes = pending.deadChunkTotalBytes
                    )
                }
                playback = HeroTrailerPlayback(
                    source = source,
                    startPositionMs = pending?.resumeAtMs ?: 0L
                )
            }
            result.onFailure { error ->
                // Silent ON SCREEN: the page keeps its backdrop, with no banner
                // and no retry UI. Not silent in the log - a trailer that never
                // mounts has to be findable in a capture, which is what the
                // fullscreen owners already do for the same failure.
                Log.w(TAG, "Billboard trailer key=$key failed to resolve", error)
                if (playbackPageKey == ownerKey) playbackPageKey = null
                if (pending != null) {
                    trailerPendingRetry = null
                    // Only a WALKED ladder whose remaining clients all failed
                    // is a verdict; a first attempt that found nothing is an
                    // ordinary failure and stays re-armable - and a HOLD is not
                    // a verdict at all (a cold mint, not a refused client), so it
                    // must stay re-armable too.
                    if (error is TokenlessHoldException) {
                        Log.w(
                            TAG,
                            "Billboard trailer key=$key held without a source to " +
                                "mount; leaving it re-armable " +
                                "(excluded=${pending.excludedClients.sorted()})"
                        )
                    } else if (heroTrailerResolveFailurePoisons(pending.excludedClients, error)) {
                        TrailerPlayerLauncher.markIdentityBlocked(key)
                    }
                }
            }
        }
    }

    /**
     * Plans and starts the retry a death is owed, or declines it.
     *
     * Both failure shapes come through here - an ordinary mid-stream error
     * (`onFailed`, which knows only the position) and a googlevideo 403 the
     * classifier calls a refused identity (`onIdentityBlocked`, which also knows
     * the duration) - through the same changed-class-first plan the phone's
     * Detail hero uses: a class that has died is never offered again, a
     * replacement resumes at the death, an identical replay resumes PAST it, and
     * `MAX_IDENTICAL_REPLAYS` bounds the replay. Returning false is "leave the
     * still up".
     *
     * The death line is written FIRST, before anything that can fail: a capture
     * has to keep the fact even when the request it describes never reaches a
     * resolve.
     */
    fun requestTrailerRetry(
        positionMs: Long,
        durationMs: Long,
        identity: Boolean = false
    ): Boolean {
        val page = pages.getOrNull(pagerState.currentPage) ?: return false
        val key = page.trailerKey?.takeIf { it.isNotBlank() } ?: return false
        // The refusal path knows the duration; the ordinary one is told it by
        // the player's own clock (see onTrailerProgress).
        val length = if (durationMs > 0L) durationMs else trailerDurationMs
        val dead = TrailerPlayerLauncher.signingClient(key)
        // The dying mount's own facts, read BEFORE anything can return: the
        // first write below quotes them and the retry carries them to the
        // overwrite that answers it (see BillboardTrailerRetry).
        val chunk = PoTokenTrace.chunkDeathFor(key)
        val source = trailerMountedSource
        val signedUrl = source?.signedUrl()
        val streamBytes = chunk?.totalBytes
            ?: signedUrl?.let { googlevideoBytes(it) }
        // The refusal path names the resolve's kind (the TV hero's vocabulary);
        // the ordinary one the dead source's.
        val deadKind = if (identity) PoTokenTrace.resolveKind() else source?.deathKind()
        val deadItag = signedUrl?.let { googlevideoItag(it) }
        // An IDENTITY refusal takes the ladder's own verdict before the plan's:
        // it is the one event whose exhaustion has to BLOCK the video, and the
        // one whose death line names the resolve's kind rather than the dead
        // source's (the same vocabulary the TV hero records).
        if (identity) {
            val refusal = heroTrailerIdentityRefusal(
                deadClient = TrailerPlayerLauncher.cachedSigningClient(key),
                excludedClients = trailerExcludedClients,
                resumeAttempts = trailerResumeAttempts
            )
            if (refusal.exhausted) {
                trailerPendingRetry = null
                PoTokenTrace.recordTrailerDeath(
                    videoId = key,
                    client = dead,
                    kind = deadKind,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    resume = "client " + (dead ?: "unknown") + " died",
                    // Terminal (the video is blocked below), and the one write
                    // that used to name neither the format nor the range.
                    itag = deadItag,
                    chunkOffsetBytes = chunk?.offsetBytes,
                    chunkTotalBytes = streamBytes
                )
                TrailerPlayerLauncher.markIdentityBlocked(key)
                Log.w(
                    TAG,
                    "Billboard trailer key=$key: identity ladder exhausted " +
                        "(excluded=${refusal.excludedClients.sorted()} " +
                        "attempts=$trailerResumeAttempts); blocked for this session"
                )
                return false
            }
        }
        val plan = heroTrailerRetryPlan(
            positionMs = positionMs,
            durationMs = length,
            deadClient = TrailerPlayerLauncher.cachedSigningClient(key),
            excludedClients = trailerExcludedClients,
            identicalReplays = trailerIdenticalReplays,
            resumeAttempts = trailerResumeAttempts,
            totalBytes = streamBytes
        )
        val attempt = trailerResumeAttempts
        val change = when {
            attempt == 0 -> "first"
            plan.changedClass ->
                "class:" + (plan.excludedClients - trailerExcludedClients).joinToString(",")

            else -> "same"
        }
        PoTokenTrace.recordTrailerDeath(
            videoId = key,
            client = dead,
            kind = deadKind,
            positionMs = positionMs,
            durationMs = length,
            resume = if (plan.verdict == HeroTrailerResumeVerdict.RESUME) {
                "pending"
            } else {
                "none: retries spent"
            },
            itag = deadItag,
            attempt = attempt,
            change = change,
            chunkOffsetBytes = chunk?.offsetBytes,
            chunkTotalBytes = streamBytes
        )
        if (plan.verdict != HeroTrailerResumeVerdict.RESUME) {
            trailerPendingRetry = null
            Log.w(
                TAG,
                "Billboard trailer key=$key: no untried client left after " +
                    "${attempt + 1} attempt(s) (excluded=${plan.excludedClients.sorted()}); " +
                    "leaving the still up"
            )
            return false
        }
        trailerExcludedClients = plan.excludedClients
        trailerResumeAttempts = attempt + 1
        if (!plan.changedClass) trailerIdenticalReplays += 1
        trailerPendingRetry = BillboardTrailerRetry(
            resumeAtMs = plan.resumeAtMs,
            excludedClients = plan.excludedClients,
            attempt = attempt + 1,
            change = change,
            deadClient = dead,
            deathPositionMs = positionMs,
            durationMs = length,
            deadKind = deadKind,
            deadItag = deadItag,
            deadChunkOffsetBytes = chunk?.offsetBytes,
            deadChunkTotalBytes = streamBytes
        )
        // The cached URL is the one that just died: drop it so the replacement
        // really re-signs. This also lifts an identity block, which the plan has
        // just decided is worth another step.
        TrailerPlayerLauncher.invalidate(key)
        Log.w(
            TAG,
            "Billboard trailer key=$key died at ${positionMs}ms; retry ${attempt + 1} " +
                "($change) resuming at ${plan.resumeAtMs}ms " +
                "excluded=${plan.excludedClients.sorted()}"
        )
        trailerRetryRequest += 1
        return true
    }

    // The requested replacement, started after the death's callbacks have all
    // run (see trailerRetryRequest).
    LaunchedEffect(trailerRetryRequest) {
        if (trailerRetryRequest == 0) return@LaunchedEffect
        startTrailer(pagerState.currentPage)
    }

    // PO-token pre-warm for the NEXT page, on settle. The billboard was the only
    // hero surface that never pre-warmed, so every one of its trailer mints ran
    // on the resolve's critical path - the shape the 0.9.23 capture shows (a
    // cold WebView spending its whole GVS budget on nothing), and the worst
    // surface for it because a page's trailer is what the dwell buys.
    //
    // Fire and forget: never awaited, never a dependency of a resolve, never
    // writing failure memory (a pre-warm warms tokens, it does not resolve).
    //
    // Honest limit: the default dwell is 6 s and the GVS budget is 6 s, so a
    // warm started at settle may not finish before the next page arms. It still
    // starts the mint earlier than turning the page would, and a completed
    // pre-warm is a cache hit at resolve time; the resolve's own re-mint covers
    // the rest (see InnerTubeExtractor.prewarmPoTokens).
    val nextPageTrailerKey = pages
        .getOrNull((pagerState.settledPage + 1) % pages.size.coerceAtLeast(1))
        ?.trailerKey
        ?.takeIf { it.isNotBlank() }
    LaunchedEffect(pagerState.settledPage, nextPageTrailerKey) {
        nextPageTrailerKey?.let { key -> InnerTubeExtractor.prewarmPoTokens(key) }
    }
    // The same page's IMDb trailer, warmed on the same settle: the billboard's
    // play press is one swipe (or one hold) away, and the lookup - title page
    // included - is what should NOT be in front of it. Its own effect, so the
    // token warm above is never held behind it.
    val nextPage = pages.getOrNull((pagerState.settledPage + 1) % pages.size.coerceAtLeast(1))
    LaunchedEffect(pagerState.settledPage, nextPage?.imdbId, nextPage?.tmdbId) {
        runCatchingCancellable {
            TrailerPlayerLauncher.prefetchImdbTrailer(
                imdbId = nextPage?.imdbId,
                tmdbId = nextPage?.tmdbId,
                type = nextPage?.preview?.type
            )
        }
    }

    // A trailer never plays over a page the viewer has left, and never over
    // another app: both are stops, and the pool is released with them.
    LaunchedEffect(pagerState.currentPage) {
        stopTrailer()
    }
    // ...and never over another surface that has taken the pooled player. The
    // mode flip alone is not a stop: an in-flight trailer kept its source and
    // its mounted surface, so the card's player re-pointed underneath it and
    // the hero showed black with its cycle frozen. This is the stop.
    LaunchedEffect(trailerSuppressed) {
        // Only a trailer that is ACTUALLY playing needs the deliberate cut. This
        // used to release the pooled player on every suppression - including the
        // Spotlight card's expand, where the hero was showing a still - and that
        // release tears down a video surface under the focused rail card. On a
        // television a surface detach is window-level churn, and the field
        // collapse of a panel ~0.5 s after it opened is the shape of focus lost
        // to nothing (spec 2026-11-12).
        if (trailerSuppressed && (playback != null || playbackPageKey != null)) stopTrailer()
    }
    // A TV Home's idle-overlay hold reads this, and it falls on dispose: a
    // trailer that ended, died or was cut must not leave the flag stuck true.
    val trailerPlayingNow = playback != null
    LaunchedEffect(trailerPlayingNow) {
        onTrailerPlayingChange(trailerPlayingNow)
    }
    DisposableEffect(Unit) {
        onDispose { onTrailerPlayingChange(false) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        backgrounded = true
        stopTrailer()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        backgrounded = false
    }
    // Leaving Home entirely disposes the slot while the pooled player survives
    // composition; without this its audio would keep playing over the next
    // screen (the TV hero's own rule).
    DisposableEffect(Unit) {
        onDispose { TrailerPlayerPool.releaseForReuse() }
    }

    // Auto-play, on the existing hero-trailer delay rather than a second pref.
    // The pending start is cancelled by this effect's keys: a page change, a
    // drag, backgrounding, or the trailer ending all restart it, and nothing
    // awaits a delay that outlived the page it was armed for.
    LaunchedEffect(
        pagerState.currentPage,
        pagerState.isScrollInProgress,
        trailerMode,
        trailerDelayMs,
        trailerAutoplay,
        playback,
        playbackPageKey,
        backgrounded,
        trailerSuppressed,
        currentPage?.settled,
        currentPage?.trailerKey
    ) {
        if (trailerMode != BillboardTrailerMode.AUTO) return@LaunchedEffect
        if (!trailerAutoplay) return@LaunchedEffect
        if (trailerSuppressed) return@LaunchedEffect
        if (backgrounded || pagerState.isScrollInProgress) return@LaunchedEffect
        if (playbackPageKey != null) return@LaunchedEffect
        val page = pages.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (!page.settled || page.trailerKey.isNullOrBlank()) return@LaunchedEffect
        delay(trailerDelayMs)
        startTrailer(pagerState.currentPage)
    }

    // The hold. "Manual only" (0) means no auto-advance at all - the swipe and
    // the segments are then the only way page to page, which is a real option
    // rather than a hidden one. The timer is paused while the viewer's
    // finger is on the pager, while the app is backgrounded, and while a
    // trailer owns the page (including the delay before an auto-played one), so
    // a page is never swapped out from under the video.
    //
    // The pace follows the SETTLED page, not `currentPage`: `currentPage` moves
    // while a scroll is still in flight, so keying a delay on it cancelled the
    // delay - and, because the advance itself is what moved it, could cancel a
    // half-finished pager animation. A finger on the pager is the pause, and its
    // release is the re-arm whether it landed on a new page or on this one.
    val autoTrailerArmed = trailerMode == BillboardTrailerMode.AUTO &&
        !trailerSuppressed &&
        currentPage?.settled == true &&
        !currentPage?.trailerKey.isNullOrBlank()
    LaunchedEffect(
        pagerState,
        cycleMs,
        playback,
        playbackPageKey,
        backgrounded,
        pages.size,
        autoTrailerArmed,
        // Lifting the suppression re-arms the hold, so the billboard's cycle
        // restarts when the expanded card collapses instead of staying frozen
        // on the page it stopped (the landing intent, now the behaviour).
        trailerSuppressed
    ) {
        if (cycleMs <= 0L || pages.size <= 1) return@LaunchedEffect
        if (backgrounded || playbackPageKey != null || playback != null) {
            return@LaunchedEffect
        }
        if (autoTrailerArmed) return@LaunchedEffect
        // An expanded card holds the hero still (audit a1): suppression already
        // stopped NEW trailers, but nothing paused the cycle, so the still swept
        // on behind the panel and the viewer collapsed back onto a page the hero
        // had moved to on its own. Keyed on trailerSuppressed above, so lifting
        // it re-enters here and restarts the delay (the landing intent).
        if (trailerSuppressed) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .collectLatest { (page, scrolling) ->
                if (scrolling) return@collectLatest
                delay(cycleMs)
                val next = (page + 1) % pages.size
                // Launched on the composition's scope rather than awaited here:
                // the animation makes `isScrollInProgress` true, and awaiting it
                // would let this collector cancel the very scroll it started.
                scope.launch { pagerState.animateScrollToPage(next) }
            }
    }

    // The sweep and the entrance pause with EXACTLY what the hold pauses with -
    // a finger on the pager, the app in the background, a trailer that owns the
    // page (or is waiting out its delay) - so the move is never playing to a
    // screen nobody is looking at, and the fill never advances under a pause.
    val sweepPaused = reducedMotion ||
        backgrounded ||
        pagerState.isScrollInProgress ||
        playbackPageKey != null ||
        playback != null ||
        autoTrailerArmed ||
        // The Ken Burns sweep is the other half of the hold (audit a1): the
        // still must not keep moving under an expanded card either.
        trailerSuppressed
    // The page this Animatable belongs to. It is what distinguishes a page
    // CHANGE (snap back to the start) from a pause (hold where it is): the
    // effect below is keyed on both, so the difference has to be data.
    var sweptPage by remember { mutableStateOf(-1) }
    LaunchedEffect(pagerState.settledPage, sweepPaused, cycleMs, reducedMotion) {
        val page = pagerState.settledPage
        if (reducedMotion) {
            sweepProgress.snapTo(1f)
            sweptPage = page
            return@LaunchedEffect
        }
        if (sweptPage != page) {
            sweepProgress.snapTo(0f)
            sweptPage = page
        }
        if (sweepPaused) {
            // Holds the held fraction: stop() keeps the value it reached, and
            // the branch above only snaps when the PAGE changed.
            sweepProgress.stop()
            return@LaunchedEffect
        }
        val totalMs = if (cycleMs > 0L) cycleMs else BILLBOARD_KEN_BURNS_MANUAL_MS
        val remainingMs = ((1f - sweepProgress.value) * totalMs).toInt().coerceAtLeast(1)
        sweepProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = remainingMs, easing = LinearEasing)
        )
    }
    // One entrance per page entry, replayed when a page is returned to.
    LaunchedEffect(pagerState.settledPage, reducedMotion) {
        if (reducedMotion) {
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        reveal.snapTo(0f)
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = BILLBOARD_CONTENT_REVEAL_MS,
                easing = FastOutSlowInEasing
            )
        )
    }

    // The pills, handed to the CURRENT page's hero and drawn on the art. One
    // block, one reveal: the copy and the pills are the page.
    val pageActions: @Composable () -> Unit = {
        BillboardAction(
            label = "Play",
            icon = Icons.Filled.PlayArrow,
            accent = true,
            focusRequester = firstPillFocusRequester,
            onClick = { currentPage?.let(onPlay) }
        )
        BillboardAction(
            label = "Details",
            onClick = { currentPage?.let(onDetails) }
        )
        // Manual mode is the ONLY mode that draws this: with Off there is no
        // trailer affordance at all, and with Auto-play the mode itself is
        // the affordance.
        if (
            trailerMode == BillboardTrailerMode.MANUAL &&
            !currentPage?.trailerKey.isNullOrBlank()
        ) {
            BillboardAction(
                label = if (playback != null) "Stop" else "Trailer",
                icon = if (playback != null) {
                    Icons.Filled.Close
                } else {
                    Icons.Filled.PlayArrow
                },
                onClick = {
                    if (playback != null) {
                        stopTrailer()
                    } else {
                        startTrailer(pagerState.currentPage)
                    }
                }
            )
        }
    }

    Column(
        // The frame (spec 2.7): 16dp off the screen's edge and 20dp corners, so
        // the page's own background shows round the hero and it reads as a card
        // on this Home rather than as a full-bleed banner. 20dp is hero scale of
        // the app's rounded language (a tile is 12dp).
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth()
            ) { index ->
                val page = pages[index]
                // The sweep belongs to the page's POSITION and the dwell, so it
                // is remembered rather than rebuilt per frame - and it is
                // computed UNCONDITIONALLY, because a remember inside an `if`
                // would corrupt the slot table the moment animations are
                // turned off or back on mid-page.
                val sweep = remember(index, cycleMs) { billboardSweep(index, cycleMs) }
                MobileHero(
                    meta = page.meta,
                    // The page's own art, or - when it settled with none at all -
                    // the next settled page's, so the hero is never a black
                    // frame (spec 2026-11-12, B4).
                    backdropUrl = billboardHeroArt(pages, index),
                    logoUrl = page.logoUrl,
                    // Animations off is a STILL backdrop, not a slower one.
                    backdropSweep = sweep.takeUnless { reducedMotion },
                    // Read inside the layer, and only for the page in front: a
                    // neighbour sliding in during a drag shows that page's own
                    // start of the move, not the current page's progress.
                    backdropProgress = {
                        if (pagerState.currentPage == index) sweepProgress.value else 0f
                    },
                    contentReveal = { reveal.value },
                    trailerPlayback = if (index == pagerState.currentPage) playback else null,
                    trailerMuted = trailerMuted,
                    trailerVideoId = page.trailerKey,
                    onTrailerEnded = { stopTrailer() },
                    onTrailerProgress = { _, duration ->
                        if (duration > 0L) trailerDurationMs = duration
                    },
                    // A mid-play death asks the ladder for a DIFFERENT request
                    // (see requestTrailerRetry): another client's URL class
                    // first, and an identical re-sign only once, resumed PAST
                    // the position that refused.
                    onTrailerFailed = { positionMs ->
                        requestTrailerRetry(positionMs, durationMs = 0L)
                    },
                    // A googlevideo 403 the classifier calls a refused identity
                    // is a property of the URL class that signed it, not of the
                    // video, so it walks the identity ladder - the one the TV
                    // hero uses - and its answer is whether a replacement was
                    // requested.
                    onTrailerIdentityBlocked = { positionMs, durationMs ->
                        requestTrailerRetry(positionMs, durationMs, identity = true)
                    },
                    onStopTrailer = { stopTrailer() },
                    // The TV's cap, or the phone's own width math (null).
                    heroHeight = heroHeight,
                    // Only the page in front carries the pills: a neighbour
                    // sliding in must not drag its own live buttons with it.
                    actions = if (index == pagerState.currentPage) pageActions else null,
                    onClick = { onDetails(page) }
                )
            }
            // TV has no segments (audit a4): each one was a plain `.clickable`
            // with a 24 dp target and no focus handling, so on the TV the bar was
            // a control the D-pad could not reach. Making it focusable would put
            // a second focus stop between the hero's pills and the rails; the
            // hero's TV paging is the cycle plus the pills, so the segments are
            // touch-only and are not drawn on TV at all. What must not remain is
            // a visible control the D-pad cannot reach.
            if (pages.size > 1 && heroHeight == null) {
                // The segmented progress bar (spec 2.6), in the dots' place and
                // with the dots' job intact: one segment per page, every segment
                // tappable to its page, each naming the title it jumps to.
                //
                // It sits ON the image now (spec 2.7), inside the frame's clip:
                // a detached strip of dashes under the card read as a third
                // element when the frame already had an edge to hang it from.
                //
                // "Manual only" and system animations off fill the current
                // segment instead of animating it: position without motion.
                val animateFill = !reducedMotion && cycleMs > 0L
                val segmentColor = KBAccent
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                        // The count the deleted "N of M" line used to carry, in
                        // the one place it earns its keep: announced, not drawn
                        // (the fill already says where the pager is to an eye).
                        .semantics {
                            contentDescription =
                                "Page ${pagerState.currentPage + 1} of ${pages.size}"
                        },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pages.forEachIndexed { index, page ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                // The touch target is a row high even though the
                                // track is 3dp: a 3dp tap target is not one.
                                .height(24.dp)
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                                .semantics {
                                    contentDescription = page.meta.name
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(BILLBOARD_SEGMENT_TRACK_DP.dp)
                                    .clip(CircleShape)
                                    // The track is the dimmed base every segment
                                    // starts as, which is exactly what an UPCOMING
                                    // page looks like; the accent fill drawn below
                                    // covers it for the pages behind and the
                                    // current page's own progress.
                                    .background(KBTextLo.copy(alpha = 0.25f))
                                    .drawBehind {
                                        // Read in the DRAW phase: the fill advances
                                        // every frame and must not recompose the
                                        // bar (or the pager behind it) for it.
                                        val fill = billboardSegmentFill(
                                            segment = index,
                                            current = pagerState.currentPage,
                                            progress = sweepProgress.value,
                                            animated = animateFill
                                        )
                                        if (fill > 0f) {
                                            drawRect(
                                                color = segmentColor,
                                                size = Size(size.width * fill, size.height)
                                            )
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One billboard action, as a pill drawn ON the art (spec 2.7).
 *
 * This used to be a KBCard, whose plate is the app's own surface fill - on a
 * backdrop that plate is invisible, which is exactly how the pre-refresh
 * screenshot's Play and Details rendered as bare text floating over the image.
 * A pill is drawn here instead: the accent fill for Play, a translucent glass
 * fill with a hairline border for the secondaries, full-round, inside the
 * frame. The press scale is the house one (KB_PRESS_SPRING at 0.98), so a press
 * still gives way under the thumb.
 *
 * On a television these are the hero's ONLY controls, so they are real D-pad
 * focusables: focus grows the pill a little and rings it in the app's accent,
 * the same language the rail cards speak, and it is what the viewer sees move
 * when the remote crosses the hero. Touch never focuses them, so the phone's
 * press behaviour is exactly what it was.
 */
@Composable
private fun BillboardAction(
    label: String,
    icon: ImageVector? = null,
    accent: Boolean = false,
    /** The pill a TV Home parks focus on when it opens (see MobileBillboard). */
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val contentColor = if (accent) KBVoid else KBTextHi
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = rememberReducedMotion()
    val pressScale by animateFloatAsState(
        targetValue = when {
            reducedMotion -> 1f
            pressed -> 0.98f
            focused -> 1.04f
            else -> 1f
        },
        animationSpec = if (reducedMotion) {
            tween(durationMillis = 0)
        } else {
            KB_PRESS_SPRING
        },
        label = "billboardActionPress"
    )
    Box(
        modifier = Modifier
            .height(42.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(if (accent) KBAccent else KBTextHi.copy(alpha = 0.16f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    // The accent fill is already the focus colour on Play; on the
                    // secondaries the ring is what makes focus visible.
                    focused && accent -> KBTextHi
                    focused -> KBAccent
                    accent -> Color.Transparent
                    else -> KBTextHi.copy(alpha = 0.30f)
                },
                shape = CircleShape
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * The rail key the billboard's feed picker stores for this rail.
 *
 * The same key the Home manager arranges the rail by, spelled through the same
 * functions, so a chip in the manager and the items here cannot disagree: a
 * built catalog keys by its id, an add-on catalog by its base URL (manifest
 * URL minus "/manifest.json"), and a rail the app builds itself (a pinned Top
 * Today row) keys by nothing - it is never offered, exactly as it is never
 * arrangeable.
 *
 * The rule itself now lives in `BillboardRules` (shared with the TV Spotlight
 * hero), so the two Homes resolve the SAME chip keys instead of carrying two
 * copies that could disagree.
 */
private fun Rail.billboardFeedKey(): String? =
    com.kennyb1201.kbstream.ui.home.billboardFeedKey(this)

/** A Continue Watching card as a billboard item: the SHOW (or the film). */
private fun UpNextItem.billboardPreview(): MetaPreview {
    val type = parentType?.takeIf { it.isNotBlank() }
        ?: if (season != null) "series" else "movie"
    return MetaPreview(
        id = if (type.equals("movie", ignoreCase = true)) id else (parentId ?: id),
        type = type,
        name = showTitle?.takeIf { it.isNotBlank() } ?: title,
        poster = poster,
        background = backdrop,
        imdbRating = imdbRating?.let { rating -> rating.toString() }
    )
}

/** A section heading plus its carousel, on the screen's 16dp edge. */
@Composable
private fun MobileSection(
    title: String,
    /**
     * When set, the heading is a control rather than a caption.
     *
     * Only the Upcoming row passes it, as on the TV: that row is one next
     * episode per show, while the whole year lives in Library's Calendar pane.
     * Every other section keeps a plain heading.
     */
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(MobileSpacing.gap)) {
        if (onClick == null) {
            MobileSectionHeader(title)
        } else {
            // The word itself is the target - no chip or pill around it. The
            // heading's own edge padding is restated here (the default one is
            // replaced, not added to) so the clickable title still lines up
            // with every other section's heading.
            //
            // The calendar glyph sits INSIDE that target, beside the word, the
            // same door the TV rail's heading draws: the word names the row,
            // the glyph says where pressing it goes (Library's Calendar pane).
            // It is not a chip or a trailing button - the heading is still the
            // whole target and the glyph is part of its label.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(horizontal = MobileSpacing.edge)
                    .clickable(onClick = onClick)
            ) {
                MobileSectionHeader(
                    title = title,
                    // The row carries the edge padding now; `fill = false` so a
                    // renamed heading ellipsizes instead of pushing the glyph
                    // off the row.
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    imageVector = Icons.Filled.CalendarMonth,
                    contentDescription = "Open calendar",
                    tint = KBAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        content()
    }
}

/**
 * A Library list the viewer pinned to Home, as a touch rail.
 *
 * The same rows the television's rail draws, out of the same loader (see
 * [rememberLibraryHomeRails]), and nothing else: a tap opens the title's Detail
 * exactly as the phone's catalog cards do - the same source signal for the
 * hero flight, the same `onItemClick` - because a pinned list is a rail like
 * any other on this screen. What it does NOT carry is the television's focus
 * machinery: there is no D-pad here, so no requesters, no bring-into-view and
 * no hero-follows-focus.
 *
 * The watched badges are the shared ones every card on this screen wears. A row
 * whose art does not resolve still draws its title (the card's own fallback),
 * which is why one bad poster never costs the rail.
 */
@Composable
private fun MobileLibraryListRail(
    rail: LibraryHomeRailUi,
    landscapeCards: Boolean,
    posterWidth: Dp,
    posterHeight: Dp,
    landscapeWidth: Dp,
    landscapeHeight: Dp,
    overridePaths: Map<String, String>,
    isWatched: (MetaPreview) -> Boolean,
    isPartiallyWatched: (MetaPreview) -> Boolean,
    onOpen: (MetaPreview) -> Unit
) {
    // The pin's stored name, plus what the loaded items ARE: a pinned list can
    // be all films, all shows or a mix, and the heading is the only place this
    // rail draws the type (the cards carry no badge) - the same suffix the TV
    // rail's heading carries, from the same rule. Display only: the pin keeps
    // the name the rename owns.
    MobileSection(
        title = rail.pin.name + libraryRailTypeSuffix(rail.items.map { it.type })
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = rail.items,
                // The rail is part of the key: the same title can sit in two
                // pinned lists, and one card's slot must not stand in for the
                // other's when a pin is removed above it (the same key the
                // television rail spells).
                key = { _, meta ->
                    "librail|" + rail.pin.key + "|" + meta.type + ":" + meta.id
                },
                contentType = { _, _ -> "library-list-card" }
            ) { _, meta ->
                val watched = isWatched(meta)
                val partiallyWatched = !watched && isPartiallyWatched(meta)
                // The same two resolutions the TV rail makes: a row with no art
                // of its own (BingeCat's media carries none) is drawn by its id,
                // and a row that HAS a poster (Trakt, MDBList) resolves nothing
                // while that poster loads.
                //
                // A carried poster is a first ATTEMPT, not a verdict (see
                // [CarriedArt]): a Trakt URL that does not load used to leave
                // the tile title-only with the resolvers switched off, while
                // the BingeCat rail beside it had its posters.
                var carriedArtFailed by remember(meta.poster) {
                    mutableStateOf(false)
                }
                val carriedArt = remember(meta.poster, carriedArtFailed) {
                    CarriedArt(meta.poster, carriedArtFailed)
                }
                val resolvedPoster = rememberTitlePosterArt(
                    id = meta.id,
                    type = meta.type,
                    enabled = !carriedArt.usable
                )
                val resolvedLandscapeArt = rememberGlobalLandscapeArt(
                    enabled = landscapeCards && !carriedArt.usable,
                    addonId = meta.id,
                    addonType = meta.type,
                    addonBackdrop = carriedArt.effective
                )
                if (landscapeCards) {
                    LandscapeCard(
                        // A Library row carries the service's poster but not a
                        // backdrop or a wordmark, so its poster is the still and
                        // the title is drawn in the corner - the same fallback
                        // an artwork-less catalog card uses.
                        backdropUrl = imageOverrideCardBackdropUrl(
                            paths = overridePaths,
                            rawId = meta.id,
                            fallbackUrl = resolvedLandscapeArt.first
                                ?: carriedArt.effective
                                ?: resolvedPoster
                        ),
                        logoUrl = resolvedLandscapeArt.second,
                        onBackdropError = { carriedArtFailed = true },
                        fallbackTitle = meta.name,
                        contentDescription = meta.name,
                        isWatched = watched,
                        isPartiallyWatched = partiallyWatched,
                        onClick = {
                            armHeroSource(meta.type, meta.id)
                            onOpen(meta)
                        },
                        modifier = Modifier
                            .heroSourceOnPress(meta.type, meta.id)
                            .width(landscapeWidth)
                            .height(landscapeHeight)
                    )
                } else {
                    PosterCard(
                        posterUrl = imageOverridePosterUrl(
                            paths = overridePaths,
                            rawId = meta.id,
                            fallbackUrl = carriedArt.effective ?: resolvedPoster
                        ),
                        onPosterError = { carriedArtFailed = true },
                        contentDescription = meta.name,
                        isWatched = watched,
                        isPartiallyWatched = partiallyWatched,
                        onClick = {
                            armHeroSource(meta.type, meta.id)
                            onOpen(meta)
                        },
                        modifier = Modifier
                            .heroSourceOnPress(meta.type, meta.id)
                            .width(posterWidth)
                            .height(posterHeight)
                    )
                }
            }
        }
    }
}

/**
 * A Continue Watching card, in the television rail's own design.
 *
 * The card WAS a bare backdrop with the show and episode captioned UNDER it,
 * which read as a different row from the one on the TV: everything here is now
 * on the artwork the way `CompactUpNextCard` draws it - the scrim, the state
 * chip in the top-left (RESUME / NEXT UP / NEW EPISODE / NEW SEASON, or a
 * finale flag), the show title and episode line in the bottom-left corner,
 * the "N ep. left" and time-left chips in the corners, and the resume bar
 * along the bottom edge.
 *
 * Spoiler-free keeps the TV rule too: an episode the viewer has not started
 * shows the SHOW's art instead of a still from that episode, and its episode
 * title is not drawn.
 */
@Composable
private fun ContinueWatchingCard(
    item: UpNextItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val title = item.showTitle ?: item.title
    // Spoiler-free: the card is offering an unwatched episode, so the guard is
    // entirely the resume position - the same rule the TV card applies.
    val hidesEpisodeIdentity = rememberHidesEpisodeTitle(inProgress = item.startPositionMs > 0)
    val episodeLabel = upNextEpisodeLabel(item)
    val progress = item.progressPercent?.coerceIn(0f, 1f)
    // Resume cards keep their progress bar and time-left. A finale being
    // resumed still has a saved position, so it behaves like a resume card.
    val isResumeCard = item.badge == UpNextBadge.CONTINUE_WATCHING ||
        (item.startPositionMs > 0L && (item.isSeasonFinale || item.isSeriesFinale))
    val timeLeft = if (isResumeCard) formatTimeLeft(item.remainingMinutes) else null
    val displayBadge = when {
        item.isSeriesFinale -> "SERIES FINALE"
        item.isSeasonFinale -> "SEASON FINALE"
        else -> when (item.badge) {
            UpNextBadge.CONTINUE_WATCHING -> "RESUME"
            UpNextBadge.NEXT_UP -> "NEXT UP"
            UpNextBadge.NEW_EPISODE -> "NEW EPISODE"
            UpNextBadge.NEW_SEASON -> "NEW SEASON"
        }
    }
    // The same chip colours the television gives the same states (see
    // `CompactUpNextCard`'s `badgeColor` argument): the chip is a state, and a
    // single accent for all four made a finale and a resume look alike.
    val badgeColor = when {
        item.isSeriesFinale -> KBDanger
        item.isSeasonFinale -> KBRust
        else -> when (item.badge) {
            UpNextBadge.CONTINUE_WATCHING -> KBAccent
            UpNextBadge.NEXT_UP -> KBSteel
            UpNextBadge.NEW_EPISODE -> KBSuccess
            UpNextBadge.NEW_SEASON -> KBPlum
        }
    }
    val bottomInset = if (isResumeCard && progress != null && progress > 0f) 15.dp else 9.dp

    PosterCard(
        posterUrl = if (hidesEpisodeIdentity) {
            item.backdrop ?: item.poster ?: ""
        } else {
            item.episodeThumbnail ?: item.backdrop ?: item.poster ?: ""
        },
        contentDescription = title,
        isWatched = false,
        onClick = onClick,
        onLongClick = onLongClick,
        // The card's own overlay prints the title (and the S/E), so the tile
        // must not print it a second time as its art-less placeholder.
        showFallbackText = false,
        modifier = Modifier
            .width(250.dp)
            .height(141.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            KBVoid.copy(alpha = 0.08f),
                            KBVoid.copy(alpha = 0.34f),
                            KBVoid.copy(alpha = 0.80f),
                            KBVoid.copy(alpha = 0.97f)
                        )
                    )
                )
        )

        Text(
            text = displayBadge,
            color = KBTextHi,
            // The card's other overlay chips (episodes left, time left) are
            // labelSmall; this one was 9sp, so two badges on the same artwork
            // were two different sizes.
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(color = badgeColor, shape = KBShapeChip)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, end = 10.dp, bottom = bottomInset)
        ) {
            Text(
                text = title,
                color = KBTextHi,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            episodeLabel?.let { label ->
                Text(
                    text = label,
                    color = KBTextHi.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (!hidesEpisodeIdentity) {
                item.episodeTitle
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { episodeTitle ->
                        Text(
                            text = episodeTitle,
                            color = KBTextHi.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
            }
        }

        item.episodesRemaining?.takeIf { it > 0 }?.let { remaining ->
            Text(
                text = if (remaining == 1) "1 ep. left" else "$remaining ep. left",
                color = KBTextHi,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = bottomInset)
                    .background(color = KBVoid.copy(alpha = 0.65f), shape = KBShapeChip)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        timeLeft?.let { label ->
            Text(
                text = label,
                color = KBTextHi.copy(alpha = 0.90f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(color = KBVoid.copy(alpha = 0.65f), shape = KBShapeChip)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        if (isResumeCard && progress != null && progress > 0f) {
            KBProgressBar(
                progress = progress,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
    }
}

/**
 * An Upcoming card, in the television rail's own design.
 *
 * The air date is the scan hook, so it gets the top-left chip ("NEW SEASON" on
 * a season premiere that is not airing today; "TODAY" still wins on the day it
 * matters), and the bottom block leads with the full calendar date before the
 * show title, the S/E line and the episode name - exactly the order
 * `UpcomingEpisodeCard` uses.
 */
@Composable
private fun UpcomingCard(
    episode: UpcomingEpisode,
    onClick: () -> Unit
) {
    val hidesEpisodeTitle = rememberHidesEpisodeTitle(inProgress = false)
    PosterCard(
        posterUrl = episode.backdrop ?: episode.poster,
        contentDescription = episode.title,
        isWatched = false,
        onClick = onClick,
        modifier = Modifier
            .width(250.dp)
            .height(141.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            KBVoid.copy(alpha = 0.08f),
                            KBVoid.copy(alpha = 0.34f),
                            KBVoid.copy(alpha = 0.80f),
                            KBVoid.copy(alpha = 0.97f)
                        )
                    )
                )
        )

        if (episode.isSeasonPremiere && episode.airDateLabel != "Today") {
            Text(
                text = "NEW SEASON",
                color = KBTextHi,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(color = KBPlum, shape = KBShapeChip)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        } else {
            Text(
                text = episode.airDateLabel.uppercase(),
                color = Color.Black,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(color = KBAccent, shape = KBShapeChip)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            episode.airDateFull
                .takeIf { it.isNotBlank() }
                ?.let { fullDate ->
                    Text(
                        text = fullDate,
                        color = KBTextHi.copy(alpha = 0.80f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            Text(
                text = episode.title,
                color = KBTextHi,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
            Text(
                // The television card's own rule, so the two rails number an
                // episode the same way: `S2 E8`, unpadded. An Upcoming row
                // always names a real pair, so the rule cannot answer null.
                text = tvUpNextEpisodeLabel(episode.season, episode.episode).orEmpty(),
                color = KBTextHi.copy(alpha = 0.78f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (!hidesEpisodeTitle) {
                episode.episodeTitle
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { episodeTitle ->
                        Text(
                            text = episodeTitle,
                            color = KBTextHi.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
            }
        }
    }
}

/**
 * Whether episode identities are hidden right now (spoiler-free mode).
 *
 * Read through the synced revision rather than once per card: the mode is a
 * SYNCED pref, so switching it on another device retitles the cards already on
 * screen.
 */
@Composable
private fun rememberHidesEpisodeTitle(inProgress: Boolean): Boolean {
    val context = LocalContext.current
    val revision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    val spoilerFree = remember(context, revision) { AppPreferences.getSpoilerFree(context) }
    return SpoilerFree.hidesIdentity(
        enabled = spoilerFree,
        watched = false,
        started = inProgress
    )
}

/**
 * `S02 · E08` for a Continue Watching card, from the fields the item has.
 *
 * The episode pair ONLY. The tracker subtitle carries the same state the card
 * already draws as its top-left chip ("Resume - S1 E2", "Up Next - ..."), so
 * leading this line with it printed the state twice and pushed the numbers off
 * the line on a phone. The pair comes from the television's own rule
 * ([tvUpNextEpisodeLabel]), so the card and the TV rail cannot number the same
 * episode differently; a card the sources named no pair for keeps the episode
 * title, then the tracker's own text ("Paused 34%"), rather than an empty line.
 */
private fun upNextEpisodeLabel(item: UpNextItem): String? =
    tvUpNextEpisodeLabel(item.season, item.episode)
        ?: item.episodeTitle?.takeIf { it.isNotBlank() }
        ?: item.subtitle?.takeIf { it.isNotBlank() }

/** The one-line meta under the hero's title. */
private fun heroMetaLine(meta: Meta): String =
    listOfNotNull(
        meta.releaseInfo?.takeIf { it.isNotBlank() },
        meta.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
        meta.type.replaceFirstChar { it.uppercase() }
    ).joinToString("  ·  ")

/**
 * The title one of the app's own Home rows draws: the viewer's rename from the
 * home manager, else the key's default title, else [fallback].
 *
 * The phone's spelling of the television's `builtinRailTitle`
 * ([com.kennyb1201.kbstream.ui.home.HomeScreen]), and deliberately the same
 * order of precedence: a blank name is not a name (the manager never stores
 * one, so this is defensive), and a rail the manager has no default for keeps
 * the title the screen has always drawn.
 *
 * Extracted from the composition so the rule has a behavioural test - the
 * field report this fixes was a row whose title "could not" be renamed because
 * the screen passed a string literal, and a source-only check of the call site
 * would not have caught a fallback that swallowed the override.
 */
internal fun mobileBuiltinRailTitle(
    renames: Map<String, String>,
    key: String,
    fallback: String
): String = renames[key]?.takeIf { it.isNotBlank() }
    ?: KBHomeOrderPrefs.builtinDefaultTitle(key)
    ?: fallback

/** The TV rail header's rule, spelled for the phone (see `homeRailTitle`). */
private fun mobileRailTitle(
    catalogName: String,
    addonName: String,
    type: String,
    showType: Boolean,
    showAddon: Boolean
): String {
    val parts = buildList {
        if (showAddon && addonName.isNotBlank()) add(addonName)
        add(catalogName)
        if (showType && type.isNotBlank()) add(type.replaceFirstChar { it.uppercase() })
    }
    return parts.joinToString(" · ")
}

/** The Detail-page preview an Upcoming card opens. */
private fun UpcomingEpisode.detail(): MetaPreview =
    MetaPreview(
        id = parentId,
        type = parentType,
        name = title,
        poster = poster,
        background = backdrop
    )

/**
 * Resumes a Continue Watching item.
 *
 * Builds the same canonical target the TV card builds - the episode marker is
 * composed into the title when the source did not put it there, and the display
 * name goes through the same rule so the player does not print an internal id -
 * then hands it to the detail target, which is the auto-select path.
 *
 * Like the TV's `openUpNext`, the history row's stream id is re-verified against
 * TMDB first (the `verifiedResumeStreamId` pass, for ids written by older
 * builds). The lookup is bounded: a slow or failed answer keeps the stored id
 * and opens anyway, because a tap must never hang on the network.
 */
/**
 * What a Continue Watching press does, once the item's resume root has been
 * re-verified. One enum rather than three booleans (the TV card's shape), so a
 * row cannot ask for two of them at once.
 *
 *  - [RESUME] is the card's own tap: Detail opens with the episode's play
 *    target and auto-plays it, resuming saved progress.
 *  - [DETAILS] is the menu's "Go to Details": the page, with NO play target,
 *    so nothing starts - this is the row that used to resume the file.
 *  - [STREAMS] is "Play Manually": the shell's streams route, which the picker
 *    opens without auto-selecting a source.
 *  - [FROM_BEGINNING] is "Play from Beginning": Detail, auto-playing at 0.
 */
private enum class UpNextOpen { RESUME, DETAILS, STREAMS, FROM_BEGINNING }

private fun openUpNextVerified(
    item: UpNextItem,
    viewModel: HomeViewModel,
    scope: CoroutineScope,
    mode: UpNextOpen,
    onOpenDetails: (MetaPreview) -> Unit,
    onOpenDetailTarget: (MetaPreview, StreamsTarget, String?) -> Unit,
    onOpenStreams: (MetaPreview, StreamsTarget, String?) -> Unit
) {
    // "Go to Details" is the page, not this episode: there is no stream to
    // resolve, so it skips the lookup (the TV card's details row does the same).
    if (mode == UpNextOpen.DETAILS) {
        openUpNext(item, mode, onOpenDetails, onOpenDetailTarget, onOpenStreams)
        return
    }
    val stored = item.episodeStreamId
    val type = item.parentType
    if (stored.isNullOrBlank() || type.isNullOrBlank()) {
        openUpNext(item, mode, onOpenDetails, onOpenDetailTarget, onOpenStreams)
        return
    }
    scope.launch {
        // Bounded like the TV path: a wrong id gets one lookup, and anything
        // slower - or a failure - keeps the stored id rather than hanging the
        // press.
        val verified = withTimeoutOrNull(CW_ROOT_VERIFY_TIMEOUT_MS) {
            runCatching {
                viewModel.verifiedResumeStreamId(
                    streamId = stored,
                    showName = item.showTitle ?: item.title,
                    type = type
                )
            }.getOrNull()
        } ?: stored
        openUpNext(
            item = item,
            mode = mode,
            onOpenDetails = onOpenDetails,
            onOpenDetailTarget = onOpenDetailTarget,
            onOpenStreams = onOpenStreams,
            verifiedStreamId = verified
        )
    }
}

private fun openUpNext(
    item: UpNextItem,
    mode: UpNextOpen,
    onOpenDetails: (MetaPreview) -> Unit,
    onOpenDetailTarget: (MetaPreview, StreamsTarget, String?) -> Unit,
    onOpenStreams: (MetaPreview, StreamsTarget, String?) -> Unit,
    // The root the verification pass returned, when one ran (see
    // [openUpNextVerified]). Null keeps the stored id.
    verifiedStreamId: String? = null
) {
    val parentId = item.parentId ?: return
    val parentType = item.parentType ?: return
    val namedEpisode = namedEpisodeNumber(item.episode)

    val detail = MetaPreview(
        id = parentId,
        type = parentType,
        name = item.title,
        poster = item.poster,
        background = item.backdrop,
        description = item.episodeDescription,
        logo = item.clearLogo
    )

    val hasMarker = item.season != null && namedEpisode != null &&
        Regex(
            "S\\s*0*${item.season}\\s*E\\s*0*$namedEpisode\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(item.title)
    val targetTitle =
        if (parentType == "series" && item.season != null && namedEpisode != null && !hasMarker) {
            buildString {
                append(item.title)
                append(" S${item.season} E$namedEpisode")
                item.episodeTitle?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
            }
        } else {
            item.title
        }

    // "Play from Beginning" is the one row that wants position 0 applied as-is:
    // a target carrying no position of its own resumes the saved watch-history
    // progress instead, which is exactly what the other three rows want.
    val fromStart = mode == UpNextOpen.FROM_BEGINNING
    val target = StreamsTarget(
        contentType = parentType,
        streamId = verifiedStreamId ?: item.episodeStreamId ?: parentId,
        title = targetTitle,
        displayName = upNextPlayerDisplayName(
            cardTitle = item.title,
            showTitle = item.showTitle,
            composedTitle = targetTitle,
            hasArtwork = !item.poster.isNullOrBlank()
        ),
        season = item.season,
        episode = namedEpisode,
        resumePositionMs = if (fromStart) 0L else item.startPositionMs,
        startFromBeginning = fromStart,
        runtimeMinutes = item.runtimeMinutes?.takeIf { it > 0 }
    )

    when (mode) {
        // No target: Detail is the destination, not a launchpad for the episode.
        UpNextOpen.DETAILS -> onOpenDetails(detail)
        UpNextOpen.STREAMS -> onOpenStreams(detail, target, item.poster)
        UpNextOpen.RESUME,
        UpNextOpen.FROM_BEGINNING -> onOpenDetailTarget(detail, target, item.poster)
    }
}
