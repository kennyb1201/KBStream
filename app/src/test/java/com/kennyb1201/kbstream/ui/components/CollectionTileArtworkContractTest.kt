package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collection tile's artwork path, and the browse rails' - both read out of
 * the source, because neither is reachable from a unit test: they are a
 * Retrofit interface, a resolver that runs inside a Composable, and three
 * screens that need a device.
 *
 * The collection case exists because a collection is the one poster surface
 * whose art cannot come from a title's detail response: `GET /collection/{id}`
 * carries no `images` to append to, so the tile was handed whatever key art the
 * SEARCH result happened to carry - the image most likely to have a burned-in
 * title - and drew a text title in the corner. Everything below pins the three
 * halves of the fix: the endpoint, the shared ranking over it, and the wiring
 * that sends a collection through it instead of through a title lookup.
 *
 * The last case is the browse rails the same audit flagged. They already pass
 * their id and type through (that is what these assertions record), so the
 * cheap way this comes back is someone "simplifying" one of those two lines
 * away: without them the card silently loses the ranked backdrop AND the
 * clearlogo, with nothing failing.
 */
class CollectionTileArtworkContractTest {

    private fun source(path: String): String = File(findMainSourceRoot(), path).readText()

    private fun normalized(path: String): String =
        source(path).replace(Regex("\\s+"), " ").trim()

    /** A window of [length] characters after [anchor], for asserting inside one body. */
    private fun after(path: String, anchor: String, length: Int = 2000): String {
        val raw = source(path)
        assertTrue("$anchor not found in $path", raw.contains(anchor))
        return raw.substringAfter(anchor).take(length)
    }

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `the collection images are filtered the way a title's images are`() {
        // The ranking can only be as good as the list it is handed: without the
        // language restriction TMDB answers with every locale it holds, and the
        // null-first order below then sorts a foreign key art to the top.
        assertTrue(
            "the endpoint is the collection's own images, not the collection detail",
            source(TMDB_API_SERVICE).contains("@GET(\"collection/{id}/images\")")
        )
        assertTrue(
            source(TMDB_API_SERVICE).contains(
                "@Query(\"include_image_language\") imageLanguage: String = \"en,null\""
            )
        )
        assertTrue(
            "and it is a suspend fun on the same interface, so it shares the " +
                "Retrofit stack and the key every other call uses",
            normalized(TMDB_API_SERVICE).contains(
                "@GET(\"collection/{id}/images\") suspend fun getCollectionImages("
            )
        )
    }

    @Test
    fun `the collection fetch ranks with the shared rule and reports no better image as no answer`() {
        val body = after(TMDB_REPOSITORY, "suspend fun fetchCollectionBackdropCached(")

        assertTrue(
            "the ranking is the one every other card's backdrop goes through, " +
                "not a second copy of the order",
            body.contains("bestCardBackdropPath(")
        )
        assertTrue(
            "and the key art the tile already holds is passed in, so the " +
                "ranking keeps its \"never the image already on screen\" half",
            body.contains("primaryPath = primary")
        )
        assertTrue(
            "an answer that can only hand the primary back is reported as NO " +
                "answer - the caller already has the primary, at its own size",
            body.contains("?.takeIf { it != primary }")
        )
        assertTrue(
            "a collection is read from the cache first, the network is asked " +
                "second, and only then is anything filed",
            body.indexOf("collectionArtCache[collectionId]") in 0..<
                body.indexOf("api.getCollectionImages(") &&
                body.indexOf("api.getCollectionImages(") <
                body.indexOf("collectionArtCache[collectionId] = now to ranked")
        )
        assertTrue(
            "a failed request and an empty image list both leave the tile on " +
                "its own art rather than on a blank, and nothing is filed in " +
                "that case - one bad round-trip cannot park the key art on " +
                "every collection for half a day",
            body.indexOf("?: return null") in 0..<
                body.indexOf("collectionArtCache[collectionId] = now to ranked")
        )
    }

    @Test
    fun `the resolver sends a collection to its own images, never to a title lookup`() {
        val resolve = after(GLOBAL_LANDSCAPE_ART, "val tmdbArt =", length = 6000)

        assertTrue(
            "the collection branch is chosen by the request's type",
            resolve.contains("isCollectionArtType(request.type)")
        )
        assertTrue(
            resolve.contains("repository.fetchCollectionBackdropCached(")
        )
        assertTrue(
            "the key art the tile holds reaches the ranking as a path: the tile " +
                "carries a URL, the ranking compares file paths",
            resolve.contains("TmdbRepository.tmdbImagePath(request.addonBackdrop)")
        )
        assertTrue(
            "a collection is never resolved as a title: a shared number would " +
                "hand it a film's artwork",
            resolve.indexOf("fetchCollectionBackdropCached(") <
                resolve.indexOf("fetchEnrichedMetaCached(")
        )
        assertTrue(
            "and whatever the branch answers still goes through the one merge " +
                "rule, so the tile falls back to its own art",
            resolve.contains("landscapeArtEntry(")
        )
    }

    // ── spec 2026-11-23: the corner wordmark comes from fanart ──────────────

    @Test
    fun `a collection wordmark is requested as a collection, not as the film with that id`() {
        val url = after(FANART_RULES, "internal fun fanartCollectionUrl(", length = 900)

        assertTrue(
            "the request declares the id space: fanart tries the TMDB MOVIE id " +
                "first, so a bare collection id answers for whatever film shares " +
                "the number (collection 35 vs film 35)",
            url.contains("source = \"tmdb_collection\"")
        )
        assertTrue(
            "a collection is its own cache space, so its bundle can never be " +
                "the same row as a film's",
            normalized(FANART_RULES).contains("FanartKind.Collection -> \"collection\"")
        )
    }

