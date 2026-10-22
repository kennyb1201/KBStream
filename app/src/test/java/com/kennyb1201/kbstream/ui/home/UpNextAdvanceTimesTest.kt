package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An ADVANCE card's time fields describe its own episode or are absent.
 *
 * Reported: The UnBelievable showed a NEXT UP card for S1 E6 (genuinely
 * unwatched) and the hero line read "44m left" - the full duration of the
 * episode just finished, presented as the next episode's remaining time. A
 * card pointing at an episode nobody has started has nothing "left", and the
 * start position must never be inherited from the row the card was derived
 * from, or a future row shape trips it.
 *
 * Runtime is the target's, and only when the resolution carries one: a card
 * with no runtime prints none, rather than the source row's duration.
 */
class UpNextAdvanceTimesTest {

    @Test
    fun `an advance card reports nothing left`() {
        val times = upNextAdvanceTimes(targetRuntimeMinutes = 44)

        assertNull(
            "the finished row's full duration is not the next episode's time left",
            times.remainingMinutes
        )
        assertEquals("nothing has been played of the advance target", 0L, times.startPositionMs)
        assertEquals("and the runtime is the target's own", 44, times.runtimeMinutes)
    }

    @Test
    fun `an advance card whose target carries no runtime carries none`() {
        assertNull(upNextAdvanceTimes(targetRuntimeMinutes = null).runtimeMinutes)
        assertNull(
            "a zero-minute runtime is no runtime",
            upNextAdvanceTimes(targetRuntimeMinutes = 0).runtimeMinutes
        )
    }

    @Test
    fun `the hero and the card render no time-left for an advance card`() {
        // Both surfaces format remainingMinutes through this rule, so the
        // exact string the field report saw ("44m left") cannot be produced.
        assertNull(formatTimeLeft(upNextAdvanceTimes(targetRuntimeMinutes = 44).remainingMinutes))
    }
}
