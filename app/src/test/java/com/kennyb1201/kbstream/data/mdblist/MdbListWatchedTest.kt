package com.kennyb1201.kbstream.data.mdblist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MDBList snapshot's flat `"<show>:<season>:<episode>"` key set, indexed by
 * show so one show's watched episodes can be read without scanning the whole
 * account per poster.
 *
 * This is the MDBList half of tracker parity: before it, only Simkl's watched
 * history reached the shared watched state, so a viewer running MDBList but no
 * Simkl had their tracker ignored across Home. The index is the piece that has
 * to get the key shapes and the id forms right, so the cases below pin both.
 */
class MdbListWatchedTest {

    // ── the index ───────────────────────────────────────────────────────

    @Test
    fun `imdb and tmdb keys are filed under their own show`() {
        val index =
            mdbListWatchedEpisodesByShow(
                setOf(
                    "tt1234567:1:1",
                    "tt1234567:1:2",
                    "tmdb:456:2:5"
                )
            )

        assertEquals(
            setOf(1 to 1, 1 to 2),
            index["tt1234567"]
        )
        assertEquals(
            setOf(2 to 5),
            index["tmdb:456"]
        )
    }

    @Test
    fun `a show key with its own colons keeps them`() {
        // Add-on ids carry a colon of their own ("kitsu:123"), so the season
        // and episode are taken from the END rather than the first two parts.
        val index = mdbListWatchedEpisodesByShow(setOf("kitsu:123:3:7"))

        assertEquals(setOf(3 to 7), index["kitsu:123"])
    }

    @Test
    fun `malformed keys prove nothing and are dropped`() {
        val index =
            mdbListWatchedEpisodesByShow(
                setOf(
                    "tt1234567:1:1",
                    "tt1234567", // no season/episode at all
                    "tt1234567:1", // only one number
                    "tt1234567:x:2", // non-numeric season
                    "tt1234567:1:y", // non-numeric episode
                    "tt1234567:0:5", // season 0 is the sources' "no season"
                    "tt1234567:2:0", // episode 0 is the sources' "no episode"
                    ":1:1", // no show
                    "  :1:1"
                )
            )

        assertEquals(setOf(1 to 1), index["tt1234567"])
        assertTrue("no other show should be filed", index.size == 1)
    }

    @Test
    fun `an empty snapshot indexes to nothing`() {
        assertTrue(mdbListWatchedEpisodesByShow(emptySet()).isEmpty())
    }

    // ── the per-show read ───────────────────────────────────────────────

    @Test
    fun `both id forms are unioned, and the duplicate episode collapses`() {
        val index =
            mdbListWatchedEpisodesByShow(
                setOf(
                    // The same episode the tracker knows under both ids.
                    "tt1234567:2:5",
                    "tmdb:456:2:5",
                    // ...plus an episode it only knows under one.
                    "tt1234567:2:6"
                )
            )

        assertEquals(
            setOf(2 to 5, 2 to 6),
            index.watchedEpisodesFor(listOf("tt1234567", "tmdb:456"))
        )
    }

    @Test
    fun `a show key cannot swallow a longer id that starts with it`() {
        // "tt123" is a prefix of "tt1234": an index keyed on anything looser
        // than the whole show key would hand tt1234's episodes to tt123.
        val index =
            mdbListWatchedEpisodesByShow(
                setOf(
                    "tt123:1:1",
                    "tt1234:9:9"
                )
            )

        assertEquals(
            setOf(1 to 1),
            index.watchedEpisodesFor(listOf("tt123"))
        )
        assertEquals(
            setOf(9 to 9),
            index.watchedEpisodesFor(listOf("tt1234"))
        )
    }

    @Test
    fun `an unknown show reads as nothing watched`() {
        val index = mdbListWatchedEpisodesByShow(setOf("tt1234567:1:1"))

        assertTrue(index.watchedEpisodesFor(listOf("tt9999999")).isEmpty())
        assertTrue(index.watchedEpisodesFor(emptyList()).isEmpty())
        assertTrue(emptyMap<String, Set<Pair<Int, Int>>>().watchedEpisodesFor(listOf("tt1234567")).isEmpty())
    }

    @Test
    fun `blank lookup keys are ignored rather than matching a blank show`() {
        val index =
            mdbListWatchedEpisodesByShow(
                setOf("tt1234567:1:1")
            )

        assertEquals(
            setOf(1 to 1),
            index.watchedEpisodesFor(listOf("  ", "tt1234567", ""))
        )
    }
}
