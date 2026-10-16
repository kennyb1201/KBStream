package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home puts the viewer back where they were when they return from Detail.
 *
 * The reported failure: `Screen.Detail` is a route in the same activity, so
 * opening a title disposes the Home composable and its `rememberLazyListState`
 * with it — every Back rebuilt the rails at the top with no focus on any card.
 * That is the most common navigation loop in the app, so it was the most
 * visible focus bug in it.
 *
 * Three lines make it work and any one of them going away silently restores the
 * bug, so they are read out of the source here (the same shape as the other
 * contract tests in this tree): the state has to live in the ViewModel (the
 * only thing that outlives the composable), the list state has to be SEEDED
 * from it, and the position has to be written back as the viewer scrolls.
 */
class HomeReturnRestoreContractTest {

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    @Test
    fun `the position is held where the screen cannot dispose it`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

        assertTrue(
            "HomeViewModel must hold the rails' scroll index",
            viewModel.contains("var railScrollIndex")
        )
        assertTrue(
            "HomeViewModel must hold the card focus was on",
            viewModel.contains("var lastFocusedRailItemKey")
        )
        assertTrue(
            "and the return signal itself: whether the screen about to compose " +
                "is a return or a fresh entry is a fact about the ViewModel's " +
                "life, not about whether some card happened to be remembered",
            viewModel.contains("fun recordHomeEntry(): Boolean {")
        )
    }

    @Test
    fun `the rails list is seeded from the remembered position`() {
        val screen = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")

        assertTrue(
            "rememberLazyListState must be seeded, or Home reopens at the top",
            screen.contains("initialFirstVisibleItemIndex = viewModel.railScrollIndex")
        )
        assertTrue(
            "the scroll position must be written back as the viewer scrolls",
            screen.contains("viewModel.railScrollOffset = offset")
        )
    }

    @Test
    fun `the remembered card is asked for focus on return`() {
        val screen = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")

        assertTrue(
            "the card's requester must be registered under its key",
            screen.contains("railFocusRequesters[cardKey] = requester")
        )
        assertTrue(
            "and used by the restore effect",
            screen.contains("railFocusRequesters[wanted]")
        )
        assertTrue(
            "a fresh entry skips the restore: whatever key an earlier visit " +
                "left behind is not a place this viewer was, and the landing on " +
                "Continue Watching owns focus on an arrival",
            screen.contains("if (isFreshEntry) return@LaunchedEffect")
        )
    }
}
