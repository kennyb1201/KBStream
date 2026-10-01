package com.kennyb1201.kbstream.ui.kb

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kennyb1201.kbstream.data.kb.BrowseHomeRail
import com.kennyb1201.kbstream.data.kb.BrowseHomeShortcut
import com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts
import com.kennyb1201.kbstream.data.kb.BrowseRowPlacement
import com.kennyb1201.kbstream.data.kb.KBCollectionProfile
import com.kennyb1201.kbstream.data.kb.KBFolder
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.kb.browseHomeRails
import com.kennyb1201.kbstream.data.kb.browseRailArrangementOf
import com.kennyb1201.kbstream.data.kb.browseRowPlacement
import com.kennyb1201.kbstream.data.kb.chipKey
import com.kennyb1201.kbstream.data.tmdb.BrowseShortcutArt
import com.kennyb1201.kbstream.ui.components.BrandMarkLogo
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.posterBorderModifier
import com.kennyb1201.kbstream.ui.home.Rail
import com.kennyb1201.kbstream.ui.home.RailHorizontalStartPadding
import com.kennyb1201.kbstream.ui.home.TvSafeAreaHorizontal
import com.kennyb1201.kbstream.ui.theme.CardShape
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Placement of imported KB collections among the addon catalog rails on
 * Home. A collection anchors to the addon rail it precedes in the stored
 * merged order (from the Collections manager), pinned collections lead
 * right after the HARDCODED rails (Top Today, or the kids "Top Kids"
 * pair — never above them), and unarranged collections render after the
 * last addon rail.
 */
object KBHomeSlots {

