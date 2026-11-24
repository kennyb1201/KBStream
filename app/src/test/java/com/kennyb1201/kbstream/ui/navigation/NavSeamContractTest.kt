package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation seam, pinned at the shape F0 has to ship with.
 *
 * The whole strangler leans on one property: every navigation in the shell goes
 * through a single decision point, and that decision point starts with NOTHING
 * migrated, so the first migration phase begins from a build whose walk is
 * byte-identical to the one before it. These tests pin that starting state and
 * the wiring that makes it true - the state object is still the
 * `rememberSaveable` one the restoration codec owns, the property still reads as
 * a plain `Screen`, and the global Back handler stands down for a migrated
 * destination so the NavController's pop is the only one.
 *
 * They also pin the choice this phase made instead of rewriting ninety-eight
 * call sites: the seam is a DELEGATE. If someone later turns it back into
 * per-call-site `navigateTo(...)` calls, this test should be updated with that
 * decision - not silently satisfied by the old form.
 */
class NavSeamContractTest {

    private val shell: String by lazy { normalized(stripComments(source(SHELL))) }

    private val seam: String by lazy { normalized(stripComments(source(SEAM))) }

    @Test
    fun `the shell's navigation state is still the saveable one, behind the seam`() {
        assertTrue(
            "the state object is what rememberSaveable produced, so restoration, " +
                "the cold-launch gate and the depth-capped codec are untouched",
            shell.contains("val screenState = rememberSaveable(stateSaver = entrySaver) {") &&
                shell.contains(
                    "mutableStateOf<Screen>(launchScreen(profilesExist, showProfilePicker))"
                )
        )
        assertTrue(
            "and the shell's `screen` IS that state, delegated through the seam",
            shell.contains("val navSeam = remember(screenState) { NavSeam(screenState) }") &&
                shell.contains("var screen by navSeam")
        )
        assertFalse(
            "no second copy of the navigation state: the old direct delegate is gone",
            shell.contains("var screen by rememberSaveable(stateSaver = entrySaver)")
        )
    }

    @Test
    fun `every write goes through one entry point and nothing is migrated yet`() {
        assertTrue(
            "one seam function, named with the spec's own vocabulary",
            seam.contains("fun navigateTo(value: Screen) {") &&
                seam.contains("state.value = value")
        )
        assertTrue(
            "the delegate routes writes to it and reads to the same state",
            seam.contains(
                "operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Screen) ="
            ) && seam.contains("navigateTo(value)") &&
                seam.contains("operator fun getValue(thisRef: Any?, property: KProperty<*>): Screen = state.value")
        )
        assertTrue(
            "F0 ships with an EMPTY migrated set: no destination renders through a " +
                "NavHost, which is what makes this build's walk byte-identical",
            seam.contains("private val migratedKinds: Set<String> = emptySet()")
        )
        assertTrue(
            "and membership is asked by the same type name the renderer keys on, so " +
                "a phase cannot migrate a kind the transition/content key disagrees about",
            seam.contains("fun isMigrated(value: Screen): Boolean = value.typeName() in migratedKinds")
        )
    }

    @Test
    fun `the global Back handler stands down for a migrated destination`() {
        assertEquals(
            "the shell's global handler is gated on the seam, so a migrated " +
                "destination's Back is the NavController's (plus its own local " +
                "handler) and never a double pop",
            1,
            Regex("BackHandler\\(enabled = !navSeam\\.isMigrated\\(screen\\)\\)")
                .findAll(shell)
                .count()
        )
    }

    @Test
    fun `the seam never encodes or restores anything itself`() {
        assertFalse(
            "the codec is the saver's job; the seam moving to its own encoding " +
                "would be a second serialization of the same graph",
            seam.contains("encodeScreen(") || seam.contains("decodeScreen(") ||
                seam.contains("ScreenSaver")
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

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
        const val SHELL = "com/kennyb1201/kbstream/MainActivity.kt"
        const val SEAM = "com/kennyb1201/kbstream/ui/navigation/NavSeam.kt"
    }
}
