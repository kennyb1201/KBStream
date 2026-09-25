package com.kennyb1201.kbstream.ui.kb

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.kennyb1201.kbstream.data.kb.KBCollectionProfile
import com.kennyb1201.kbstream.data.kb.KBFolder
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.home.Rail
import com.kennyb1201.kbstream.ui.home.RailHorizontalStartPadding
import com.kennyb1201.kbstream.ui.home.TvSafeAreaHorizontal
import com.kennyb1201.kbstream.ui.theme.CardShape
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
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

        return topTodayRails + hardcodedRails + pinned + middle + tail
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

/** One Home entry: either an addon catalog rail or an imported collection. */
sealed class HomeEntry {
    // sourceIndex preserves the original rails position so two rails that
    // ever share addon/catalog/type still get unique LazyColumn keys.
    data class AddonRail(val rail: Rail, val sourceIndex: Int) : HomeEntry()
    data class Collection(val collection: KBCollectionProfile) : HomeEntry()
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
