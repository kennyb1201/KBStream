package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anime trackers' connect dialogs are USABLE on the phone (spec 2026-11-14).
 *
 * The field report: the Kitsu screen on the phone is untappable, and AniList and
 * MAL are the same - tapping the row does open the dialog, and then nothing
 * inside it responds. One shared cause: the panel had been given a touch twin
 * (`AnimeDialogPanel`) and the bodies had not, so on the phone every button was
 * still a tv-material3 `Card` waiting for Select on a remote and every field was
 * still the D-pad `BasicTextField` waiting for a focus requester. The dialog drew
 * and was dead.
 *
 * Pinned here:
 *  - F1: the same `phone` flag the plate already used picks the body components
 *    too, in all three dialogs, and no TV card or field is left in a body;
 *  - F2: the behaviours the bodies rely on survive the swap - empty-input error,
 *    the busy guard against a second tap, the keyboard hiding on connect and
 *    disconnect, the masked password, and the CONNECT focus requester staying a
 *    TV-only concern;
 *  - F3: MAL's split is untouched - the browser button taps on the phone, the QR
 *    stays the TV's (and the no-browser fallback's) path.
 *
 * Pinned by reading the source: this module has no Compose test harness, and
 * every failure here is silent in the field - the dialog opens, the plate is
 * phone-shaped, and the controls are simply inert.
 */
class AnimeConnectDialogsTouchContractTest {

    private val kitsu by lazy { flat(source(KITSU)) }
    private val aniList by lazy { flat(source(ANILIST)) }
    private val mal by lazy { flat(source(MAL)) }
    private val panel by lazy { flat(source(PANEL)) }
    private val tvCard by lazy { flat(source(TV_CARD)) }
    private val tvField by lazy { flat(source(TV_FIELD)) }
    private val touchCard by lazy { flat(source(TOUCH_CARD)) }
    private val touchField by lazy { flat(source(TOUCH_FIELD)) }

    private val dialogs = listOf(KITSU to kitsu, ANILIST to aniList, MAL to mal)

    // ── F1: the bodies take the touch twins on the phone ──────────────────

    @Test
    fun `every dialog body is drawn with the split, on the flag it was raised with`() {
        dialogs.forEach { (name, dialog) ->
            assertTrue(
                "$name must draw its plate for the surface it was raised on",
                dialog.contains("AnimeDialogPanel(phone = phone")
            )
            assertTrue(
                "$name: the phone's buttons must be the touch twin",
                dialog.contains("AnimeDialogButton( phone = phone,")
            )
        }
        // The two field-bearing dialogs; MAL types nothing by design ("Nothing is
        // typed on this screen").
        listOf(KITSU to kitsu, ANILIST to aniList).forEach { (name, dialog) ->
            assertTrue(
                "$name: the phone's fields must be the touch twin",
                dialog.contains("AnimeDialogField( phone = phone,")
            )
        }
        assertFalse(
            "MAL still types nothing, so a field there would be a new flow",
            mal.contains("AnimeDialogField(")
        )
    }

    @Test
    fun `no TV card or field is left in a body`() {
        dialogs.forEach { (name, dialog) ->
            // The bodies must go through the split only. A direct `KBCard(` or
            // `KBTextField(` here is the exact bug: it draws, and a thumb cannot
            // press it.
            assertFalse("$name still draws a TV card", dialog.contains("KBCard("))
            assertFalse("$name still draws a TV field", dialog.contains("KBTextField("))
            assertFalse(
                "$name must not reach for the twins itself - that is the split's job",
                dialog.contains("TouchKBCard(") || dialog.contains("TouchKBTextField(")
            )
        }
    }

    @Test
    fun `the split picks the twin on the phone and leaves the TV path alone`() {
        val button = panel.substringAfter("internal fun AnimeDialogButton(")
            .substringBefore("internal fun AnimeDialogField(")
        assertTrue(
            "the phone branch is the touch card",
            button.contains("if (phone) { TouchKBCard(")
        )
        assertTrue(
            "the TV branch is the card it always was",
            button.contains("} else { KBCard(")
        )
        assertTrue(
            "and the touch card is the one that takes a tap, not a Select key",
            touchCard.contains("combinedClickable(")
        )
        assertTrue(
            "which the TV card cannot: it fires from a key event",
            tvCard.contains("onPreviewKeyEvent") && !tvCard.contains("combinedClickable(")
        )

        val field = panel.substringAfter("internal fun AnimeDialogField(")
        assertTrue("the phone branch is the touch field", field.contains("if (phone) { TouchKBTextField("))
        assertTrue("the TV branch is the field it always was", field.contains("} else { KBTextField("))
        assertTrue(
            "the touch field is a real editable Material 3 field",
            touchField.contains("OutlinedTextField(")
        )
        assertTrue(
            "and the TV field keeps its D-pad editing session",
            tvField.contains("openKeyboardOnFocus") && tvField.contains("keepFocusOnDone")
        )
    }

    // ── F2: the behaviours the bodies rely on survive the swap ────────────

    @Test
    fun `CONNECT still validates empty input with its own sentence`() {
        assertTrue(
            "Kitsu still refuses a blank email or password before any network call",
            kitsu.contains("if (typedEmail.isEmpty() || password.isEmpty()) {") &&
                kitsu.contains("error = \"Enter your Kitsu email and password\"")
        )
        assertTrue(
            "AniList still refuses an empty field (spec 2026-11-14, F3: the field is " +
                "a CODE now, and its sentence says what to do instead of guessing)",
            aniList.contains("if (pasted.isEmpty()) {") &&
                aniList.contains("\"Approve in the browser, or paste the code AniList \"")
        )
        listOf(KITSU to kitsu, ANILIST to aniList).forEach { (name, dialog) ->
            val guard = dialog.indexOf("if (busy) return@AnimeDialogButton")
            val call = dialog.indexOf("scope.launch {")
            assertTrue("$name must guard a second tap", guard >= 0)
            assertTrue(
                "$name must guard BEFORE the connect it launches, or a double " +
                    "tap fires two connects",
                guard < call
            )
            assertTrue(
                "$name says it is busy while the connect runs",
                dialog.contains("busy = true") && dialog.contains("\"CONNECTING…\"")
            )
        }
    }

