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
    /**
     * ESPN's own short name - "Yankees" for the New York Yankees, "Red Sox" for
     * Boston. It is what a heading reads best with (a game reminder's title),
     * and it is the ONE place the feed's nickname is unambiguous: taking the
     * last word of the display name gives "Sox" and "Angels" alike. Null when
     * the feed carries none (a person), where the display name is used instead.
     */
    val shortName: String? = null,
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
    /**
     * The situation line of a live game, in the feed's own terms: "3rd & 7 ·
     * Ball on NE 32" for football, "Top 5th · 1 out" for baseball. Null
     * everywhere else - an upcoming or final game has no situation, and a
     * league whose scoreboard carries none (soccer, hockey, the individual
     * sports) shows no line rather than a placeholder. Parsed from the same
     * scoreboard payload as [statusDetail], so it costs no extra request; see
     * `espnSituation`.
     */
    val situation: String? = null,
    /**
     * ESPN's own id of the side with the ball, for a live football game whose
     * feed names a possession that resolves to one of THIS game's competitors.
     * Null everywhere else, and null rather than guessed when the feed names
     * none or names an id the competition does not carry - the same "don't
     * guess" rule [situation] follows, and from the same parse, so the sheet's
     * marker and the card's sentence can never point at different sides.
     */
    val possessionTeamId: String? = null,
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
    /**
     * The game's top performers, up to two - one per side - when ESPN carries
     * them on the competition. Empty for most sports and for a game the feed
     * has no leader block for; the detail sheet skips the section entirely
     * rather than forcing one.
     */
    val leaders: List<GameLeader> = emptyList(),
)

/**
 * What ESPN's summary endpoint carries for one game.
 *
 * A separate shape from [SportsGame] because it is a separate and much heavier
 * document (`.../summary?event={id}`), fetched LAZILY - only while a live game's
 * detail sheet is open - and never by a card. Every field is nullable/empty on
 * purpose: the summary's shape varies by league, and a league ESPN covers
 * thinly (or not at all) has to degrade to the plain sheet rather than to an
 * error.
 */
data class EspnGameSummary(
    /**
     * Paired stat rows: label + away/home display values, e.g.
     * ("Total Yards", "312", "298"). Only the labels ESPN actually sends for
     * BOTH sides are kept, so a row is never a pair of dashes; empty means the
     * sheet draws no stats table at all.
     */
    val teamStats: List<Triple<String, String, String>> = emptyList(),
    /** 0..100, home-team win chance; null when ESPN doesn't carry it. */
    val homeWinProbability: Int? = null,
    /** Most recent play text, e.g. "J. Allen pass to S. Diggs for 14 yds". Null when absent. */
    val lastPlay: String? = null,
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
) {
    /**
     * The key a correction for this event is remembered under.
     *
     * The event's own NAME, lowercased, where a team is keyed by its ESPN id.
     * The inversion is deliberate: a golf tournament's id is per-round on some
     * feeds, so keying on it would forget a viewer's picked channel between
     * days, while "Baycurrent Classic" is the same string all week and is how
     * the viewer refers to it. The id is the fallback for a feed that carries
     * no name, exactly as a team's display name is its fallback.
     */
    val favoriteKey: String
        get() = name.trim().lowercase().takeIf { it.isNotEmpty() } ?: id.trim()
}

/** One top performer in a game, as ESPN carries them on the competition. */
data class GameLeader(
    val name: String,
    /** The side they play for, so the sheet can put each line under its team. */
    val teamAbbreviation: String,
    /** ESPN's own summary line, e.g. "28 PTS, 11 REB". */
    val summary: String,
)

/**
 * One row of a league table.
 *
 * The three W/L/T counts are read as numbers; everything else is the string
 * ESPN sends, held verbatim. That split is deliberate: a count is something the
 * hub might sort or total, while a win percentage ("1.000"), a games-behind
 * ("-", "4.5") and a streak ("W4") are the feed's own display text, and
 * re-deriving them from the counts would be a second opinion the app cannot
 * check.
 */
data class StandingEntry(
    val abbreviation: String,
    val displayName: String,
    val logoUrl: String?,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    /** "1.000" as ESPN sends it; blank when the feed carries none (soccer). */
    val winPercent: String,
    /** "-" for the group leader, the games-back string otherwise; blank when absent. */
    val gamesBehind: String,
    /** "W4"; blank when the feed carries none. */
    val streak: String,
)

/**
 * One division (or conference, for a league the feed does not split) of a
 * league table.
 */
