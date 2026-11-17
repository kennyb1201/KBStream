package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A season switch never blanks the episode row (round four F1, 2026-11-16).
 *
 * The dwell (see [DetailSeasonFocusDwellContractTest]) collapses a walk onto the
 * poster the viewer stops on. What was left was the shape of the reload itself:
 * `loadEpisodesForSeason` set `_episodes` to an empty list BEFORE it started, so
 * every settled switch published two `_episodes` values - empty, then filled -
 * and the row tore down and rebuilt its cards in between, re-requesting every
 * episode still from Coil. On a season the viewer had already visited the
 * answer was a 12h cache hit, which made the blank pure cost.
 *
 * The rule now: the previous season's episodes stay on screen until the new
 * season's land. A FAILED load still clears the row - the previous season's
 * episodes under this season's label would be a lie, and the error state needs
 * the room.
 */
class DetailSeasonNoBlankContractTest {

    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt") }

    private fun body(from: String, to: String) =
        viewModel.substringAfter(from).substringBefore(to)

    @Test
    fun `the TMDB branch does not empty the row before the fetch`() {
        val branch = body(
            "latestEpisodeSeasonRequest = season\n        // Same no-blank rule as the synthetic branch above",
            "val seasonEpisodes = tmdbRepository.getSeasonEpisodes"
        )
        assertTrue(
            "no synchronous emptyList before the launch: $branch",
            !branch.contains("_episodes.value = emptyList()") &&
                branch.contains("_episodesLoading.value = true")
        )
    }

    @Test
    fun `the synthetic branch does not either`() {
        val branch = body(
            "if (_isSyntheticDetail.value) {\n            latestEpisodeSeasonRequest = season",
            "viewModelScope.launch {"
        )
        assertTrue(
            "no synchronous emptyList in the synthetic branch: $branch",
            !branch.contains("_episodes.value = emptyList()") &&
                branch.contains("_episodeError.value = null")
        )
    }

    @Test
    fun `the publish still replaces and a failure still clears`() {
        assertTrue(
            "the new season's list is published over the old one",
            viewModel.contains("_episodes.value = applyAirDateCorrections(seasonEpisodes, season)")
        )
        val failure = viewModel
            .substringAfter("} catch (e: Exception) {\n                Log.e(\"KBStream\", \"episodes load failed")
            .substringBefore("} finally {")
        assertTrue(
            "and a failed load does not leave the previous season's episodes up: $failure",
            failure.contains("_episodes.value = emptyList()") &&
                failure.contains("_episodeError.value = e.message")
        )
    }

    @Test
    fun `a different title still starts from no episodes`() {
        val reset = viewModel
            .substringAfter("_isSyntheticDetail.value = false\n        _airDateCorrections.value = AirDateCorrections.NONE")
            .substringBefore("_inProgressByStreamId.value = emptyMap()")
        assertTrue(
            "opening another title is not a season switch: $reset",
            reset.contains("_episodes.value = emptyList()")
        )
    }

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: " + file.absolutePath)
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
