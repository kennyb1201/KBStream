package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the Detail screen's Awards fact is sourced from OMDB and wired the way
 * the repository's own tests assume.
 *
 * [com.kennyb1201.kbstream.data.omdb.OmdbRepositoryTest] pins what the lookup
 * returns; this pins how the answer reaches the screen, which is Compose and
 * ViewModel plumbing a JVM test cannot run: the flow the screen observes, the
 * fire-and-forget load that cannot delay the TMDB detail, the absent-on-blank
 * rule, and the fact's place in the row.
 *
 * Read from the source, like the other detail-page contract tests.
 */
class OmdbAwardsContractTest {

    private val viewModelSource: String by lazy {
        normalized(File(findMainSourceRoot(), VIEW_MODEL).readText())
    }
    private val enrichmentRaw: String by lazy {
        File(findMainSourceRoot(), ENRICHMENT).readText()
    }
    private val screenRaw: String by lazy {
        File(findMainSourceRoot(), SCREEN).readText()
    }
    private val screenSource: String by lazy { normalized(screenRaw) }

    // ── ViewModel ───────────────────────────────────────────────────

    @Test
    fun `the view model exposes awards as its own state flow`() {
        assertTrue(
            "the screen collects an awards StateFlow, so the load has to publish one",
            viewModelSource.contains("val awards: StateFlow<String?> = _awards.asStateFlow()")
        )
        assertTrue(
            "a new title must not show the previous one's awards while it loads",
            viewModelSource.contains("_awards.value = null")
        )
    }

    @Test
    fun `the awards load is launched, never awaited`() {
        assertTrue(
            "load() must fire the awards fetch alongside the ratings fetch",
            viewModelSource.contains("fetchAwards(normalizedType)")
        )
        assertTrue(
            "the fetch delegates to the enrichment helper",
            viewModelSource.contains("DetailRatingEnrichment.awards(this, normalizedType)")
        )
        assertFalse(
            "awaiting it would put OMDB between the user and the TMDB detail paint",
            Regex("fetchAwards\\([^)]*\\)\\.await").containsMatchIn(viewModelSource)
        )

        // The helper does its work inside the view model's own scope, so the
        // call that starts it returns immediately.
        val body = balancedBody(
            enrichmentRaw,
            "fun awards(vm: DetailViewModel, normalizedType: String)"
        )
        assertTrue(
            "the lookup must live in a launched coroutine, not on the calling thread",
            body.contains("vm.viewModelScope.launch")
        )
        assertTrue(
            "and it is the OMDB repository that performs it",
            body.contains("vm.omdbRepository.awardsFor(imdbId)")
        )
    }

    // ── Screen ──────────────────────────────────────────────────────

    @Test
    fun `the screen observes the awards flow`() {
        assertTrue(
            screenSource.contains("val awards by viewModel.awards.collectAsStateWithLifecycle()")
        )
    }

    @Test
    fun `an Awards fact appears only when there is text`() {
        assertTrue(
            "the awards block must be guarded by isNotBlank, with no 'Awards: —' placeholder",
            screenSource.contains(
                "awards ?.takeIf { it.isNotBlank() } ?.let { add( DetailFactItem( \"Awards\", it, wrap = true ) ) }"
            )
        )
        assertFalse(
            "the TMDB/addon meta awards field is no longer the source",
            screenSource.contains("m.awards")
        )
    }

    @Test
    fun `the awards text is shown whole rather than truncated`() {
        // Kenny's call (2026-10-09): "Won 1 Oscar. 45 wins & 78 nominations
        // total" displays in full. Every other fact card caps its value at two
        // lines with an ellipsis, which cut this sentence in half.
        assertTrue(
            "the fact has to ask for the wrapping card",
            screenSource.contains("DetailFactItem( \"Awards\", it, wrap = true )")
        )
        // Scoped to the fact card: other composables in this file legitimately
        // cap a line at two, and only this one renders a fact's value.
        val card = balancedBody(screenRaw, "private fun DetailFactCard(")
        assertTrue(
            "and the card's line cap has to follow the flag",
            card.contains("maxLines = if (fact.wrap) Int.MAX_VALUE else 2")
        )
        assertTrue(
            "a wrapping fact also needs room to be read at 150dp",
            card.contains("width(if (fact.wrap) 280.dp else 150.dp)")
        )
        assertFalse(
            "the value may no longer be capped at two lines unconditionally",
            card.contains("maxLines = 2")
        )
    }

    @Test
    fun `Awards sits after Revenue and before Country`() {
        val revenue = screenSource.indexOf("DetailFactItem( \"Revenue\"")
        val awards = screenSource.indexOf("DetailFactItem( \"Awards\"")
        val country = screenSource.indexOf("DetailFactItem( \"Country\"")

        assertTrue("all three facts are still built", revenue >= 0 && awards >= 0 && country >= 0)
        assertTrue(
            "movies read Release Date, Budget, Revenue, Awards",
            revenue < awards
        )
        assertTrue(
            "and the country/language facts stay after it",
            awards < country
        )
    }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    /**
     * The body of the function starting at [signature], found by brace
     * matching — unlike the newline heuristic other contract tests use, this
     * works for a top-level function whose indentation matches nothing.
     */
    private fun balancedBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("no body for function: $signature", open >= 0)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced braces for $signature")
    }

    private fun findMainSourceRoot(): File {
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        const val ENRICHMENT = "com/kennyb1201/kbstream/ui/detail/DetailRatingEnrichment.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
