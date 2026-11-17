package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard's adjacent page is composed before the cycle reaches it (dig
 * four, 2026-11-16, §2e rank 8).
 *
 * The pager kept the default window, so the next page composed only on the tick
 * that paged to it: a page composition plus a hero-size decode (plus the fade
 * that holds two bitmaps) landing in the same frame as the transition. One page
 * either way keeps the neighbour ready, which is what a timer-driven swap can
 * afford - there are at most a handful of pages.
 *
 * The crossfade stays: the billboard is a slow, deliberate swap the viewer is
 * watching, not a tile in a scrolling rail, and it is on no scroll path.
 */
class BillboardPrecomposeContractTest {

    private val home by lazy { source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt") }

    @Test
    fun `the pager keeps a page of window on each side`() {
        val pager = home
            .substringAfter("HorizontalPager(")
            .substringBefore(") { index ->")
        assertTrue(
            "one page either way: $pager",
            // The pager's own name for it in the foundation this app resolves.
            pager.contains("beyondViewportPageCount = 1")
        )
        assertTrue(
            "and it is the billboard's own pager, still keyed on its state",
            pager.contains("state = pagerState")
        )
    }

    @Test
    fun `the hero crossfade is untouched here`() {
        assertTrue(
            "the phone hero keeps its fade - it is a watched swap, not a scroll",
            home.contains(".crossfade(true)")
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
