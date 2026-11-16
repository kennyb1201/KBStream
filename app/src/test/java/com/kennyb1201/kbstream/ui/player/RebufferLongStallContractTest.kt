package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One long stall convicts a source; three short ones still take three.
 *
 * Field case (Kenny, 2026-11-16 12:03, TCL build 338): Landman S02E02 on the
 * auto-picked Usenet Vault 4K HEVC source (7.4 GB) cost three rebuffers — the
 * last one 9184 ms — before the downshift fired. The count rule's reasoning
 * ("one is a CDN hiccup and two is a bad minute") is about SHORT stalls; a
 * source with any read-ahead buffer does not starve for nine seconds and then
 * recover by itself, so the viewer paid two extra stalls for evidence the first
 * one already gave.
 *
 * This pins the shape rather than the arithmetic — [RebufferDownshiftTest]
 * owns the window itself, and every case here compiles against the real
 * constants, so a threshold that drifts fails as loudly as a missing call.
 */
class RebufferLongStallContractTest {

    // --- F1: the threshold, and both engines carrying the duration ----------

    @Test
    fun `the long-stall threshold sits just under the reported stall`() {
        assertEquals(
            "a 9184ms stall must be past the trigger, with room to spare",
            8_000L,
            REBUFFER_DOWNSHIFT_LONG_STALL_MS
        )
        assertTrue(
            "and the trigger must be far above the 2s minimum it sits on",
            REBUFFER_DOWNSHIFT_LONG_STALL_MS >= 4 * REBUFFER_DOWNSHIFT_MIN_STALL_MS
        )
        assertTrue(
            "a stall that long is conclusive on its own",
            RebufferDownshiftTracker().let { tracker ->
                tracker.record(1_000_000L, 9_184L)
                tracker.due(1_000_000L)
            }
        )
    }

    @Test
    fun `the main player hands the stall's length to the tracker`() {
        val downshift = nativeDownshift
        assertTrue(
            "the duration must travel with the timestamp, or the fast trigger has " +
                "nothing to read",
            downshift.contains("rebufferDownshift.record(now, stalledMs)")
        )
        assertTrue(
            "and the log must say which of the two rules fired",
            downshift.contains("rebufferDownshift.longStall(now)")
        )
    }

    @Test
    fun `the MPV engine gets the same trigger from the same tracker`() {
        val mpv = mpvBuffering
        assertTrue(
            "the backup engine's stalls must carry their length too",
            mpv.contains("rebufferDownshift.record(now, stalledMs)")
        )
        assertTrue(
            "and the length it records is the one it measured for the minimum",
            mpv.indexOf("val stalledMs = System.currentTimeMillis() - startedAt") in
                0 until mpv.indexOf("rebufferDownshift.record(now, stalledMs)")
        )
        assertTrue(
            "the MPV log must name the long stall as well",
            mpv.contains("rebufferDownshift.longStall(now)")
        )
    }

    // --- F2: the count rule is intact for shorter stalls --------------------

    @Test
    fun `three short stalls in five minutes still need three`() {
        assertEquals(3, REBUFFER_DOWNSHIFT_COUNT)
        assertEquals(5 * 60_000L, REBUFFER_DOWNSHIFT_WINDOW_MS)
        val tracker = RebufferDownshiftTracker()
        repeat(REBUFFER_DOWNSHIFT_COUNT - 1) { i ->
            tracker.record(1_000_000L + i * 1_000L, REBUFFER_DOWNSHIFT_MIN_STALL_MS + 500L)
        }
        assertFalse(
            "two short stalls are still a bad minute, not a verdict",
            tracker.due(1_000_000L + 2_000L)
        )
        tracker.record(1_000_000L + 3_000L, REBUFFER_DOWNSHIFT_MIN_STALL_MS + 500L)
        assertTrue(
            "and the third inside the window is still what fires",
            tracker.due(1_000_000L + 3_000L)
        )
    }

    // --- F3: the exhaustion hold outranks the fast trigger ------------------

    @Test
    fun `a long stall inside the exhaustion window still holds the source`() {
        val downshift = nativeDownshift
        val hold = downshift.indexOf("if (DecoderPoolHealth.exhaustedRecently()) {")
        val record = downshift.indexOf("rebufferDownshift.record(now, stalledMs)")
        val switch = downshift.indexOf("val switching = tryNextSource(")
        assertTrue("the exhaustion hold must exist", hold >= 0)
        assertTrue(
            "the hold must read before the stall is recorded, so a long stall cannot " +
                "spend the window while the pool is recovering",
            hold < record
        )
        assertTrue(
            "and the switch must stay unreachable behind it",
            hold < switch
        )
        assertFalse(
            "nothing in this handler may rebuild the player",
            downshift.contains("recreatePlayer(")
        )
    }

    // --- The guards the trigger must not outrun -----------------------------

    @Test
    fun `the seek grace and the minimum stall still gate both engines`() {
        assertEquals(2_000L, REBUFFER_DOWNSHIFT_MIN_STALL_MS)
        assertEquals(1_000L, SEEK_REBUFFER_GRACE_MS)
        val downshift = nativeDownshift
        val mpv = mpvBuffering
        assertTrue(
            "a long stall after a seek is still the seek's own",
            downshift.indexOf("rebufferFollowsSeek(rebufferStartMs, lastSeekAtMs)") in
                0 until downshift.indexOf("rebufferDownshift.record(now, stalledMs)")
        )
        assertTrue(
            "and the minimum still gates the MPV engine before its record",
            mpv.indexOf("rebufferFollowsSeek(startedAt, lastSeekAtMs)") in
                0 until mpv.indexOf("rebufferDownshift.record(now, stalledMs)")
        )
        assertTrue(
            "no engine may record a stall it has not measured against the minimum",
            downshift.indexOf("stalledMs < REBUFFER_DOWNSHIFT_MIN_STALL_MS") in
                0 until downshift.indexOf("rebufferDownshift.record(now, stalledMs)")
        )
    }

    // --- Source ------------------------------------------------------------------

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

    /** The whole rebuffer handler in the main player. */
    private val nativeDownshift: String by lazy {
        region(
            source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"),
            "private fun maybeDownshiftOnRebuffer(rebufferStartMs: Long, stalledMs: Long): Boolean {",
            "private fun tryNextSource("
        )
    }

    /** The whole buffering handler in the MPV engine. */
    private val mpvBuffering: String by lazy {
        region(
            source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"),
            "private fun onMpvBufferingChanged(buffering: Boolean) {",
            "Runs once per file load, MPV_NO_VIDEO_GRACE_MS after it opened."
        )
    }
}
