package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A local advance card is never printed from evidence that cannot name a next
 * episode.
 *
 * Reported bug: shows the viewer was caught up on came back on COLD ENTRY as
 * "Next up" of the episode just finished, then burned off after a restart or
 * two. Two mechanisms produced the same card through one print site - the state
 * was incomplete (the resolver refused to walk and returned the furthest
 * episode watched), or the walk was blind to the row's id flavor (an
 * unresolved IMDb twin) and stepped onto the finished episode itself.
 *
 * [shouldOmitLocalAdvanceCard] is the decision both mechanisms share, and
 * [isImdbTwinUnknown] is the second mechanism's root: a local read that
 * answered with a narrowed id list must not be mistaken for a complete one.
 * A genuinely-next episode must still card - this rule omits a pass, never a
 * show.
 */
class LocalAdvanceCardOmissionTest {

    @Test
    fun `incomplete evidence omits the card whatever pair came back`() {
        assertTrue(
            "the resolver's known pair is the episode just finished, not a next one",
            shouldOmitLocalAdvanceCard(
                targetSeason = 2,
                targetEpisode = 5,
                rowSeason = 2,
                rowEpisode = 5,
                evidenceIncomplete = true
            )
        )
        assertTrue(
            "a null target pair printed as a card is the S1E1 floor escaping",
            shouldOmitLocalAdvanceCard(
                targetSeason = null,
                targetEpisode = null,
                rowSeason = 2,
                rowEpisode = 5,
                evidenceIncomplete = true
            )
        )
        assertTrue(
            "even a pair that LOOKS like a next episode is unproven here",
            shouldOmitLocalAdvanceCard(
                targetSeason = 2,
                targetEpisode = 6,
                rowSeason = 2,
                rowEpisode = 5,
                evidenceIncomplete = true
            )
        )
    }

    @Test
    fun `a self-target is omitted even when the state looked complete`() {
        assertTrue(
            "an advance card naming the episode it was built from is proof the " +
                "walk was missing that row",
            shouldOmitLocalAdvanceCard(
                targetSeason = 2,
                targetEpisode = 5,
                rowSeason = 2,
                rowEpisode = 5,
                evidenceIncomplete = false
            )
        )
    }

    @Test
    fun `a genuinely next episode still cards`() {
        assertFalse(
            shouldOmitLocalAdvanceCard(
                targetSeason = 2,
                targetEpisode = 6,
                rowSeason = 2,
                rowEpisode = 5,
                evidenceIncomplete = false
            )
        )
        assertFalse(
            "a season boundary is a different episode too",
            shouldOmitLocalAdvanceCard(
                targetSeason = 3,
                targetEpisode = 1,
                rowSeason = 2,
                rowEpisode = 10,
                evidenceIncomplete = false
            )
        )
    }

    @Test
    fun `a pair-less row cannot prove a self-target`() {
        // Documents the residual rather than hiding it: with no episode on the
        // row there is nothing to compare, so this rule stays out of the way.
        // The fix for that shape is write-side.
        assertFalse(
            shouldOmitLocalAdvanceCard(
                targetSeason = 2,
                targetEpisode = 5,
                rowSeason = null,
                rowEpisode = null,
                evidenceIncomplete = false
            )
        )
    }

    @Test
    fun `an unresolved imdb twin makes the read blind`() {
        assertTrue(
            isImdbTwinUnknown(
                tmdbId = 4567,
                imdbId = null,
                parentId = "tmdb:4567",
                missRecorded = false
            )
        )
        assertTrue(
            "a blank answer is the same as no answer",
            isImdbTwinUnknown(
                tmdbId = 4567,
                imdbId = "  ",
                parentId = "tmdb:4567",
                missRecorded = false
            )
        )
    }

    @Test
    fun `a proven no-mapping answer is not blindness`() {
        assertFalse(
            "TMDB said there is no IMDb id; the twin adds nothing and the show " +
                "must keep its card",
            isImdbTwinUnknown(
                tmdbId = 4567,
                imdbId = null,
                parentId = "tmdb:4567",
                missRecorded = true
            )
        )
    }

