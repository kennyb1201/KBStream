package com.kennyb1201.kbstream.data.bingecat

/**
 * Everything the Library section needs, in one read.
 *
 * [profileId] is the KBStream profile the numbers were read FOR and [mappedId]
 * the BingeCat profile they came from. Both are compared on every use, so a
 * mapping change or a profile switch can never serve the previous account's
 * rows out of this cache (spec F2's Mom trap, closed in the cache as well as on
 * the wire).
 *
 * In its own file since 2026-11-21, when it gained an on-disk form: the snapshot
 * is what the persisted cache stores and restores, and keeping it beside the
 * codec that encodes it leaves the pair readable (and unit-testable) without the
 * repository's Retrofit/Android imports.
 */
data class BingeCatSnapshot(
    val watchlist: List<BingeCatRow>,
    val saved: List<BingeCatRow>,
    val lists: List<BingeCatList>,
    val watched: Set<String>,
    val refreshedAtMs: Long,
    val profileId: String?,
    val mappedId: Long,
    /**
     * Whether [lists] is the account's whole list set as of [refreshedAtMs].
     *
     * False when the lists leg failed - [lists] then carries the rows this
     * device already had, or nothing at all on a cold snapshot. The freshness
     * stamp cannot say this on its own: a partial refresh re-stamps the
     * snapshot, so a caller that only compared [refreshedAtMs] against its own
     * read time would treat one failed request as "the account has no lists"
     * and prune every pinned BingeCat rail on the strength of it.
     */
    val listsComplete: Boolean = true
)
