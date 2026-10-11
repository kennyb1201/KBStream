package com.kennyb1201.kbstream.data.sports

/**
 * The one rewrite a favourites store needs: keys written before they were
 * league-scoped, re-expressed against the slate the hub just loaded.
 *
 * The keys used to be the team's bare ESPN id (see
 * [SportsTeam.bareFavoriteKey]). That is unique within a league and nowhere
 * else, so a viewer who followed the NBA's Lakers also had the NFL's Raiders
 * starred, and a game nobody followed climbed the slate - which is the bug this
 * exists to clean up after. [SportsTeam.favoriteKey] is scoped now, and every
 * read matches the scoped form, so a store still holding a bare id would simply
 * stop matching: the viewer's teams would go quiet rather than follow them onto
 * the new format.
 *
 * [migrate] is the adoption, and it is deliberately a rewrite against the
 * LOADED SLATE rather than a format rule: only the feed knows which league an
 * id belongs to. A key that names a side in more than one league (two leagues
 * both publish id 13, and both are on today) expands to the scoped key of each
 * - the viewer's original set, now spelled out. A key that names nothing on the
 * board is handed back untouched rather than dropped, so a team that is not
 * playing today is not lost: the rewrite runs on every refresh, and the key
 * migrates the first time its team appears.
 *
 * Pure, and a file of its own for the reason the hub's other rules are: the
 * interesting cases are all of the "an id that means three different teams"
 * shape a unit test can pin exactly, with no TV and no ESPN payload. See
 * [SportsFavoriteKeysTest].
 */
internal object SportsFavoriteKeys {

    /**
     * [stored] with every legacy key replaced by the league-scoped key of each
     * side of [games] it names; keys that already name a side, and keys that
     * name none, are kept exactly as they are.
     *
     * Returns [stored] ITSELF when nothing had to move, so the caller can use
     * an identity comparison to know whether a write is worth doing - the
     * ordinary case is a store that was migrated on a previous run.
     */
    fun migrate(stored: Set<String>, games: List<SportsGame>): Set<String> {
        if (stored.isEmpty() || games.isEmpty()) return stored

        val sides = games.flatMap { game -> listOf(game.away, game.home) }
        // The scoped keys actually on the slate, so an already-migrated store
        // is recognised without having to parse the key format back apart.
        val onSlate = sides.mapTo(HashSet()) { side -> side.favoriteKey }
        // Every side a legacy key could mean, by the bare key it was stored
        // under. Linked so the expansion keeps the feed's own order.
        val byBareKey = HashMap<String, MutableSet<String>>()
        sides.forEach { side ->
            byBareKey.getOrPut(side.bareFavoriteKey) { LinkedHashSet() } += side.favoriteKey
        }

        var rewrote = false
        val migrated = LinkedHashSet<String>(stored.size)
        stored.forEach { key ->
            if (key in onSlate) {
                migrated += key
                return@forEach
            }
            val expansion = byBareKey[key]
            if (expansion.isNullOrEmpty()) {
                migrated += key
            } else {
                migrated += expansion
                if (key !in expansion) rewrote = true
            }
        }
        return if (rewrote) migrated else stored
    }
}
