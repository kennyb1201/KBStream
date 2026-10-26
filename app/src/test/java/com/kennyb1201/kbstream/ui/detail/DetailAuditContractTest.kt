package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Detail deep dive's findings, pinned where a unit test can reach them.
 *
 * The crash, the unaired marks and the leaked tab are all Compose behaviour -
 * the pure rules live in [DetailSeasonEpisodesTest] and [EpisodeRowKeyTest],
 * and this file pins the WIRING: the screens and the ViewModel have to call
 * those rules, or every rule test stays green while the bug is still on screen.
 */
class DetailAuditContractTest {

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** Whitespace-insensitive view, so an assertion survives re-indentation. */
    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ")

    private val tv by lazy { source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt") }
    private val mobile by lazy { source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt") }
    private val touch by lazy { source("com/kennyb1201/kbstream/ui/components/touch/KBEpisodeTouch.kt") }
    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt") }

    // ── C1: mobile episode rows are keyed by the episode ────────────────────

    @Test
    fun `the phone keys BOTH episode presentations by the episode, not the file`() {
        val src = normalized(mobile)
        assertEquals(
            "the vertical list and the sideways rail both render the same viewModel.episodes " +
                "list, so both need the file-id-proof key",
            2,
            count(src, "episodeRowKey(id, currentSeason, episode.episodeNumber)")
        )
        assertFalse(
            "a key of the stream id is FILE numbering and repeats on segmented shows: $src",
            src.contains("key = { \"ep:\" + it.streamId }")
        )
    }

    // ── C2: the TV episode menu uses the filtered number list ───────────────

    @Test
    fun `the TV episode menu marks the same aired-only numbers the chip menu does`() {
        val src = normalized(tv)
        assertTrue(
            "the episode-card menu must not pass the raw listing: $src",
            src.contains(
                "seasonEpisodeNumbers = seasonEpisodeNumbersFor( seasonForMenu ) .orEmpty()"
            )
        )
    }

    @Test
    fun `the ViewModel gates every mark on the aired rule`() {
        val src = normalized(viewModel)
        assertTrue(
            "the per-season write filters its numbers through the shared rule",
            src.contains("airedEpisodeNumbers(season, episodeNumbers)")
        )
        assertTrue(
            "and the whole-series map does too, season by season",
            src.contains("season to airedEpisodeNumbers(season, episodes)")
        )
    }

    // ── C3: unaired cards are not press targets on the phone ────────────────

    @Test
    fun `both phone episode layouts gate the tap and the hold on availability`() {
        listOf("mobile list row" to mobile, "touch rail card" to touch).forEach { (name, file) ->
            val src = normalized(file)
            assertTrue(
                "$name must not open an unaired episode: $src",
                src.contains("onClick = { if (!unavailable) onClick() }")
            )
            assertTrue(
                "$name must not raise the menu for one either: $src",
                src.contains("onLongClick = if (unavailable) null else onLongClick")
            )
        }
        assertTrue(
            "and the menu's own Play rows go through the same guard",
            normalized(mobile).contains(
                "episodeSeason !in staleSeasons && isEpisodeUnavailable(episode.airDate, today)"
            )
        )
    }

    // ── C4: the Media tab belongs to a title ────────────────────────────────

    @Test
    fun `the TV media tab is keyed by the title, like the phone's`() {
        val src = normalized(tv)
        assertTrue(
            "a drill-down reuses this composition: $src",
            src.contains("var mediaTabIndex by remember(type, id)")
        )
        assertFalse(
            "an unkeyed remember carried title A's tab onto title B",
            src.contains("var mediaTabIndex by remember {")
        )
    }

    // ── C5 / S1: dual roles survive, exact duplicates do not crash ──────────

    @Test
    fun `the TV cast rail dedupes by credit, so a second role is not dropped`() {
        val src = normalized(tv)
        assertTrue(src.contains("distinctBy { it.id to it.character.orEmpty() }"))
        assertFalse(
            "the bare id dropped a voice actor's second character",
            src.contains("distinctBy { it.id }.take(25)")
        )
    }

    @Test
    fun `the phone cast rail dedupes by the same composite its key uses`() {
        val src = normalized(mobile)
        assertTrue(src.contains("cast.distinctBy { it.id to it.character.orEmpty() }"))
    }

    // ── C6: a dead press says so ────────────────────────────────────────────

    @Test
    fun `an unresolvable poster press reports itself instead of doing nothing`() {
        assertEquals(
            "both rails (collection and More Like This)",
            2,
            count(normalized(tv), "Couldn't open this title")
        )
        assertEquals(
            "and both phone rails",
            2,
            count(normalized(mobile), "Couldn't open this title")
        )
    }

    // ── S2: sibling-profile ownership reaches Detail's badges ───────────────

    @Test
    fun `Detail applies the Home ownership rule to its badge sets`() {
        val src = normalized(viewModel)
        assertTrue(src.contains("private fun detailOwnedByAnotherProfile()"))
        assertTrue(
            "the title's episode ticks are withheld from a profile that does not own it",
            src.contains("if (detailOwnedByAnotherProfile()) {") &&
                src.contains("_watchedEpisodeKeys.value = emptySet()")
        )
        assertTrue(
            "and the poster rows filter per poster title",
            src.contains("filterNot { item -> item.tmdbId in ownedElsewhere }")
        )
    }

    // ── S3 / I2: rail requesters are disposed with their cards ──────────────

    @Test
    fun `rail requesters are registered by DisposableEffect and removed on dispose`() {
        val src = normalized(tv)
        assertTrue(src.contains("private fun RegisterRailRequester("))
        assertTrue(src.contains("if (requesters[key] === requester) requesters.remove(key)"))
        assertEquals(
            "every direct write site now goes through the helper",
            0,
            count(src, "railFocusRequesters[chipKey] =")
        )
        assertFalse(
            "the old direct-assignment registration",
            src.contains("railFocusRequesters[cardKey] = cardFocusRequester")
        )
    }

    // ── I1: the title's own page can save it ────────────────────────────────

    @Test
    fun `the title's own page offers the Library rows`() {
        assertTrue(
            "the Play long-press menu adds them through the shared menu",
            count(normalized(tv), "libraryTarget = LibraryAddTarget(") >= 2
        )
        assertTrue(
            "the phone gets a button of its own",
            normalized(mobile).contains("ADD TO LIBRARY")
        )
    }

    // ── I3: an unknown season size is not guessed at ────────────────────────

    @Test
    fun `a season no source can size does not offer a truncated mark`() {
        val src = normalized(tv)
        assertFalse(
            "the old fallback marked 1..highest already watched",
            src.contains("maxOrNull() ?: 0")
        )
        assertTrue(
            "the gesture is withheld when there is no episode set",
            src.contains("episodeNumbers.isNullOrEmpty()")
        )
    }

    private fun count(text: String, needle: String): Int {
        var from = 0
        var n = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }
}
