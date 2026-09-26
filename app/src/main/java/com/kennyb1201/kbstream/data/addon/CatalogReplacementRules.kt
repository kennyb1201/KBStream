package com.kennyb1201.kbstream.data.addon

/** Catalog identity as the addon list keys on it (type + id). */
private fun ManifestCatalog.replacementKey(): String = "${type.lowercase()}|$id"

/**
 * Pairs the catalogs an addon HAD that a fresh manifest no longer lists with
 * the newcomers that replaced them.
 *
 * Dynamic addons (BingeCat's because-you-watched rails, curated lists)
 * frequently swap catalog ids as their content changes: conceptually the new
 * catalog REPLACES the removed one. The addon list already leans on that to
 * hand a newcomer a freed global-order slot, but the user's per-catalog
 * settings (showOnHome / customName) and their HOME ARRANGEMENT are keyed by
 * the catalog id — so without this pairing a reordered dynamic rail silently
 * dropped back to the default tail every time its id changed.
 *
 * The pairing is positional and identity-free, because the id is exactly what
 * changes: removed catalogs in their saved order against the manifest's own
 * order, one-for-one. (A name match is not usable — these catalogs' names
 * embed the content too.) Returns `(added, replaced)` pairs; catalogs that
 * line up on one side with no counterpart are left unpaired.
 */
internal fun pairReplacedCatalogs(
    existing: List<ManifestCatalog>,
    fresh: List<ManifestCatalog>
): List<Pair<ManifestCatalog, ManifestCatalog>> {
    val freshKeys = fresh.mapTo(HashSet()) { it.replacementKey() }
    val existingKeys = existing.mapTo(HashSet()) { it.replacementKey() }
    val removed = existing.filter { it.replacementKey() !in freshKeys }
    val added = fresh.filter { it.replacementKey() !in existingKeys }
    val pairs = minOf(removed.size, added.size)
    return (0 until pairs).map { index -> added[index] to removed[index] }
}