    @Test
    fun `the resolver asks fanart for the collection logo and files only answers`() {
        val resolve = after(GLOBAL_LANDSCAPE_ART, "val tmdbArt =", length = 6000)

        assertTrue(
            "the logo is fanart's, addressed by the COLLECTION id",
            resolve.contains("clearlogoAnswer(FanartKind.Collection, collectionId)")
        )
        assertTrue(
            "a fanart call that failed is not written down as 'this collection " +
                "has no logo': the disk row keeps that answer for a month",
            resolve.contains("logo.getOrNull()?.answered == true")
        )
    }

    @Test
    fun `a collection tile never draws its name on the art`() {
        // Kenny's rule: the backdrop already carries the franchise branding, and
        // on a collection page the name is headline text right beside the tile.
        // The no-logo fallback stays for TITLE cards, which is why this is a
        // takeUnless on the type rather than a change to LandscapeCard.
        assertTrue(
            "a collection passes no fallback title to the card",
            normalized(GLOBAL_POSTER_CARD).contains(
                "fallbackTitle = contentDescription.takeUnless { isCollectionArtType(artType) },"
            )
        )
        assertTrue(
            "and the accessibility name is still passed - nulling the drawn " +
                "title must not make the tile unlabeled",
            normalized(GLOBAL_POSTER_CARD).contains("contentDescription = contentDescription,")
        )
    }

    @Test
    fun `both collection headers are resolved as collections`() {
        // Since the header title became the wordmark (spec 2026-11-23), the
        // resolution moved UP into the header and the card is handed its
        // answer: one request, two draws. The card still names the collection,
        // because the id space is what keeps a collection's art and the corner
        // title rule intact - see CollectionHeaderTitleLogoContractTest for the
        // split itself.
        listOf(COLLECTION_SCREEN, COLLECTION_MOBILE).forEach { screen ->
            val header = source(screen)

            assertTrue(
                "$screen's header must resolve the collection itself, or the " +
                    "title has no wordmark to draw",
                header.contains("addonType = ART_TYPE_COLLECTION")
            )
            assertTrue(
                "$screen's header card must still carry the collection id, or " +
                    "the header keeps cropping the flat poster with the name over it",
                header.contains("artId = collectionId.toString(),")
            )
            assertTrue(
                "$screen's header card must say it is a collection",
                header.contains("artType = ART_TYPE_COLLECTION")
            )
        }
    }

    @Test
    fun `the search tile resolves the collection's own art`() {
        val tile = after(SEARCH_PART2, "internal fun CollectionPosterTile(", length = 1200)

        assertTrue(
            "the tile names the collection, and the type is what routes it to " +
                "the collection endpoint",
            tile.contains("artId = collection.id.toString(),") &&
                tile.contains("artType = ART_TYPE_COLLECTION")
        )
        assertTrue(
            "the result's own key art stays as the fallback, so a TMDB miss " +
                "draws exactly what it drew before",
            tile.contains("backdropUrl = collection.backdropPath?.let {")
        )
    }

    @Test
    fun `a collection id is never read as a title's image choice`() {
        // Collection 55 and film 55 are different things, and every choice the
        // viewer makes is filed under a title id: reading one for a collection
        // puts a film's chosen still on an unrelated franchise's tile.
        assertTrue(
            normalized(GLOBAL_POSTER_CARD).contains(
                "rawId = artId.takeUnless { isCollectionArtType(artType) },"
            )
        )
    }

    @Test
    fun `every browse rail card carries the id and type it must be resolved by`() {
        listOf(DECADE, STUDIO, TAG).forEach { screen ->
            val rail = source(screen)

            assertEquals(
                "$screen must have exactly one card call, so the assertions " +
                    "below are about it",
                1,
                Regex("GlobalPosterCard\\(").findAll(rail).count()
            )
            assertTrue(
                "$screen's card must carry the item's id, or the resolver never " +
                    "runs and the tile draws the primary key art with a text title",
                rail.contains("artId = studioItem.item.id.toString(),")
            )
            assertTrue(
                "$screen's card must carry the item's own media type",
                rail.contains("artType = rawMediaType")
            )
        }
    }

    @Test
    fun `the collection type is the one spelling the vocabulary knows`() {
        assertEquals("collection", ART_TYPE_COLLECTION)
        assertTrue(isCollectionArtType("collection"))
        assertTrue(isCollectionArtType(" Collection "))
        assertFalse(isCollectionArtType("movie"))
        assertFalse(isCollectionArtType("series"))
        assertFalse(isCollectionArtType("tv"))
        assertFalse(isCollectionArtType(null))
    }

    private companion object {
        const val TMDB_API_SERVICE = "com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt"
        const val TMDB_REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val GLOBAL_LANDSCAPE_ART =
            "com/kennyb1201/kbstream/ui/components/GlobalLandscapeArt.kt"
        const val GLOBAL_POSTER_CARD =
            "com/kennyb1201/kbstream/ui/components/GlobalPosterCard.kt"
        const val SEARCH_PART2 = "com/kennyb1201/kbstream/ui/search/SearchScreenPart2.kt"
        const val DECADE = "com/kennyb1201/kbstream/ui/decade/DecadeScreen.kt"
        const val STUDIO = "com/kennyb1201/kbstream/ui/studio/StudioScreen.kt"
        const val TAG = "com/kennyb1201/kbstream/ui/tag/TagScreen.kt"
        const val FANART_RULES = "com/kennyb1201/kbstream/data/fanart/FanartRules.kt"
        const val COLLECTION_SCREEN =
            "com/kennyb1201/kbstream/ui/collection/CollectionScreen.kt"
        const val COLLECTION_MOBILE =
            "com/kennyb1201/kbstream/ui/mobile/CollectionScreenMobile.kt"
    }
}
