package com.kennyb1201.kbstream.data.publiclists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules half of the public-lists surface (spec F2-F5), the only half a JVM
 * test can hold: the rest needs a token, a network and Compose.
 *
 * Three of these are rules the spec states in words and nothing else enforces:
 *
 *  - a blank query must be DISABLED, not fired (F3) - and on MDBList each fired
 *    query burns the day's allowance;
 *  - every card and detail must name the vendor and, for a community list, its
 *    owner (F5) - a public list drawn without its owner reads as KBStream's own
 *    recommendation, which the spec forbids outright;
 *  - a spent MDBList allowance is its own outcome, neither an empty directory
 *    nor a retryable failure (F2, F4).
 */
class PublicListsRulesTest {

    private fun card(
        vendor: PublicListVendor = PublicListVendor.TRAKT,
        owner: String? = "kenny",
        official: Boolean = false
    ) = PublicListCard(
        vendor = vendor,
        ref = "the-list",
        name = "The List",
        owner = owner,
        description = null,
        itemCount = 24,
        likes = 118,
        official = official
    )

    @Test
    fun `a blank or single-character query is disabled`() {
        assertFalse(PublicListRules.searchEnabled(""))
        assertFalse(PublicListRules.searchEnabled("   "))
        assertFalse(PublicListRules.searchEnabled(" a "))
        assertTrue(PublicListRules.searchEnabled("ab"))
        assertTrue(PublicListRules.searchEnabled("  best of 90s  "))
    }

    @Test
    fun `every card names its vendor and its owner`() {
        assertEquals(
            "Trakt community list · by kenny",
            PublicListRules.attribution(card())
        )
        assertEquals(
            "MDBList community list · by kenny",
            PublicListRules.attribution(card(vendor = PublicListVendor.MDBLIST))
        )
    }

    @Test
    fun `an owner-less card still names its vendor, and never KBStream`() {
        listOf(null, "", "   ").forEach { owner ->
            val text = PublicListRules.attribution(card(owner = owner))
            assertFalse(
                "a community list must never be attributed to this app: $text",
                text.contains("KBStream")
            )
            assertTrue(
                "a community list must name its vendor even without an owner: $text",
                text.contains("Trakt community list")
            )
        }
    }

    @Test
    fun `an official list says it is the provider's own`() {
        assertEquals(
            "MDBList official list",
            PublicListRules.attribution(card(vendor = PublicListVendor.MDBLIST, official = true))
        )
    }

    @Test
    fun `the counts line leaves out what the service did not send`() {
        assertEquals("24 titles · 118 likes", PublicListRules.countsLabel(24, 118))
        assertEquals("1 title · 1 like", PublicListRules.countsLabel(1, 1))
        assertEquals(
            "24 titles",
            PublicListRules.countsLabel(24, null)
        )
        assertEquals(
            "24 titles",
            PublicListRules.countsLabel(24, 0)
        )
        assertEquals("No titles yet", PublicListRules.countsLabel(0, null))
    }

    @Test
    fun `the spent allowance is its own sentence, not a failure code`() {
        val text = PublicListRules.allowanceText(PublicListVendor.MDBLIST)
        assertTrue(text.contains("MDBList"))
        assertTrue(
            "the allowance sentence has to say what stays on screen, which is what " +
                "keeps a spent day from looking like an empty directory: $text",
            text.contains("stays on screen")
        )
        assertFalse(
            "a spent allowance is not a failure and must not offer a retry: $text",
            text.contains("try again", ignoreCase = true)
        )
    }

    @Test
    fun `the liked-list cap names a fix the viewer can make`() {
        val text = PublicListRules.likedLimitText(PublicListVendor.MDBLIST)
        assertTrue(
            "MDBList's cap is a tier limit, so the sentence must name the action " +
                "that clears it rather than reading as a broken account: $text",
            text.contains("limit") && text.lowercase().contains("unlike")
        )
    }

