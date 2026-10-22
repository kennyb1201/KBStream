package com.kennyb1201.kbstream.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-back delivery loop's one decision, pinned without a composition.
 *
 * The reported bug was a ONE-SHOT ask, so the only thing this table has to keep
 * true is that a miss is never the end of the story: the loop asks again until
 * the target takes the focus or the frame budget runs out, and running out is
 * reported rather than silent.
 */
class DetailHandBackStepTest {

    @Test
    fun `a landed request delivers immediately, however many frames it took`() {
        assertEquals(DetailHandBackStep.DELIVERED, detailHandBackStep(1, landed = true))
        assertEquals(DetailHandBackStep.DELIVERED, detailHandBackStep(12, landed = true))
        assertEquals(
            "a landing on the very last frame still counts: the budget bounds " +
                "the waiting, not the success",
            DetailHandBackStep.DELIVERED,
            detailHandBackStep(DETAIL_HAND_BACK_FRAMES, landed = true)
        )
    }

    @Test
    fun `a miss is retried across frames`() {
        assertEquals(DetailHandBackStep.RETRY, detailHandBackStep(1, landed = false))
        assertEquals(
            DetailHandBackStep.RETRY,
            detailHandBackStep(DETAIL_HAND_BACK_FRAMES - 1, landed = false)
        )
    }

    @Test
    fun `the loop gives up only when the frame budget is spent`() {
        assertEquals(
            DetailHandBackStep.ABANDONED,
            detailHandBackStep(DETAIL_HAND_BACK_FRAMES, landed = false)
        )
        assertEquals(
            DetailHandBackStep.ABANDONED,
            detailHandBackStep(DETAIL_HAND_BACK_FRAMES + 5, landed = false)
        )
    }

    @Test
    fun `the budget is about a second of frames`() {
        assertTrue(
            "frame-paced, so it has to be generous enough for a rail that has to " +
                "rebuild and short enough to be reported inside a second: " +
                "$DETAIL_HAND_BACK_FRAMES",
            DETAIL_HAND_BACK_FRAMES in 30..120
        )
    }
}
