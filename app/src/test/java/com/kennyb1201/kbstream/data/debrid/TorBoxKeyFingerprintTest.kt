package com.kennyb1201.kbstream.data.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fingerprint the TorBox VERIFY answer is remembered against.
 *
 * The row used to forget that a key had ever been verified as soon as the
 * screen closed; the record that fixes it is filed under this value, so the two
 * properties that make it correct are pinned here: the same key always maps to
 * the same digest (so re-blurring the field cannot lose the answer), and a
 * different key never does (so a new key cannot borrow the old verdict).
 */
class TorBoxKeyFingerprintTest {

    @Test
    fun `the same key fingerprints the same, including around whitespace`() {
        val key = "a1b2c3d4-0000-1111-2222-333344445555"
        assertEquals(
            "a re-saved field (or a pasted-with-spaces copy) must not look like a new key",
            torboxKeyFingerprint(key),
            torboxKeyFingerprint("  $key  ")
        )
        assertTrue(
            "and the digest is stable for the life of the app",
            torboxKeyFingerprint(key) == torboxKeyFingerprint(key)
        )
    }

    @Test
    fun `a different key never borrows the answer`() {
        assertNotEquals(
            torboxKeyFingerprint("key-one"),
            torboxKeyFingerprint("key-two")
        )
        assertNotEquals(
            "a single character is a different credential",
            torboxKeyFingerprint("key-one"),
            torboxKeyFingerprint("key-one2")
        )
    }

    @Test
    fun `the stored value can never be read back as the credential`() {
        val key = "a1b2c3d4-0000-1111-2222-333344445555"
        val fingerprint = torboxKeyFingerprint(key)
        assertFalse("the key must not appear in the record", fingerprint.contains(key))
        assertEquals("SHA-256, hex", 64, fingerprint.length)
        assertTrue(
            "hex only, so it cannot collide with a key's own characters",
            fingerprint.all { it in "0123456789abcdef" }
        )
    }
}
