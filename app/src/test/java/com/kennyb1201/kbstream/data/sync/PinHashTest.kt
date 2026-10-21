package com.kennyb1201.kbstream.data.sync

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
        assertFalse(PinHash.create("1234").contains("1234"))
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