    fun buildMergedEntries(
        context: android.content.Context,
        rails: List<Rail>,
        state: KBHomeViewModel.UiState
    ): List<HomeEntry> {
        val addonEntries = rails.mapIndexed { index, rail ->
            HomeEntry.AddonRail(rail, index)
        }
        val collections = state.collections
        // NOTE: no early return when there are no collections. The home
        // manager arranges ADDON rails through this same order, so bailing
        // out here made every reorder a no-op on Home for anyone without an
        // imported collection profile — the dialog (which reads the order
        // prefs directly) showed the new arrangement while Home kept the
        // loader's default order. The walk below is already collection-
        // agnostic: with none loaded it simply places the addon rails by the
        // stored arrangement, falling back to loader order for the rest.
        val arrangement = state.arrangement
        // Never-arranged collections are HIDDEN by default: they only
        // appear on Home after the user enables them in the home manager
        // (Add-ons -> HOME -> Show). Legacy profiles already arranged keep
        // their saved state.
        val arrangedKeys = arrangement.pinned.toSet() +
            arrangement.order.toSet() + arrangement.hiddenSet
        val effectiveHidden = arrangement.hiddenSet + collectionKeysNeedingDefault(
            context, state.collections, arrangedKeys
        )
        val hidden = effectiveHidden
        val pinnedKeys = arrangement.pinned.toSet()

        // The browse-menu chips mirrored to Home, as one rail per KIND:
        // genres and tags together, services and networks together, then
        // studios, decades and collections each on their own. They are neither
        // collections nor addon catalogs, and they are the rails whose default
        // is VISIBLE: a never-arranged collection waits in the home manager
        // until Show is pressed, but a rail the viewer just filled from a
        // chip's long-press has to be somewhere they can see, or the action
        // reads as having done nothing.
        //
        // Each rail carries its own arrangement key, so it is pinned and moved
        // on its own. An arrangement that still names the single shared row
        // this replaced applies to every rail that has no arrangement of its
        // own - see browseRailArrangementOf.
        val legacyBrowseKey = BrowseHomeShortcuts.LEGACY_ROW_KEY
        val placedBrowse = browseHomeRails(state.browseShortcuts)
            .mapNotNull { rail ->
                val flags = browseRailArrangementOf(
                    key = rail.key,
                    legacyKey = legacyBrowseKey,
                    pinnedKeys = pinnedKeys,
                    order = arrangement.order,
                    hiddenSet = hidden
                )
                val placement = browseRowPlacement(
                    shortcuts = rail.shortcuts,
                    hidden = flags.hidden,
                    pinned = flags.pinned,
                    arranged = flags.arranged
                )
                if (placement == BrowseRowPlacement.NONE) {
                    null
                } else {
                    HomeEntry.BrowseRail(rail) to placement
                }
            }
        val browseEntries = placedBrowse.map { (entry, _) -> entry }
        val browseKeys = browseEntries.mapTo(mutableSetOf()) { entry ->
            entry.key
        }

        /**
         * The browse rails one arrangement key stands for. A rail's own key
         * names exactly one; the legacy shared row's key names the rails that
         * carry no arrangement of their own, in rail order - which is how an
         * install that pinned or moved the old row keeps its rails where it
         * put them.
         */
        fun browseEntriesFor(key: String): List<HomeEntry> {
            val direct = browseEntries.filter { entry -> entry.key == key }
            if (direct.isNotEmpty()) return direct
            if (key != legacyBrowseKey) return emptyList()
            return browseEntries.filter { entry ->
                entry.key !in pinnedKeys && !arrangement.order.contains(entry.key)
            }
        }

        val collectionByKey = LinkedHashMap<String, KBCollectionProfile>()
        for (collection in collections) {
            collectionByKey.putIfAbsent(
                key(context, collection),
                collection
            )
        }
        val addonKeyByRail = rails.associate { rail ->
            rail to KBHomeOrderPrefs.addonKey(rail.baseUrl, rail.type, rail.catalogId)
        }

        // Top Today rails are hard-pinned by the home rail loader
        // (loadPinnedTopTodayRails) to render ABOVE every addon rail. The
        // merged arrangement must never demote them into the middle/tail,
        // or a stored order key for any other rail pushes them to the
        // bottom. Identify them by the manifest base URL and keep them
        // first, exactly like HomeViewModel does.
        val topTodayKeys = addonEntries
            .map { entry -> addonKeyByRail[(entry as HomeEntry.AddonRail).rail] }
            .filter { it?.startsWith("addon:https://toptoday.llamayu.com/") == true }
            .toSet()
        val topTodayRails = addonEntries.filter { entry ->
            addonKeyByRail[(entry as HomeEntry.AddonRail).rail] in topTodayKeys
        }

        // Hardcoded rails: rows this app builds itself instead of fetching them
        // from an addon manifest, which is exactly what a null baseUrl means
        // (the kids-profile "Top Kids Movies / Shows" pair). They have no
        // manifest to key them against, so they can never be arranged by the
        // user — and without this they fell through to the tail, where a
        // PINNED collection displaced them from the top of Home. They belong
        // with the Top Today rows: hardcoded first, then whatever is pinned.
        val hardcodedRails = addonEntries.filter { entry ->
            (entry as HomeEntry.AddonRail).rail.baseUrl == null
        }
        val hardcodedKeys = hardcodedRails
            .map { entry -> addonKeyByRail[(entry as HomeEntry.AddonRail).rail] }
            .toSet()

        // Pinned block: collections first-class, but addon catalog keys can
        // be pinned too (manager's jump-to-top writes them here). Hidden
        // keys never render. Top Today rails stay ABOVE this block.
        val pinned = arrangement.pinned.flatMap { pinKey ->
            when {
                pinKey in hidden -> emptyList()
                // Top Today renders first unconditionally — never also in
                // the pinned block (would duplicate the rail).
                pinKey in topTodayKeys -> emptyList()
                pinKey == legacyBrowseKey || pinKey in browseKeys ->
                    browseEntriesFor(pinKey)
                pinKey.startsWith("kb:") ->
                    listOfNotNull(collectionByKey[pinKey]).map { HomeEntry.Collection(it) }
                else ->
                    addonEntries.filter { entry ->
                        addonKeyByRail[(entry as HomeEntry.AddonRail).rail] == pinKey
                    }
            }
        }
        val pinnedAddonKeys = arrangement.pinned
            .filter { it !in collectionByKey }
            .toSet()

        // Walk the stored merged order (pinned handled separately). A
        // collection key emits a collection entry; an addon key emits every
        // addon rail matching it (multiple addons can share a catalog id).
        // Top Today keys are skipped: those rails always lead the list.
        val middle = mutableListOf<HomeEntry>()
        for (key in arrangement.order) {
            if (key in pinnedKeys) continue
            if (key in topTodayKeys) continue
            if (key in hardcodedKeys) continue
            if (key in pinnedAddonKeys) continue
            if (key == legacyBrowseKey || key in browseKeys) {
                middle += browseEntriesFor(key)
                continue
            }
            val collection = collectionByKey[key]
            if (collection != null) {
                if (key !in hidden) {
                    middle += HomeEntry.Collection(collection)
                }
            } else {
                addonEntries.forEachIndexed { index, entry ->
                    val addon = entry as HomeEntry.AddonRail
                    if (addonKeyByRail[addon.rail] == key) {
                        middle += addonEntries[index]
                    }
                }
            }
        }

        // Defaults: addon rails keep their computed order; never-arranged
        // collections follow them in import order.
        val placedRails = middle.filterIsInstance<HomeEntry.AddonRail>().toSet()
        val tail = mutableListOf<HomeEntry>()
        for (entry in addonEntries) {
            val railKey = addonKeyByRail[(entry as HomeEntry.AddonRail).rail]
            if (entry !in placedRails &&
                railKey !in topTodayKeys &&
                railKey !in hardcodedKeys &&
                railKey !in pinnedAddonKeys
            ) {
                tail += entry
            }
        }
        val placedCollections = middle.filterIsInstance<HomeEntry.Collection>().toSet()
        for ((key, collection) in collectionByKey) {
            if (key !in hidden &&
                key !in arrangement.pinned.toSet() &&
                HomeEntry.Collection(collection) !in placedCollections
            ) {
                tail += HomeEntry.Collection(collection)
            }
        }

        // The browse rails' default spot: directly under the Top Today rows,
        // which is where the rest of Home's doors to the catalog begin. Only
        // a rail the user has never touched lands here - once it is pinned or
        // placed in the stored order, the walk above owns where it sits.
        val defaultBrowse = placedBrowse
            .filter { (_, placement) -> placement == BrowseRowPlacement.BELOW_TOP_TODAY }
            .map { (entry, _) -> entry }

        return topTodayRails + defaultBrowse + hardcodedRails + pinned + middle + tail
    }

