package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile editor's turn at Phase 2's rule: one `FormFactor` branch in the TV
 * file, the phone layout in a new tv-free file, and the same `ProfileManager`
 * writes and `KidsGate` decisions behind both.
 *
 * The editor is where a port can quietly weaken a lock, so the gates are pinned
 * by name: a first PIN needs an existing parent PIN, a kids change / PIN change /
 * profile creation beside a kids profile needs the parent PIN first, DELETE is
 * guarded, and backing out of the prompt refuses the action.
 */
class ProfileEditScreenMobileContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun editorScreen(): String =
        source("com/kennyb1201/kbstream/ui/profiles/ProfileEditScreen.kt")

    private fun editorMobile(): String =
        source("com/kennyb1201/kbstream/ui/mobile/ProfileEditScreenMobile.kt")

    @Test
    fun `the editor dispatches on the form factor at its entry`() {
        val tv = editorScreen()
        val dispatch = tv.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val mobileCall = tv.indexOf("ProfileEditScreenMobile(")
        assertTrue("ProfileEditScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call ProfileEditScreenMobile", mobileCall > dispatch)
        val block = tv.substring(mobileCall).substringBefore("val context")
        assertTrue(
            "the branch hands the edited id and the done callback over",
            block.contains("editingProfileId = editingProfileId") &&
                block.contains("onDone = onDone")
        )
        assertTrue(
            "the branch returns, so the TV editor never composes on a phone",
            block.contains("return")
        )
        assertTrue(
            "the TV editor is still the form it was",
            tv.substringAfter("val context").contains("KidsOptionChipRow(") &&
                tv.substringAfter("val context").contains("KBPageTitle(")
        )
    }

    @Test
    fun `the mobile editor is built on the touch twins and the mobile theme`() {
        val mobile = editorMobile()
        assertTrue("the screen must apply MobileTheme", mobile.contains("MobileTheme {"))
        listOf(
            "ui.components.touch.KBTextField",
            "ui.components.touch.KBButton",
            "ui.components.touch.KBDialogPanel",
            "ui.components.touch.TouchChoiceChip",
            "ui.components.touch.KBPasteChip"
        ).forEach { twin ->
            assertTrue("the form must use $twin", mobile.contains(twin))
        }
        assertFalse(
            "the mobile editor must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
    }

    @Test
    fun `the save writes the same profile fields as the tv editor`() {
        val mobile = editorMobile()
        listOf(
            "ProfileManager.createAndMigrateLegacy(",
            "ProfileManager.rename(",
            "ProfileManager.finalizeAvatar(",
            "ProfileManager.setCustomAvatar(",
            "ProfileManager.setGuest(",
            "ProfileManager.setKidsMaxAge(",
            "ProfileManager.setKidsOptions(",
            "ProfileManager.setPin("
        ).forEach { write ->
            assertTrue("the mobile save must call $write", mobile.contains(write))
        }
        assertTrue(
            "a new profile's PIN is written to the row that now exists",
            mobile.contains("pinTargetId = created.id")
        )
        assertTrue(
            "the PIN fields validate the current, new and confirm entries",
            mobile.contains("ProfileManager.verifyPin(requireNotNull(editing), currentPinInput)") &&
                mobile.contains("newPinInput.length == 4 && newPinInput == confirmPinInput") &&
                mobile.contains("\"Current PIN is incorrect\"") &&
                mobile.contains("\"PINs don't match\"")
        )
        assertTrue(
            "the bedtime keeps the shared conversions, so a :30 stays a :30",
            mobile.contains("bedtimeMinutesFor(kidsBedtime, kidsBedtimeMinute)") &&
                mobile.contains("bedtimeChipFor(mins)")
        )
    }

    @Test
    fun `the mobile editor keeps every parent-PIN gate`() {
        val mobile = editorMobile()
        assertTrue(
            "M6: a first PIN on a PIN-less profile is refused",
            mobile.contains("KidsGate.firstPinNeedsParentPin(editing)") &&
                mobile.contains("\"Set a parent PIN first\"")
        )
        assertTrue(
            "C2/C5: a kids change, PIN change or creation beside a kids profile " +
                "asks for the parent PIN",
            mobile.contains("KidsGate.saveNeedsPin(") &&
                mobile.contains("kidsChanged = kidsChanged") &&
                mobile.contains("creatingProfile = editing == null") &&
                mobile.contains("pinChanging = anyPinField") &&
                mobile.contains("anyKidsProfileExists = profiles.any { it.kidsMaxAge != null }")
        )
        assertTrue(
            "a lock being set says why the PIN is needed",
            mobile.contains("\"Required to set this limit\"")
        )
        assertTrue(
            "the prompt accepts the profile's PIN or the parent PIN",
            mobile.contains("KidsGate.hasCredential(gateTarget)") &&
                mobile.contains("KidsGate.verifies(gateTarget, parentPinCurrent)") &&
                mobile.contains("KidsTimeGuard.setParentPin(parentPinNew)")
        )
        assertTrue(
            "backing out of a gated prompt refuses the action",
            mobile.contains("if (refused && wasGated)") &&
                mobile.contains("\"A parent PIN is needed for that\"")
        )
        assertTrue(
            "DELETE is gated by the same rule as on TV",
            mobile.contains("KidsGate.deleteNeedsPin(") &&
                mobile.contains("ProfileManager.delete(context, target.id)")
        )
        assertTrue(
            "another profile's editor is gated when it carries a PIN or is a kids one",
            mobile.contains("KidsGate.hasOwnPin(p) || KidsGate.isKids(p)")
        )
    }

    @Test
    fun `the mobile editor keeps the avatar and manage-mode surfaces`() {
        val mobile = editorMobile()
        assertTrue(
            "an uploaded photo and a typed URL both reach finalizeAvatar",
            mobile.contains("imagePicker.launch(\"image/*\")") &&
                mobile.contains("ProfileManager.importAvatarImage(context, uri)") &&
                mobile.contains("startsWith(\"http\")")
        )
        assertTrue(
            "REMOVE PHOTO clears the custom image",
            mobile.contains("\"REMOVE PHOTO\"") &&
                mobile.contains("ProfileManager.setCustomAvatar(context, it.id, null)")
        )
        assertTrue(
            "the generic avatar colours are offered",
            mobile.contains("ProfileManager.AVATAR_COUNT") &&
                mobile.contains("ProfileManager.AVATAR_COLORS[")
        )
        assertTrue(
            "manage mode can switch target or start a new profile",
            mobile.contains("label = \"+ New\"") &&
                mobile.contains("effectiveEditId = p.id")
        )
        assertTrue(
            "a brand-new install explains the library migration",
            mobile.contains("be moved into this profile so nothing is lost")
        )
    }
}
