package com.kennyb1201.kbstream.ui.search

/**
 * Curated entries for the Search screen's browse browser (the browsable
 * replacement for the empty "no results" whitespace). Sidebar categories
 * open a submenu; an entry opens an existing app screen:
 *
 *  - Genres / Keywords      -> Screen.Tag (genre / keyword discover)
 *  - Services & Networks    -> Screen.Studio (service entries carry a
 *                             watch-provider id, so their page runs MOVIES
 *                             + SERIES provider rails; a plain network
 *                             entry runs its series rails, plus MOVIES
 *                             rails when it also carries the brand's
 *                             company id — TMDB's only route to a
 *                             network's movie slate)
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
 *
 * [originalsCompanyId] means two different things by entry kind: for a
 * service it adds a company-driven ORIGINALS rail (movies + TV); for a plain
 * NETWORK it is what lets the page show movies at all, since network
 * discover is TV-only.
 */
data class BrowseEntry(
    val id: Int,
    val name: String,
    val providerId: Int? = null,
    val networkOrCompanyId: Int? = null,
    val networkIsCompany: Boolean = false,
    /** Extra originals rail: the brand's production company (movies+TV). */
    val originalsCompanyId: Int? = null
)

/**
 * A streaming service with both a watch-provider id (everything "On Now"
 * on the service, in US watch region — used by the Recent / Popular /
 * Most Voted rails) and its network/company id (what it produced — the
 * "Originals" rail). Null network/company id means the service has no
 * meaningful originals catalog (Tubi, Pluto, ...), so it only offers the
 * provider rails.
 *
 * [originalsCompanyId] optionally adds a SECOND originals rail driven by
 * the brand's production company: company discover includes MOVIES too
 * (network discover is TV-only), and it catches content the provider
 * rails lost — titles that left the service, plus co-productions TMDB
 * tags with the company but not the network. Null when the brand has no
 * meaningful company page in TMDB (Hulu, Peacock, MUBI, ...).
 */
