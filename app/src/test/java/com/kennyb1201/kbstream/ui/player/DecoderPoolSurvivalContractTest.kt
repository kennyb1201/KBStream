package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder pool is not fed by the player's own recovery.
 *
 * Field evidence, TCL / Android 12 / 32-bit / 1.7 GB on a 53.6 GB 67 Mbps 4K
 * file: 12s to ready, no first frame, nine player rebuilds, five
 * OMX_ErrorInsufficientResources (0x80001000) events, the "run out of video
 * decoder resources" card — and the same file playing clean after a force-stop.
 * The box decodes it; the pool wedges under the session's own rebuild storm and
 * recovers on process death. Two mechanisms were feeding that storm:
 *
 *  1. the rebuffer downshift counted rebuffers blindly and switched sources,
 *     and every switch is a rebuild is a decoder request — when the POOL is the
 *     problem, every switch adds pressure;
 *  2. on 0x80001000 the first move was "try a smaller source", another rebuild
 *     against the same wedged pool, with the MPV software fallback (the designed
 *     escape hatch, and the one engine that needs no MediaCodec at all) firing
 *     only after the source ladder was exhausted.
 *
 * This pins the shape a device cannot show us: which branch reads which fact,
 * what it is allowed to call, and in what order. The rules themselves are
 * covered by [DecoderPoolHealthTest], [RebuildBudgetTest] and
 * [PlaybackRecoveryRulesTest]; the lane gate by
 * [com.kennyb1201.kbstream.data.player.StreamFetchLanesTest].
 */
class DecoderPoolSurvivalContractTest {

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

    private fun region(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor missing: $from", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("end anchor missing: $to", end > start)
        return text.substring(start, end)
    }

    private val native = source(NATIVE)

    // --- 0x80001000 ---

    @Test
    fun `a wedged pool is read from the process-wide fact, not guessed`() {
        assertTrue(
            "the exhaustion branch must ask whether a frame has EVER rendered in this " +
                "process — that is what separates a wedged pool from a file the box " +
                "cannot decode",
            exhaustionBranch.contains("DecoderPoolHealth.everRenderedFirstFrame")
        )
        assertTrue(
            "and it must record the exhaustion so the rebuffer downshift can tell pool " +
                "pressure from a slow source",
            exhaustionBranch.contains("DecoderPoolHealth.noteExhaustion()")
        )
        assertTrue(
            "the fact is latched by the first painted frame",
            native.contains("DecoderPoolHealth.noteFirstFrame()")
        )
    }

    @Test
    fun `a wedged pool hands to the software engine with zero switches and zero rebuilds`() {
        val wedged = region(
            exhaustionBranch,
            "if (DecoderPoolHealth.everRenderedFirstFrame) {",
            "// A fresh pool refused the first configure"
        )
        assertTrue(
            "the backup engine is the one decoder path that needs no MediaCodec, so it " +
                "is the move for a pool this process has wedged",
            wedged.contains("handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)")
        )
        listOf("tryNextSource(", "switchToSource(", "scheduleRetry()")
            .forEach { call ->
                assertFalse(
                    "a wedged pool must not be asked for a decoder again — found $call " +
                        "in the branch that knows the pool is the problem",
                    wedged.contains(call)
                )
            }
        // One rebuild IS allowed here since 2026-11-28 (F2), and exactly one:
        // the same source after the full vendor window. It is not a ladder step
        // and it is not immediate - an un-settled rebuild is what wedges the pool.
        assertEquals(
            "the wedged branch may rebuild exactly once, for the full-window retry",
            1,
            wedged.split("recreatePlayer(").size - 1
        )
        assertTrue(
            "and that rebuild must wait the full vendor window, not the 6s settle " +
                "it already spent",
            wedged.contains("recreatePlayer(settleMs = DECODER_FULL_WINDOW_MS)")
        )
        assertTrue(
            "and it must be the branch that runs FIRST, before the smaller-source attempt",
            exhaustionBranch.indexOf("if (DecoderPoolHealth.everRenderedFirstFrame) {") <
                exhaustionBranch.indexOf("val switching = tryNextSource(")
        )
    }

