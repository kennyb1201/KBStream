package com.kennyb1201.kbstream.ui.addons

import com.kennyb1201.kbstream.data.addon.CatalogConfiguration
import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.data.kb.homeColumnRailKeys
import com.kennyb1201.kbstream.data.kb.railMoveChangesOrder
import com.kennyb1201.kbstream.data.library.LibraryHomeRailPin
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.key

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
     * A Library list the viewer pinned to Home (see `LibraryHomeRails`).
     *
     * A rail of the same column as every other row: its position is the Home
     * order's (the arrangement), and its title is the name the PIN stores. That
     * is why it carries the pin itself - the arrows write the arrangement like
     * any row's, while RENAME and REMOVE write the pins blob - and this flag is
     * how the row's buttons know which of the two they are looking at.
     */
    val isLibraryRail: Boolean = false,
    /** The pin behind a [isLibraryRail] row; null for every other kind. */
    val libraryPin: LibraryHomeRailPin? = null,
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
    isGuest: Boolean,
    /**
     * The viewer's pinned Library lists, in pin order - which is also the
     * fallback order for any pin the Home order has not placed yet. Empty on a
     * surface that does not manage them, which is why it defaults.
     */
    libraryPins: List<LibraryHomeRailPin> = emptyList()
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
    // The pin keys join the column at the same place Home places them (see
    // `homeColumnRailKeys`): after the arrangement's own defaults, so a pin the
    // Home order has not placed yet still lists at the end, in pin order.
    val pinByKey = libraryPins.associateBy { pin -> pin.key }
    val column = homeColumnRailKeys(
        prefs = prefs,
        defaults = movableDefaults,
        libraryKeys = libraryPins.map { pin -> pin.key }
    )

    // Every row is built against [column], so a move the manager offers is a
    // move Home can make - including the ones that cross between an arrangement
    // rail and a pinned list, which the split into two lists used to forbid.
    val arrangementKeys = column.filterNot { key -> pinByKey.containsKey(key) }
    val arrangementRows = arrangementKeys.mapNotNull { key ->
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
            canMoveTop = railMoveChangesOrder(prefs, column, key, Int.MIN_VALUE),
            canMoveUp = railMoveChangesOrder(prefs, column, key, -1),
            canMoveDown = railMoveChangesOrder(prefs, column, key, +1),
            canMoveBottom = railMoveChangesOrder(prefs, column, key, Int.MAX_VALUE)
        )
    }

    // The viewer's own Library lists, where the ONE Home order puts them - the
    // same walk that placed the catalogs above, filtered to the pin keys. Their
    // arrows are decided against [column], not against the pins alone: UP on the
    // first pin has to be able to put it above a catalog (the arrows used to be
    // dark there by construction). The move itself writes the HOME ORDER - a
    // move is not a pin edit, so the pins blob keeps the order it had as the
    // fallback for a new install.
    //
    // One row per pin, including a pin whose list no longer answers (the ghost a
    // rename left behind) - removing it must not depend on the list loading, or
    // the rail that cannot be loaded is also a rail that cannot be removed.
    val libraryRows = column.mapNotNull { key ->
        val pin = pinByKey[key] ?: return@mapNotNull null
        HomeRailManagerRow(
            key = pin.key,
            isCollection = false,
            config = null,
            collectionKey = null,
            title = pin.name,
            subtitle = "Library · " + libraryRailSourceLabel(pin.source),
            // The arrangement's pin flag is a different thing (see
            // KBHomeOrderPrefs.pinned): a Library row is pinned by definition,
            // and showing it as an arrangement pin would put a pin glyph and a
            // pin button on a row neither of them acts on.
            isPinned = false,
            isHidden = false,
            isLibraryRail = true,
            libraryPin = pin,
            canMoveTop = railMoveChangesOrder(prefs, column, pin.key, Int.MIN_VALUE),
            canMoveUp = railMoveChangesOrder(prefs, column, pin.key, -1),
            canMoveDown = railMoveChangesOrder(prefs, column, pin.key, +1),
            canMoveBottom = railMoveChangesOrder(prefs, column, pin.key, Int.MAX_VALUE)
        )
    }

    // One row per column entry, in the column's order: the arrangement rows and
    // the Library rows are built apart only because they are built from
    // different data, and are returned interleaved because Home draws them
    // interleaved.
    val rowByKey = (arrangementRows + libraryRows).associateBy { row -> row.key }
    return column.mapNotNull { key -> rowByKey[key] }
}

/**
 * The service a Library pin names, short enough for a manager subtitle.
 *
 * The Library's own labels say which KIND of row it is ("BingeCat list",
 * "Trakt list"); the manager already says LIBRARY in the kind chip, so the
 * subtitle only has to name the service. A source this build does not know
 * falls back to its stored name rather than vanishing from the row.
 */
internal fun libraryRailSourceLabel(source: String): String =
    runCatching { LibrarySource.valueOf(source) }.getOrNull()
        ?.label
        ?.substringBefore(" ·")
        ?.removeSuffix(" list")
        ?: source.ifBlank { "Library" }

