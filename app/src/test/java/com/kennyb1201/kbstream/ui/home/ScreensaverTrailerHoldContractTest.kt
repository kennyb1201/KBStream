package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the screensaver's arm gate learns that a trailer is playing, and where
 * the idle clock learns that it stopped.
 *
 * The rules themselves are behaviour tests (`ScreensaverTrailerHoldTest`). This
 * pins the wiring, because every link in it is silently revertable and none of
 * them fails loudly:
 *
 *  - the playing state is the PLAYER's (mounted + first frame), reported up from
 *    the hero - not "a trailer key exists", which would hold the gate on a
 *    held or settled trailer and never arm again;
 *  - the gate has to read it, or the overlay arms over the trailer exactly as
 *    reported;
 *  - the falling edge has to restart the clock, or the overlay arms the instant
 *    the trailer ends (the wait was measured from a press minutes ago);
 *  - and the suppression path that stops a trailer under a cover must stay as
 *    it is: this fix is about not arming, not about stopping.
 */
class ScreensaverTrailerHoldContractTest {

    private val root: File by lazy { findSourceRoot() }

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
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val home = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    private val rules = source("com/kennyb1201/kbstream/ui/home/ScreensaverRules.kt")

    /** The hero's own body: from its declaration to the artwork alias's file end. */
    private fun heroBody(): String {
        val start = home.indexOf("private fun HomeHero(")
        assertTrue("HomeHero is missing", start >= 0)
        val end = home.indexOf("private fun HomeHeroHost(", start)
        assertTrue("HomeHeroHost is missing", end > start)
        return squash(home.substring(start, end))
    }

    /** The screensaver block: from the arm gate to exitScreensaver. */
    private fun gateBlock(): String {
        val start = home.indexOf("val screensaverArmed =")
        assertTrue("the arm gate is missing", start >= 0)
        val end = home.indexOf("fun exitScreensaver()", start)
        assertTrue("exitScreensaver is missing", end > start)
        return squash(home.substring(start, end))
    }

    @Test
    fun `the playing state is the player's, not the trailer key's`() {
        val hero = heroBody()
        assertTrue(
            "the hero reports the rule's own answer, from what is mounted and drawn: $hero",
            hero.contains(
                "val trailerOnScreen = heroTrailerPlaying( mounted = resolvedTrailer != null, " +
                    "frameRendered = trailerFrameShown )"
            )
        )
        assertTrue(
            "the first frame is what makes a mounted source a playing trailer",
            hero.contains("onFirstFrame = { firstFrameRendered = true trailerFrameShown = true }")
        )
        assertTrue(
            "and a hero leaving mid-trailer must not leave the gate held",
            hero.contains("onDispose { onTrailerPlayingChange(false) }")
        )
        assertFalse(
            "a resolved trailer key is not playback: that answer never drops on a hold",
            hero.contains("heroTrailerPlaying( mounted = trailerKey")
        )
        assertTrue(
            "the rule itself is mounted AND painted",
            squash(rules).contains("): Boolean = mounted && frameRendered")
        )
    }

    @Test
    fun `Home holds the gate on a playing trailer`() {
        assertTrue(
            "Home owns the reported state",
            squash(home).contains("var heroTrailerPlaying by remember { mutableStateOf(false) }")
        )
        assertTrue(
            "and receives it from the hero it draws",
            squash(home).contains("onTrailerPlayingChange = { heroTrailerPlaying = it }")
        )
        assertTrue(
            "the gate reads it",
            gateBlock().contains("browseShortcutMenu == null && !heroTrailerPlaying")
        )
    }

    @Test
    fun `the falling edge restarts the wait, and only the falling edge`() {
        val block = gateBlock()
        assertTrue(
            "the reset goes through the rule, with the previous state: $block",
            block.contains("screensaverLastKeyAt = screensaverIdleClockAfterTrailer(") &&
                block.contains("wasPlaying = screensaverTrailerWasPlaying,") &&
                block.contains("playing = heroTrailerPlaying,")
        )
        assertTrue(
            "it runs once per edge, guarded by the previous state: $block",
            block.contains("SideEffect { if (screensaverTrailerWasPlaying != heroTrailerPlaying) {") &&
                block.contains("screensaverTrailerWasPlaying = heroTrailerPlaying }")
        )
        assertFalse(
            "and never on every recomposition: an unguarded write would drift the deadline",
            block.contains("SideEffect { screensaverLastKeyAt = screensaverIdleClockAfterTrailer(")
        )
        assertTrue(
            "and the rule only writes on a true → false edge",
            squash(rules).contains(
                "): Long = if (wasPlaying && !playing) nowMs else lastKeyAtMs"
            )
        )
    }

    @Test
    fun `the stop path under a cover is untouched`() {
        assertTrue(
            "the overlay still stops a trailer it covers through the same autoplay switch",
            squash(home).contains("trailerSuppressed = screensaverVisible")
        )
        assertTrue(
            "and the hero still receives it",
            squash(home).contains("trailerSuppressed: Boolean = false")
        )
    }
}
