package com.kennyb1201.kbstream.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.security.PinVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app-level PARENT PIN (sweep-4 M11).
 *
 * Kenny's decision (2026-10-18): a kids profile with a time lock and no PIN of
 * its own had NO way out of the veil - Back is swallowed, everything is covered,
 * and the only exits were a PIN the profile did not have or the window passing.
 * One parent PIN, set once and asked for only at the lock screen, gives every
 * lock a working exit while daily profile switching stays PIN-free.
 *
 * Robolectric because the credential lives in the guard's own prefs file: the
 * hash has to be written there, read back as a HASH, and verified through the
 * same function a profile PIN uses (a second algorithm, or the same one written
 * twice, is how two stored hashes drift apart).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ParentPinTest.NoopApplication::class)
class ParentPinTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // The guard is an object with a Context it does not own (MainActivity
        // hands it one at launch), so the test plays that part.
        KidsTimeGuard.appContext = context
        prefs().edit().clear().commit()
        PinVault.setParentHash(context, null)
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        PinVault.setParentHash(context, null)
    }

    private fun prefs() =
        context.getSharedPreferences("kids_time_guard", Context.MODE_PRIVATE)

    /**
     * The hash as it is actually stored: the encrypted vault (Settings L2).
     *
     * Robolectric has no AndroidKeyStore, so [com.kennyb1201.kbstream.data.
     * security.SecureTokenStore] answers its plain-store fallback here - the same
     * store name, the same reads and writes, only without ciphertext. What this
     * file can pin is the CONTRACT (this is where the hash lives, nothing else
     * holds it, and a legacy copy is drained), not the encryption itself.
     */
    private fun vaulted(): String? = PinVault.parentHash(context)

    /** The pre-vault location, which must be empty after every path here. */
    private fun legacy(key: String): String? = prefs().getString(key, null)

    /** A profile with no PIN of its own - the case the parent PIN exists for. */
    private fun pinlessProfile() = ProfileManager.Profile(id = "kid", name = "Kid")

    // ── the credential itself ──────────────────────────────────────────

    @Test
    fun `a parent PIN round-trips, and only the hash is stored`() {
        assertFalse("nothing is set to begin with", KidsTimeGuard.hasParentPin())

        assertTrue(KidsTimeGuard.setParentPin("2468"))

        assertTrue("the guard reports it is set", KidsTimeGuard.hasParentPin())
        assertTrue("and the PIN verifies", KidsTimeGuard.verifyParentPin("2468"))
        assertFalse("a different four digits do not", KidsTimeGuard.verifyParentPin("2469"))

        val storedPin = vaulted()
        assertNotNull("something is persisted, in the vault", storedPin)
        assertNull(
            "and NOT in the plain kids_time_guard file any more (Settings L2)",
            legacy("parent_pin_hash")
        )
        // Salted (audit B2), so two hashes of the same PIN are deliberately
        // different values: what has to match is the ALGORITHM, which is what
        // "the same credential a profile PIN produces" means now.
        assertTrue(
            "and it is the same salted format a profile PIN produces, not a second algorithm",
            storedPin!!.startsWith("v2$") && ProfileManager.hashPin("2468").startsWith("v2$")
        )
        assertTrue(
            "verified through the profile-PIN function",
            ProfileManager.verifyHash(storedPin, "2468")
        )
        assertFalse(
            "the plain PIN is never written: the file holds the hash only",
            storedPin == "2468"
        )
    }

    @Test
    fun `a malformed parent PIN is refused and changes nothing`() {
        KidsTimeGuard.setParentPin("1357")
        val before = vaulted()

        listOf("12", "12345", "12a4", "abcd", "123 ").forEach { bad ->
            assertFalse("\"$bad\" is not a 4-digit PIN", KidsTimeGuard.setParentPin(bad))
        }
        assertEquals(
            "a refused input writes nothing at all",
            before,
            vaulted()
        )
        // Blank is the CLEAR value rather than a malformed one, exactly as it is
        // for a profile PIN (see ProfileManager.setPin) - which is why it is not
        // in the list above: it is the one "no PIN" spelling that is accepted.
        assertTrue(KidsTimeGuard.setParentPin("  "))
        assertFalse(KidsTimeGuard.hasParentPin())

        KidsTimeGuard.setParentPin("1357")
        assertTrue(
            "so the PIN that WAS set is still the one that answers",
            KidsTimeGuard.verifyParentPin("1357")
        )
    }

    @Test
    fun `with no parent PIN there is nothing to check, and that answers no`() {
        assertFalse(
            "the honest answer for \"no credential\": never true by default, " +
                "the same shape ProfileManager.verifyPin has for a profile with no PIN",
            KidsTimeGuard.verifyParentPin("0000")
        )
        assertNull(vaulted())
        assertNull(legacy("parent_pin_hash"))
    }

    @Test
    fun `a parent PIN set before the vault is adopted, verified and its copy erased`() {
        // A build before Settings L2 wrote this to the plain file. The read path
        // has to keep honouring it - a parent must never be locked out by an
        // upgrade - so it is drained into the vault on first use, and only once
        // that read back is the plaintext copy dropped.
        val hash = ProfileManager.hashPin("2468")
        prefs().edit().putString("parent_pin_hash", hash).commit()

        assertTrue("the old hash still answers", KidsTimeGuard.verifyParentPin("2468"))
        assertTrue("hasParentPin reads it through the same path", KidsTimeGuard.hasParentPin())
        assertEquals("and it now lives in the vault", hash, vaulted())
        assertNull(
            "with the plaintext copy gone - that is the point of the migration",
            legacy("parent_pin_hash")
        )
        assertTrue(
            "and it is still the same credential after the move",
            KidsTimeGuard.verifyParentPin("2468")
        )
    }

    @Test
    fun `clearing the parent PIN removes it`() {
        KidsTimeGuard.setParentPin("2468")
        assertTrue(KidsTimeGuard.hasParentPin())

        assertTrue(KidsTimeGuard.setParentPin(null))
        assertFalse("cleared", KidsTimeGuard.hasParentPin())
        assertNull("and the hash goes with it", vaulted())
        assertNull("from either store", legacy("parent_pin_hash"))
    }

    // ── what the veil accepts ──────────────────────────────────────────

    @Test
    fun `the override takes the parent PIN on a profile with no PIN of its own`() {
        val kid = pinlessProfile()
        assertFalse("without any PIN there is still no way in", KidsTimeGuard.overrideForSession(kid, "2468"))

        KidsTimeGuard.setParentPin("2468")

        assertTrue(
            "and with one set, the veil's own field answers for the PIN-less profile",
            KidsTimeGuard.overrideForSession(kid, "2468")
        )
    }

    @Test
    fun `the override still refuses a wrong entry`() {
        KidsTimeGuard.setParentPin("2468")

        assertFalse(
            "three right digits and one wrong is a wrong PIN",
            KidsTimeGuard.overrideForSession(pinlessProfile(), "2469")
        )
        assertFalse(
            "and an empty entry is not a credential either",
            KidsTimeGuard.overrideForSession(pinlessProfile(), "")
        )
    }

    @Test
    fun `the profile's own PIN keeps working beside it`() {
        val kid = pinlessProfile().copy(pinHash = ProfileManager.hashPin("1111"))
        KidsTimeGuard.setParentPin("2468")

        assertTrue("the profile's own PIN", KidsTimeGuard.overrideForSession(kid, "1111"))
        assertTrue("and the parent PIN", KidsTimeGuard.overrideForSession(kid, "2468"))
        assertFalse("but not the other profile's digits", KidsTimeGuard.overrideForSession(kid, "2222"))
    }
}
