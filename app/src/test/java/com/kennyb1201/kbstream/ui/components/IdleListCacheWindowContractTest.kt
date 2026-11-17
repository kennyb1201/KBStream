package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-composition, app-wide (spec 2026-11-16 round three F5; dig four's global
 * finding: `beyondBounds` had zero hits anywhere but Home).
 *
 * The window is defined once and applied where a viewer moves into off-screen
 * content: the Home rails, the Library and Catalog poster grids, Search's rail
 * column and Detail's page column. The Guide grid is excluded on purpose - one
 * of its rows composes the whole six-hour span, so pre-composing a row there is
 * the opposite of cheap.
 */
class IdleListCacheWindowContractTest {

    private val window by lazy { source("com/kennyb1201/kbstream/ui/components/IdleListCacheWindow.kt") }

    private val surfaces = mapOf(
        "Home rails" to "com/kennyb1201/kbstream/ui/home/HomeScreen.kt",
        "Library grid" to "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt",
        "Catalog grid" to "com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt",
        "Search rails" to "com/kennyb1201/kbstream/ui/search/SearchScreen.kt",
        "Detail page" to "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt",
        // The phone's own lists (dig five: the window was TV-only, and a flick
        // into a new row pays the same first composition a D-pad walk does).
        "phone Home" to "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt",
        "phone Library grid" to "com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt",
        "phone Catalog grid" to "com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt"
    )

    @Test
    fun `the rule is one object, both ways and filled while idle`() {
        assertTrue(
            "half a viewport each way",
            window.contains("override fun Density.calculateAheadWindow(viewport: Int): Int = viewport / 2") &&
                window.contains("override fun Density.calculateBehindWindow(viewport: Int): Int = viewport / 2")
        )
        // The "fill while idle" flag does not exist in the foundation this app
        // resolves (the interface there carries the two window functions only),
        // so the rule is the window itself plus where it is applied.
        assertTrue(
            "the window is handed to the state, which is the only place this foundation reads it",
            window.contains("rememberLazyListState(\n    cacheWindow = IdleListCacheWindow") &&
                window.contains("rememberLazyGridState(cacheWindow = IdleListCacheWindow)")
        )
    }

    @Test
    fun `every scrolling surface outside the guide carries it`() {
        surfaces.forEach { (name, path) ->
            val source = source(path)
            assertTrue(
                "$name takes its state from the shared window helper",
                source.contains("rememberIdleLazyListState(") ||
                    source.contains("rememberIdleLazyGridState(")
            )
        }
    }

    @Test
    fun `the guide grid is deliberately left out`() {
        val guide = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
        assertFalse(
            "a Guide row composes its whole six-hour span; pre-composing one is a " +
                "regression, not a saving",
            guide.contains("rememberIdleLazyListState(") ||
                guide.contains("rememberIdleLazyGridState(")
        )
        assertTrue(
            "and the reason is written down where the window is defined",
            window.contains("NOT applied to the Guide grid")
        )
    }

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: " + file.absolutePath)
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
