package com.kennyb1201.kbstream.data.watched

/**
 * The watched keys one browse tile can have, and the rule that decides which of
 * them it emits.
 *
 * A tile in a TMDB browse rail (genre/keyword, studio/network/service, decade,
 * collection) is named by its TMDB id. The app's watch state, though, has
 * always been keyed by IMDB id — so every one of those screens resolved
 * TMDB→IMDB first and badged off the result. That pass is capped at 300 ids per
 * load (the guard against flooding TMDB's `/external_ids`), while a six-rail
 * page carries up to ~840 items, so everything past the cap had no IMDB id, no
 * key, and no badge for the whole visit — and because those pages put MOVIES×3
 * before SERIES×3, the cap starved the series rails systematically, which is the
 * "many posters show no eye badge and no checkmark" field report.
 *
 * The raw `tmdb:<n>` flavor needs no resolution at all, and the repository
 * already stores and answers both forms under the same `"type::id"` key (see
 * [WatchedStatusRepository]); HomeViewModel's watched refresh preloads exactly
 * that form, uncapped, for its whole rail list. This is that same rule, in one
 * place: emit the IMDB key when it matches, emit the `tmdb:` key when it
 * matches, and emit both when both do.
 *
 * Pure and suspend-free except for the caller's own matcher, so the rule is
 * unit-tested directly ([WatchedStatusKeysTest]) instead of through four
 * Android view models that construct their repository privately.
 */
internal object WatchedStatusKeys {

    /**
     * `"series::tmdb:123"` — the key shape every watched key in the app uses.
     *
     * The type is LOWERCASED here, and only lowercased: that kills a casing
     * mismatch (`"TV"` vs `"tv"`) on its own, which is the difference the
     * search screen's tiles used to lose every badge to. Mapping a raw spelling
     * onto the app's own one — `tv`/`show` → `series` — is deliberately NOT
     * done here; that is the caller's job (`normalizeMediaType`, or
     * `normalizedType` on the search screen), and every call site normalizes
     * before the type reaches this function. Doing it in both places would put
     * the mapping in two homes and let them drift.
     */
    fun key(id: String, type: String): String = "${type.lowercase()}::$id"

    /** The id flavor a rail item's own TMDB id is keyed under. */
    fun tmdbKeyId(tmdbId: Int): String = "tmdb:$tmdbId"

    /**
     * The keys [tmdbId] is watched under for [mediaType], in candidate order:
     * the resolved IMDB flavor first, the raw TMDB one second.
     *
     * [resolvedImdbId] is whatever the resolution pass produced — null when it
     * never reached this item (past the cap), when it answered nothing (TMDB has
     * no `imdb_id` for the title), or when it failed. [matches] is the caller's
     * own test for one id flavor against the state it cares about (fully watched,
     * or started-but-not-finished), so a title matching under both flavors
     * contributes both keys and the tile's own IMDB check still hits first.
     */
    suspend fun watchedKeysOf(
        tmdbId: Int,
        mediaType: String,
        resolvedImdbId: String?,
        matches: suspend (id: String, type: String) -> Boolean
    ): List<String> =
        listOfNotNull(resolvedImdbId, tmdbKeyId(tmdbId))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { matches(it, mediaType) }
            .map { key(it, mediaType) }
}
