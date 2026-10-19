package com.kennyb1201.kbstream.ui.mobile

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.imageOverridePosterUrl
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.LandscapeCard
import com.kennyb1201.kbstream.ui.components.touch.PosterCard
import com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.home.HomeViewModel
import com.kennyb1201.kbstream.ui.home.Rail
import com.kennyb1201.kbstream.ui.home.UpNextItem
import com.kennyb1201.kbstream.ui.home.UpcomingEpisode
import com.kennyb1201.kbstream.ui.home.upNextPlayerDisplayName
import com.kennyb1201.kbstream.ui.kb.HomeEntry
import com.kennyb1201.kbstream.ui.kb.KBHomeSlots
import com.kennyb1201.kbstream.ui.kb.KBHomeViewModel
import com.kennyb1201.kbstream.ui.kb.collectionRailKey
import com.kennyb1201.kbstream.ui.kb.collectionRailTiles
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
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
 * `tileShape`; Browse rows render as chips and can be removed with a long
 * press; landscape mode (the synced pref) draws backdrop+clearlogo cards with
 * their rank.
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
    onSearch: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchProfile: () -> Unit,
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
        // The rail card shape is one synced preference for the whole screen,
        // exactly as on TV.
        val landscapeCards = remember(activeProfileId?.id, syncRevision) {
            AppPreferences.getHomeLandscapeCards(context)
        }

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
        val builtinWithContent = remember(upNext.isNotEmpty(), upcoming.isNotEmpty()) {
            buildSet {
                if (upNext.isNotEmpty()) add(KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING)
                if (upcoming.isNotEmpty()) add(KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE)
            }
        }
        val homeEntries = remember(rails, kbState, builtinWithContent) {
            KBHomeSlots.buildMergedEntries(context, rails, kbState, builtinWithContent)
        }

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
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                item(key = "topBar") {
                    MobileTopBar(
                        onSearch = onSearch,
                        onOpenGuide = onOpenGuide,
                        onOpenLibrary = onOpenLibrary,
                        onOpenSettings = onOpenSettings,
                        onSwitchProfile = onSwitchProfile
                    )
                }

                heroMeta?.let { hero ->
                    item(key = "hero") {
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
                                    item(key = "continueWatching") {
                                        MobileSection(title = "Continue Watching") {
                                            LazyRow(
                                                contentPadding =
                                                    PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(12.dp)
                                            ) {
                                                items(upNext, key = { "cw:" + it.id }) { item ->
                                                    ContinueWatchingCard(
                                                        item = item,
                                                        onClick = {
                                                            openUpNextVerified(
                                                                item = item,
                                                                viewModel = viewModel,
                                                                scope = scope,
                                                                onOpen = onOpenDetailTarget
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
                                if (upcoming.isNotEmpty()) {
                                    item(key = "upcoming") {
                                        MobileSection(title = "Upcoming") {
                                            LazyRow(
                                                contentPadding =
                                                    PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(12.dp)
                                            ) {
                                                items(
                                                    upcoming,
                                                    key = { "up:" + it.id }
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
                            key = "kb:" + (entry.collection.id ?: entry.collection.title)
                        ) {
                            MobileCollectionSection(
                                collection = entry.collection,
                                onOpenFolder = onOpenKBFolder
                            )
                        }

                        is HomeEntry.BrowseRail -> item(key = "browse:" + entry.key) {
                            MobileBrowseShortcutSection(
                                rail = entry.rail,
                                onOpen = onOpenBrowseShortcut,
                                onLongPress = kbViewModel::removeBrowseShortcut
                            )
                        }

                        is HomeEntry.AddonRail -> {
                            val rail = entry.rail
                            item(
                                key = "rail:" + rail.addonName + ":" + rail.catalogId +
                                    ":" + rail.type + ":" + entry.sourceIndex
                            ) {
                                MobileSection(
                                    title = mobileRailTitle(
                                        catalogName = rail.catalogName,
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
                                            }
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
                                                // (see Rail.landscapeArt).
                                                val art = rail.landscapeArt[meta.id]
                                                LandscapeCard(
                                                    backdropUrl = art?.first
                                                        ?: meta.background
                                                        ?: meta.poster,
                                                    logoUrl = art?.second,
                                                    fallbackTitle = meta.name,
                                                    contentDescription = meta.name,
                                                    isWatched = watched,
                                                    isPartiallyWatched =
                                                        partiallyWatched,
                                                    rank = if (rail.ranked) index + 1 else null,
                                                    onClick = { onItemClick(meta) },
                                                    onLongClick = {
                                                        menuTarget =
                                                            RailMenuTarget(meta, rail)
                                                    },
                                                    modifier = Modifier
                                                        .width(224.dp)
                                                        .height(126.dp)
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
                                                        .width(120.dp)
                                                        .height(180.dp)
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
                    (rails.isNotEmpty() || upNext.isNotEmpty() || upcoming.isNotEmpty())
                if (allRailsHidden) {
                    item(key = "all-hidden") {
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

                if (isLoading && rails.isEmpty() && upNext.isEmpty() && upcoming.isEmpty()) {
                    item(key = "loading") {
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
                    item(key = "error") {
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
                                onOpen = onOpenDetailTarget
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
                key = { index, tile -> collectionRailKey(index, tile) }
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
 * The Browse chips the viewer mirrored to Home, as one chip row per kind.
 *
 * TV draws these as tiles; a phone gets chips, which is what the Search screen's
 * browse strip already uses. A long press removes the chip from Home - the TV's
 * tile menu action, and the same pref blob either way.
 */
@Composable
private fun MobileBrowseShortcutSection(
    rail: BrowseHomeRail,
    onOpen: (BrowseHomeShortcut) -> Unit,
    onLongPress: (BrowseHomeShortcut) -> Unit
) {
    if (rail.shortcuts.isEmpty()) return

    MobileSection(title = rail.title.ifBlank { "Browse" }) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rail.shortcuts, key = { it.categoryKey + ":" + it.id }) { shortcut ->
                Text(
                    text = shortcut.name,
                    color = KBAccent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(KBShapePanel)
                        .background(KBAccent.copy(alpha = 0.18f))
                        .combinedClickable(
                            onClick = { onOpen(shortcut) },
                            onLongClick = { onLongPress(shortcut) }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
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
 * The phone top bar: the wordmark and the four places the TV's hidden top bar
 * leads to.
 *
 * The TV raises that bar with Up from the first rail because its screen is one
 * focus column; a phone has no such gesture, so the bar is simply always there.
 * The actions are the same four (search, guide, library, settings) plus the
 * profile switch, which the TV puts in the same row.
 */
@Composable
private fun MobileTopBar(
    onSearch: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchProfile: () -> Unit
) {
    val activeProfile by ProfileManager.activeProfile.collectAsStateWithLifecycle()
    val profileName = activeProfile?.name?.takeIf { it.isNotBlank() } ?: "PROFILES"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "KBSTREAM",
            color = KBTextHi,
            fontFamily = OswaldFamily,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = profileName,
            color = KBAccent,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(KBShapeCard)
                .clickable(onClick = onSwitchProfile)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = KBTextHi)
        }
        IconButton(onClick = onOpenGuide) {
            Icon(Icons.Filled.LiveTv, contentDescription = "TV guide", tint = KBTextHi)
        }
        IconButton(onClick = onOpenLibrary) {
            Icon(Icons.Filled.VideoLibrary, contentDescription = "Library", tint = KBTextHi)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = KBTextHi)
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        content()
    }
}

/**
 * A Continue Watching card: the episode still with its clearlogo, the show and
 * episode under it, and the resume progress as a thin bar.
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
    val hidesEpisodeTitle = rememberHidesEpisodeTitle(inProgress = item.startPositionMs > 0)
    Column(
        modifier = Modifier.width(250.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LandscapeCard(
            backdropUrl = item.episodeThumbnail ?: item.backdrop ?: item.poster,
            logoUrl = item.clearLogo,
            fallbackTitle = title,
            contentDescription = title,
            isWatched = false,
            onClick = onClick,
            onLongClick = onLongClick,
            isPartiallyWatched = false,
            modifier = Modifier
                .width(250.dp)
                .height(141.dp)
        )
        item.progressPercent?.takeIf { it > 0f }?.let { progress ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(KBSurfaceRaised)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(KBAccent)
                )
            }
        }
        Text(
            text = title,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!hidesEpisodeTitle) {
            upNextEpisodeLabel(item)?.let { label ->
                Text(
                    text = label,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** An Upcoming card: the show's backdrop, its air-date line, then the episode. */
@Composable
private fun UpcomingCard(
    episode: UpcomingEpisode,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(250.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LandscapeCard(
            backdropUrl = episode.backdrop ?: episode.poster,
            logoUrl = null,
            fallbackTitle = episode.title,
            contentDescription = episode.title,
            isWatched = false,
            onClick = onClick,
            isPartiallyWatched = false,
            modifier = Modifier
                .width(250.dp)
                .height(141.dp)
        )
        Text(
            text = episode.airDateLabel,
            color = KBAccent,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
        Text(
            text = episode.title,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = buildString {
                append("S").append(episode.season)
                append(" E").append(episode.episode)
                episode.episodeTitle?.takeIf { it.isNotBlank() }?.let {
                    append(" · ").append(it)
                }
            },
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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

/** `S2 E5 · Episode name`, from the fields the Up Next item actually has. */
private fun upNextEpisodeLabel(item: UpNextItem): String? {
    item.subtitle?.takeIf { it.isNotBlank() }?.let { return it }
    val season = item.season
    val episode = item.episode
    return if (season != null && episode != null) {
        buildString {
            append("S").append(season)
            append(" E").append(episode)
            item.episodeTitle?.takeIf { it.isNotBlank() }?.let {
                append(" · ").append(it)
            }
        }
    } else {
        item.episodeTitle?.takeIf { it.isNotBlank() }
    }
}

/** The one-line meta under the hero's title. */
private fun heroMetaLine(meta: Meta): String =
    listOfNotNull(
        meta.releaseInfo?.takeIf { it.isNotBlank() },
        meta.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
        meta.type.replaceFirstChar { it.uppercase() }
    ).joinToString("  ·  ")

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
private fun openUpNextVerified(
    item: UpNextItem,
    viewModel: HomeViewModel,
    scope: CoroutineScope,
    onOpen: (MetaPreview, StreamsTarget, String?) -> Unit
) {
    val stored = item.episodeStreamId
    val type = item.parentType
    if (stored.isNullOrBlank() || type.isNullOrBlank()) {
        openUpNext(item, onOpen)
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
        openUpNext(item, onOpen, verifiedStreamId = verified)
    }
}

private fun openUpNext(
    item: UpNextItem,
    onOpenDetailTarget: (MetaPreview, StreamsTarget, String?) -> Unit,
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
        resumePositionMs = item.startPositionMs,
        runtimeMinutes = item.runtimeMinutes?.takeIf { it > 0 }
    )

    onOpenDetailTarget(detail, target, item.poster)
}