    @Test
    fun `a first-configure exhaustion still tries exactly one smaller source`() {
        assertTrue(
            "the other case is a fresh pool refusing the first configure: one smaller " +
                "source there asks for the same decoder the same way it always would, so " +
                "it stays — bounded by its own flag",
            exhaustionBranch.contains("if (resourceExhausted && !decoderResourceFallbackDone) {")
        )
        assertEquals(
            "the one-attempt flag may only be set once in the branch",
            1,
            exhaustionBranch.split("decoderResourceFallbackDone = true").size - 1
        )
        assertTrue(
            "and it is still the platform-teardown-aware delay, not an immediate rebuild",
            exhaustionBranch.contains(
                "val switching = tryNextSource(\n" +
                    "                    delayMs = DECODER_RESOURCE_RETRY_DELAY_MS,"
            )
        )
        val afterSwitch = exhaustionBranch.indexOf("if (switching) return")
        assertTrue(
            "with no source left the session must not walk the six-attempt ladder",
            afterSwitch > 0
        )
        assertTrue(
            "the fallback that follows is the backup engine, then the card",
            exhaustionBranch.indexOf(
                "handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)",
                afterSwitch
            ) > afterSwitch
        )
        assertTrue(
            "the card is what is left when even that is refused",
            exhaustionBranch.indexOf("DECODER_EXHAUSTED_MESSAGE", afterSwitch) > afterSwitch
        )
    }

    // --- Every rebuild leaves the vendor decoder its window -----------------

    @Test
    fun `every mid-playback rebuild passes the decoder release settle`() {
        // The defect this pins: the settle existed and was correct, and only the
        // two source-replacement paths passed it. The retry ladder, both
        // watchdog stages, the P5 rebuilds, the subtitle and audio restates, the
        // DV strip and the foreground return all rebuilt ~0ms after releasing a
        // decoder, inside the vendor's window - which is how a TCL session hit
        // 0x80001000 twice mid-playback with frames already on screen.
        val calls = native.lines()
            .map { it.trim() }
            .filter {
                it.contains("recreatePlayer(") &&
                    !it.startsWith("*") &&
                    !it.startsWith("//") &&
                    !it.startsWith("private fun")
            }
        assertTrue("the rebuild call sites are missing entirely", calls.size >= 15)
        calls.forEach { call ->
            assertTrue(
                "a rebuild must leave the vendor decoder its release window - " +
                    "this one passes no settle: $call",
                call.contains("settleMs = ")
            )
        }
        assertTrue(
            "the common-case settle is still the field-proven 6s",
            native.contains("private const val DECODER_RELEASE_SETTLE_MS = 6_000L")
        )
        assertTrue(
            "and the full vendor window has a constant of its own",
            native.contains("private const val DECODER_FULL_WINDOW_MS = 15_000L")
        )
        assertFalse(
            "the name must no longer claim this is a source-switch value only",
            native.contains("SOURCE_SWITCH_SETTLE_MS")
        )
    }

    @Test
    fun `a settled rebuild shows something, and only one thing`() {
        val recreate = region(native, "private fun recreatePlayer(", "private fun createPlayerListener()")
        val waitAt = recreate.indexOf("if (wait > 0L) {")
        assertTrue("the settle wait branch must exist", waitAt >= 0)
        // Detaching the Surface takes the picture with it, so the wait cannot be
        // black. A recovery path's own notice (the retry ladder, both watchdog
        // stages, the DV strip) is that thing already; everything else gets the
        // splash rather than a second indicator beside one.
        assertTrue(
            "the wait must raise a backdrop when none is up",
            recreate.contains("if (!backdropUp) showSplash()")
        )
        assertTrue(
            "and it has to be up before the wait starts, not after it",
            recreate.indexOf("if (!backdropUp) showSplash()") in waitAt until recreate.indexOf("handler.postDelayed(")
        )
        assertTrue(
            "the wait is reported, because the full-window retry's line has to say what it paid",
            recreate.contains("return wait")
        )
    }

