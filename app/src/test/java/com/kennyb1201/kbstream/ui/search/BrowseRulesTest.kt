package com.kennyb1201.kbstream.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse browser's filter, counts and blurbs.
 *
 * These are the parts of the rebuild a viewer feels immediately: whether the
 * filter finds a chip (279 collections is not a list anyone scrolls), whether
 * "1 genre" reads correctly, and whether a tab explains where it goes. They
 * are also the parts that fail silently - a filter that drops a real match
 * still renders, it just never shows you what you asked for - which is why
 * they live in a pure file and are pinned here.
 */
class BrowseRulesTest {

    private fun entry(id: Int, name: String) = BrowseEntry(id, name)

    private val sample = listOf(
        entry(1, "Netflix"),
        entry(2, "Disney+"),
        entry(3, "Neon"),
        entry(4, "Netflix Originals"),
        entry(5, "A24")
    )

    @Test
    fun `a blank query keeps every entry`() {
        assertEquals(sample, filterBrowseEntries(sample, ""))
        assertEquals(sample, filterBrowseEntries(sample, "   "))
    }

    @Test
    fun `the filter is case-insensitive and matches anywhere in the name`() {
        assertEquals(
            listOf(entry(1, "Netflix"), entry(4, "Netflix Originals")),
            filterBrowseEntries(sample, "netF")
        )
        // Substring, not prefix: "on" is inside "Disney+"? no - inside
        // nothing here; but "flix" is inside both Netflix chips.
        assertEquals(
            listOf(entry(1, "Netflix"), entry(4, "Netflix Originals")),
            filterBrowseEntries(sample, "flix")
        )
    }

    @Test
    fun `the filter preserves the curated order`() {
        // Netflix (1) was curated before Netflix Originals (4); a relevance
        // re-rank would have to decide which is "better", and moving a chip
        // the viewer was about to press is worse than a stable order.
        val matched = filterBrowseEntries(sample, "net")
        assertEquals(listOf(1, 4), matched.map { it.id })
    }

    @Test
    fun `a query nothing matches keeps nothing`() {
        assertTrue(filterBrowseEntries(sample, "zzz").isEmpty())
    }

    @Test
    fun `a count reads in the category's own noun, singular or plural`() {
        assertEquals("26 genres", browseCountLabel(26, 26, "genres"))
        assertEquals("1 genre", browseCountLabel(1, 1, "genres"))
        assertEquals("279 collections", browseCountLabel(279, 279, "collections"))
        assertEquals("1 collection", browseCountLabel(1, 1, "collections"))
        assertEquals("91 services", browseCountLabel(91, 91, "services"))
        assertEquals("12 decades", browseCountLabel(12, 12, "decades"))
    }

    @Test
    fun `a filtered count says how much of the category is showing`() {
        assertEquals("3 of 279 collections", browseCountLabel(3, 279, "collections"))
        assertEquals("1 of 26 genres", browseCountLabel(1, 26, "genres"))
    }

    @Test
    fun `a category with no listed noun counts in entries`() {
        assertEquals("7 entries", browseCountLabel(7, 7, "something-new"))
        assertEquals("1 entry", browseCountLabel(1, 1, "something-new"))
        assertEquals("entry", browseCategoryNoun("something-new"))
        assertEquals("entries", browseCategoryNounPlural("something-new"))
    }

    @Test
    fun `every browse category has a noun and a blurb`() {
        val keys = BROWSE_CATEGORIES.map { it.key }
        assertTrue(keys.isNotEmpty())
        val blurbs = keys.map { key ->
            assertNotEquals("entry", browseCategoryNoun(key))
            browseCategoryBlurb(key).also { blurb ->
                assertTrue("blurb for $key is blank", blurb.isNotBlank())
            }
        }
        // One tab must not describe a different tab's screen.
        assertEquals(blurbs.size, blurbs.toSet().size)
    }
}
