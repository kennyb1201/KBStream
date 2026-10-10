package com.kennyb1201.kbstream.ui.kb

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.kennyb1201.kbstream.data.kb.mergedHomeRailKeys
import com.kennyb1201.kbstream.data.tmdb.BrowseShortcutArt
import com.kennyb1201.kbstream.ui.components.BrandMarkLogo
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.ui.components.posterBorderModifier
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.home.Rail
import com.kennyb1201.kbstream.ui.home.RailHorizontalStartPadding
import com.kennyb1201.kbstream.ui.home.TvSafeAreaHorizontal
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlin.math.abs

/**
 * Which built-in rails are emitted, in default order.
 *
 * Pure, and unit tested, because the rule is a rule and not glue: a hidden
 * built-in is gone, and so is one with nothing to show (an empty Continue
 * Watching was never drawn - the inline version tested `upNext.isNotEmpty()`
 * before emitting it, and this is that test). The order is [keys]'s, which the
 * caller passes as the built-in registry's own order - the default position an
 * arrangement falls back to.
 */
internal fun visibleBuiltinRailKeys(
    hidden: Set<String>,
    withContent: Set<String>,
    keys: List<String> = KBHomeOrderPrefs.BUILTIN_KEYS
): List<String> = keys.filter { key -> key !in hidden && key in withContent }

/**
 * The merged rail order Home draws: one arrangement for every rail on Home,
 * assembled from [mergedHomeRailKeys] over the same defaults list the home
 * manager builds. A rail is placed purely by its arrangement key's slot in
 * that order - built-ins (Continue Watching, Upcoming, the two Top Today rows),
 * browse rails, built catalogs, add-on catalogs and imported collections
 * alike. There is no per-family block any more: that is what let Home draw a
 * rail (an unarranged built-in, a Top Today row) somewhere the manager could
 * neither show nor aim at.
 */
object KBHomeSlots {

    /**
     * @param builtinWithContent the built-in rail keys that have something to
     *        show right now. A built-in rail carries no content of its own -
     *        HomeScreen renders it - so the entry is only emitted when the
     *        caller says there is content for it, which is exactly the rule
     *        Home applied when the same two rails were written inline (an empty
     *        Continue Watching was simply not drawn).
     */
    fun buildMergedEntries(
        context: android.content.Context,
        rails: List<Rail>,
        state: KBHomeViewModel.UiState,
        builtinWithContent: Set<String> = emptySet()
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
        // Built-in rails (Continue Watching, Upcoming Schedule). Rows this app
        // draws itself, so like the hardcoded kid rails they key against no
        // manifest - but unlike those they ARE arrangeable: the arrangement
        // names them, so they take a position from the stored order, and an
        // arrangement that says nothing about them leaves them at the front.
        // A hidden one, or one with nothing to show, is not emitted at all.
        val builtinKeys = KBHomeOrderPrefs.BUILTIN_KEYS
        val builtinSet = builtinKeys.toSet()
        val builtinsVisible = visibleBuiltinRailKeys(
            hidden = hidden,
            withContent = builtinWithContent
        )

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
        }        // A built catalog has no manifest to key against, so it keys by its own
        // id in the `custom:` family and is arranged like any other rail.
        // Everything else keys by its add-on URL exactly as before - EXCEPT an
        // APP-BUILT row, which keys by its BUILT-IN key (see
        // KBHomeOrderPrefs.builtinKeyForCatalogId): the two Top Today feed rows
        // and the profile rails the app builds itself (a kids profile's rows,
        // a guest profile's fixed set). That is what puts every one of
        // them in the one arrangement with the rest of Home: the manager lists
        // them as built-in rows, they can be moved, hidden and renamed, and
        // their content still comes from the feed the loader fetched.
        val addonKeyByRail = rails.associate { rail ->
            rail to (
                rail.customCatalogId
                    ?.let { KBHomeOrderPrefs.customCatalogKey(it) }
                    ?: KBHomeOrderPrefs.builtinKeyForCatalogId(rail.catalogId)
                    ?: KBHomeOrderPrefs.addonKey(rail.baseUrl, rail.type, rail.catalogId)
            )
        }

