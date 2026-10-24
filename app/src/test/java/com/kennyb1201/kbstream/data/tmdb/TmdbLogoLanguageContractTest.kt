package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Foreign clearlogos must never reach the landscape cards or the hero.
 *
 * Reported: "I'm seeing foreign clearlogos on the landscape cards and in hero
 * for some items." The selection itself (English preferred, then TMDB's
 * `null`) is unit-tested through [bestLogoPath] in `LandscapeArtTest`; what
 * CANNOT be seen from a JVM test is the two halves that only exist at runtime -
 * the request that decides which images TMDB even returns, and the disk cache
 * that would keep serving a logo already resolved the old way. Both are pinned
 * here by reading the sources, the way this tree's other player/network
 * contracts are read.
 *
 * The root cause, in one line: TMDB's `null` language is NOT "textless". It is
 * "language not set", the value TMDB assigns by default when an image is
 * uploaded without one (developer.themoviedb.org/docs/image-languages: `en,null`
 * returns "those that haven't been set yet"). For a backdrop that is close
 * enough to textless; for a LOGO it is an untagged wordmark, and a title's
 * untagged wordmark is very often its original-language mark. So the fix is not
 * "ask for en,null" (already done) but "never fall back to the null logo".
 */
class TmdbLogoLanguageContractTest {

    private val apiService by lazy { read(API_SERVICE) }
    private val heroRepo by lazy { read(HERO_REPO) }
    private val models by lazy { read(MODELS) }
    private val becauseYouWatched by lazy { read(BECAUSE_YOU_WATCHED) }

    @Test
    fun `every artwork endpoint asks TMDB for English plus untagged images`() {
        // The request half is what narrows the response before any client-side
        // pick: without it TMDB answers with every locale it holds.
        //
        // Three endpoints serve card artwork, and the count is a pin rather
        // than a census: the collection's own images endpoint joined the two
        // detail ones when collection tiles stopped drawing the key art the
        // search result happened to carry (see
        // CollectionTileArtworkContractTest), and it has to ask for the same
        // languages or the ranking it feeds is handed every locale TMDB holds.
        assertEquals(
            "the two detail endpoints and the collection images endpoint must " +
                "each carry the image-language filter",
            3,
            Regex("include_image_language\"\\) imageLanguage: String = \"en,null\"")
                .findAll(apiService)
                .count()
        )
        listOf("getMovie", "getTv", "getCollectionImages").forEach { endpoint ->
            val signature = apiService
                .substringAfter("suspend fun $endpoint(")
                .substringBefore("): ")
            assertTrue(
                "$endpoint must carry the filter in its own signature, not " +
                    "only somewhere in this file",
                signature.contains(
                    "include_image_language\") imageLanguage: String = \"en,null\""
                )
            )
        }
    }

    @Test
    fun `the hero resolves the English wordmark and never an untagged one`() {
        assertTrue(
            "the hero's logo pick must require an explicitly English wordmark",
            heroRepo.contains("filter { !it.filePath.isNullOrBlank() && it.iso6391 == \"en\" }")
        )
        assertFalse(
            "and must NOT keep the `null` fallback that served untagged (often " +
                "original-language) wordmarks whenever a title had no English mark",
            heroRepo.contains(".thenByDescending { it.iso6391 == null }")
        )
    }

    @Test
    fun `the hero's resolved-logo cache is versioned past the foreign entries`() {
        // This cache stores the RESOLVED URL, so a foreign logo written before
        // the fix would be served for its full 30-day TTL. The prefix is the
        // cache key, so bumping it drops every such row at once.
        //
        // Bumped a second time, to logo2, when it turned out the English-only
        // pick was correct but its LOGO-LESS answers were being persisted: a
        // title whose resolve raced an empty `logos` payload stayed
        // wordmark-free for the month (Kenny's MobLand case, where Detail - a
        // path that never reads this cache - showed the logo the whole time).
        assertTrue(
            "the hero disk cache prefix must be bumped past the logo-less entries",
            heroRepo.contains("DISK_KEY_PREFIX = \"hero_artwork_logo2:\"")
        )
    }

