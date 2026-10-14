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
        // MPV's NEXT button is the second one there: it used to call its own
        // arithmetic target straight into launchNextEpisode, so a caught-up
        // show could still be sent to the invented season-end episode by one
        // press, one press after the panel had correctly offered the
        // recommendations instead.
        assertEquals(
            "MPV chains from the panel decision and its Next button",
            2,
            Regex("airedNextEpisodeTarget\\(").findAll(source(MPV)).count()
        )
        assertTrue(
            "and the Next button is one of them",
            source(MPV).substringAfter("override fun onChromeNext() {")
                .substringBefore("override fun onChromeOpenSourcePicker")
                .contains("airedNextEpisodeTarget(")
        )
    }

    @Test
    fun `every engine tells the gate which season the session is in`() {
        // The gate has to distinguish the invented season-end jump from a chain
        // inside the season playing, and only the caller knows which season
        // that is: the target itself cannot say ((19, 1) is the fabrication for
        // a session in S18 and an ordinary random pick for one in S1). A call
        // site that forgets it silently loses the check.
        listOf(EXO, MPV, EXTERNAL).forEach { path ->
            val src = source(path)
            val calls = Regex("airedNextEpisodeTarget\\(").findAll(src).count()
            val seasons = Regex("sessionSeason = season").findAll(src).count()
            assertEquals(
                "$path passes the session's season at every gate call",
                calls,
                seasons
            )
        }
    }

    @Test
    fun `every engine still invents the jump, so the gate stays the only thing confirming it`() {
        // The arithmetic is deliberate and stays in the players - it is where
        // `episode 1` comes from - and what makes it safe is that each engine
        // hands it to the gate with its own season before using the result.
        listOf(EXO, MPV, EXTERNAL).forEach { path ->
            assertTrue(
                "$path no longer computes the season-end jump",
                source(path).contains("+ 1) to 1")
            )
        }
    }

    private companion object {
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val EXO = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    }
}
