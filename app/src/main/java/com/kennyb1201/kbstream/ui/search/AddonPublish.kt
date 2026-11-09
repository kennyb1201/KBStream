package com.kennyb1201.kbstream.ui.search

/**
 * The rules the add-on wave needs at publish time, kept apart from the
 * coroutine plumbing so they can be pinned without a ViewModel.
 *
 * The wave publishes twice: a snapshot as each rail answers, and one
 * consolidated list when every add-on is done. Both are publishes of the SAME
 * groups — the snapshot is just earlier — so the ordering below is what keeps
 * the later one from contradicting the earlier one.
 */

/**
 * Apply the TMDB name+year de-dupe to [groups] and drop a rail it empties.
 *
 * Only the consolidated publish calls this: the index is built from both TMDB
 * search waves, so awaiting it while a rail is being built is what held fast
 * add-on rails behind a stalled TMDB request. A snapshot publishes with the
 * `seen`-set de-dupe alone, which needs no TMDB.
 *
 * The drop-the-empty-rail rule matters here as much as in the digital-release
 * filter: a rail whose every hit turned out to be a TMDB title the viewer is
 * already looking at must disappear, not stay as a titleless heading.
 *
 * A search-style rail ("AI Search", People, Collections) is exempt: overlapping
 * the other rails is expected there, and an emptied one is exactly the "my AI
 * search rail disappeared" bug.
 */
internal fun dedupAddonGroups(
    groups: List<AddonResultGroup>,
    index: TmdbDedupIndex
): List<AddonResultGroup> = groups.mapNotNull { group ->
    if (group.searchStyle) return@mapNotNull group
    val kept = group.results.filterNot { result ->
        index.matches(
            type = result.type,
            id = result.id,
            name = result.name,
            // The year the card shows, which is the one the index was built
            // from — an enriched year can prove a duplicate the raw catalog
            // page could not.
            year = result.year
        )
    }
    if (kept.isEmpty()) null else group.copy(results = kept)
}

/**
 * Drop every rail whose results are empty: the shared "a rail with nothing left
 * is not a rail" rule. The digital-release filter can empty a group, and so can
 * the TMDB de-dupe; a titleless heading is worse than no heading at all.
 */
internal fun dropEmptyAddonGroups(
    groups: List<AddonResultGroup>
): List<AddonResultGroup> = groups.filter { group -> group.results.isNotEmpty() }