data class BrowseService(
    val name: String,
    val providerId: Int?,
    val networkOrCompanyId: Int?,
    val networkIsCompany: Boolean,
    val originalsCompanyId: Int? = null
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
    "olympics",
    // Fourth wave (2026-09) — all-ages/family keywords, every name
    // verified to resolve EXACTLY via /search/keyword with real discover
    // depth ("soccer" does not resolve; "toys" has only 9 movies — use
    // "toy"). Doubles as the source pool for the kids list below.
    "fairy",
    "unicorn",
    "pony",
    "puppy",
    "kitten",
    "zoo",
    "toy",
    "birthday",
    "baking",
    "vacation",
    "summer vacation",
    "baseball",
    "basketball",
    "swimming",
    "treasure hunt",
    "magic",
    "santa claus",
    "puppet",
    "bicycle",
    "sleepover"
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
    BrowseEntry(43, "National Geographic", originalsCompanyId = 7521),
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
    BrowseEntry(1267, "Freeform", originalsCompanyId = 127128),
    // Second wave — every id verified live via /network/{id} (name match):
    // premium cable, basic cable, and classic broadcast networks. HBO (49)
    // is served by the HBO Max service page above — the provider rails
    // carry every HBO series plus the movie library, so a second
    // series-only chip would just duplicate it.
    BrowseEntry(30, "USA Network", originalsCompanyId = 16642),
    BrowseEntry(74, "Bravo"),
    BrowseEntry(88, "FX"),
    BrowseEntry(1035, "FXX"),
    BrowseEntry(41, "TNT"),
    BrowseEntry(68, "TBS"),
    BrowseEntry(77, "Syfy"),
    BrowseEntry(129, "A&E"),
    BrowseEntry(34, "Lifetime", originalsCompanyId = 3431),
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
    // 2026-09: networks that also get MOVIES rails. TMDB has no
    // movies-by-network discover, so a network's movie slate is reachable
    // only through the brand's own production company; [originalsCompanyId]
    // carries that company id and the network page runs MOVIES · RECENT /
    // POPULAR / TOP RATED through it. Each id was verified live (exact name
    // match on /search/company + a non-empty movie discover slate), and the
    // networks NOT listed here have no such company page in TMDB — TLC,
    // HGTV, Food Network, Discovery, A&E, E!, BET, AMC, FX, MTV, Bravo,
    // Starz, TNT/TBS (those two names belong to Turkish and Japanese
    // broadcasters in TMDB's company space), so they stay series-only
    // rather than pulling in another country's catalog.
    BrowseEntry(65, "History", originalsCompanyId = 3507),
    BrowseEntry(244, "Investigation Discovery", originalsCompanyId = 73761),
    BrowseEntry(84, "TLC"),
    BrowseEntry(210, "HGTV"), // Discovery+ service page covers the brand; HGTV stays its own chip (its own page, series rails)
    BrowseEntry(91, "Animal Planet", originalsCompanyId = 110102),
    BrowseEntry(226, "Science Channel", originalsCompanyId = 8400),
    BrowseEntry(54, "Disney Channel", originalsCompanyId = 240533),
    BrowseEntry(44, "Disney XD"),
    BrowseEntry(281, "Disney Junior"),
    BrowseEntry(827, "OWN"),
    BrowseEntry(132, "Oxygen"),
    BrowseEntry(448, "WE tv"),
    BrowseEntry(63, "Game Show Network"),
    BrowseEntry(384, "Hallmark Channel", originalsCompanyId = 53015),
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
    BrowseEntry(5459, "Boomerang"),
    // Fourth wave (2026-09) — ids resolved via flagship-show probes
    // (/search/tv -> /tv/{id}.networks; TMDB has NO network search endpoint,
    // so /search/network 404s), then confirmed with a RECENT discover
    // replica. Each parenthesised date is that probe's newest series, so
    // archive-only pages (Logo, AXS TV, CMT, TV Land) are deliberate
    // archive pages rather than a broken list:
    //   Travel Channel 209 (2023), Nat Geo Wild 1043 (2026), Smithsonian
    //   Channel 658 (2020), VICE TV 3706 (2024), CNN 59 (2025), HLN 181
    //   (2020), IFC 124 (2023), SundanceTV 270 (2024), VH1 158 (2022),
    //   CMT 85 (2019), TV Land 397 (2017), Logo 62 (2016), AXS TV 206
    //   (2013), REELZ 367 (2022), The Weather Channel 306, Bloomberg
    //   Television 666, C-SPAN 322, NBA TV 301, NHL Network 907, FS1 2317.
    // Skipped because TMDB has no network page at all: American Heroes
    // Channel, Discovery Life, Fox Business, NewsNation, NFL / MLB / Golf /
    // Tennis / Big Ten / SEC Network, UniMás, Galavisión, Estrella TV,
    // Ovation, MeTV, Comet.
    BrowseEntry(209, "Travel Channel"),
    BrowseEntry(1043, "Nat Geo Wild"),
    BrowseEntry(658, "Smithsonian Channel"),
    BrowseEntry(3706, "VICE TV"),
    BrowseEntry(59, "CNN"),
    BrowseEntry(181, "HLN"),
    BrowseEntry(124, "IFC"),
    BrowseEntry(270, "SundanceTV"),
    BrowseEntry(158, "VH1"),
    BrowseEntry(85, "CMT"),
    BrowseEntry(397, "TV Land"),
    BrowseEntry(62, "Logo"),
    BrowseEntry(206, "AXS TV"),
    BrowseEntry(367, "REELZ"),
    BrowseEntry(306, "The Weather Channel"),
    BrowseEntry(666, "Bloomberg Television"),
    BrowseEntry(322, "C-SPAN"),
    BrowseEntry(301, "NBA TV"),
    BrowseEntry(907, "NHL Network"),
    BrowseEntry(2317, "FS1")
)

