package com.kennyb1201.kbstream.ui.components

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbImageAsset
import com.kennyb1201.kbstream.data.tmdb.TmdbImagesResponse
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The three answers every landscape card depends on, and the one thing they
 * cannot fail loudly at: the string the artwork is filed under.
 *
 * The writer and the reader live in different files - the rail builders inside
 * `HomeViewModel`, the card in `HomeScreen`, the folder screens in `ui/kb` - and
 * are never compiled against each other, so a drifted format produces no error
 * at all: cards quietly fall back to their add-on backdrop, and every rebuild
 * re-resolves artwork the app had already resolved. These pin the shape rather
 * than the mechanism; if it ever has to change, the failure is the point.
 *
 * The rank a pinned "Top ... Today" row shows is pinned the same way, for the
 * same reason: the card that draws the number and the rail that declares itself
 * a ranking are in different files, and a number on a row that is not a standing
 * is a lie about the row.
 */
class LandscapeArtTest {

    private val blank = null to null

    private fun request(
        id: String = "tt1",
        type: String = "movie",
        addonBackdrop: String? = null,
        addonLogo: String? = null,
        tmdbOnly: Boolean = false
    ) = LandscapeArtRequest(
        id = id,
        type = type,
        addonBackdrop = addonBackdrop,
        addonLogo = addonLogo,
        tmdbOnly = tmdbOnly
    )

    // ---- the key ---------------------------------------------------------

    @Test
    fun `the key is the media type and the id joined by a colon`() {
        assertEquals("movie:tmdb:603", landscapeArtKey("movie", "tmdb:603"))
        assertEquals("series:tt0944947", landscapeArtKey("series", "tt0944947"))
    }

    @Test
    fun `the type is part of the identity, not decoration`() {
        // A TMDB id and an add-on's id for a show can be the same string; the
        // type is what keeps a film's backdrop off a show's card.
        assertNotEquals(
            landscapeArtKey("movie", "603"),
            landscapeArtKey("series", "603")
        )
    }

    @Test
    fun `an id that carries its own separator is not split again`() {
        // Add-on rails namespace their ids ("tmdb:603"), so a real key holds
        // more than one colon. The key is opaque: built and compared, never
        // taken apart - a caller that split on ':' would read the namespace as
        // the type and never match a filed entry.
        assertEquals("movie:tmdb:603", landscapeArtKey("movie", "tmdb:603"))
        assertNotEquals(
            landscapeArtKey("movie", "tmdb:603"),
            landscapeArtKey("movie", "tmdb")
        )
    }

    @Test
    fun `every spelling of a medium files under one key`() {
        // The type in the key is the type the artwork was looked up BY, so a
        // show typed "tv" by one add-on and "series" by another shares a single
        // entry instead of paying a second lookup for the second spelling.
        listOf("movie", "anime.movie").forEach { type ->
            assertEquals("movie:603", landscapeArtKey(type, "603"))
        }
        listOf("series", "show", "tv", "anime", "anime.series").forEach { type ->
            assertEquals("series:603", landscapeArtKey(type, "603"))
        }
        // Case and padding are the same type, not a new entry.
        assertEquals("movie:603", landscapeArtKey("  MOVIE ", "603"))
    }

    @Test
    fun `a type TMDB cannot name still gets a key`() {
        // The key has to be derivable whatever the type is, or an entry filed
        // as "no artwork" would be invisible to the reader and the item would
        // be re-attempted on every build.
        assertEquals("channel:7", landscapeArtKey("channel", "7"))
        assertEquals("channel:7", landscapeArtKey("CHANNEL", "7"))
    }

    @Test
    fun `a request keys itself the same way the card reads it`() {
        assertEquals("movie:tt1", request(id = "tt1", type = "movie").key)

        val meta = MetaPreview(id = "tmdb:603", type = "movie", name = "A Film")
        assertEquals(landscapeArtKey(meta.type, meta.id), meta.landscapeArtKey())
    }

    // ---- what TMDB offers ------------------------------------------------

    @Test
    fun `a detail with no images offers nothing`() {
        assertEquals(blank, (null as TmdbDetail?).landscapeArtUrls())
        assertEquals(blank, TmdbDetail(id = 1).landscapeArtUrls())
    }

    @Test
    fun `the backdrop offered is the alternate, not the hero's primary`() {
        val detail = TmdbDetail(
            id = 603,
            backdropPath = "/primary.jpg",
            images = TmdbImagesResponse(
                backdrops = listOf(
                    TmdbImageAsset(filePath = "/primary.jpg"),
                    TmdbImageAsset(filePath = "/alt.jpg")
                )
            )
        )

        assertEquals(
            TmdbRepository.BACKDROP_BASE + "/alt.jpg",
            detail.landscapeArtUrls().first
        )
    }

