package com.kennyb1201.kbstream.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the Search browse catalog that the app relies on at
 * runtime:
 *
 *  1. SearchViewModel resolves the UNION of the standard and kids lists
 *     (distinct by name) and caches ids by name against TMDB search, so
 *     a name appearing in BOTH lists is fine — same name, same TMDB
 *     entity, same id. The real hazards are duplicates WITHIN a list
 *     (double chips, double-resolved ids) and names with no usable id.
 *
 *  2. Kids categories expose only kid-focused entries: no adult-only
 *     networks/studios slip in, and every collection is a franchise a
 *     parent would hand to a kid.
 *
 *  3. Every kids service/decade entry must carry a discoverable id.
 *
 *  4. Every keyword name is the EXACT name TMDB's /search/keyword answers
 *     with — the resolver accepts an exact-name hit only, so a misspelling
 *     is not a wrong rail, it is a chip that opens nothing.
 *
 *  5. The union resolve and the disk cache only ever RENDER the active mode's
 *     own names (see [browseEntriesFor]). Invariants 1-4 all inspect the
 *     LISTS, so none of them says anything about what a kids profile is
 *     handed at runtime — the resolver once published the raw union, which
 *     put every adult tag and every adult collection into the kids Browse
 *     menu.
 */
class SearchBrowseCatalogKidsTest {

    private fun names(entries: List<BrowseEntry>) = entries.map { it.name.trim() }

    /** Stand-in ids: [browseEntriesFor] keys on the name, which is the chip. */
    private fun chips(labels: List<String>): List<BrowseEntry> =
        labels.distinct().mapIndexed { index, label -> BrowseEntry(index + 1, label) }

    // ── cache-contract invariants ───────────────────────────────────

    @Test
    fun `union of keyword lists has no whitespace-variant duplicates`() {
        // The resolver feeds BROWSE + KIDS through .distinct() before
        // resolving, so an exact-equal name in both lists collapses to one
        // chip (fine — same name, same TMDB entity). What must never happen:
        // the same trimmed name appearing with DIFFERENT raw spellings
        // ("dinosaur" vs "dinosaur ") — .distinct() keeps both, the UI
        // renders two identical chips, and the id cache races. Group by
        // trimmed name and assert every group has exactly one spelling.
        val variants = (BROWSE_KEYWORD_NAMES + KIDS_KEYWORD_NAMES)
            .groupBy { it.trim() }
            .filterValues { spellings -> spellings.distinct().size > 1 }
        assertTrue(
            "whitespace-variant keyword duplicates: $variants",
            variants.isEmpty()
        )
    }

    @Test
    fun `union of collection lists has no whitespace-variant duplicates`() {
        val variants = (BROWSE_COLLECTION_NAMES + KIDS_COLLECTION_NAMES)
            .groupBy { it.trim() }
            .filterValues { spellings -> spellings.distinct().size > 1 }
        assertTrue(
            "whitespace-variant collection duplicates: $variants",
            variants.isEmpty()
        )
    }

    @Test
    fun `kids services carry a usable discover id and no duplicates`() {
        // Kids services mix streaming services (by provider+network id) and
        // kid NETWORKS as page targets (Cartoon Network, PBS Kids...), so
        // unlike keywords/collections they are NOT a subset of the standard
        // streaming-services list. The real invariants: every entry can
        // drive a discover page and no brand appears twice.
        // The kids list is split across two files (SearchBrowseCatalog plus
        // SearchBrowseCatalogExtras, which the ViewModel merges in), so the
        // invariants have to run over BOTH halves or the overflow would
        // never be checked.
        val allKidsServices = KIDS_SERVICES + KIDS_SERVICES_EXTRA

        val unusable = allKidsServices.filter {
            it.providerId == null && it.networkOrCompanyId == null
        }
        assertTrue(
            "kids services with no provider/network id: ${unusable.map { it.name }}",
            unusable.isEmpty()
        )

        val dupes = allKidsServices.groupingBy { it.name.trim() }.eachCount()
            .filterValues { it > 1 }.keys
        assertTrue("duplicate kids services: $dupes", dupes.isEmpty())
    }

    // ── hygiene: no duplicates, no blank names ──────────────────────────

