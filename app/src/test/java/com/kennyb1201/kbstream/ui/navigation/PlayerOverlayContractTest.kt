package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the player overlay extraction (navcompose F0) must NOT change.
 *
 * The spec's first "do not guess" risk was how the shell leaves `Screen.Player`
 * when the player Activity finishes. It is now pinned by reading the call site:
 * the exit is an `ActivityResultContracts.StartActivityForResult` callback that
 * reads the two PERSISTED handoffs (ActorHandoff, NextEpisodeResult) before it
 * decides anything, and falls back to `stableBackDestination(current.returnTo)`
 * for an ordinary exit. There is no `onResume` check and no launch-mode trick.
 *
 * `Screen.Player` is the last thing F0 moves: it stops being a `Screen` value
 * and becomes a shell-level overlay (`playerLaunch`), drawn beside the
 * AnimatedContent instead of inside it. That is a ~700-line block move, and the
 * whole point of this test is that the move cannot quietly become a rewrite:
 * the overlay keeps the same launcher, the same two handoff reads in the same
 * order, and the same returnTo fallback - including the Sports-sheet return,
 * which is the player's own `returnTo` carrying `Sports(detailGameId = ...)`.
 *
 * Read from the source: these are launcher callbacks and persisted-state reads,
 * which need a device and a player Activity to exercise.
 */
class PlayerOverlayContractTest {

    private val shell: String by lazy { normalized(stripComments(source(MAIN))) }
    private val routes: String by lazy { normalized(stripComments(source(ROUTES))) }

    /** The player arm of the shell's renderer, and nothing above or below it. */
    private val playerArm: String by lazy {
        shell
            .substringAfter("is Screen.Player -> {")
            .substringBefore("val playerSplash = current as? Screen.Player")
    }

    @Test
    fun `the player is not a destination, even while it still renders from the screen state`() {
        assertTrue(
            "the route table answers null for the player, which is what keeps the " +
                "overlay out of the NavHost",
            routes.contains("is Screen.Player -> null")
        )
        assertTrue(
            "and the shell still identifies its splash from the current value, " +
                "which is the seam the overlay takes over",
            shell.contains("val playerSplash = current as? Screen.Player")
        )
    }

    @Test
    fun `the exit is the activity result, not a guess on resume`() {
        assertTrue(
            "the player is launched and its exit observed through one " +
                "StartActivityForResult launcher",
            playerArm.contains("rememberLauncherForActivityResult(") &&
                playerArm.contains("ActivityResultContracts.StartActivityForResult()")
        )
        assertTrue(
            "and the launch is what starts the player Activity",
            shell.contains("playerResultLauncher.launch(")
        )
        assertTrue(
            "the launch itself is keyed on the URL, so a re-target for the same " +
                "URL cannot relaunch it",
            shell.contains("LaunchedEffect(current.url)")
        )
    }

    @Test
    fun `the exit reads both persisted handoffs, and the actor one first`() {
        val actor = playerArm.indexOf("ActorHandoff.consumePersisted(context)")
        val next = playerArm.indexOf("NextEpisodeResult.consumePersisted(context)")
        assertTrue(
            "both handoffs are read from the store the player wrote before it " +
                "finished, which is what survives the OS recreating this activity",
            actor >= 0 && next >= 0
        )
        assertTrue(
            "the actor ticket is read first: it is the one result whose " +
                "destination the shell may not still hold",
            actor in 0 until next
        )
    }

    @Test
    fun `an ordinary exit still lands on the sanitized returnTo`() {
        assertTrue(
            "the fallback is the same sanitized destination the Back table " +
                "pins, so the Sports sheet reopens and autoplay can never re-fire",
            playerArm.contains("stableBackDestination(current.returnTo)")
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
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
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val ROUTES = "com/kennyb1201/kbstream/ui/navigation/NavRoutes.kt"
    }
}
