package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail's "More Like This" rail has its own flow (perf spec 2026-11-22, 7a/7b).
 *
 * The trim and the taste blend used to publish a copy of the WHOLE TmdbDetail
 * just to swap `recommendations`. Every `remember(tmdbDetail)` derivation on the
 * page - people, media, seasons, keywords, the meta line, the trailer - re-ran
 * because one rail changed, which is the structural driver behind the page's
 * busy recomposition windows.
 *
 * Source pins: the screen and the view model are Android/Compose, so the wiring
 * is checked here and the behavior is covered by the device acceptance run.
 */
class DetailRecommendationsFlowContractTest {

    private val vm = source(VIEW_MODEL)
    private val tv = source(TV_SCREEN)
    private val phone = source(MOBILE_SCREEN)

    @Test
    fun `the rail has its own flow, beside the detail`() {
        assertTrue(
            "a nullable list flow, cleared with the title",
            vm.contains("private val _recommendations = MutableStateFlow<TmdbRecommendations?>(null)")
        )
        assertTrue(
            "exposed to both screens",
            vm.contains("val recommendations: StateFlow<TmdbRecommendations?> =")
        )
    }

    @Test
    fun `the trimmed publish writes the detail once and the rail separately`() {
        assertTrue(
            "the trim produces the rail, not a new detail",
            vm.contains("val shownRecommendations =")
        )
        assertTrue(
            "the detail is published whole, exactly as fetched",
            vm.contains("_tmdbDetail.value = detail")
        )
        assertTrue(
            "and the trimmed rail rides its own flow",
            vm.contains("_recommendations.value = shownRecommendations")
        )
        assertFalse(
            "a recommendations copy of the detail is the bug this removed",
            vm.contains("_tmdbDetail.value = shownDetail") ||
                vm.contains("recommendations = detail.recommendations?.copy(")
        )
    }

    @Test
    fun `the blend publishes the rail, never the detail`() {
        assertTrue(
            "the blend writes the rail flow",
            vm.contains("_recommendations.value = baseRecommendations.copy(results = items)")
        )
        assertFalse(
            "and a blend must not copy the whole detail again",
            vm.contains("_tmdbDetail.value = current.copy(")
        )
        assertTrue(
            "the blend's base falls back through the flow so a second blend keeps its list",
            squash(vm).contains("_recommendations.value?:current.recommendations?:TmdbRecommendations()")
        )
    }

    @Test
    fun `every path that drops the detail drops the rail with it`() {
        val clears = Regex("_recommendations\\.value = null").findAll(vm).count()
        assertTrue(
            "the load reset, the kids rejection and the synthetic stand-in all clear it; found $clears",
            clears >= 3
        )
        assertTrue(
            "the reset happens with the detail, before the new load can paint",
            vm.indexOf("_recommendations.value = null") <
                vm.indexOf("loadEpisodeOrders()")
        )
    }

    @Test
    fun `both screens read the rail from the flow, with the detail as fallback`() {
        assertTrue(
            "the TV screen subscribes",
            tv.contains("by viewModel.recommendations.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "and falls back to a cached detail's own list",
            tv.contains("val recommendationSource = recommendationsFlow ?: tmdbDetail?.recommendations")
        )
        assertTrue(
            "the phone subscribes too - the two surfaces cannot disagree",
            phone.contains("by viewModel.recommendations.collectAsStateWithLifecycle()") &&
                phone.contains("(recommendationsFlow ?: tmdbDetail?.recommendations)")
        )
    }

    @Test
    fun `the poster badge pass reads the rail that is on screen`() {
        assertTrue(
            "the blend's picks are what the rail shows, so they are what gets badged",
            vm.contains("val rail = _recommendations.value ?: _tmdbDetail.value?.recommendations")
        )
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
        const val TV_SCREEN = "ui/detail/DetailScreen.kt"
        const val MOBILE_SCREEN = "ui/mobile/DetailScreenMobile.kt"
    }
}
