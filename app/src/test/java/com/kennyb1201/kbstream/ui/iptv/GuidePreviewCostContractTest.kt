package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Guide's corner preview must not cost the grid its smoothness
 * (spec 2026-11-12, build 262).
 *
 * Reported: with the preview on, scrolling the guide is laggy and the preview's
 * own playback is laggy too; with it off, the scroll is smooth. That is the
 * decode, not the preview: the box is 240x135dp - 480x270 pixels on a TV at 2x
 * density - and the player was choosing whatever the stream offered, usually
 * 1080p, to fill it. Nine times the pixels the box can show, decoded on the same
 * 1.7-2GB box that is scrolling Compose cells, program data and logos at the
 * same time.
 *
 * The fix is F1: a ceiling on the preview player's track selection. F3 is the
 * explicit NON-goal and is pinned here so no later change "fixes" the lag by
 * pausing the preview while the grid moves - the preview is live and must keep
 * playing, video and audio, throughout a scroll. F2 is pinned as the negative
 * it is: the PlayerView never enters a Compose snapshot, so the only per-frame
 * work the preview owns is its own decode.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out this file, then JUnitCore with -Duser.dir at the repo root.
 */
class GuidePreviewCostContractTest {

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

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    /** The corner preview's own composable, from its declaration to the next one. */
    private val preview: String
        get() = between(
            source(GUIDE),
            "private fun GridPreview(",
            "@Composable\nprivate fun GridDetailBlock("
        )

    // ── F1: the decode is capped to what the box can show ──────────────────

    @Test
    fun `the preview player decodes only what its corner box can show`() {
        val body = preview

        assertTrue(
            "the preview declares a resolution ceiling",
            body.contains("setMaxVideoSize(PREVIEW_MAX_VIDEO_WIDTH_PX, PREVIEW_MAX_VIDEO_HEIGHT_PX)")
        )
        assertTrue(
            "and a bitrate ceiling with it: a 360p ceiling that still pulled a " +
                "high-bitrate rendition would keep the network and the parser busy",
            body.contains("setMaxVideoBitrate(PREVIEW_MAX_VIDEO_BITRATE)")
        )

        val guide = source(GUIDE)
        assertTrue(
            "the cap is 640x360: above the box's own 480x270 pixels at 2x density, " +
                "so the picture is downsampled rather than invented, and a ninth of 1080p",
            guide.contains("PREVIEW_MAX_VIDEO_WIDTH_PX = 640") &&
                guide.contains("PREVIEW_MAX_VIDEO_HEIGHT_PX = 360")
        )
        assertTrue(
            "and the bitrate is a normal 360p live rate, not a fixed high one",
            guide.contains("PREVIEW_MAX_VIDEO_BITRATE = 1_200_000")
        )
        assertTrue(
            "subtitles stay off exactly as before - the cap is additive",
            body.contains("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)")
        )
    }

    @Test
    fun `the cap belongs to the preview alone`() {
        assertFalse(
            "the full-screen player must not inherit a preview's resolution ceiling",
            source(PLAYER_TRACK_BRIDGE).contains("setMaxVideoSize")
        )
        assertFalse(
            "nor the main player activity",
            source(NATIVE_PLAYER).contains("setMaxVideoSize")
        )
        assertFalse(
            "nor the hero's inline trailer",
            source(HOME_SCREEN).contains("setMaxVideoSize")
        )
    }

    // ── F3: the preview keeps playing while the grid moves ────────────────

    @Test
    fun `nothing pauses or freezes the preview while the grid scrolls`() {
        val body = preview

        assertFalse(
            "the preview is live: no pause on scroll, no playWhenReady = false",
            body.contains("isScrollInProgress") ||
                body.contains(".pause()") ||
                body.contains("playWhenReady = false")
        )
        assertTrue(
            "it still starts playing on its own, with audio",
            body.contains("playWhenReady = true") && body.contains("player.volume = 1f")
        )

        val band = between(
            source(GUIDE),
            "private fun GridTopBand(",
            "private fun GridPreview("
        )
        assertTrue("the band still mounts the preview", band.contains("GridPreview("))
        assertFalse(
            "and the band does not gate it on a scroll state",
            band.contains("scroll") || band.contains("Scrolling")
        )
        assertTrue(
            "the preview still follows the LAST-WATCHED channel, not focus",
            between(source(GUIDE), "private fun GridTopBand(", "private fun GridPreview(")
                .contains("previewChannel?.takeIf { previewEnabled }")
        )
    }

    // ── F2: the preview's frames never invalidate the grid ────────────────

    @Test
    fun `the player view is built once and never enters a Compose snapshot`() {
        val body = preview

        assertTrue(
            "the PlayerView is constructed in the AndroidView factory",
            body.contains("AndroidView(") && body.contains("PlayerView(ctx)")
        )
        assertFalse(
            "with no update block: a rendered frame must never come back through " +
                "Compose and recompose the grid",
            body.contains("update = {")
        )
        assertFalse(
            "and no per-frame position read, which would drive a state write every " +
                "tick",
            body.contains("currentPosition") || body.contains("LaunchedEffect")
        )
    }

    private companion object {
        const val GUIDE = "com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt"
        const val PLAYER_TRACK_BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
