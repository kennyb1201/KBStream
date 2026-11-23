package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-heal's wiring (spec 2026-11-23, F4), as source.
 *
 * The behaviour that matters cannot be unit-tested without an Android context:
 * it reads Room, then unmarks through the ViewModel's own fan-out. What CAN be
 * pinned is the shape - it runs off a loaded listing, it only ever removes a
 * provably FUTURE date, it never touches a null-dated episode, and it unmarks
 * through the same path a manual unmark uses so the `settleRemovedKeys` fence
 * still applies.
 */
class DetailFutureMarkHealContractTest {

    private companion object {
        const val VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        const val HEAL_FN = "private suspend fun healFutureDatedMarks("
    }

    private val viewModel by lazy { source(VIEW_MODEL) }

    private val heal by lazy {
        val start = viewModel.indexOf(HEAL_FN)
        assertTrue("the heal must exist", start >= 0)
        viewModel.substring(start, viewModel.indexOf("fun markPreviousUnwatched(", start))
    }

    @Test
    fun `the heal runs off a listing that has actually loaded`() {
        // `autoLoadRelevantSeason` only STARTS the season read, so a call
        // beside it would run before there are any dates to check.
        assertFalse(
            "the heal must not be hooked where the listing does not exist yet",
            viewModel.substringAfter("autoLoadRelevantSeason(")
                .substringBefore("// 4. Handle Meta addon loading")
                .contains("healFutureDatedMarks(")
        )
        assertTrue(
            "it runs where the loaded season and its episodes are published",
            viewModel.contains(
                "_loadedSeason.value = season"
            ) &&
                viewModel.contains("healFutureDatedMarks(")
        )
        assertTrue(
            "and once per season per screen, not on every switch back",
            viewModel.contains("healedFutureMarkSeasons.add(\"\$imdbId:\$season\")")
        )
        assertTrue(
            "the loaded listing is what supplies the dates",
            heal.contains("episodes: List<ResolvedEpisode>")
        )
    }

    @Test
    fun `only a provably future date is healed`() {
        assertTrue(
            "the strictly-future rule, shared with the marking path",
            heal.contains("filter { DetailSeasonEpisodes.provablyFuture(it.airDate) }")
        )
        assertFalse(
            "a null date proves nothing, so it is never touched",
            heal.contains("airDate == null") || heal.contains("isNullOrBlank()")
        )
        assertTrue(
            "and only the loaded season's completed rows are candidates",
            heal.contains("rowSeason == season")
        )
    }

    @Test
    fun `it unmarks through the same fan-out a manual unmark uses`() {
        assertTrue(
            "the local delete + tracker slices + tracker removal, fenced",
            heal.contains("markSpecificEpisodesUnwatched(")
        )
        assertTrue(
            "one line per healed show, so a paste shows it ran",
            heal.contains("healed \${healed.size} future-dated marks parent=\$parentId")
        )
    }

    private val sourceRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }
}