    @Test
    fun `a logo-less hero answer may not pin the title for thirty days`() {
        // The backdrop it did fetch stays worth caching; the LOGO question is
        // asked again after a day, so one raced resolve costs a request rather
        // than a month of plain-text heroes.
        assertTrue(
            "a backdrop-only entry must expire on a short negative TTL",
            heroRepo.contains("LOGO_MISS_TTL_MS = 24L * 60L * 60L * 1000L")
        )
        assertTrue(
            "and the read must pick it for entries with no logo",
            heroRepo.contains("val ttl = if (hasLogo) DISK_CACHE_TTL_MS else LOGO_MISS_TTL_MS") &&
                heroRepo.contains("if (ageMs < ttl)")
        )
    }

    @Test
    fun `every hero resolve is recorded for copy diagnostics`() {
        // Logcat is unreachable from a couch, and the capture Kenny could send
        // did not contain the hero's own resolution line. The same four facts -
        // tmdbId, media type, logo found, which layer answered - are recorded
        // for the in-app report instead, so a still-logo-less title names its
        // id and its answering layer without another evening of guessing.
        assertTrue(
            "every exit from resolve() records a HeroArtTrace line, including a " +
                "missing tmdbId that never asked at all",
            Regex("HeroArtTrace\\.record\\(").findAll(heroRepo).count() >= 4
        )
        listOf("MEMORY", "DISK", "NETWORK", "NONE").forEach { layer ->
            assertTrue(
                "the $layer layer must be distinguishable in the trace",
                heroRepo.contains("HeroArtTrace.Layer.$layer")
            )
        }
        val diagnostics = read(DIAGNOSTICS)
        assertTrue(
            "and Diagnostics must print those lines in the copied report",
            diagnostics.contains("private fun heroArtLine()") &&
                diagnostics.contains("heroArtLine()?.let { report.appendLine(it) }")
        )
    }

    @Test
    fun `the shared logo pick is English-only`() {
        // bestLogoPath backs the landscape cards, the Detail header, the
        // pre-playback splashes and the Continue Watching picker, so it is the
        // one place the rule has to hold for all of them.
        assertTrue(
            "bestLogoPath must filter to English logos",
            models.contains("filter { !it.filePath.isNullOrBlank() && it.iso6391 == \"en\" }")
        )
        assertFalse(
            "and must not rank untagged logos as a fallback",
            models.contains(".thenByDescending { it.iso6391 == null }")
        )
    }

    @Test
    fun `a landscape card's clearlogo is never the add-on's`() {
        // The add-on's `logo` is a bare URL with no language on it, and a
        // localized catalog ships its own locale's wordmark, so it cannot be
        // tested for English and must not be drawn - TMDB's English wordmark or
        // nothing. The field is gone from the request, not merely re-ranked, so
        // there is no way for it to creep back into the merge.
        val landscape = read(LANDSCAPE_ART)
        assertFalse(
            "the add-on logo must not be carried on the landscape request at all",
            landscape.contains("addonLogo")
        )
        assertTrue(
            "and the merge must take TMDB's English wordmark as the card's logo",
            landscape.contains("return (tmdbArt.first ?: addonBackdrop) to tmdbArt.second")
        )
    }

    @Test
    fun `the end-credits strip uses the shared English-only pick`() {
        // It had its own copy that preferred English but otherwise took TMDB's
        // first entry, which could be an untagged foreign mark.
        assertTrue(
            "BecauseYouWatched must use bestLogoPath() rather than its own sort",
            becauseYouWatched.contains("val logo = detail.bestLogoPath()")
        )
        assertFalse(
            "its old hand-rolled pick must be gone",
            becauseYouWatched.contains("sortedWith(compareByDescending { it.iso6391 == \"en\" })")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val API_SERVICE = "com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt"
        const val HERO_REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbHeroArtworkRepository.kt"
        const val MODELS = "com/kennyb1201/kbstream/data/tmdb/TmdbModels.kt"
        const val BECAUSE_YOU_WATCHED =
            "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        const val LANDSCAPE_ART =
            "com/kennyb1201/kbstream/ui/components/LandscapeArt.kt"
        const val DIAGNOSTICS =
            "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
    }
}
