package com.kennyb1201.kbstream.data.sports

/**
 * The sports hub's data model: one shape for a head-to-head game, one for a
 * tournament event.
 *
 * Two models rather than one because the two are genuinely different questions.
 * A game has two sides and a winner; a golf tournament or a Grand Prix has a
 * field and a leaderboard, and forcing it into home/away produces a card with
 * an empty half. [SportsLeague.kind] says which shape a league's scoreboard
 * carries, and the hub renders the matching card.
 */

/** One side of a head-to-head game. */
data class SportsTeam(
    /**
     * ESPN's own team (or athlete) id. It is what a favourite is stored under,
     * so it has to survive a rename and a move between feeds - see
     * [favoriteKey] for the fallback when the feed carries none.
     */
    val id: String? = null,
    /** ESPN's short code, e.g. "LAL". */
    val abbreviation: String,
    /** ESPN's full name, e.g. "Los Angeles Lakers". */
    val displayName: String,
    /**
     * The side's mark: the club crest, or - for a sport whose "sides" are
     * people - the player's headshot, or their country flag when ESPN carries
     * no headshot. Null only when the feed offers none of the three, which is
     * when the card prints initials on the same plate.
     */
    val logoUrl: String?,
    /** Points/sets/runs as ESPN reports them; null before the game starts. */
    val score: String?,
    val isHome: Boolean,
    /** "41-12", or null when the feed carries no record (tennis, MMA). */
    val record: String?,
    /**
     * ESPN's own club colour, as a bare hex string ("860038"); the card tints
     * the mark's plate with it so a crest sits on its club's colour rather than
     * on grey. Null when the feed gives none (a person, a tournament stop), and
     * unusable values are dropped when the plate is drawn.
     */
    val colorHex: String? = null,
) {
    /**
     * The key a favourite is stored under.
     *
     * ESPN's id where there is one, and the display name where there is not -
     * which is how a person is keyed (a tennis player, a fighter), and they are
     * precisely the names the feed is unambiguous about. Stored, not computed
     * per call site, so a team favourited on one screen is matchable on
     * another.
     */
    val favoriteKey: String
        get() = id?.trim()?.takeIf { it.isNotEmpty() } ?: displayName.trim().lowercase()
}

/**
 * Whether either side of [this] game is one of the followed [favoriteKeys].
 *
 * A free function rather than a method so the hub's filter and its tests read
 * the same rule, and so nothing on the data model has to know that a favourites
 * tab exists.
 */
fun SportsGame.involvesFavorite(favoriteKeys: Set<String>): Boolean =
    away.favoriteKey in favoriteKeys || home.favoriteKey in favoriteKeys

/** Where a game is in its own timeline. */
enum class GameState { LIVE, UPCOMING, FINAL }

/** One head-to-head fixture. */
data class SportsGame(
    /** ESPN event id. */
    val id: String,
    /** The ESPN path this came from, e.g. "basketball/nba". */
    val league: String,
    /** ESPN's own event name, e.g. "Lakers at Celtics". */
    val name: String,
    /** Scheduled start, epoch millis. */
    val dateMs: Long,
    val state: GameState,
    /** "Q3 4:32" live, "7:30 PM ET" upcoming, "Final" after. */
    val statusDetail: String,
    val away: SportsTeam,
    val home: SportsTeam,
    /** Networks airing it, e.g. ["ESPN"] - the bridge to the playlist. */
    val broadcastNames: List<String>,
    /** Where it is played, "Rocket Arena · Cleveland, OH"; null when unknown. */
    val venue: String? = null,
    /**
     * ESPN's own one-line context for the event - "ALDS - Game 4" on a
     * postseason game, "Preseason" on an exhibition. It says WHY this fixture
     * is on now, which the date and the network cannot. Null when there is
     * none, which is most of the regular season.
     */
    val note: String? = null,
    /**
     * The week of a football season, as ESPN's own `week.number` reads it:
     * "Week 6". Null for every other sport, and for a football event the feed
     * does not put on a numbered week (a bowl, the playoffs). It is a card's
     * context line where it exists, because a schedule is read in weeks - see
     * the hub's card, which prefers it to the venue.
     */
    val week: String? = null,
)

/** One row of a tournament leaderboard. */
data class Leader(
    val name: String,
    /** Golf: "-8" relative to par. Racing: "P3" or the gap. */
    val score: String,
    /**
     * The player's country flag, which is the only mark ESPN carries for a
     * golfer or a driver (their athletes have a flag and no crest). Null when
     * the row has none - then the leaderboard row is just its rank and name.
     */
    val flagUrl: String? = null,
)

