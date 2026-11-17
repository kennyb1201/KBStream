package com.kennyb1201.kbstream.ui.simkl

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kids-profile Simkl controls sit behind the parent PIN (spec 2026-11-16,
 * kids-profile reconnect/disconnect).
 *
 * What the report found: on a kids profile the connected branch drew REFRESH and
 * BACK only. RECONNECT was gated on `isLegacySession && !kidsProfile` and
 * DISCONNECT on `!kidsProfile`, and the parent PIN gate existed only on the LINK
 * path - so a kids profile holding a legacy session had no in-app route to V2
 * and no way to unlink at all.
 *
 * What the reason turned out to be: the m10 comment said DISCONNECT was hidden
 * "because it destroys the local watch corpus", and that stopped being true at
 * playback spec C4/D1 - `clearAuth()` revokes the grant and drops the session,
 * nothing else. The restriction was never about a wipe; it is about who may act,
 * and the app already owns the apparatus for that: the PIN gate the first-time
 * link uses (trackers C1).
 *
 * So both controls are drawn, and the PIN decides. This test pins the gates, the
 * order that makes the gate reachable from a CONNECTED profile, and the fact
 * that an accepted PIN does not itself disconnect.
 */
class SimklKidsParentPinContractTest {

    private val tv by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklConnectScreen.kt") }
    private val phone by lazy { source("com/kennyb1201/kbstream/ui/mobile/SimklConnectScreenMobile.kt") }
    private val vm by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklViewModel.kt") }
    private val state by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklUiState.kt") }

    @Test
    fun `the TV screen draws both controls on a kids profile`() {
        assertTrue(
            "RECONNECT is legacy-only, not kids-gated: " + reconnectSite(tv),
            tv.contains("if (uiState.isLegacySession) {\n                            KBButton(label = \"RECONNECT\"")
        )
        assertFalse(
            "the kids-profile exclusion is gone from the reconnect gate",
            tv.contains("uiState.isLegacySession && !kidsProfile")
        )
        assertTrue(
            "DISCONNECT is drawn unconditionally, and asks the ViewModel for a kids profile",
            tv.contains("label = \"DISCONNECT\",") && tv.contains("vm.requestDisconnect()")
        )
        assertFalse(
            "the button is no longer wrapped in its own kids-profile check",
            tv.contains("if (!kidsProfile) {\n                            KBButton(\n                                label = \"DISCONNECT\",")
        )
    }

    @Test
    fun `the phone screen draws both controls too`() {
        assertTrue(
            "RECONNECT without the kids clause",
            phone.contains("if (uiState.isLegacySession) {\n                                KBButton(\n                                    label = \"RECONNECT\"")
        )
        assertFalse(phone.contains("uiState.isLegacySession && !kidsProfile"))
        assertTrue(
            "DISCONNECT drawn, routed through the ViewModel for a kids profile",
            phone.contains("label = \"DISCONNECT\",") && phone.contains("vm.requestDisconnect()")
        )
    }

    @Test
    fun `the gate comes before the connected branch on both surfaces`() {
        listOf("tv" to tv, "phone" to phone).forEach { (name, source) ->
            val gate = source.indexOf("} else if (uiState.requiresParentPin) {")
            val connected = source.indexOf("} else if (uiState.isConnected) {")
            assertTrue(
                "$name: the gate must be reachable FROM a connected kids profile: " +
                    "gate=$gate connected=$connected",
                gate in 1 until connected
            )
        }
    }

    @Test
    fun `a wrong PIN and a cancel leave the session alone`() {
        assertTrue(
            "a wrong PIN is the existing line, and the gate stays up",
            vm.contains("if (!guard.verifyParentPin(pin)) {") &&
                vm.contains("pinError = true")
        )
        listOf("tv" to tv, "phone" to phone).forEach { (name, source) ->
            assertTrue(
                "$name: cancel closes the gate and nothing else runs",
                source.contains("vm.cancelParentPin()") && source.contains("if (!isConnected) onBack()")
            )
        }
    }

