package com.kennyb1201.kbstream.ui.search

/**
 * Curated entries for the Search screen's browse browser (the browsable
 * replacement for the empty "no results" whitespace). Sidebar categories
 * open a submenu; an entry loads discover rails in the browser itself or
 * opens an existing app screen:
 *
 *  - Genres / Keywords      -> Screen.Tag (genre / keyword discover)
 *  - Networks / Studios     -> Screen.Studio (network / company discover)
 *  - Collections            -> Screen.Collection
 *  - Services               -> watch-provider discover (Recent / Popular /
 *                             Most Voted) plus network/company discover
 *                             ("Originals") — order: Originals first
 *  - Decades                -> in-browser discover rails (Popular / Most
 *                             Voted), movies+series merged
 *
 * Network ids below were verified against themoviedb.org/network/{id}
 * pages; company ids against themoviedb.org/company/{id}; watch-provider
 * ids against TMDB's /watch/providers registry. Keyword and collection ids
 * are NOT hand-maintained: they resolve at runtime from /search/keyword and
 * /search/collection so they can never rot.
 */

/** One selectable entry in a browse submenu. */
data class BrowseEntry(
    val id: Int,
    val name: String
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
    "high school"
)

// ---------------------------------------------------------------------------
// Networks — verified against themoviedb.org/network/{id}.
// ---------------------------------------------------------------------------

val BROWSE_NETWORKS = listOf(
    BrowseEntry(213, "Netflix"),
    BrowseEntry(132, "Prime Video"),
    BrowseEntry(2739, "Disney+"),
    BrowseEntry(2552, "Apple TV+"),
    BrowseEntry(49, "HBO"),
    BrowseEntry(453, "Hulu"),
    BrowseEntry(4330, "Paramount+"),
    BrowseEntry(3186, "Peacock"),
    BrowseEntry(2, "ABC"),
    BrowseEntry(6, "NBC"),
    BrowseEntry(16, "CBS"),
    BrowseEntry(19, "FOX"),
    BrowseEntry(4, "BBC One"),
    BrowseEntry(33, "BBC Two"),
    BrowseEntry(47, "Comedy Central"),
    BrowseEntry(13, "Nickelodeon"),
    BrowseEntry(56, "Cartoon Network"),
    BrowseEntry(71, "The CW"),
    BrowseEntry(174, "AMC"),
    BrowseEntry(43, "Starz"),
    BrowseEntry(80, "Adult Swim"),
    BrowseEntry(75, "ABC Family")
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
    BrowseEntry(882, "Legendary Pictures"),
    BrowseEntry(923, "Focus Features"),
    BrowseEntry(7295, "Regency Enterprises"),
    BrowseEntry(10146, "Sony Pictures Animation"),
    BrowseEntry(10292, "Working Title Films")
)

// ---------------------------------------------------------------------------
// Streaming services. providerId drives the Recent / Popular / Most Voted
// rails (watch-provider discover with watch_region "US");
// networkOrCompanyId drives the "Originals" rail (network discover for
// TV-first services, company discover for movie studios). Provider ids from
// TMDB's watch-provider registry (Netflix=8, Prime=9, Disney+=337,
// Apple TV+=350, Max=384/1899, Hulu=15, Paramount+=531, Peacock=386, ...).
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
        networkOrCompanyId = 132,
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
        providerId = 384,
        networkOrCompanyId = 49,
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
        providerId = 531,
        networkOrCompanyId = 4330,
        networkIsCompany = false
    ),
    BrowseService(
        "Peacock",
        providerId = 386,
        networkOrCompanyId = 3186,
        networkIsCompany = false
    ),
    BrowseService(
        "Starz",
        providerId = 43,
        networkOrCompanyId = 43,
        networkIsCompany = false
    ),
    BrowseService(
        "Showtime",
        providerId = 37,
        networkOrCompanyId = 88,
        networkIsCompany = false
    ),
    // Free/fast services: no curated originals id — provider rails only.
    BrowseService(
        "Tubi",
        providerId = 73,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    BrowseService(
        "Pluto TV",
        providerId = 300,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    BrowseService(
        "Crunchyroll",
        providerId = 283,
        networkOrCompanyId = null,
        networkIsCompany = false
    )
)

/** Services submenu entries; [BrowseEntry.id] indexes BROWSE_SERVICES. */
val BROWSE_SERVICE_ENTRIES: List<BrowseEntry> = BROWSE_SERVICES
    .mapIndexed { index, service -> BrowseEntry(index, service.name) }

// ---------------------------------------------------------------------------
// Decades 2020s -> 1950s. Popular + Most Voted rails only (per design);
// the rail loader merges movies and series into each rail.
// ---------------------------------------------------------------------------

val BROWSE_DECADES: List<BrowseEntry> = (2020 downTo 1950 step 10)
    .map { decade -> BrowseEntry(decade, "${decade}s") }

// ---------------------------------------------------------------------------
// Collections: famous franchises. ids resolved at RUNTIME from
// /search/collection (TMDB collection ids are easy to misremember; the
// search endpoint returns the canonical id for each name).
// ---------------------------------------------------------------------------

val BROWSE_COLLECTION_NAMES = listOf(
    "Star Wars",
    "The Lord of the Rings Collection",
    "Harry Potter Collection",
    "Marvel Cinematic Universe",
    "Spider-Man Collection",
    "Toy Story Collection",
    "The Dark Knight Collection",
    "Jurassic Park Collection",
    "Terminator Collection",
    "Mad Max Collection",
    "Pirates of the Caribbean Collection",
    "John Wick Collection",
    "The Conjuring Universe",
    "Mission: Impossible Collection",
    "Fast & Furious Collection",
    "Indiana Jones Collection",
    "X-Men Collection",
    "Transformers Collection",
    "Back to the Future Collection",
    "Despicable Me Collection",
    "How to Train Your Dragon Collection",
    "Saw Collection",
    "DC Universe Animated Original Movies",
    "Alien Collection",
    "Predator Collection"
)

/**
 * Sidebar categories. Keywords and Collections ship name lists resolved at
 * runtime (see SearchViewModel), so their [BrowseCategory.entries] stay
 * empty here.
 */
fun browseCategoryEntries(key: String): List<BrowseEntry> = when (key) {
    "genres" -> BROWSE_GENRES
    "keywords" -> emptyList() // runtime-resolved
    "networks" -> BROWSE_NETWORKS
    "studios" -> BROWSE_STUDIOS
    "services" -> BROWSE_SERVICE_ENTRIES
    "decades" -> BROWSE_DECADES
    "collections" -> emptyList() // runtime-resolved
    else -> emptyList()
}

val BROWSE_CATEGORIES: List<BrowseCategory> = listOf(
    BrowseCategory("genres", "Genres", BROWSE_GENRES),
    BrowseCategory("keywords", "Keywords", emptyList()),
    BrowseCategory("networks", "Networks", BROWSE_NETWORKS),
    BrowseCategory("studios", "Studios", BROWSE_STUDIOS),
    BrowseCategory("services", "Services", BROWSE_SERVICE_ENTRIES),
    BrowseCategory("decades", "Decades", BROWSE_DECADES),
    BrowseCategory("collections", "Collections", emptyList())
)
