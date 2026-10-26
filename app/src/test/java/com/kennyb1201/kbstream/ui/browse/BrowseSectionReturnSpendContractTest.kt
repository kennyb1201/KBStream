package com.kennyb1201.kbstream.ui.browse

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a browse page's return record is SPENT.
 *
 * The record itself, and the decide-then-restore walk, are pinned by
 * [BrowseReturnFocusTest], [BrowseReturnDecisionTest] and
 * [BrowseReturnFocusWiringContractTest]. What none of them pinned is the spend,
 * and that is where the same bug landed twice in opposite directions:
 *
 *  - Nulling it in the isLoading branch discarded the card the viewer had just
 *    pressed, because the reload's isLoading always lands AFTER the effect
 *    consumed the record (the hook the wiring test now holds);
 *  - never spending it at all left the record set past a successful landing, so
 *    the NEXT load of the same page re-read it - with a fresh attempt budget -
 *    and re-landed focus on the stale card. On the TV section pages that is one
 *    genre chip press away: the chips reload the rails under the same rail
 *    titles, so [browseReturnDecision] answers TRY_LANDING and the D-pad leaves
 *    the chip row for the card the viewer had just come back from.
 *
 * So the rule is: hold it across the reload, spend it once the landing lands.
 * Collection and the phone pages already did; the three TV section pages now do
 * too, and this pins the rule on all of them so the third implementation cannot
 * drift again.
 */
class BrowseSectionReturnSpendContractTest {

    /** The three TV pages that are a list of rails behind a header. */
    private val tvSectionPages = listOf(
        "tag/TagScreen.kt",
        "studio/StudioScreen.kt",
        "decade/DecadeScreen.kt"
    )

    /** Every page that already spent its record after landing. */
    private val spendingPages = listOf(
        "collection/CollectionScreen.kt",
        "mobile/BrowseSectionsMobile.kt",
        "mobile/CollectionScreenMobile.kt",
        "mobile/ActorScreenMobile.kt",
        "mobile/KBFolderScreenMobile.kt",
        "mobile/LibraryScreenMobile.kt"
    )

    @Test
    fun `the TV section pages spend the record once the landing lands`() {
        tvSectionPages.forEach { path ->
            val screen = normalized(code(path))
            val landing = screen.indexOf("focusPlaced = restoreBrowseReturn(target)")
            val spent = screen.indexOf("if (focusPlaced) returnTarget = null")
            assertTrue(
                "$path: the landing has to be read into focusPlaced first - a spend " +
                    "before it would drop a record whose landing has not landed yet",
                landing >= 0
            )
            assertTrue(
                "$path: and a LANDED record has to be spent, or the page's next load " +
                    "re-reads it and lands focus on the stale card (which is what a " +
                    "genre chip press does)",
                spent > landing
            )
            assertFalse(
                "$path: while the reload branch keeps holding it - nulling it there is " +
                    "the other half of this bug, and cost every Back its card",
                screen.substringAfter("isLoading) {").substringBefore("} else if")
                    .contains("returnTarget = null")
            )
        }
    }

    @Test
    fun `the collection page and the phone pages still spend theirs`() {
        spendingPages.forEach { path ->
            assertTrue(
                "$path: this page's return effect is the one the TV pages were fixed " +
                    "to match - it spends the record after landing (or, for the phone " +
                    "pages, after putting the grid back)",
                code(path).contains("returnTarget = null")
            )
        }
    }

    @Test
    fun `nothing reloads the tag page but its route effect and its error retry`() {
        // The Tag page carries the same held-and-now-spent effect as Studio and
        // Decade but has no genre chips and no same-route guard, so the stale
        // replay had no in-page trigger when this was written: only the route
        // effect and the error branch's retry call load(), and the retry needs a
        // failed load after a landing. Pinned so a new caller - a refresh
        // action, a focus-driven reload - is noticed rather than silently
        // reviving the trigger.
        val tag = code("tag/TagScreen.kt")
        assertEquals(
            "TagScreen calls viewModel.load() from exactly two places: the route " +
                "effect and the error branch's retry",
            2,
            Regex("viewModel\\.load\\(").findAll(tag).count()
        )
    }

    @Test
    fun `the phone pages spend the record after they put the grid back`() {
        // Same rule, in the phone's own shape: there is no focus to land, so the
        // spend sits after the scroll that restores the place.
        val sections = code("mobile/BrowseSectionsMobile.kt")
        val effect = sections
            .substringAfter("LaunchedEffect(returnTarget, sections) {")
            .substringBefore("fun record(")
        assertTrue(
            "the phone puts the rail back, scrolls its row to the card, and then " +
                "spends the record - spending it first would lose the place on the " +
                "next recomposition the effect keys on",
            effect.contains("revealBrowseReturnRail(") &&
                effect.contains("returnTarget = null") &&
                effect.indexOf("revealBrowseReturnRail(") < effect.indexOf("returnTarget = null")
        )
    }

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

    private fun code(path: String): String =
        File(sourceRoot, "com/kennyb1201/kbstream/ui/$path").readText()

    private fun normalized(text: String): String =
        stripComments(text).replace(Regex("\\s+"), " ").trim()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }
}
