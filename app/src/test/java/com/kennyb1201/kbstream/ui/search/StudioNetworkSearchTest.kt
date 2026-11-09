package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Networks in the Search screen's Studios rail (spec 2026-11-09).
 *
 * The rail was company-only because its one wave is `GET search/company` and
 * TMDB has no `/search/network` endpoint, so "CW" offered companies and never
 * The CW. The catalog already names every network the app can open - the same
 * verified entries the browse sidebar draws - so the fix is a local name match
 * merged in front of the company wave: no new call, no new endpoint, no catalog
 * change.
 *
 * What is pinned here:
 *
 *  - the match bands (exact, prefix, contains) and the catalog order inside a
 *    band, so the rail is not a relevance guess (F10),
 *  - the two-letter floor, and the provider-only `-1` entries that must still be
 *    offered (they open through their provider rails) versus an entry with
 *    neither id, which cannot be opened at all (F3),
 *  - the item key carrying the KIND, because company and network ids live in
 *    different id spaces and collide (F4/F6),
 *  - the merge: catalog first, companies behind, de-duplicated on the brand's
 *    name OR a company-space id the catalog entry names, capped at eight across
 *    both sides (F5/F12),
 *  - and, against the REAL catalog, that "CW" finds The CW and "HBO" finds the
 *    HBO Max service page - the two settlers' queries (S1/S2), settled here at
 *    the rule level as well as on the device.
 */
class StudioNetworkSearchTest {

    private fun network(
        id: Int,
        name: String,
        providerId: Int? = null,
        networkOrCompanyId: Int? = null,
        networkIsCompany: Boolean = false,
        originalsCompanyId: Int? = null
    ) = BrowseEntry(
        id = id,
        name = name,
        providerId = providerId,
        networkOrCompanyId = networkOrCompanyId,
        networkIsCompany = networkIsCompany,
        originalsCompanyId = originalsCompanyId
    )

    private fun company(id: Int, name: String) =
        TmdbSearchStudioResult(id = id, name = name, logoPath = null, originCountry = null)

    private fun names(hits: List<StudioSearchHit>): List<String> = hits.map { it.displayName }

    // ── the match bands ───────────────────────────────────────────────────

    @Test
    fun `an exact name leads, then prefixes, then substrings - catalog order inside each band`() {
        val entries = listOf(
            network(1, "Bravo"),
            network(2, "The CW"),
            network(3, "CW Seed"),
            network(4, "ABC"),
            network(5, "Nick at Nite")
        )

        val hits = networkStudioHits(entries, "CW", max = 8)

        assertEquals(
            "an exact match first, then names that START with the query, then names " +
                "that merely contain it - and no match at all for ABC or Bravo",
            listOf("CW Seed", "The CW"),
            names(hits)
        )
        assertEquals(
            "The CW is a contains-match on 'cw' and CW Seed a prefix-match: the band, " +
                "not the catalog's popular-first order, decides",
            "studio:network:3",
            hits.first().key
        )
    }

    @Test
    fun `a one-letter query contributes nothing, and so does a blank one`() {
        val entries = listOf(network(1, "A&E"), network(2, "ABC"))

        assertEquals(
            "a single letter would substring-match most of the catalog, and no brand " +
                "is one letter: the company wave answers that query alone",
            emptyList<String>(),
            names(networkStudioHits(entries, "a", max = 8))
        )
        assertEquals(emptyList<String>(), names(networkStudioHits(entries, "  ", max = 8)))
    }

    @Test
    fun `the query is trimmed and case-folded like the catalog's names`() {
        val entries = listOf(network(71, "The CW"))

        assertEquals(
            listOf("The CW"),
            names(networkStudioHits(entries, "  cW  ", max = 8))
        )
    }

    // ── provider-only entries and unopenable ones ─────────────────────────