/** One tournament event: a golf round or a Grand Prix. */
data class TournamentEvent(
    val id: String,
    val league: String,
    val name: String,
    val dateMs: Long,
    val state: GameState,
    /** "Round 1 - Play Complete". */
    val statusDetail: String,
    /** Top five, best first; empty when the feed carries no leaderboard yet. */
    val leaders: List<Leader>,
    val broadcastNames: List<String>,
    /**
     * The stop or course - an F1 event carries its `circuit`, a golf round and
     * a tennis tournament their venue. Null when the feed names neither.
     */
    val venue: String? = null,
    /** ESPN's own context line, as on [SportsGame.note]. */
    val note: String? = null,
)

/** The shape a league's scoreboard carries. */
enum class SportsKind { HEAD_TO_HEAD, TOURNAMENT }

/**
 * One league the hub can show.
 *
 * [path] is the ESPN URL segment (`site.api.espn.com/apis/site/v2/sports/$path/scoreboard`)
 * and doubles as the stable id the enabled-set is stored under.
 */
data class SportsLeague(
    val path: String,
    val label: String,
    val kind: SportsKind,
)

/**
 * The catalog of leagues, and the four the hub opens with.
 *
 * A static list, deliberately: the ESPN path is the only thing that has to be
 * right per league, and a data-driven list is what lets the Settings toggles be
 * generated instead of hand-written. Boxing is absent on purpose - ESPN covers
 * it as news rather than structured events, so there is no schedule to list.
 */
object SportsLeagues {

    val ALL: List<SportsLeague> = listOf(
        SportsLeague("football/nfl", "NFL", SportsKind.HEAD_TO_HEAD),
        SportsLeague("basketball/nba", "NBA", SportsKind.HEAD_TO_HEAD),
        SportsLeague("baseball/mlb", "MLB", SportsKind.HEAD_TO_HEAD),
        SportsLeague("hockey/nhl", "NHL", SportsKind.HEAD_TO_HEAD),
        SportsLeague("basketball/wnba", "WNBA", SportsKind.HEAD_TO_HEAD),
        SportsLeague("football/ncf", "College Football", SportsKind.HEAD_TO_HEAD),
        SportsLeague("basketball/ncb", "College Basketball", SportsKind.HEAD_TO_HEAD),
        SportsLeague("soccer/eng.1", "Premier League", SportsKind.HEAD_TO_HEAD),
        SportsLeague("soccer/esp.1", "La Liga", SportsKind.HEAD_TO_HEAD),
        SportsLeague("soccer/uefa.champions", "Champions League", SportsKind.HEAD_TO_HEAD),
        SportsLeague("soccer/usa.1", "MLS", SportsKind.HEAD_TO_HEAD),
        SportsLeague("tennis/atp", "Tennis", SportsKind.HEAD_TO_HEAD),
        SportsLeague("mma/ufc", "UFC", SportsKind.HEAD_TO_HEAD),
        SportsLeague("mma/pfl", "PFL", SportsKind.HEAD_TO_HEAD),
        SportsLeague("golf/pga", "Golf", SportsKind.TOURNAMENT),
        SportsLeague("racing/f1", "F1", SportsKind.TOURNAMENT),
    )

    /**
     * The hub's own favourites tab.
     *
     * Not an ESPN path - there is no league behind it - so it is spelled in a
     * way no real path can collide with. [SportsLeagues.enabled] never returns
     * it and the leagues panel never lists it: it is a view over the leagues
     * the viewer turned on, not one of them.
     */
    val FAVORITES: SportsLeague =
        SportsLeague(FAVORITES_PATH, "Favorites", SportsKind.HEAD_TO_HEAD)

    /** The favourites tab's id, which is also the hub's selected-tab value. */
    const val FAVORITES_PATH = "sports:favorites"

    /** The four US leagues every install starts with. */
    val DEFAULT_ENABLED: Set<String> = setOf(
        "football/nfl",
        "basketball/nba",
        "baseball/mlb",
        "hockey/nhl",
    )

    fun byPath(path: String): SportsLeague? = ALL.firstOrNull { it.path == path }

    /**
     * The leagues to render, in catalog order, for an enabled-path set.
     *
     * Unknown paths in [enabled] are ignored: the hub renders tabs only for
     * enabled leagues, and a league this build does not know cannot be one.
     */
    fun enabled(enabled: Set<String>): List<SportsLeague> =
        ALL.filter { it.path in enabled }
}