    @Test
    fun `a resolved twin and a tt-keyed row are both complete reads`() {
        assertFalse(
            isImdbTwinUnknown(
                tmdbId = 4567,
                imdbId = "tt12345",
                parentId = "tmdb:4567",
                missRecorded = false
            )
        )
        assertFalse(
            "a tt row reads its own flavor; the twin would add nothing",
            isImdbTwinUnknown(
                tmdbId = 4567,
                imdbId = null,
                parentId = "tt12345",
                missRecorded = false
            )
        )
        assertFalse(
            "no TMDB id means no lookup was attempted",
            isImdbTwinUnknown(
                tmdbId = 0,
                imdbId = null,
                parentId = "tt12345",
                missRecorded = false
            )
        )
    }

    @Test
    fun `the builder consults the rule before printing a card`() {
        val viewModel = normalized(stripComments(viewModelSource()))
        val builder = viewModel.substringAfter("private suspend fun buildLocalNextUpItem(")
            .substringBefore("private suspend fun kidsFilterUpNext(")

        assertTrue(
            "the incomplete flag must be read for this show at print time: $builder",
            builder.contains(
                "watchedStateMutex.withLock { parentId in watchedStateIncompleteShows }"
            )
        )
        assertTrue(
            "and the shared rule must be what decides: $builder",
            builder.contains("shouldOmitLocalAdvanceCard(") &&
                builder.contains("evidenceIncomplete = evidenceIncomplete")
        )
        val omission = builder.substringAfter("shouldOmitLocalAdvanceCard(")
            .substringBefore("val poster =")
        assertFalse(
            "the omission must not record the show as caught up - unknown is not " +
                "finished, and that would suppress its tracker card too: $omission",
            omission.contains("caughtUpShowKeys")
        )
    }

    /**
     * The other half of the ghost fix: an answer that cannot be printed must not
     * take the show with it. Field 2026-10-30 reported the whole shape - a card
     * that never moved, a rail much shorter than before, and a show with one
     * episode left gone even after a restart.
     */
    @Test
    fun `an unprovable answer falls back to the next listed episode`() {
        val viewModel = normalized(stripComments(viewModelSource()))
        val builder = viewModel.substringAfter("private suspend fun buildLocalNextUpItem(")
            .substringBefore("private suspend fun kidsFilterUpNext(")

        val omission = builder.substringAfter("shouldOmitLocalAdvanceCard(")
            .substringBefore("val poster =")

        assertTrue(
            "the untrustworthy answer must be replaced by the listing's next " +
                "episode, not dropped: $omission",
            omission.contains("resolveEpisodeAfterCompletedRow(")
        )
        assertTrue(
            "and the show may only be omitted when the listing has nothing " +
                "aired left: $omission",
            omission.contains("?: return null")
        )
    }

    @Test
    fun `the fallback walks the listing forward and skips unaired episodes`() {
        val viewModel = normalized(stripComments(viewModelSource()))
        val walk = viewModel.substringAfter("private suspend fun resolveEpisodeAfterCompletedRow(")
            .substringBefore("private suspend fun kidsFilterUpNext(")

        assertTrue(
            "strictly after the completed row is what keeps the ghost out: $walk",
            walk.contains("episode.episodeNumber > floorEpisode")
        )
        assertTrue(
            "and only aired episodes may be carded: $walk",
            walk.contains("isAiredOrUnknown( episode.airDate )")
        )
        assertTrue(
            "the first empty season is the end of the show: $walk",
            walk.contains("if (episodes.isEmpty()) return null")
        )
        assertTrue(
            "later seasons start from their first episode: $walk",
            walk.contains("floorEpisode = 0")
        )
    }

    @Test
    fun `the preload reports an unresolved twin as incomplete evidence`() {
        val viewModel = normalized(stripComments(viewModelSource()))

        assertTrue(
            "the preload must read the flagged id list, not the bare one",
            viewModel.contains(
                "val (showParentIds, imdbTwinUnknown) = " +
                    "localHistoryParentIdsWithTwinState( parentId, tmdbShowId )"
            )
        )
        assertTrue(
            "and the twin must reach the existing incomplete set",
            viewModel.contains(
                "if (localReadFailed || trackerReadFailed || imdbTwinUnknown) { " +
                    "watchedStateIncompleteShows.add( parentId )"
            )
        )
    }

    private fun viewModelSource(): String =
        source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

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
}