// ---------------------------------------------------------------------------
// ── Presentation ordering: household names first, rest alphabetical ─────
// The curated lists above are grouped by theme/wave history; the picker
// chips read better with the brands people actually open at the top.
// Purely presentational: ids, ids-as-typed, and data are untouched.

/** Household-name streaming services, in curated popularity order. */
internal val POPULAR_SERVICES_ORDER = listOf(
    "Netflix", "Prime Video", "Disney+", "Apple TV", "HBO Max", "Hulu",
    "Paramount+", "Peacock", "ESPN+", "Tubi", "Pluto TV", "Crunchyroll",
    "TCM", "Fox One", "HiDive"
)

/** Broadcast majors + the biggest cable brands, curated order. */
internal val POPULAR_NETWORKS_ORDER = listOf(
    "ABC", "NBC", "CBS", "FOX", "MTV", "Comedy Central", "Nickelodeon",
    "Cartoon Network", "Adult Swim", "USA Network", "FX", "TNT", "TBS",
    "CNN", "Travel Channel", "IFC", "SundanceTV"
)

/** The traditional majors + top franchise houses, curated order. */
private val POPULAR_STUDIOS_ORDER = listOf(
    "Warner Bros. Pictures", "Walt Disney Pictures", "Universal Pictures",
    "Paramount Pictures", "Columbia Pictures", "20th Century Fox",
    "Metro-Goldwyn-Mayer", "Marvel Studios", "Lucasfilm", "Pixar",
    "DreamWorks Animation", "Illumination", "Studio Ghibli",
    "A24", "NEON", "StudioCanal", "Pathé", "Gaumont", "Lionsgate"
)

/** Kids-first animation/TV brands, curated order. */
internal val POPULAR_KIDS_STUDIOS_ORDER = listOf(
    "Walt Disney Pictures", "Pixar", "Walt Disney Animation Studios",
    "Illumination", "DreamWorks Animation", "Studio Ghibli",
    "Nickelodeon Movies", "Cartoon Network Studios", "The Pokémon Company",
    "Lucasfilm"
)

/**
 * Ranks [items] for the picker UI: entries whose [name] appears in
 * [popular] first (in that list's order), everything else alphabetically.
 * Names not in the popular list fall through to the alphabetical tail.
 */
internal fun <T> popularFirst(
    items: List<T>,
    name: (T) -> String,
    popular: List<String>
): List<T> {
    val rank = popular.withIndex().associate { (i, n) -> n to i }
    return items.sortedWith(
        compareBy(
            { rank[name(it).trim()] ?: Int.MAX_VALUE },
            { name(it).trim().lowercase() }
        )
    )
}

// Studios — verified against themoviedb.org/company/{id}.
// ---------------------------------------------------------------------------

