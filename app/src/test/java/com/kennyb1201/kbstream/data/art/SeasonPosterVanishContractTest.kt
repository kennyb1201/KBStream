package com.kennyb1201.kbstream.data.art

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season poster that vanished on scroll-back (spec 2026-11-14).
 *
 * The poster showed, disappeared when he scrolled back to it, and stayed gone.
 * The effect wrote its answer straight into the state, so a re-run that came
 * back empty erased a good URL, and nothing re-triggered it afterwards. Every
 * run now decides through [SeasonPosterMemory] - replace on an answer, keep
 * through a failure, clear only on an answered none - both screens trace what
 * they did, and the header image gets one retry instead of a silent blank.
 *
 * Pinned by reading the sources: this needs two screens, a player-less Compose
 * tree and a network to exercise otherwise. The rule itself is covered by
 * [SeasonPosterMemoryTest].
 */
class SeasonPosterVanishContractTest {

    private val tv by lazy { read("ui/detail/DetailScreen.kt") }
    private val phone by lazy { read("ui/mobile/DetailScreenMobile.kt") }
    private val fanart by lazy { read("data/fanart/FanartArtRepository.kt") }
    private val tmdb by lazy { read("data/tmdb/TmdbRepository.kt") }
    private val diagnostics by lazy { read("data/reporting/Diagnostics.kt") }
    private val application by lazy { read("MainApplication.kt") }

    private val tvEffect by lazy {
        tv.slice("LaunchedEffect(normalizedType, tmdbDetail?.id, effectiveSeason)", "val hasExplicitSeasonSource")
    }
    private val phoneEffect by lazy {
        phone.slice("LaunchedEffect(normalizedType, tmdbDetail?.id, currentSeason)", "val watchedInSeason")
    }

    @Test
    fun `neither screen writes a lookup straight into its poster state`() {
        listOf(tvEffect to "TV", phoneEffect to "phone").forEach { (effect, name) ->
            assertTrue("$name: the rule is the shared one", effect.contains("SeasonPosterMemory.decide("))
            assertFalse(
                "$name: the raw answer must never be assigned - that is the erasure",
                effect.contains("= fanart.seasonPoster(")
            )
            assertTrue(
                "$name: a URL is assigned only on the replace branch",
                effect.contains("SeasonPosterMemory.Decision.REPLACE -> ")
            )
            assertTrue(
                "$name: and null only on the clear branch",
                effect.contains("SeasonPosterMemory.Decision.CLEAR -> ")
            )
        }
    }

    @Test
    fun `a run with no season or another page keeps the poster`() {
        listOf(
            Triple(tvEffect, "TV", "if (clear) seasonPosterUrl = null"),
            Triple(phoneEffect, "phone", "if (clear) mobileSeasonPosterUrl = null")
        ).forEach { (effect, name, conditionalClear) ->
            assertTrue(
                "$name: a transient null must not erase the poster - the null write " +
                    "is behind the \"another page\" branch, not the early return",
                effect.contains(conditionalClear)
            )
            assertTrue(
                "$name: and the keep verdict is recorded for the transient case",
                effect.contains("SeasonPosterMemory.Decision.KEEP")
            )
            assertTrue(
                "$name: only a page of another kind clears it",
                effect.contains("normalizedType != \"series\"") || effect.contains("normalizedType == \"movie\"")
            )
        }
    }

    @Test
    fun `the lookup says what each source answered`() {
        listOf(tvEffect to "TV", phoneEffect to "phone").forEach { (effect, name) ->
            assertTrue("$name: the provenance-carrying lookup", effect.contains("seasonPosterAnswer("))
            assertTrue(
                "$name: a failed TVDB resolve is not a proven absence",
                effect.contains("hasProvenNoTvdbMapping(showId, \"series\")")
            )
        }
        assertTrue(
            "fanart tells NONE from UNANSWERED by whether a bundle arrived",
            fanart.contains("bundle == null -> ArtSourceAnswer.UNANSWERED") &&
                fanart.contains("else -> ArtSourceAnswer.NONE")
        )
        assertTrue(
            "and TMDB tells a failed call from an answered blank",
            tmdb.contains("if (fetched.isFailure) return ArtAnswer(null, ArtSourceAnswer.UNANSWERED)")
        )
        assertTrue(
            "the old entry point keeps its behaviour by delegating",
            fanart.contains("wantFullSize: Boolean = false\n    ): String? = seasonPosterAnswer(tvdbId, tmdbTvId, season, wantFullSize).url") &&
                tmdb.contains("seasonPosterPath(tvId: Int?, season: Int?): String? =\n        seasonPosterAnswer(tvId, season).url")
        )
    }

    @Test
    fun `every run is named in the paste`() {
        listOf(tvEffect to "TV", phoneEffect to "phone").forEach { (effect, name) ->
            assertTrue("$name: the run is traced", effect.contains("SeasonPosterTrace.runLine("))
            assertTrue(
                "$name: and the trigger is computed from the keys, not guessed",
                effect.contains("previous.first != normalizedType") &&
                    effect.contains("previous.second != showId") &&
                    effect.contains("previous.third != seasonNumber")
            )
        }
        assertTrue(
            "the report prints the ring beside the Detail state",
            diagnostics.contains("SeasonPosterTrace.lines().forEach { report.appendLine(\"poster: \$it\") }")
        )
    }

    @Test
    fun `the header image survives its item being disposed`() {
        assertTrue("TV traces the draw", tv.contains("SeasonPosterTrace.imageLine(\n                                                            \"tv\""))
        assertTrue("phone traces the draw", phone.contains("SeasonPosterTrace.imageLine(\n                                                        \"phone\""))
        assertTrue("TV re-keys for one retry", tv.contains("key(seasonPosterRetry)"))
        assertTrue("phone re-keys for one retry", phone.contains("key(mobileSeasonPosterRetry)"))
        listOf(tv to "TV", phone to "phone").forEach { (screen, name) ->
            assertTrue(
                "$name: exactly one retry, not a loop",
                screen.contains("if (!seasonPosterRetried) {") ||
                    screen.contains("if (!mobileSeasonPosterRetried) {")
            )
        }
        assertTrue(
            "the app really has both image caches the retry can hit",
            application.contains(".memoryCache(") && application.contains(".diskCache(")
        )
    }

    @Test
    fun `a second run for the same show costs no network for the TVDB id`() {
        val resolve = tmdb.slice("suspend fun resolveTvdbId(", "internal fun hasProvenNoTvdbMapping")
        val cached = resolve.indexOf("cachedExternalIds(key, now)")
        val network = resolve.indexOf("fetchExternalIds(tmdbId, normalizedType)")
        assertTrue("the disk/memory copy is consulted first", cached in 0 until network)
        assertTrue("and only a miss reaches the network", network > cached)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val base = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream")
                val candidate = File(base, relative)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "$startMarker is missing" }
        val end = indexOf(endMarker, start)
        require(end > start) { "$endMarker must follow $startMarker" }
        return substring(start, end)
    }
}
