package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide preview is foreground audio (specs 2026-11-26).
 *
 * `GridPreview` builds an ExoPlayer with `playWhenReady = true` and releases it
 * in `DisposableEffect.onDispose` - which fires when the guide leaves the
 * composition, NOT when the activity stops and not when a fullscreen player
 * starts (that player is an overlay above the host, so the guide stays
 * composed). Backgrounding kept the audio playing over the launcher; playback
 * kept it playing under the player. This half pins the foreground side of the
 * one gate that now covers both; [GuidePreviewPlayerGateTest] pins the player
 * side and the plumbing.
 *
 * The truth table itself is [previewCanPlay], which runs on the JVM; what is
 * pinned here is the wiring: the lifecycle observer that feeds it, and the one
 * place that applies its answer to the player.
 *
 * Against the previous behaviour every lifecycle assertion here fails: there
 * was no observer and no gate.
 */
class GuidePreviewLifecycleContractTest {

    private val preview: String by lazy {
        between(
            source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt"),
            "private fun GridPreview(",
            "/**\n * F14: the focused program, in a block at the top",
        )
    }

    // ── The foreground input ──────────────────────────────────────────────

    @Test
    fun `the preview observes the lifecycle it is composed under`() {
        assertTrue(
            "the observer hangs off the current lifecycle owner",
            preview.contains("val lifecycleOwner = LocalLifecycleOwner.current"),
        )
        assertTrue(
            "and the effect is keyed on it, so it cannot outlive it",
            preview.contains("DisposableEffect(lifecycleOwner) {"),
        )
        assertTrue(
            "the observer is a LifecycleEventObserver",
            preview.contains("val observer = LifecycleEventObserver { _, event ->"),
        )
        assertTrue(
            "added on composition",
            preview.contains("lifecycleOwner.lifecycle.addObserver(observer)"),
        )
        assertTrue(
            "and removed in onDispose, so a grid close does not leave it behind",
            preview.contains("onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }"),
        )
    }

    @Test
    fun `a stop leaves the foreground and a start returns to it`() {
        assertTrue("the stop is handled", preview.contains("Lifecycle.Event.ON_STOP ->"))
        assertTrue(
            "and it clears the foreground flag rather than touching the player directly",
            preview.contains("Lifecycle.Event.ON_STOP -> inForeground = false"),
        )
        assertTrue("the start is handled", preview.contains("Lifecycle.Event.ON_START ->"))
        assertTrue(
            "and it sets the flag the gate reads",
            preview.contains("Lifecycle.Event.ON_START -> inForeground = true"),
        )
    }

    @Test
    fun `the foreground flag is the gate's first input`() {
        assertTrue(
            "one rule decides, and it is the shared one",
            preview.contains("val canPlay = previewCanPlay("),
        )
        assertTrue(
            "fed the lifecycle's answer",
            preview.contains("inForeground = inForeground,"),
        )
        assertTrue(
            "the player's state and the failure flag",
            preview.contains("playerActive = playerActive,") && preview.contains("failed = failed,"),
        )
    }

    @Test
    fun `the gate's answer is what the player is set to`() {
        assertTrue(
            "the one write of playWhenReady outside creation and error",
            preview.contains("if (player.playWhenReady != canPlay) player.playWhenReady = canPlay"),
        )
    }

    @Test
    fun `a start is not a release`() {
        val gate = preview.substringAfter("F13g (2026-11-26)")
        assertFalse(
            "pausing must not release: coming back must not re-buffer",
            gate.substringBefore("Box(").contains("release()"),
        )
        assertEquals(
            "the only release is still the composition's own onDispose",
            1,
            Regex("player\\.release\\(\\)").findAll(preview).count(),
        )
    }

    // ── What the fix must not change ──────────────────────────────────────

    @Test
    fun `the preview still starts playing, with sound`() {
        assertTrue(preview.contains("player.setMediaItem(MediaItem.fromUri(channel.streamUrl))"))
        assertTrue(preview.contains("player.prepare()"))
        assertTrue(preview.contains("player.playWhenReady = true"))
        assertTrue(preview.contains("player.volume = 1f"))
    }

    @Test
    fun `the failed path is unchanged`() {
        assertTrue(
            "F13h: an error marks the preview failed and stops the player",
            preview.contains("failed = true") && preview.contains("runCatching { player.stop() }"),
        )
        assertTrue(
            "the still is what a failed preview draws",
            preview.contains("if (!failed && previewPlayer != null)"),
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
