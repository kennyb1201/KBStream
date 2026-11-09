package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyword search wave.
 *
 * "cyberpunk" and "heist" are not titles, so no amount of movie/TV search will
 * ever find them — TMDB files them as keywords, and the app already has a page
 * that discovers by one (the Tag page the Keywords browse chip opens). What was
 * missing was the query that turns a typed word into a keyword hit.
 *
 * The wave is a sixth TMDB call, so the properties that matter are the ones the
 * other five already have: it runs beside them, its failure cannot take them
 * down, an empty answer means no rail rather than an error, and a tap lands on
 * the existing keyword page. Search is a Compose screen over an
 * AndroidViewModel, so the wiring is pinned from source; the rail rules are
 * pinned as the constants and the publish line they have to agree with.
 */
class KeywordWaveTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun viewModel() = source(VIEW_MODEL)

    /** From [start] up to the first [end] after it, or the file's end. */
    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        return text.substring(from, if (to > from) to else text.length)
    }

    /**
     * The block that starts every TMDB wave, up to the de-dupe index the add-on
     * rail consults (which is built from the title waves, not a wave of its own).
     */
    private fun waveBlock(text: String = viewModel()) = slice(
        text,
        "val tmdbMoviesDeferred = async {",
        "// The de-dupe index the add-on rails consult"
    )

    @Test
    fun `the keyword wave runs beside the other five`() {
        val vm = viewModel()
        val waves = waveBlock(vm)

        assertTrue(
            "the keyword lookup must be one of the parallel waves",
            waves.contains(
                "tmdbWave(\"Keyword search\") { tmdbRepository.searchKeywordsOrNull(normalized) }"
            )
        )
        assertEquals(
            "six waves: movies, TV, person, studio, collection, keyword",
            6,
            waves.split("Deferred = async {").size - 1
        )
        assertTrue(
            "and its hits must be awaited with the rest, or a keyword-only " +
                "query would publish after the fact",
            vm.contains("val keywordResults = keywordDeferred.await()")
        )
    }

    @Test
    fun `the keyword hits publish as a Keywords rail of eight`() {
        val vm = viewModel()

        assertTrue(
            "the rail is published from the wave's own list",
            vm.contains(
                "_keywordResults.value =\n" +
                    "                    keywordResults.distinctBy { it.id }.take(MAX_KEYWORD_RESULTS)"
            )
        )
        assertTrue(
            "the screen can only draw a rail it is handed",
            vm.contains("val keywordResults: StateFlow<List<TmdbSearchKeywordResult>>")
        )
        // Cap 8 "like Studios and Collections": the same rail length, so the
        // three name-only rails of the screen stay one shape.
        listOf("MAX_STUDIO_RESULTS", "MAX_COLLECTION_RESULTS", "MAX_KEYWORD_RESULTS").forEach { name ->
            assertTrue("$name must stay 8 (see MAX_KEYWORD_RESULTS)", vm.contains("const val $name = 8"))
        }
    }

    @Test
    fun `both surfaces draw the rail and gate it on having hits`() {
        val tv = source(TV)
        assertTrue(
            "TV must collect the rails",
            tv.contains("val keywordResults by viewModel.keywordResults.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "no keyword hits is no rail - not an empty heading",
            tv.contains("if (!isLoading && keywordResults.isNotEmpty()) {") &&
                tv.contains("SearchRail(title = \"Keywords\"")
        )
        assertTrue(
            "a keyword draws as a tag chip, the app's own keyword shape",
            tv.contains("label = \"#${'$'}{keyword.name}\"")
        )

        val mobile = source(MOBILE)
        assertTrue(
            "the phone collects them too",
            mobile.contains("val keywordResults by viewModel.keywordResults.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "with the same gate and the same heading",
            mobile.contains("if (!isLoading && keywordResults.isNotEmpty()) {") &&
                mobile.contains("MobileSearchRail(title = \"Keywords\")")
        )
        assertTrue(
            "and a touch tile for a tag",
            mobile.contains("KeywordTile(") && mobile.contains("text = \"#${'$'}{keyword.name}\"")
        )
    }

    @Test
    fun `a keyword hit counts as a match on both surfaces`() {
        // A query whose ONLY hit is a keyword must not read "No matches found":
        // the rail is a result, so it joins the total the empty state reads.
        assertTrue(
            "TV counts keyword hits",
            source(TV).contains("collectionResults.size + keywordResults.size +")
        )
        assertTrue(
            "and so does the phone",
            source(MOBILE).contains("collectionResults.size + keywordResults.size +")
        )
        assertTrue(
            "the count line names them too",
            source(TV).contains("keywordCount = keywordResults.size") &&
                source(TV_PART2).contains("if (keywordCount > 0) add(\"\$keywordCount keywords\")")
        )
        assertTrue(
            "and the phone's line does as well",
            source(MOBILE).contains("add(\"${'$'}{keywordResults.size} keywords\")")
        )
    }

    @Test
    fun `a keyword failure is isolated from the other waves`() {
        val vm = viewModel()

        // The wave goes through the counting wrapper: a failure is logged,
        // counted, and turned into an empty list rather than thrown, so the
        // other five waves publish exactly as they would have.
        assertTrue(
            "one failing wave must not throw out of the wave block",
            vm.contains("runCatchingCancellable { call() }") &&
                vm.contains("failedTmdbWaves.incrementAndGet()")
        )
        assertTrue(
            "and the all-fail card needs EVERY wave to fail, keyword included",
            vm.contains("const val TMDB_WAVE_COUNT = 6") &&
                vm.contains("if (failedTmdbWaves.get() == TMDB_WAVE_COUNT)")
        )
        // TMDB_WAVE_COUNT has to track the wave count or a keyword-only outage
        // would report the whole search as unavailable.
        assertEquals(
            "the wave count and the wave list must agree",
            6,
            waveBlock(vm).split("Deferred = async {").size - 1
        )
    }

    @Test
    fun `a keyword tap opens the keyword Tag page with the hit's id`() {
        val vm = viewModel()
        val opened = slice(vm, "fun onKeywordOpened(keyword: TmdbSearchKeywordResult) {", "\n    }")

        assertTrue(
            "the tap reuses the existing Tag navigation, as a KEYWORD page",
            opened.contains("onOpenTagScreen?.invoke(keyword.id, keyword.name, true, \"movie\")")
        )
        // The Tag screen is what turns isKeyword into the keyword discover
        // page: a tap that asked for a genre page with a keyword id would open
        // the wrong titles entirely.
        assertTrue(
            "and that page is the existing keyword discover page",
            source(TAG_VIEW_MODEL).contains(
                "tmdbRepository.getKeywordRailPage(screenId, title, pageNumber)"
            )
        )
        assertTrue(
            "TV routes its chips through it",
            source(TV).contains("viewModel.onKeywordOpened(keyword)")
        )
        assertTrue(
            "and so does the phone's tile",
            source(MOBILE).contains("viewModel.onKeywordOpened(keyword)")
        )
        assertFalse(
            "no new keyword screen: the Tag page is the one that exists",
            vm.contains("onOpenKeywordScreen")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        const val TV = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"
        const val TV_PART2 = "com/kennyb1201/kbstream/ui/search/SearchScreenPart2.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt"
        const val TAG_VIEW_MODEL = "com/kennyb1201/kbstream/ui/tag/TagViewModel.kt"
    }
}
