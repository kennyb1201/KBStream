package com.kennyb1201.kbstream.ui.addons

import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.kb.mergedHomeRailKeys
import com.kennyb1201.kbstream.data.kb.railMoveChangesOrder

/**
 * One row of the Home rail manager: a built-in rail, a browse rail, a built
 * catalog, an add-on catalog or an imported collection, in the merged order
 * Home draws them.
 *
 * Extracted from the TV dialog together with [homeRailManagerRows] so the phone
 * manager lists exactly the same rows, in the same order, with the same
 * "would this press move it?" answers. The two screens differ in their
 * presentation only - this is the part a second copy would silently drift on,
 * and it is the part a viewer aims at when arranging Home.
 */
internal data class HomeRailManagerRow(
    val key: String,
    val isCollection: Boolean,
    val config: CatalogConfiguration?,
    val collectionKey: String?,
    val title: String,
    val subtitle: String,
    val isPinned: Boolean,
    val isHidden: Boolean,
    /**
     * A browse rail (genres & tags, services & networks, ...) rather than a
     * collection: the kind tag says BROWSE, and [isCollection] carries the pin
     * control both kinds use.
     */
    val isBrowseRail: Boolean = false,
    /**
     * A built-in rail this app draws itself (Continue Watching, Upcoming
     * Schedule): no configuration object, no pin control, but it does get the
     * reorder arrows and the rename button.
     */
    val isBuiltinRail: Boolean = false,
    /**
     * A built catalog (the Catalog Builder's rule sets). It is arranged through
     * the same generic key-based callbacks a built-in row uses, so
     * [isBuiltinRail] is true for it too - this flag exists for the KIND TAG
     * and nothing else, because calling a hand-composed catalog "BUILT-IN"
     * would name the wrong feature.
     */
    val isCustomCatalog: Boolean = false,
    /**
     * Whether each reorder press would actually change where the rail sits on
     * Home, decided by the same transforms that perform the move.
     */
    val canMoveTop: Boolean = false,
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false,
    val canMoveBottom: Boolean = false
)

/**
 * The manager's rows, in the merged Home order.
 *
 * [kidsMaxAge] / [isGuest] select the profile's built-ins exactly as Home draws
 * them (see [KBHomeOrderPrefs.builtinKeysFor]): a kids profile has no Top Today
 * rows and has its own kids rails, and a guest profile has its own rails after
 * them. A row Home does not draw is not offered here, because an arrow next to
 * a rail nobody can see would move it two drawn slots.
 */