    @Test
    fun `an accepted PIN for DISCONNECT hands back, it does not disconnect`() {
        // One implementation for both modes (F1, 2026-11-17): a verified PIN and a
        // just-created one must leave the screen in the same state.
        val runAction = vm.substringAfter("private fun runParentAction(action: SimklParentAction) {")
            .substringBefore("/**\n     * DISCONNECT on a kids profile")
        assertTrue(
            "the action decides: $runAction",
            runAction.contains("SimklParentAction.DISCONNECT ->") &&
                runAction.contains("approvedParentAction = SimklParentAction.DISCONNECT")
        )
        assertTrue(
            "and only the LINK path starts the code request",
            runAction.substringAfter("SimklParentAction.LINK ->").contains("beginCodeRequest()") &&
                !runAction.substringBefore("SimklParentAction.LINK ->").contains("beginCodeRequest()")
        )
        assertTrue(
            "the verify path reaches it through the same function the create path uses",
            vm.substringAfter("fun submitParentPin(pin: String) {")
                .substringBefore("fun submitParentPinCreation(")
                .contains("runParentAction(_uiState.value.parentPinAction)")
        )
        listOf("tv" to tv, "phone" to phone).forEach { (name, source) ->
            assertTrue(
                "$name: the confirmation still runs, then the approval is consumed",
                source.contains("if (uiState.approvedParentAction == SimklParentAction.DISCONNECT)") &&
                    source.contains("showDisconnectConfirm = true") &&
                    source.contains("vm.consumeParentApproval()")
            )
        }
    }

    // ── 2026-11-17: no PIN is a way to SET one, not a dead end ───────────

    @Test
    fun `no parent PIN set opens the create prompt instead of a sentence`() {
        val request = vm.substringAfter("fun requestDisconnect() {")
            .substringBefore("/** The screen has acted on")
        assertTrue(
            "a kids disconnect asks for the gate, for the DISCONNECT act: $request",
            request.contains("requireParentPin(SimklParentAction.DISCONNECT)")
        )
        assertTrue(
            "for a kids profile only - a non-kids disconnect does not come through here",
            request.contains("if (ProfileManager.activeProfile.value?.kidsMaxAge == null) return")
        )
        val helper = vm.substringAfter("private fun requireParentPin(action: SimklParentAction) {")
            .substringBefore("fun submitParentPin(pin: String) {")
        assertTrue(
            "one gate opener for both acts",
            helper.contains("requiresParentPin = true") &&
                helper.contains("parentPinAction = action")
        )
        assertTrue(
            "and the MODE is whether this device holds a PIN (F1)",
            helper.contains("parentPinCreate = !guard.hasParentPin()")
        )
        assertTrue(
            "the link path uses the same opener",
            vm.substringAfter("fun generateCode()").contains("requireParentPin(SimklParentAction.LINK)")
        )
        assertFalse(
            "F3: the sentence is never the whole of what the screen shows again",
            vm.contains("errorMessage = PARENT_PIN_MISSING")
        )
    }

    @Test
    fun `the create prompt writes the PIN through the shared prompt rules`() {
        val create = vm.substringAfter("fun submitParentPinCreation(")
            .substringBefore("private fun runParentAction(")
        assertTrue(
            "the same rules the profile editors use (F2)",
            create.contains("parentPinPromptSpec(gated = true, credentialExists = guard.hasParentPin())") &&
                create.contains("submitParentPinPrompt(")
        )
        assertTrue(
            "the write is the device-local store, and its failure is honoured by the rule",
            create.contains("writePin = { pin -> guard.setParentPin(pin) }")
        )
        assertTrue(
            "a rejection is the inline message and nothing proceeds (F2)",
            create.contains("is ParentPinSubmit.Rejected ->") &&
                create.contains("parentPinMessage = result.message")
        )
        assertTrue(
            "an accepted PIN runs the pressed act without asking again (F1)",
            create.contains("is ParentPinSubmit.Accepted ->") &&
                create.contains("runParentAction(_uiState.value.parentPinAction)")
        )
    }