    /**
     * Collection keys that have never been arranged anywhere: these default
     * to hidden so a fresh import stays off Home until Show is pressed in
     * the home manager.
     */
    private fun collectionKeysNeedingDefault(
        context: android.content.Context,
        collections: List<KBCollectionProfile>,
        arrangedKeys: Set<String>
    ): Set<String> = collections
        .map { key(context, it) }
        .filter { it !in arrangedKeys }
        .toSet()

    private fun key(context: android.content.Context, collection: KBCollectionProfile): String =
        KBHomeOrderPrefs.resolveArrangementKey(
            context,
            collection.id,
            collection.title
        )
}

/**
 * One Home entry: an addon catalog rail, an imported collection, or the
 * shared Browse row.
 */
sealed class HomeEntry {
    // sourceIndex preserves the original rails position so two rails that
    // ever share addon/catalog/type still get unique LazyColumn keys.
    data class AddonRail(val rail: Rail, val sourceIndex: Int) : HomeEntry()
    data class Collection(val collection: KBCollectionProfile) : HomeEntry()

    /**
     * One rail of browse chips the viewer mirrored to Home.
     *
     * It carries the chips rather than a rendered result because the tiles
     * open the app's own discover screens (genre, service/network, studio,
     * collection, decade) - the rail is a shortcut shelf, not a catalog copy,
     * so there is nothing to fetch and nothing to keep in sync. The kind of
     * chip decides which rail it lands in, and each rail is arranged (pinned,
     * moved, hidden) on its own key.
     */
    data class BrowseRail(val rail: BrowseHomeRail) : HomeEntry() {
        /** Arrangement key of this rail. */
        val key: String get() = rail.key
    }
}

private val CollectionTileWidth = 210.dp
private val CollectionTileHeight = 118.dp

/** Tile size resolved from a folder's manifest tileShape. */
private data class FolderTileSize(val width: Dp, val height: Dp)

/**
 * KB's tile sizing: POSTER uses the poster card proportions, LANDSCAPE
 * is 16:9 of the poster width, SQUARE is a square of the poster width.
 * Case-insensitive: manifests in the wild mix "LANDSCAPE"/"landscape".
 */
private fun folderTileSize(tileShape: String?): FolderTileSize = when (tileShape?.uppercase()) {
    "POSTER" -> FolderTileSize(124.dp, 180.dp)
    "SQUARE" -> FolderTileSize(124.dp, 124.dp)
    else -> FolderTileSize(CollectionTileWidth, CollectionTileHeight)
}

/**
 * One imported KB collection on Home: its title plus a row of folder
 * tiles (hosted cover art, optional focus GIF overlay, per-folder tile
 * shape). Focusing a tile reports the folder so Home's hero can swap to
 * the folder's manifest backdrop + clearlogo, matching KB's
 * ModernPayload.CollectionFolder hero behavior. Clicking a folder opens
 * the folder screen with the collection's layout mode.
 */
