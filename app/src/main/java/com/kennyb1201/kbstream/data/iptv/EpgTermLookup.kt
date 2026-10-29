package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow

/**
 * The sports hub's per-matchup guide lookup, in two stages.
 *
 * Stage one is the FTS index ([IptvRepository.searchProgramsByTitleTerms]),
 * which is what a scoreboard pass can afford to ask about every game: it is
 * indexed, so an empty answer costs nothing.
 *
 * Stage two is the `LIKE` scan ([IptvRepository.searchProgramsByTitleTermsLike]),
 * and it runs ONLY when the index answered nothing. That gate is the whole
 * design: a matchup the index served pays nothing at all, so this cannot widen
 * a match or add rows to a card that already found its feeds - it only covers
 * the branch where index coverage, not the guide, was the reason a row was
 * missing (Kenny, 2026-10-29: a STRONG guide row for Jets @ Lightning that the
 * hub never listed).
 *
 * [maxFallbacks] bounds the scans per pass. A scan reads every program that has
 * not finished yet - that is the cost `searchProgramsByTitleTerms` was written
 * to avoid paying per game - so a pass may fall back only so many times, and the
 * rest of the slate keeps its empty answer. Twenty is generous for a real
 * matchup (a game the guide carries usually answers on the first stage) and
 * small enough that a completely unindexed guide cannot turn a pass into a
 * minute of scanning.
 *
 * Pure: it takes the two lookups as functions, so the gate and the bound are
 * testable without a database, and the ViewModel keeps the I/O.
 */
internal class EpgTermLookup(
    private val indexed: suspend (terms: List<String>, limit: Int) -> List<EpgProgramRow>,
    private val like: suspend (terms: List<String>, limit: Int) -> List<EpgProgramRow>,
    private val maxFallbacks: Int = MAX_LIKE_FALLBACKS,
) {

    /** What one question answered, and which stage answered it. */
    data class Hit(
        val rows: List<EpgProgramRow>,
        /** True when the index was empty and the scan ran. */
        val fallback: Boolean,
    )

    private var fallbacksLeft = maxFallbacks

    /** Scans still allowed this pass; 0 means every further empty answer stays empty. */
    val fallbacksRemaining: Int get() = fallbacksLeft

    /** A new pass starts with a full scan budget. */
    fun resetBudget() {
        fallbacksLeft = maxFallbacks
    }

    suspend fun rows(terms: List<String>, limit: Int): Hit {
        // The FTS side needs two terms to be the AND-of-two-teams query it is;
        // one term would pull the guide's whole back catalogue in.
        if (terms.count { it.isNotBlank() } < 2) return Hit(emptyList(), fallback = false)
        val found = indexed(terms, limit)
        if (found.isNotEmpty()) return Hit(found, fallback = false)
        if (fallbacksLeft <= 0) return Hit(emptyList(), fallback = false)
        fallbacksLeft--
        return Hit(like(terms, limit), fallback = true)
    }

    companion object {
        /** Scans one matching pass may spend on empty index answers. */
        const val MAX_LIKE_FALLBACKS = 20
    }
}
