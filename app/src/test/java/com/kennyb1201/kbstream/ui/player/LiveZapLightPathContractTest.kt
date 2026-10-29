package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-zap light path, pinned at the source.
 *
 * Every channel change used to funnel through `recreatePlayer` with a
 * `SOURCE_SWITCH_SETTLE_MS` wait: a full ExoPlayer teardown, up to six seconds
 * of decoder grace, then a rebuild. The fix runs the change on the player that
 * is already bound to the Surface - no teardown, no settle.
 *
 * This is wiring, not arithmetic, so a unit test cannot exercise it without a
 * TV in the room. What it can check is that the branch exists, that the light
 * path never re-attaches `PlayerView.player` (touching it is precisely the
 * teardown the path exists to avoid), and that the zap is measured separately
 * from the createPlayer-anchored `playback.source_ready` - after a light zap
 * that block never runs, so without its own span a channel change is invisible
 * in diagnostics.
 */
class LiveZapLightPathContractTest {

    private fun readSource(path: String): String {
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

    /**
     * The text of the function starting at [signature] up to the next member.
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(signature: String): String {
        val src = readSource(PLAYER)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private val lightPath: String by lazy { functionBody("private fun lightSwitchLiveChannel() {") }

    @Test
    fun `a live switch with a player takes the light path, not a rebuild`() {
        val body = functionBody("fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {")
        assertTrue(
            "a live channel change must not tear the player down",
            body.contains("if (isLiveChannel && exoPlayer != null) {")
        )
        assertTrue(body.contains("lightSwitchLiveChannel()"))
        // The rebuild branch must survive for VOD and for a live switch with no
        // player yet (the very first load still builds one).
        assertTrue(body.contains("recreatePlayer(settleMs = SOURCE_SWITCH_SETTLE_MS)"))
    }

    @Test
    fun `the light path re-feeds the same player instead of rebuilding it`() {
        assertTrue(lightPath.contains("player.setMediaItem("))
        assertTrue(lightPath.contains("player.prepare()"))
        assertTrue(lightPath.contains("player.playWhenReady = true"))
        // Rebuilding here would re-pay the teardown + settle the path exists to
        // remove.
        assertFalse(lightPath.contains("recreatePlayer("))
        // The Surface must stay attached: handing PlayerView a new player is the
        // teardown. The player is reused as-is.
        assertFalse(lightPath.contains("playerView.player"))
    }

    @Test
    fun `the light path clears stale rebuilds and the live watchdog`() {
        assertTrue(lightPath.contains("liveWatchdogToken++"))
        assertTrue(lightPath.contains("playerGeneration++"))
        assertTrue(lightPath.contains("player.removeListener(cueHandler)"))
        assertTrue(lightPath.contains("SubtitleCueHandler().also { player.addListener(it) }"))
    }

    @Test
    fun `the light path and a full build share one MediaItem shape`() {
        assertTrue(
            readSource(PLAYER).contains("private fun newMediaItemBuilder(mimeType: String?)")
        )
        assertTrue(
            "createPlayer must build its item through the shared helper",
            readSource(PLAYER).contains("val mediaItemBuilder = newMediaItemBuilder(mimeType)")
        )
        assertTrue(
            "the light path must reuse the same helper",
            lightPath.contains("newMediaItemBuilder(")
        )
    }

    @Test
    fun `a zap is measured from the channel change to the next READY`() {
        val src = readSource(PLAYER)
        assertTrue(src.contains("private var zapTraceStartMs = 0L"))
        val tune = functionBody(
            "private fun tuneToChannel(index: Int, channel: LiveChannelZapRegistry.ZapChannel) {"
        )
        assertTrue(
            "the zap clock starts before the banner paints",
            tune.contains("zapTraceStartMs = System.currentTimeMillis()")
        )
        assertTrue("a re-tune to the same URL drops the clock", tune.contains("zapTraceStartMs = 0L"))
        assertTrue(src.contains("\"playback.zap_ready\""))
    }

    /**
     * The light path re-anchors the startup trace, or a zap is measured against
     * the session's FIRST channel.
     *
     * `startupTraceStartMs` / `firstReadyAtMs` used to reset only in
     * `createPlayer`, and the light path never reaches it: the READY block's
     * `firstReadyAtMs == 0L` stamp had already fired on entry channel, so
     * `playback.first_frame` recorded every later zap against an anchor from
     * another channel. That made per-zap latency unreadable from diagnostics.
     */
    @Test
    fun `the light path re-anchors the startup trace per zap`() {
        assertTrue(lightPath.contains("startupTraceStartMs = System.currentTimeMillis()"))
        assertTrue(lightPath.contains("firstReadyAtMs = 0L"))
        // It must sit beside the per-zap frame resets it complements: an anchor
        // reset that a later reset overwrote would be no anchor at all.
        val frameReset = lightPath.indexOf("firstFrameRendered = false")
        val anchorReset = lightPath.indexOf("startupTraceStartMs = System.currentTimeMillis()")
        assertTrue("the frame and anchor resets must be in the same reset block", frameReset >= 0 && anchorReset > frameReset)
        // Still no rebuild: the anchor is reset, not the player.
        assertFalse(lightPath.contains("recreatePlayer("))
    }

    /**
     * The zap leg reaches the report beside the other two startup figures. The
     * source→ready span starts when the player was BUILT, which on a light zap
     * is not when the viewer changed channel - so without this number a slow
     * zap and a slow decoder cannot be told apart.
     */
    @Test
    fun `diagnostics report the zap to ready leg`() {
        val diagnostics = readSource(DIAGNOSTICS)
        val body = diagnostics.substringAfter("private fun playbackLine(): String? {")
            .substringBefore("\n    }")
        assertTrue(body.contains("PerfTrace.latestByPrefix(\"playback.zap_ready\")"))
        assertTrue(body.contains("\" zap→ready=\""))
        // A report with only a zap sample is still a report.
        assertTrue(body.contains("zapReady == null"))
    }

    private companion object {
        const val PLAYER =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val DIAGNOSTICS =
            "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
    }
}
