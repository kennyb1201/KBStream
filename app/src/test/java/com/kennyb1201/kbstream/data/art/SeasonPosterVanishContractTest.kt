package com.kennyb1201.kbstream.data.art

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season poster that vanished on scroll-back (spec 2026-11-14), now per season
 * (spec 2026-11-15, the poster season picker).
 *
 * The poster showed, disappeared when he scrolled back to it, and stayed gone.
 * The effect wrote its answer straight into the state, so a re-run that came back
 * empty erased a good URL, and nothing re-triggered it afterwards. Every run
 * decides through [SeasonPosterMemory] - replace on an answer, keep through a
 * failure, clear only on an answered none - and the image draw gets one retry
 * instead of a silent blank.
 *
 * What moved: the single header slot is gone, and the resolution lives once in
 * [SeasonPosterRow]'s `rememberSeasonPoster`, asked per season as each poster
 * composes. The rule is the same rule, so these pins follow it there - and the
 * per-season form is what makes the keep matter more, not less: a row that
 * re-composes as it scrolls re-runs the lookup for every poster it brings back.
 *
 * Pinned by reading the sources: this needs two screens, a player-less Compose
 * tree and a network to exercise otherwise. The rule itself is covered by
 * [SeasonPosterMemoryTest].
 */
class SeasonPosterVanishContractTest {

    private val tv by lazy { read("ui/detail/DetailScreen.kt") }
    private val phone by lazy { read("ui/mobile/DetailScreenMobile.kt") }
    private val row by lazy { read("ui/detail/SeasonPosterRow.kt") }
    private val fanart by lazy { read("data/fanart/FanartArtRepository.kt") }
    private val tmdb by lazy { read("data/tmdb/TmdbRepository.kt") }
    private val diagnostics by lazy { read("data/reporting/Diagnostics.kt") }
    private val application by lazy { read("MainApplication.kt") }

    /** The per-season resolution: the lookup and the effect that runs it. */
    private val resolve by lazy {
        row.slice("internal class SeasonPosterResolver(", "internal fun SeasonPosterArt(")
    }

    private val draw by lazy { row.substringAfter("internal fun SeasonPosterArt(") }

    @Test
    fun `nothing writes a lookup straight into a poster state`() {
        assertTrue("the rule is the shared one", resolve.contains("SeasonPosterMemory.decide("))
        assertFalse(
            "the raw answer must never be assigned - that is the erasure",
            resolve.contains("= fanart.seasonPoster(")
        )
        assertTrue(
            "a URL is assigned only on the replace branch",
            resolve.contains("SeasonPosterMemory.Decision.REPLACE -> poster =")
        )
        assertTrue(
            "and null only on the clear branch",
            resolve.contains("SeasonPosterMemory.Decision.CLEAR -> poster = null")
        )
        assertTrue(
            "the keep branch writes nothing at all",
            resolve.contains("SeasonPosterMemory.Decision.KEEP -> Unit")
        )
        listOf(tv to "TV", phone to "phone").forEach { (screen, name) ->
            assertFalse(
                "$name: neither screen owns a poster slot of its own any more",
                screen.contains("seasonPosterUrl") || screen.contains("mobileSeasonPosterUrl")
            )
        }
    }

    @Test
    fun `a run for another title keeps the poster already drawn`() {
        assertTrue(
            "the decide call reads the poster on screen, not a fresh null",
            resolve.contains("current = poster,")
        )
        assertTrue(
            "and a page that cannot resolve a season returns before touching it - " +
                "and so does a run paused behind a loading episode list (spec " +
                "2026-11-21, rule 7), which keeps whatever poster was already drawn",
            resolve.contains("if (!resolver.canResolve || paused) return@LaunchedEffect")
        )
        assertTrue(
            "a show without a TMDB id yet is the same case",
            resolve.contains("val lookup = resolver.lookup(season) ?: return@LaunchedEffect")
        )
    }

    @Test
    fun `the lookup says what each source answered`() {
        assertTrue("the provenance-carrying lookup", resolve.contains("seasonPosterAnswer("))
        assertTrue(
            "a failed TVDB resolve is not a proven absence",
            resolve.contains("hasProvenNoTvdbMapping(id, \"series\")") &&
                resolve.contains("tvdbProvenAbsent = tvdbProvenAbsent")
        )
        assertTrue(
            "and the rule is handed that distinction",
            resolve.contains("tvdbProvenAbsent = lookup.tvdbProvenAbsent")
        )
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
    fun `every season's run is named in the paste`() {
        assertTrue(
            "the run is traced with THIS season's number",
            resolve.contains("SeasonPosterTrace.runLine(trigger, season, lookup.answer, decision)")
        )
        assertTrue(
            "the trigger says whether the item is resolving for the first time",
            resolve.contains("val trigger = if (runs == 0) \"open\" else \"rerun\"")
        )
        assertTrue(
            "so a paste can name the source of every season's poster",
            diagnostics.contains(
                "SeasonPosterTrace.lines().forEach { report.appendLine(\"poster: \$it\") }"
            )
        )
    }

    @Test
    fun `each poster's image survives its item being disposed`() {
        assertTrue("the draw is traced per surface", draw.contains("SeasonPosterTrace.imageLine("))
        assertTrue("TV labels its draws", tv.contains("surface = \"tv\""))
        assertTrue("phone labels its draws", phone.contains("surface = \"phone\""))
        assertTrue("the draw re-keys for one retry", draw.contains("key(retry)"))
        assertTrue(
            "exactly one retry, not a loop",
            draw.contains("if (!retried) {") && draw.contains("retry++")
        )
        assertTrue("and the failure is traced", draw.contains("imageLine(surface, \"error\")"))
        assertTrue(
            "the app really has both image caches the retry can hit",
            application.contains(".memoryCache(") && application.contains(".diskCache(")
        )
    }

    @Test
    fun `a second run for the same show costs no network for the TVDB id`() {
        val resolveIds = tmdb.slice("suspend fun resolveTvdbId(", "internal fun hasProvenNoTvdbMapping")
        val cached = resolveIds.indexOf("cachedExternalIds(key, now)")
        val network = resolveIds.indexOf("fetchExternalIds(tmdbId, normalizedType)")
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
