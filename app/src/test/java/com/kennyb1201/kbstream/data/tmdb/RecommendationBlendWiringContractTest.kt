package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both recommendation surfaces run the SAME engine, and each supplies only what
 * is its own.
 *
 * "More Like This" and "Because You Watched" were two engines before this: two
 * relevance rules, two places to fix the same report, and a player row that
 * could suggest a title Detail would never show. The blend now lives in one
 * file ([BLEND]); this contract pins the two call sites, the parameters each
 * passes, and the three product decisions that are easy to undo by accident:
 *
 *  - franchise belongs to "Because You Watched" ONLY - Detail's Collections
 *    rail sits directly above More Like This and already carries the saga;
 *  - `/similar` is fetched only when the other tiers leave the row short;
 *  - the reason line is the blend's own wording, and there is no match
 *    percentage anywhere.
 *
 * There is no device in CI and neither screen can be driven from a JVM test, so
 * the wiring is what these assertions read.
 */
class RecommendationBlendWiringContractTest {

    @Test
    fun `one engine owns the tiers, the scoring and the watched rule`() {
        val blend = source(BLEND)
        listOf(
            "internal suspend fun buildRecommendationBlend(",
            "internal fun blendScore(",
            "internal fun blendRank(",
            "internal fun blendSelect(",
            "internal fun blendWatched(",
            "internal fun blendReason(",
            "internal suspend fun blendKidsGate("
        ).forEach { declaration ->
            assertTrue("the blend no longer declares $declaration", blend.contains(declaration))
        }
        val player = source(BYW)
        assertFalse(
            "the player must not keep a second copy of the tier weights",
            player.contains("FRANCHISE(100)")
        )
        assertFalse(
            "nor a second ranking",
            player.contains("sortedWith(compareByDescending")
        )
    }

    @Test
    fun `franchise belongs to because you watched and never to more like this`() {
        val blend = source(BLEND)
        assertTrue(
            "the franchise tier is gated behind its own flag",
            blend.contains("if (params.includeFranchise) {")
        )
        val player = source(BYW)
        assertTrue(
            "the player asks for it",
            player.contains("includeFranchise = true")
        )
        val viewModel = source(DETAIL_VM)
        assertTrue(
            "Detail does not - the Collections rail above it already has the saga",
            viewModel.contains("includeFranchise = false")
        )
        listOf(viewModel, source(DETAIL)).forEach { source ->
            assertFalse(
                "and no Detail file fetches a collection for this rail",
                source.contains("getKBCollectionItems")
            )
            assertFalse(
                "nor can it ever label something \"Same series\"",
                source.contains("\"Same series\"")
            )
        }
    }

    @Test
    fun `similar is fetched only when the other tiers leave the row short`() {
        val blend = source(BLEND)
        val gate = blend.indexOf("if (candidates.size < params.pickCount) {")
        val fetch = blend.indexOf("repo.getSimilar(")

        assertTrue("the shortfall gate is missing", gate >= 0)
        assertTrue("the fill fetch is missing", fetch >= 0)
        assertTrue("`/similar` must sit inside the shortfall branch", gate < fetch)
        assertTrue(
            "and it must be the similarity endpoint, at one call site",
            1 == blend.split("repo.getSimilar(").size - 1
        )
        val api = source(API)
        assertTrue(
            "the movie endpoint exists",
            api.contains("@GET(\"movie/{id}/similar\")")
        )
        assertTrue(
            "and so does the tv twin",
            api.contains("@GET(\"tv/{id}/similar\")")
        )
        assertTrue(
            "with a repository wrapper",
            source(TMDB_REPO).contains("suspend fun getSimilar(")
        )
    }

    @Test
    fun `the reason line is the pinned wording and there is no match percentage`() {
        val blend = source(BLEND)
        listOf(
            "\"Same series\"",
            "\"Directed by \${person.name}\"",
            "\"Written by \${person.name}\"",
            "\"Starring \${person.name}\"",
            "\"Similar theme: \$keyword\"",
            "\"Similar to \$seedName\""
        ).forEach { template ->
            assertTrue("the reason template $template is missing", blend.contains(template))
        }
        assertFalse(
            "a fake match percentage is exactly what this row must not have",
            blend.contains("%")
        )
        assertTrue(
            "the reason is carried on the model the rail renders",
            source(MODELS).contains("val reason: String? = null")
        )
        assertTrue(
            "and drawn on the TV card",
            source(DETAIL).contains("captionReason = rec.reason")
        )
        assertTrue(
            "and on the phone card",
            source(MOBILE).contains("item.reason")
        )
    }

    @Test
    fun `each surface passes its own parameters`() {
        val player = source(BYW)
        assertTrue(player.contains("watchedMode = BlendWatchedMode.DROP"))
        assertTrue(
            "Because You Watched suggests something new: a watched title is gone",
            player.contains("dropHidden = true")
        )
        assertTrue(player.contains("pickCount = BYW_PICK_COUNT"))

        val viewModel = source(DETAIL_VM)
        assertTrue(
            "More Like This keeps a watched title, at the tail of the rail",
            viewModel.contains("watchedMode = BlendWatchedMode.DEMOTE")
        )
        assertTrue(
            "the TV rail's size is the blend's pick count",
            viewModel.contains("private const val MLT_PICK_COUNT = 30") &&
                viewModel.contains("pickCount = MLT_PICK_COUNT")
        )
        assertTrue(
            "and the phone still takes the first 20 of that same list",
            source(MOBILE).contains("recommendations.take(20)")
        )
    }

    @Test
    fun `the watched rule runs before the row is cut`() {
        val blend = source(BLEND)
        val walk = blend.indexOf("for (candidate in ranked) {")
        val select = blend.indexOf("blendSelect(gated, params.pickCount)")

        assertTrue("the ranked walk is missing", walk >= 0)
        assertTrue("the selection is missing", select >= 0)
        assertTrue(
            "dedup must happen before the take, or watched titles eat row slots",
            walk < select
        )
        assertTrue(
            "hidden titles are dropped inside the same engine",
            blend.contains("HiddenTitles.hides(")
        )
        assertTrue(
            "the TMDB flavor is tested first, with no lookup",
            blend.contains("localHistory.contains(\"tmdb:\$candidateTmdbId\")")
        )
    }

    @Test
    fun `the rail order on Detail is unchanged`() {
        val chain = source(FOCUS_CHAIN)
        val collection = chain.indexOf("    COLLECTION,")
        val moreLikeThis = chain.indexOf("    MORE_LIKE_THIS,")

        assertTrue("the focus chain lists both rails", collection >= 0 && moreLikeThis >= 0)
        assertTrue(
            "the Collections rail still sits directly above More Like This",
            collection < moreLikeThis
        )
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
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
        const val BLEND = "com/kennyb1201/kbstream/data/tmdb/RecommendationBlend.kt"
        const val BYW = "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        const val DETAIL_VM = "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
        const val MODELS = "com/kennyb1201/kbstream/data/tmdb/TmdbModels.kt"
        const val API = "com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt"
        const val TMDB_REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val FOCUS_CHAIN = "com/kennyb1201/kbstream/ui/detail/DetailFocusChain.kt"
    }
}
