package com.kennyb1201.kbstream.ui.search

/**
 * Overflow half of the browse catalog, KIDS side.
 *
 * SearchBrowseCatalog.kt grew past the size the editor rewrites in one
 * pass, so the kids additions live here and SearchViewModel merges them in:
 * [baseBrowseCategories] appends the services/studios entries and
 * [SearchViewModel.activeCollectionNames] plus [SearchViewModel.resolveCatalogEntries]
 * cover the collections. Nothing here duplicates the main file — these are
 * purely additions, so a merge is always a plain append.
 *
 * Every id follows the same verification rule as the main file: providerIds
 * come from the live US watch-provider registry, networkIds from
 * flagship-show probes (TMDB has no network search endpoint), and company
 * ids from a /search/company name match plus a non-empty movie slate.
 */

/** Kid-focused services/networks added after the main catalog's kids list. */
val KIDS_SERVICES_EXTRA: List<BrowseService> = listOf(
    // Netflix's dedicated kids storefront — a different provider entry to
    // the main Netflix one, so a kids profile gets the kids catalog only.
    BrowseService(
        "Netflix Kids",
        providerId = 175,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    BrowseService(
        "Toon Goggles",
        providerId = 2030,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    BrowseService(
        "Pure Flix",
        providerId = 278,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    BrowseService(
        "Fawesome",
        providerId = 2409,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    BrowseService(
        "BYUtv",
        providerId = 2129,
        networkOrCompanyId = null,
        networkIsCompany = false
    ),
    // Nick at Nite is network 259 (the classic-sitcom block). Discovery Kids
    // (2630, Doki) and Fox Kids (2686, Power Rangers) are archive pages —
    // kept for their classic catalogs, not for a live slate.
    BrowseService(
        "Nick at Nite",
        providerId = null,
        networkOrCompanyId = 259,
        networkIsCompany = false
    ),
    BrowseService(
        "Discovery Kids",
        providerId = null,
        networkOrCompanyId = 2630,
        networkIsCompany = false
    ),
    BrowseService(
        "Fox Kids",
        providerId = null,
        networkOrCompanyId = 2686,
        networkIsCompany = false
    )
)

/**
 * [KIDS_SERVICES_EXTRA] in submenu-entry form — the same field mapping
 * [BROWSE_PROVIDER_ENTRIES] applies in the main catalog file.
 */
val KIDS_SERVICES_EXTRA_ENTRIES: List<BrowseEntry> = KIDS_SERVICES_EXTRA.map { service ->
    BrowseEntry(
        service.networkOrCompanyId ?: -1,
        service.name,
        service.providerId,
        service.networkOrCompanyId,
        service.networkIsCompany,
        service.originalsCompanyId
    )
}

/**
 * Kid-friendly studios added after the main catalog's kids list.
 *
 * These are the children's houses the standard studios strip no longer
 * carries: the 2026-09 curating pass MOVED them here rather than deleting
 * them, because a kids studio the kids menu already offers belongs on the kids
 * menu and not on the standard strip. Nelvana is the one the standard list
 * also names in its removed block — it moved for the same reason.
 */
val KIDS_STUDIOS_EXTRA: List<BrowseEntry> = listOf(
    BrowseEntry(1023, "Nelvana"),
    BrowseEntry(148496, "WildBrain Studios"),
    BrowseEntry(23948, "Cartoon Saloon"),
    BrowseEntry(4152, "Titmouse"),
    BrowseEntry(2787, "Reel FX Creative Studios"),
    BrowseEntry(8089, "Animal Logic"),
    BrowseEntry(179999, "Skydance Animation")
)

/**
 * Kid-friendly franchises added after the main catalog's kids list. Every
 * name resolves EXACTLY via /search/collection — the resolver prefers an
 * exact-name hit, so an inexact name silently loses the chip even when TMDB
 * has a page for it.
 */
val KIDS_COLLECTION_NAMES_EXTRA: List<String> = listOf(
    // Disney animation canon — the biggest gap in the kids list.
    "The Lion King Collection",
    "Aladdin Collection",
    "Beauty and the Beast Collection",
    "The Little Mermaid Collection",
    "Cinderella Collection",
    "Mulan Collection",
    "Alice in Wonderland Collection",
    // Animation franchises beyond Disney
    "Chicken Run Collection",
    "Wallace & Gromit Collection",
    "Shaun the Sheep Collection",
    "Open Season Collection",
    "Surf's Up Collection",
    "The Swan Princess Collection",
    "Pokémon Collection",
    "Monster High Collection",
    // Live-action family staples
    "Casper Collection",
    // 2026-09 — kid franchises: modern animation, TV-brand favorites, anime
    // features, and the family franchises that moved out of the adult
    // collections list (Honey, I Shrunk the Kids was the only one of them not
    // already in the main kids list). Every name verified live against
    // /search/collection to resolve EXACTLY.
    "Honey, I Shrunk the Kids Collection",
    "Minions Collection",
    "Space Jam Collection",
    "The NeverEnding Story Collection",
    "Free Willy Collection",
    "The Brave Little Toaster Collection",
    "Spirit Collection",
    "Thomas & Friends Collection",
    "Power Rangers Collection",
    "Teen Titans Collection",
    "Ben 10 Collection",
    "Yu-Gi-Oh! Collection",
    "Dragon Ball Collection",
    "Naruto Collection",
    "One Piece Collection",
    "Sailor Moon Collection",
    "Detective Conan Collection",
    "Crayon Shin-chan Collection",
    "Stand by Me Doraemon Collection",
    "Franklin Collection",
    "Planes Collection",
    "Tinker Bell Collection",
    "How the Grinch Stole Christmas Collection",
    "The Cheetah Girls Collection",
    "Minecraft Movie Collection",
    // 2026-09 seventh wave — more kids collections, every name verified
    // live against /search/collection to resolve EXACTLY and its member
    // titles read back to confirm it is the franchise the name claims
    // (the resolver prefers an exact hit, so an inexact name is a dead
    // chip, and a same-ish name can point at an unrelated page).
    // Appended here, not to the main file, so the merge stays a plain
    // append — see the header note in KIDS_COLLECTION_NAMES.
    // ── Film and TV franchises.
    "The Super Mario Collection",
    "The Smurfs (Theatrical) Collection",
    "Digimon Adventure Collection",
    "Captain Underpants Collection",
    "Megamind Collection",
    "Sinbad Collection",
    "Anastasia Collection",
    "The Secret of NIMH Collection",
    "Short Circuit Collection",
    "Atlantis Collection",
    "The Hunchback of Notre Dame Collection",
    "The Gruffalo Collection",
    "Inside Out Collection",
    "The Mitchells vs. the Machines Collection",
    "Leo Collection",
    "The Sea Beast Collection",
    "The Garfield Movie Collection",

    // ── Animal films.
    "The Benji Collection",
    "Beverly Hills Chihuahua Collection",
    "Cats & Dogs Collection",
    "Homeward Bound Collection",
    "White Fang Collection",
    "Lassie Collection",
    "Charlotte's Web Collection",
    "Babe Collection",

    // ── Kids TV movies and all-ages anime.
    "Strawberry Shortcake (2003) Collection",
    "Johnny Tsunami Collection",
    "Zenon Collection",
    "Twitches Collection",
    "Wizards of Waverly Place Collection",
    "Kim Possible Collection",
    "Recess Collection",
    "The Powerpuff Girls Collection",
    "Astro Boy Collection",
    "Robotech Collection",
    "Cardcaptor Sakura Collection",
    "Yo-kai Watch Collection",
    "Tamagotchi Collection",
    "Hamtaro Collection",
    "Inazuma Eleven Collection",
)
