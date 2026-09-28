package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The poster a title saved from a "Top Today" rail shows in the library.
 *
 * Reported problem: saving one from that rail put the poster in the library
 * with the rank number and the "JUST ADDED" strip still burned into it — both
 * are part of the add-on's ranked/tagged image, not UI the app draws, so they
 * travelled with the URL the library stored.
 */
class LibraryPosterTest {

    @Test
    fun `a ranked tagged poster loses both promo parameters`() {
        assertEquals(
            "https://toptoday.llamayu.com/poster/1101383.png?" +
                "type=movie&tag=none&rank=none&lang=en&logos=0",
            cleanLibraryPoster(
                "https://toptoday.llamayu.com/poster/1101383.png?" +
                    "type=movie&tag=just_added&rank=5&lang=en&logos=0"
            )
        )
    }

    @Test
    fun `every promo tag is dropped, not just the first`() {
        for (tag in listOf("just_added", "now_streaming", "out_on_bluray")) {
            assertEquals(
                "https://toptoday.llamayu.com/poster/1492640.png?" +
                    "type=movie&tag=none&rank=none&lang=en",
                cleanLibraryPoster(
                    "https://toptoday.llamayu.com/poster/1492640.png?" +
                        "type=movie&tag=$tag&rank=1&lang=en"
                )
            )
        }
    }

    @Test
    fun `an already-plain poster is returned unchanged`() {
        val clean =
            "https://toptoday.llamayu.com/poster/1339713.png?" +
                "type=movie&tag=none&rank=none&lang=en&logos=0"
        assertEquals(clean, cleanLibraryPoster(clean))
    }

    @Test
    fun `a poster with no promo parameters is left completely alone`() {
        val tmdb = "https://image.tmdb.org/t/p/w500/abc123.jpg"
        assertEquals(tmdb, cleanLibraryPoster(tmdb))

        // `tag` alone is not this convention: without a rank it means whatever
        // the add-on that sent it meant, so it is not rewritten.
        val other = "https://addon.example/poster.png?tag=documentary&lang=en"
        assertEquals(other, cleanLibraryPoster(other))

        assertEquals(
            "https://toptoday.llamayu.com/backdrop/1492640.png?type=movie&logos=1",
            cleanLibraryPoster(
                "https://toptoday.llamayu.com/backdrop/1492640.png?type=movie&logos=1"
            )
        )
    }

    @Test
    fun `an absent poster stays absent`() {
        assertNull(cleanLibraryPoster(null))
        assertNull(cleanLibraryPoster(""))
        assertNull(cleanLibraryPoster("   "))
    }

    @Test
    fun `the rest of the URL survives the rewrite`() {
        // A fragment (and the path, host and every other parameter) is not the
        // rewrite's business.
        assertEquals(
            "https://cdn.example/p.jpg?w=500&rank=none&tag=none#frag",
            cleanLibraryPoster("https://cdn.example/p.jpg?w=500&rank=2&tag=just_added#frag")
        )
    }

    @Test
    fun `a round trip through the store's JSON keeps the plain poster`() {
        // The value the store writes is what the store reads back, so a cleaned
        // poster never picks the badges up again on the way through.
        val stored = cleanLibraryPoster(
            "https://toptoday.llamayu.com/poster/1084244.png?" +
                "type=movie&tag=out_on_bluray&rank=6&lang=en&logos=0"
        )
        assertEquals(stored, cleanLibraryPoster(stored))
        assertEquals(
            "https://toptoday.llamayu.com/poster/1084244.png?" +
                "type=movie&tag=none&rank=none&lang=en&logos=0",
            stored
        )
    }
}
