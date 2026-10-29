package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary the launch payload is spilled at.
 *
 * The regression behind it (ANDROID-12): one launch carried 1,553,592 bytes of
 * extras, the binder refused the transaction, and every Play from that title
 * ended in `RuntimeException: Failure from system` with no player. These pin
 * that the decision is a hard cut at the budget rather than "large is large",
 * and that the size measured is the one the binder actually carries.
 */
class PlayerLaunchPayloadRulesTest {

    @Test
    fun `a payload inside the budget rides the intent`() {
        assertFalse(shouldSpillPlayerPayload(0))
        assertFalse(shouldSpillPlayerPayload(1024))
        assertFalse(
            "the budget itself still fits: it is the limit, not the last byte under it",
            shouldSpillPlayerPayload(PLAYER_PAYLOAD_BUDGET_BYTES)
        )
    }

    @Test
    fun `a payload past the budget is spilled`() {
        assertTrue(shouldSpillPlayerPayload(PLAYER_PAYLOAD_BUDGET_BYTES + 1))
        assertTrue(
            "the size that actually failed in the field must spill",
            shouldSpillPlayerPayload(1_553_592)
        )
    }

    @Test
    fun `the budget leaves room under the binder buffer`() {
        assertTrue(
            "the binder transaction buffer is about 1 MB and the launch carries other " +
                "extras beside these, so the budget must sit well under it",
            PLAYER_PAYLOAD_BUDGET_BYTES < 500_000
        )
    }

    @Test
    fun `size is measured in utf-8 bytes, not characters`() {
        assertEquals(5, playerPayloadBytes(mapOf("a" to "hello")))
        assertEquals(
            "a non-ASCII title counts the bytes the binder carries, not its char count",
            6,
            playerPayloadBytes(mapOf("t" to "ééé"))
        )
    }

    @Test
    fun `the whole payload is measured, not one key of it`() {
        val payloads = mapOf(
            "sources_json" to "x".repeat(200_000),
            "source_addons" to "y".repeat(60_000),
            "cast_json" to "z".repeat(60_000)
        )
        assertEquals(320_000, playerPayloadBytes(payloads))
        assertTrue(
            "each key alone fits, together they do not - the launch carries all three",
            shouldSpillPlayerPayload(playerPayloadBytes(payloads))
        )
    }

    @Test
    fun `an empty payload has nothing to carry`() {
        assertEquals(0, playerPayloadBytes(emptyMap()))
        assertFalse(shouldSpillPlayerPayload(playerPayloadBytes(emptyMap())))
    }
}
