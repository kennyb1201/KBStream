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

// Part 2 of the SearchBrowseCatalog.kt split. This file's
// declarations were moved here verbatim by scripts/split_kotlin.py,
// which cuts only at top-level boundaries -- the editor's file view
// stops at ~60 KB, so the original had an unreachable tail. Only the
// declarations another part calls were widened to `internal`.

// Merged "Services & Networks" submenu: every streaming service (with its
// watch-provider id, so its screen gets Recent / Popular / Most Voted rails
// alongside Originals) followed by the TV network list — one screen per
// brand instead of separate Services and Networks categories. Ordered
// popular-first, then alphabetical.
val BROWSE_PROVIDER_ENTRIES: List<BrowseEntry> = popularFirst(
    BROWSE_SERVICES
        .map { service ->
            BrowseEntry(
                service.networkOrCompanyId ?: -1,
                service.name,
                service.providerId,
                service.networkOrCompanyId,
                service.networkIsCompany,
                service.originalsCompanyId
            )
        } + BROWSE_NETWORKS,
    name = { it.name },
    popular = POPULAR_SERVICES_ORDER + POPULAR_NETWORKS_ORDER
)

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
    "AVP Collection",
    // Fifth wave (2026-09). Every name below was re-verified against
    // /search/collection and resolves EXACTLY (the resolver prefers an
    // exact-name hit, so an inexact name silently loses the chip). Names
    // that returned no exact match were left out entirely — The Texas
    // Chainsaw Massacre, Dollars Trilogy, Hulk, Ouija, RED, 300, Sleeping
    // Beauty, Snow White, Tarzan, Winnie the Pooh and Inside Out only exist
    // under unrelated same-ish titles (the sixth wave later found canonical
    // pages for Twilight and Blade; Night at the Museum lives in the kids
    // collections). Where TMDB's canonical page carries a slightly
    // different name, the canonical spelling is used:
    //   'Spider-Man: Spider-Verse', 'The Gremlins', 'The Jack Ryan',
    //   '28 Days/Weeks/Years Later', 'Has Fallen', 'The Amityville'.
    // Prestige / crime / drama
    "The Godfather Collection",
    "Dirty Harry Collection",
    "Crank Collection",
    "Death Wish Collection",
    "Friday Collection",
    // Sci-fi / adventure tentpoles
    "Dune Collection",
    "Blade Runner Collection",
    "Gladiator Collection",
    "Kill Bill Collection",
    "Tron Collection",
    "Independence Day Collection",
    "Pacific Rim Collection",
    "The Meg Collection",
    "RoboCop Collection",
    "Sin City Collection",
    "The Crow Collection",
    "Beetlejuice Collection",
    "The Gremlins Collection",
    "Planet of the Apes (Reboot) Collection",
    // Action / heist / comedy franchises
    "The Karate Kid Collection",
    "Beverly Hills Cop Collection",
    "Creed Collection",
    "The Equalizer Collection",
    "The Expendables Collection",
    "The Purge Collection",
    "A Quiet Place Collection",
    "The Strangers Collection",
    "Don't Breathe Collection",
    "Escape Room Collection",
    "Wrong Turn Collection",
    "Jeepers Creepers Collection",
    "Children of the Corn Collection",
    "V/H/S Collection",
    "Leprechaun Collection",
    "Critters Collection",
    "Tremors Collection",
    "Child's Play Collection",
    "Hellraiser Collection",
    "The Exorcist Collection",
    "The Ring Collection",
    "The Grudge Collection",
    "The Nun Collection",
    "The Amityville Collection",
    "Zombieland Collection",
    "Resident Evil Collection",
    "Underworld Collection",
    "Fear Street Collection",
    "28 Days/Weeks/Years Later Collection",
    "Pitch Perfect Collection",
    "Now You See Me Collection",
    "Fifty Shades Collection",
    "Magic Mike Collection",
    "Wayne's World Collection",
    "Bill & Ted Collection",
    "Austin Powers Collection",
    "Dumb and Dumber Collection",
    "Meet the Parents Collection",
    "Police Academy Collection",
    // Superhero solos + spy sagas
    "Iron Man Collection",
    "Thor Collection",
    "Captain America Collection",
    "Guardians of the Galaxy Collection",
    "Ant-Man Collection",
    "The Amazing Spider-Man Collection",
    "Spider-Man: Spider-Verse Collection",
    "Fantastic Beasts Collection",
    "The Jack Ryan Collection",
    "Has Fallen Collection",
    // Sixth wave (2026-09) — adult-leaning franchises, and the answer to the
    // family ones that used to sit in this list: Toy Story, Shrek, Despicable
    // Me, Madagascar and friends are the kids catalog's now, so an adult
    // profile stops being offered a wall of animation. Every name below was
    // verified live against /search/collection to resolve EXACTLY (see
    // scripts/tmdb_collections.py); where TMDB's page carries a different
    // spelling, the canonical one is used:
    //   'Mechanic Collection', 'Death Race (2008) Collection',
    //   'The Chronicles of Riddick Collection', 'Jump Street Collection',
    //   'Ong Bak Collection', 'Twilight Collection',
    //   'King Kong (1933) Collection', 'King Kong (1976) Collection',
    //   'The Pink Panther (Original) Collection', 'Addams Family Collection'.
    // Crime / action / heist
    "Sicario Collection",
    "Jack Reacher Collection",
    "Den of Thieves Collection",
    "Extraction Collection",
    "xXx Collection",
    "Escape Plan Collection",
    "The Hitman's Bodyguard Collection",
    "Universal Soldier Collection",
    "Mechanic Collection",
    "Death Race (2008) Collection",
    "The Chronicles of Riddick Collection",
    "Jump Street Collection",
    "Ong Bak Collection",
    "Ip Man Collection",
    "The Raid Collection",
    "Infernal Affairs Collection",
    "Train to Busan Collection",
    "Taxi Collection",
    "48 Hrs. Collection",
    "Coming to America Collection",
    "The Nutty Professor Collection",
    "Big Momma's House Collection",
    "Madea Collection",
    "Barbershop Collection",
    "Ride Along Collection",
    "Ted Collection",
    "Horrible Bosses Collection",
    "Neighbors Collection",
    "Ace Ventura Collection",
    "Shanghai Noon Collection",
    // Horror
    "Terrifier Collection",
    "Hatchet Collection",
    "Hostel Collection",
    "Wolf Creek Collection",
    "The Collector Collection",
    "Candyman Collection",
    "The Omen Collection",
    "Poltergeist Collection",
    "Pet Sematary Collection",
    "Sinister Collection",
    "It Collection",
    "I Know What You Did Last Summer Collection",
    "Urban Legend Collection",
    "Piranha Collection",
    "Anaconda Collection",
    "Lake Placid Collection",
    // Sci-fi / fantasy / adventure / comedy sagas
    "Knives Out Collection",
    "Twister Collection",
    "Speed Collection",
    "Starship Troopers Collection",
    "The Thing Collection",
    "Sherlock Holmes Collection",
    "Conan the Barbarian Collection",
    "G.I. Joe Collection",
    "Silent Hill Collection",
    "Hitman Collection",
    "Tomb Raider Collection",
    "Mortal Kombat Collection",
    "Kick-Ass Collection",
    "Hellboy Collection",
    "Blade Collection",
    "The Magnificent Seven Collection",
    "Young Guns Collection",
    "King Kong (1933) Collection",
    "King Kong (1976) Collection",
    "The Pink Panther (Original) Collection",
    "Addams Family Collection",
    "Twilight Collection",
    "After Collection",
    "Step Up Collection",
    "Bring It On Collection",
    "Grease Collection",
    "Mamma Mia! Collection"
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

