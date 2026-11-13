package com.kennyb1201.kbstream.data.anime

/**
 * What the anime ID hub knows about one title (spec 2026-11-13, F1).
 *
 * Its own file, with no Android and no network: the Detail header's two labels
 * read it, the tracker legs compare it, and both are JVM-testable because of it.
 */
internal data class AnimeIdentity(
    val anilistId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
    /**
     * AniList's community score, 0-100 (`averageScore`), or null when it has
     * none. Shown as `/10` where the Detail screen renders it.
     */
    val averageScore: Int? = null,
    /** The next episode AniList knows about, when it knows one. */
    val nextAiringEpisode: Int? = null,
    /** Epoch SECONDS (AniList's `airingAt`), or null. */
    val nextAiringAtSeconds: Long? = null,
    /**
     * MAL's community score (`mean`, 0-10, one decimal) and its rank, when MAL
     * has them. Fetched with the same call that resolves the MAL id (spec
     * 2026-11-13, F4) - never a second MAL request - and absent, not zero, for a
     * title MAL has not scored.
     */
    val malScore: Double? = null,
    val malRank: Int? = null
) {
    val hasAnyId: Boolean
        get() = anilistId != null || malId != null || kitsuId != null
}
