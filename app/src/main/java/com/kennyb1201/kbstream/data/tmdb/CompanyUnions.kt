package com.kennyb1201.kbstream.data.tmdb

/**
 * Brands TMDB splits across several company entities (rebrands,
 * reorganizations), keyed by the browse chip's primary company id.
 *
 * TMDB creates a NEW company entity when a studio rebrands, so a studio screen
 * that discovers by one id shows a catalog that stops where the rebrand
 * happened: DC's chip (9993, "DC Entertainment") carries only the animated
 * films, while every live-action title since 2016 sits under 128064 and every
 * title since the 2022 DC Studios rebrand under 184898. 20th Century Fox's 25
 * tops out in 2020; everything since the Disney rebrand is 127928. MGM's 21 is
 * frozen around 2023; the active successor is 210099.
 *
 * The value is the full union with the primary FIRST, and `with_companies`
 * takes the pipe-joined ids with OR semantics. The primary stays first because
 * the header, logo and detail still use the primary id alone — the brand's
 * identity does not change, only the rails widen.
 *
 * Grown from the 2026-10-18 TMDB audit like the catalog's own re-verified id
 * comments ([com.kennyb1201.kbstream.ui.search.BROWSE_STUDIOS]): an entry earns
 * its place when a company's page provably ends while the brand keeps
 * releasing films elsewhere. The audit covered 26 studios and 3 networks and
 * found no other splits, so this map is deliberately short.
 *
 * Data layer on purpose: the repository must not import the ui.search catalog
 * that owns the chips.
 */
internal val COMPANY_ID_UNIONS: Map<Int, List<Int>> = mapOf(
    // DC: 9993 = DC Entertainment (animation only, ends 2019),
    // 128064 = DC Films (all live-action 2016–2023), 184898 = DC Studios
    // (2022 rebrand; Superman 2025+), 429 = DC Comics (older + animated
    // titles 9993 lacks, e.g. Batman Begins, 2025–26 animation).
    9993 to listOf(9993, 128064, 184898, 429),
    // 20th Century Fox → 20th Century Studios (Disney rebrand, 2020).
    25 to listOf(25, 127928),
    // MGM → Amazon MGM Studios (2022 acquisition); 21 is frozen ~2023.
    21 to listOf(21, 210099),
)

/**
 * Pipe-joined union for `with_companies`; an unknown id passes through alone.
 *
 * Joining happens here rather than at each call site so the cache key and the
 * request cannot disagree: a unioned page must never be filed under the
 * primary id, or it would collide with a single-id page.
 */
internal fun resolveCompanyUnion(companyId: Int): String =
    (COMPANY_ID_UNIONS[companyId] ?: listOf(companyId)).joinToString("|")
