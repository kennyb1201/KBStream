package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sports Hub's two minors (spec 2026-11-11).
 *
 * Both are the same shape of fault: a question answered for the whole slate
 * where the answer only ever mattered for part of it.
 *
 *  - the match pass's skip also required a NON-EMPTY result map, so a slate the
 *    playlist matches nothing in - the exact case the skip was written for -
 *    re-ran the full FTS pass every 30s and re-blinked "Finding channel…" on
 *    every card;
 *  - the Favorites tab picked its leagues from followed teams but kept the loop
 *    alive on the whole section, so a league whose only followed game had gone
 *    final kept polling (and waking for kickoffs nobody follows) while another
 *    game in it was live.
 *
 * Source-reading for the ViewModel half, like the rest of this package's
 * contracts: `resolveMatches`, the tick and the poll's Job all live inside a
 * ViewModel that wants an Application and a network. The rules themselves are
 * pinned structurally here (they are pure, and `SportsLivePollRulesTest` runs
 * them); every assertion below fails against the 0.9.223 source.
 */
class SportsHubMinorsContractTest {

    private val viewModel by lazy { source(VIEW_MODEL) }
    private val rules by lazy { source(RULES) }

    /** `resolveMatches` from its declaration to the empty-slate early return. */
    private val matchPass: String by lazy {
        viewModel
            .substringAfter("private suspend fun resolveMatches(sections: List<LeagueSection>) {")
            .substringBefore("if (games.isEmpty() && events.isEmpty()) {")
    }

    /** The end of that function: where a completed pass files its key. */
    private val matchPassEnd: String by lazy {
        viewModel
            .substringAfter("SportsTier1Trace.record(tier1.render())")
            .substringBefore("private fun guideIndexSourceKey")
    }

    // ── F1: a zero-match slate is a completed pass ─────────────────────────

    @Test
    fun `the skip no longer requires a match to have been found`() {
        assertFalse(
            "F1: emptiness made the skip unreachable on the slate it was written for - " +
                "a pass that matched nothing still answered the question",
            matchPass.contains("_matches.value.isNotEmpty()")
        )
        assertTrue(
            "F1: the three checks that DO prove the answer is still current remain",
            matchPass.contains("matchKey == lastMatchKey") &&
                matchPass.contains("lastMatchLineupGeneration == lineupGeneration") &&
                matchPass.contains("lastMatchGuideGeneration == guideAtPassStart")
        )
    }

    @Test
    fun `the key is only filed by a pass that ran to completion`() {
        val apply = matchPassEnd.indexOf("lastMatchKey = matchKey")
        assertTrue(
            "F1: the key is recorded after the results it belongs to",
            matchPassEnd.indexOf("_matches.value = matches") in 0 until apply
        )
        assertTrue(
            "F1: and with the lineup and guide generations it was computed against",
            apply < matchPassEnd.indexOf("lastMatchLineupGeneration = lineupGeneration") &&
                matchPassEnd.indexOf("lastMatchLineupGeneration = lineupGeneration") <
                matchPassEnd.indexOf("lastMatchGuideGeneration = guideAtPassStart")
        )
        assertTrue(
            "F1: so a pass cancelled before the end never files one and is re-run next beat",
            matchPassEnd.indexOf("lastMatchGuideGeneration = guideAtPassStart") <
                matchPassEnd.indexOf("_matchingDone.value = true")
        )
    }

    @Test
    fun `a skipped pass never moves the loading state`() {
        val skip = matchPass.substringAfter("if (matchKey == lastMatchKey")
        val loading = matchPass.indexOf("_matchingDone.value = false")
        assertTrue(
            "F1: the skip still answers before any pass starts, so no card blinks",
            matchPass.contains("PerfTrace.record(\"sports.match.skipped\", 0L)") &&
                skip.substringBefore("}").contains("_matchingDone.value = true")
        )
        assertTrue(
            "F1: and the skip block sits above the line that starts a pass",
            matchPass.indexOf("if (matchKey == lastMatchKey") < loading
        )
        assertTrue(
            "F1: every real pass still brackets the cards false, then true",
            matchPass.trimEnd().endsWith("_matchingDone.value = false") ||
                matchPass.contains("_matchingDone.value = false")
        )
    }

    @Test
    fun `an empty slate keeps its own path`() {
        val empty = viewModel
            .substringAfter("if (games.isEmpty() && events.isEmpty()) {")
            .substringBefore("val channelsStarted")
        assertTrue(
            "F1: with no cards there is no key to skip against, and nothing to look up",
            empty.contains("_matches.value = emptyMap()") &&
                empty.contains("lastMatchKey = null") &&
                empty.contains("_matchingDone.value = true")
        )
    }

    // ── F2: Favorites keeps its own slate alive ────────────────────────────

