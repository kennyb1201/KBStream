package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The stored-PIN format (audit B2).
 *
 * The regression this pins down: a PIN used to be stored as bare
 * `SHA-256(pin)`, unsalted, over a 10,000-value space, inside a blob that
 * syncs to Supabase. The hash had to become salted and iterated WITHOUT
 * locking out anyone holding an old hash — so both formats verify here.
 */
class PinHashTest {

    @Test
    fun `a new hash is salted, so the same PIN never stores the same value`() {
        val first = PinHash.create("1234")
        val second = PinHash.create("1234")

        assertTrue("v2 format", first.startsWith("v2$"))
        assertNotEquals("per-hash salt", first, second)
        assertTrue(PinHash.matches(first, "1234"))
        assertTrue(PinHash.matches(second, "1234"))
    }

    @Test
    fun `the stored value does not contain the PIN`() {
        val stored = PinHash.create("1234")
        val fields = stored.split('$')
        // Deliberately NOT a substring hunt. Every 4-digit PIN is spelled out of
        // the hex alphabet the salt and digest use, so "the stored value never
        // happens to contain 1234" is a lottery over ~93 positions of random hex
        // (about one run in 700) - and it landed on the v0.9.108 build with a
        // perfectly good hash, failing a release for nothing. What the format
        // actually promises is pinned instead, deterministically.
        assertEquals(
            "v2$<iterations>$<salt>$<digest> is the whole stored value",
            4,
            fields.size
        )
        assertNotEquals(
            "the digest is not the PIN itself",
            "1234",
            fields[3]
        )
        assertNotEquals(
            "and not the unsalted SHA-256 the old format stored, which WAS the PIN " +
                "to anyone holding a precomputed table",
            sha256Hex("1234"),
            fields[3]
        )
        assertTrue(
            "every byte of it is the format's own alphabet, so no field can spell the " +
                "PIN out",
            stored.matches(Regex("v2\\$\\d+\\$[0-9a-f]{32}\\$[0-9a-f]{64}"))
        )
    }

    @Test
    fun `a wrong PIN does not verify`() {
        val stored = PinHash.create("1234")
        assertFalse(PinHash.matches(stored, "4321"))
        assertFalse(PinHash.matches(stored, ""))
        assertFalse(PinHash.matches(stored, "12345"))
    }

    @Test
    fun `nothing stored is not a match`() {
        assertFalse(PinHash.matches(null, "1234"))
        assertFalse(PinHash.matches("", "1234"))
        assertFalse(PinHash.matches("   ", "1234"))
    }

    @Test
    fun `a corrupt v2 value refuses instead of throwing`() {
        assertFalse(PinHash.matches("v2\$not-a-number\$ab\$cd", "1234"))
        assertFalse(PinHash.matches("v2\$100\$zz\$cd", "1234"))
        assertFalse(PinHash.matches("v2\$100\$abcd", "1234"))
    }

    @Test
    fun `a legacy unsalted hash still verifies and is flagged for upgrade`() {
        val legacy = sha256Hex("2468")

        assertTrue("old hashes keep working", PinHash.matches(legacy, "2468"))
        assertFalse("but only for the right PIN", PinHash.matches(legacy, "1357"))
        assertTrue("and is rewritten next time the PIN is seen", PinHash.needsUpgrade(legacy))
        assertFalse(PinHash.needsUpgrade(PinHash.create("2468")))
        assertFalse("no hash is not an upgrade candidate", PinHash.needsUpgrade(null))
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
