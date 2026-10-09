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
    fun `the detail endpoints still ask TMDB for English plus untagged images`() {
        // The request half is what narrows the response before any client-side
        // pick: without it TMDB answers with every locale it holds.
        assertEquals(
            "both detail endpoints must carry the image-language filter",
            2,
            Regex("include_image_language\"\\) imageLanguage: String = \"en,null\"")
                .findAll(apiService)
                .count()
        )
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
        assertTrue(
            "the hero disk cache prefix must be bumped past hero_artwork_en:",
            heroRepo.contains("DISK_KEY_PREFIX = \"hero_artwork_enlogo:\"")
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
    }
}
