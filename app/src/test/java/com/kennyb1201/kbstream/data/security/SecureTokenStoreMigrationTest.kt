package com.kennyb1201.kbstream.data.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one guard standing between the encrypted-store migration and a live
 * session: the store keeps its old NAME, so a rename of a file that is already
 * ciphertext would destroy the encrypted prefs (and with them the Supabase
 * refresh token and the Simkl token).
 *
 * These cases pin the detection both ways. A false negative renames an
 * encrypted file and signs the user out; a false positive leaves the plaintext
 * file in place forever, which is the leak this change exists to close.
 */
class SecureTokenStoreMigrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(bytes: ByteArray): File =
        temp.newFile("store.xml").apply { writeBytes(bytes) }

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
    fun `ciphertext is not plaintext`() {
        // EncryptedSharedPreferences writes ciphertext; a leading byte that is
        // not '<' is the whole signal this check needs.
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
}