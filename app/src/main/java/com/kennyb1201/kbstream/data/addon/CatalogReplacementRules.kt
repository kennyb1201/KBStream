package com.kennyb1201.kbstream.data.addon

/** Catalog identity as the addon list keys on it (type + id). */
private fun ManifestCatalog.replacementKey(): String = "${type.lowercase()}|$id"

/** Just the media type, lowercased — the only stable axis of a dynamic catalog. */
private fun ManifestCatalog.typeKey(): String = type.lowercase()

/**
 * Pairs the catalogs an addon HAD that a fresh manifest no longer lists with
 * the newcomers that replaced them.
 *
 * Dynamic addons (BingeCat's because-you-watched rails, AIOStreams' generated
 * lists) frequently swap catalog ids as their content changes: conceptually the
 * new catalog REPLACES the removed one. The addon list already leans on that to
 * hand a newcomer a freed global-order slot, but the user's per-catalog
 * settings (showOnHome / customName) and their HOME ARRANGEMENT are keyed by the
 * catalog id — so without this pairing a reordered dynamic rail silently
 * dropped back to the default tail every time its id changed.
 *
 * The pairing is positional and identity-free, because the id is exactly what
 * changes: removed catalogs in their saved order against the manifest's own
 * order, one-for-one. (A name match is not usable — these catalogs' names embed
 * the content too.)
 *
 * Pairing is confined to ONE MEDIA TYPE. A refresh that replaces several ids at
 * once was previously paired across the whole addon, so a removed movie rail and
 * a newcomer series rail could be matched whenever the addon also reordered
 * them — which handed the movie's user settings (and its hidden/shown state) to
 * the series rail, and vice versa. Restricting each pool to its own type is the
 * cheapest signal that cannot be scrambled by a reorder, because a catalog's
 * media type does not churn with its content.
 *
 * Within a type, a predecessor with the same NAME wins over position, and only
 * the leftovers are paired positionally.
 *
 * Returns `(added, replaced)` pairs in the manifest's order; catalogs that line
 * up on one side with no counterpart of the same type are left unpaired.
 */
internal fun pairReplacedCatalogs(
    existing: List<ManifestCatalog>,
    fresh: List<ManifestCatalog>
): List<Pair<ManifestCatalog, ManifestCatalog>> {
    val freshKeys = fresh.mapTo(HashSet()) { it.replacementKey() }
    val existingKeys = existing.mapTo(HashSet()) { it.replacementKey() }
    val removedByType =
        existing.filter { it.replacementKey() !in freshKeys }.groupBy { it.typeKey() }
    val added = fresh.filter { it.replacementKey() !in existingKeys }

    val takenByType = HashMap<String, MutableSet<String>>()
    return added.mapNotNull { newcomer ->
        val type = newcomer.typeKey()
        val pool = removedByType[type] ?: return@mapNotNull null
        val taken = takenByType.getOrPut(type) { mutableSetOf() }

        // Prefer the predecessor with the SAME NAME. A refresh that reorders a
        // dynamic addon's rails would otherwise hand each newcomer the settings
        // of whichever rail happened to sit in that slot before — renaming a
        // catalog and seeing the old name come back, or a hidden rail return to
        // Home, is that mismatch. Names that embed the content (BingeCat's
        // because-you-watched rails) simply match nothing and fall through to
        // the positional rule below, which is what this pairing did before.
        val match =
            pool.firstOrNull { it.name == newcomer.name && it.replacementKey() !in taken }
                ?: pool.firstOrNull { it.replacementKey() !in taken }
                ?: return@mapNotNull null

        taken += match.replacementKey()
        newcomer to match
    }
}

/**
 * The result of planning how a refreshed manifest's catalogs merge with what
 * the user already had.
 *
 * [catalogs] is the merged list in manifest order; [replaced] pairs each
 * newcomer with the removed catalog it stands in for (see
 * [pairReplacedCatalogs]), so the caller can remap anything keyed by catalog id
 * — the Home arrangement.
 */
internal data class ManifestMergePlan(
    val catalogs: List<ManifestCatalog>,
    val replaced: List<Pair<ManifestCatalog, ManifestCatalog>>
)

/**
 * Merge a freshly fetched manifest's catalogs with the user's stored ones for
 * the SAME addon, preserving everything that is KBStream-local:
 *
 *  - `customName` (the user's rename),
 *  - `showOnHome` (the user's pin/hide),
 *  - `order` (the global slot, inherited by a replacement on an id swap),
 *
 * and defaulting a genuinely new catalog to [ManifestCatalog.defaultShowOnHome]
 * so hidden-by-design rails (search placeholders, director/seed catalogs) stay
 * off Home on install. [globalOrder] is the GLOBAL order-keyed slot map (every
 * addon, not just this one), and [keyOf] derives a catalog's key exactly the
 * way that map is keyed.
 *
 * Pure, so the whole merge — the part that regressed into losing a rename, a
 * pin, or a slot — is unit tested without a manager, a store, or a network.
 */