data class StandingGroup(
    val name: String,
    val entries: List<StandingEntry>,
    /**
     * The conference this group belongs to when the feed nests divisions inside
     * one ("AFC" for "AFC East"). Null when the group is itself the top level -
     * a single-table league, or a conference the feed does not split.
     *
     * Kept because the two-column table puts a conference's divisions in one
     * column, and by the time the groups reach the screen the nesting is gone:
     * NHL and NBA division names ("Atlantic", "Central") say nothing about which
     * conference they sit in, so the split cannot be re-derived from [name].
     */
    val conference: String? = null,
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
    /**
     * Whether ESPN publishes a standings table for this league.
     *
     * Static, like the catalog itself: the individual sports (tennis, MMA) have
     * no team table at all, and golf and F1 are tournaments rather than
     * standings. The hub hides the STANDINGS toggle for a league that has none
     * rather than offering a view that can only ever be empty.
     */
    val hasStandings: Boolean = false,
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
        SportsLeague("football/nfl", "NFL", SportsKind.HEAD_TO_HEAD, hasStandings = true),
        SportsLeague("basketball/nba", "NBA", SportsKind.HEAD_TO_HEAD, hasStandings = true),
        SportsLeague("baseball/mlb", "MLB", SportsKind.HEAD_TO_HEAD, hasStandings = true),
        SportsLeague("hockey/nhl", "NHL", SportsKind.HEAD_TO_HEAD, hasStandings = true),
        SportsLeague("basketball/wnba", "WNBA", SportsKind.HEAD_TO_HEAD, hasStandings = true),
        // The long-form ESPN spellings, not the "ncf"/"ncb" shorthands the
        // site's own URLs use: `site.api.espn.com/.../football/ncf/scoreboard`
        // and `/basketball/ncb/scoreboard` answer HTTP 400 every time, so those
        // two tabs could only ever render "Couldn't reach ESPN".
        SportsLeague(
            "football/college-football",
            "College Football",
            SportsKind.HEAD_TO_HEAD,
            hasStandings = true
        ),
        SportsLeague(
            "basketball/mens-college-basketball",
            "College Basketball",
            SportsKind.HEAD_TO_HEAD,
            hasStandings = true
        ),
        SportsLeague("soccer/eng.1", "Premier League", SportsKind.HEAD_TO_HEAD, hasStandings = true),
        SportsLeague("soccer/esp.1", "La Liga", SportsKind.HEAD_TO_HEAD, hasStandings = true),
        SportsLeague("soccer/uefa.champions", "Champions League", SportsKind.HEAD_TO_HEAD, hasStandings = true),
        SportsLeague("soccer/usa.1", "MLS", SportsKind.HEAD_TO_HEAD, hasStandings = true),
        // Individual sports: no team table exists, so the hub never offers one.
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

    /**
     * Every league in [ALL], on out of the box.
     *
     * The whole catalog rather than a US-league starter set. The hub is a
     * scoreboard, and a viewer who watches one sport is served just as well by
     * the leagues panel as one who watches them all - while a default that
     * silently hides a sport leaves its games reading "No games today" for no
     * reason the viewer can see. A league out of season renders its own empty
     * state and costs nothing; the panel's toggles are what narrow the hub to
     * whatever a profile actually follows.
     *
     * Built from [ALL] rather than a hand-written list so a league added to the
     * catalog is on by default too, instead of being invisible until someone
     * remembers to touch this set.
     */
    val DEFAULT_ENABLED: Set<String> = ALL.map { it.path }.toSet()

    fun byPath(path: String): SportsLeague? = ALL.firstOrNull { it.path == path }

    /**
     * The leagues to render, in the viewer's order, for an enabled-path set.
     *
     * Unknown paths in [enabled] are ignored: the hub renders tabs only for
     * enabled leagues, and a league this build does not know cannot be one.
     * [order] is the rearranged order from the leagues panel; empty means
     * catalog order, which is what a fresh install has.
     */
    fun enabled(enabled: Set<String>, order: List<String> = emptyList()): List<SportsLeague> =
        ordered(order).filter { it.path in enabled }

    /**
     * The whole catalog in the viewer's own order.
     *
     * [order] is a run of league paths, as arranged by the leagues panel's move
     * controls. A path it names is ranked by its position; everything else - a
     * league this build added after the order was written, a path it no longer
     * knows - keeps catalog order behind those. `sortedBy` is stable, so two
     * unranked leagues do not swap places from one read to the next.
     */
    fun ordered(order: List<String>): List<SportsLeague> {
        if (order.isEmpty()) return ALL
        val rank = order.withIndex().associate { (index, path) -> path to index }
        return ALL.sortedBy { rank[it.path] ?: Int.MAX_VALUE }
    }
}
