package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a launch opens.
 *
 * The picker used to be forced by a `LaunchedEffect(Unit)` beside the screen
 * state, and an effect runs a frame after the first composition: with profiles
 * on the device, Home composed first — starting its rail loaders and painting
 * its empty placeholder rails — and the picker replaced it a frame later. The
 * gate therefore lives in the state's own saver now (see [launchScreenSaver]),
 * which is decided during that first composition and cannot leave a Home frame
 * behind.
 *
 * These cases pin both halves of the gate plus the wiring that reaches them:
 * the two screens it is allowed to pick, that a profile-less install still gets
 * the plain [ScreenSaver] (the restore kept for process death), that a device
 * with profiles ignores whatever was restored, and that MainActivity no longer
 * carries the post-composition flip.
 */
class LaunchScreenGateTest {

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
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    // ── the gate as a value ──────────────────────────────────────────

    @Test
    fun `a profile-less install opens on home`() {
        // A fresh install (or one whose profiles were all deleted) has no
        // identity to choose, so the picker would be a dead end.
        assertEquals(Screen.Home, launchScreen(profilesExist = false))
    }

    @Test
    fun `a device with profiles opens on who's watching`() {
        // The entry picker carries no returnTo: Back there is the exit prompt,
        // which is what makes it the app's entry screen rather than a detour.
        assertEquals(Screen.ProfilePicker(), launchScreen(profilesExist = true))
    }

    @Test
    fun `the gate only ever answers with one of those two screens`() {
        // Home is the launch screen for onboarding, Who's Watching for
        // identity — anything else would be a launch into a drill-down with no
        // back destination.
        listOf(false, true).forEach { profilesExist ->
            assertTrue(
                "unexpected launch screen for profilesExist=$profilesExist",
                launchScreen(profilesExist) in listOf(Screen.Home, Screen.ProfilePicker())
            )
        }
    }

    // ── the gate in the restore path ─────────────────────────────────

    @Test
    fun `without profiles the restore is the plain screen saver`() {
        // Process death has to keep landing back on the detail/streams screen
        // that was up, which is the whole reason the saver exists.
        assertSame(ScreenSaver, launchScreenSaver(profilesExist = false))
    }

    @Test
    fun `with profiles a restored home is replaced by the picker`() {
        // The regression this fixes: a saved Home (the app was on Home when
        // the process was killed) must not be handed back, or the launch paints
        // Home's skeleton rails before the picker.
        val restored = launchScreenSaver(profilesExist = true).restore("{\"type\":\"home\"}")

        assertEquals(Screen.ProfilePicker(), restored)
    }

    @Test
    fun `with profiles a restored detail screen is replaced by the picker too`() {
        // The effect this replaced overrode EVERY restored screen, not just
        // Home, so the gate has to be unconditional to stay equivalent.
        val restored = launchScreenSaver(profilesExist = true).restore("detail")

        assertEquals(Screen.ProfilePicker(), restored)
    }

    @Test
    fun `the gated restore never decodes what was saved`() {
        // The picker is returned whatever the bundle holds — including a value
        // that is not even JSON. That is what proves the saved Home is bypassed
        // rather than parsed and then overridden after a frame.
        val restored = launchScreenSaver(profilesExist = true).restore("not json at all")

        assertEquals(Screen.ProfilePicker(), restored)
    }

    @Test
    fun `the two savers are different objects`() {
        assertFalse(launchScreenSaver(profilesExist = true) === ScreenSaver)
    }

    // ── the wiring MainActivity has to keep ──────────────────────────

    @Test
    fun `the entry screen is seeded from the gate, not from Home`() {
        val main = squash(source(MAIN))
        assertTrue(
            "the state must start on the gate's answer",
            main.contains("{ mutableStateOf<Screen>(launchScreen(profilesExist)) }")
        )
        assertTrue(
            "the saver has to be the gated one",
            main.contains("stateSaver = entrySaver")
        )
        assertTrue(
            "and the gate is what both halves read",
            main.contains("launchScreenSaver(profilesExist)")
        )
        assertFalse(
            "seeding the state with Home is the flash this replaced",
            main.contains("mutableStateOf<Screen>(Screen.Home)")
        )
    }

    @Test
    fun `the picker is no longer forced after the first composition`() {
        // The exact removed shape: a LaunchedEffect flipping the screen to the
        // picker one frame after Home had already composed.
        val main = squash(source(MAIN))
        assertFalse(
            "a post-composition flip cannot help but flash Home first",
            main.contains("ProfileManager.hasProfiles(applicationContext)) { screen = Screen.ProfilePicker() }")
        )
    }

    private companion object {
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
