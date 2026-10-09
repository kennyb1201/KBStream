package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API-key rows are masked, and masking stays presentation-only.
 *
 * Fifth audit (2026-10-09), P1: MDBList, OpenSubtitles and TorBox each drew
 * their key in the clear on Settings -> Integrations, so anyone in the room
 * could read a live credential off the TV. The rows now go through
 * [com.kennyb1201.kbstream.ui.components.KBSecretField], which masks by default
 * and reveals on the eye chip.
 *
 * Read from the source, the way the other settings contracts are: this module
 * carries no Compose UI harness, so the wiring - which rows are masked, where
 * the reveal state lives, that the save/paste/status paths still see the real
 * key, and that nothing logs it - is pinned textually, while the masking
 * transformation itself is exercised in KBSecretFieldMaskingTest.
 */
class KeyFieldMaskingContractTest {

    private val settings by lazy { source(SETTINGS).squash() }
    private val field by lazy { source(FIELD).squash() }

    @Test
    fun `the three credential fields render masked`() {
        assertEquals(
            "MDBList, OpenSubtitles and TorBox are the key rows",
            3,
            Regex("KBSecretField\\(").findAll(settings).count()
        )
        listOf("mdbListKeyInput", "subsKeyInput", "torboxKeyInput").forEach { input ->
            assertTrue(
                "$input must go through the masked field, not a plain KBTextField",
                settings.contains("KBSecretField( value = $input,")
            )
        }
    }

    @Test
    fun `a key field is masked by default and drawn plain only while revealed`() {
        assertTrue(
            "dots unless the viewer asked to see it",
            field.contains(
                "if (revealed) VisualTransformation.None else PasswordVisualTransformation()"
            )
        )
        assertTrue(
            "and the field is the one thing that changes - the value is passed through",
            field.contains("visualTransformation = secretFieldTransformation(revealed)")
        )
        assertTrue(
            "so saving, pasting and verifying keep handling the real key string",
            field.contains("value = value, onValueChange = onValueChange,") &&
                field.contains("onFocusChanged = { focused -> fieldFocused = focused")
        )
    }

    @Test
    fun `the reveal does not persist across a screen re-entry`() {
        assertTrue(
            "it starts masked every time the composable is entered",
            field.contains("var revealed by remember { mutableStateOf(false) }")
        )
        assertFalse(
            "rememberSaveable would restore a revealed key after a rotation/re-entry",
            field.contains("by rememberSaveable")
        )
        assertTrue(
            "and it re-masks when focus leaves both the field and the chip",
            field.contains("if (shouldRemaskKey(fieldFocused, chipFocused)) revealed = false")
        )
        assertFalse(
            "the revealed flag is UI state and must never be written to prefs",
            settings.contains("setKeyRevealed") || field.contains("AppPreferences")
        )
    }

    @Test
    fun `the eye chip is focusable and its state is announced`() {
        assertTrue(
            "a D-pad user has to be able to reach it",
            field.contains("KBCard( onClick = onToggle,")
        )
        assertTrue(
            "and the D-pad user cannot see the glyph change, so the label reports the state",
            field.contains("if (revealed) \"Hide API key\" else \"Show API key\"")
        )
        assertFalse(
            "an unlabelled eye is not announced at all",
            field.contains("contentDescription = \"Toggle\"")
        )
    }

    @Test
    fun `paste, Done and blur still save the real key`() {
        // Unchanged by the masking work: three save paths per row, because a
        // remote user routinely navigates away instead of pressing Done.
        listOf(
            "AppPreferences.setMdbListApiKey(context, mdbListKeyInput)",
            "AppPreferences.setOpensubtitlesApiKey(context, subsKeyInput)",
            "AppPreferences.setTorboxApiKey(context, torboxKeyInput)"
        ).forEach { call ->
            assertEquals(
                "$call must keep its paste, Done and blur paths",
                3,
                Regex(Regex.escape(call)).findAll(settings).count()
            )
        }
        assertTrue(
            "and the status line still reads off the real key's saved state",
            settings.contains("if (mdbListKeySaved)") &&
                settings.contains("\"MDBList — Connected\"") &&
                settings.contains("if (subsKeySaved)") &&
                settings.contains("if (torboxKeySaved)")
        )
    }

    @Test
    fun `no key material can reach a log`() {
        listOf(settings to "SettingsScreen.kt", field to "KBTextField.kt").forEach { (src, name) ->
            assertFalse("$name must not log", src.contains("Log."))
            assertFalse("$name must not print", src.contains("println("))
            assertFalse("$name must not print", src.contains("System.out"))
            assertFalse("$name must not print", src.contains("printStackTrace"))
        }
    }

    private fun String.squash(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
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

    private companion object {
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val FIELD = "com/kennyb1201/kbstream/ui/components/KBTextField.kt"
    }
}
