package com.kennyb1201.kbstream.ui.nuvio

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kennyb1201.kbstream.data.nuvio.NuvioCollectionProfile
import com.kennyb1201.kbstream.data.nuvio.NuvioFolder
import com.kennyb1201.kbstream.data.nuvio.NuvioHomeOrderPrefs
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.home.Rail
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Placement of imported Nuvio collections among the addon catalog rails on
 * Home. A collection anchors to the addon rail it precedes in the stored
 * merged order (from the Collections manager), pinned collections lead
 * right after Continue Watching, and unarranged collections render after
 * the last addon rail.
 */
object NuvioHomeSlots {

    fun buildMergedEntries(
        rails: List<Rail>,
        state: NuvioHomeViewModel.UiState
    ): List<HomeEntry> {
        val addonEntries = rails.mapIndexed { index, rail ->
            HomeEntry.AddonRail(rail, index)
        }
        val collections = state.collections
        if (collections.isEmpty()) return addonEntries

        val arrangement = state.arrangement
        val hidden = arrangement.hiddenSet
        val pinnedKeys = arrangement.pinned.toSet()

        val collectionByKey = LinkedHashMap<String, NuvioCollectionProfile>()
        for (collection in collections) {
            collectionByKey.putIfAbsent(
                NuvioHomeOrderPrefs.collectionKey(collection.id, collection.title),
                collection
            )
        }
        val addonKeyByRail = rails.associate { rail ->
            rail to NuvioHomeOrderPrefs.addonKey(rail.baseUrl, rail.type, rail.catalogId)
        }

        val pinned = arrangement.pinned
            .mapNotNull { collectionByKey[it] }
            .filter { key(it) !in hidden }
            .map { HomeEntry.Collection(it) }

        // Walk the stored merged order (pinned handled separately). A
        // collection key emits a collection entry; an addon key emits every
        // addon rail matching it (multiple addons can share a catalog id).
        val middle = mutableListOf<HomeEntry>()
        for (key in arrangement.order) {
            if (key in pinnedKeys) continue
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
            if (entry !in placedRails) tail += entry
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

        return pinned + middle + tail
    }

    private fun key(collection: NuvioCollectionProfile): String =
        NuvioHomeOrderPrefs.collectionKey(collection.id, collection.title)
}

/** One Home entry: either an addon catalog rail or an imported collection. */
sealed class HomeEntry {
    // sourceIndex preserves the original rails position so two rails that
    // ever share addon/catalog/type still get unique LazyColumn keys.
    data class AddonRail(val rail: Rail, val sourceIndex: Int) : HomeEntry()
    data class Collection(val collection: NuvioCollectionProfile) : HomeEntry()
}

private val CollectionTileWidth = 210.dp
private val CollectionTileHeight = 118.dp

/**
 * One imported Nuvio collection on Home: its title plus a row of folder
 * tiles (hosted cover art with a title overlay, like Nuvio's collection
 * rows). Clicking a folder opens the folder screen with the collection's
 * layout mode.
 */
@Composable
fun NuvioHomeCollectionRail(
    collection: NuvioCollectionProfile,
    onOpenFolder: (String) -> Unit
) {
    Column(
        modifier = Modifier.padding(
            start = 12.dp,
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

        LazyRow(
            contentPadding = PaddingValues(start = 0.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
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
                        onClick = { onOpenFolder(folderId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionFolderTile(
    folder: NuvioFolder,
    onClick: () -> Unit
) {
    KBCard(onClick = onClick) {
        Box(
            modifier = Modifier
                .width(CollectionTileWidth)
                .height(CollectionTileHeight)
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

            // Title scrim: readable folder name over the collage art.
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
