package com.kennyb1201.kbstream.ui.search

/**
 * Curated entries for the Search screen's browse browser (the browsable
 * replacement for the empty "no results" whitespace). Sidebar categories
 * open a submenu; an entry opens an existing app screen:
 *
 *  - Genres / Keywords      -> Screen.Tag (genre / keyword discover)
 *  - Services & Networks    -> Screen.Studio (service entries carry a
 *                             watch-provider id, so their page runs MOVIES
 *                             + SERIES provider rails; plain network
 *                             entries keep the series-only network page)
 *  - Studios                -> Screen.Studio (company discover)
 *  - Collections            -> Screen.Collection
 *  - Decades                -> Screen.Decade (per-decade page, genre-style
 *                             rails minus RECENT, movies and series kept
 *                             separate)
 *
 * Network ids below were verified against themoviedb.org/network/{id} pages;
 * company ids against themoviedb.org/company/{id}; watch-provider ids
 * against TMDB's /watch/providers registry. Keyword and collection ids are
 * NOT hand-maintained: they resolve at runtime from /search/keyword and
 * /search/collection so they can never rot.
 */

/**
 * One selectable entry in a browse submenu. [id] is the TMDB network or
 * company id that the destination screen discovers with; the three
 * provider fields (all null for plain network entries) additionally let a
 * service page run watch-provider rails for everything "On Now" in the
 * US region — see [BROWSE_PROVIDER_ENTRIES].
 */
data class BrowseEntry(
    val id: Int,
    val name: String,
    val providerId: Int? = null,
    val networkOrCompanyId: Int? = null,
    val networkIsCompany: Boolean = false
)

/**
 * A streaming service with both a watch-provider id (everything "On Now"
 * on the service, in US watch region — used by the Recent / Popular /
 * Most Voted rails) and its network/company id (what it produced — the
 * "Originals" rail). Null network/company id means the service has no
 * meaningful originals catalog (Tubi, Pluto, ...), so it only offers the
 * provider rails.
 */
data class BrowseService(
    val name: String,
    val providerId: Int?,
    val networkOrCompanyId: Int?,
    val networkIsCompany: Boolean
)

/** One sidebar category and its statically-known submenu entries. */
data class BrowseCategory(
    val key: String,
    val label: String,
    val entries: List<BrowseEntry>
)

// ---------------------------------------------------------------------------
// Genres — stable TMDB ids (the movie-discover id; TV uses its own where
// different). Genre rails open Screen.Tag, which handles both media types.
// ---------------------------------------------------------------------------

val BROWSE_GENRES = listOf(
    BrowseEntry(28, "Action"),
    BrowseEntry(12, "Adventure"),
    BrowseEntry(16, "Animation"),
    BrowseEntry(35, "Comedy"),
    BrowseEntry(80, "Crime"),
    BrowseEntry(99, "Documentary"),
    BrowseEntry(18, "Drama"),
    BrowseEntry(10751, "Family"),
    BrowseEntry(14, "Fantasy"),
    BrowseEntry(36, "History"),
    BrowseEntry(27, "Horror"),
    BrowseEntry(10402, "Music"),
    BrowseEntry(9648, "Mystery"),
    BrowseEntry(10749, "Romance"),
    BrowseEntry(878, "Science Fiction"),
    BrowseEntry(10770, "TV Movie"),
    BrowseEntry(53, "Thriller"),
    BrowseEntry(10752, "War"),
    BrowseEntry(37, "Western"),
    // TV-only genre ids (action/adventure, kids, news, reality, sci-fi,
    // soap, talk, war & politics). Screen.Tag discovers both media types,
    // and TV discover honors them.
    BrowseEntry(10759, "Action & Adventure (TV)"),
    BrowseEntry(10762, "Kids (TV)"),
    BrowseEntry(10763, "News (TV)"),
    BrowseEntry(10764, "Reality (TV)"),
    BrowseEntry(10765, "Sci-Fi & Fantasy (TV)"),
    BrowseEntry(10766, "Soap (TV)"),
    BrowseEntry(10767, "Talk (TV)"),
    BrowseEntry(10768, "War & Politics (TV)")
)

