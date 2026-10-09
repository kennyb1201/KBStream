package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every game on the slate gets a guide lookup, not the first league's worth.
 *
 * This is the "a lot of games still say not in your playlist" fix, and the bug
 * it pins is worth stating plainly because it is invisible from the outside: the
 * pass stopped after 24 games, taken in league order from EVERY enabled league's
 * slate, while the hub matches all of them at once. A viewer with all the sports
 * on got EPG candidates for the first league or two and nothing at all for every
 * league behind it - and those games could then only match on the feed's own
 * broadcast names, which for a regional or streaming game name no channel in an
 * IPTV lineup. The guide had the game on six channels; the matcher never saw
 * one of them.
 *
 * So the contracts here are about coverage: no per-slate game cap, a lookup per
 * matchup whichever league it is in (deduplicated, because two games between the
 * same teams ask the same question), an abbreviation fallback for the guides
 * that title games "MIN @ TB", and a lookup that cannot fail the pass.
 *
 * There is no TV and no guide in CI, so - like the hub's other contracts - the
 * wiring is read out of the source.
 */
class SportsMatchCoverageContractTest {

    private val model: String by lazy { flat(VIEW_MODEL) }

    private val candidates: String by lazy {
        slice(model, "private suspend fun epgCandidates(", "private suspend fun searchTerms(")
    }

    private val lookup: String by lazy {
        slice(model, "private suspend fun searchTerms(", "private fun abbreviationSearchTerms(")
    }

    private val abbreviations: String by lazy {
        slice(model, "private fun abbreviationSearchTerms(", "private fun titleSearchTerm(")
    }

    private val titleTerm: String by lazy {
        slice(model, "private fun titleSearchTerm(", "private companion object")
    }

    @Test
    fun `the pass covers the whole slate rather than the first league of it`() {
        assertFalse(
            "no per-slate game cap: taken in league order it left every league behind the first uncovered",
            candidates.contains(".take(")
        )
        assertTrue(
            "every non-final game is considered",
            candidates.contains(".filter { it.state != GameState.FINAL }")
        )
        assertTrue(
            "and the ceiling that remains is a safety net, not a budget",
            candidates.contains("if (lookups >= MAX_EPG_LOOKUPS) return@forEach")
        )
        val ceiling = Regex("const val MAX_EPG_LOOKUPS = (\\d+)")
            .find(model)?.groupValues?.get(1)?.toInt()
        assertTrue("MAX_EPG_LOOKUPS must be declared", ceiling != null)
        assertTrue(
            "and must clear a full day of every enabled league (was 24 games, which was the bug)",
            ceiling!! >= 100
        )
        assertFalse(
            "the old per-slate cap is gone, not merely unused",
            model.contains("MAX_EPG_GAMES")
        )
    }

    @Test
    fun `a matchup is looked up once however many cards it covers`() {
        assertTrue(
            "two games between the same teams ask the same question",
            candidates.contains("if (!asked.add(terms.sorted().joinToString(\"|\"))) return@forEach")
        )
        assertTrue("the set it dedupes into exists", candidates.contains("val asked = HashSet<String>()"))
        assertTrue(
            "both teams must be searchable, or the lookup is a single-team query",
            candidates.contains("if (terms.size < 2) return@forEach")
        )
    }

    @Test
    fun `a guide that titles a game by abbreviation is still found`() {
        assertTrue(
            "when the nickname lookup comes back empty, the abbreviations are asked",
            candidates.contains(".ifEmpty { searchTerms(abbreviationSearchTerms(game)) }")
        )
        assertTrue(
            "both abbreviations, lowercased, and only when long enough to search on",
            abbreviations.contains("team.abbreviation.trim().lowercase()") &&
                abbreviations.contains("it.length >= MIN_ABBREV_SEARCH_TERM_LENGTH")
        )
        assertTrue(
            "the floor for an abbreviation is 2, not the 3 the nickname terms use",
            model.contains("const val MIN_ABBREV_SEARCH_TERM_LENGTH = 2")
        )
    }

    @Test
    fun `the lookup term is the feed's own short name, which is what a guide titles a game with`() {
        // The reported symptom behind this case: whole sports read "Not in your
        // playlist" over a guide that was carrying their games. The lookup term
        // came from the LAST WORD of the feed's display name, which for a
        // college or soccer side is not the name a guide uses - the last word of
        // "Washington Huskies" is "Huskies", of "Leeds United" it is "United" -
        // so those matchups asked the index for a word that was not in the row
        // and got nothing back. The feed's short name is the name the guide
        // actually writes.
        assertTrue(
            "the short name must be read first",
            titleTerm.contains("team.shortName")
        )
        assertTrue(
            "and its last usable token is the term, because the lookup ANDs the two teams together",
            titleTerm.contains(".lastOrNull { it.length >= MIN_SEARCH_TERM_LENGTH }")
        )
        assertTrue(
            "the full name's last word stays the fallback for a feed with no short name",
            titleTerm.contains("team.displayName") &&
                titleTerm.contains("lastWord.length >= MIN_SEARCH_TERM_LENGTH -> lastWord")
        )
        assertTrue(
            "and the abbreviation is the last resort",
            titleTerm.contains("team.abbreviation.length >= MIN_SEARCH_TERM_LENGTH -> team.abbreviation")
        )
    }

    @Test
    fun `a lookup cannot fail the matching pass`() {
        assertTrue(
            "the search degrades to no EPG signal for those teams",
            lookup.contains("runCatchingCancellable") &&
                lookup.contains(".getOrDefault(emptyList())")
        )
        assertTrue(
            "and it is limited, so one lookup cannot pull the whole guide back",
            lookup.contains("iptv.searchProgramsByTitleTerms(terms, EPG_SEARCH_LIMIT)")
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
    }
}
