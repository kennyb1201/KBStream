package com.kennyb1201.kbstream.ui.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.KidsGate
import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import com.kennyb1201.kbstream.data.sync.ProfileManager
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parent-PIN prompt's CONTRACT: verify-only when a credential exists,
 * create when it does not, rotate only from the Parent PIN row, one verification
 * per editor session, and a failed write that refuses instead of closing.
 *
 * The field report this comes from: one change on the kids profile asked for a
 * PIN in three places, and the PIN "never saved". Nothing was wrong with the
 * credential - it is stored and verified here against the real store - the
 * prompt had no way to USE it: the gated dialog always demanded a New PIN +
 * Confirm and wrote it, so the stored PIN could only ever be rotated, and the
 * write's `false` return was discarded.
 *
 * Robolectric because the parent PIN lives in the guard's device-local prefs;
 * the decision itself is pure and is driven here through the same functions the
 * two editors call.
 */
@RunWith(AndroidJUnit4::class)
class ParentPinPromptTest {

    private val kids = ProfileManager.Profile(id = "kids", name = "Kids", kidsMaxAge = 7)

    @Before
    fun setUp() {
        KidsTimeGuard.appContext = ApplicationProvider.getApplicationContext<Context>()
        KidsTimeGuard.setParentPin(null)
    }

    @After
    fun tearDown() {
        KidsTimeGuard.setParentPin(null)
    }

    // ── §1: the gated prompt gets a true verify mode ────────────────────

    @Test
    fun `a gated prompt with an existing credential is verify-only`() {
        KidsTimeGuard.setParentPin("1234")
        val spec = parentPinPromptSpec(
            gated = true,
            credentialExists = KidsGate.hasCredential(kids)
        )

        assertEquals(ParentPinPromptMode.VERIFY, spec.mode)
        assertTrue("one field: the PIN", spec.askCurrentPin)
        assertFalse(
            "no new/confirm fields on a gate - the new PIN is not even composed",
            spec.askNewPin
        )

        var writes = 0
        val accepted = submitParentPin(
            spec = spec,
            gateTarget = kids,
            currentPin = "1234",
            newPin = "",
            confirmPin = "",
            writePin = { writes++; true }
        )

        assertEquals(ParentPinSubmit.Accepted(wrotePin = false), accepted)
        assertEquals("a verification writes nothing", 0, writes)
        assertTrue(
            "and the credential that verified it is untouched - no forced rotation",
            KidsTimeGuard.verifyParentPin("1234")
        )
    }

    @Test
    fun `a wrong PIN fails in place and writes nothing`() {
        KidsTimeGuard.setParentPin("1234")
        val spec = parentPinPromptSpec(gated = true, credentialExists = true)

        var writes = 0
        val rejected = submitParentPin(
            spec = spec,
            gateTarget = kids,
            currentPin = "9999",
            newPin = "",
            confirmPin = "",
            writePin = { writes++; true }
        )

        assertEquals(ParentPinSubmit.Rejected(PARENT_PIN_INCORRECT), rejected)
        assertEquals(0, writes)
        assertTrue(KidsTimeGuard.verifyParentPin("1234"))
    }

    @Test
    fun `a gated prompt with no credential keeps the create flow`() {
        val spec = parentPinPromptSpec(
            gated = true,
            credentialExists = KidsGate.hasCredential(kids)
        )

        assertEquals(ParentPinPromptMode.CREATE, spec.mode)
        assertFalse("there is nothing to verify against yet", spec.askCurrentPin)
        assertTrue(spec.askNewPin)

        val accepted = submitParentPin(
            spec = spec,
            gateTarget = kids,
            currentPin = "",
            newPin = "4321",
            confirmPin = "4321"
        )

        assertEquals(ParentPinSubmit.Accepted(wrotePin = true), accepted)
        assertTrue("the audit's create-instead-of-failing-open half", KidsTimeGuard.hasParentPin())
        assertTrue(KidsTimeGuard.verifyParentPin("4321"))
    }

    // ── §3: the write's own answer is honoured ─────────────────────────

    @Test
    fun `a write that fails keeps the dialog and refuses the action`() {
        val spec = parentPinPromptSpec(gated = true, credentialExists = false)

        val rejected = submitParentPin(
            spec = spec,
            gateTarget = kids,
            currentPin = "",
            newPin = "4321",
            confirmPin = "4321",
            writePin = { false }
        )

        assertEquals(ParentPinSubmit.Rejected(PARENT_PIN_SAVE_FAILED), rejected)
        assertFalse("and nothing was stored", KidsTimeGuard.hasParentPin())
    }

    // ── the Parent PIN row: the only place a new PIN is demanded ────────

    @Test
    fun `the Parent PIN row rotates, and a first SET asks for no current PIN`() {
        KidsTimeGuard.setParentPin("1234")
        val rotate = parentPinPromptSpec(
            gated = false,
            credentialExists = KidsGate.hasCredential(null)
        )

        assertEquals(ParentPinPromptMode.ROTATE, rotate.mode)
        assertTrue(rotate.askCurrentPin)
        assertTrue(rotate.askNewPin)
        assertEquals(
            "the current PIN is verified before a change, as it always was",
            ParentPinSubmit.Rejected(PARENT_PIN_CURRENT_INCORRECT),
            submitParentPin(rotate, gateTarget = null, "9999", "5678", "5678")
        )
        assertEquals(
            ParentPinSubmit.Rejected(PARENT_PIN_MISMATCH),
            submitParentPin(rotate, gateTarget = null, "1234", "5678", "5679")
        )
        assertEquals(
            ParentPinSubmit.Accepted(wrotePin = true),
            submitParentPin(rotate, gateTarget = null, "1234", "5678", "5678")
        )
        assertTrue(KidsTimeGuard.verifyParentPin("5678"))
        assertFalse(KidsTimeGuard.verifyParentPin("1234"))

        val firstSet = parentPinPromptSpec(gated = false, credentialExists = false)
        assertEquals(ParentPinPromptMode.ROTATE, firstSet.mode)
        assertFalse("a first SET does not ask for a PIN that does not exist", firstSet.askCurrentPin)
        assertTrue(firstSet.askNewPin)
    }