        // The loaded app-built rails by their built-in key, so the emission
        // below can draw the fetched rail (its content) at whichever slot the
        // arrangement gives that key, rather than a bare built-in placeholder -
        // which is what a Top Today row, a kids row and a guest row all need:
        // the key decides WHERE the rail sits, the fetcher decides what is in
        // it.
        val appBuiltAddonByKey = addonEntries
            .mapNotNull { entry ->
                val rail = (entry as HomeEntry.AddonRail).rail
                KBHomeOrderPrefs.builtinKeyForCatalogId(rail.catalogId)?.let { it to entry }
            }
            .toMap()


        // One list, and the SAME one the home manager draws and the reorder
        // core moves within (see mergedHomeRailKeys and
        // AddonsViewModel.homeRailDefaults): built-ins (the two app rows and
        // the two Top Today rows), then the browse rails, then the built
        // catalogs, then the add-on catalogs, then the collections. Home no
        // longer assembles a block of its own per family - that is exactly what
        // let it draw a rail (an unarranged built-in, a Top Today row) somewhere
        // the manager could neither show nor aim at.
        val defaults = buildList {
            addAll(builtinKeys)
            // Ordered, not [browseKeys] (a Set): the default order has to be the
            // same list the manager builds, or two unarranged browse rails swap
            // places between the two screens.
            addAll(browseEntries.map { it.key })
            addAll(addonKeyByRail.values.filter { KBHomeOrderPrefs.isCustomCatalogKey(it) })
            addAll(addonEntries.map { entry -> addonKeyByRail.getValue((entry as HomeEntry.AddonRail).rail) })
            addAll(collectionByKey.keys)
        }
        val mergedOrder = mergedHomeRailKeys(arrangement, defaults)

        // The manager's rename for one arrangement key, or null when it carries
        // none. A blank name is treated as "no override" (withRename never
        // stores one, so this is defensive).
        fun renamedTitle(key: String): String? =
            arrangement.renames[key]?.takeIf { it.isNotBlank() }

        return mergedOrder.flatMap { key ->
            if (key in hidden) return@flatMap emptyList()
            when {
                // An app-built row (a Top Today row, a kids row, a guest row):
                // draw the loaded rail (its content), at the slot the
                // arrangement gave its built-in key.
                appBuiltAddonByKey.containsKey(key) ->
                    listOf(
                        appBuiltAddonByKey.getValue(key).copy(
                            titleOverride = renamedTitle(key)
                        )
                    )

                key in builtinSet ->
                    if (key in builtinsVisible) {
                        listOf(HomeEntry.BuiltinRail(key))
                    } else {
                        emptyList()
                    }

                key == legacyBrowseKey || key in browseKeys -> browseEntriesFor(key)

                collectionByKey.containsKey(key) ->
                    listOf(HomeEntry.Collection(collectionByKey.getValue(key)))

                else -> addonEntries.mapNotNull { entry ->
                    val addon = entry as HomeEntry.AddonRail
                    if (addonKeyByRail[addon.rail] == key) {
                        addon.copy(titleOverride = renamedTitle(key))
                    } else {
                        null
                    }
                }
            }
        }.distinct()
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
    data class AddonRail(
        val rail: Rail,
        val sourceIndex: Int,
        /**
         * The viewer's rename for this rail, when the arrangement carries one
         * (the manager offers Rename on a built-in row - among them the Top
         * Today rows - and on a built catalog). Null means "draw the rail's own
         * name". An add-on catalog rail has no rename control, so it is always
         * null there.
         */
        val titleOverride: String? = null
    ) : HomeEntry()
    data class Collection(val collection: KBCollectionProfile) : HomeEntry()

    /**
     * A built-in rail (Continue Watching, Upcoming Schedule). It carries only
     * its arrangement key; HomeScreen renders the content, because the content
     * is this app's own rows rather than anything the loader fetched.
     */
    data class BuiltinRail(val key: String) : HomeEntry()

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
/**
 * The D-pad Up -> top bar hook for the rail drawn FIRST on Home.
 *
 * The hero spacer above the rails is inert, so the top bar is unreachable
 * except through a rail that consumes Up and opens it - and it has to be
 * exactly ONE rail, the one drawn first, or Up from a rail with another rail
 * above it jumps over that rail instead of moving focus to it. Home decides
 * which rail that is from the merged arrangement (see the top-rail note in
 * HomeScreen) and passes [openTopBar] only to that one; every other rail passes
 * null and leaves Up to the focus system, which moves focus up normally.
 *
 * Here rather than in HomeScreen because every rail kind that can own the hook
 * has to apply the same modifier, and two of them (the Browse and Collection
 * rows) live in this file.
 */
/**
 * True for the rail drawn FIRST in the merged Home order, and for nothing else.
 *
 * Its own function because the rule is all that decides which rail can open the
 * top bar, so it is the thing worth pinning with a test rather than the call
 * sites that apply it (see [homeTopRailUpHook]).
 */
internal fun isTopRailEntry(entryIndex: Int): Boolean = entryIndex == 0

internal fun Modifier.homeTopRailUpHook(
    requester: FocusRequester,
    openTopBar: ((FocusRequester) -> Unit)?
): Modifier = if (openTopBar == null) {
    this
} else {
    this.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
            openTopBar(requester)
            true
        } else {
            false
        }
    }
}

