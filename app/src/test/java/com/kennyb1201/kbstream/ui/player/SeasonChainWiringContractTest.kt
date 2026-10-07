package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two wiring facts behind the reported "S4 finale showed Because you watched
 * while S5 was already out" bug, neither reachable from a unit test that only
 * sees pure functions:
 *
 *  1. The detail route is the one that used to drop the season's episode
 *     count, and the count is what the player's own `(s + 1) episode 1`
 *     arithmetic needs. [NextEpisodeAiringTest] pins the gate's decision; this
 *     pins that the detail route now feeds it the count as well, so the fast
 *     path works there too instead of relying on the gate's fallback alone.
 *  2. The gate only helps if every engine asks it. All three players chain
 *     forward on their own, so each must call [airedNextEpisodeTarget] rather
 *     than decide "next episode" from arithmetic alone.
 *
 * Read from the source: both are launch/compose wiring that needs a device to
 * stage.
 */
class SeasonChainWiringContractTest {

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

    @Test
    fun `the detail route hands the player the season's episode count`() {
        val main = source(MAIN)
        // The count lives on the StreamsTarget the detail screen built; the
        // PendingPlay this route creates is what carries it into the player's
        // launch extras (see the autoselect branch of onNavigateStreams).
        assertTrue(
            "the detail route dropped the count, so a finale launched from it " +
                "asked the player for an episode the season does not list",
            main.contains("totalEpisodesInSeason = target.totalEpisodesInSeason,")
        )
        assertTrue(
            "and the count is still what reaches the player as an extra",
            main.contains("current.totalEpisodesInSeason?.let") &&
                main.contains("putExtra(\"total_episodes_in_season\", it)")
        )
    }

    @Test
    fun `every engine chains through the air-date gate`() {
        // ExoPlayer, MPV and External each compute their own "next episode";
        // none of them may use that arithmetic unguarded, or the finale from
        // that engine falls through to recommendations again.
        listOf(EXO, MPV, EXTERNAL).forEach { path ->
            val calls = Regex("airedNextEpisodeTarget\\(").findAll(source(path)).count()
            assertTrue(
                "$path must gate its end-of-playback chain on the air-date rule",
                calls > 0
            )
        }
        // A count, not just "somewhere": the reported bug was one launch route
        // deciding for itself, and losing a call site is how that returns.
        assertEquals(
            "ExoPlayer chains forward from the panel decision and the Next handoff",
            2,
            Regex("airedNextEpisodeTarget\\(").findAll(source(EXO)).count()
        )
    }

    private companion object {
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val EXO = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    }
}
