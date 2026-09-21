package com.kennyb1201.kbstream.ui.search

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
 */
class SearchBrowseCatalogKidsTest {

    private fun names(entries: List<BrowseEntry>) = entries.map { it.name.trim() }

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
}