/**
 * How long D-pad Up has to be HELD, on any rail, before Home reveals its top
 * bar.
 *
 * The bar is otherwise reachable only from the rail drawn first (see
 * [homeTopRailUpHook]), so on a Home with a full catalog list it is one Up
 * press per rail - forty-nine catalogs down that is forty-nine presses to
 * reach SEARCH. The hold is the shortcut from anywhere, and a normal (short)
 * press still moves focus to the rail above, so nothing about vertical
 * navigation changes.
 *
 * Longer than the Select long press (KBCard's 450 ms) on purpose: Up is also
 * the key a viewer holds to skim several rails in one go, and reusing the
 * Select threshold turned that skim into the top bar opening over it. 700 ms is
 * past a skim and still well short of feeling unresponsive.
 *
 * Timed on the wall clock between KeyDown and KeyUp rather than from the
 * platform's key repeats, for the reason KBCard documents: plenty of TV remotes
 * and emulators never emit repeat KeyDown events at all, so a repeat-based hold
 * is a hold only on some hardware.
 */
internal const val HomeTopBarHoldMs = 700L

/** Whether a D-pad Up held for [heldMs] reveals Home's top bar. */
internal fun isTopBarHold(heldMs: Long): Boolean = heldMs >= HomeTopBarHoldMs

/**
 * The hold-Up -> top bar hook, applied to Home's RAILS LIST rather than to a
 * card.
 *
 * Preview key events travel down from the root to the focused element, so one
 * modifier on the list sees Up for every rail drawn inside it - every catalog
 * rail, every browse and collection row and both built-ins - and no rail kind
 * can forget to apply it, which is exactly the hole [homeTopRailUpHook] has for
 * the short press (it needs each rail kind to pass it on; see
 * HomeRailUpHookContractTest).
 *
 * KeyDown is never consumed: the press has to reach the focus system, because a
 * short press still means "move focus to the rail above". Only the KeyUp that
 * ends a long hold is swallowed, so the release cannot also fight the focus
 * move its own press already made.
 *
 * [restoreTargetAtPress] is read once, when the press begins: that is the card
 * the viewer was on, and it is what the bar hands focus back to when it closes.
 * Reading it at KeyUp instead would return focus to whichever card focus had
 * climbed to during the hold - i.e. not where the viewer was.
 */
@Composable
internal fun rememberHomeTopBarHoldUpHook(
    restoreTargetAtPress: () -> FocusRequester?,
    onOpenTopBar: (FocusRequester?) -> Unit
): Modifier {
    var pressStartTime by remember {
        mutableLongStateOf(0L)
    }
    var restoreTarget by remember {
        mutableStateOf<FocusRequester?>(null)
    }

    return Modifier.onPreviewKeyEvent { event ->
        when {
            event.key != Key.DirectionUp -> false

            event.type == KeyEventType.KeyDown -> {
                if (event.nativeKeyEvent.repeatCount == 0) {
                    pressStartTime = System.currentTimeMillis()
                    restoreTarget = restoreTargetAtPress()
                }
                false
            }

            event.type == KeyEventType.KeyUp -> {
                // 0 means no press of ours is open - a release the list never
                // saw the press for (focus arrived inside it mid-press, say).
                // Timed against a zeroed start it would read as a hold of
                // fifty-odd years and open the bar on a stray release.
                val heldMs = if (pressStartTime == 0L) {
                    0L
                } else {
                    System.currentTimeMillis() - pressStartTime
                }
                pressStartTime = 0L
                if (isTopBarHold(heldMs)) {
                    onOpenTopBar(restoreTarget)
                    true
                } else {
                    false
                }
            }

            else -> false
        }
    }
}