    @Test
    fun `save wording keeps a second save out of the error voice`() {
        assertEquals(
            "Saved to your Trakt lists",
            PublicListRules.savedText(PublicListVendor.TRAKT)
        )
        assertEquals("Already in your lists", PublicListRules.ALREADY_SAVED)
        assertFalse(
            "re-saving is a no-op, not a failure, so its sentence must not blame " +
                "anything: ${PublicListRules.ALREADY_SAVED}",
            PublicListRules.ALREADY_SAVED.contains("Couldn't", ignoreCase = true)
        )
    }

    @Test
    fun `failure codes are turned into sentences per vendor`() {
        listOf(PublicListVendor.TRAKT, PublicListVendor.MDBLIST).forEach { vendor ->
            assertEquals(
                "Reconnect ${vendor.label} to continue: this session was not accepted.",
                PublicListRules.failureText(vendor, "401")
            )
            assertEquals(
                "${vendor.label} is rate limiting KBStream. Try again in a minute.",
                PublicListRules.failureText(vendor, "429")
            )
            listOf("", "500").forEach { reason ->
                val text = PublicListRules.failureText(vendor, reason)
                assertTrue(
                    "a failure sentence must name the vendor it was about: $text",
                    text.contains(vendor.label)
                )
            }
        }
    }

    @Test
    fun `an empty answer is an empty state, not an error`() {
        assertTrue(
            PublicListRules.emptyDirectoryText(PublicListVendor.TRAKT).contains("Trakt")
        )
        assertFalse(
            "an empty directory is a real answer: " + PublicListRules.NO_SEARCH_RESULTS,
            PublicListRules.NO_SEARCH_RESULTS.contains("Couldn't", ignoreCase = true)
        )
    }

    @Test
    fun `the ceiling note is the rule, in words the viewer reads`() {
        assertTrue(PublicListRules.CEILING_NOTE.contains("ceiling"))
        assertFalse(
            "the browse surface does not curate, so the note must not promise it does",
            PublicListRules.CEILING_NOTE.contains("curated", ignoreCase = true)
        )
    }

    @Test
    fun `a card is keyed by its vendor and its own ref`() {
        val trakt = card()
        val mdblist = card(vendor = PublicListVendor.MDBLIST)
        assertEquals("trakt:the-list", trakt.key)
        assertEquals("mdblist:the-list", mdblist.key)
        assertTrue(
            "two vendors' lists must not share a key, or a save's phase lands on the " +
                "wrong card",
            trakt.key != mdblist.key
        )
    }

    @Test
    fun `each vendor browses only its own directories, in the spec's order`() {
        assertEquals(
            listOf(PublicListDirectory.TRAKT_POPULAR, PublicListDirectory.TRAKT_TRENDING),
            PublicListDirectory.forVendor(PublicListVendor.TRAKT)
        )
        assertEquals(
            listOf(
                PublicListDirectory.MDBLIST_TOP,
                PublicListDirectory.MDBLIST_CURATED,
                PublicListDirectory.MDBLIST_OFFICIAL
            ),
            PublicListDirectory.forVendor(PublicListVendor.MDBLIST)
        )
    }

    @Test
    fun `a route's vendor string round-trips and unknown ones do not resolve`() {
        listOf(PublicListVendor.TRAKT, PublicListVendor.MDBLIST).forEach { vendor ->
            assertEquals(vendor, PublicListVendor.fromWire(vendor.wire))
        }
        assertEquals(null, PublicListVendor.fromWire("simkl"))
        assertEquals(null, PublicListVendor.fromWire(null))
    }

    @Test
    fun `a title's detail id and ceiling key follow the app's own rules`() {
        val titled = PublicListTitle(
            tmdbId = 603,
            imdbId = "tt0133093",
            isMovie = true,
            title = "The Matrix",
            year = 1999
        )
        assertEquals("tt0133093", titled.detailId)
        assertEquals(603 to "movie", titled.ceilingKey)

        val show = titled.copy(imdbId = " ", isMovie = false)
        assertEquals(
            "with no IMDb id the typed TMDB form is the id Detail takes",
            "tmdb:603",
            show.detailId
        )
        assertEquals(603 to "series", show.ceilingKey)

        assertEquals(null, show.copy(tmdbId = null).detailId)
    }
}
