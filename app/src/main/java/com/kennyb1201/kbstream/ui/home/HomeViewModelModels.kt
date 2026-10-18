package com.kennyb1201.kbstream.ui.home

/*
 * Small value types HomeViewModel passes around internally. They used to be
 * private nested classes inside its multi-thousand-line body; neither carries
 * view-model state, so they live here as package-level types the class still
 * reaches unqualified.
 */

/** Identity of one rail: where its catalog came from and how to render it. */
internal data class RailInfo(
    val addonName: String,
    val catalogId: String,
    val catalogType: String,
    val catalogRawName: String,
    val baseUrl: String,
    val hideUpcoming: Boolean,
    val landscapeCards: Boolean,
    val pinned: Boolean
)

/** The catalog a grid page is loading, keyed for the in-flight de-dup map. */
internal data class PendingCatalogLoad(
    val addonName: String,
    val baseUrl: String,
    val catalogId: String,
    val catalogType: String,
    val catalogRawName: String,
    /**
     * True when the viewer set this catalog's name in the add-on screen, so it
     * is shown EXACTLY as typed ("AI" must not come back as "Ai") instead of
     * being prettified by [formatCatalogName].
     */
    val catalogUserNamed: Boolean = false
)

/**
 * The rail identity a catalog load publishes under: what [HomeViewModel]'s own
 * `railKeyOf` builds, spelled once so the ordering rules below and the view
 * model cannot disagree about which rail is which.
 */
internal fun catalogRailKey(addonName: String, catalogId: String?, type: String): String =
    addonName + "::" + catalogId + "::" + type

/**
 * CATALOG ORDER: the position of every catalog Home is about to load, by the
 * rail key its rail will publish under.
 *
 * This is the order the viewer set in the add-ons screen (the global catalog
 * order — see AddonManager.getCatalogConfigurations), and it is the order the
 * rails have to reach Home in. The fan-out fetches concurrently, so the order
 * they RESOLVE in is a property of the network: two lanes on the same box
 * finish in a different order minute to minute. Ordering the published list by
 * this map is what makes Home's rail order the viewer's, not the wire's.
 */
internal fun catalogOrderByKey(pending: List<PendingCatalogLoad>): Map<String, Int> =
    pending
        .mapIndexed { index, load ->
            catalogRailKey(load.addonName, load.catalogId, load.catalogType) to index
        }
        // Duplicates cannot survive the fan-out's own de-dupe, and first-wins
        // keeps the earliest catalog's slot if one ever does.
        .toMap()

/**
 * [rails] in catalog order (see [catalogOrderByKey]).
 *
 * [rails] arrives in fetch-completion order, which is why this exists. A rail
 * the map does not name — an app-built row, or one a previous build left behind
 * for a catalog that is no longer loaded — sorts last, and the sort is stable,
 * so those keep the relative order they arrived in.
 */
internal fun orderCatalogRails(
    rails: List<Rail>,
    catalogOrder: Map<String, Int>
): List<Rail> =
    rails.sortedBy { rail ->
        catalogOrder[catalogRailKey(rail.addonName, rail.catalogId, rail.type)]
            ?: Int.MAX_VALUE
    }
