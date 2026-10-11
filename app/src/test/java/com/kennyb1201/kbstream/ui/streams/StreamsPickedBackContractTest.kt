package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A playback launched by tapping a source in the streams picker is a MANUAL
 * pick, and backing out of it has to bring the picker back.
 *
 * The failure this pins: with auto-select on, the player-result callback's
 * normal-BACK branch sent every non-channel playback to
 * `stableBackDestination(current.returnTo)`. A manual pick builds that
 * `returnTo` from the picker's own (which deliberately never keeps a picker in
 * the chain), so back skipped the picker and dropped the user on Home or the
 * detail screen — the auto-select rule could not tell a pick made by hand from
 * an auto-play it started itself.
 *
 * The branch lives inside the navigation `when` of MainActivity, which no JVM
 * test can drive, so the wiring is pinned by reading the source — the same
 * pattern the other navigation contracts use. The four device cases in the
 * spec's verify list (auto-select on/off, tap through the picker, and the
 * channel back) remain the behavioural check.
 */
class StreamsPickedBackContractTest {

    private val main: String by lazy { source(MAIN) }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /**
     * The `is Screen.Streams -> {` branch, up to the player branch that follows.
     *
     * Both anchors carry the opening brace: `stableBackDestination` also spells
     * these two types (its own `when` at the top of the file), and a bare type
     * name would slice that helper instead of the screen branch.
     */
    private fun streamsBranch(): String {
        val start = main.indexOf("is Screen.Streams -> {")
        assertTrue("the streams branch not found", start >= 0)
        val end = main.indexOf("is Screen.Player -> {", start)
        assertTrue("the player branch not found after it", end > start)
        return main.substring(start, end)
    }

    /** The normal-BACK exit of the player-result callback, up to the player body. */
    private fun backExit(): String {
        val start = main.indexOf("// Normal BACK exit from player")
        assertTrue("the back-exit branch not found", start >= 0)
        val end = main.indexOf("LaunchedEffect(current.url)", start)
        assertTrue("the end of the player branch not found", end > start)
        return main.substring(start, end)
    }

    @Test
    fun `Screen_Player carries the manual-pick flag and defaults it off`() {
        assertTrue(
            "the flag has to exist on Screen.Player, and default false so every existing " +
                "launch keeps its behavior",
            main.contains("val pickedFromStreams: Boolean = false,")
        )
    }

    @Test
    fun `only the picker's own tap opts in`() {
        assertEquals(
            "exactly one construction site is a manual pick: the streams picker's",
            1,
            Regex("pickedFromStreams = true").findAll(main).count()
        )
        assertTrue(
            "and it is the picker's player, not a channel or auto-play launch",
            streamsBranch().contains("pickedFromStreams = true")
        )
    }

    @Test
    fun `auto-select only claims a back it started itself`() {
        val back = squash(backExit())
        assertTrue(
            "a channel still backs to its launch's own destination first",
            back.contains("current.parentType == \"channel\" ||")
        )
        assertTrue(
            "auto-select must not claim the back when the picker launched the playback",
            back.contains("(AppPreferences.getAutoSelectStream(context) && !current.pickedFromStreams)")
        )
        assertEquals(
            "the flag is consulted exactly once, in that gate",
            1,
            Regex("!current\\.pickedFromStreams").findAll(main).count()
        )
    }

    @Test
    fun `the rebuilt picker is reused, and the flag is why it is needed`() {
        val back = squash(backExit())
        assertTrue(
            "the else branch still rebuilds the picker for the target",
            back.contains("screen = Screen.Streams(")
        )
        assertTrue(
            "the rebuilt picker keeps its own back destination, never the player's",
            back.contains("returnTo = stableBackDestination(current.returnTo)")
        )
        assertTrue(
            "and the exited-target suppression still keeps it from re-auto-selecting",
            back.contains("(autoPlayedStreamKeys + exitedKey).distinct()")
        )
        assertTrue(
            "the picker's player deliberately keeps stableBackDestination as returnTo - " +
                "that is the whole reason the flag exists, so it must not be 'fixed' instead",
            streamsBranch().contains("returnTo = stableBackDestination(current.returnTo)")
        )
    }

    private companion object {
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
