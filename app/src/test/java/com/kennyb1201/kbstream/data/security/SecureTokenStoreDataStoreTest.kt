package com.kennyb1201.kbstream.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The encrypted token store's contract, as far as a JVM can hold it.
 *
 * A Robolectric run has no AndroidKeyStore, so these cases exercise the
 * documented degradation (a plain DataStore file) rather than the AES-GCM path.
 * That is the half a unit test can own, and it is the half that decides whether
 * the app can still sign in on a device whose keystore is gone.
 *
 * What matters here beyond "it stores and reads":
 *
 *  - a store that was PLAINTEXT before encryption existed is adopted by value,
 *    and the plaintext file stops existing - this is the no-re-login promise in
 *    the one shape a unit test can reproduce;
 *  - deleting a profile removes its secure files. They live in files/secure/,
 *    outside the prefs directory a profile delete scans, so that sweep is the
 *    only thing standing between a deleted kids profile's tokens and the disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureTokenStoreDataStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The files this store owns for [name], whichever mode it opened in. */
    private fun secureFiles(name: String): List<File> =
        File(context.filesDir, "secure").listFiles()
            ?.filter { it.name.startsWith("$name.") }
            .orEmpty()

    private fun legacyFile(name: String): File =
        File(context.filesDir.parentFile, "shared_prefs/$name.xml")

    @Test
    fun `a plaintext store is adopted by value and its file is gone`() {
        val name = "adopt.profile.simkl_auth"
        // Exactly the shape a pre-encryption build left behind.
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("access_token", "abc")
            .putLong("expires_at", 1234L)
            .commit()
        assertTrue("fixture: the plaintext file must exist", legacyFile(name).isFile)

        val store = SecureTokenStore.prefs(context, name, legacyPlaintext = true)

        assertEquals("abc", store.getString("access_token", null))
        assertEquals(1234L, store.getLong("expires_at", 0L))
        assertFalse("the plaintext file must not survive", legacyFile(name).exists())
    }

    @Test
    fun `a write and a read round-trip through the facade`() {
        val name = "roundtrip.profile.trakt"

        assertTrue(SecureTokenStore.prefs(context, name).edit().putString("token", "t").commit())
        assertEquals(
            "t",
            SecureTokenStore.prefs(context, name).getString("token", null)
        )
        assertTrue("the value is on disk, not only in memory", secureFiles(name).isNotEmpty())
        assertTrue(SecureTokenStore.discard(context, name))
    }

    @Test
    fun `a deleted profile takes its secure files with it`() {
        val profileId = "gone-profile"
        val name = "$profileId.simkl_auth"
        SecureTokenStore.prefs(context, name).edit().putString("token", "t").commit()
        assertTrue("fixture: a file to delete", secureFiles(name).isNotEmpty())

        SecureTokenStore.discardProfile(context, profileId)

        assertTrue("nothing left on disk", secureFiles(name).isEmpty())
        assertFalse("and nothing left to discard", SecureTokenStore.discard(context, name))
    }
}