// ---------------------------------------------------------------------------
// Kids Mode browse browser. A kids profile gets its own sidebar: every list
// below is kid-focused end to end (kids collections, kid-focused services
// and networks, kid-friendly keywords, family/animation studios) so the
// chips themselves never point a child at adult slates.
//
// The KIDS_* name lists need not be subsets of the standard BROWSE_* ones:
// SearchViewModel resolves the UNION of both and disk-caches ids by name, so
// one pass serves both modes and a kids-only name (a kids collection, a kids
// network) still resolves. That matters twice over for the collections — a
// kid-facing franchise is the kids catalog's, not a chip an adult profile has
// to scroll past, while the resolver still hands it an id.
// ---------------------------------------------------------------------------

/**
 * Kid-friendly genre set: animation + family-first, plus the gentle
 * adventure/comedy/music lanes. Everything hard (crime, horror, war,
 * thrillers, news/reality/soap lanes) is excluded by construction.
 */
val KIDS_GENRES: List<BrowseEntry> = BROWSE_GENRES.filter { it.id in setOf(
    16,      // Animation
    10751,   // Family
    12,      // Adventure
    35,      // Comedy
    14,      // Fantasy
    10402,   // Music
    10762,   // Kids (TV)
    99       // Documentary (nature/space docs are a kids staple)
)}