// ---------------------------------------------------------------------------
// Keywords: resolved at runtime from /search/keyword — hand-maintained
// keyword ids rot.
// ---------------------------------------------------------------------------

val BROWSE_KEYWORD_NAMES = listOf(
    "zombie",
    "found footage",
    "time travel",
    "dystopia",
    "superhero",
    "space",
    "vampire",
    "heist",
    "post-apocalyptic future",
    "false accusation",
    "serial killer",
    "supernatural",
    "based on a true story",
    "black comedy",
    "psychological thriller",
    "slasher",
    "alien",
    "dinosaur",
    "revenge",
    "survival",
    "cyberpunk",
    "kaiju",
    "monster",
    "haunted house",
    "coming of age",
    "murder",
    "prison",
    "martial arts",
    "wedding",
    "high school",
    // Second wave — every name verified to resolve exactly via
    // /search/keyword with real discover depth (100+ movies each).
    "werewolf",
    "wizard",
    "dragon",
    "pirate",
    "samurai",
    "ninja",
    "spy",
    "assassin",
    "detective",
    "courtroom",
    "survival horror",
    "zombie apocalypse",
    "alien invasion",
    "robot",
    "boxing",
    "road trip",
    "sports",
    "dancing",
    "chef",
    "desert",
    "island",
    "snow",
    "submarine",
    "airplane",
    "train",
    "school",
    "college",
    "friendship",
    "love triangle",
    "single father",
    "single mother",
    "dysfunctional family",
    "inheritance",
    "ghost",
    "exorcism",
    "cult",
    "witch",
    "time loop",
    "infidelity",
    "addiction",
    "experiment",
    "world war ii",
    "vietnam war",
    "cold war",
    "civil war"
)

// ---------------------------------------------------------------------------
// Networks — verified against themoviedb.org/network/{id}.
// ---------------------------------------------------------------------------

val BROWSE_NETWORKS = listOf(
    // Plain network pages (series rails only). Services above already carry
    // the big streamers, so the network list here intentionally excludes
    // Netflix/Prime/Disney+/Apple TV+/HBO/Hulu/Paramount+/Peacock — those
    // duplicates only ever rendered series-only pages next to the full
    // service pages.
    // ids verified live against TMDB /network/{id}:
    //  2=ABC, 6=NBC, 16=CBS, 19=FOX, 33=MTV (was mislabeled BBC Two),
    //  43=National Geographic (was mislabeled Starz), 4=BBC One,
    //  47=Comedy Central, 13=Nickelodeon, 56=Cartoon Network, 71=The CW,
    //  174=AMC, 80=Adult Swim, 75=ABC Family.
    BrowseEntry(2, "ABC"),
    BrowseEntry(6, "NBC"),
    BrowseEntry(16, "CBS"),
    BrowseEntry(19, "FOX"),
    BrowseEntry(33, "MTV"),
    BrowseEntry(43, "National Geographic"),
    BrowseEntry(4, "BBC One"),
    BrowseEntry(47, "Comedy Central"),
    BrowseEntry(13, "Nickelodeon"),
    BrowseEntry(56, "Cartoon Network"),
    BrowseEntry(71, "The CW"),
    BrowseEntry(174, "AMC"),
    BrowseEntry(80, "Adult Swim"),
    BrowseEntry(75, "ABC Family"),
    // Second wave — every id verified live via /network/{id} (name match):
    // premium cable, basic cable, and classic broadcast networks.
    BrowseEntry(49, "HBO"),
    BrowseEntry(30, "USA Network"),
    BrowseEntry(74, "Bravo"),
    BrowseEntry(88, "FX"),
    BrowseEntry(1035, "FXX"),
    BrowseEntry(41, "TNT"),
    BrowseEntry(68, "TBS"),
    BrowseEntry(77, "Syfy"),
    BrowseEntry(129, "A&E"),
    BrowseEntry(34, "Lifetime"),
    BrowseEntry(76, "E!"),
    BrowseEntry(24, "BET"),
    BrowseEntry(106, "Discovery"),
    BrowseEntry(143, "Food Network"),
    BrowseEntry(2076, "Paramount Network"),
    BrowseEntry(21, "The WB"),
    BrowseEntry(26, "Channel 4")
)

