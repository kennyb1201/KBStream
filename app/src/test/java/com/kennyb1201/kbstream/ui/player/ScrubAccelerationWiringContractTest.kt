package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held LEFT/RIGHT with the overlay down seeks faster, in both engines.
 *
 * The ramp itself is arithmetic and is pinned by [ScrubAccelerationTest]; what
 * this pins is that the two activities actually read it, because a ramp nobody
 * calls is the exact way "make holding go faster" silently regresses - the
 * numbers stay right, the feature stops existing, and no unit test notices.
 *
 * The shape that must survive on each side:
 *
 *  - ExoPlayer: the scrub tick advances by `ScrubAcceleration.stepFor`, on the
 *    shared [ScrubAcceleration.TICK_MS] cadence and past the shared
 *    [ScrubAcceleration.HOLD_START_MS] window, while the quick press stays a
 *    plain ten-second step (the contract [ScrubPreviewsRemovedContractTest]
 *    already holds).
 *  - libmpv: the overlay-down LEFT/RIGHT press opens an accelerated scrub
 *    instead of only stepping ten seconds and raising the overlay, so the
 *    overlay stays down for a hold - which is the entire point of the request -
 *    and the release is what lands it.
 *
 * This is wiring inside Android activities, so a device cannot be avoided here;
 * what a contract test can pin is the shape and the order.
 */
class ScrubAccelerationWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PLAYER = "com/kennyb1201/kbstream/ui/player/"
        const val NATIVE = PLAYER + "NativePlayerActivity.kt"
        const val MPV = PLAYER + "MpvPlayerActivity.kt"
    }

    // ── The ExoPlayer engine ────────────────────────────────────────────

    @Test
    fun `the main player's scrub tick advances on the shared ramp`() {
        val native = source(NATIVE)
        assertTrue(
            "the scrub tick must advance by the shared ramp",
            native.contains("ScrubAcceleration.stepFor(scrubHeldMs)")
        )
        assertTrue(
            "and it must advance on the shared cadence",
            native.contains("scrubHandler.postDelayed(this, ScrubAcceleration.TICK_MS)")
        )
        assertTrue(
            "the hold window must be the shared one",
            native.contains("scrubHandler.postDelayed(scrubHoldStarter, ScrubAcceleration.HOLD_START_MS)")
        )
        // The ramp reads a hold clock, not the old fixed step that only grew
        // once the scrub had already started.
        assertTrue(
            "the hold clock must be reset when the scrub starts",
            native.contains("scrubHeldMs = 0L")
        )
        assertFalse(
            "the old per-tick step must be gone",
            native.contains("scrubStepMs")
        )
        assertTrue(
            "a quick press must still be the ten-second step",
            native.contains("stepSeekBy(10_000L * scrubDirection)")
        )
    }

    // ── The libmpv engine ───────────────────────────────────────────────

    @Test
    fun `libmpv opens an accelerated scrub on an overlay-down press`() {
        val mpv = source(MPV)
        assertTrue(
            "the overlay-down LEFT/RIGHT press must open the scrub",
            mpv.contains("beginMpvHoldScrub(")
        )
        assertTrue(
            "and the scrub must read the shared ramp",
            mpv.contains("ScrubAcceleration.stepFor(mpvScrubHeldMs)")
        )
        assertTrue(
            "on the shared cadence and hold window",
            mpv.contains("mpvScrubHandler.postDelayed(mpvScrubHoldStarter, ScrubAcceleration.HOLD_START_MS)")
        )
        assertTrue(
            "it must own the position it is scrubbing to",
            mpv.contains("mpvScrubTargetMs")
        )
        assertTrue(
            "and land it when the press is released",
            mpv.contains("endMpvHoldScrub()")
        )
    }

    @Test
    fun `a libmpv hold keeps the overlay down while it scrubs`() {
        // The request was "without overlay up": a hold that raises the overlay
        // hands the D-pad to the seek bar in the middle of the scrub, which is
        // the behavior being replaced. The single ten-second step keeps its old
        // shape - one `surface?.seekBy` - but the reveal has to sit behind the
        // scrub's own decision, not in front of it.
        val mpv = source(MPV)
        val begin = mpv.indexOf("private fun beginMpvHoldScrub(")
        assertTrue("beginMpvHoldScrub must exist", begin >= 0)
        val end = mpv.indexOf("private fun endMpvHoldScrub(")
        assertTrue("endMpvHoldScrub must exist", end > begin)
        val beginBody = mpv.substring(begin, end)
        // The only reveal in that body is the no-duration fallback; the seeded
        // path must not call showControls().
        val reveals = Regex("showControls\\(\\)").findAll(beginBody).count()
        assertEquals(
            "only the stream that cannot be scrubbed may reveal the overlay on press",
            1,
            reveals
        )
        assertTrue(
            "the tap's reveal belongs to the release half",
            mpv.substring(end, minOf(mpv.length, end + 1_500)).contains("showControls()")
        )
    }
}