/**
 * The tiles a collection rail can actually draw, in order.
 *
 * A folder without an id can't be opened ([KBHomeCollectionRail] needs the id
 * to navigate) and can't be keyed, so it is dropped here rather than inside the
 * item block. Dropping it inside left the keyed, focusable slot behind while
 * emitting nothing: an invisible position in the middle of the rail. With one
 * sitting between real tiles, the lazy list's item state and focus search
 * disagreed about where that position's content was, which is what locked the
 * row up and made it oscillate - data-dependently, silently, at the same
 * position every time.
 *
 * Filtering at the rail (rather than in the ViewModel) keeps the change local:
 * the folder screen's own loading path also reads the collection's folder list
 * and already resolves by id, so the ViewModel's shape does not need to change.
 *
 * The id is carried alongside the folder so the tile's click target is a
 * non-null `String` - the "can't be opened without an id" invariant is then in
 * the type instead of in a `!!`.
 */
internal fun collectionRailTiles(folders: List<KBFolder>): List<Pair<String, KBFolder>> =
    folders.mapNotNull { folder -> folder.id?.let { id -> id to folder } }

/**
 * The lazy-list key for the tile at [index].
 *
 * Unique per POSITION, not merely per id. The key used to be `id ?: title`, so
 * two folders sharing an id (or a null id beside a blank title) produced
 * identical keys - and duplicate keys make a lazy list lose item identity, so a
 * tile's state (focus included) attaches to whichever item the key was last
 * bound to and the row bounces between the confused items. The position suffix
 * makes a collision impossible however malformed the manifest is, while
 * remaining stable for a given list, so a rail whose folders all have unique
 * ids is keyed the same way it was before apart from the suffix.
 */
internal fun collectionRailKey(index: Int, tile: Pair<String, KBFolder>): String =
    "${tile.first}#$index"

// LocalBringIntoViewSpec is still experimental; the rails that install their
// own landing spec opt in the same way (see HomeScreen's rail column).
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KBHomeCollectionRail(
    collection: KBCollectionProfile,
    onOpenFolder: (String) -> Unit,
    onFolderFocused: ((KBFolder) -> Unit)? = null,
    // Non-null only for the rail Home draws first (see [homeTopRailUpHook]).
    onUpPressed: ((FocusRequester) -> Unit)? = null
) {
    Column(
        modifier = Modifier.padding(
            start = TvSafeAreaHorizontal,
            top = 0.dp,
            bottom = 8.dp
        )
    ) {
        // The app's rail heading, not a fourth treatment of its own: this was a
        // raw titleMedium at 94% white with its own padding, so a collection's
        // title sat a little smaller and tighter than the same rail's title on
        // every other Home row (see KBSectionHeader).
        KBSectionHeader(
            title = collection.title.ifBlank { "Collections" },
            modifier = Modifier.padding(top = 4.dp)
        )

        // The SAME gutter a catalog rail uses (HomeScreen's TvSafeAreaHorizontal
        // plus RailHorizontalStartPadding), shared rather than retyped so the
        // two cannot drift again. The collection rail used to start its first
        // card at the safe-area inset ALONE, which left 12dp less room than
        // every other rail: the focused first tile scaled up and glowed
        // straight off the left edge of the screen, so a collection always
        // looked a little clipped compared with the catalog rail below it.

        val listState = rememberLazyListState()

        // Only the drawable tiles: see [collectionRailTiles] for why the
        // null-id folders are dropped here and not inside the item block.
        val tiles = remember(collection) { collectionRailTiles(collection.folders) }

        // This rail's own focus landing (see [CollectionBringIntoViewSpec]):
        // supplied around THIS LazyRow, not on the screen, so no other rail's
        // bring-into-view is re-tuned. The spec the enclosing column installs
        // is built for a LazyColumn of RAILS (a fixed header inset, a landing
        // line derived from the focused row's height); a tile inside a
        // horizontal row is not what it was written for, and letting it decide
        // where the tile comes to rest is how the row ended up chasing its own
        // scroll.
        val density = LocalDensity.current
        val bringIntoViewSpec = remember(density) {
            CollectionBringIntoViewSpec(insetPx = with(density) { KBFocusRowInset.toPx() })
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(
                    start = RailHorizontalStartPadding,
                    end = TvSafeAreaHorizontal,
                    top = 4.dp,
                    bottom = 12.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // itemsIndexed rather than items: the key has to include the
                // position to stay unique (see [collectionRailKey]).
                itemsIndexed(
                    items = tiles,
                    key = { index, tile -> collectionRailKey(index, tile) }
                ) { _, (folderId, folder) ->
                    // Every item emits a tile - the null guard and its empty
                    // branch are gone, so no position can be a focusable no-op.
                    val requester = remember { FocusRequester() }
                    CollectionFolderTile(
                        folder = folder,
                        onClick = { onOpenFolder(folderId) },
                        onFocus = onFolderFocused?.let { callback -> { callback(folder) } },
                        modifier = Modifier
                            .focusRequester(requester)
                            .homeTopRailUpHook(requester, onUpPressed)
                    )
                }
            }
        }
    }
}