    @Test
    fun `the logo offered is TMDB's`() {
        val detail = TmdbDetail(
            id = 603,
            images = TmdbImagesResponse(
                logos = listOf(TmdbImageAsset(filePath = "/logo.png"))
            )
        )

        assertEquals(
            TmdbRepository.LOGO_BASE + "/logo.png",
            detail.landscapeArtUrls().second
        )
    }

    // ---- the merge -------------------------------------------------------

    @Test
    fun `TMDB's backdrop wins while the add-on's logo is kept`() {
        // The two halves of the entry are picked by opposite rules, which is
        // the part worth pinning: the add-on's background is usually the same
        // primary image the hero is showing (so TMDB's alternate wins), while
        // its clearlogo is already the right language and styling for the title
        // it ships with (so it wins over TMDB's).
        assertEquals(
            "tmdb-backdrop" to "addon-logo",
            landscapeArtEntry(
                tmdbArt = "tmdb-backdrop" to "tmdb-logo",
                request = request(addonBackdrop = "addon-backdrop", addonLogo = "addon-logo")
            )
        )
    }

    @Test
    fun `the add-on's clearlogo wins over TMDB's, and TMDB's wins over nothing`() {
        // Deliberate, and the one rule that reads backwards next to the
        // backdrop: a provider's logo is already the right language and styling
        // for the title it ships with, so it is preferred over TMDB's.
        assertEquals(
            "addon-logo",
            landscapeArtEntry(
                tmdbArt = null to "tmdb-logo",
                request = request(addonLogo = "addon-logo")
            ).second
        )
        assertEquals(
            "tmdb-logo",
            landscapeArtEntry(
                tmdbArt = null to "tmdb-logo",
                request = request()
            ).second
        )
    }

    @Test
    fun `the add-on's backdrop is what a card falls back to`() {
        assertEquals(
            "addon-backdrop" to null,
            landscapeArtEntry(
                tmdbArt = blank,
                request = request(addonBackdrop = "addon-backdrop")
            )
        )
    }

    @Test
    fun `a blank add-on field is a missing one`() {
        assertEquals(
            blank,
            landscapeArtEntry(
                tmdbArt = blank,
                request = request(addonBackdrop = "   ", addonLogo = "")
            )
        )
    }

    @Test
    fun `a pinned rail never falls back to the add-on's art`() {
        // The add-on's backgrounds on those rails carry burned-in promo text.
        // The entry is still written - with BLANK markers, because "no entry"
        // is how a rail says "resolve me again" - and LandscapeCard treats
        // blank exactly like missing.
        val pinned = request(
            addonBackdrop = "promo-backdrop",
            addonLogo = "promo-logo",
            tmdbOnly = true
        )

        assertEquals("" to "", landscapeArtEntry(tmdbArt = blank, request = pinned))
        assertEquals(
            "tmdb-backdrop" to "",
            landscapeArtEntry(tmdbArt = "tmdb-backdrop" to null, request = pinned)
        )
    }

    @Test
    fun `an entry is written for every request, empty answer included`() {
        // The map entry's PRESENCE is what stops a rebuild resolving the same
        // title again, so an item TMDB has nothing for has to be filed too.
        val entry = landscapeArtEntry(tmdbArt = blank, request = request())
        assertNull(entry.first)
        assertNull(entry.second)
    }

    // ── the number a ranked row shows ───────────────────────────────────────

    @Test
    fun `a ranked rail numbers its cards from one`() {
        assertEquals(1, landscapeRank(ranked = true, index = 0))
        assertEquals(2, landscapeRank(ranked = true, index = 1))
        assertEquals((1..10).toList(), (0 until 10).map { landscapeRank(true, it) })
    }

    @Test
    fun `an unranked rail numbers nothing`() {
        // Every row that is not a standing - a genre row, a discovery row, an
        // add-on's own catalog - keeps its artwork clean. A "3" on one of them
        // would be a claim the row never made.
        assertNull(landscapeRank(ranked = false, index = 0))
        assertNull(landscapeRank(ranked = false, index = 9))
    }

    @Test
    fun `the number is the card's place on screen, so nothing is skipped`() {
        // The rank is read off the items the viewport is showing, not off what
        // the add-on sent: a title the digital-release filter or the kids
        // ceiling dropped leaves no gap, and 1..N keeps matching the row.
        val survivors = listOf("kept-a", "kept-b", "kept-c", "kept-d")
        assertEquals(
            listOf(1, 2, 3, 4),
            survivors.mapIndexed { index, _ -> landscapeRank(ranked = true, index = index) }
        )
    }
}