    @Test
    fun `both surfaces draw the create prompt, and it states why it asks`() {
        listOf("tv" to tv, "phone" to phone).forEach { (name, source) ->
            assertTrue(
                "$name: the gate has a create half",
                source.contains("if (uiState.parentPinCreate) {") &&
                    source.contains("SimklParentPinCreateGate")
            )
            val gate = source.substringAfter("if (uiState.parentPinCreate) {")
            assertTrue(
                "$name: new + confirm, and the button that creates it",
                gate.contains("placeholder = \"New PIN\"") &&
                    gate.contains("placeholder = \"Confirm PIN\"") &&
                    gate.contains("vm.submitParentPinCreation(newPin, confirmPin)")
            )
            assertTrue(
                "$name: the sentence is the prompt's reason, not the screen (F3)",
                gate.contains("message = SimklParentPinCopy.MISSING")
            )
            assertTrue(
                "$name: a rejection is shown inline, in this screen's own error style",
                gate.contains("uiState.parentPinMessage?.let")
            )
        }
    }

    @Test
    fun `the verify path a PIN-holding device sees is untouched`() {
        // The gate is split in two now, so the S4 check has to read the VERIFY
        // half itself rather than "the file mentions UNLOCK somewhere".
        listOf(
            "tv" to tv.substringAfter("private fun SimklParentPinGate(")
                .substringBefore("private fun SimklParentPinCreateGate("),
            "phone" to phone.substringAfter("private fun SimklParentPinGateMobile(")
                .substringBefore("private fun SimklParentPinCreateGateMobile(")
        ).forEach { (name, verify) ->
            assertTrue("$name: the verify gate exists", verify.length > 200)
            assertTrue(
                "$name: the field + UNLOCK / CANCEL gate is still what a PIN-holding " +
                    "device gets (S4)",
                verify.contains("label = \"UNLOCK\"") &&
                    verify.contains("label = \"CANCEL\"") &&
                    verify.contains("KeyboardType.NumberPassword")
            )
            assertTrue(
                "$name: and it still verifies through the ViewModel",
                verify.contains("vm.submitParentPin(pin)")
            )
            assertFalse(
                "$name: the verify half never writes a PIN",
                verify.contains("submitParentPinCreation")
            )
        }
    }

    @Test
    fun `the created PIN stays on this device`() {
        assertTrue(
            "F5: the write is KidsTimeGuard's device-local store",
            vm.contains("guard.setParentPin(pin)")
        )
        assertFalse(
            "and nothing about a created PIN enters a synced blob",
            vm.substringAfter("fun submitParentPinCreation(")
                .substringBefore("private fun runParentAction(")
                .contains("sync")
        )
    }

    @Test
    fun `the state carries which act the PIN is for`() {
        assertTrue(
            "the action and the handed-back approval live in the state",
            state.contains("val parentPinAction: SimklParentAction = SimklParentAction.LINK") &&
                state.contains("val approvedParentAction: SimklParentAction? = null")
        )
        assertTrue(
            "two acts, and LINK is the default so every older caller is unchanged",
            state.contains("enum class SimklParentAction {") &&
                state.contains("LINK,") &&
                state.contains("DISCONNECT,")
        )
    }

    @Test
    fun `the stale wipe justification is gone from both surfaces`() {
        listOf("tv" to tv, "phone" to phone).forEach { (name, source) ->
            assertFalse(
                "$name: the kids-profile rule is no longer justified by a wipe",
                source.contains("unlinking wipes the local watch corpus") ||
                    source.contains("unlinking clears the local watch corpus")
            )
        }
        assertTrue(
            "the ViewModel says what the rule actually is, and that disconnect only " +
                "drops the session",
            vm.contains("it is about WHO may act") &&
                vm.contains("revokes the V2") &&
                vm.contains("drops the tracker session")
        )
    }

    private fun reconnectSite(source: String): String =
        source.substringAfter("Legacy only: the same device flow", "").take(220)

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: " + file.absolutePath)
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
