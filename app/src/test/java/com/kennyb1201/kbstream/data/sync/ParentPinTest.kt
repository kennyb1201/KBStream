package com.kennyb1201.kbstream.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        context.getSharedPreferences("kids_time_guard", Context.MODE_PRIVATE)

    private fun stored(key: String): String? = prefs().getString(key, null)

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

        val storedPin = stored("parent_pin_hash")
        assertNotNull("something is persisted", storedPin)
        assertEquals(
            "and it is the SAME hash a profile PIN produces, not a second algorithm",
            ProfileManager.hashPin("2468"),
            storedPin
        )
        assertFalse(
            "the plain PIN is never written: the file holds the hash only",
            storedPin == "2468"
        )
    }

    @Test
    fun `a malformed parent PIN is refused and changes nothing`() {
        KidsTimeGuard.setParentPin("1357")

        listOf("12", "12345", "12a4", "abcd", "123 ").forEach { bad ->
            assertFalse("\"$bad\" is not a 4-digit PIN", KidsTimeGuard.setParentPin(bad))
        }
        // Blank is the CLEAR value rather than a malformed one, exactly as it is
        // for a profile PIN (see ProfileManager.setPin) - which is why it is not
        // in the list above: it is the one "no PIN" spelling that is accepted.
        assertTrue(KidsTimeGuard.setParentPin("  "))
        assertFalse(KidsTimeGuard.hasParentPin())
        KidsTimeGuard.setParentPin("1357")

        assertEquals(
            "so the PIN that WAS set is still the one that answers",
            ProfileManager.hashPin("1357"),
            stored("parent_pin_hash")
        )
        assertTrue(KidsTimeGuard.verifyParentPin("1357"))
    }

    @Test
    fun `with no parent PIN there is nothing to check, and that answers no`() {
        assertFalse(
            "the honest answer for \"no credential\": never true by default, " +
                "the same shape ProfileManager.verifyPin has for a profile with no PIN",
            KidsTimeGuard.verifyParentPin("0000")
        )
        assertNull(stored("parent_pin_hash"))
    }

    @Test
    fun `clearing the parent PIN removes it`() {
        KidsTimeGuard.setParentPin("2468")
        assertTrue(KidsTimeGuard.hasParentPin())

        assertTrue(KidsTimeGuard.setParentPin(null))
        assertFalse("cleared", KidsTimeGuard.hasParentPin())
        assertNull("and the hash goes with it", stored("parent_pin_hash"))
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
