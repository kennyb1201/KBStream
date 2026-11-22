package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Detail review rail's publish race (spec 2026-11-22, F1-F3).
 *
 * Both legs launch concurrently and used to publish differently: TMDB replaced
 * the rail, Trakt appended to it. A Trakt-first open therefore lost its comments
 * to the TMDB publish that landed after, which is why a show with thousands of
 * Trakt votes showed only TMDB's handful of reviews - on some opens and not
 * others.
 *
 * The fix is structural (two sources, one combined publish, reset per title,
 * a guard on the TMDB leg, one diagnostics line), and [ReviewMergeTest] pins
 * the merge itself. These pins hold the wiring in place, as these files cannot
 * be run on the JVM test harness.
 */
class DetailReviewsRaceContractTest {

    private val vm = source(VIEW_MODEL)
    private val enrichment = source(ENRICHMENT)
    private val diagnostics = source(DIAGNOSTICS)

    @Test
    fun `the two sources are stored apart`() {
        assertTrue(
            "TMDB's pages have their own slot",
            vm.contains("private var tmdbReviewsSource: List<TmdbReview> = emptyList()")
        )
        assertTrue(
            "so do Trakt's comments",
            vm.contains("private var traktReviewsSource: List<TmdbReview> = emptyList()")
        )
    }

    @Test
    fun `both legs record a source and publish through the same rule`() {
        val setAll = block(vm, "internal fun setAllReviews(")
        assertTrue(
            "the TMDB leg writes its slot",
            setAll.contains("tmdbReviewsSource = value")
        )
        assertTrue(
            "then publishes - it does not assign the flow itself",
            setAll.contains("publishReviews()") && !setAll.contains("_allReviews.value =")
        )

        val addAll = block(vm, "internal fun addAllReviews(")
        assertTrue(
            "the Trakt leg writes its own slot",
            addAll.contains("traktReviewsSource = extra")
        )
        assertTrue(
            "and publishes through the same call",
            addAll.contains("publishReviews()") && !addAll.contains("_allReviews.value =")
        )

        val publish = block(vm, "private fun publishReviews()")
        assertTrue(
            "one combined publish, TMDB first then Trakt",
            publish.contains("mergeReviewSources(") &&
                publish.indexOf("tmdb =") in 0 until publish.indexOf("trakt =")
        )
        assertEquals(
            "the combined list is the rail's only writer",
            1,
            squash(vm).split("_allReviews.value=mergeReviewSources(").size - 1
        )
    }

    @Test
    fun `a new load clears both sources before any leg can publish`() {
        val resetAt = vm.indexOf("tmdbReviewsSource = emptyList()")
        assertTrue(
            "both slots are cleared on load",
            resetAt >= 0 &&
                vm.indexOf("traktReviewsSource = emptyList()", resetAt) > resetAt
        )
        assertTrue(
            "and the diagnostics line belongs to this load, not the last one",
            vm.contains("ReviewsTrace.beginLoad()")
        )
        assertTrue(
            "the clear happens before the empty publish, so a leg cannot ride the old slots",
            resetAt < vm.indexOf("_allReviews.value = emptyList()")
        )
    }

    @Test
    fun `the TMDB leg has the retarget guard the Trakt leg already had`() {
        val extras = block(enrichment, "fun extraReviews(")
        assertTrue(
            "the request's title is captured before the launch",
            extras.contains("val requestParentId = vm.imdbId")
        )
        assertTrue(
            "and checked before publishing",
            extras.indexOf("if (vm.imdbId != requestParentId) return@launch") in
                0 until extras.indexOf("vm.setAllReviews(")
        )
        assertTrue(
            "the Trakt leg keeps its own guard",
            block(enrichment, "fun traktComments(")
                .contains("if (vm.imdbId != requestParentId)")
        )
    }

    @Test
    fun `every Trakt exit says why in the diagnostics line`() {
        val trakt = block(enrichment, "fun traktComments(")
        assertTrue("a disconnected account is named", trakt.contains("noteTraktReviewsEmpty(\"no-token\")"))
        assertTrue("a retargeted page is named", trakt.contains("noteTraktReviewsEmpty(\"guard\")"))
        assertTrue(
            "and an empty or failed answer carries its reason",
            trakt.contains("noteTraktReviewsEmpty(reason)") &&
                trakt.contains("\"error:\"") &&
                trakt.contains("\"none\"")
        )
        assertTrue(
            "both counts are recorded where COPY DIAGNOSTICS prints them",
            vm.contains("ReviewsTrace.noteTmdb(value.size)") &&
                vm.contains("ReviewsTrace.noteTrakt(extra.size)") &&
                diagnostics.contains("ReviewsTrace.summary()")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        error("unbalanced: $declaration")
    }

    private fun squash(source: String): String = source.replace(Regex("\\s+"), "")

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val VIEW_MODEL = "ui/detail/DetailViewModel.kt"
        const val ENRICHMENT = "ui/detail/DetailRatingEnrichment.kt"
        const val DIAGNOSTICS = "data/reporting/Diagnostics.kt"
    }
}
