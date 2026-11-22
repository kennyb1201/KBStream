package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's rail cards must receive remembered handlers, not a fresh lambda per
 * recomposition. The rail republishes many times during a cold load and on
 * every hero/focus change; a new `onClick` each time makes the card and its
 * subtree non-skippable even when the card's data is unchanged.
 *
 * Source-level because this module has no Compose test harness, and because the
 * failure is silent: the screen still works, it just repaints every visible card
 * more often than it has to.
 */
class HomeCardCallbackStabilityContractTest {

    private val screen: String by lazy {
        File(findMainSourceRoot(), SCREEN).readText()
    }

    @Test
    fun `catalog rail cards use remembered click, long-press and error handlers`() {
        assertTrue(
            "the click handler is keyed on the card, so a republish reuses it",
            screen.contains("val onCardClick =\n" +
                "                                                    remember(meta) {")
        )
        assertTrue(
            "the long-press handler is keyed on the card, its rail and its focus requester",
            screen.contains(
                "val onCardLongClick =\n" +
                    "                                                    remember(meta, requester) {"
            )
        )
        assertTrue(
            "the poster-error logger does not allocate a new lambda per recomposition",
            screen.contains(
                "val onPosterError =\n" +
                    "                                                    remember(meta) {"
            )
        )
        assertTrue(
            "both card variants receive the remembered click",
            screen.contains("onClick = onCardClick,")
        )
        assertTrue(
            "both card variants receive the remembered long press",
            screen.contains("onLongClick = onCardLongClick,")
        )
        assertTrue(
            "the poster card receives the remembered error handler",
            screen.contains("onPosterError = onPosterError")
        )
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
        const val SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