    // --- F2: one same-source retry after the full vendor window --------------

    @Test
    fun `a settled exhaustion retries the same source once, after the full window`() {
        val wedged = region(
            exhaustionBranch,
            "if (DecoderPoolHealth.everRenderedFirstFrame) {",
            "// A fresh pool refused the first configure"
        )
        assertTrue(
            "the retry must be one-shot for the source",
            wedged.contains("if (!decoderFullWindowRetryDone && claimFullWindowRetryBudget()) {")
        )
        assertTrue(
            "the flag has to be spent before the rebuild, or a failed create would retry again",
            wedged.indexOf("decoderFullWindowRetryDone = true") in 0 until
                wedged.indexOf("recreatePlayer(settleMs = DECODER_FULL_WINDOW_MS)")
        )
        assertTrue(
            "it waits the full vendor window, not the 6s settle that just failed",
            wedged.contains("recreatePlayer(settleMs = DECODER_FULL_WINDOW_MS)")
        )
        assertFalse(
            "it is the SAME source: no ladder step, no switch, from a branch that " +
                "already knows another source asks the same pool the same question",
            wedged.contains("tryNextSource(") || wedged.contains("switchToSource(")
        )
        // The retry is a rebuild, so the cap bounds it as well (F2's budget rule).
        assertTrue(
            "the retry spends this session's rebuild budget",
            wedged.contains("claimFullWindowRetryBudget()")
        )
        val claim = region(native, "private fun claimFullWindowRetryBudget()", "// --- Playback Ended ---")
        assertTrue(claim.contains("rebuildBudget.allows(causeKey)"))
        assertTrue(claim.contains("rebuildBudget.record(causeKey)"))
        // The measurement: a paste shows the wait that was applied, so 3s vs 6s vs
        // 15s is answered by the next capture instead of another build.
        assertTrue(
            "the retry's trace line must record the wait it applied",
            wedged.contains("(waited \${waitedMs}ms)")
        )
        assertTrue(
            "and the notice stays up across the wait",
            wedged.contains("reconnectingContainer.visibility = View.VISIBLE")
        )
        // The other half of the measurement: a paste with the retry line and no
        // handoff must be able to say whether the session came back.
        val firstFrame = region(
            native,
            "private fun markFirstFrameRendered() {",
            "private fun scheduleP5PrimeRebuild() {"
        )
        assertTrue(
            "a recovered full-window retry must say so, once",
            firstFrame.contains("if (decoderFullWindowRetryDone && !decoderFullWindowRetryRecovered) {") &&
                firstFrame.contains("cause = \"full-window retry recovered\"")
        )
    }

    @Test
    fun `the retry is per source, reset with the other one-shot decoder flags`() {
        val switch = region(
            native,
            "fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {",
            "private fun lightSwitchLiveChannel()"
        )
        assertTrue(
            "a new source has not spent its retry",
            switch.contains("decoderFullWindowRetryDone = false")
        )
        assertEquals(
            "only one place may spend it",
            1,
            native.split("decoderFullWindowRetryDone = true").size - 1
        )
    }

    @Test
    fun `a second exhaustion after the full window goes straight to the backup engine`() {
        val guard = region(
            native,
            "if (resourceExhausted && decoderFullWindowRetryDone) {",
            "if (decoderFailure) {"
        )
        assertFalse(
            "the window has been waited out; the probe below asks the same wedged pool",
            guard.contains("decoderFailureRetried = true")
        )
        assertFalse(
            "no further rebuild may be part of this outcome",
            guard.contains("recreatePlayer(")
        )
        assertTrue(
            "the backup engine is the step that is left",
            guard.contains("handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)")
        )
        assertTrue(
            "and the card is what is left when even that is refused",
            guard.contains("DECODER_EXHAUSTED_MESSAGE")
        )
        assertTrue(
            "it must be checked before the decoder-failure ladder, not after it",
            native.indexOf("if (resourceExhausted && decoderFullWindowRetryDone) {") <
                native.indexOf("if (decoderFailure) {", native.indexOf("if (resourceExhausted && decoderFullWindowRetryDone) {"))
        )
    }