    @Test
    fun `no duplicate names inside any chip list`() {
        for ((label, list) in mapOf(
            "KIDS_KEYWORD_NAMES" to KIDS_KEYWORD_NAMES,
            "KIDS_COLLECTION_NAMES" to
                (KIDS_COLLECTION_NAMES + KIDS_COLLECTION_NAMES_EXTRA),
            "BROWSE_KEYWORD_NAMES" to BROWSE_KEYWORD_NAMES,
            "BROWSE_COLLECTION_NAMES" to BROWSE_COLLECTION_NAMES
        )) {
            val dupes = list.map { it.trim() }.groupingBy { it }.eachCount()
                .filterValues { it > 1 }.keys
            assertTrue("duplicate entries in $label: $dupes", dupes.isEmpty())
        }
    }

    @Test
    fun `no blank or whitespace-only names`() {
        for ((label, list) in mapOf(
            "KIDS_KEYWORD_NAMES" to KIDS_KEYWORD_NAMES,
            "KIDS_COLLECTION_NAMES" to
                (KIDS_COLLECTION_NAMES + KIDS_COLLECTION_NAMES_EXTRA)
        )) {
            val blanks = list.filter { it.isBlank() }
            assertTrue("blank entries in $label: ${blanks.size}", blanks.isEmpty())
        }
    }

    // ── kids safety: ids verified to be the kid-facing entities ─────────