    @Test
    fun `the keyboard hides on connect and on disconnect`() {
        // The two dialogs that type. MAL types nothing, so it never raises one.
        listOf(KITSU to kitsu, ANILIST to aniList).forEach { (name, dialog) ->
            assertTrue(
                "$name must hide the keyboard on the way into a connect",
                dialog.contains("keyboard?.hide() busy = true")
            )
            assertTrue(
                "$name must hide it on a disconnect too - the touch keyboard can be " +
                    "up when the button is pressed",
                dialog.contains("keyboard?.hide() onDisconnect()")
            )
        }
        assertFalse(
            "MAL has no field, so it should not have grown a keyboard controller",
            mal.contains("LocalSoftwareKeyboardController")
        )
    }

    @Test
    fun `the password stays masked on the touch field`() {
        assertTrue(
            "the password field still asks for the password keyboard and the mask",
            kitsu.contains("keyboardType = KeyboardType.Password") &&
                kitsu.contains("visualTransformation = PasswordVisualTransformation()")
        )
        // The swap must not drop either: the split forwards both to whichever
        // field it draws, so the touch field masks the password the TV field did.
        assertEquals(
            "both branches of the field split must forward the mask",
            2,
            panel.count("visualTransformation = visualTransformation")
        )
        assertEquals(
            "and the keyboard type",
            2,
            panel.count("keyboardType = keyboardType")
        )
        assertTrue(
            "which the touch field honours like any Material 3 field",
            touchField.contains("visualTransformation = visualTransformation")
        )
    }

    @Test
    fun `the CONNECT focus requester is applied on the TV plate only`() {
        assertTrue(
            "Kitsu still wires its primary action for the D-pad",
            kitsu.contains("focusRequester = connectFocusRequester")
        )
        val button = panel.substringAfter("internal fun AnimeDialogButton(")
            .substringBefore("internal fun AnimeDialogField(")
        val phoneBranch = button.substringAfter("if (phone) {").substringBefore("} else {")
        val tvBranch = button.substringAfter("} else {")
        assertFalse(
            "the phone plate has no focus to place - a tap focuses its own target",
            phoneBranch.contains("focusRequester")
        )
        assertTrue(
            "the TV plate keeps the requester it had",
            tvBranch.contains("modifier.focusRequester(focusRequester)")
        )
    }

    // ── F3: MAL's surface split stays intact ──────────────────────────────

    @Test
    fun `MAL opens the browser with a tap on the phone and keeps the QR for the TV`() {
        val browser = mal.substringAfter("if (canConnect && phone && !browserUnavailable) {")
            .substringBefore("if (state == MalConnectionState.Connected ||")
        assertTrue(
            "the phone's connect is a touch button that starts the landed flow",
            browser.contains("AnimeDialogButton( phone = phone,") &&
                browser.contains("MalLinks.openAuthorize(context)")
        )
        assertTrue(
            "the QR stays where a phone cannot help itself: TV, and a phone with no browser",
            mal.contains("if (canConnect && (!phone || browserUnavailable)) {") &&
                mal.contains("KBQrCode(url = MalLinks.startLink())")
        )
        assertTrue(
            "the phone's no-browser fallback sentence is unchanged",
            mal.contains("This device has no browser to open the authorize page.")
        )
        // The one control MAL does have on every state must be pressable too.
        val close = mal.substringAfter("AnimeDialogButton(phone = phone, onClick = onDismiss)")
        assertTrue("CLOSE must dismiss", close.contains("CLOSE"))
    }

    @Test
    fun `the row that raises these dialogs is untouched by the fix`() {
        // The phone rows already tapped (they use the touch card); the bug was
        // inside the dialog. Pinning it keeps a later "fix" from reworking rows.
        assertTrue(
            "the shared rows still pass the surface's flag into all three dialogs",
            flat(source(SHARED_ROWS)).contains("phone = phone")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun source(fileName: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                listOf(
                    File(candidate, "com/kennyb1201/kbstream/ui/settings/$fileName"),
                    File(candidate, "com/kennyb1201/kbstream/ui/components/$fileName"),
                    File(candidate, "com/kennyb1201/kbstream/ui/components/touch/$fileName")
                ).firstOrNull { it.isFile }?.let { return it.readText() }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "source missing: $fileName (walking up from " + System.getProperty("user.dir") + ")"
        )
    }

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun String.count(token: String): Int {
        var found = 0
        var from = indexOf(token)
        while (from >= 0) {
            found++
            from = indexOf(token, from + token.length)
        }
        return found
    }

    private companion object {
        const val KITSU = "KitsuConnectDialog.kt"
        const val ANILIST = "AniListConnectDialog.kt"
        const val MAL = "MalConnectDialog.kt"
        const val PANEL = "AnimeDialogPanel.kt"
        const val SHARED_ROWS = "AnimeTrackerRows.kt"
        const val TV_CARD = "KBCard.kt"
        const val TV_FIELD = "KBTextField.kt"
        const val TOUCH_CARD = "TouchCard.kt"
        const val TOUCH_FIELD = "TouchTextField.kt"
    }
}
