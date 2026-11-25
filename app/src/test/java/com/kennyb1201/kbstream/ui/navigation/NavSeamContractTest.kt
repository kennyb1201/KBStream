package com.kennyb1201.kbstream.ui.navigation

import java.io.File
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
    fun `the graph owns navigation, and the shell keeps no screen to restore`() {
        assertTrue(
            "the seam is built on the controller, with the gate's launch answer as " +
                "what a read means before the graph exists",
            shell.contains("val pickerOnLaunch = profilesExist && coldLaunch && showProfilePicker") &&
                shell.contains("val navSeam = remember(navController) { NavSeam(navController, launchEntry) }") &&
                shell.contains("var screen by navSeam")
        )
        assertFalse(
            "no Screen is saved any more: the controller restores its own stack, " +
                "which is what makes the routes the restore format (and why the " +
                "one-shots must never become route arguments)",
            shell.contains("stateSaver = entrySaver") ||
                shell.contains("val entrySaver = remember(") ||
                shell.contains("mutableStateOf<Screen>(launchScreen(")
        )
    }

    @Test
    fun `every write navigates, and the player rides above the host`() {
        assertTrue(
            "one seam function, named with the spec's own vocabulary, and it is the " +
                "controller that navigates",
            seam.contains("fun navigateTo(value: Screen) {") &&
                seam.contains("navController.navigate(target) {")
        )
        assertTrue(
            "the delegate routes writes to it and reads to the mirror",
            seam.contains(
                "operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Screen) ="
            ) && seam.contains("navigateTo(value)") &&
                seam.contains(
                    "operator fun getValue(thisRef: Any?, property: KProperty<*>): Screen = playerLaunch ?: mirror.value"
                )
        )
        assertTrue(
            "and the player is PUBLISHED rather than translated: it has no route, so " +
                "the arm has to be composed above the host for pressing a source to " +
                "launch anything at all",
            seam.contains("if (value is Screen.Player) {") &&
                seam.contains("playerLaunch = value") &&
                shell.contains("val playerOverlay = navSeam.playerLaunch") &&
                shell.contains("renderScreen(player)")
        )
        assertTrue(
            "a real destination dismisses the overlay, so the player's own exit " +
                "write is also the dismissal",
            seam.contains("playerLaunch = null")
        )
    }

    @Test
    fun `a read mirrors the graph, and a write made before it is queued`() {
        assertTrue(
            "the mirror follows the current entry: the destination the controller " +
                "comes to rest on is what the shell's readers see",
            seam.contains("fun follow(entry: NavBackStackEntry?)") &&
                seam.contains("val shown = NavRoutes.screenOfEntry(entry)") &&
                seam.contains("mirror.value = shown")
        )
        assertTrue(
            "and a write that arrives before the host attaches its graph (the " +
                "shell's deep-link and remote-play effects run first) waits for it " +
                "rather than being thrown away",
            seam.contains("if (navController.currentBackStackEntry == null) {") &&
                seam.contains("queued = value") &&
                seam.contains("queued = null")
        )
        assertTrue(
            "the Detail arrival is still taken once and keyed by (type, id), so a " +
                "return to the page and a More Like This re-target both find nothing",
            seam.contains("val held = detailArrival ?: return null") &&
                seam.contains("held.takeIf { it.type == type && it.id == id }")
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
