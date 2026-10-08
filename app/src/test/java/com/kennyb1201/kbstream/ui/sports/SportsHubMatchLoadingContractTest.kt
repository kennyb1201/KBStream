package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "It says every game is not in my playlist, then a minute later half of them
 * have channels."
 *
 * Matching reads the paged lineup, builds a guide index over five figures of
 * channels and runs an EPG query, which on a large provider is 40-60 seconds.
 * For all of that time every card's channel line used to read "Not in your
 * playlist" - indistinguishable from a genuine no-match, and how a slow hub gets
 * read as a broken one. The fix is a `matchingDone` flag that words the window as
 * a loading state instead, and the invariants here are what keep it honest: a
 * pass never claims a no-match before it finishes, the card stays pressable
 * throughout, and the flag resets on every pass that re-runs matching.
 *
 * There is no TV in CI, so - like the hub's other contracts - the wiring is read
 * out of the source.
 */
class SportsHubMatchLoadingContractTest {

    private val vm: String by lazy { flat(VM) }
    private val hub: String by lazy { flat(HUB) }

    @Test
    fun `the hub publishes whether a matching pass has finished`() {
        assertTrue(
            "a StateFlow starts life as not-done",
            vm.contains("private val _matchingDone = MutableStateFlow(false)")
        )
        assertTrue(
            "and it is exposed for the screen to read",
            vm.contains("val matchingDone: StateFlow<Boolean> = _matchingDone.asStateFlow()")
        )
    }

    @Test
    fun `every pass starts by looking again and ends by saying it is done`() {
        val resolve = slice(vm, "private suspend fun resolveMatches(", "private suspend fun cachedGuideIndex(")
        assertTrue(
            "the top of the pass drops the flag, so a refresh or the live tick restarts the loading state",
            resolve.contains("_matchingDone.value = false")
        )
        assertTrue(
            "the ordinary way out finishes the pass",
            resolve.contains("_matches.value = matches")
        )
        assertEquals(
            "every way out finishes it too - an empty slate and a missing lineup are answers, not a wait",
            3,
            Regex("_matchingDone\\.value = true").findAll(resolve).count()
        )
    }

    @Test
    fun `the card line says it is still looking, and never claims a no-match early`() {
        val line = slice(hub, "private fun ChannelLine(", "private fun TournamentCard(")
        assertTrue(
            "with matching unfinished the line says it is looking",
            line.contains("!matchingDone -> \"Finding channel…\"")
        )
        assertTrue(
            "and only once it is done do the two settled facts appear, the missing lineup first",
            line.contains("lineupMissing -> \"Lineup not loaded\"") &&
                line.contains("else -> \"Not in your playlist\"")
        )
        assertTrue(
            "the loading branch is tested before the no-match ones, or the claim would show first",
            line.indexOf("!matchingDone") < line.indexOf("else -> \"Not in your playlist\"")
        )
    }

    @Test
    fun `a card is still one focus stop while matching runs`() {
        val card = slice(hub, "private fun GameCard(", "private fun TeamColumn(")
        assertTrue("the card takes the flag", card.contains("matchingDone: Boolean"))
        assertTrue(
            "and still opens the sheet, so a press during the load has somewhere to go",
            card.contains("onClick = { onOpenDetail(game) }")
        )
        assertFalse(
            "the press is never gated on matching - the loading state does not block interaction",
            card.contains("if (matchingDone)")
        )
    }

    @Test
    fun `the detail sheet shows the same loading state on its watch row`() {
        val sheet = slice(hub, "private fun GameDetailSheet(", "private fun BackupChannelLabel(")
        assertTrue("the sheet is told whether matching is done", sheet.contains("matchingDone: Boolean"))
        assertTrue(
            "and its disabled Watch row reads the same loading label, not the no-match one",
            sheet.contains("SportsDetailRules.watchLabel(false, lineupMissing, matchingDone)")
        )
    }

    @Test
    fun `the screen hands the flag from the hub down to every card`() {
        assertTrue(
            "the screen collects it",
            hub.contains("val matchingDone by viewModel.matchingDone.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "the sheet is told",
            hub.contains("channels = matches[game.id].orEmpty(), lineupMissing = lineupMissing, matchingDone = matchingDone,")
        )
        assertTrue(
            "and the game card is told, through the row that builds them",
            hub.contains("lineupMissing = lineupMissing, matchingDone = matchingDone, onOpenDetail = onOpenDetail,")
        )
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
    }

    private fun flat(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

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

    private companion object {
        const val VM = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val HUB = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
    }
}