/**
 * The collection rail's focus landing: keep [KBFocusRowInset] of room between
 * the focused tile and the edge of the viewport it is being scrolled to.
 *
 * Reported problem (2026-10-09, build 0.6.4): with a tile focused and the rail
 * otherwise at rest, the scroll offset oscillated 234 <-> 249 - a 15px bounce
 * under a stationary focus, with no focus change in the log at all. The tile
 * list was NOT churning (one "tiles rebuilt" line covered the whole scroll),
 * so this was not the duplicate-key rebinding the positional keys fixed; the
 * landing position itself was unstable.
 *
 * The default spec lands the focused node's LAYOUT rectangle flush with the
 * viewport edge and counts it visible the moment it fits. A focused tile draws
 * a 2dp accent border and a 12dp glow OUTSIDE that rectangle (see [KBCard] and
 * [posterBorderModifier]), so the bounds a viewer judges by are ~15px larger
 * than the ones the spec measures: the tile is brought to the edge, its own
 * overflow reads as one step short, and the next pass scrolls again - which
 * moves the item under the focus, which re-fires the focus modifier, which asks
 * again. The bounce IS the spec and the focus event disagreeing about where the
 * tile ends.
 *
 * The fix is the one the sports hub, the detail screen and Home's own rail
 * column already use: inset the viewport by a margin at each edge, so the
 * landing is a line the tile's LAYOUT rectangle can reach and then stay on -
 * margin plus the overflow is still inside the viewport, so nothing asks for a
 * corrective scroll once it is there. The distance depends only on the focused
 * tile's own rectangle and not on where the row happens to be, so it is the
 * same number on every pass and cannot oscillate.
 *
 * Nothing about focus navigation changes: the tiles, their keys and the focus
 * callbacks are untouched, and only where the scroll comes to rest moves.
 *
 * `internal`, like the rail's own tile/`key` rules beside it, because the
 * landing it computes is the thing this bug was - and the one property that
 * matters (a node already clear of both margins asks for NO scroll, and asking
 * again from where it landed asks for no scroll either) is arithmetic that a
 * contract test can assert exactly, with no device and no D-pad.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class CollectionBringIntoViewSpec(private val insetPx: Float) : BringIntoViewSpec {

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float {
        // A margin can never eat the viewport: a tile wider than the room the
        // two margins leave still lands on the nearest edge, rather than being
        // scrolled somewhere it could not be seen at all.
        val margin = insetPx.coerceAtMost(containerSize / 3f)
        // The rect arrives as the focused node's leading edge plus its extent -
        // the tile that asked to be brought into view, which is also the thing
        // carrying the border and the glow.
        val trailingEdge = offset + abs(size)
        return when {
            // Past the trailing margin: scroll forward just far enough to clear it.
            trailingEdge > containerSize - margin -> trailingEdge - (containerSize - margin)
            // Before the leading one: back off by the same amount.
            offset < margin -> offset - margin
            // Already clear of both: no scroll at all - which is the case that
            // has to stay at exactly 0f, because it is what keeps a rail whose
            // tile is already in view from being nudged by its own focus.
            else -> 0f
        }
    }
}

@Composable
private fun CollectionFolderTile(
    folder: KBFolder,
    onClick: () -> Unit,
    onFocus: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tileSize = folderTileSize(folder.tileShape)
    var isFocused by remember { mutableStateOf(false) }
    val focusModifier = Modifier.onFocusChanged {
        isFocused = it.isFocused
        if (it.isFocused) onFocus?.invoke()
    }

    // The poster edge the tile's outline is drawn with (Settings → Display →
    // Poster Edges), handed to the card as well as to the border below.
    //
    // The card's shape is what tv-material3 CLIPS the content to, so a tile
    // that draws a Pill outline over a card-class clip shows its cover art
    // outside its own outline at the corners - which is the reported "the
    // poster spills out of the outline on the edges" (see [BrowseShortcutTile],
    // where it was reported). PosterCard and LandscapeCard have always passed
    // the viewer's edge for exactly this reason; these two tiles drew the edge
    // without following it.
    val tileShape = posterEdgeShape()

    KBCard(
        onClick = onClick,
        modifier = focusModifier.then(modifier),
        shape = tileShape
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
                        // The tile's own edge, not the card default: the GIF
                        // covers the whole cover, so its corners have to be the
                        // corners the tile draws (see tileShape).
                        .clip(tileShape)
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
 * chip carries no manifest cover and no curated art, so the tile's backdrop -
 * and the backdrop the HERO swaps in while that tile is focused - come from one
 * TMDB discover lookup (see KBHomeViewModel and
 * TmdbRepository.getBrowseShortcutArt), a service's or studio's brand mark
 * comes from that same lookup, and a shortcut TMDB has nothing for simply keeps
 * the name it has always drawn.
 */
