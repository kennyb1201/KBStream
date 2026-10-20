package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update popup must not be a dead end on a phone (field report, 2026-10-20).
 *
 * Three things made it one, all of them invisible on a television:
 *  1. its buttons were built from the TV `KBCard`, a focus surface whose clicks
 *     arrive as Select/Enter from a remote and never from a finger, so INSTALL
 *     NOW and LATER could not be tapped;
 *  2. `Dialog(onDismissRequest = { })` swallowed Back and the scrim in every
 *     phase, so the phone's own exit did nothing;
 *  3. the panel was a fixed 560dp, wider than a portrait phone.
 *
 * The fix is a mobile branch only: touch plates, a phase-aware dismiss that can
 * only ever do what the phase's own button does, and a capped width. The
 * television rendering and its Back-does-nothing barrier are byte-identical, and
 * that half is pinned here too - a fix that changed the TV dialog would be a
 * regression dressed as a fix.
 */
class UpdatePopupMobileInputContractTest {

    /** `MainActivity`, whitespace-collapsed so a wrapped call still matches. */
    private val source: String by lazy {
        MainActivitySource.read().replace(Regex("\\s+"), " ")
    }

    /** The popup itself, bounded by the next declaration after it. */
    private val popup: String by lazy {
        val start = source.indexOf("private fun UpdateAvailablePopup(")
        assertTrue("UpdateAvailablePopup must exist", start >= 0)
        val end = source.indexOf("/** * The destination a Home Browse tile opens.", start)
        assertTrue("the popup body must be bounded by the next declaration", end > start)
        source.substring(start, end)
    }

    /** The button-plate helper, which is declared after the popup. */
    private val plate: String by lazy {
        val start = source.indexOf("private fun DialogPlate(")
        assertTrue("DialogPlate must exist", start >= 0)
        val end = source.indexOf("@Composable private fun UpdateDialogButton(", start)
        assertTrue("the plate body must be bounded by the next composable", end > start)
        source.substring(start, end)
    }

    @Test
    fun `the buttons are touch plates on a phone and the tv card on a television`() {
        assertTrue(
            "the form factor has to be read once, from the app's own switch: $popup",
            popup.contains("val mobile = !FormFactor.isTv")
        )
        assertEquals(
            "every one of the six buttons (INSTALL NOW / LATER / HIDE / DONE / " +
                "TRY AGAIN / CLOSE) has to go through the plate, or the one left " +
                "behind is the one a finger cannot press",
            6,
            Regex("DialogPlate\\(mobile = mobile,").findAll(popup).count()
        )
        assertFalse(
            "and none may still be built straight from the TV card",
            popup.contains("KBCard(onClick")
        )
        assertTrue(
            "the plate is the touch twin on a phone: $plate",
            plate.contains("if (mobile) { TouchKBCard(onClick = onClick)")
        )
        assertTrue(
            "and the TV card everywhere else, unchanged",
            plate.contains("} else { KBCard(onClick = onClick)")
        )
    }

    @Test
    fun `back and the scrim mean Later on a phone and nothing at all on a tv`() {
        assertTrue(
            "the dialog must hand its dismiss to the phase-aware lambda, not a " +
                "barrier that eats Back: $popup",
            popup.contains("Dialog(onDismissRequest = onDismiss)")
        )
        assertFalse(
            "the old barrier must be gone",
            popup.contains("Dialog(onDismissRequest = { })")
        )

        val mobileDismiss = popup.substring(
            popup.indexOf("val onDismiss: () -> Unit = if (mobile)"),
            popup.indexOf("Dialog(onDismissRequest = onDismiss)")
        )
        assertTrue(
            "Offer: exactly the LATER action - hide, then the per-version " +
                "dismissal that keeps it down after a relaunch: $mobileDismiss",
            mobileDismiss.contains("UpdatePhase.Offer -> available?.let { offer ->") &&
                mobileDismiss.contains("hidden = true") &&
                mobileDismiss.contains("AppUpdater.dismissPopup(context, offer)")
        )
        assertTrue(
            "Installed and Failed: acknowledge(), the DONE and CLOSE actions",
            mobileDismiss.contains("UpdatePhase.Installed, UpdatePhase.Failed -> AppUpdater.acknowledge()")
        )
        assertTrue(
            "Downloading and Installing: a no-op, so a stray Back cannot orphan " +
                "the download (Installing keeps its HIDE button)",
            mobileDismiss.contains("UpdatePhase.Downloading, UpdatePhase.Installing -> Unit")
        )
        assertTrue(
            "and the television keeps the barrier, untouched: $popup",
            popup.contains("} else { {} }")
        )
    }