    @Test
    fun `kids services exclude the known adult-slate network ids`() {
        // TMDB ids verified 2026-09: 14 is plain PBS (adult slate, NOT PBS
        // Kids = 122), 1279 is an unrelated company (Universal Kids = 2133),
        // 159 is Indian StarPlus (TeenNick = 234). The regression that put
        // 14 into KIDS_SERVICES surfaced a kids profile's search with adult
        // PBS programming.
        val banned = setOf(14, 1279, 159)
        val offenders = (KIDS_SERVICES + KIDS_SERVICES_EXTRA)
            .filter { it.networkOrCompanyId in banned }
        assertTrue(
            "kids services contain known-wrong network ids: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `kids decades stay inside the standard decades`() {
        val standard = BROWSE_DECADES.map { it.id }.toSet()
        val kids = KIDS_DECADES.map { it.id }
        assertTrue("kids decades must be a subset of standard", kids.all { it in standard })
    }

    // ── the kids/adult split of the collections list ─────────────────────

    @Test
    fun `kid-facing franchises are not offered to an adult profile`() {
        // Reported: the adult Collections submenu was a wall of animation -
        // Toy Story, Shrek, Despicable Me and friends. Those franchises are
        // the kids catalog's now. This is a deliberate split, not "everything
        // on the kids list": the tentpoles an adult also watches (Avengers,
        // Jurassic Park, Batman, Harry Potter) stay on both lists.
        val kidFacing = listOf(
            "Toy Story Collection",
            "Shrek Collection",
            "Despicable Me Collection",
            "How to Train Your Dragon Collection",
            "Ice Age Collection",
            "Madagascar Collection",
            "Kung Fu Panda Collection",
            "Finding Nemo Collection",
            "Monsters, Inc. Collection",
            "The Incredibles Collection",
            "Cars Collection",
            "Sing Collection",
            "The Boss Baby Collection",
            "The Croods Collection",
            "The Secret Life of Pets Collection",
            "Hotel Transylvania Collection",
            "Cloudy with a Chance of Meatballs Collection",
            "Paddington Collection",
            "Diary of a Wimpy Kid Collection",
            "Scooby-Doo Collection",
            "The LEGO Movie Collection",
            "Chicken Run Collection",
            "Wallace & Gromit Collection",
            "Shaun the Sheep Collection",
            "Open Season Collection",
            "Surf's Up Collection",
            "Pokémon Collection",
            "The Swan Princess Collection",
            "Casper Collection",
            "Monster High Collection",
            "Honey, I Shrunk the Kids Collection",
            "Alvin and the Chipmunks Collection",
            "The Land Before Time Collection"
        )

        val leaked = kidFacing.filter { it in BROWSE_COLLECTION_NAMES }
        assertTrue(
            "kid-facing franchises still in the adult collection list: $leaked",
            leaked.isEmpty()
        )

        // The move only counts if a kids profile can still reach them.
        val kids = KIDS_COLLECTION_NAMES + KIDS_COLLECTION_NAMES_EXTRA
        val missing = kidFacing.filterNot { it in kids }
        assertTrue(
            "kid-facing franchises missing from the kids collection list: $missing",
            missing.isEmpty()
        )
    }

    // ── the kids/adult split of the studios list ─────────────────────────

    // ── the 2026-09 seventh wave: more adult tags, more kids tags ───────

    @Test
    fun `adult keyword list carries the English and UK lanes`() {
        // The seventh wave was asked for more ENGLISH adult tags, so the UK
        // lanes are pinned by name. A later pass that trims the list back to
        // American-only content has to delete this test on purpose.
        val uk = listOf(
            "british",
            "england",
            "scotland",
            "ireland",
            "wales",
            "london, england",
            "irish",
            "scottish",
            "victorian era"
        )
        val missingUk = uk.filterNot { it in BROWSE_KEYWORD_NAMES }
        assertTrue(
            "UK tags missing from the adult keyword list: $missingUk",
            missingUk.isEmpty()
        )

        // ...plus the lanes the adult list was thinnest on: noir, procedurals,
        // modern horror, the western and prestige drama.
        val lanes = listOf(
            "film noir",
            "neo-noir",
            "whodunit",
            "police procedural",
            "folk horror",
            "creature feature",
            "spaghetti western",
            "space opera",
            "period drama",
            "true crime"
        )
        val missingLanes = lanes.filterNot { it in BROWSE_KEYWORD_NAMES }
        assertTrue(
            "adult lanes missing from the keyword list: $missingLanes",
            missingLanes.isEmpty()
        )
    }

    @Test
    fun `kids chips that never resolved were repointed at TMDB's names`() {
        // TMDB's /search/keyword has no exact match for "treehouse", "fire
        // truck" or "rockets" (its canonical names are "tree house",
        // "firetruck" and "rocket"), and the resolver accepts an exact hit
        // only — so each shipped as a chip that opened nothing. "new baby"
        // and "new kid" had no exact match at all and were dropped; the first
        // idea comes back as "baby".
        val dead = listOf("treehouse", "fire truck", "rockets", "new baby", "new kid")
        for (name in dead) {
            assertFalse(
                "a chip that cannot resolve is back: $name",
                name in KIDS_KEYWORD_NAMES
            )
        }
        val live = listOf("tree house", "firetruck", "rocket", "baby")
        for (name in live) {
            assertTrue("replacement chip missing: $name", name in KIDS_KEYWORD_NAMES)
        }
    }

    @Test
    fun `keyword names are trimmed and lowercase in both lists`() {
        // The chip label is the raw string while the resolver matches the
        // exact TMDB name case-insensitively, so a stray capital or a trailing
        // space is either a chip that reads wrong or one that never resolves.
        val kids = KIDS_KEYWORD_NAMES.filter { it != it.trim() || it != it.lowercase() }
        assertTrue("kids keywords must be trimmed and lowercase: $kids", kids.isEmpty())
        val adult = BROWSE_KEYWORD_NAMES.filter { it != it.trim() || it != it.lowercase() }
        assertTrue("adult keywords must be trimmed and lowercase: $adult", adult.isEmpty())
    }

    @Test
    fun `seventh wave kids collections stay off the adult list`() {
        // Children's TV movies, animal films and all-ages anime: kid-facing by
        // construction, so an adult profile must not be offered them — the
        // same split the sixth wave applied to Toy Story and friends. Both
        // halves of the assertion matter: gone from the kids menu, or back on
        // the adult strip, are each a regression.
        val kidsOnly = listOf(
            "The Super Mario Collection",
            "Strawberry Shortcake (2003) Collection",
            "The Benji Collection",
            "Beverly Hills Chihuahua Collection",
            "Charlotte's Web Collection",
            "Babe Collection",
            "Lassie Collection",
            "Homeward Bound Collection",
            "White Fang Collection",
            "Astro Boy Collection",
            "Robotech Collection",
            "Cardcaptor Sakura Collection",
            "Yo-kai Watch Collection",
            "Tamagotchi Collection",
            "Hamtaro Collection",
            "Inazuma Eleven Collection",
            "The Gruffalo Collection",
            "Atlantis Collection",
            "The Hunchback of Notre Dame Collection",
            "Recess Collection",
            "The Powerpuff Girls Collection",
            "Zenon Collection",
            "Twitches Collection"
        )
        val kids = KIDS_COLLECTION_NAMES + KIDS_COLLECTION_NAMES_EXTRA
        val missing = kidsOnly.filterNot { it in kids }
        assertTrue("kids seventh-wave collections missing: $missing", missing.isEmpty())
        val leaked = kidsOnly.filter { it in BROWSE_COLLECTION_NAMES }
        assertTrue("kids seventh-wave collections on the adult list: $leaked", leaked.isEmpty())
    }

    @Test
    fun `children's houses stay on the kids menu and off the standard strip`() {
        // The 2026-09 curating pass took the anime houses and the regional
        // film studios off the standard studios strip, and MOVED the
        // children's houses to the kids list rather than deleting them: a kids
        // studio the kids menu already carries belongs on the kids menu. Both
        // halves are pinned, because either one alone is a regression — gone
        // from the kids menu, or back on the adult strip.
        val houses = listOf(
            "Nelvana",
            "WildBrain Studios",
            "Cartoon Saloon",
            "Titmouse",
            "Reel FX Creative Studios",
            "Animal Logic",
            "Skydance Animation"
        )

        val kids = (KIDS_STUDIOS + KIDS_STUDIOS_EXTRA).map { it.name.trim() }
        val missing = houses.filterNot { it in kids }
        assertTrue(
            "children's houses missing from the kids list: $missing",
            missing.isEmpty()
        )

        val standard = BROWSE_STUDIOS.map { it.name.trim() }
        val leaked = houses.filter { it in standard }
        assertTrue(
            "children's houses still on the standard studios strip: $leaked",
            leaked.isEmpty()
        )
    }

    // ── the kids/adult split at PUBLISH time ─────────────────────────

    @Test
    fun `a kids sidebar is never handed an adult-only tag`() {
        // Reported: a kids profile's Keywords menu was "almost all the adult
        // stuff". One name lookup serves both modes, so the resolver and the
        // disk cache both hold the UNION, and the kids pane only gets its own
        // list back because it is filtered when it is published.
        val union = BROWSE_KEYWORD_NAMES + KIDS_KEYWORD_NAMES
        val shown = browseEntriesFor("keywords", chips(union), isKidsMode = true)
        val kids = KIDS_KEYWORD_NAMES.toSet()
        assertTrue(
            "adult-only tags reach a kids profile: " +
                shown.map { it.name }.filterNot { it in kids }.take(10),
            shown.all { it.name in kids }
        )
        // The assertion above only means something while the lists differ.
        assertTrue(
            "the adult and kids keyword lists no longer differ",
            shown.size < union.distinct().size
        )
    }

    @Test
    fun `a kids sidebar is never handed an adult-only collection`() {
        val union =
            BROWSE_COLLECTION_NAMES + KIDS_COLLECTION_NAMES + KIDS_COLLECTION_NAMES_EXTRA
        val shown = browseEntriesFor("collections", chips(union), isKidsMode = true)
        val kids = (KIDS_COLLECTION_NAMES + KIDS_COLLECTION_NAMES_EXTRA).toSet()
        assertTrue(
            "adult-only collections reach a kids profile: " +
                shown.map { it.name }.filterNot { it in kids }.take(10),
            shown.all { it.name in kids }
        )
        assertTrue(
            "the adult and kids collection lists no longer differ",
            shown.size < union.distinct().size
        )
    }

    @Test
    fun `an adult sidebar is not handed the kids-only tags`() {
        // The union cuts both ways: the seventh wave's kids-only names must
        // not appear on the standard strip either.
        val union = BROWSE_KEYWORD_NAMES + KIDS_KEYWORD_NAMES
        val shown = browseEntriesFor("keywords", chips(union), isKidsMode = false)
        val adult = BROWSE_KEYWORD_NAMES.toSet()
        assertTrue(
            "kids-only tags reach an adult profile: " +
                shown.map { it.name }.filterNot { it in adult }.take(10),
            shown.all { it.name in adult }
        )
    }

    @Test
    fun `a mode flip re-slices the one cached union`() {
        // Both modes are served from a single resolve and a single cache
        // entry, so switching profile must re-slice, never re-resolve: every
        // resolved name has to land on exactly one of the two sides.
        val union = (BROWSE_KEYWORD_NAMES + KIDS_KEYWORD_NAMES).distinct()
        val resolved = chips(union)
        val forKids = browseEntriesFor("keywords", resolved, isKidsMode = true).map { it.name }
        val forAdults = browseEntriesFor("keywords", resolved, isKidsMode = false).map { it.name }
        assertEquals(
            "every resolved keyword belongs to exactly one mode",
            union.size,
            (forKids + forAdults).distinct().size
        )
    }

    @Test
    fun `categories curated per mode are passed through untouched`() {
        // Genres, services, studios and decades carry their split in the
        // category lists themselves, so the publish filter must not touch
        // them - and must never silently empty a category it does not own.
        val genres = chips(listOf("Comedy", "Drama"))
        assertEquals(genres, browseEntriesFor("genres", genres, isKidsMode = true))
        assertEquals(genres, browseEntriesFor("genres", genres, isKidsMode = false))
        assertEquals(genres, browseEntriesFor("decades", genres, isKidsMode = true))
    }
}
