package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the coalescing lands in the page (spec 2026-11-15, third round — F1, F3).
 *
 * [DetailFocusMoveRulesTest] pins the decision itself. This pins that the screen
 * asks the rule and obeys it: the direction is passed with every press, the
 * cancel only happens on a new intent, a repeat press is named `via=coalesced` so
 * the paste can tell it from a reversal, and the move's own cleanup cannot clear
 * the move that replaced it.
 */
class DetailMoveCoalesceContractTest {

    private val screen = read("ui/detail/DetailScreen.kt")

    @Test
    fun `the move takes the press's direction and asks the rule`() {
        val start = screen.substringAfter("fun startFocusMove(").take(400)
        assertTrue(
            "the direction is part of the press, not inferred from the move",
            start.contains("direction: DetailFocusDirection")
        )
        assertTrue(
            "every press goes through the one decision",
            start.contains("DetailFocusMoveRules.decide(") &&
                start.contains("inFlight?.isActive == true")
        )
    }

    @Test
    fun `only a new intent cancels the move in flight`() {
        val start = screen.substringAfter("fun startFocusMove(").substringBefore("/**\n     * The press's fall-through")
        val coalesce = start.substringAfter("Decision.COALESCE -> {").substringBefore("// A new intent")
        assertFalse(
            "a repeat press cancels nothing - that cancel is the bug",
            coalesce.contains(".cancel()")
        )
        val launch = start.substringAfter("Decision.CANCEL_AND_LAUNCH -> {")
        assertTrue(
            "the reversal (and a first press) cancels, exactly as before",
            launch.contains("inFlight?.cancel()")
        )
    }

    @Test
    fun `a coalesced press is named, so cancelled only ever means reversed`() {
        val start = screen.substringAfter("fun startFocusMove(").take(3_000)
        assertTrue(
            "the line the paste reads",
            start.contains("landed=pending via=coalesced")
        )
        assertTrue(
            "with the row and direction the press was standing on",
            start.contains("\"detail move \$from \$direction -> ")
        )
        // The in-flight move's own line is unchanged and still the one that
        // reports the landing; this is the extra line a repeat press adds.
        assertTrue(
            "and the cancellation line still exists, for the reversal",
            screen.contains("tracedVia = \"cancelled\"")
        )
    }

    @Test
    fun `a cancelled move cannot clear the move that replaced it`() {
        val start = screen.substringAfter("fun startFocusMove(").take(3_000)
        assertTrue(
            "the cleanup is generation-guarded",
            start.contains("focusMoveGeneration == generation")
        )
        assertTrue(
            "and the job is cleared only for the newest move",
            start.contains("focusMoveInFlight = null") &&
                start.contains("focusMoveDirection = null")
        )
    }

    @Test
    fun `every press that starts a move names its direction`() {
        val calls = Regex("startFocusMove\\(")
            .findAll(screen)
            .count()
        val named = Regex("startFocusMove\\(\\s*DetailRow\\.").findAll(screen).count()
        val chainPress = Regex("startFocusMove\\(row, direction, presence\\)").findAll(screen).count()
        assertTrue("the page has several presses", calls > 5)
        assertTrue(
            "and every one of them passes a direction ($named row sites, $chainPress chain sites)",
            named + chainPress >= calls - 1 // the declaration itself is the -1
        )
    }

    @Test
    fun `the settled episode section is asked directly, before any reveal`() {
        // F2: the live code already asks the season chip first and only scrolls
        // the section back in when that ask fails, so a page that has the section
        // never enters the retry loop for it. Pinned so it cannot invert.
        val reveal = screen.substringAfter("suspend fun revealAndFocusEpisodes(")
            .substringBefore("suspend fun focusRow(")
        assertTrue(
            "the direct ask is the first thing the landing does",
            reveal.substringAfter("{").trimStart().startsWith("if (focusSeasonOrEpisode()) return true")
        )
        assertTrue(
            "and the scroll-back is the fallback",
            reveal.indexOf("revealRail(SEASON_ROW_KEY") > reveal.indexOf("focusSeasonOrEpisode()")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/src/main/java/com/kennyb1201/kbstream/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