    @Test
    fun `a provider-only service is offered, keyed by its provider, and an id-less entry never is`() {
        val entries = listOf(
            // The -1 sentinel is normal: Tubi/Pluto have no network id and open
            // through their provider rails.
            network(-1, "Tubi", providerId = 73),
            // No id AND no provider: nothing could open it.
            network(-1, "Nowhere", providerId = null)
        )

        val hits = networkStudioHits(entries, "tu", max = 8)

        assertEquals(listOf("Tubi"), names(hits))
        assertEquals(
            "the provider id is the only identity a -1 entry has",
            "studio:service:provider:73",
            hits.single().key
        )
        assertEquals(
            "an entry neither id can open is skipped",
            emptyList<String>(),
            names(networkStudioHits(entries, "now", max = 8))
        )
    }

    // ── keys carry the kind, because the id spaces collide ────────────────

    @Test
    fun `company and network keys are namespaced, so a shared number is two rows`() {
        assertEquals(
            "studio:company:43",
            StudioSearchHit.Company(company(43, "Starz")).key
        )
        assertEquals(
            "the same number in the other space is a different row",
            "studio:network:43",
            StudioSearchHit.Network(network(43, "National Geographic")).key
        )
        assertTrue(
            "a company row shows its TMDB logo and country; a catalog network has neither",
            StudioSearchHit.Company(
                TmdbSearchStudioResult(1, "A24", "/logo.png", "US")
            ).let { it.logoPath == "/logo.png" && it.originCountry == "US" } &&
                StudioSearchHit.Network(network(71, "The CW"))
                    .let { it.logoPath == null && it.originCountry == null }
        )
    }

    // ── the merge ─────────────────────────────────────────────────────────

    @Test
    fun `catalog hits come first and companies follow, capped at the rail length`() {
        val catalog = listOf(
            StudioSearchHit.Network(network(71, "The CW")),
            StudioSearchHit.Network(network(3186, "HBO Max"))
        )
        val companies = (1..10).map { company(1000 + it, "Studio $it") }

        val merged = mergeStudioHits(catalog, companies, max = 8)

        assertEquals(
            "eight rows: the rail never grows past MAX_STUDIO_RESULTS",
            8,
            merged.size
        )
        assertEquals(
            "the catalog's rows lead, in catalog order, and the companies follow in " +
                "TMDB's order",
            listOf("The CW", "HBO Max", "Studio 1", "Studio 2", "Studio 3", "Studio 4"),
            names(merged).take(6)
        )
        assertTrue(
            "everything in the rail is a hit the screen can render",
            merged.all { it is StudioSearchHit.Network || it is StudioSearchHit.Company }
        )
    }

    @Test
    fun `a company for a brand already on the rail is dropped by name`() {
        // "CW": TMDB's company search and the catalog's network are one brand.
        val merged = mergeStudioHits(
            catalog = listOf(StudioSearchHit.Network(network(71, "The CW"))),
            companies = listOf(company(9999, " the cw "), company(5, "A24")),
            max = 8
        )

        assertEquals(
            "one CW row, from the catalog; the company twin is the duplicate",
            listOf("The CW", "A24"),
            names(merged)
        )
    }

    @Test
    fun `a company whose id the catalog entry names is dropped - originals, and company-space ids only`() {
        val natGeo = StudioSearchHit.Network(
            network(43, "National Geographic", originalsCompanyId = 7521)
        )
        // networkIsCompany = false: networkOrCompanyId is a NETWORK id, so a
        // company row sharing that number is a different entity, not this brand.
        val hbo = StudioSearchHit.Network(
            network(
                3186,
                "HBO Max",
                providerId = 1899,
                networkOrCompanyId = 3186,
                networkIsCompany = false,
                originalsCompanyId = 3268
            )
        )

        val merged = mergeStudioHits(
            catalog = listOf(natGeo, hbo),
            companies = listOf(
                // "Home Box Office (HBO)" - the brand under another name.
                company(3268, "Home Box Office (HBO)"),
                company(7521, "National Geographic Films"),
                company(3186, "Some Network-Space Twin"),
                company(7, "A24")
            ),
            max = 8
        )

        assertEquals(
            "the originals-company ids collapse the two duplicate rows; the network " +
                "id in the other space does not, because it is a different entity",
            listOf(
                "National Geographic",
                "HBO Max",
                "Some Network-Space Twin",
                "A24"
            ),
            names(merged)
        )
    }

