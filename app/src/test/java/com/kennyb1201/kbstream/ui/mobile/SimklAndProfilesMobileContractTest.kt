package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phases 2's rule for the two launch-path screens: one `FormFactor` branch in
 * each TV file, the phone layout in a tv-free file, and the same state holders
 * and gates behind both.
 *
 * Both screens carry a safety rule that a port can quietly lose - Simkl's
 * disconnect confirmation (and its absence on a kids profile), and the
 * profile picker's PIN / kids exit gates - so those are pinned here by name.
 */
class SimklAndProfilesMobileContractTest {

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

    private fun simklScreen(): String =
        source("com/kennyb1201/kbstream/ui/simkl/SimklConnectScreen.kt")

    private fun simklMobile(): String =
        source("com/kennyb1201/kbstream/ui/mobile/SimklConnectScreenMobile.kt")

    private fun pickerScreen(): String =
        source("com/kennyb1201/kbstream/ui/profiles/ProfilePickerScreen.kt")

    private fun pickerMobile(): String =
        source("com/kennyb1201/kbstream/ui/mobile/ProfilePickerScreenMobile.kt")

    @Test
    fun `both screens dispatch on the form factor at their entry`() {
        val simkl = simklScreen()
        val simklDispatch = simkl.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val simklCall = simkl.indexOf("SimklConnectScreenMobile(")
        assertTrue("SimklConnectScreen must branch on FormFactor.isTv", simklDispatch >= 0)
        assertTrue(simklCall > simklDispatch)
        val simklBlock = simkl.substring(simklCall).substringBefore("val uiState")
        assertTrue(
            "the Simkl branch hands over the same callback and ViewModel",
            simklBlock.contains("onBack = onBack") && simklBlock.contains("vm = vm")
        )
        assertTrue(
            "the Simkl branch returns, so the TV page never composes on a phone",
            simklBlock.contains("return")
        )
        assertTrue(
            "the TV Simkl page is still the panel it was",
            simkl.substringAfter("val uiState").contains("DisconnectConfirmDialog(")
        )

        val picker = pickerScreen()
        val pickerDispatch = picker.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val pickerCall = picker.indexOf("ProfilePickerScreenMobile(")
        assertTrue("ProfilePickerScreen must branch on FormFactor.isTv", pickerDispatch >= 0)
        assertTrue(pickerCall > pickerDispatch)
        val pickerBlock = picker.substring(pickerCall).substringBefore("val pickerContext")
        assertTrue(
            "the picker branch hands over both navigation callbacks",
            pickerBlock.contains("onSelect = onSelect") &&
                pickerBlock.contains("onManage = onManage")
        )
        assertTrue(
            "the picker branch returns, so the TV page never composes on a phone",
            pickerBlock.contains("return")
        )
        assertTrue(
            "the TV picker still wraps tiles into rows of five",
            picker.substringAfter("val pickerContext").contains("PROFILES_PER_ROW")
        )
    }

    @Test
    fun `both mobile screens are built on the touch twins and the mobile theme`() {
        listOf("Simkl" to simklMobile(), "Profiles" to pickerMobile())
            .forEach { (name, mobile) ->
                assertTrue("$name must apply MobileTheme", mobile.contains("MobileTheme {"))
                assertTrue(
                    "$name must use the touch button and dialog plate",
                    mobile.contains("ui.components.touch.KBButton") &&
                        mobile.contains("ui.components.touch.KBDialogPanel")
                )
                assertFalse(
                    "$name must not import a tv-material3 widget",
                    mobile.contains("import androidx.tv.material3.")
                )
            }
        assertTrue(
            "the PIN fields are the touch text field",
            pickerMobile().contains("ui.components.touch.KBTextField")
        )
    }

    @Test
    fun `the mobile Simkl page drives the tv view model`() {
        val mobile = simklMobile()
        listOf(
            "vm.uiState",
            "vm::generateCode",
            "vm::refreshSimklNow",
            "vm.disconnect()",
            "uiState.isConnected",
            "uiState.accountName",
            "uiState.watchedCounts",
            "uiState.verificationUrl",
            "uiState.userCode",
            "uiState.statusMessage",
            "uiState.errorMessage",
            "uiState.isLoadingWatching"
        ).forEach { read ->
            assertTrue("mobile Simkl must use $read", mobile.contains(read))
        }
        assertTrue(
            "the code is shown as a code, with its own label",
            mobile.contains("\"CODE\"") && mobile.contains("style = MaterialTheme.typography.displaySmall")
        )
    }

    @Test
    fun `the Simkl disconnect keeps both safety rules`() {
        val mobile = simklMobile()
        assertTrue(
            "a kids profile does not get the destructive button at all",
            mobile.contains("kidsMaxAge != null") &&
                mobile.contains("if (!kidsProfile)")
        )
        assertTrue(
            "the press opens a confirmation, never the disconnect itself",
            mobile.contains("showDisconnectConfirm = true") &&
                mobile.contains("Dialog(onDismissRequest = { showDisconnectConfirm = false })")
        )
        assertTrue(
            "the dialog names the wipe, not just the unlink",
            mobile.contains("clears every") &&
                mobile.contains("watched marker") &&
                mobile.contains("cannot be undone")
        )
        assertTrue(
            "only the confirm calls the view model",
            mobile.contains("vm.disconnect()")
        )
    }

    @Test
    fun `the mobile picker keeps the enter and exit gates`() {
        val mobile = pickerMobile()
        assertTrue(
            "the active profile enters without rebinding the singletons",
            mobile.contains("active?.id == profile.id -> onSelect()")
        )
        assertTrue(
            "a profile with its own PIN asks for THAT PIN",
            mobile.contains("ProfileManager.hasPin(profile)") &&
                mobile.contains("ProfileManager.verifyPin(pinProfile, pinEntry)")
        )
        assertTrue(
            "four digits verify themselves, and a wrong one clears the entry",
            mobile.contains("if (pinEntry.length == 4)") &&
                mobile.contains("pinError = true") &&
                mobile.contains("pinEntry = \"\"")
        )
        assertTrue(
            "leaving a gated kids profile asks first",
            mobile.contains("KidsGate.leavingNeedsPin(active)") &&
                mobile.contains("openExitGate(ExitGateAction.Switch(profile))")
        )
        assertTrue(
            "the exit gate accepts the parent PIN and never fails open",
            mobile.contains("KidsGate.hasCredential(active)") &&
                mobile.contains("KidsGate.verifies(gateProfile, exitGateEntry)")
        )
        assertTrue(
            "with no credential it creates the parent PIN before leaving",
            mobile.contains("ProfileManager.isValidPin(exitGateNew)") &&
                mobile.contains("KidsTimeGuard.setParentPin(exitGateNew)") &&
                mobile.contains("PINs don't match")
        )
        assertTrue(
            "MANAGE sits in the same grid and is gated the same way",
            mobile.contains("\"MANAGE\"") &&
                mobile.contains("ExitGateAction.Manage")
        )
    }

    @Test
    fun `the mobile picker draws the same avatars`() {
        val mobile = pickerMobile()
        assertTrue(
            "a custom avatar is drawn first",
            mobile.contains("customAvatarUrl ?: profile.avatarData") &&
                mobile.contains("AsyncImage(")
        )
        assertTrue(
            "a generic avatar keeps the palette and the initial",
            mobile.contains("ProfileManager.AVATAR_COLORS[") &&
                mobile.contains("name.take(1).uppercase()")
        )
        assertTrue(
            "the selected profile keeps its light ring",
            mobile.contains("width = if (selected) 3.dp else 0.dp")
        )
    }
}
