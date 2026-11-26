package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide preview pauses while a fullscreen player is up (2026-11-26).
 *
 * Field report: clicking a channel to watch left the preview playing under the
 * player - double audio. F13g assumed the guide leaves the composition when
 * playback starts; it does not. The player is an overlay arm above the host
 * (`MainActivity` composes it over the NavHost), so the guide stays composed,
 * `GridPreview`'s release never runs and its ExoPlayer keeps playing.
 *
 * The gate is [previewCanPlay]: foreground, no active player, not failed. This
 * file pins its truth table (the pause on a launch and the resume on a clear)
 * and the plumbing that carries the shell's launch down to the preview - the
 * part a JVM cannot observe on a real player.
 *
 * Against the previous behaviour this fails by construction: nothing passed a
 * player state down, and no gate existed.
 */
class GuidePreviewPlayerGateTest {

    // ── The rule ──────────────────────────────────────────────────────────

    @Test
    fun `an active player pauses the preview`() {
        assertFalse(
            "the reported double audio: the preview must be silent under the player",
            previewCanPlay(inForeground = true, playerActive = true, failed = false),
        )
    }

    @Test
    fun `clearing the player resumes it`() {
        assertTrue(
            "backing out of the player leaves the preview playing again",
            previewCanPlay(inForeground = true, playerActive = false, failed = false),
        )
    }

    @Test
    fun `a backgrounded app stays silent even when the player clears`() {
        assertFalse(
            "the two reasons compose: no resume behind the launcher",
            previewCanPlay(inForeground = false, playerActive = false, failed = false),
        )
        assertFalse(previewCanPlay(inForeground = false, playerActive = true, failed = false))
    }

    @Test
    fun `a failed preview never plays`() {
        assertFalse(
            "F13h: a dead feed stays stopped, whatever the other inputs say",
            previewCanPlay(inForeground = true, playerActive = false, failed = true),
        )
        assertFalse(previewCanPlay(inForeground = false, playerActive = false, failed = true))
    }

    // ── The plumbing ──────────────────────────────────────────────────────

    @Test
    fun `the shell's player launch is what reaches the guide`() {
        val main = source("com/kennyb1201/kbstream/MainActivity.kt")
        val guideArm = between(main, "is Screen.Guide ->", "onOpenSports = {")
        assertTrue(
            "the player overlay is the shell's state, read in the guide's arm",
            guideArm.contains("playerActive = navSeam.playerLaunch != null"),
        )
        assertEquals(
            "and that is the only place the guide is given it",
            1,
            Regex(Regex.escape("playerActive = navSeam.playerLaunch != null")).findAll(main).count(),
        )
    }

    @Test
    fun `the guide passes it down to the preview without changing the phone`() {
        val guide = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
        assertTrue(
            "the guide takes the input",
            between(guide, "fun GuideScreen(", "if (!FormFactor.isTv)")
                .contains("playerActive: Boolean = false,"),
        )
        assertTrue(
            "and hands it to the TV grid",
            between(guide, "GuideGridView(", "onPlay = { item ->")
                .contains("playerActive = playerActive,"),
        )
        assertFalse(
            "the phone guide has no preview, so its branch is untouched",
            between(guide, "GuideScreenMobile(", "return").contains("playerActive"),
        )
    }

    @Test
    fun `the grid carries it through the band into the preview`() {
        val grid = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
        assertTrue(
            "the grid takes the input",
            between(grid, "internal fun GuideGridView(", "headerFocusRequester: FocusRequester,")
                .contains("playerActive: Boolean = false,"),
        )
        assertTrue(
            "the band takes it",
            between(grid, "private fun GridTopBand(", "item: IptvChannelWithEpg?,")
                .contains("playerActive: Boolean,"),
        )
        assertTrue(
            "the preview takes it",
            between(grid, "private fun GridPreview(", "modifier: Modifier = Modifier,")
                .contains("playerActive: Boolean,"),
        )
        assertEquals(
            "band, preview, and the rule's own input - one per hop, no side channel",
            3,
            Regex(Regex.escape("playerActive = playerActive,")).findAll(grid).count(),
        )
    }

    // ── Source access (the module is the working dir under Gradle) ────────

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("marker missing: $start", from >= 0)
        val to = text.indexOf(end, from)
        assertTrue("marker missing after $start: $end", to > from)
        return text.substring(from, to)
    }

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
            "main source root not found walking up from " + System.getProperty("user.dir"),
        )
    }
}