val BROWSE_STUDIOS: List<BrowseEntry> = popularFirst(
    listOf(
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
    BrowseEntry(147786, "Dimension Films"),
    // Fourth wave (2026-09) — indies, foreign labels, genre houses, and the
    // animation shops the kids list leans on. Every id picked from
    // /search/company by NAME MATCH + a non-empty movie discover slate;
    // the famous names whose first hit was an empty duplicate were resolved
    // to the canonical page instead (NEON 90733 over the 0-movie 307597,
    // StudioCanal 694 over 218178, Pathé 7981 over 209044, Gaumont 9 over
    // 276068, New Regency Pictures 10104, Zentropa Entertainments 76,
    // Shochiku 192, Nikkatsu Corporation 955, Constantin Film 47,
    // Nordisk Film 143164, Lotte Entertainment 7819, Huayi Brothers
    // Pictures 3393, The Asylum 1311, WildBrain Studios 148496).
    // Skipped: Cannon Films / Sony Pictures Imageworks / Netflix Animation
    // / RLJE Films / Bleecker Street — no company page with a real slate.
    BrowseEntry(47729, "STXfilms"),
    BrowseEntry(90733, "NEON"),
    BrowseEntry(694, "StudioCanal"),
    BrowseEntry(7981, "Pathé"),
    BrowseEntry(9, "Gaumont"),
    BrowseEntry(10104, "New Regency Pictures"),
    BrowseEntry(911, "Roadside Attractions"),
    BrowseEntry(307, "IFC Films"),
    BrowseEntry(1030, "Magnolia Pictures"),
    BrowseEntry(12852, "Shout! Factory"),
    BrowseEntry(88606, "Vertical"),
    BrowseEntry(76992, "Saban Films"),
    BrowseEntry(6626, "Voltage Pictures"),
    BrowseEntry(6896, "EuropaCorp"),
    BrowseEntry(47, "Constantin Film"),
    BrowseEntry(143164, "Nordisk Film"),
    BrowseEntry(76, "Zentropa Entertainments"),
    BrowseEntry(192, "Shochiku"),
    BrowseEntry(955, "Nikkatsu Corporation"),
    BrowseEntry(1311, "The Asylum"),
    BrowseEntry(9118, "Samuel Goldwyn Films"),
    BrowseEntry(10210, "Morgan Creek Entertainment"),
    BrowseEntry(130, "Jerry Bruckheimer Films"),
    BrowseEntry(1885, "Silver Pictures"),
    BrowseEntry(437, "Heyday Films"),
    BrowseEntry(7576, "EON Productions"),
    BrowseEntry(76907, "Atomic Monster"),
    BrowseEntry(2481, "Platinum Dunes"),
    BrowseEntry(2061, "Twisted Pictures"),
    BrowseEntry(829, "Vertigo Entertainment"),
    BrowseEntry(7164, "TMS Entertainment"),
    BrowseEntry(5372, "OLM"),
    BrowseEntry(1023, "Nelvana"),
    BrowseEntry(148496, "WildBrain Studios"),
    BrowseEntry(4152, "Titmouse"),
    BrowseEntry(23948, "Cartoon Saloon"),
    BrowseEntry(2787, "Reel FX Creative Studios"),
    BrowseEntry(8089, "Animal Logic"),
    BrowseEntry(179999, "Skydance Animation"),
    BrowseEntry(1569, "Yash Raj Films"),
    BrowseEntry(19146, "Dharma Productions"),
    BrowseEntry(3522, "T-Series"),
    BrowseEntry(7819, "Lotte Entertainment"),
    BrowseEntry(3491, "Showbox"),
    BrowseEntry(3393, "Huayi Brothers Pictures")
    ),
    name = { it.name },
    popular = POPULAR_STUDIOS_ORDER
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
//    Crunchyroll=283, Angel Studios=1956. Showtime has NO provider id any
//    more (folded into Paramount Plus Premium), so it runs as a plain
//    network page.
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
        networkIsCompany = false,
        originalsCompanyId = 178464
    ),
    BrowseService(
        "Prime Video",
        providerId = 9,
        networkOrCompanyId = 1024,
        networkIsCompany = false,
        originalsCompanyId = 210099 // Amazon MGM Studios
    ),
    BrowseService(
        "Disney+",
        providerId = 337,
        networkOrCompanyId = 2739,
        networkIsCompany = false,
        originalsCompanyId = 2 // Walt Disney Pictures — the Disney+ film originals
        // (Noelle, Togo, Chip 'n Dale… all tagged company 2; the Disney+
        // series originals ride the network 2739 rail).
    ),
    BrowseService(
        "Apple TV",
        providerId = 350,
        networkOrCompanyId = 2552,
        networkIsCompany = false,
        originalsCompanyId = 194232 // Apple Studios
    ),
    BrowseService(
        "HBO Max",
        providerId = 1899,
        networkOrCompanyId = 3186,
        networkIsCompany = false,
        originalsCompanyId = 3268 // HBO — the whole HBO film/TV slate
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
        networkIsCompany = false,
        originalsCompanyId = 32491 // Peacock Films — their film co-productions
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
        networkIsCompany = false,
        originalsCompanyId = 4343 // Showtime Networks — adds the movie slate
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
        networkIsCompany = false,
        originalsCompanyId = 23242 // AMC Studios
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
        networkIsCompany = false,
        originalsCompanyId = 17037 // ESPN — 30-for-30 film library etc.
    ),
    BrowseService(
        "The Roku Channel",
        providerId = 207,
        networkOrCompanyId = 290879,
        networkIsCompany = true,
        originalsCompanyId = 279515 // Roku Media — originals film slate
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
        networkIsCompany = true,
        originalsCompanyId = 142877
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
        networkOrCompanyId = 204957,
        networkIsCompany = true,
        originalsCompanyId = 204957 // MUBI's releases (288516 is empty)
    ),
    BrowseService(
        "Criterion Channel",
        providerId = 258,
        networkOrCompanyId = 10932,
        networkIsCompany = true,
        originalsCompanyId = 204170 // The Criterion Collection film library
    ),
    BrowseService(
        "fuboTV",
        providerId = 257,
        networkOrCompanyId = 238158, // Fubo Studios (57501 is 'Fun TV' — dead end)
        networkIsCompany = true
    ),
    // Faith-and-family studio: watch provider 1956 is their streaming
    // catalog (Hacksaw Ridge, Midway, David…), network 3081 "Angel" carries
    // the series originals (The Chosen, Homestead, Vindication), and company
    // 165435 is the real Angel Studios film library (48 films; the other
    // company-id hits were empty or unrelated).
    BrowseService(
        "Angel Studios",
        providerId = 1956,
        networkOrCompanyId = 3081,
        networkIsCompany = false,
        originalsCompanyId = 165435
    ),
    BrowseService(
        "Xumo Play",
        providerId = 1963,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    //
    // Fourth wave (2026-09) — genre, niche, and international streamers.
    // Every providerId re-verified live against the CURRENT US registry
    // (/watch/providers/movie + /tv). None of these has a verified
    // network/company page to carry the header logo, so they open on the
    // watch-provider rails alone and fall back to the text header exactly
    // like Xumo Play above (provider rails still cover movies AND series).
    BrowseService("TCM", providerId = 361, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Fox One", providerId = 2545, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("HiDive", providerId = 430, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("RetroCrush", providerId = 446, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Kocowa", providerId = 464, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Rakuten Viki", providerId = 344, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("iQIYI", providerId = 581, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("AsianCrush", providerId = 514, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("MHz Choice", providerId = 427, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("MagellanTV", providerId = 551, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("OVID", providerId = 433, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Kino Film Collection", providerId = 2135, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Metrograph", providerId = 585, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("ARROW", providerId = 529, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Shout! Factory TV", providerId = 439, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Midnight Pulp", providerId = 1960, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Troma NOW", providerId = 2078, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Hi-YAH!", providerId = 503, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Pure Flix", providerId = 278, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("BYUtv", providerId = 2129, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Fawesome", providerId = 2409, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Revry", providerId = 473, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Dekkoo", providerId = 444, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Here TV", providerId = 417, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("WOW Presents Plus", providerId = 546, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Chai Flicks", providerId = 438, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("BroadwayHD", providerId = 554, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Lifetime Movie Club", providerId = 284, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Ovation TV", providerId = 1953, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Vice TV", providerId = 458, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("VIX", providerId = 457, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Cineverse", providerId = 1957, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("DistroTV", providerId = 1971, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Fandor", providerId = 25, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("Filmzie", providerId = 559, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("DisneyNOW", providerId = 508, networkOrCompanyId = null, networkIsCompany = false),
    BrowseService("FXNow", providerId = 123, networkOrCompanyId = null, networkIsCompany = false)
)

