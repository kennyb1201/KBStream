package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the CW hidden-titles filter reads its identity, and where its decision
 * is printed.
 *
 * The match rule itself is a behaviour test (`CwHiddenMatchTest`); this pins the
 * wiring the field case turned on, because each link is silently revertable:
 *
 *  - the filter must ask the CARD for the decision (`item.hiddenDecision`), not
 *    rebuild an identity inline - that inline expression is the defect;
 *  - the show name a card arrived without comes from the parent row the rail is
 *    built from, published by the ViewModel off the same history flow, so the
 *    filter never reads the database and never compares an episode title;
 *  - the decision has to reach COPY DIAGNOSTICS, or the next report of this
 *    shape costs another evening (spec F2);
 *  - and the two neighbours (the Upcoming schedule's own filter, the Top Picks
 *    blend) must stay as they are: they already pass the right identity, and
 *    this fix is not allowed to grow into them.
 */
class CwHiddenFilterContractTest {

    private val root: File by lazy { findSourceRoot() }

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
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val home = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    private val phoneHome = source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    private val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
    private val upNext = source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt")
    private val identity = source("com/kennyb1201/kbstream/ui/home/UpNextHiddenIdentity.kt")
    private val keys = source("com/kennyb1201/kbstream/data/library/HiddenTitleKeys.kt")
    private val hiddenTitles = source("com/kennyb1201/kbstream/data/library/HiddenTitles.kt")
    private val diagnostics = source("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")

    /** The CW filter block: from its pass to the Upcoming filter below it. */
    private fun cwFilterBlock(): String {
        val start = home.indexOf("val cwFilterPass = remember(")
        assertTrue("the CW filter pass is missing", start >= 0)
        val end = home.indexOf("val upcomingScheduleRaw by", start)
        assertTrue("the block after the CW filter is missing", end > start)
        return squash(home.substring(start, end))
    }

    @Test
    fun `the CW filter asks the card for its identity`() {
        val block = cwFilterBlock()
        assertTrue(
            "the filter must go through the card's identity, not rebuild one: $block",
            block.contains("item.hiddenDecision(hiddenTitleKeys, cwShowNames)")
        )
        assertFalse(
            "the pre-fix inline identity must not come back: $block",
            block.contains("item.showTitle ?: item.title")
        )
        assertTrue(
            "and it must still filter against the hidden key set",
            block.contains("cwFilterPass.filterNot { (_, decision) -> decision.hidden }")
        )
    }

    @Test
    fun `the phone's Home filters by the same decision, and prints it too`() {
        // The report that commissioned the fix came from the phone, and the
        // phone has its own Home: a fix on the TV file alone would have left
        // the reported screen untouched.
        val mobile = squash(phoneHome)
        val block =
            mobile.substringAfter("val cwFilterPass = remember(")
                .substringBefore("val upcomingRaw by")
        assertTrue(
            "the phone's CW filter must go through the card's identity: $block",
            block.contains("item.hiddenDecision(hiddenTitleKeys, cwShowNames)")
        )
        assertFalse(
            "the pre-fix inline identity must not come back on the phone: $block",
            block.contains("item.showTitle ?: item.title")
        )
        assertTrue(
            "and the phone's pass is what a phone capture needs to see",
            block.contains("SideEffect { CwFilterTrace.recordAll(cwFilterLines) }")
        )
    }

    @Test
    fun `both card menus hide by the identity the filter matches`() {
        assertTrue(
            "the TV card menu stores the filter's identity",
            squash(home).contains("menuItem.hiddenIdentity(cwShowNames)") &&
                squash(home).contains("identity.mediaType")
        )
        assertTrue(
            "and so does the phone's, or a hide made there would leave the card",
            squash(phoneHome).contains("item.hiddenIdentity(cwShowNames)") &&
                squash(phoneHome).contains("hideIdentity.mediaType")
        )
    }