// ---------------------------------------------------------------------------
// Studios — verified against themoviedb.org/company/{id}.
// ---------------------------------------------------------------------------

val BROWSE_STUDIOS = listOf(
    BrowseEntry(174, "Warner Bros. Pictures"),
    BrowseEntry(2, "Walt Disney Pictures"),
    BrowseEntry(33, "Universal Pictures"),
    BrowseEntry(4, "Paramount Pictures"),
    BrowseEntry(5, "Columbia Pictures"),
    BrowseEntry(21, "Metro-Goldwyn-Mayer"),
    BrowseEntry(25, "20th Century Fox"),
    BrowseEntry(420, "Marvel Studios"),
    BrowseEntry(9993, "DC"),
    BrowseEntry(12, "New Line Cinema"),
    BrowseEntry(1632, "Lionsgate"),
    BrowseEntry(41077, "A24"),
    BrowseEntry(3172, "Blumhouse Productions"),
    BrowseEntry(3, "Pixar"),
    BrowseEntry(6704, "Illumination"),
    BrowseEntry(521, "DreamWorks Animation"),
    BrowseEntry(10342, "Studio Ghibli"),
    // Tail of this list previously off-by-one (each id was the NEXT
    // studio's): Legendary showed TOHO, Focus showed Legendary, Sony
    // Pictures Animation showed Focus, Regency showed Relativity, and
    // Working Title pointed at a dead id. All re-verified against
    // themoviedb.org/company/{id} + /search/company:
    //  882=TOHO, 923=Legendary, 508=Regency, 10146=Focus,
    //  2251=Sony Pictures Animation, 10163=Working Title Films.
    BrowseEntry(882, "TOHO"),
    BrowseEntry(923, "Legendary Pictures"),
    BrowseEntry(508, "Regency Enterprises"),
    BrowseEntry(10146, "Focus Features"),
    BrowseEntry(2251, "Sony Pictures Animation"),
    BrowseEntry(10163, "Working Title Films"),
    // Second wave — every id verified live via /company/{id} (name match)
    // and a /discover movie-count sanity check.
    BrowseEntry(56, "Amblin Entertainment"),
    BrowseEntry(79, "Village Roadshow Pictures"),
    BrowseEntry(97, "Castle Rock Entertainment"),
    BrowseEntry(60, "United Artists"),
    BrowseEntry(41, "Orion Pictures"),
    BrowseEntry(14, "Miramax"),
    BrowseEntry(491, "Summit Entertainment"),
    BrowseEntry(1088, "Alcon Entertainment"),
    BrowseEntry(2188, "Artisan Entertainment"),
    BrowseEntry(9195, "Touchstone Pictures"),
    BrowseEntry(11461, "Bad Robot"),
    BrowseEntry(10221, "Walden Media"),
    BrowseEntry(3281, "GK Films"),
    BrowseEntry(82819, "Skydance Media"),
    BrowseEntry(143790, "Spyglass Media Group"),
    BrowseEntry(559, "TriStar Pictures"),
    BrowseEntry(3287, "Screen Gems"),
    BrowseEntry(43, "Fox Searchlight Pictures"),
    BrowseEntry(7295, "Relativity Media")
)

// ---------------------------------------------------------------------------
// Streaming services. providerId drives the Recent / Popular / Most Voted
// rails (watch-provider discover with watch_region "US");
// networkOrCompanyId drives the "Originals" rail AND the header logo/detail
// (network discover for TV-first services, company discover for movie
// studios). ALL ids below verified live against TMDB:
//  - providers: Netflix=8, Prime=9, Disney+=337, Apple TV+=350,
//    HBO Max=1899 (384 is dead), Hulu=15, Paramount+=2303 ("Paramount Plus
//    Premium"; 531 is dead), Peacock=386, Starz=43, Tubi=73, Pluto=300,
//    Crunchyroll=283. Showtime has NO provider id any more (folded into
//    Paramount Plus Premium), so it runs as a plain network page.
//  - networks: Netflix=213, Prime Video=1024 (132 is Oxygen!),
//    Disney+=2739, Apple TV+=2552, HBO Max=3186, Hulu=453, Paramount+=4330,
//    Peacock=3353 (3186 is HBO Max!), Starz=318 (43 is National
//    Geographic!), Showtime=67, Tubi=5187, Pluto TV=3245, Crunchyroll=1112.
// ---------------------------------------------------------------------------

