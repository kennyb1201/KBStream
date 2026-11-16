package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repeated-stall downshift, decided without a TV in the room.
 *
 * The report this pins is the source that plays but cannot keep up: "38
 * rebuffers on a 1 Mbps stream". Nothing errors, so the ranker (which chooses
 * up front) and the error ladder (which reacts to failures) both ignore it, and
 * the viewer watches a spinner in slices for the length of the film. The window
 * below is the whole feature, so it is exercised directly.
 *
 * The interesting cases are the ones that must NOT downshift, because a source
 * switch is not free — it costs the load splash and a decoder rebuild:
 *
 *  - a single SHORT stall is a CDN hiccup, not a verdict (a single long one
 *    is a verdict, and has its own section below);
 *  - stalls spread wider than the window are a source that is merely uneven,
 *    and the window is what keeps three far-apart blips from adding up;
 *  - a seek flushes the read-ahead buffer, so the buffering that follows it is
 *    the jump's own and says nothing about the source's speed.
 */
class RebufferDownshiftTest {

    private val base = 1_000_000L

    /** A stall long enough to count, short of the long-stall verdict. */
    private val shortStall = REBUFFER_DOWNSHIFT_MIN_STALL_MS + 500L

    /** The reported stall: Landman S02E02 on the 4K Usenet Vault source. */
    private val landmanStall = 9_184L

    // --- The sliding window -------------------------------------------------

    @Test
    fun `fewer than the threshold is not enough to downshift`() {
        val tracker = RebufferDownshiftTracker()
        tracker.record(base, shortStall)
        assertFalse(tracker.due(base))
        tracker.record(base + 1_000L, shortStall)
        assertFalse(tracker.due(base + 1_000L))
    }

    @Test
    fun `the threshold reached inside the window downshifts`() {
        val tracker = RebufferDownshiftTracker()
        repeat(REBUFFER_DOWNSHIFT_COUNT) { i ->
            tracker.record(base + i * 1_000L, shortStall)
        }
        assertTrue(tracker.due(base + (REBUFFER_DOWNSHIFT_COUNT - 1) * 1_000L))
    }

    @Test
    fun `rebuffers spread past the window do not add up`() {
        val tracker = RebufferDownshiftTracker()
        val gap = REBUFFER_DOWNSHIFT_WINDOW_MS + 1L
        repeat(REBUFFER_DOWNSHIFT_COUNT) { i ->
            tracker.record(base + i * gap, shortStall)
        }
        val now = base + (REBUFFER_DOWNSHIFT_COUNT - 1) * gap
        assertEquals(1, tracker.count(now))
        assertFalse(tracker.due(now))
    }

    @Test
    fun `a rebuffer exactly one window old is still inside it`() {
        val tracker = RebufferDownshiftTracker()
        tracker.record(base, shortStall)
        assertEquals(1, tracker.count(base + REBUFFER_DOWNSHIFT_WINDOW_MS))
        assertEquals(0, tracker.count(base + REBUFFER_DOWNSHIFT_WINDOW_MS + 1L))
    }

    @Test
    fun `a fresh source starts with an empty window`() {
        val tracker = RebufferDownshiftTracker()
        repeat(REBUFFER_DOWNSHIFT_COUNT) { i -> tracker.record(base + i, shortStall) }
        assertTrue(tracker.due(base))
        tracker.reset()
        assertFalse(tracker.due(base))
        assertEquals(0, tracker.count(base))
    }

    // --- One long stall is a verdict on its own -----------------------------

    @Test
    fun `the reported nine second stall downshifts with no second stall paid`() {
        val tracker = RebufferDownshiftTracker()
        tracker.record(base, landmanStall)
        assertEquals("one stall, not three, is the whole window", 1, tracker.count(base))
        assertTrue("a 9184ms stall must convict the source by itself", tracker.due(base))
        assertTrue(tracker.longStall(base))
    }

    @Test
    fun `the long-stall threshold itself is enough`() {
        val tracker = RebufferDownshiftTracker()
        tracker.record(base, REBUFFER_DOWNSHIFT_LONG_STALL_MS)
        assertTrue(tracker.due(base))
    }

