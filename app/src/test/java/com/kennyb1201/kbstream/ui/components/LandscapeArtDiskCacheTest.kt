package com.kennyb1201.kbstream.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The landscape-card memo's disk layer, pinned without a device.
 *
 * Field report 2026-10-29: on a cold start the browse screens' cards took a
 * visibly long time to fill in, and revisits in the same session were fast - the
 * memo in `GlobalLandscapeArtCache` is session-only, so every cold start
 * re-resolved every card's TMDB art. The disk layer answers that, and what must
 * not drift is exactly what a JVM test can hold: which requests share one row,
 * how long each kind of answer may stand, and that the row survives the round
 * trip in every shape the resolver can produce.
 */
class LandscapeArtDiskCacheTest {

    private val day = 24L * 60L * 60L * 1000L

    private fun request(
        id: String = "tt0111161",
        type: String = "movie",
        addonBackdrop: String? = "https://addon.example/backdrop.jpg",
        tmdbOnly: Boolean = false
    ) = LandscapeArtRequest(
        id = id,
        type = type,
        addonBackdrop = addonBackdrop,
        tmdbOnly = tmdbOnly
    )

    private fun entry(backdrop: String? = null, logo: String? = null) =
        LandscapeArtDiskEntry(backdrop = backdrop, logo = logo)

    // ── keying ──────────────────────────────────────────────────────────────

    @Test
    fun `the key is the versioned prefix plus the card's own key`() {
        val request = request()
        assertEquals(
            "one spelling of the row key, built from the card key",
            LANDSCAPE_ART_DISK_PREFIX + request.key,
            landscapeArtDiskKey(request.key)
        )
        assertEquals("landscape_art_v1:", LANDSCAPE_ART_DISK_PREFIX)
    }

    @Test
    fun `merge-time inputs never split an entry`() {
        // The add-on backdrop and the `tmdbOnly` flag are INPUTS TO THE MERGE,
        // not to the lookup: they are applied when the row is read, so one row
        // serves every surface showing the title. If they were in the key, a
        // card on a pinned rail and the same card on a browse row would resolve
        // - and store - the same TMDB pair twice.
        val plain = landscapeArtDiskKey(request().key)
        assertEquals(
            "a changed add-on backdrop is a merge-time input",
            plain,
            landscapeArtDiskKey(request(addonBackdrop = null).key)
        )
        assertEquals(
            "so is tmdbOnly",
            plain,
            landscapeArtDiskKey(request(tmdbOnly = true).key)
        )
    }

    @Test
    fun `a different title or type is a different row`() {
        assertTrue(
            "a film and a show with the same id are different lookups",
            landscapeArtDiskKey(request(type = "movie").key) !=
                landscapeArtDiskKey(request(type = "tv").key)
        )
        assertTrue(
            "and so are two titles",
            landscapeArtDiskKey(request(id = "tt0111161").key) !=
                landscapeArtDiskKey(request(id = "tt0068646").key)
        )
    }

    // ── TTLs ────────────────────────────────────────────────────────────────

    @Test
    fun `an answer with artwork is trusted for a month`() {
        val art = entry(backdrop = "https://image.tmdb.org/t/p/w780/b.jpg", logo = null)
        assertEquals("artwork's TTL", 30 * day, landscapeArtDiskTtlMs(art))
        assertEquals(
            "a logo-only answer is still artwork",
            30 * day,
            landscapeArtDiskTtlMs(entry(logo = "https://image.tmdb.org/t/p/w500/l.png"))
        )
        assertTrue("29 days old is still served", landscapeArtDiskEntryIsFresh(29 * day, art))
        assertFalse("31 days old is re-asked", landscapeArtDiskEntryIsFresh(31 * day, art))
    }

    @Test
    fun `a both-null answer is trusted for a day`() {
        assertEquals("the miss TTL", day, LANDSCAPE_ART_MISS_TTL_MS)
        assertEquals("a both-null entry takes it", day, landscapeArtDiskTtlMs(entry()))
        assertTrue(
            "23 hours old is still served, so a visit does not re-walk",
            landscapeArtDiskEntryIsFresh(23 * 60L * 60L * 1000L, entry())
        )
        assertFalse(
            "25 hours old is re-asked, so artwork uploaded later appears",
            landscapeArtDiskEntryIsFresh(25 * 60L * 60L * 1000L, entry())
        )
    }

    @Test
    fun `a clock that went backwards is never fresh`() {
        assertFalse(
            "a negative age is not evidence of freshness",
            landscapeArtDiskEntryIsFresh(-1, entry(backdrop = "b"))
        )
    }

    // ── codec ───────────────────────────────────────────────────────────────

    @Test
    fun `the codec round-trips every shape the resolver can answer`() {
        val shapes = listOf(
            "both null (TMDB had nothing)" to entry(),
            "backdrop only" to entry(backdrop = "https://image.tmdb.org/t/p/w780/b.jpg"),
            "logo only" to entry(logo = "https://image.tmdb.org/t/p/w500/l.png"),
            "both" to entry(
                backdrop = "https://image.tmdb.org/t/p/w780/b.jpg",
                logo = "https://image.tmdb.org/t/p/w500/l.png"
            )
        )
        shapes.forEach { (what, expected) ->
            val decoded = decodeLandscapeArtDiskEntry(encodeLandscapeArtDiskEntry(expected))
            assertEquals("$what must survive the round trip", expected, decoded)
            assertEquals("and must merge back to the same pair", expected.toPair(), decoded?.toPair())
        }
    }

    @Test
    fun `a corrupt row reads as a miss, never as a crash`() {
        listOf(
            "not json at all",
            "{\"backdrop\":",
            "{]",
            "{\"backdrop\":{\"nested\":1}}"
        ).forEach { json ->
            assertNull("\"$json\" must read as a miss", decodeLandscapeArtDiskEntry(json))
        }
    }

    @Test
    fun `a row that lost a half decodes it as missing`() {
        // An older writer, or a generation that never wrote both keys: the half
        // that is there still answers, the other merges from the request.
        assertEquals(
            "a row with no logo key",
            entry(backdrop = "b"),
            decodeLandscapeArtDiskEntry("{\"backdrop\":\"b\"}")
        )
        assertEquals(
            "a row with no keys at all is a both-null answer, not a miss on read",
            entry(),
            decodeLandscapeArtDiskEntry("{}")
        )
    }
}
