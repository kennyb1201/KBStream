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