val BROWSE_SERVICES = listOf(
    BrowseService(
        "Netflix",
        providerId = 8,
        networkOrCompanyId = 213,
        networkIsCompany = false
    ),
    BrowseService(
        "Prime Video",
        providerId = 9,
        networkOrCompanyId = 1024,
        networkIsCompany = false
    ),
    BrowseService(
        "Disney+",
        providerId = 337,
        networkOrCompanyId = 2739,
        networkIsCompany = false
    ),
    BrowseService(
        "Apple TV+",
        providerId = 350,
        networkOrCompanyId = 2552,
        networkIsCompany = false
    ),
    BrowseService(
        "HBO Max",
        providerId = 1899,
        networkOrCompanyId = 3186,
        networkIsCompany = false
    ),
    BrowseService(
        "Hulu",
        providerId = 15,
        networkOrCompanyId = 453,
        networkIsCompany = false
    ),
    BrowseService(
        "Paramount+",
        providerId = 2303,
        networkOrCompanyId = 4330,
        networkIsCompany = false
    ),
    BrowseService(
        "Peacock",
        providerId = 386,
        networkOrCompanyId = 3353,
        networkIsCompany = false
    ),
    BrowseService(
        "Starz",
        providerId = 43,
        networkOrCompanyId = 318,
        networkIsCompany = false
    ),
    // Showtime's watch-provider id no longer exists in TMDB's US registry
    // (the content moved under Paramount Plus Premium), so it opens as a
    // plain network page (series rails) instead of showing empty rails.
    BrowseService(
        "Showtime",
        providerId = null,
        networkOrCompanyId = 67,
        networkIsCompany = false
    ),
    // Free/fast services now carry their (network) originals id so the
    // header gets a real clear-logo instead of falling back to text.
    BrowseService(
        "Tubi",
        providerId = 73,
        networkOrCompanyId = 5187,
        networkIsCompany = false
    ),
    BrowseService(
        "Pluto TV",
        providerId = 300,
        networkOrCompanyId = 3245,
        networkIsCompany = false
    ),
    BrowseService(
        "Crunchyroll",
        providerId = 283,
        networkOrCompanyId = 1112,
        networkIsCompany = false
    )
)

// Merged "Services & Networks" submenu: every streaming service (with its
// watch-provider id, so its screen gets Recent / Popular / Most Voted rails
// alongside Originals) followed by the TV network list — one screen per
// brand instead of separate Services and Networks categories.
val BROWSE_PROVIDER_ENTRIES: List<BrowseEntry> = BROWSE_SERVICES
    .map { service ->
        BrowseEntry(
            service.networkOrCompanyId ?: -1,
            service.name,
            service.providerId,
            service.networkOrCompanyId,
            service.networkIsCompany
        )
    } + BROWSE_NETWORKS

// ---------------------------------------------------------------------------
// Decades 2020s -> 1950s. Each decade opens Screen.Decade, whose rails keep
// movies and series separate like the genre/keyword screens.
// ---------------------------------------------------------------------------

val BROWSE_DECADES: List<BrowseEntry> = (2020 downTo 1950 step 10)
    .map { decade -> BrowseEntry(decade, "${decade}s") }

// ---------------------------------------------------------------------------
// Collections: famous franchises. ids resolved at RUNTIME from
// /search/collection (TMDB collection ids are easy to misremember; the
// search endpoint returns the canonical id for each name).
// ---------------------------------------------------------------------------