    @Test
    fun `the panel fits a portrait phone without touching the television width`() {
        assertTrue(
            "the phone panel is the screen minus a 16dp gutter, capped at the " +
                "television width: $popup",
            popup.contains(".widthIn(max = 560.dp)") &&
                popup.contains(".fillMaxWidth()") &&
                popup.contains(".padding(horizontal = 16.dp)")
        )
        val mobilePanel = popup.substring(
            popup.indexOf("if (mobile) { Modifier"),
            popup.indexOf("} else { Modifier.width(560.dp)")
        )
        assertTrue(
            "the cap has to be applied BEFORE the fill, or fillMaxWidth claims " +
                "the whole screen and widthIn can never bite: $mobilePanel",
            mobilePanel.indexOf("widthIn(") in 0 until mobilePanel.indexOf("fillMaxWidth()")
        )
        assertTrue(
            "and the television keeps its fixed panel",
            popup.contains("} else { Modifier.width(560.dp)")
        )
    }

    @Test
    fun `the exit prompt gets the same phones-only branch`() {
        // Found by the hardening sweep, not by the field: the exit prompt (Back
        // on Home -> "EXIT KBSTREAM?") built STAY and EXIT from the same TV card,
        // so both were dead to a finger. It was escapable - Back runs onDismiss
        // - which is why it never reached a bug report, but the check is "every
        // interactive element is operable by touch".
        val start = source.indexOf("private fun ExitConfirmDialog(")
        assertTrue("ExitConfirmDialog must exist", start >= 0)
        val end = source.indexOf("private fun ExitDialogButton(", start)
        assertTrue("the exit prompt body must be bounded by its button", end > start)
        val exit = source.substring(start, end)

        assertTrue(
            "it has to know which surface it is on: $exit",
            exit.contains("val mobile = !FormFactor.isTv")
        )
        assertTrue(
            "and both answers are touch plates on a phone",
            exit.contains("ExitDialogButton(label = \"STAY\", mobile = mobile") &&
                exit.contains("ExitDialogButton(label = \"EXIT\", mobile = mobile")
        )
        assertTrue(
            "the panel is capped for a portrait phone and fixed on a television",
            exit.contains(".widthIn(max = 620.dp)") &&
                exit.contains("Modifier.width(620.dp)")
        )
        assertTrue(
            "and Back still dismisses it",
            exit.contains("Dialog(onDismissRequest = onDismiss)")
        )
    }

    @Test
    fun `no dialog in the shell swallows its own dismiss`() {
        // The class the report named: a barrier for `onDismissRequest` leaves a
        // phone with no exit at all. Only the update prompt and the exit prompt
        // are dialogs in this file; neither may regress.
        assertFalse(
            "a dialog that ignores Back is a dead end on a phone",
            source.contains("Dialog(onDismissRequest = { })")
        )
    }

    private object MainActivitySource {
        fun read(): String {
            val file = File(
                findMainSourceRoot(),
                "com/kennyb1201/kbstream/MainActivity.kt"
            )
            assertTrue("source missing: $file", file.isFile)
            return file.readText()
        }

        private fun findMainSourceRoot(): File {
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
    }
}
