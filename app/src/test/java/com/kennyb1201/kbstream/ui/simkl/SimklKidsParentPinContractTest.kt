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
        val submit = vm.substringAfter("fun submitParentPin(pin: String) {")
            .substringBefore("/**\n     * DISCONNECT on a kids profile")
        assertTrue(
            "the action decides: $submit",
            submit.contains("when (_uiState.value.parentPinAction)") &&
                submit.contains("SimklParentAction.DISCONNECT ->") &&
                submit.contains("approvedParentAction = SimklParentAction.DISCONNECT")
        )
        assertTrue(
            "and only the LINK path starts the code request",
            submit.substringAfter("SimklParentAction.LINK ->").contains("beginCodeRequest()") &&
                !submit.substringBefore("SimklParentAction.LINK ->").contains("beginCodeRequest()")
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

    @Test
    fun `no parent PIN set is the existing error and nothing else`() {
        val request = vm.substringAfter("fun requestDisconnect() {")
            .substringBefore("/** The screen has acted on")
        assertTrue(
            "the missing-PIN branch sets the sentence and returns: $request",
            request.contains("if (!guard.hasParentPin())") &&
                request.contains("errorMessage = PARENT_PIN_MISSING") &&
                request.contains("requiresParentPin = false")
        )
        assertTrue(
            "for a kids profile only - a non-kids disconnect does not come through here",
            request.contains("if (ProfileManager.activeProfile.value?.kidsMaxAge == null) return")
        )
        assertTrue(
            "and the gate is what the PIN-set path opens, for the DISCONNECT action",
            request.contains("parentPinAction = SimklParentAction.DISCONNECT")
        )
        assertTrue(
            "the sentence is one constant, shared with the link path",
            vm.contains("const val PARENT_PIN_MISSING") &&
                vm.substringAfter("fun generateCode()").contains("errorMessage = PARENT_PIN_MISSING")
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
