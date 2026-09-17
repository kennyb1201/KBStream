package com.kennyb1201.kbstream.ui.search

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the Search browse catalog that the app relies on at
 * runtime:
 *
 *  1. Every kids chip list is a strict subset of the standard list.
 *     SearchViewModel caches keyword/collection ids keyed by NAME and
 *     shares that cache across profile switches; if a kids-only name
 *     ever appears that the standard list lacks, the standard mode
 *     would never resolve its id and the chip would silently open an
 *     empty rail.
 *
 *  2. Kids categories expose only kid-focused entries: no adult-only
 *     networks/studios slip in, and every collection is a franchise a
 *     parent would hand to a kid.
 *
 *  3. Duplicate names inside one list would double-resolve ids and
 *     duplicate chips on screen.
 */
class SearchBrowseCatalogKidsTest {

    private fun names(entries: List<BrowseEntry>) = entries.map { it.name.trim() }

    // ── subset invariants (the SearchViewModel cache contract) ──────────

    @Test
    fun `kids keyword names are a subset of standard keyword names`() {
        val standard = BROWSE_KEYWORD_NAMES.map { it.trim() }.toSet()
        val kids = KIDS_KEYWORD_NAMES.map { it.trim() }.toSet()
        val missing = kids - standard
        assertTrue(
            "kids keywords missing from BROWSE_KEYWORD_NAMES: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `kids collection names are a subset of standard collection names`() {
        val standard = BROWSE_COLLECTION_NAMES.map { it.trim() }.toSet()
        val kids = KIDS_COLLECTION_NAMES.map { it.trim() }.toSet()
        val missing = kids - standard
        assertTrue(
            "kids collections missing from BROWSE_COLLECTION_NAMES: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `kids services carry a usable discover id and no duplicates`() {
        // Kids services mix streaming services (by provider+network id) and
        // kid NETWORKS as page targets (Cartoon Network, PBS Kids...), so
        // unlike keywords/collections they are NOT a subset of the standard
        // streaming-services list. The real invariants: every entry can
        // drive a discover page and no brand appears twice.
        val unusable = KIDS_SERVICES.filter {
            it.providerId == null && it.networkOrCompanyId == null
        }
        assertTrue(
            "kids services with no provider/network id: ${unusable.map { it.name }}",
            unusable.isEmpty()
        )

        val dupes = KIDS_SERVICES.groupingBy { it.name.trim() }.eachCount()
            .filterValues { it > 1 }.keys
        assertTrue("duplicate kids services: $dupes", dupes.isEmpty())
    }

    // ── hygiene: no duplicates, no blank names ──────────────────────────

    @Test
    fun `no duplicate names inside any chip list`() {
        for ((label, list) in mapOf(
            "KIDS_KEYWORD_NAMES" to KIDS_KEYWORD_NAMES,
            "KIDS_COLLECTION_NAMES" to KIDS_COLLECTION_NAMES,
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
            "KIDS_COLLECTION_NAMES" to KIDS_COLLECTION_NAMES
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
        val offenders = KIDS_SERVICES.filter { it.networkOrCompanyId in banned }
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