    // ── §2: one verification unlocks the editor session ─────────────────

    @Test
    fun `two gated actions in one editor session prompt once`() {
        KidsTimeGuard.setParentPin("1234")
        val spec = parentPinPromptSpec(gated = true, credentialExists = true)
        val session = ParentPinSession()

        var prompts = 0
        var ran = 0
        var pending: (() -> Unit)? = null
        val gate: (() -> Unit) -> Unit = { action ->
            session.gate(action = action) { gated ->
                prompts++
                pending = gated
            }
        }

        // First gated action: the prompt is raised for it.
        gate { ran++ }
        assertEquals(1, prompts)
        assertEquals("nothing runs before the PIN", 0, ran)

        // The prompt's own SAVE: verify mode, so nothing is written.
        assertEquals(
            ParentPinSubmit.Accepted(wrotePin = false),
            submitParentPin(spec, kids, "1234", "", "")
        )
        session.markVerified()
        pending?.invoke()
        assertEquals("the interrupted action runs once verified", 1, ran)

        // The rest of the sitting: no second prompt (three for one change was
        // the field report).
        gate { ran++ }
        gate { ran++ }
        assertEquals(1, prompts)
        assertEquals(3, ran)
    }

    @Test
    fun `a fresh editor session prompts again`() {
        val session = ParentPinSession()
        var prompts = 0
        session.gate(action = {}) { prompts++ }
        session.markVerified()
        session.gate(action = {}) { prompts++ }
        assertEquals("verified sessions do not re-ask", 1, prompts)

        // Reopening the editor is a new session: UI state, never persisted.
        val reopened = ParentPinSession()
        reopened.gate(action = {}) { prompts++ }
        assertEquals(2, prompts)
        assertFalse("and a fresh session starts unverified", reopened.verified)
    }

    // ── preserved: a first profile PIN still needs a parent PIN ─────────

    @Test
    fun `a first profile PIN still cannot be minted without a parent PIN`() {
        assertFalse(KidsTimeGuard.hasParentPin())
        assertTrue(
            "M6: no parent PIN and no profile PIN - the save gate refuses",
            KidsGate.firstPinNeedsParentPin(kids)
        )
        KidsTimeGuard.setParentPin("1234")
        assertFalse(
            "with one, the two-step (row first) is what authorises it",
            KidsGate.firstPinNeedsParentPin(kids)
        )
    }

    // ── §4: both editors call the one contract ─────────────────────────

    @Test
    fun `both editors use the one shared prompt contract`() {
        listOf(EDITOR_TV, EDITOR_MOBILE).forEach { path ->
            val screen = flat(path)
            assertTrue(
                "$path must hold the session as editor state",
                screen.contains("rememberParentPinSession(effectiveEditId)")
            )
            assertTrue(
                "$path must gate through it, so one verification covers the sitting",
                screen.contains("parentPinSession.gate(action = action)")
            )
            assertTrue(
                "$path must take its fields from the shared rule",
                screen.contains("parentPinPromptSpec(") &&
                    screen.contains("if (spec.askCurrentPin)") &&
                    screen.contains("if (spec.askNewPin)")
            )
            assertTrue(
                "$path must submit through the shared rule",
                screen.contains("submitParentPin(") &&
                    screen.contains("is ParentPinSubmit.Rejected -> parentPinError = result.message")
            )
            assertTrue(
                "$path must only close and run the action on acceptance",
                screen.contains("is ParentPinSubmit.Accepted ->") &&
                    screen.contains("parentPinSession.markVerified()") &&
                    screen.indexOf("parentPinSession.markVerified()") <
                    screen.indexOf("after?.invoke()")
            )
            assertFalse(
                "$path must not write the PIN itself: rotation has one owner",
                screen.contains("KidsTimeGuard.setParentPin(")
            )
        }

        val helper = flat(HELPER)
        assertTrue(
            "the helper is where both credentials are accepted",
            helper.contains("if (KidsGate.verifies(gateTarget, currentPin))")
        )
        assertTrue(
            "and where a failed write refuses rather than closing",
            helper.contains("!writePin(newPin) -> ParentPinSubmit.Rejected(PARENT_PIN_SAVE_FAILED)")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun flat(relative: String): String =
        read(relative)
            .lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }
            .replace(Regex("\\s+"), " ")

    private fun read(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun sourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val PROFILES = "com/kennyb1201/kbstream/ui/profiles/"
        const val EDITOR_TV = "${PROFILES}ProfileEditScreen.kt"
        const val EDITOR_MOBILE = "com/kennyb1201/kbstream/ui/mobile/ProfileEditScreenMobile.kt"
        const val HELPER = "${PROFILES}ParentPinPrompt.kt"
    }
}
