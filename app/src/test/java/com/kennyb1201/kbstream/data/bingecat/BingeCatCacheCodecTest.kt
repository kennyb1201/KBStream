package com.kennyb1201.kbstream.data.bingecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-disk form of the BingeCat cache (spec 2026-11-21, rule 1).
 *
 * Three things have to hold or the cache is worse than no cache: a snapshot must
 * come back exactly as it went in (or the Library quietly loses rows on every
 * launch), a record from an older build must not be read as this build's (or a
 * field written last appears as a truncated row), and a corrupt line must cost
 * only that line.
 */
class BingeCatCacheCodecTest {

    private fun row(title: String, isMovie: Boolean = false, tmdb: Int? = 7, imdb: String? = null) =
        BingeCatRow(title = title, year = 2019, tmdbId = tmdb, imdbId = imdb, isMovie = isMovie)

    @Test
    fun `a snapshot round-trips through the codec`() {
        val snapshot = BingeCatSnapshot(
            watchlist = listOf(row("Dune", isMovie = true), row("Severance", imdb = "tt11280740")),
            saved = listOf(row("Arrival", isMovie = true, tmdb = 329865)),
            lists = listOf(
                BingeCatList(ref = "internal:12", name = "Weekend", itemCount = 4)
            ),
            watched = setOf("series::tt11280740", "movie::tt1160419"),
            refreshedAtMs = 1_700_000_000_000L,
            profileId = "profile-2",
            mappedId = 88L,
            listsComplete = false
        )

        val decoded = BingeCatCacheCodec.decodeSnapshot(
            BingeCatCacheCodec.encodeSnapshot("abc123", snapshot)
        )

        assertEquals("abc123", decoded?.tokenFingerprint)
        assertEquals(snapshot, decoded?.snapshot)
    }

    @Test
    fun `a title with a tab or a newline survives`() {
        // Escaping is not hypothetical: a title with a colon is common, and the
        // dashboard's own entries can carry anything the add-on sent.
        val tricky = row("Mission: Impossible\tPart\nOne")
        val snapshot = BingeCatSnapshot(
            watchlist = listOf(tricky),
            saved = emptyList(),
            lists = emptyList(),
            watched = emptySet(),
            refreshedAtMs = 1L,
            profileId = null,
            mappedId = 3L
        )

        val decoded = BingeCatCacheCodec.decodeSnapshot(
            BingeCatCacheCodec.encodeSnapshot("fp", snapshot)
        )

        assertEquals("Mission: Impossible\tPart\nOne", decoded?.snapshot?.watchlist?.first()?.title)
    }

    @Test
    fun `a record from another build is refused, not half-read`() {
        assertNull(
            "a header this build does not know must be discarded whole - reading it " +
                "as a snapshot would paint rows the format never promised",
            BingeCatCacheCodec.decodeSnapshot("bcs0\tabc\t1\tp\t0\t1\nW\tDune\t\t7\t\t1")
        )
        assertNull(BingeCatCacheCodec.decodeSnapshot(""))
        assertNull(BingeCatCacheCodec.decodeSnapshot("garbage"))
    }

    @Test
    fun `one corrupt row costs only that row`() {
        val record = buildString {
            append("bcs1\tfp\t5\tprofile-1\t100\t1")
            append("\nW\tGood\t2020\t11\t\t0")
            append("\nW\tNo ids at all\t2020\t\t\t0")
            append("\nX\tunknown line kind\t\t\t\t0")
            append("\nL\tinternal:1\tMine\t2")
        }

        val decoded = BingeCatCacheCodec.decodeSnapshot(record)

        assertEquals(listOf("Good"), decoded?.snapshot?.watchlist?.map { it.title })
        assertEquals(1, decoded?.snapshot?.lists?.size)
    }

    @Test
    fun `a list's rows round-trip with their stamp and ref`() {
        val rows = listOf(row("Dune", isMovie = true), row("Andor"))
        val decoded = BingeCatCacheCodec.decodeRows(
            BingeCatCacheCodec.encodeRows("fp", "internal:9", 4242L, rows)
        )

        assertEquals("fp", decoded?.tokenFingerprint)
        assertEquals("internal:9", decoded?.ref)
        assertEquals(4242L, decoded?.savedAtMs)
        assertEquals(rows, decoded?.rows)
    }

    @Test
    fun `an empty list is a stored walk, not a missing one`() {
        // A list can genuinely hold nothing. Its record still exists, with its
        // stamp, so the rail can tell "walked, and empty" from "never walked".
        val decoded = BingeCatCacheCodec.decodeRows(
            BingeCatCacheCodec.encodeRows("fp", "internal:2", 9L, emptyList())
        )

        assertTrue(decoded != null)
        assertEquals(emptyList<BingeCatRow>(), decoded?.rows)
    }
}
