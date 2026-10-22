package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.kennyb1201.kbstream.data.kb.BrowseHomeRail
import com.kennyb1201.kbstream.data.kb.BrowseHomeShortcut
import com.kennyb1201.kbstream.data.kb.chipKey
import com.kennyb1201.kbstream.data.tmdb.BrowseShortcutArt
import com.kennyb1201.kbstream.data.kb.KBCollectionProfile
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.kb.KBFolder
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.spoiler.SpoilerFree
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.BrandMarkImage
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
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.LandscapeCard
import com.kennyb1201.kbstream.ui.components.touch.MobileSectionHeader
import com.kennyb1201.kbstream.ui.components.touch.MobileSpacing
import com.kennyb1201.kbstream.ui.components.touch.PosterCard
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.touch.rememberTouchPosterSize
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.home.HomeViewModel
import com.kennyb1201.kbstream.ui.home.Rail
import com.kennyb1201.kbstream.ui.home.UpNextBadge
import com.kennyb1201.kbstream.ui.home.UpNextItem
import com.kennyb1201.kbstream.ui.home.UpcomingEpisode
import com.kennyb1201.kbstream.ui.home.formatTimeLeft
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
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import com.kennyb1201.kbstream.ui.theme.OswaldFamily
import kotlinx.coroutines.CoroutineScope
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
        // Settings' "Hide the Upcoming rail": the Upcoming schedule row is off
        // Home. The same synced pref filters unreleased titles out of the
        // catalog rails inside HomeViewModel; this is the row itself, which is
        // what the phone's own label promises.

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

        val upNextRaw by viewModel.upNext.collectAsStateWithLifecycle()
        // Continue Watching and Upcoming come from history and Simkl rather than
        // from the rails, so they are filtered here as well (see HomeScreen).
        val upNext = remember(upNextRaw, hiddenTitleKeys) {
            upNextRaw.filterNot { item ->
                HiddenTitles.hides(
                    hiddenTitleKeys,
                    item.parentType ?: if (item.season != null) "series" else "movie",
                    item.showTitle ?: item.title,
                    null,
                    item.parentId,
                    item.id
                )
            }
        }
        val upcoming by viewModel.upcomingSchedule.collectAsStateWithLifecycle()

        // KB collections (imported profiles), the Browse chips mirrored to
        // Home, and the rail arrangement that interleaves them with the
        // catalogs - the same slot builder the TV screen uses, so a rail lands
        // where the home manager says on both screens.
        val kbViewModel: KBHomeViewModel = viewModel()
        val kbState by kbViewModel.state.collectAsStateWithLifecycle()
        val hideUpcomingRail = remember(activeProfileId?.id, syncRevision) {
            AppPreferences.getHomeRailHideUpcoming(context)
        }
        // The row the screen actually draws - everything below asks this, not
        // the raw schedule, or "All rails are hidden" would claim the viewer
        // hid rails when the only thing they hid was Upcoming.
        val upcomingVisible = upcoming.isNotEmpty() && !hideUpcomingRail
        val builtinWithContent = remember(upNext.isNotEmpty(), upcomingVisible) {
            buildSet {
                if (upNext.isNotEmpty()) add(KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING)
                if (upcomingVisible) add(KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE)
            }
        }
        val homeEntries = remember(rails, kbState, builtinWithContent) {
            KBHomeSlots.buildMergedEntries(context, rails, kbState, builtinWithContent)
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

        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val error by viewModel.error.collectAsStateWithLifecycle()

        val heroMeta by viewModel.heroMeta.collectAsStateWithLifecycle()
        val heroBackdropUrl by viewModel.heroBackdropUrl.collectAsStateWithLifecycle()
        val heroLogoUrl by viewModel.heroLogoUrl.collectAsStateWithLifecycle()

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

                heroMeta?.let { hero ->
                    item(key = "hero", contentType = "hero") {
                        MobileHero(
                            meta = hero,
                            backdropUrl = heroBackdropUrl,
                            logoUrl = heroLogoUrl,
                            onClick = { onItemClick(hero.asPreview()) }
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
                                        MobileSection(title = upcomingTitle) {
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

                            // Any other built-in key is a row the add-on loader
                            // supplies as a catalog; it arrives as an AddonRail.
                            else -> Unit
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
                                    LazyRow(
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
                                                    onClick = { onItemClick(meta) },
                                                    onLongClick = {
                                                        menuTarget =
                                                            RailMenuTarget(meta, rail)
                                                    },
                                                    modifier = Modifier
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
                                                    onClick = { onItemClick(meta) },
                                                    onLongClick = {
                                                        menuTarget =
                                                            RailMenuTarget(meta, rail)
                                                    },
                                                    modifier = Modifier
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

                // Every rail the loader produced has been hidden by the viewer
                // (HD-P2-5). The merged list is empty while the pieces it is
                // built from are not, which separates this from a first run
                // with no catalogs at all - there, there is nothing to manage
                // yet. Without this card Home was the hero and nothing else.
                val allRailsHidden = !isLoading && homeEntries.isEmpty() &&
                    (rails.isNotEmpty() || upNext.isNotEmpty() || upcomingVisible)
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

                if (isLoading && rails.isEmpty() && upNext.isEmpty() && !upcomingVisible) {
                    item(key = "loading", contentType = "skeleton") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = KBAccent)
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
                            KBButton(label = "RETRY") {
                                viewModel.onHomeResumed()
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
                    hideTarget = hideTarget(
                        item.showTitle ?: item.title,
                        mediaType,
                        item.poster,
                        listOf(item.parentId, item.id)
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
                    actions = listOf(
                        PosterContextAction(
                            label = "Open in Grid",
                            description = "Browse this whole catalog as a poster grid"
                        ) {
                            menuTarget = null
                            onOpenCatalogGrid(target.rail)
                        },
                        PosterContextAction(
                            label = "Go to Details",
                            description = "Open this title's detail page"
                        ) {
                            menuTarget = null
                            onItemClick(target.meta)
                        },
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
                    ),
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
    val rail: Rail
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
            fontFamily = OswaldFamily,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * A full-width hero: the backdrop, the clearlogo (with the shared dark-surface
 * treatment) or the title, and the short meta line. Tapping it opens the title.
 */
@Composable
private fun MobileHero(
    meta: Meta,
    backdropUrl: String?,
    logoUrl: String?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    val logo = logoUrl?.takeIf { it.isNotBlank() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
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
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            KBVoid.copy(alpha = 0.45f),
                            KBVoid.copy(alpha = 0.94f)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 14.dp),
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
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            meta.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A section heading plus its carousel, on the screen's 16dp edge. */
@Composable
private fun MobileSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(MobileSpacing.gap)) {
        MobileSectionHeader(title)
        content()
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

/** The Detail-page preview the hero opens, from the detail model it carries. */
private fun Meta.asPreview(): MetaPreview =
    MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster,
        background = background,
        description = description,
        logo = logo,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating
    )

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