    @Test
    fun `a stall just under the long-stall threshold still needs three`() {
        val tracker = RebufferDownshiftTracker()
        tracker.record(base, REBUFFER_DOWNSHIFT_LONG_STALL_MS - 1L)
        assertFalse(tracker.due(base))
        assertFalse(tracker.longStall(base))
        tracker.record(base + 1_000L, shortStall)
        tracker.record(base + 2_000L, shortStall)
        assertTrue("under the threshold the count rule still decides", tracker.due(base + 2_000L))
    }

    @Test
    fun `the long stall is not earnable by short stalls adding up`() {
        val tracker = RebufferDownshiftTracker()
        repeat(REBUFFER_DOWNSHIFT_COUNT - 1) { i -> tracker.record(base + i, shortStall) }
        assertFalse(tracker.longStall(base))
        assertFalse(tracker.due(base))
    }

    @Test
    fun `a long stall prunes out of the window like any other`() {
        val tracker = RebufferDownshiftTracker()
        tracker.record(base, landmanStall)
        val later = base + REBUFFER_DOWNSHIFT_WINDOW_MS + 1L
        assertFalse(tracker.due(later))
        assertFalse(tracker.longStall(later))
        assertEquals(0, tracker.count(later))
    }

    @Test
    fun `a reset clears the long-stall verdict with the window`() {
        val tracker = RebufferDownshiftTracker()
        tracker.record(base, landmanStall)
        assertTrue(tracker.due(base))
        tracker.reset()
        assertFalse(tracker.due(base))
        assertFalse(tracker.longStall(base))
    }

    // --- A seek's own rebuffer ---------------------------------------------

    @Test
    fun `with no seek this session a rebuffer is never the seek's`() {
        assertFalse(rebufferFollowsSeek(rebufferStartedAtMs = base, lastSeekAtMs = 0L))
    }

    @Test
    fun `a seek just before the buffering explains that rebuffer`() {
        assertTrue(rebufferFollowsSeek(rebufferStartedAtMs = base, lastSeekAtMs = base - 300L))
    }

    @Test
    fun `a seek stamped after buffering began still explains it`() {
        // Media3 can run the discontinuity callback and the STATE_BUFFERING in
        // either order; the grace window covers the reordering.
        assertTrue(
            rebufferFollowsSeek(
                rebufferStartedAtMs = base,
                lastSeekAtMs = base + SEEK_REBUFFER_GRACE_MS - 1L
            )
        )
    }

    @Test
    fun `a seek from long before does not explain a later rebuffer`() {
        assertFalse(
            rebufferFollowsSeek(
                rebufferStartedAtMs = base,
                lastSeekAtMs = base - SEEK_REBUFFER_GRACE_MS - 1L
            )
        )
    }

    // --- The wiring the arithmetic cannot prove on its own -----------------

    private val activitySource: String by lazy {
        val file = File(findSourceRoot(), ACTIVITY)
        assertTrue("source missing: $file", file.isFile)
        file.readText()
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

    @Test
    fun `the player counts a finished rebuffer through the downshift rules`() {
        assertTrue(
            "a completed mid-playback rebuffer must reach the downshift rules",
            activitySource.contains("maybeDownshiftOnRebuffer(")
        )
    }

    @Test
    fun `the downshift walks the existing next-source ladder`() {
        val block = activitySource
            .substringAfter("private fun maybeDownshiftOnRebuffer(")
            .substringBefore("private fun tryNextSource(")
        assertTrue(
            "the downshift must reuse tryNextSource rather than build its own ladder",
            block.contains("tryNextSource(")
        )
    }

    @Test
    fun `a source switch resets the rebuffer window`() {
        val block = activitySource
            .substringAfter("fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false)")
            .substringBefore("private fun maybeDownshiftOnRebuffer(")
        assertTrue(
            "a fresh source must not inherit the previous source's stalls",
            block.contains("rebufferDownshift.reset()")
        )
        assertTrue(
            "a source switch must also clear a given-up ladder",
            block.contains("rebufferDownshiftGivenUp = false")
        )
    }

    @Test
    fun `a seek is stamped so its own buffering is not counted`() {
        val block = activitySource
            .substringAfter("override fun onPositionDiscontinuity(")
            .substringBefore("override fun onRenderedFirstFrame(")
        assertTrue(
            "seeks must be stamped for rebufferFollowsSeek to exclude them",
            block.contains("lastSeekAtMs = System.currentTimeMillis()")
        )
    }

    private companion object {
        const val ACTIVITY = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