@Composable
fun KBHomeBrowseRail(
    shortcuts: List<BrowseHomeShortcut>,
    // Resolved tile/hero artwork per chip key. Empty on the first frame, then
    // filled in per shortcut as each lookup lands.
    artByKey: Map<String, BrowseShortcutArt> = emptyMap(),
    onOpenShortcut: (BrowseHomeShortcut) -> Unit,
    onShortcutFocused: ((BrowseHomeShortcut) -> Unit)? = null,
    // Long-press on a tile, with that tile's own FocusRequester. Home raises
    // its "Remove from Home" menu from here, and the requester is what lets the
    // menu hand focus back to the tile it came from - the same contract the
    // catalog rails' cards have (see HomeScreen's lastPosterFocusRequester).
    onShortcutLongPress: ((BrowseHomeShortcut, FocusRequester) -> Unit)? = null,
    // Non-null only for the rail Home draws first (see [homeTopRailUpHook]).
    onUpPressed: ((FocusRequester) -> Unit)? = null,
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
                val requester = remember { FocusRequester() }
                BrowseShortcutTile(
                    shortcut = shortcut,
                    art = artByKey[shortcut.chipKey()],
                    onClick = { onOpenShortcut(shortcut) },
                    onLongClick = onShortcutLongPress?.let { callback ->
                        { callback(shortcut, requester) }
                    },
                    onFocus = onShortcutFocused?.let { callback ->
                        { callback(shortcut) }
                    },
                    modifier = Modifier
                        .focusRequester(requester)
                        .homeTopRailUpHook(requester, onUpPressed)
                )
            }
        }
    }
}

/**
 * One Browse tile: a landscape card whose face IS the shortcut - the chip's own
 * name, or a service's / studio's brand mark - over the dim backdrop the tile's
 * lookup resolved.
 *
 * The name is the card, not a caption on one. A tile used to carry a kind badge
 * in its corner (a film reel, a calendar, a tag) and the chip's name in a
 * caption under its logo; both are gone. The rail's own name (see
 * [KBHomeBrowseRail]'s title) already says which KIND of door the row holds,
 * so the badge only labelled the row a second time, and a name tucked into a
 * corner under a logo reads as decoration rather than as the label it is.
 *
 * No wordmark but a brand's own. A tile's clearlogo is a service's or studio's
 * mark and nothing else (see [BrowseShortcutArt]); a category that owns no mark
 * - a genre, a tag, a decade, a collection - says its own name instead of
 * borrowing a popular title's wordmark, which is what made the Adventure tile
 * and the hero above it look like a poster for whatever film TMDB happened to
 * spotlight in that genre.
 *
 * The backdrop is TEXTURE here, not a thumbnail: kept well under full strength
 * and then dimmed further by the scrim below, it gives the card its color
 * without competing with the name on it. The lookup's backdrop is shared with
 * the HERO (see HomeScreen's HomeHeroHost), which is where a full-bleed still
 * belongs.
 *
 * A brand mark is drawn through [BrandMarkImage] rather than as a plain image,
 * and that is not cosmetic: a service's or studio's mark is as often as not
 * the dark glyph-on-transparency TMDB's company and network endpoints default
 * to, which on this near-black card is a logo that is *there* and invisible.
 * The shared composable whitens exactly those, and reports the marks that
 * cannot be drawn at all - a featureless plate, art that never arrives - so
 * the chip's name stands in instead of an empty card.
 *
 * Holding Select opens the caller's menu ([onLongClick]) instead of the door.
 * Home's is "Remove from Home": a chip mirrored to Home is otherwise only
 * removable from the browse chip that added it, which is not where the viewer
 * is standing when the row gets in the way.
 */
