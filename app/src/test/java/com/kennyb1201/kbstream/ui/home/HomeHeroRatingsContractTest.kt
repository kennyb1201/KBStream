package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home hero shows MDBList's critic/audience ratings under its metadata line.
 *
 * The hero resolves its title asynchronously on focus (see
 * [HomeViewModel.resolveHeroMeta]) and the ratings ride along on the same job.
 * Wire only half of this up - fetch the ratings but never expose them, or
 * collect them but never render the chips - and the feature compiles cleanly
 * while the hero simply shows nothing, which is exactly the "invisible until
 * someone looks at the screen" failure this reads the sources to prevent.
 */
class HomeHeroRatingsContractTest {

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

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }

    @Test
    fun `the view model resolves and exposes hero ratings`() {
        val vm = source(VIEW_MODEL)
        assertTrue(
            "HomeViewModel must hold hero ratings state",
            vm.contains("MutableStateFlow<MdbListRatings?>(null)")
        )
        assertTrue(
            "HomeViewModel must expose hero ratings as a StateFlow",
            vm.contains("val heroRatings: StateFlow<MdbListRatings?>")
        )
        assertTrue(
            "hero ratings are only fetched when an MDBList key is configured",
            vm.contains("MdbListClient.isConfigured(appContext)")
        )
        assertTrue(
            "hero ratings must actually be fetched",
            vm.contains("MdbListClient.fetchRatings(")
        )
        assertTrue(
            "hero ratings must be cleared when the focused item changes",
            vm.contains("_heroRatings.value = null")
        )
    }

    @Test
    fun `the hero renders the ratings chips`() {
        val screen = source(SCREEN)
        assertTrue(
            "HomeScreen must collect hero ratings",
            screen.contains("viewModel.heroRatings.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "the host must thread hero ratings into the hero",
            screen.contains("heroRatings = heroRatingsState")
        )
        assertTrue(
            "the hero must accept a heroRatings parameter",
            screen.contains("heroRatings: MdbListRatings? = null")
        )
        assertTrue(
            "the hero must build its chips from the shared source list",
            screen.contains("mdbListRatingSources(") && screen.contains("MdbListRatingChips(")
        )
    }
}