internal fun homeRailManagerRows(
    configurations: List<CatalogConfiguration>,
    collectionsState: AddonsViewModel.CollectionUiState,
    kidsMaxAge: Int?,
    isGuest: Boolean
): List<HomeRailManagerRow> {
    val collectionByKey = collectionsState.collections.associateBy { it.key }
    val addonByKey = configurations.associateBy {
        KBHomeOrderPrefs.addonKeyFromManifest(
            it.addonManifestUrl,
            it.catalog.type,
            it.catalog.id
        )
    }
    // The browse rails join the list while they have chips in them: each is a
    // rail the viewer can pin, move and hide like a collection, and a rail that
    // cannot be seen in the manager cannot be arranged.
    val browseRailByKey = collectionsState.browseRails.associateBy { rail -> rail.key }
    // The viewer's own built catalogs (the Catalog Builder's rule sets). They
    // join this list because that is what makes them arrangeable.
    val customCatalogByKey = collectionsState.customCatalogs.associateBy { catalog ->
        KBHomeOrderPrefs.customCatalogKey(catalog.id)
    }
    // The same merged order the ViewModel writes and the same rule it moves a
    // row with: one shared function, so the list the viewer aims at is the list
    // the move acts on.
    val prefs = KBHomeOrderPrefs.readOrder()
    val movableDefaults = KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge, isGuest) +
        browseRailByKey.keys.toList() +
        customCatalogByKey.keys.toList() +
        addonByKey.keys +
        collectionsState.collections.map { it.key }
    val orderedKeys = mergedHomeRailKeys(prefs, movableDefaults)

    return orderedKeys.mapNotNull { key ->
        val browseRail = browseRailByKey[key]
        val row = if (browseRail != null) {
            // isCollection is the flag that carries the PIN control, and this
            // row has one. Its hidden state is the stored flag alone: unlike a
            // collection, a never-arranged browse rail renders (under the Top
            // Today rows), so it must not be listed as hidden for want of an
            // arrangement entry.
            HomeRailManagerRow(
                key = key,
                isCollection = true,
                config = null,
                collectionKey = key,
                title = browseRail.title,
                subtitle = "Browse · ${browseRail.shortcuts.size} saved",
                isPinned = key in prefs.pinned,
                isHidden = key in prefs.hiddenSet,
                isBrowseRail = true
            )
        } else if (KBHomeOrderPrefs.isBuiltinKey(key)) {
            // A built-in rail: orderable and hideable, never pinnable - which
            // is why isCollection stays false. Rename still applies, and its
            // title is the arrangement's override when there is one.
            HomeRailManagerRow(
                key = key,
                isCollection = false,
                config = null,
                collectionKey = null,
                title = KBHomeOrderPrefs.railTitle(
                    prefs,
                    key,
                    KBHomeOrderPrefs.builtinDefaultTitle(key).orEmpty()
                ),
                subtitle = "Built-in · " +
                    KBHomeOrderPrefs.builtinDefaultTitle(key).orEmpty(),
                isPinned = false,
                isHidden = key in prefs.hiddenSet,
                isBuiltinRail = true
            )
        } else if (KBHomeOrderPrefs.isCustomCatalogKey(key)) {
            // A built catalog: no configuration object and no pin control, but
            // it gets the reorder arrows and the rename button, which is why it
            // is flagged as a "built-in" row - those callbacks are generic by
            // key, and this rail lives in the same key space they write.
            val catalog = customCatalogByKey[key] ?: return@mapNotNull null
            HomeRailManagerRow(
                key = key,
                isCollection = false,
                config = null,
                collectionKey = null,
                title = KBHomeOrderPrefs.railTitle(prefs, key, catalog.name),
                subtitle = "Catalog · " + catalogSummaryLine(catalog),
                isPinned = key in prefs.pinned,
                isHidden = key in prefs.hiddenSet,
                isBuiltinRail = true,
                isCustomCatalog = true
            )
        } else if (key.startsWith("kb:")) {
            val collection = collectionByKey[key] ?: return@mapNotNull null
            // Hidden/pinned derive from the FRESH home-order prefs - the same
            // rule Home and the ViewModel use (hidden-set membership, or
            // never-arranged counts as hidden) - not the async collectionsState
            // snapshot, which reloads a beat after a toggle.
            val arranged = prefs.pinned.toSet() + prefs.order.toSet() + prefs.hiddenSet
            HomeRailManagerRow(
                key = key,
                isCollection = true,
                config = null,
                collectionKey = key,
                title = collection.title,
                subtitle = "Collection · ${collection.folderCount} folders",
                isPinned = key in prefs.pinned,
                isHidden = key in prefs.hiddenSet || key !in arranged
            )
        } else {
            val config = addonByKey[key] ?: return@mapNotNull null
            HomeRailManagerRow(
                key = key,
                isCollection = false,
                config = config,
                collectionKey = null,
                title = config.catalog.displayName.ifBlank { config.catalog.id },
                subtitle = "${config.catalog.type} · ${config.addonName}",
                isPinned = key in prefs.pinned.toSet(),
                isHidden = !config.catalog.showOnHome
            )
        }
        // Exact, not a guess: an arrow is offered when pressing it would
        // actually change where the rail sits on Home.
        row.copy(
            canMoveTop = railMoveChangesOrder(prefs, movableDefaults, key, Int.MIN_VALUE),
            canMoveUp = railMoveChangesOrder(prefs, movableDefaults, key, -1),
            canMoveDown = railMoveChangesOrder(prefs, movableDefaults, key, +1),
            canMoveBottom = railMoveChangesOrder(prefs, movableDefaults, key, Int.MAX_VALUE)
        )
    }
}