    @Test
    fun `a company-space id counts when the entry says its id IS a company`() {
        val entry = StudioSearchHit.Network(
            network(453, "Hulu", networkOrCompanyId = 453, networkIsCompany = true)
        )

        val merged = mergeStudioHits(
            catalog = listOf(entry),
            companies = listOf(company(453, "Hulu LLC")),
            max = 8
        )

        assertEquals("one Hulu row, from the catalog", listOf("Hulu"), names(merged))
    }

    @Test
    fun `duplicate company ids collapse, keeping TMDB's order`() {
        val merged = mergeStudioHits(
            catalog = emptyList(),
            companies = listOf(company(1, "A24"), company(1, "A24"), company(2, "Lionsgate")),
            max = 8
        )

        assertEquals(listOf("A24", "Lionsgate"), names(merged))
    }

    @Test
    fun `no catalog rows leaves the company wave exactly as it was`() {
        val companies = listOf(company(1, "A24"), company(2, "Lionsgate"))

        assertEquals(
            "this is the regression surface: a company-only query is unchanged",
            listOf("A24", "Lionsgate"),
            names(mergeStudioHits(emptyList(), companies, max = 8))
        )
        assertEquals(
            "and an empty cap keeps nothing rather than throwing",
            emptyList<String>(),
            names(mergeStudioHits(emptyList(), companies, max = 0))
        )
    }

    // ── against the real catalog (S1/S2 at the rule level) ─────────────────

    @Test
    fun `the real catalog answers CW with The CW and HBO with the HBO Max service`() {
        val cw = networkStudioHits(BROWSE_PROVIDER_ENTRIES, "CW", max = 8)
        assertTrue(
            "S1: The CW is a catalog network, not something TMDB's company search " +
                "could be relied on for",
            cw.any { it.displayName == "The CW" }
        )
        assertEquals(
            "and it opens the network page (no provider id)",
            "studio:network:71",
            cw.first { it.displayName == "The CW" }.key
        )

        val hbo = networkStudioHits(BROWSE_PROVIDER_ENTRIES, "HBO", max = 8)
        assertTrue(
            "S2: 'HBO' matches the HBO Max SERVICE entry - provider rails plus the " +
                "brand's originals - which is the richer page, not a series-only network",
            hbo.any {
                it.displayName == "HBO Max" &&
                    (it as StudioSearchHit.Network).entry.providerId == 1899
            }
        )
        assertFalse(
            "and no bare 'HBO' network row is offered beside it",
            hbo.any { it.displayName == "HBO" }
        )
    }

    @Test
    fun `the kids list is the authority in kids mode, not the adult one`() {
        assertTrue(
            "S5: a kids-curated network is reachable - the matcher runs whatever list " +
                "the caller publishes, and in kids mode that is the kids curation",
            networkStudioHits(KIDS_PROVIDER_ENTRIES, "Nick Jr.", max = 8)
                .any { it.displayName == "Nick Jr." }
        )
        assertEquals(
            "and an adult-only brand the kids curation does not carry cannot be answered " +
                "from it: The CW is an adult network entry, absent from the kids list",
            emptyList<String>(),
            names(networkStudioHits(KIDS_PROVIDER_ENTRIES, "CW", max = 8))
        )
        assertTrue(
            "the kids list is a CURATED list, not a filtered adult one: it deliberately " +
                "carries family-slate streamers such as HBO Max, so 'HBO' answering HBO Max " +
                "on a kids profile is the catalog's decision, not a leak this slice adds",
            networkStudioHits(KIDS_PROVIDER_ENTRIES, "HBO", max = 8)
                .any { it.displayName == "HBO Max" }
        )
    }
}