    // --- F3: the handoff frees the decoder it is leaving ---------------------

    @Test
    fun `the handoff releases the Exo player before the backup engine opens the file`() {
        val handoff = region(native, "private fun handOffToMpv(", "private fun switchPlayerManually()")
        val positionAt = handoff.indexOf("val position = carriedPositionMs()")
        val stopAt = handoff.indexOf("runCatching { exoPlayer?.stop() }")
        val releaseAt = handoff.indexOf("runCatching { exoPlayer?.release() }")
        val launchAt = handoff.indexOf("mpvFallbackLauncher.launch(")

        assertTrue("the position is carried first", positionAt in 0 until stopAt)
        assertTrue("stop() closes the loaders so the other engine can open the file", stopAt in 0 until releaseAt)
        assertTrue("and release() starts the vendor's release clock before the launch", releaseAt in 0 until launchAt)
        val between = handoff.substring(releaseAt, launchAt)
        assertTrue(
            "the field must be cleared, or onStop would see a live player again",
            between.contains("exoPlayer = null")
        )
        assertTrue(
            "and the release time is what a quick return's settle counts from",
            between.contains("playerReleasedAtMs = System.currentTimeMillis()")
        )
        // onStop still runs after the handoff, and must find nothing to release
        // twice: `exoPlayer?.release()` survives a null player.
        val stop = region(native, "override fun onStop() {", "override fun onDestroy() {")
        assertTrue(stop.contains("exoPlayer?.release()"))
        assertTrue(stop.contains("exoPlayer = null"))
    }

    // --- Rebuffer downshift ---

    @Test
    fun `a rebuffer inside the exhaustion window holds the source`() {
        val downshift = region(
            native,
            "private fun maybeDownshiftOnRebuffer(rebufferStartMs: Long, stalledMs: Long): Boolean {",
            "\n    /**\n     * Moves to the next ranked source."
        )
        assertTrue(
            "a stall inside the decoder-exhaustion window is the box not having released " +
                "a decoder yet, so it must be read as pool pressure",
            downshift.contains("if (DecoderPoolHealth.exhaustedRecently()) {")
        )
        assertTrue(
            "and the check must come before the stall is even counted, so a pool-pressure " +
                "stall cannot spend the downshift window",
            downshift.indexOf("DecoderPoolHealth.exhaustedRecently()") <
                downshift.indexOf("rebufferDownshift.record(now, stalledMs)")
        )
        assertTrue(
            "the switch itself must be unreachable while the pool is the problem",
            downshift.indexOf("DecoderPoolHealth.exhaustedRecently()") <
                downshift.indexOf("val switching = tryNextSource(")
        )
        assertFalse(
            "no rebuild belongs in a rebuffer handler at all",
            downshift.contains("recreatePlayer(")
        )
    }

    // --- Rebuild budget ---

    @Test
    fun `the ladder stops at the cap instead of rebuilding the same component`() {
        val retry = region(native, "private fun scheduleRetry() {", "\n    // --- Playback Ended ---")
        assertTrue(
            "the ladder must consult this session's rebuild budget before it posts another " +
                "rebuild",
            retry.contains("!rebuildBudget.allows(causeKey)")
        )
        assertTrue(
            "and the cap must be checked before the rebuild is queued, not after it ran",
            retry.indexOf("!rebuildBudget.allows(causeKey)") <
                retry.indexOf("handler.postDelayed(\n            retryRunnable")
        )
        assertTrue(
            "past the cap the session goes to the backup engine",
            retry.contains("if (!handOffToMpv(")
        )
        assertTrue(
            "and the card remains the fallback when that is refused",
            retry.contains("retryExhausted = true")
        )
        assertTrue(
            "a live channel is exempt: it has no backup engine and its reconnect loop is " +
                "deliberate",
            retry.contains("if (!isLiveChannel && causeKey != null && !rebuildBudget.allows(causeKey))")
        )
    }