    @Test
    fun `the identity is the card's own, widened on all three legs`() {
        val body = squash(identity)
        assertTrue(
            "the TMDB show id is an offered spelling",
            body.contains("tmdbId?.takeIf { it > 0 }?.let { \"tmdb:\$it\" }")
        )
        assertTrue(
            "and so is the history row the card was built from",
            body.contains("historyRowId")
        )
        assertTrue(
            "a series card takes the SHOW's name, from the card or its parent row",
            body.contains("showTitle?.trim()?.takeIf { it.isNotEmpty() }") &&
                body.contains("showNameFromHistory?.trim()?.takeIf { it.isNotEmpty() }")
        )
        assertTrue(
            "and a movie keeps its own title",
            body.contains("title.trim().takeIf { it.isNotEmpty() }")
        )
        assertTrue(
            "the type is declared first and derived from the card's shape only when it is not",
            body.contains("val declared = declaredMediaType(parentType)") &&
                body.contains("else -> hasSeriesSignal(")
        )
        assertTrue(
            "an episode-shaped id is a series signal, because season alone is what missed",
            body.contains("hasEpisodeIdShape(parentId)")
        )
    }

    @Test
    fun `the show name reaches the filter from the history row, not a query`() {
        assertTrue(
            "the extension looks the name up by parent id, then by history row",
            squash(upNext).contains(
                "parentId ?.let { upNextIdentifier(it) } ?.let { showNamesFromHistory[it] } " +
                    "?: historyRowId?.let { showNamesFromHistory[it] }"
            )
        )
        assertTrue(
            "the map is published off the same history flow the cards are built from",
            squash(viewModel).contains("publishContinueWatchingShowNames(rawHistory)")
        )
        assertTrue(
            "and it is re-published as a full replace, so a profile switch clears it",
            squash(viewModel).contains("if (names != _continueWatchingShowNames.value) {")
        )
        assertTrue(
            "the screen reads that map and keys the pass on it",
            squash(home).contains(
                "viewModel.continueWatchingShowNames.collectAsStateWithLifecycle()"
            ) && cwFilterBlock().contains("remember(upNextRaw, hiddenTitleKeys, cwShowNames)")
        )
    }

    @Test
    fun `the decision reaches COPY DIAGNOSTICS`() {
        assertTrue(
            "the pass is recorded after composition, not during it",
            squash(home).contains("SideEffect { CwFilterTrace.recordAll(cwFilterLines) }")
        )
        assertTrue(
            "the report prints the block",
            diagnostics.contains("CwFilterTrace.lines().forEach { report.appendLine(it) }")
        )
        assertTrue(
            "and a line carries the identity and the verdict",
            squash(identity).contains("\"cw.filter: \"") &&
                squash(identity).contains("append(\" hidden=\")")
        )
    }

    @Test
    fun `the neighbouring filters are untouched`() {
        val upcoming =
            squash(home).substringAfter("val upcomingSchedule = remember(upcomingScheduleRaw")
                .substringBefore("// The personalized row's own picks")
        assertTrue(
            "the Upcoming schedule still matches on the episode's own identity: $upcoming",
            upcoming.contains("episode.title") &&
                upcoming.contains("episode.parentId") &&
                upcoming.contains("episode.id")
        )
        assertTrue(
            "Top Picks is still not filtered a second time here",
            squash(home).contains(
                "Not filtered by the hidden-titles pass above on purpose"
            )
        )
        assertTrue(
            "the rails filter is unchanged",
            squash(home).contains("meta.type, meta.name, meta.yearOrNull, meta.id")
        )
    }

    @Test
    fun `hiding has one key rule, and HiddenTitles delegates to it`() {
        assertTrue(
            "the pure object owns the name-key prefix",
            keys.contains("const val TITLE_KEY_PREFIX = \"title::\"")
        )
        val hides =
            squash(hiddenTitles).substringAfter("fun hides(")
                .substringBefore("fun keys(")
        assertTrue(
            "HiddenTitles must not keep a second copy of the match: $hides",
            hides.contains("= HiddenTitleKeys.hides(hidden, mediaType, title, year, *ids)")
        )
        assertFalse(
            "the name-key construction belongs to the pure object, not here",
            hiddenTitles.contains("TITLE_KEY_PREFIX")
        )
    }
}
