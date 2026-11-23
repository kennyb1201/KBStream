package com.kennyb1201.kbstream.data.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Telling the two shapes of old store apart, which is the whole risk in the
 * adoption step.
 *
 * An EncryptedSharedPreferences file is NOT a binary blob: it is an ordinary
 * `shared_prefs/<name>.xml` holding base64 key names and base64 values, so it
 * passes every "is this XML?" check there is. Copied through the plain path it
 * would be stored as if base64 were a token, and its only readable copy would
 * then be deleted - a sign-out. The reverse mistake (treating plain XML as a
 * ciphertext store) leaves the user signed out too.
 *
 * So the ESP check runs first, and it keys off the one thing ESP puts in the
 * file that a plain store never has: the keyset entry it embeds under a
 * reserved name. These cases pin both detectors.
 */
class SecureTokenStoreMigrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(bytes: ByteArray): File =
        temp.newFile("store.xml").apply { writeBytes(bytes) }

    // ── plain XML ────────────────────────────────────────────────────

    @Test
    fun `a standard prefs xml file is plaintext`() {
        val file = write(
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="access_token">abc</string>
            </map>
            """.trimIndent().toByteArray()
        )

        assertTrue(SecureTokenStore.looksLikePlaintext(file))
    }

    @Test
    fun `a map element without an xml declaration is plaintext`() {
        val file = write("<map><boolean name=\"signed_out\" value=\"true\" /></map>".toByteArray())

        assertTrue(SecureTokenStore.looksLikePlaintext(file))
    }

    @Test
    fun `a file that is not xml at all is not plaintext`() {
        val file = write(byteArrayOf(0x3F, 0x1A, 0x00, 0x7B, 0x22, 0x09))

        assertFalse(SecureTokenStore.looksLikePlaintext(file))
    }

    @Test
    fun `an empty file is not treated as plaintext`() {
        assertFalse(SecureTokenStore.looksLikePlaintext(write(ByteArray(0))))
    }

    @Test
    fun `a missing file is not treated as plaintext`() {
        assertFalse(SecureTokenStore.looksLikePlaintext(File(temp.root, "absent.xml")))
    }

    // ── EncryptedSharedPreferences ──────────────────────────────────

    @Test
    fun `an encrypted store is recognised by its embedded keyset`() {
        val file = write(
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="__androidx_security_crypto_encrypted_prefs_key_keyset__">{"primaryKeyId":123}</string>
                <string name="Af9xQw==">Bd7yPg==</string>
            </map>
            """.trimIndent().toByteArray()
        )

        assertTrue(
            "the keyset entry is what marks the entries as ciphertext",
            SecureTokenStore.isEncryptedSharedPreferences(file)
        )
        // And it passes the XML check, which is exactly why the order in the
        // adoption step matters.
        assertTrue(SecureTokenStore.looksLikePlaintext(file))
    }

    @Test
    fun `a plain xml store is not an encrypted store`() {
        val file = write("<map><string name=\"access_token\">abc</string></map>".toByteArray())

        assertFalse(SecureTokenStore.isEncryptedSharedPreferences(file))
    }

    @Test
    fun `a missing file is not an encrypted store`() {
        assertFalse(
            SecureTokenStore.isEncryptedSharedPreferences(File(temp.root, "absent.xml"))
        )
    }
}
