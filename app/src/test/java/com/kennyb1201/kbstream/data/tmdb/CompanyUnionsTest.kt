package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A studio chip discovers by ONE TMDB company id, but a rebrand splits the
 * brand across several entities: DC's 9993 has only the animated films, the
 * live-action ones are 128064 and the post-2022 ones 184898. The union map is
 * what puts them back on one screen, so these cases pin the exact strings TMDB
 * is asked for — a dropped id silently hides a chunk of the catalog again.
 */
class CompanyUnionsTest {

    @Test
    fun `the DC chip fetches every entity the brand has released under`() {
        assertEquals(
            "primary 9993 first, then DC Films, DC Studios and DC Comics",
            "9993|128064|184898|429",
            resolveCompanyUnion(9993)
        )
    }

    @Test
    fun `20th Century Fox widens to the Disney-era studio`() {
        // 25 ends at The Empty Man (Oct 2020); Avatar: The Way of Water,
        // Deadpool & Wolverine and Alien: Romulus are all 127928.
        assertEquals("25|127928", resolveCompanyUnion(25))
    }

    @Test
    fun `MGM widens to Amazon MGM Studios`() {
        // 21 is frozen around 2023; the active successor is 210099.
        assertEquals("21|210099", resolveCompanyUnion(21))
    }

    @Test
    fun `an id TMDB has not split passes through alone`() {
        // A24 — the union must be a no-op, not an empty filter that would
        // return the whole of TMDB.
        assertEquals("41077", resolveCompanyUnion(41077))
    }

    @Test
    fun `every union stays a well-formed OR clause with its primary first`() {
        assertTrue("the map must not be empty", COMPANY_ID_UNIONS.isNotEmpty())
        COMPANY_ID_UNIONS.forEach { (primary, union) ->
            assertTrue("$primary has an empty union", union.isNotEmpty())
            assertEquals("$primary must stay first", primary, union.first())
            assertEquals("$primary repeats an id", union.size, union.distinct().size)
            assertTrue("$primary has a non-positive id", union.all { it > 0 })
        }
    }

    @Test
    fun `the joined value is integers separated by pipes`() {
        // Unioned and non-unioned alike: both shapes have to parse as a
        // with_companies OR clause, or TMDB returns an error/empty page.
        (COMPANY_ID_UNIONS.keys + 41077).forEach { primary ->
            val joined = resolveCompanyUnion(primary)
            assertTrue(
                "$joined is not a with_companies OR clause",
                joined.split("|").all { it.toIntOrNull() != null }
            )
        }
    }
}