/**
 * Kid-focused streaming services and networks: all-ages and family
 * slates only (kid brands, animation studios' streamers, family cable).
 * providers/network ids follow the same verified-id conventions as
 * BROWSE_SERVICES / BROWSE_NETWORKS above. All ids re-verified live
 * against TMDB (2026-09): PBS Kids is network 122 (14 is plain PBS with
 * an adult slate), Universal Kids is network 2133 (1279 is an unrelated
 * company), TeenNick is network 234 (159 resolves to Indian StarPlus),
 * and Nick Jr. (35) / Nicktoons (224) were probed in via their flagship
 * shows.
 */
val KIDS_SERVICES: List<BrowseService> = listOf(
    BrowseService(
        "Disney+",
        providerId = 337,
        networkOrCompanyId = 2739,
        networkIsCompany = false
    ),
    // Nick's subscription streamer — all kids, all day.
    BrowseService(
        "Paramount+",
        providerId = 2303,
        networkOrCompanyId = 4330,
        networkIsCompany = false
    ),
    BrowseService(
        "Netflix",
        providerId = 8,
        networkOrCompanyId = 213,
        networkIsCompany = false
    ),
    BrowseService(
        "PBS Kids",
        providerId = null,
        networkOrCompanyId = 122, // TMDB 'PBS Kids' — 14 is plain PBS (adult slate)
        networkIsCompany = false
    ),
    BrowseService(
        "Cartoon Network",
        providerId = null,
        networkOrCompanyId = 56,
        networkIsCompany = false
    ),
    BrowseService(
        "Nickelodeon",
        providerId = null,
        networkOrCompanyId = 13,
        networkIsCompany = false
    ),
    BrowseService(
        "Disney Channel",
        providerId = null,
        networkOrCompanyId = 54,
        networkIsCompany = false
    ),
    BrowseService(
        "Disney Junior",
        providerId = null,
        networkOrCompanyId = 281,
        networkIsCompany = false
    ),
    BrowseService(
        "Disney XD",
        providerId = null,
        networkOrCompanyId = 44,
        networkIsCompany = false
    ),
    BrowseService(
        "Boomerang",
        providerId = null,
        networkOrCompanyId = 5459,
        networkIsCompany = false
    ),
    BrowseService(
        "Nick Jr.",
        providerId = null,
        networkOrCompanyId = 35, // flagship probes: Bubble Guppies / Team Umizoomi
        networkIsCompany = false
    ),
    BrowseService(
        "Nicktoons",
        providerId = null,
        networkOrCompanyId = 224, // flagship probes: T.U.F.F. Puppy / Robot and Monster
        networkIsCompany = false
    ),
    BrowseService(
        "Universal Kids",
        providerId = null,
        networkOrCompanyId = 2133, // Top Chef Junior / Where's Waldo? — 1279 is a dead company id
        networkIsCompany = false
    ),
    BrowseService(
        "TeenNick",
        providerId = null,
        networkOrCompanyId = 234, // Hollywood Heights probe — 159 is Indian 'StarPlus'
        networkIsCompany = false
    ),
    // 2026-09 second wave — family-slate streamers and more kids networks
    // (ids verified live against TMDB: watch-provider list for providerIds,
    // network endpoints / flagship-show probes for networkIds).
    BrowseService(
        "Apple TV",
        providerId = 350,
        networkOrCompanyId = 2552, // Ted Lasso probe (2572 is Smithsonian Earth)
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
    // Anime is a kids staple — Crunchyroll's all-ages side (Doraemon,
    // Pokémon, Yo-kai Watch dubs live here).
    BrowseService(
        "Crunchyroll",
        providerId = 283,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    // FAST services: heavy kids channels, free.
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
        "The Roku Channel",
        providerId = 207,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    BrowseService(
        "Plex",
        providerId = 538,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    // Big subscription catalogs with deep kids libraries; network ids are
    // the originals networks (Prime Video 1024 via Pete the Cat probe,
    // Peacock 3353 via TrollsTopia / Where's Waldo? probes).
    BrowseService(
        "Prime Video",
        providerId = 9,
        networkOrCompanyId = 1024,
        networkIsCompany = false
    ),
    BrowseService(
        "Peacock",
        providerId = 386,
        networkOrCompanyId = 3353,
        networkIsCompany = false
    ),
    BrowseService(
        "Discovery Family",
        providerId = null,
        networkOrCompanyId = 1287, // MLP: Friendship Is Magic probe
        networkIsCompany = false
    ),
    BrowseService(
        "Treehouse TV",
        providerId = null,
        networkOrCompanyId = 216, // MLP:FIM co-network probe
        networkIsCompany = false
    ),
    BrowseService(
        "ABC Kids (AU)",
        providerId = null,
        networkOrCompanyId = 2854, // Bluey's home network
        networkIsCompany = false
    ),
    BrowseService(
        "Hub Network",
        providerId = null,
        networkOrCompanyId = 518, // MLP:FIM original home
        networkIsCompany = false
    ),
    // 2026-09 third wave — international kids channels. TMDB's network
    // search endpoint returns nothing for these names, so every id was
    // probed through flagship shows (Hey Duggee→CBeebies, Shaun the
    // Sheep→CBBC, Caillou→Teletoon, Pocoyo→CITV, Almost Naked Animals→YTV,
    // The Next Step→Family Channel, Dino Dan→Noggin).
    BrowseService(
        "CBeebies",
        providerId = null,
        networkOrCompanyId = 166, // Hey Duggee / Charlie and Lola
        networkIsCompany = false
    ),
    BrowseService(
        "CBBC",
        providerId = null,
        networkOrCompanyId = 15, // Shaun the Sheep
        networkIsCompany = false
    ),
    BrowseService(
        "Teletoon",
        providerId = null,
        networkOrCompanyId = 83, // Caillou / Total Drama
        networkIsCompany = false
    ),
    BrowseService(
        "CITV",
        providerId = null,
        networkOrCompanyId = 112, // Pocoyo
        networkIsCompany = false
    ),
    BrowseService(
        "YTV",
        providerId = null,
        networkOrCompanyId = 46, // Almost Naked Animals
        networkIsCompany = false
    ),
    BrowseService(
        "Family Channel",
        providerId = null,
        networkOrCompanyId = 197, // The Next Step
        networkIsCompany = false
    ),
    BrowseService(
        "Noggin",
        providerId = null,
        networkOrCompanyId = 188, // Dino Dan
        networkIsCompany = false
    )
)

/**
 * Kid-friendly studios: animation houses and family brands. Every id
 * verified live against /company/{id} (2026-09). The tail entries are
 * family-house additions resolved via /search/company with a discover
 * depth check (Toei Animation's company candidates were 0-movie pages,
 * so the anime studios stay on the standard list instead).
 */
val KIDS_STUDIOS: List<BrowseEntry> = popularFirst(
    listOf(
    BrowseEntry(2, "Walt Disney Pictures"),
    BrowseEntry(3, "Pixar"),
    BrowseEntry(6125, "Walt Disney Animation Studios"),
    BrowseEntry(6704, "Illumination"),
    BrowseEntry(521, "DreamWorks Animation"),
    BrowseEntry(10342, "Studio Ghibli"),
    BrowseEntry(2251, "Sony Pictures Animation"),
    BrowseEntry(25120, "Warner Animation Group"),
    BrowseEntry(24955, "Paramount Animation"),
    BrowseEntry(2348, "Nickelodeon Movies"),
    BrowseEntry(7899, "Cartoon Network Studios"),
    BrowseEntry(12654, "The Pokémon Company"),
    BrowseEntry(1, "Lucasfilm"),
    BrowseEntry(297, "Aardman"),
    BrowseEntry(11537, "LAIKA"),
    BrowseEntry(2785, "Warner Bros. Animation"),
    BrowseEntry(6254, "The Jim Henson Company")
    ),
    name = { it.name },
    popular = POPULAR_KIDS_STUDIOS_ORDER
)

/**
 * Kids collections: franchises a child knows, resolved at runtime exactly
 * like the standard list (resolver unions both lists, so entries need not
 * be a strict subset of BROWSE_COLLECTION_NAMES anymore).
 */
val KIDS_COLLECTION_NAMES = listOf(
    "Toy Story Collection",
    "The Avengers Collection",
    "Spider-Man Collection",
    "Jurassic Park Collection",
    "Pirates of the Caribbean Collection",
    "Indiana Jones Collection",
    "Transformers Collection",
    "Back to the Future Collection",
    "Despicable Me Collection",
    "How to Train Your Dragon Collection",
    "Batman Collection",
    "Superman Collection",
    "Jumanji Collection",
    "The Chronicles of Narnia Collection",
    "Percy Jackson Collection",
    "The Hunger Games Collection",
    "Ice Age Collection",
    "Madagascar Collection",
    "Shrek Collection",
    "Kung Fu Panda Collection",
    "Finding Nemo Collection",
    "Monsters, Inc. Collection",
    "The Incredibles Collection",
    "Cars Collection",
    "The Hobbit Collection",
    // 2026-09 additions — exact-resolving family franchises (see the
    // fourth wave on BROWSE_COLLECTION_NAMES). The resolver unions both
    // lists, so kids-only names resolve fine.
    "The Land Before Time Collection",
    "Alvin and the Chipmunks Collection",
    "The Secret Life of Pets Collection",
    "Hotel Transylvania Collection",
    "Cloudy with a Chance of Meatballs Collection",
    "Paddington Collection",
    "Diary of a Wimpy Kid Collection",
    "Scooby-Doo Collection",
    "The LEGO Movie Collection",
    "The Boss Baby Collection",
    "The Croods Collection",
    "Sing Collection",
    // 2026-09 second wave — TV-brand and modern-kids franchises, every
    // name verified to resolve exactly against /search/collection.
    "SpongeBob Collection",
    "PAW Patrol (Theatrical) Collection",
    "My Little Pony: Equestria Girls Collection",
    "Curious George Collection",
    "The Sandlot Collection",
    "Peter Rabbit Collection",
    "The Muppets Collection",
    "Stuart Little Collection",
    "Care Bears Collection",
    "Garfield Collection",
    "Night at the Museum Collection",
    "The Angry Birds Collection",
    "Teenage Mutant Ninja Turtles Collection",
    "The Smurfs (Animated) Collection",
    "Lilo & Stitch (Animated) Collection",
    "Lilo & Stitch (Live-Action) Collection",
    "Barbie Collection",
    // 2026-09 third wave — Disney classics + modern hits. Every name
    // re-verified live against /search/collection (exact first hit).
    "Frozen Collection",
    "Moana Collection",
    "Encanto Collection",
    "Coco Collection",
    "Zootopia Collection",
    "Tangled Collection",
    "Wreck-It Ralph Collection",
    "Wish Collection",
    "Bambi Collection",
    "Peter Pan Collection",
    "Lady and the Tramp Collection",
    "101 Dalmatians (Animated) Collection",
    "The Adventures of Pinocchio Collection",
    "Pocahontas Collection",
    "Hercules Collection",
    "Brother Bear Collection",
    "Robin Hood (Animated) Collection",
    "The Emperor's New Groove Collection",
    "The Rescuers Collection",
    "The Fox and the Hound Collection",
    "The Jungle Book Collection",
    "Mary Poppins Collection",
    "A Goofy Movie Collection",
    // 2026-09 third wave — animation franchises beyond Disney
    "Rio Collection",
    "The Trolls Collection",
    "Puss in Boots Collection",
    "The Wild Robot Collection",
    "The Bad Guys Collection",
    "An American Tail Collection",
    "Balto Collection",
    "Alpha and Omega Collection",
    "Rugrats Collection",
    // 2026-09 third wave — live-action family staples
    "Home Alone Collection",
    "Sonic the Hedgehog Collection",
    "Nanny McPhee Collection",
    "Freaky Friday Collection",
    "The Shaggy Dog Collection",
    "Flubber Collection",
    "Tooth Fairy Collection",
    "Herbie Collection",
    "Dr. Dolittle Collection",
    "The Mighty Ducks Collection",
    "Air Bud Collection",
    "Beethoven Collection",
    "The Little Rascals Collection",
    "Dennis the Menace Collection",
    "Problem Child Collection",
    "Look Who's Talking Collection",
    "George of the Jungle Collection",
    "Inspector Gadget Collection",
    "Woody Woodpecker Collection",
    "The Flintstones Collection",
    "Mr. Bean Collection",
    "Baby Geniuses Collection",
    "Goosebumps Collection",
    // 2026-09 third wave — seasonal / DCOM
    "All Dogs Go to Heaven Collection",
    "FernGully Collection",
    "The Polar Express Collection",
    "The Santa Clause Collection",
    "Hocus Pocus Collection",
    "Halloweentown Collection",
    "Descendants Collection",
    "High School Musical Collection",
    "Camp Rock Collection"
)

/**
 * Kid-focused keywords: silly, warm, adventurous. No horror/war/crime
 * keywords at all. Strict name subset of BROWSE_KEYWORD_NAMES.
 */
val KIDS_KEYWORD_NAMES = listOf(
    "dinosaur",
    "dragon",
    "wizard",
    "superhero",
    "robot",
    "pirate",
    "mermaid",
    "genie",
    "space",
    "time travel",
    "alien",
    "monster",
    "kaiju",
    "coming of age",
    "friendship",
    "wedding",
    "dancing",
    "sports",
    "surfing",
    "skateboarding",
    "gymnastics",
    "olympics",
    "road trip",
    "amusement park",
    "circus",
    "summer camp",
    "island",
    "snow",
    "desert",
    "school",
    "high school",
    "single father",
    "single mother",
    "body swap",
    "shapeshifting",
    "magical object",
    "astronaut",
    "moon landing",
    "deep space",
    "boxing",
    "chef",
    "band",
    "musician",
    "dancer",
    "choir",
    // 2026-09 additions — animals, holidays, and everyday-kid topics
    // (see the fourth wave on BROWSE_KEYWORD_NAMES). Strict subset of the
    // standard list so one resolve pass serves both modes.
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
    "sleepover",
    // 2026-09 second wave — animals, myth, everyday-kid life, gentle
    // adventure. Names resolve against TMDB keyword search; the resolver
    // unions both lists, so kids-only entries resolve fine.
    // maintained with BROWSE_KEYWORD_NAMES.
    "jungle",
    "safari",
    "penguin",
    "dolphin",
    "turtle",
    "bear",
    "wolf",
    "rabbit",
    "duck",
    "farm",
    "garden",
    "treehouse",
    "clubhouse",
    "hide and seek",
    "drawing",
    "storybook",
    "library",
    "helicopter",
    "fire truck",
    "trains",
    "submarine",
    "hot air balloon",
    "rockets",
    "mars",
    "volcano",
    "rainbow",
    "wishing well",
    "flying car",
    "giant",
    "troll",
    "elf",
    "gnome",
    "leprechaun",
    "yeti",
    "knight",
    "castle",
    "ninja",
    "detective",
    "secret agent",
    "maze",
    "riddle",
    "bowling",
    "karate",
    "cheerleading",
    "marching band",
    "school play",
    "talent show",
    "science fair",
    "spelling bee",
    "best friends",
    "siblings",
    "new baby",
    "first day of school",
    "moving",
    "new kid"
)

/** Decades are neutral; reuse the standard list. */
val KIDS_DECADES: List<BrowseEntry> = BROWSE_DECADES

/** Merged "Services & Networks" submenu for kids profiles. */
val KIDS_PROVIDER_ENTRIES: List<BrowseEntry> = popularFirst(
    KIDS_SERVICES
        .map { service ->
            BrowseEntry(
                service.networkOrCompanyId ?: -1,
                service.name,
                service.providerId,
                service.networkOrCompanyId,
                service.networkIsCompany,
                service.originalsCompanyId
            )
        },
    name = { it.name },
    popular = POPULAR_SERVICES_ORDER + POPULAR_NETWORKS_ORDER
)

/**
 * The kids sidebar. Same six category keys as the standard browser so the
 * ViewModel/screen/navigation plumbing is shared; only the chip lists and
 * labels differ.
 */
val KIDS_BROWSE_CATEGORIES: List<BrowseCategory> = listOf(
    BrowseCategory("genres", "Genres", KIDS_GENRES),
    BrowseCategory("keywords", "Keywords", emptyList()),
    BrowseCategory("services", "Services & Networks", KIDS_PROVIDER_ENTRIES),
    BrowseCategory("studios", "Studios", KIDS_STUDIOS),
    BrowseCategory("decades", "Decades", KIDS_DECADES),
    BrowseCategory("collections", "Collections", emptyList())
)
