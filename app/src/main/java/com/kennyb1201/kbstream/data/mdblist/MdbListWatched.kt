package com.kennyb1201.kbstream.data.mdblist

/**
 * MDBList's watched snapshot, turned into the shape the watched-state layer
 * needs: episodes per show.
 *
 * The snapshot itself is a flat set of keys - `"tt1234567:2:5"` and
 * `"tmdb:456:2:5"` for the same episode, because the tracker writes an imdb key
 * and a tmdb key for every show it knows both ids for (see MdbListClient.addKey)
 * - so anything asking "what has this show watched?" had to prefix-scan the
 * whole set per show. That is fine once and pathological per poster, and the
 * watched-state preload runs for every visible poster.
 *
 * Both id forms are kept here rather than collapsed: a show is looked up by
 * whichever forms the caller knows (the same `tt...` / `tmdb:<n>` pair the app
 * resolves for its own history), and a tmdb-only entry still answers.
 */

/**
 * The snapshot's watched episodes, indexed by the show key each entry names.
 *
 * Malformed entries are skipped rather than guessed at: a key with no numeric
 * season/episode, or with a zero number (the sources' "no episode"), proves
 * nothing about progress, and counting it could make a show read as caught up
 * on evidence that does not exist.
 */
internal fun mdbListWatchedEpisodesByShow(
    episodeKeys: Set<String>
): Map<String, Set<Pair<Int, Int>>> {
    if (episodeKeys.isEmpty()) return emptyMap()

    val byShow = LinkedHashMap<String, MutableSet<Pair<Int, Int>>>()

    episodeKeys.forEach { key ->
        val parts = key.split(':')
        if (parts.size < 3) return@forEach

        val season = parts[parts.size - 2].toIntOrNull() ?: return@forEach
        val episode = parts[parts.size - 1].toIntOrNull() ?: return@forEach
        if (season <= 0 || episode <= 0) return@forEach

        val showKey = parts.dropLast(2).joinToString(":")
        if (showKey.isBlank()) return@forEach

        byShow.getOrPut(showKey) { linkedSetOf() } += season to episode
    }

    return byShow
}

/**
 * One show's watched episodes, from whichever of [showKeys] the index knows.
 *
 * The forms are unioned rather than tried in turn: a show the tracker lists
 * under both ids has its episodes split across the two - a device that only
 * ever pushed the tmdb id, say - and reading just one form would undercount the
 * show's progress.
 */
internal fun Map<String, Set<Pair<Int, Int>>>.watchedEpisodesFor(
    showKeys: List<String>
): Set<Pair<Int, Int>> {
    if (showKeys.isEmpty() || isEmpty()) return emptySet()

    val merged = linkedSetOf<Pair<Int, Int>>()

    showKeys.forEach { key ->
        val trimmed = key.trim()
        if (trimmed.isBlank()) return@forEach
        this[trimmed]?.let { merged += it }
    }

    return merged
}
