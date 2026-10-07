package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-shot semantics of the Home -> rail-manager handoff (HD-P2-5).
 *
 * Home cannot raise the manager dialog itself - it is wired to the Add-ons
 * view model - so the button navigates to Add-ons and leaves this flag. Like
 * every other handoff in the app, the reader must clear it, or the manager
 * would re-open on the next, unrelated visit to Add-ons.
 */
class HomeRailManagerRequestTest {

    @Test
    fun `a request is consumed exactly once`() {
        HomeRailManagerRequest.request()

        assertTrue("the first read sees it", HomeRailManagerRequest.consume())
        assertFalse("and the second does not", HomeRailManagerRequest.consume())
    }

    @Test
    fun `nothing pending consumes as false`() {
        // Drain any state left by a previous test, then check the empty case.
        HomeRailManagerRequest.consume()

        assertFalse(HomeRailManagerRequest.consume())
    }
}

/**
 * And the card itself (HD-P2-5).
 *
 * The audit's finding: with every rail hidden, Home drew the hero and nothing
 * else. The empty-catalog card cannot catch it - it keys on the CATALOG list,
 * and the rails exist, they are just not visible - so the state had no hint and
 * no route back to the manager that did the hiding.
 */
class AllRailsHiddenCardContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `the empty branch carries the message and the manager action`() {
        val home = source(HOME)
        val start = home.indexOf("mergedEntries.isEmpty() && !isLoading ->")
        assertTrue(
            "the all-hidden branch must exist, after the catalog-empty one so " +
                "it cannot shadow it",
            start >= 0
        )
        val catalogEmpty = home.indexOf("rails.isEmpty() && !isLoading ->")
        assertTrue(
            "the catalog-empty branch must still come first",
            catalogEmpty >= 0 && catalogEmpty < start
        )
        val block = home.substring(start, home.indexOf("else -> {", start))
        assertTrue(
            "the card says what happened",
            block.contains("message = \"All rails are hidden.\"")
        )
        assertTrue(
            "and offers the way out",
            block.contains("actionLabel = \"MANAGE RAILS\"") &&
                block.contains("onRetry = onManageRails")
        )
        assertTrue(
            "the card takes focus and fills the rail viewport, like every " +
                "other status card",
            block.contains("modifier = Modifier.fillParentMaxSize()")
        )
    }

    @Test
    fun `the action reaches the manager through a consumed one-shot request`() {
        val addons = source(ADDONS)
        assertTrue(
            "the Add-ons screen must pick the request up as it appears",
            addons.contains("if (HomeRailManagerRequest.consume()) showCatalogManager = true")
        )
        assertTrue(
            "and it must open the SAME dialog the manager button does",
            addons.contains("if (showCatalogManager) {") &&
                addons.contains("CatalogManagerDialog(")
        )
        val main = source(MAIN)
        assertTrue(
            "Home's callback leaves the request and navigates there",
            main.contains("onManageRails = {") &&
                main.contains("HomeRailManagerRequest.request()") &&
                main.contains("screen = Screen.Addons(returnTo = Screen.Home)")
        )
    }

    @Test
    fun `the shared status card can label an action that is not a retry`() {
        val card = source(STATUS)
        assertTrue(
            "the label is overridable, with the retry action as the default, in " +
                "the app's button casing",
            card.contains("actionLabel: String = \"RETRY\"")
        )
        assertTrue(
            "and the card renders the label it was handed",
            card.contains("Text(actionLabel)")
        )
        assertFalse(
            "the hardcoded label must be gone, or MANAGE RAILS would read as a retry",
            card.contains("Text(\"RETRY\")")
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val ADDONS = "com/kennyb1201/kbstream/ui/addons/AddonsScreen.kt"
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val STATUS = "com/kennyb1201/kbstream/ui/components/KBStatusMessage.kt"
    }
}
