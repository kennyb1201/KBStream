package com.kennyb1201.kbstream.data.simkl

import com.kennyb1201.kbstream.data.sync.SimklAuthRules
import com.kennyb1201.kbstream.ui.simkl.SimklLinkOfferCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simkl V2 link-state sync (spec 2026-11-16): the account syncs, the tokens stay
 * per device.
 *
 * The report behind this was "is there any way to make Simkl V2 sync device to
 * device?". For the TOKENS the answer is no and must stay no - a shared refresh
 * token means a refresh on one device invalidates the pair on the other - so the
 * thing that travels is the LINK STATE: which account this profile is linked to,
 * and that it is linked through V2. A device that reads it and holds no session
 * of its own offers its own one-time device flow.
 *
 * These tests pin the four decisions that make that safe: what a row may carry
 * (S4), when a device has anything to say, what a received row stores (F2/F4: it
 * never clears), and when the offer is owed (S3/S5/S6).
 */
class SimklLinkStateContractTest {

    // ── F1/S4: the row carries the name and the marker, and nothing else ──

    @Test
    fun `the link row is the name and a marker, and no token material`() {
        val fields = SimklLinkRules.payloadFields(updatedAt = 1_700_000_000_000L, accountName = "Kenneth Bailey")

        assertEquals(
            "the row's whole shape, so a field added later fails here instead of travelling",
            SimklLinkRules.PAYLOAD_FIELDS,
            fields.map { it.first }.toSet()
        )
        assertEquals(true, fields.toMap()[SimklLinkRules.LINK_FIELD])
        assertEquals("Kenneth Bailey", fields.toMap()[SimklLinkRules.NAME_FIELD])

        // The constraint, said as a constraint: no field name that could carry a
        // grant appears at all.
        val names = fields.map { it.first.lowercase() }
        listOf("access", "refresh", "token", "expires", "scope").forEach { forbidden ->
            assertFalse(
                "no $forbidden field may appear in a link row",
                names.any { it.contains(forbidden) }
            )
        }
        // And no value is a plausible secret: the two values that travel are the
        // stamp and the display name.
        assertTrue(fields.all { it.second is Long || it.second is Boolean || it.second is String })
    }

    @Test
    fun `the trimmed name is what travels`() {
        val fields = SimklLinkRules.payloadFields(0L, "  Kenneth Bailey  ").toMap()
        assertEquals("Kenneth Bailey", fields[SimklLinkRules.NAME_FIELD])
    }

    // ── F1: when this device has anything to say ────────────────────────

    @Test
    fun `only a V2 session with a resolved name publishes a link state`() {
        assertTrue(SimklLinkRules.shouldPublish("Kenneth Bailey", isV2Session = true))
        assertFalse("no name yet: the row could name nobody", SimklLinkRules.shouldPublish(null, true))
        assertFalse(SimklLinkRules.shouldPublish("  ", true))
        assertFalse(
            "a V1 session's tokens travel on its own row; this is not its business",
            SimklLinkRules.shouldPublish("Kenneth Bailey", isV2Session = false)
        )
    }

    @Test
    fun `the auth row still publishes nothing at all for a V2 session`() {
        // The other half of S4, and the reason this feature is a separate row: a
        // V2 session must not publish itself, marker or no marker.
        assertFalse(
            SimklAuthRules.shouldPublish(
                accessToken = "an-access-token",
                signedOut = false,
                refreshToken = "a-refresh-token"
            )
        )
    }

    // ── F2/F4: what a received row stores, and what it must not do ──────

    @Test
    fun `a link row stores the account name`() {
        assertEquals("Kenneth Bailey", SimklLinkRules.linkNameToStore(true, "Kenneth Bailey"))
    }

    @Test
    fun `a row that is not linked never clears the stored state`() {
        // The link state is NEVER cleared by this feature - a disconnect does not
        // clear it, and a device that never linked must not either. Null here is
        // "write nothing", not "erase".
        assertNull(SimklLinkRules.linkNameToStore(linked = false, name = "Kenneth Bailey"))
        assertNull(SimklLinkRules.linkNameToStore(linked = false, name = null))
    }

    @Test
    fun `a linked row with no name stores nothing`() {
        assertNull(SimklLinkRules.linkNameToStore(true, null))
        assertNull(SimklLinkRules.linkNameToStore(true, "   "))
    }

    // ── F3/S3/S5/S6: when the offer is owed ─────────────────────────────

    @Test
    fun `a device with no session, linked elsewhere, is offered the link`() {
        // S1's state on the reading device.
        assertEquals(
            "Kenneth Bailey",
            SimklLinkRules.offerName(
                hasSession = false,
                signedOut = false,
                offerSuppressed = false,
                linkedName = "Kenneth Bailey"
            )
        )
    }

    @Test
    fun `a device that holds any session is not offered it`() {
        // S5 in particular: a V1 session is still a session, and its reconnect is
        // the legacy row's job - this offer must never be the thing that pushes a
        // legacy user at a second grant.
        assertNull(
            SimklLinkRules.offerName(true, signedOut = false, offerSuppressed = false, "Kenneth Bailey")
        )
    }

    @Test
    fun `a device that disconnected stays quiet, both ways`() {
        // S3: the V1 tombstone answers signedOut, a V2 disconnect writes no
        // tombstone so it answers offerSuppressed. Either way: no nag.
        assertNull(
            "deliberate disconnect (legacy marker)",
            SimklLinkRules.offerName(false, signedOut = true, offerSuppressed = false, "Kenneth Bailey")
        )
        assertNull(
            "deliberate disconnect (V2, device-local suppression)",
            SimklLinkRules.offerName(false, signedOut = false, offerSuppressed = true, "Kenneth Bailey")
        )
    }

    @Test
    fun `a fresh device with no link state shows no prompt and no name`() {
        // S6: nothing was ever published, so there is nothing to offer - and the
        // screen falls back to its plain not-connected state.
        assertNull(SimklLinkRules.offerName(false, false, false, linkedName = null))
        assertNull(SimklLinkRules.offerName(false, false, false, linkedName = "  "))
    }
}

/** The words the offer is made of (F3), pinned so both screens cannot drift. */
class SimklLinkOfferCopyTest {

    @Test
    fun `the offer names the account and the fact that it is another device`() {
        assertEquals(
            "Linked as Kenneth Bailey on another device",
            SimklLinkOfferCopy.line("Kenneth Bailey")
        )
    }

    @Test
    fun `the action is the existing device flow, not a second sign-in`() {
        assertEquals("CONNECT THIS DEVICE", SimklLinkOfferCopy.ACTION)
        // The explanation has to carry the per-device half: the viewer just
        // linked on their phone and will reasonably expect this device to be
        // signed in. That is the one thing they cannot guess.
        assertNotNull(SimklLinkOfferCopy.EXPLANATION)
        assertTrue(
            SimklLinkOfferCopy.EXPLANATION,
            SimklLinkOfferCopy.EXPLANATION.contains("own code")
        )
        assertTrue(
            SimklLinkOfferCopy.EXPLANATION,
            SimklLinkOfferCopy.EXPLANATION.contains("stays signed in")
        )
    }
}