    @Test
    fun `the pick and the keep-alive share one favorite test`() {
        val pick = rules
            .substringAfter("fun liveLeagues(")
            .substringBefore("val section = sections.firstOrNull")
        assertTrue(
            "F2: the Favorites pick reads the shared halves",
            pick.contains("it.isFavoriteLive(favoriteKeys) ||") &&
                pick.contains("it.isFavoriteKickoffArmed(favoriteKeys, nowMs)")
        )
        assertTrue(
            "F2: whose definition is written once, games only, for every caller",
            rules.contains("private fun SportsGame.isFavoriteLive(favoriteKeys: Set<String>): Boolean =") &&
                rules.contains("involvesFavorite(favoriteKeys) && state == GameState.LIVE") &&
                rules.contains(
                    "private fun SportsGame.isFavoriteKickoffArmed(favoriteKeys: Set<String>, nowMs: Long): Boolean ="
                ) &&
                rules.contains("involvesFavorite(favoriteKeys) && isKickoffArmed(state, dateMs, nowMs)")
        )
        assertTrue(
            "F2: and the keep-alive is those same halves, never a tournament",
            rules.contains("fun hasFavoriteLive(section: LeagueSection?, favoriteKeys: Set<String>): Boolean {") &&
                rules.contains("return section.games.any { it.isFavoriteLive(favoriteKeys) }") &&
                rules.contains("fun hasFavoriteKickoffArmed(") &&
                rules.contains("return section.games.any { it.isFavoriteKickoffArmed(favoriteKeys, nowMs) }")
        )
    }

    @Test
    fun `the next wake is a favorite start on the Favorites tab`() {
        val delay = rules.substringAfter("fun delayUntilNextStartMs(")
        assertTrue(
            "F2: the wait takes the same scope the pick and the return take",
            delay.contains("favoriteKeys: Set<String> = emptySet(),") &&
                delay.contains("favoritesOnly: Boolean = false,")
        )
        assertTrue(
            "F2: Favorites waits for a followed game, not for any game in the league",
            delay.contains("(!favoritesOnly || it.involvesFavorite(favoriteKeys))")
        )
        assertTrue(
            "F2: and a tournament is never a Favorites start - a field cannot be followed",
            delay.contains("val tournaments = if (favoritesOnly) {") &&
                delay.contains("Long.MAX_VALUE")
        )
        assertTrue(
            "F2: the clamp and floor that keep the loop honest are unchanged",
            delay.contains(".coerceIn(POLL_INTERVAL_MS, MAX_REARM_MS)")
        )
    }

    @Test
    fun `the tick keeps polling on the tab's own answer`() {
        assertTrue(
            "F2: the loop knows which tab it was armed for, once",
            viewModel.contains(
                "val favoritesOnly = _selectedLeaguePath.value == SportsLeagues.FAVORITES_PATH"
            ) && viewModel.contains("if (refreshLeagues(paths, favoritesOnly, favoriteKeys)) continue")
        )
        val poll = viewModel
            .substringAfter("private suspend fun refreshLeagues(")
            .substringBefore("/**\n     * Keeps the open sheet's stats moving")
        assertTrue(
            "F2: and asks the favorites question there instead of the whole-section one",
            poll.contains("if (favoritesOnly) {") &&
                poll.contains("SportsLivePollRules.hasFavoriteLive(section, favoriteKeys) ||") &&
                poll.contains(
                    "SportsLivePollRules.hasFavoriteKickoffArmed(section, favoriteKeys, now)"
                )
        )
        assertTrue(
            "F2: a league tab's answer is untouched",
            poll.contains("SportsLivePollRules.hasLive(section) ||") &&
                poll.contains("SportsLivePollRules.hasKickoffArmed(section, now)")
        )
        assertTrue(
            "F2: and the wait is asked with the same scope, or the two disagree",
            viewModel.contains("favoriteKeys = favoriteKeys,") &&
                viewModel.contains("favoritesOnly = favoritesOnly,")
        )
    }

    @Test
    fun `the beat, the arm and the league tab are unchanged`() {
        assertTrue(
            "F2: 30s beat",
            rules.contains("const val POLL_INTERVAL_MS = 30_000L")
        )
        assertTrue(
            "F2: five-minute kickoff arm",
            rules.contains("const val KICKOFF_ARM_MS = 5 * 60_000L")
        )
        assertTrue(
            "F2: six-hour re-arm clamp",
            rules.contains("const val MAX_REARM_MS = 6L * 60L * 60_000L")
        )
        assertTrue(
            "F2: a league tab still polls and still wakes on the whole section",
            rules.contains("return if (hasLive(section) || hasKickoffArmed(section, nowMs)) {")
        )
    }

    // ── source plumbing ────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