internal fun planManifestMerge(
    existingCatalogs: List<ManifestCatalog>,
    manifestCatalogs: List<ManifestCatalog>,
    globalOrder: Map<String, Int>,
    keyOf: (ManifestCatalog) -> String
): ManifestMergePlan {
    val existingByKey = existingCatalogs.associateBy(keyOf)
    val replaced =
        pairReplacedCatalogs(existingCatalogs.sortedBy { it.order }, manifestCatalogs)
    val replacementByKey =
        replaced.associate { (added, removed) -> keyOf(added) to removed }
    val manifestKeys = manifestCatalogs.mapTo(mutableSetOf(), keyOf)
    val freedOrderSlots =
        slotsFreedByManifest(existingByKey.keys, manifestKeys, globalOrder)
    val fallbackOrderStart = (globalOrder.values.maxOrNull() ?: -1) + 1

    var freedSlotCursor = 0
    var overflowOffset = 0

    val catalogs =
        manifestCatalogs.map { manifestCatalog ->
            val key = keyOf(manifestCatalog)
            val inherited = existingByKey[key] ?: replacementByKey[key]
            manifestCatalog.copy(
                showOnHome = inherited?.showOnHome ?: manifestCatalog.defaultShowOnHome,
                customName = inherited?.customName ?: manifestCatalog.customName,
                order =
                    globalOrder[key]
                        ?: (
                            freedOrderSlots.getOrNull(freedSlotCursor++)
                                ?: (fallbackOrderStart + overflowOffset++)
                            )
            )
        }

    return ManifestMergePlan(catalogs = catalogs, replaced = replaced)
}

/**
 * Apply the catalog manager's Show All / Hide All to one addon's catalogs.
 *
 * Search placeholders are returned untouched: they are hidden from the manager
 * and from Home ([ManifestCatalog.isSearchPlaceholder]), so flipping their flag
 * would rewrite state the user can neither see nor act on.
 */
internal fun setAllCatalogsVisible(
    catalogs: List<ManifestCatalog>,
    showOnHome: Boolean
): List<ManifestCatalog> =
    catalogs.map { catalog ->
        if (catalog.isSearchPlaceholder) catalog
        else catalog.copy(showOnHome = showOnHome)
    }

/**
 * Merge a freshly fetched manifest's catalogs with the user's local
 * configuration for the SAME addon.
 *
 * Existing catalogs (matched by type+id) keep their position and the settings
 * that are KBStream-local rather than part of the manifest:
 *  - `showOnHome` (the user's pin/hide), and
 *  - `customName` (the user's rename).
 *
 * Catalogs the manifest no longer lists are dropped; genuinely new ones append
 * in manifest order and default to visible.
 *
 * Carrying `customName` here matters as much as it does in
 * [pairReplacedCatalogs]'s caller ([AddonManager.updateAddonFromManifest]):
 * rebuilding a catalog from the fresh manifest without it made a renamed rail
 * silently revert to the manifest's own name on the next refresh, and then push
 * that nameless copy to every other device.
 */
internal fun mergeRefreshedCatalogs(
    oldCatalogs: List<ManifestCatalog>,
    newCatalogs: List<ManifestCatalog>
): List<ManifestCatalog> {
    fun key(type: String, id: String): String =
        "${type.trim().lowercase()}::${id.trim().lowercase()}"

    val oldByKey = oldCatalogs.associateBy { key(it.type, it.id) }
    val oldOrder = oldCatalogs.sortedBy { it.order }.map { key(it.type, it.id) }
    val newByKey = newCatalogs.associateBy { key(it.type, it.id) }

    val result = mutableListOf<ManifestCatalog>()

    // Preserve the user's existing order first.
    oldOrder.forEach { k ->
        val newCatalog = newByKey[k] ?: return@forEach
        val oldCatalog = oldByKey[k]
        result += newCatalog.copy(
            showOnHome = oldCatalog?.showOnHome ?: newCatalog.defaultShowOnHome,
            customName = oldCatalog?.customName ?: newCatalog.customName
        )
    }

    // Append catalogs that are new in the refreshed manifest. Some addons
    // (e.g. AIOStreams) list the same catalog more than once in their manifest
    // — dedupe by (type, id) so Home never builds two rails with the same key
    // (duplicate LazyColumn keys crash the rail list, which is why catalogs
    // showed in the add-on screen but never appeared on Home).
    val seen = mutableSetOf<String>()
    newCatalogs.forEach { catalog ->
        val k = key(catalog.type, catalog.id)
        if (oldByKey[k] == null && seen.add(k)) {
            // A genuinely new catalog honors the manifest's visibility hint,
            // exactly like the background manifest merge. Defaulting every
            // newcomer to visible flooded Home with search placeholders and
            // director/seed rails the addon ships hidden by design.
            result += catalog.copy(showOnHome = catalog.defaultShowOnHome)
        }
    }

    return result.mapIndexed { index, catalog -> catalog.copy(order = index) }
}

/**
 * Global-order slots a manifest refresh is allowed to hand to its newcomers:
 * the positions of THIS addon's catalogs that the fresh manifest no longer
 * lists, ascending.
 *
 * The distinction matters because [globalOrder] is the GLOBAL catalog order —
 * it holds every addon's catalogs, not just this one's. The caller used to
 * filter that map by "key not in this addon's manifest", which also admits
 * every OTHER addon's positions; a newcomer then took the lowest of those
 * (typically index 0) and jumped to the TOP of Home instead of inheriting the
 * slot it replaced. That is the "the addon's catalogs keep rearranging
 * themselves on every launch / Refresh addons" report for addons whose ids
 * churn (BingeCat rails, AIOStreams lists).
 *
 * [existingAddonKeys] is this addon's stored catalog keys, [manifestKeys] the
 * fresh manifest's.
 */
internal fun slotsFreedByManifest(
    existingAddonKeys: Set<String>,
    manifestKeys: Set<String>,
    globalOrder: Map<String, Int>
): List<Int> =
    existingAddonKeys
        .filter { it !in manifestKeys }
        .mapNotNull { key -> globalOrder[key] }
        .sorted()
