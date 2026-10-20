package com.kennyb1201.kbstream.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The flow-level rule behind the second half of the "code expired" report.
 *
 * The release build's failure was: a poll threw while constructing the token
 * (see [TraktWireFormatTest]), the throw was counted as one more failed poll,
 * and the retry read 404 because the throwing poll's 200 had already traded the
 * code away. Reporting that 404 as "that code expired" blamed the viewer's
 * timing for an app fault.
 *
 * One poll cannot see the difference, so the loop hands the whole flow to
 * [TraktFlowEvidence]. These are the sequences a real connect produced, plus
 * the two that must keep the old meaning.
 */
class TraktFlowEvidenceTest {

    @Test
    fun `a 404 after a throw in the same flow is a failure, not an expiry`() {
        // The live sequence, in order: waiting, the 200 that threw while
        // constructing the token, then the spent code read again.
        val flow = TraktFlowEvidence()
        flow.recordPoll(400)
        flow.recordThrow()
        flow.recordPoll(404)

        assertEquals(
            "the code was spent by the poll that threw, so this is an app fault",
            TraktDeviceFlowRules.Poll.FAILED,
            flow.expiredOutcome()
        )
    }

    @Test
    fun `a 404 with no throw anywhere in the flow keeps the code-expired meaning`() {
        val flow = TraktFlowEvidence()
        flow.recordPoll(400)
        flow.recordPoll(400)
        flow.recordPoll(404)

        assertEquals(
            TraktDeviceFlowRules.Poll.EXPIRED,
            flow.expiredOutcome()
        )
    }

    @Test
    fun `the evidence belongs to the whole flow, not the settling poll`() {
        // The throw can be several polls back: a PENDING in between must not
        // erase it, which is exactly what a "last poll was fine" check would do.
        val flow = TraktFlowEvidence()
        flow.recordThrow()
        flow.recordPoll(200)
        flow.recordPoll(400)
        flow.recordPoll(404)

        assertEquals(TraktDeviceFlowRules.Poll.FAILED, flow.expiredOutcome())
    }

    @Test
    fun `only a 404 is explained away by a local throw`() {
        // 409/410 are Trakt itself saying the code is gone; a throw here cannot
        // mean this app consumed it behind the loop's back.
        listOf(409, 410).forEach { status ->
            val flow = TraktFlowEvidence()
            flow.recordThrow()
            flow.recordPoll(status)
            assertEquals(
                "$status is Trakt reporting the code dead",
                TraktDeviceFlowRules.Poll.EXPIRED,
                flow.expiredOutcome()
            )
        }
    }
}