@Composable
fun KBHomeCollectionRail(
    collection: KBCollectionProfile,
    onOpenFolder: (String) -> Unit,
    onFolderFocused: ((KBFolder) -> Unit)? = null
) {
    Column(
        modifier = Modifier.padding(
            start = TvSafeAreaHorizontal,
            top = 0.dp,
            bottom = 8.dp
        )
    ) {
        Text(
            text = collection.title.ifBlank { "Collections" },
            color = KBTextHi.copy(alpha = 0.94f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )

        // The SAME gutter a catalog rail uses (HomeScreen's TvSafeAreaHorizontal
        // plus RailHorizontalStartPadding), shared rather than retyped so the
        // two cannot drift again. The collection rail used to start its first
        // card at the safe-area inset ALONE, which left 12dp less room than
        // every other rail: the focused first tile scaled up and glowed
        // straight off the left edge of the screen, so a collection always
        // looked a little clipped compared with the catalog rail below it.
        LazyRow(
            contentPadding = PaddingValues(
                start = RailHorizontalStartPadding,
                end = TvSafeAreaHorizontal,
                top = 4.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = collection.folders,
                key = { it.id ?: it.title }
            ) { folder ->
                val folderId = folder.id
                if (folderId != null) {
                    CollectionFolderTile(
                        folder = folder,
                        onClick = { onOpenFolder(folderId) },
                        onFocus = onFolderFocused?.let { callback -> { callback(folder) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionFolderTile(
    folder: KBFolder,
    onClick: () -> Unit,
    onFocus: (() -> Unit)? = null
) {
    val tileSize = folderTileSize(folder.tileShape)
    var isFocused by remember { mutableStateOf(false) }
    val focusModifier = Modifier.onFocusChanged {
        isFocused = it.isFocused
        if (it.isFocused) onFocus?.invoke()
    }

    KBCard(
        onClick = onClick,
        modifier = focusModifier
    ) {
        Box(
            modifier = Modifier
                .width(tileSize.width)
                .height(tileSize.height)
                // The same faint edge a poster tile draws (Settings →
                // Interface → Poster Border): the folder tiles sit in a rail
                // on Home beside catalog posters, so without it a folder with
                // dark cover art reads as a borderless card among bordered
                // ones.
                .then(posterBorderModifier())
        ) {
            val coverUrl = folder.coverImageUrl?.takeIf { it.isNotBlank() }
            if (coverUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUrl).build(),
                    contentDescription = folder.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(KBSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = folder.coverEmoji?.takeIf { it.isNotBlank() }
                            ?: folder.title.take(1).uppercase(),
                        color = KBTextHi,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Focus GIF overlay (manifest focusGifUrl + focusGifEnabled):
            // animated on top of the cover only while the tile is focused,
            // fading in once loaded — KB's CollectionRowSection behavior
            // (the GIF is never a static poster; Coil still decodes its
            // first frame, so the URL is withheld until focus).
            val focusGifUrl = if (isFocused && folder.focusGifEnabled) {
                folder.focusGifUrl?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            if (focusGifUrl != null) {
                var gifLoaded by remember(focusGifUrl) { mutableStateOf(false) }
                val gifAlpha by animateFloatAsState(
                    targetValue = if (gifLoaded) 1f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "folderGifFadeIn"
                )
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(focusGifUrl).build(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    onSuccess = { gifLoaded = true },
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CardShape)
                        .graphicsLayer { alpha = gifAlpha }
                )
            }

            // Title scrim + label: KB's hideTitle flag drops both, letting
            // artwork (or focus GIF) stand alone.
            if (!folder.hideTitle) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    KBVoid.copy(alpha = 0f),
                                    KBVoid.copy(alpha = 0.75f)
                                )
                            )
                        )
                )
                Text(
                    text = folder.title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }
        }
    }
}

/**
 * A Browse rail: one landscape tile per browse chip the viewer mirrored to
 * Home, in the order they were added, for one kind of chip (genres and tags,
 * services and networks, studios, decades, collections).
 *
 * It is shaped like a collection rail on purpose - same tile size, same
 * gutters, same focus treatment - because it is the same kind of thing on
 * Home (a row that opens somewhere else), and it is the row the home manager
 * lets the viewer move or pin next to the collections.
 *
 * Each tile opens the screen its chip opens in Browse, not a copy of it: the
 * chip's own category decides (genre, keyword, service/network, studio,
 * collection, decade), so the row can never drift from what the browse browser
 * shows. Its artwork is resolved separately, after the row has drawn: a browse
 * chip carries no manifest cover and no curated art, so the tile's clearlogo -
 * and the backdrop the HERO swaps in while that tile is focused - come from one
 * TMDB discover lookup (see KBHomeViewModel and
 * TmdbRepository.getBrowseShortcutArt), and a shortcut TMDB has nothing for
 * simply keeps the wordmark it has always drawn.
 */
@Composable
fun KBHomeBrowseRail(
    shortcuts: List<BrowseHomeShortcut>,
    // Resolved tile/hero artwork per chip key. Empty on the first frame, then
    // filled in per shortcut as each lookup lands.
    artByKey: Map<String, BrowseShortcutArt> = emptyMap(),
    onOpenShortcut: (BrowseHomeShortcut) -> Unit,
    onShortcutFocused: ((BrowseHomeShortcut) -> Unit)? = null,
    // The rail's own name ("Genres & Tags", "Studios", ...). Defaulted to the
    // old shared row's word so a caller that predates the split still draws
    // something sensible.
    title: String = "Browse"
) {
    Column(
        modifier = Modifier.padding(
            start = TvSafeAreaHorizontal,
            top = 0.dp,
            bottom = 8.dp
        )
    ) {
        Text(
            text = title,
            color = KBTextHi.copy(alpha = 0.94f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(
                start = RailHorizontalStartPadding,
                end = TvSafeAreaHorizontal,
                top = 4.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = shortcuts,
                key = { it.chipKey() }
            ) { shortcut ->
                BrowseShortcutTile(
                    shortcut = shortcut,
                    art = artByKey[shortcut.chipKey()],
                    onClick = { onOpenShortcut(shortcut) },
                    onFocus = onShortcutFocused?.let { callback ->
                        { callback(shortcut) }
                    }
                )
            }
        }
    }
}

/**
 * One Browse tile: a landscape card that draws the shortcut's clearlogo on the
 * card's own surface, and nothing else.
 *
 * No backdrop on the tile. A category's resolved still reads as a mismatched
 * thumbnail under a wordmark rather than as a cover, and it no longer has a job
 * to do here: the caption that would have been under the logo is gone, and the
 * rail's own name (see [BrowseHomeRail.title]) is what says where the chip came
 * from. The backdrop is not wasted - the HERO still swaps in the resolved
 * backdrop and clearlogo while a tile is focused (see HomeScreen's
 * HomeHeroHost), which is where a full-bleed still belongs.
 *
 * The wordmark is the fallback, not the design. A shortcut TMDB has no logo
 * for - and every tile on the first frame, before the lookups land - keeps
 * the plain surface with the chip's name on it, so the row is legible
 * immediately and upgrades in place rather than holding the rail back for a
 * network walk.
 *
 * A brand mark is drawn through [BrandMarkImage] rather than as a plain image,
 * and that is not cosmetic: a service's or studio's mark is as often as not
 * the dark glyph-on-transparency TMDB's company and network endpoints default
 * to, which on this near-black card is a logo that is *there* and invisible.
 * The shared composable whitens exactly those, and reports the marks that
 * cannot be drawn at all - a featureless plate, art that never arrives - so
 * the wordmark stands in instead of an empty card.
 */
@Composable
private fun BrowseShortcutTile(
    shortcut: BrowseHomeShortcut,
    art: BrowseShortcutArt?,
    onClick: () -> Unit,
    onFocus: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusModifier = Modifier.onFocusChanged {
        isFocused = it.isFocused
        if (it.isFocused) onFocus?.invoke()
    }

    // The brand's WHOLE candidate list when it has one (see BrowseShortcutArt):
    // the tile must be able to step past a mark that cannot be drawn, or a
    // service whose top mark is a blank plate shows its name instead of a logo.
    // A genre / keyword / decade has only the single spotlight wordmark.
    val clearlogoUrls = art?.clearlogoUrls.orEmpty()
        .ifEmpty {
            listOfNotNull(art?.clearlogoUrl?.takeIf { it.isNotBlank() })
        }
    // Set when every resolved mark turns out to be undrawable on this surface:
    // the chip's own name is then the tile, rather than a blank card that
    // looks like the artwork is still loading.
    var markUnusable by remember(clearlogoUrls) { mutableStateOf(false) }

    KBCard(
        onClick = onClick,
        modifier = focusModifier
    ) {
        Box(
            modifier = Modifier
                .width(CollectionTileWidth)
                .height(CollectionTileHeight)
                .background(KBSurface)
                // The faint edge every poster tile draws (Settings →
                // Interface), so a browse/network chip sits in the rail with
                // the same border as the catalog posters around it.
                .then(posterBorderModifier()),
            contentAlignment = Alignment.Center
        ) {
            if (clearlogoUrls.isEmpty() || markUnusable) {
                // No clearlogo (no art at all, a category TMDB has no logo
                // for, or the first frame): the chip's own name is the mark.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 10.dp)
                ) {
                    Text(
                        text = shortcut.name.uppercase(),
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                BrandMarkLogo(
                    urls = clearlogoUrls,
                    contentDescription = shortcut.name,
                    onUnusable = { markUnusable = true },
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(CollectionTileHeight * 0.5f)
                )
            }
        }
    }
}
