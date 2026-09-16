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
 *
 * 2026-09 third wave: 16 more streaming services (AMC+, Discovery+, MGM+,
 * ESPN+, Roku Channel, Plex, BritBox, Acorn, Shudder, ALLBLK, Sundance
 * Now, CuriosityStream, MUBI, Criterion Channel, fuboTV, Xumo Play),
 * 35 more US networks (Discovery/HGTV/TLC family, Disney channels,
 * Hallmark, PBS, Telemundo/Univision, news, UK), 43 more studios
 * (Amazon MGM, Lucasfilm, anime studios, etc.) and ~150 more keywords.
 * Every id was live-verified against the current TMDB API (name match +
 * discover-depth check); dead/duplicate TMDB pages were deliberately
 * skipped — see the per-section comments for what and why.
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
    "civil war",
    // Third wave — every name verified to resolve EXACTLY via
    // /search/keyword (case-insensitive) with a movie count check
    // (2026-09). Misses were replaced with their resolvable variants:
    // "formula one (f1)" resolves, "formula 1" does not; "forensic"
    // resolves, "forensics" does not.
    "mixed martial arts (mma)",
    "wrestling",
    "nascar",
    "car racing",
    "formula one (f1)",
    "bank heist",
    "hacking",
    "con artist",
    "undercover",
    "small town",
    "big city",
    "amusement park",
    "circus",
    "summer camp",
    "midlife crisis",
    "toxic relationship",
    "obsessive love",
    "stalker",
    "conspiracy",
    "cover-up",
    "whistleblower",
    "journalist",
    "lawyer",
    "judge",
    "jury",
    "forensic",
    "hospital",
    "doctor",
    "nurse",
    "surgeon",
    "paramedic",
    "firefighter",
    "police",
    "police officer",
    "bounty hunter",
    "gunslinger",
    "saloon",
    "cowboy",
    "cowgirl",
    "outlaw",
    "gold rush",
    "prohibition",
    "stockbroker",
    "banker",
    "hedge fund",
    "wall street",
    "election",
    "chess",
    "poker",
    "esports",
    "gamer",
    "influencer",
    "startup",
    "crypto",
    "artificial intelligence",
    "virtual reality",
    "social media",
    "paparazzi",
    "fashion",
    "model",
    "musician",
    "band",
    "rapper",
    "dj",
    "stand-up comedian",
    "broadway",
    "theatre",
    "opera",
    "ballet",
    "dancer",
    "choir",
    "military",
    "veteran",
    "homelessness",
    "poverty",
    "immigrant",
    "american dream",
    "racism",
    "civil rights",
    "royal family",
    "monarchy",
    "billionaire",
    "cult leader",
    "doomsday",
    "nuclear war",
    "pandemic",
    "outbreak",
    "art theft",
    "bank robbery",
    "prison escape",
    "wrongful conviction",
    "death row",
    "sniper",
    "double agent",
    "air force",
    "navy",
    "astronaut",
    "space race",
    "moon landing",
    "deep space",
    "alien contact",
    "parallel universe",
    "multiverse",
    "time machine",
    "body swap",
    "shapeshifting",
    "immortality",
    "genie",
    "mermaid",
    "doppelganger",
    "cryogenics",
    "cloning",
    "genetic engineering",
    "mutation",
    "superhero team",
    "vigilante",
    "sword and sorcery",
    "magical object",
    "cursed object",
    "haunted doll",
    "ouija",
    "seance",
    "medium",
    "exorcist",
    "demon",
    "apocalypse",
    "flood",
    "volcano",
    "earthquake",
    "tornado",
    "hurricane",
    "shark",
    "snake",
    "spider",
    "crocodile",
    "bear",
    "wolf pack",
    "surfing",
    "skateboarding",
    "gymnastics",
    "olympics"
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
    //  174=AMC, 80=Adult Swim.
    //
    // Every id below re-verified LIVE (network/{id} name match + a RECENT
    // discover replica: newest first_air_date with vote_count.gte=5):
    //  - Discovery: TMDB 106 is Discovery (CANADA) — its newest series was
    //    2017, which is why the Discovery page's Recent rail topped out at
    //    Heavy Rescue: 401. The US Discovery Channel is 64 (verified via
    //    the network ids Gold Rush / Deadliest Catch / MythBusters carry).
    //  - ABC Family: the brand became Freeform in 2016; TMDB 75 has no new
    //    series since 2015. 1267 (Freeform) carries the current slate.
    //  - The WB: defunct since 2006 — kept as an archive page on purpose;
    //    its Recent rail is historical by definition.
    //  - BET: 24's newest is 2022, but current BET shows still list 24, so
    //    it stays; BET+ (3343) would show the fresher streamer slate.
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
    // AMC (174) is served by the AMC+ service page above — one chip per
    // brand (its series slate IS the AMC+ originals, The Vampire Lestat,
    // Talamasca, etc.).
    BrowseEntry(80, "Adult Swim"),
    // Rebrand fix: ABC Family (75) has been Freeform since 2016; the old id
    // showed nothing newer than 2015 in Recent.
    BrowseEntry(1267, "Freeform"),
    // Second wave — every id verified live via /network/{id} (name match):
    // premium cable, basic cable, and classic broadcast networks. HBO (49)
    // is served by the HBO Max service page above — the provider rails
    // carry every HBO series plus the movie library, so a second
    // series-only chip would just duplicate it.
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
    // Discovery (64) is served by the Discovery+ service page above — one
    // chip per brand.
    BrowseEntry(143, "Food Network"),
    BrowseEntry(2076, "Paramount Network"),
    BrowseEntry(21, "The WB"),
    BrowseEntry(26, "Channel 4"),
    // Third wave — every id resolved via flagship-show probes against
    // /tv/{id}.networks and /network/{id} name matches (2026-09), with a
    // RECENT discover replica confirming live slates. Skipped: UP TV (1180,
    // newest is 2015), Hallmark Movies & Mysteries (dead/rebranded pages),
    // NFL/MLB Network (no TMDB network pages).
    BrowseEntry(65, "History"),
    BrowseEntry(244, "Investigation Discovery"),
    BrowseEntry(84, "TLC"),
    BrowseEntry(210, "HGTV"), // Discovery+ service page covers the brand; HGTV stays its own chip (its own page, series rails)
    BrowseEntry(91, "Animal Planet"),
    BrowseEntry(226, "Science Channel"),
    BrowseEntry(54, "Disney Channel"),
    BrowseEntry(44, "Disney XD"),
    BrowseEntry(281, "Disney Junior"),
    BrowseEntry(827, "OWN"),
    BrowseEntry(132, "Oxygen"),
    BrowseEntry(448, "WE tv"),
    BrowseEntry(63, "Game Show Network"),
    BrowseEntry(384, "Hallmark Channel"),
    BrowseEntry(747, "INSP"),
    BrowseEntry(516, "Pop TV"),
    BrowseEntry(1351, "Bounce TV"),
    BrowseEntry(14, "PBS"),
    BrowseEntry(193, "Telemundo"),
    BrowseEntry(28, "Univision"),
    BrowseEntry(364, "truTV"),
    BrowseEntry(359, "Cinemax"),
    // ESPN (29) is served by the ESPN+ service page above — one chip per
    // brand.
    BrowseEntry(175, "CNBC"),
    BrowseEntry(37, "MSNBC"),
    BrowseEntry(45, "Fox News"),
    BrowseEntry(493, "BBC America"),
    BrowseEntry(332, "BBC Two"),
    BrowseEntry(9, "ITV1"),
    BrowseEntry(99, "Channel 5"),
    BrowseEntry(1287, "Discovery Family"),
    BrowseEntry(2444, "MotorTrend"),
    BrowseEntry(802, "Cooking Channel"),
    BrowseEntry(530, "Destination America"),
    BrowseEntry(5459, "Boomerang")
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
    BrowseEntry(9993, "DC"), // TMDB canonical name: DC Entertainment
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
    //  2251=Sony Pictures Animation, 10163=Working Title (TMDB's canonical
    //  name; "Working Title Films" is the same company 10163).
    BrowseEntry(882, "TOHO"),
    BrowseEntry(923, "Legendary Pictures"),
    BrowseEntry(508, "Regency Enterprises"),
    BrowseEntry(10146, "Focus Features"),
    BrowseEntry(2251, "Sony Pictures Animation"),
    BrowseEntry(10163, "Working Title"), // id verified; TMDB name is just 'Working Title'
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
    BrowseEntry(7295, "Relativity Media"),
    // Third wave — every id confirmed via /company/{id} name match PLUS a
    // /discover/movie depth check (companies with 0-movie catalogs, e.g.
    // TV-only shingles like 20th Television / ABC Signature / CBS TV
    // studios, are skipped because the Studio screen runs movie rails).
    //  - Lucasfilm is company 1 (TMDB canonical: Lucasfilm Ltd.); the
    //    /search/company first-hit for "Lucasfilm" (11928) is Luna-Film.
    //  - Annapurna is 13184 (38 movies); 117057 is a newer, empty duplicate.
    //  - Walt Disney Animation Studios: 6125 (53 movies) is the live one;
    //    158526 is a newer duplicate.
    BrowseEntry(210099, "Amazon MGM Studios"),
    BrowseEntry(1, "Lucasfilm"), // TMDB canonical: Lucasfilm Ltd.
    BrowseEntry(6125, "Walt Disney Animation Studios"),
    BrowseEntry(9383, "Blue Sky Studios"),
    BrowseEntry(58, "Sony Pictures Classics"),
    BrowseEntry(25120, "Warner Animation Group"),
    BrowseEntry(7899, "Cartoon Network Studios"),
    BrowseEntry(2348, "Nickelodeon Movies"),
    BrowseEntry(746, "MTV Films"),
    BrowseEntry(96540, "Paramount Players"),
    BrowseEntry(24955, "Paramount Animation"),
    BrowseEntry(23, "Imagine Entertainment"),
    BrowseEntry(1645, "Scott Free Productions"),
    BrowseEntry(18, "Gracie Films"),
    BrowseEntry(7036, "CJ Entertainment"),
    BrowseEntry(2073, "KADOKAWA"),
    BrowseEntry(528, "Bandai Visual"),
    BrowseEntry(3153, "Sunrise"),
    BrowseEntry(5438, "Kyoto Animation"),
    BrowseEntry(3464, "Madhouse"),
    BrowseEntry(5542, "Toei Animation"),
    BrowseEntry(31058, "WIT STUDIO"),
    BrowseEntry(21444, "MAPPA"),
    BrowseEntry(5887, "ufotable"),
    BrowseEntry(13113, "A-1 Pictures"),
    BrowseEntry(2849, "Bones"), // TMDB canonical: BONES
    BrowseEntry(529, "Production I.G"),
    BrowseEntry(6689, "SHAFT"),
    BrowseEntry(50908, "Trigger"), // TMDB canonical: TRIGGER
    BrowseEntry(3756, "CoMix Wave Films"),
    BrowseEntry(12292, "Temple Hill Entertainment"),
    BrowseEntry(5420, "Color Force"),
    BrowseEntry(6735, "Participant"),
    BrowseEntry(1020, "Millennium Media"),
    BrowseEntry(12654, "The Pokémon Company"),
    BrowseEntry(3052, "Troma Entertainment"),
    BrowseEntry(10427, "Open Road Films"),
    BrowseEntry(13184, "Annapurna Pictures"),
    BrowseEntry(81, "Plan B Entertainment"),
    BrowseEntry(147786, "Dimension Films")
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
    ),
    //
    // Third wave — every provider id re-checked against the CURRENT US
    // registry (/watch/providers/movie + tv, 2026-09) and every company id
    // confirmed via /company/{id} name match:
    //  - AMC+ provider 526 (80 is linear AMC; header uses network 174=AMC
    //    for the logo — the rails are all watch-provider based).
    //  - Discovery+ provider 520; header uses company 225743 ('discovery
    //    plus') so the branding is the streamer, not the linear channel.
    //  - MGM+ provider 34; network 6219=MGM+ carries its originals (Spider-Noir,
    //    The Westies, 2026 slates) — 922 is the legacy 'Epix' page that tops
    //    out at 2022, 8522 is a near-empty duplicate.
    //  - ESPN+ provider 1768 (1718 is linear ESPN); header on network 29.
    //  - Hallmark+ provider 290 is Amazon-channel-only; the network page
    //    (384) already shows the current slate, so only the linear network
    //    is added below. Hallmark Movies & Mysteries' TMDB pages (385,
    //    2853) are dead/rebranded, so it is skipped entirely.
    //  - Premium niche streamers verified: Roku Channel 207, Plex 538,
    //    BritBox 151, Acorn TV 87, Shudder 99, ALLBLK 251, Sundance Now
    //    143, CuriosityStream 190, MUBI 11, Criterion Channel 258,
    //    fuboTV 257, Xumo Play 1963. DAZN and ESPN linear have no stable
    //    US registry entries worth a service page.
    //  - Header ids for services WITHOUT a meaningful originals rail still
    //    point at their verified company ids (networkIsCompany=true) so
    //    the screen header gets a real clear-logo instead of text:
    //    BritBox 159102, Acorn TV 212651, ALLBLK 172801, Sundance Now
    //    181515, fuboTV 57501. MUBI / Criterion Channel / Xumo Play have
    //    no confirmed company page, so they fall back to the text header.
    //  - ONE CHIP PER BRAND: where a brand already has a service page, its
    //    plain network chip was removed from BROWSE_NETWORKS below (AMC,
    //    Discovery, ESPN, HBO) — the service page is a superset (provider
    //    rails cover movies AND shows; a second series-only chip for the
    //    same brand just duplicated the page).
    BrowseService(
        "AMC+",
        providerId = 526,
        networkOrCompanyId = 174,
        networkIsCompany = false
    ),
    BrowseService(
        "Discovery+",
        providerId = 520,
        networkOrCompanyId = 225743,
        networkIsCompany = true
    ),
    BrowseService(
        "MGM+",
        providerId = 34,
        networkOrCompanyId = 6219,
        networkIsCompany = false
    ),
    BrowseService(
        "ESPN+",
        providerId = 1768,
        networkOrCompanyId = 29,
        networkIsCompany = false
    ),
    BrowseService(
        "The Roku Channel",
        providerId = 207,
        networkOrCompanyId = 290879,
        networkIsCompany = true
    ),
    BrowseService(
        "Plex",
        providerId = 538,
        networkOrCompanyId = 283072,
        networkIsCompany = true
    ),
    BrowseService(
        "BritBox",
        providerId = 151,
        networkOrCompanyId = 159102,
        networkIsCompany = true
    ),
    BrowseService(
        "Acorn TV",
        providerId = 87,
        networkOrCompanyId = 212651,
        networkIsCompany = true
    ),
    BrowseService(
        "Shudder",
        providerId = 99,
        networkOrCompanyId = 142877,
        networkIsCompany = true
    ),
    BrowseService(
        "ALLBLK",
        providerId = 251,
        networkOrCompanyId = 172801, // company id, header logo only
        networkIsCompany = true
    ),
    BrowseService(
        "Sundance Now",
        providerId = 143,
        networkOrCompanyId = 181515,
        networkIsCompany = true
    ),
    BrowseService(
        "CuriosityStream",
        providerId = 190,
        networkOrCompanyId = 96320,
        networkIsCompany = true
    ),
    BrowseService(
        "MUBI",
        providerId = 11,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    BrowseService(
        "Criterion Channel",
        providerId = 258,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    BrowseService(
        "fuboTV",
        providerId = 257,
        networkOrCompanyId = 57501,
        networkIsCompany = true
    ),
    BrowseService(
        "Xumo Play",
        providerId = 1963,
        networkOrCompanyId = null,
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