val BROWSE_COLLECTION_NAMES = listOf(
    "Star Wars Collection",
    "The Lord of the Rings Collection",
    "Harry Potter Collection",
    "The Avengers Collection",
    "Spider-Man Collection",
    "Toy Story Collection",
    "The Dark Knight Collection",
    "Jurassic Park Collection",
    "The Terminator Collection",
    "Mad Max Collection",
    "Pirates of the Caribbean Collection",
    "John Wick Collection",
    "The Conjuring Collection",
    "Mission: Impossible Collection",
    "The Fast and the Furious Collection",
    "Indiana Jones Collection",
    "X-Men Collection",
    "Transformers Collection",
    "Back to the Future Collection",
    "Despicable Me Collection",
    "How to Train Your Dragon Collection",
    "Saw Collection",
    "Alien Collection",
    "Predator Collection",
    // Second wave — every name verified to either hit exactly via
    // /search/collection or resolve to its canonical franchise page.
    "Batman Collection",
    "Superman Collection",
    "James Bond Collection",
    "Jaws Collection",
    "Die Hard Collection",
    "Rocky Collection",
    "Rambo Collection",
    "The Matrix Collection",
    "Ghostbusters Collection",
    "Men in Black Collection",
    "Lethal Weapon Collection",
    "Rush Hour Collection",
    "Bad Boys Collection",
    "Ocean's Collection",
    "The Mummy Collection",
    "National Treasure Collection",
    "Jumanji Collection",
    "Kingsman Collection",
    "Taken Collection",
    "The Bourne Collection",
    "The Transporter Collection",
    "The Hunger Games Collection",
    "Divergent Collection",
    "The Maze Runner Collection",
    "Percy Jackson Collection",
    "The Hobbit Collection",
    "The Chronicles of Narnia Collection",
    "Godzilla Collection",
    "Monsterverse Collection",
    "Planet of the Apes (Original) Collection",
    "Ice Age Collection",
    "Madagascar Collection",
    "Shrek Collection",
    "Kung Fu Panda Collection",
    "Finding Nemo Collection",
    "Monsters, Inc. Collection",
    "The Incredibles Collection",
    "Cars Collection",
    "Fantastic Four Collection",
    "Deadpool Collection",
    "Venom Collection",
    "Ghost Rider Collection",
    "Halloween Collection",
    "A Nightmare on Elm Street Collection",
    "Friday the 13th Collection",
    "Scream Collection",
    "Final Destination Collection",
    "Paranormal Activity Collection",
    "Insidious Collection",
    "Annabelle Collection",
    "American Pie Collection",
    "Scary Movie Collection",
    "The Naked Gun Collection",
    "Johnny English Collection",
    "Anchorman Collection",
    "Zoolander Collection",
    "The Hangover Collection",
    "Jackass Collection",
    "Star Trek: The Original Series Collection",
    "Star Trek: The Next Generation Collection",
    "Star Trek: Alternate Reality Collection",
    "Prometheus Collection",
    "AVP Collection"
)

/**
 * Sidebar categories. Keywords and Collections ship name lists resolved at
 * runtime (see SearchViewModel), so their [BrowseCategory.entries] stay
 * empty here.
 */
fun browseCategoryEntries(key: String): List<BrowseEntry> = when (key) {
    "genres" -> BROWSE_GENRES
    "keywords" -> emptyList() // runtime-resolved
    "services" -> BROWSE_PROVIDER_ENTRIES
    "studios" -> BROWSE_STUDIOS
    "decades" -> BROWSE_DECADES
    "collections" -> emptyList() // runtime-resolved
    else -> emptyList()
}

val BROWSE_CATEGORIES: List<BrowseCategory> = listOf(
    BrowseCategory("genres", "Genres", BROWSE_GENRES),
    BrowseCategory("keywords", "Keywords", emptyList()),
    BrowseCategory("services", "Services & Networks", BROWSE_PROVIDER_ENTRIES),
    BrowseCategory("studios", "Studios", BROWSE_STUDIOS),
    BrowseCategory("decades", "Decades", BROWSE_DECADES),
    BrowseCategory("collections", "Collections", emptyList())
)
