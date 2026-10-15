package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coming back to the hub re-reads the whole slate.
 *
 * Reported (field, 2026-10-15): a Rays game was watched to completion, the viewer
 * backed out, and the favourites tab still said the game had not started. The
 * live tick was re-armed from the sections held across the player - and in that
 * copy the game is still UPCOMING, so its league was never in the live set and
 * nothing ever corrected the card. The same held copy put a ceiling on every
 * other score on the slate: they were up to a beat old, and a game that WAS live
 * and had since finished kept its last score.
 *
 * The fix is the hub's own full load on the way back rather than a bespoke
 * partial merge, so what is pinned here is the wiring, not a new rule:
 * `resumeLivePoll` detects a return and calls `refresh()`, the Refresh key's own
 * path, which reads every enabled league, keeps the cards up while it does, and
 * re-arms the tick from what came back. Both halves fail silently: a return that
 * only re-arms the tick looks exactly like the bug above, and a return that also
 * reloads when the hub was never left pays for the load the ViewModel's init
 * already started - two passes over ESPN on every first open.
 */
class SportsHubReturnRefreshContractTest {

    private val viewModel: String by lazy { source().replace(Regex("\\s+"), " ") }

    /** The return path, from its own declaration to the next public function. */
    private val resume: String by lazy {
        slice("fun resumeLivePoll() {", "fun rememberHubScroll(")
    }

    /** The full load itself, from its declaration to the favourites migration. */
    private val refresh: String by lazy {
        slice("fun refresh() {", "private fun adoptLegacyFavoriteKeys(")
    }

    /** The way out, which is what makes the next entry a return. */
    private val pause: String by lazy { slice("fun pauseLivePoll() {", "fun resumeLivePoll() {") }

    @Test
    fun `a hub that was left re-reads the whole slate on the way back`() {
        assertTrue(
            "the paused flag is exactly \"this hub was left\", and it decides the return",
            resume.contains("val returning = pollPaused")
        )
        assertTrue(
            "a return takes the full load - the hub's own path - not a partial one",
            resume.contains("if (returning) refresh() else syncLivePoll()")
        )
    }

    @Test
    fun `the first open pays for the load once, not twice`() {
        assertTrue(
            "nothing has paused the hub on the first composition",
            pause.contains("pollPaused = true")
        )
        assertTrue(
            "so the flag has to be read before it is cleared, or every entry looks like a return",
            resume.indexOf("val returning = pollPaused") < resume.indexOf("pollPaused = false")
        )
        assertFalse(
            "and no bypass of that decision: an unconditional load here would re-fetch over the " +
                "init's own refresh",
            resume.contains("refresh() syncLivePoll()") || resume.contains("syncLivePoll() refresh()")
        )
    }

    @Test
    fun `the full load is the hub's own, one pass, cards kept, tick re-armed`() {
        assertTrue(
            "every enabled league, in one pass",
            refresh.contains("val fetched = fetchSections(leagues)")
        )
        assertTrue(
            "the cards stay on screen while it lands: a spinner only for a hub with nothing to show",
            refresh.contains("_isLoading.value = _sections.value.isEmpty()")
        )
        assertTrue(
            "the slate is only cleared for a profile with no leagues enabled, never on a return",
            refresh.contains("if (leagues.isEmpty()) { _sections.value = emptyList()")
        )
        assertTrue(
            "and the tick is aimed at the fresh sections when it finishes, so a game that came back " +
                "FINAL leaves the poll set",
            refresh.contains("resolveMatches(fetched)") &&
                refresh.indexOf("resolveMatches(fetched)") < refresh.indexOf("syncLivePoll()")
        )
    }

    @Test
    fun `the steady tick and the way out are untouched`() {
        assertFalse(
            "no bespoke merge on the return path: the partial-refetch helper is gone with it",
            viewModel.contains("staleFavoritePaths")
        )
        assertTrue(
            "a partial merge survives only where it belongs - the 30s beat",
            viewModel.contains("private suspend fun refreshLeagues(paths: List<String>): Boolean") &&
                viewModel.contains("SportsLivePollRules.liveLeagues(")
        )
        assertTrue(
            "leaving still stops the tick and nothing else",
            pause.contains("pollPaused = true") &&
                pause.contains("livePollJob?.cancel()") &&
                !pause.contains("_sections")
        )
    }

    @Test
    fun `the stale-candidate rule is not left behind as dead code`() {
        val rules = File(findSourceRoot(), RULES).readText()
        assertFalse(
            "a return reloads everything, so a rule that names leagues one by one has no caller",
            rules.contains("fun staleLeagues(")
        )
        assertTrue(
            "what the beat still needs from the rules is untouched",
            rules.replace(Regex("\\s+"), " ").contains("fun liveLeagues(")
        )
    }

    // ── Source access ───────────────────────────────────────────────────

    private fun slice(startMarker: String, endMarker: String): String {
        val start = viewModel.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = viewModel.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return viewModel.substring(start, end)
    }

    private fun source(): String {
        val file = File(findSourceRoot(), VIEW_MODEL)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val RULES = "com/kennyb1201/kbstream/ui/sports/SportsLivePollRules.kt"
    }
}
