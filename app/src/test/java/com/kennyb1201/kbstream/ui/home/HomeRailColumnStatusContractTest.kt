package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail column's own status branches (findings C4 and C6).
 *
 * Two ways the column used to lie with a status card. It reported "no
 * catalogs" when the catalogs were healthy and every title on them was
 * hidden - with a retry that re-applied the same filter, so the card could
 * never clear - and its error-with-rails branch drew the pill INSTEAD of the
 * rails, despite the branch's own reasoning that "the rails below are what the
 * viewer is here for".
 */
class HomeRailColumnStatusContractTest {

    private val home = normalized(stripComments(source(HOME)))

    @Test
    fun `hide-everything is told apart from no catalogs`() {
        assertTrue(
            "the raw rail list must survive the hidden-title filter, so the card can " +
                "tell a failed/empty build from an emptied one",
            home.contains(
                "val railsRawForProfile = remember(railsRaw, railsBuiltForProfile, " +
                    "activeProfileId) { if (railsBuiltForProfile == activeProfileId) " +
                    "railsRaw else emptyList() }"
            )
        )

        val emptyBranch = home.substringAfter("rails.isEmpty() && !isLoading ->")
            .substringBefore("mergedEntries.isEmpty() && !isLoading ->")
        assertTrue(
            "an emptied rail column is named as such",
            emptyBranch.contains("All titles on your rails are hidden.")
        )
        assertTrue(
            "with the recovery that actually works: Undo the hiding, in place",
            emptyBranch.contains("actionLabel = \"UNHIDE ALL\"") &&
                emptyBranch.contains("onRetry = { HiddenTitles.unhideAll(context) }")
        )
        assertTrue(
            "and a first run with no add-ons still gets the add-an-addon card",
            emptyBranch.contains("No catalogs available. Add an addon to get started.")
        )
        assertTrue(
            "and a failed build keeps its network retry",
            emptyBranch.contains("Couldn't reach your add-ons. Check the ")
        )
    }

    @Test
    fun `a status line never withholds the rails it sits above`() {
        assertFalse(
            "the old branch drew the error INSTEAD of the catalog rails",
            home.contains("error != null && rails.isNotEmpty() ->")
        )
        val railBranch = home.substringAfter("mergedEntries.isEmpty() && !isLoading ->")
            .substringAfter("else -> {")
        assertTrue(
            "the status pill is drawn inside the rail column, above the entries",
            railBranch.substringBefore("itemsIndexed(").contains("Error: \$error")
        )
        assertTrue(
            "the entries are still the column's own items",
            railBranch.contains("items = mergedEntries,")
        )
    }

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

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
