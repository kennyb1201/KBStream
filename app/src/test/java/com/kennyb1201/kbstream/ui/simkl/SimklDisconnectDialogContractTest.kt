package com.kennyb1201.kbstream.ui.simkl

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disconnect confirmation reads in full, and tells the truth about the
 * session in front of it (spec 2026-11-16, phone dialog).
 *
 * Reported from a phone screenshot: the DISCONNECT button wrapped to
 * "DISCONNE / CT" and the title ellipsized to "DISCONNECT FROM S…". Both are
 * layout facts at a large font scale on a narrow panel, not one-offs - the two
 * buttons split the panel in half and the shared heading is deliberately one
 * line. The third defect was copy: the body stated the V1 truth ("the app cannot
 * revoke its own access") for every session, and V2 does revoke.
 */
class SimklDisconnectDialogContractTest {

    private val copy = SimklDisconnectCopy
    private val phone by lazy { source("com/kennyb1201/kbstream/ui/mobile/SimklConnectScreenMobile.kt") }
    private val tv by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklConnectScreen.kt") }

    @Test
    fun `a V2 session is told the grant is revoked and a V1 one is told to remove it`() {
        val current = copy.body(isLegacySession = false)
        val legacy = copy.body(isLegacySession = true)

        assertTrue(
            "V2: the app ends its own access, which is what revoke does: $current",
            current.contains("ends its own access") && !current.contains("cannot revoke")
        )
        assertTrue(
            "V1: the viewer still has to remove it at simkl.com - V1 had no revoke: $legacy",
            legacy.contains("simkl.com") && legacy.contains("cannot revoke")
        )
        assertFalse(
            "and the V1 instruction is not offered to a V2 session",
            current.contains("simkl.com")
        )
        assertTrue(
            "the two bodies differ",
            current != legacy
        )
        listOf(current, legacy).forEach { body ->
            assertTrue(
                "BOTH keep the C4/D1 fact this dialog exists to state: $body",
                body.contains("stay here") && body.contains("watch history")
            )
        }
    }

    @Test
    fun `the phone title is short enough to survive one line`() {
        assertEquals("Disconnect?", copy.TITLE_PHONE)
        assertTrue(
            "no app name in it: the shared heading ellipsizes rather than wraps",
            !copy.TITLE_PHONE.contains("Simkl", ignoreCase = true)
        )
        assertTrue(
            "and it is well under the 23 characters that truncated",
            copy.TITLE_PHONE.length <= 12
        )
        assertTrue(
            "the TV panel has the width for the naming title, so it says which app",
            copy.TITLE_TV.contains("Simkl")
        )
    }

    @Test
    fun `the phone dialog draws the short title and the session's body`() {
        val dialog = dialogBlock()
        assertTrue(
            "the short title (the block starts after this line, so it is asserted " +
                "on the file)",
            phone.contains("KBDialogPanel(title = SimklDisconnectCopy.TITLE_PHONE)")
        )
        assertTrue(
            "and the body comes from the rule, for the session actually connected",
            dialog.contains("text = SimklDisconnectCopy.body(uiState.isLegacySession)")
        )
        assertFalse(
            "no fixed paragraph left in the dialog",
            dialog.contains("Simkl keeps this app in your connected apps")
        )
    }

    @Test
    fun `the buttons no longer split the width in half`() {
        val dialog = dialogBlock()
        assertTrue(
            "a FlowRow, so the two share a line while both fit and stack when they " +
                "do not: $dialog",
            dialog.contains("FlowRow(")
        )
        assertEquals(
            "and NEITHER button is weighted: a weight is what squeezed DISCONNECT " +
                "into half a panel",
            0,
            Regex("weight\\(1f\\)").findAll(dialog).count()
        )
        val flat = dialog.replace(Regex("\\s+"), " ")
        assertTrue(
            "and each button carries no size modifier at all, so nothing can squeeze " +
                "a label into two lines: $flat",
            flat.contains("label = \"CANCEL\", onClick = { showDisconnectConfirm = false }") &&
                flat.contains("label = \"DISCONNECT\", danger = true, onClick")
        )
    }

    @Test
    fun `the TV dialog states the same thing`() {
        assertTrue(
            "the TV title names the app and is drawn from the one rule",
            tv.contains("KBDialogPanel(title = SimklDisconnectCopy.TITLE_TV)")
        )
        assertTrue(
            "its body is the session-aware body too, not a second paragraph",
            tv.contains("text = SimklDisconnectCopy.body(isLegacySession)") &&
                tv.contains("isLegacySession = uiState.isLegacySession")
        )
    }

    /**
     * The phone dialog's own source, bounded so an assertion cannot pass on a
     * slice that runs off into the rest of the file.
     */
    private fun dialogBlock(): String = phone
        .substringAfter("KBDialogPanel(title = SimklDisconnectCopy.TITLE_PHONE)")
        .take(2600)

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