    @Test
    fun `the budget is spent where the rebuild happens`() {
        val runnable = region(
            native,
            "private val retryRunnable = Runnable {",
            "    /**\n     * The ladder has rolled over instead of parking on the error card"
        )
        assertTrue(
            "the rebuild counts itself against its own cause, so two different problems " +
                "in one session each keep their own allowance",
            runnable.contains("PlaybackRecoveryRules.failureCauseKey(lastPlaybackError)")
        )
        assertTrue(runnable.contains("?.let { rebuildBudget.record(it) }"))
    }

    // --- The hero trailer's decoder ---

    @Test
    fun `the hero trailer's pooled player is quieted before this Activity decodes`() {
        val onCreate = native.indexOf("override fun onCreate(savedInstanceState: Bundle?) {")
        val release = native.indexOf("TrailerPlayerPool.releaseForReuse()")
        val firstPlayback = native.indexOf("private fun createPlayer(")
        assertTrue("the pool call must exist", release > 0)
        assertTrue(
            "the trailer player is application-scoped and a paused ExoPlayer keeps its " +
                "decoder: on a one-4K-decode box a live hero trailer is a concurrent claim " +
                "against this session's first configure",
            release > onCreate && release < firstPlayback
        )
    }

    // --- Fetch lanes ---

    @Test
    fun `heavy sources get fetch lanes and ordinary ones keep the single stream`() {
        val create = region(
            native,
            "private fun createPlayer(",
            "private fun recreatePlayer("
        )
        assertTrue(
            "the lane gate must read the bitrate the session knows",
            create.contains("declaredBitrateBps = streamBitrate,")
        )
        assertTrue(
            "and fall back to the source's own 4K claim only while no bitrate is known",
            create.contains("StreamRanker.resolutionRank(it) >= UHD_RESOLUTION_RANK")
        )
        assertTrue(
            "under the gate the app's one shared client is what reads the source",
            create.contains("val httpFactory: androidx.media3.datasource.DataSource.Factory =\n" +
                "            laneFactory ?: sharedHttpFactory")
        )
        assertTrue(
            "the shared client still carries the addon's own headers",
            create.contains("if (extraHeaders.isNotEmpty() && laneFactory == null) {")
        )
    }

    @Test
    fun `the lanes are per-playback and torn down with the session`() {
        val create = region(
            native,
            "private fun createPlayer(",
            "private fun recreatePlayer("
        )
        assertTrue(
            "a rebuild replaces the previous session's lanes instead of stacking pools",
            create.contains("lanePool?.release()")
        )
        val recreate = region(
            native,
            "private fun recreatePlayer(",
            "private fun createPlayerListener()"
        )
        assertTrue(
            "and the rebuild releases them before building the next player",
            recreate.indexOf("lanePool?.release()") < recreate.indexOf("createPlayer()")
        )
        val destroy = region(native, "override fun onDestroy() {", "private fun oneLaneHttpClient()")
        assertTrue(
            "nothing may keep a lane's socket or reader thread alive past the screen",
            destroy.contains("lanePool?.release()")
        )
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }

    /**
     * The whole 0x80001000 branch, from its guard to the missing-decoder branch
     * that follows it.
     */
    private val exhaustionBranch: String by lazy {
        region(
            native,
            "if (resourceExhausted && !decoderResourceFallbackDone) {",
            "// No decoder for this codec at all"
        )
    }
}