@Composable
private fun BrowseShortcutTile(
    shortcut: BrowseHomeShortcut,
    art: BrowseShortcutArt?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusModifier = Modifier.onFocusChanged {
        isFocused = it.isFocused
        if (it.isFocused) onFocus?.invoke()
    }

    // The brand's WHOLE candidate list when it has one (see BrowseShortcutArt):
    // the tile must be able to step past a mark that cannot be drawn, or a
    // service whose top mark is a blank plate shows its name instead of a logo.
    // Empty for every category that owns no mark - a genre, a tag, a decade, a
    // collection - which draws its own name, so the single-URL fallback below
    // only ever carries a brand's first candidate.
    val clearlogoUrls = art?.clearlogoUrls.orEmpty()
        .ifEmpty {
            listOfNotNull(art?.clearlogoUrl?.takeIf { it.isNotBlank() })
        }
    // Set when every resolved mark turns out to be undrawable on this surface:
    // the chip's own name is then the tile, rather than a blank card that
    // looks like the artwork is still loading.
    var markUnusable by remember(clearlogoUrls) { mutableStateOf(false) }

    // The resolved spotlight still (a genre's / decade's / studio's most
    // popular title, a collection's own art) drawn as a dim base so the tile
    // has color and depth instead of an empty surface.
    val backgroundUrl = art?.backdropUrl?.takeIf { it.isNotBlank() }

    // Reported: "the poster spills out of the outline on the edges". The tile
    // draws the poster edge's outline (posterBorderModifier below), but the CARD
    // was left on the default card shape - and the card's shape is what
    // tv-material3 clips the content to. With Poster Edges set to Pill the tile
    // clipped (and ringed) at the card's 12dp while its outline was drawn at
    // 20dp, so the backdrop's corners sat outside the very line framing them.
    // The viewer's own edge, passed to the card exactly as PosterCard does.
    val tileShape = posterEdgeShape()

    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.then(focusModifier),
        shape = tileShape
    ) {
        Box(
            modifier = Modifier
                .width(CollectionTileWidth)
                .height(CollectionTileHeight)
                .background(KBSurface)
                // A soft accent wash under everything, so a shortcut with no
                // resolved artwork is still a warm card rather than a flat
                // grey rectangle.
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            KBAccent.copy(alpha = 0.12f),
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                )
                // The faint edge every poster tile draws (Settings →
                // Interface), so a browse/network chip sits in the rail with
                // the same border as the catalog posters around it.
                .then(posterBorderModifier()),
            contentAlignment = Alignment.Center
        ) {
            // The backdrop is TEXTURE here, not a thumbnail: kept well under
            // full strength and then dimmed further by the scrim below, it
            // gives the card its color without the "mismatched still under a
            // wordmark" read that a full-strength image produced.
            if (backgroundUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(backgroundUrl).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 0.42f }
                )
            }

            // Legibility scrim: the name or the brand mark sits on it, so a
            // bright backdrop cannot swallow either.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                KBVoid.copy(alpha = 0.30f),
                                KBVoid.copy(alpha = 0.90f)
                            )
                        )
                    )
            )

            if (clearlogoUrls.isEmpty() || markUnusable) {
                // No brand mark: a genre, a tag, a decade or a collection owns
                // none, a service's may all have turned out undrawable, and
                // every tile is in this state on the first frame. The chip's
                // own name IS the tile - centred and uppercased so it reads as
                // the card's face rather than as a caption under one.
                Text(
                    text = shortcut.name.uppercase(),
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                )
            } else {
                BrandMarkLogo(
                    urls = clearlogoUrls,
                    contentDescription = shortcut.name,
                    onUnusable = { markUnusable = true },
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(CollectionTileHeight * 0.42f)
                )
            }
        }
    }
}
