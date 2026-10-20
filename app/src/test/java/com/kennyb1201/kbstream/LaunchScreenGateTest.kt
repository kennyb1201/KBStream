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
            listOf(false, true).forEach { showProfilePicker ->
                assertTrue(
                    "unexpected launch screen for profilesExist=$profilesExist " +
                        "showProfilePicker=$showProfilePicker",
                    launchScreen(profilesExist, showProfilePicker) in
                        listOf(Screen.Home, Screen.ProfilePicker())
                )
            }
        }
    }

    // ── the device-local "Who's Watching on launch" switch ───────────

    @Test
    fun `a device with the picker switched off opens into the last used profile`() {
        // ProfileManager.init re-establishes the last used profile before the
        // first composition, so Home is that profile already: the picker is
        // skipped rather than replaced with anything else.
        assertEquals(Screen.Home, launchScreen(profilesExist = true, showProfilePicker = false))
    }

    @Test
    fun `switching the picker off thins the saver back to the plain one`() {
        // No gate means the ordinary restore, exactly as a profile-less install
        // gets: a launch has nothing to restore, so the state's initial value
        // (Home) stands.
        assertSame(
            ScreenSaver,
            launchScreenSaver(profilesExist = true, coldLaunch = true, showProfilePicker = false)
        )
        assertEquals(
            "and a saved screen is handed back rather than gated to the picker",
            Screen.Home,
            launchScreenSaver(profilesExist = true, coldLaunch = true, showProfilePicker = false)
                .restore("{\"type\":\"home\"}")
        )
    }

    @Test
    fun `the switch defaults to the picker, so an untouched device is unchanged`() {
        assertEquals(
            Screen.ProfilePicker(),
            launchScreen(profilesExist = true)
        )
        assertFalse(
            "and its saver is still the gated one",
            launchScreenSaver(profilesExist = true) === ScreenSaver
        )
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

    // ── the gate must not fire on a rebuild ──────────────────────────

    @Test
    fun `a configuration change restores the screen the viewer was on`() {
        // A phone rotation re-creates MainActivity in the same process. That
        // restore used to be gated exactly like a launch, so turning the device
        // reopened Who's Watching over whatever was on screen.
        val rebuild = launchScreenSaver(profilesExist = true, coldLaunch = false)

        assertSame("a rebuild keeps the ordinary saver", ScreenSaver, rebuild)
        assertEquals(
            "and hands back the saved screen",
            Screen.Home,
            rebuild.restore("{\"type\":\"home\"}")
        )
    }

    @Test
    fun `a cold launch with profiles still opens on who's watching`() {
        assertEquals(
            Screen.ProfilePicker(),
            launchScreenSaver(profilesExist = true, coldLaunch = true)
                .restore("{\"type\":\"home\"}")
        )
    }

    // ── launch, rebuild, or return from another app ──────────────────

    @Test
    fun `a phone create carrying saved state is a return, not a launch`() {
        // The field report: Android killed the process during an app switch and
        // handed the re-created activity its saved state. The viewer never
        // left, so the restore must be honoured instead of gated to the picker.
        assertFalse(
            isColdLaunch(shellStarted = false, isTv = false, hasSavedState = true)
        )
    }

    @Test
    fun `a phone create with no saved state is still a launch`() {
        // A fresh task - swiped away in recents, or a first install - has
        // nothing to restore, so Who's Watching still gates it.
        assertTrue(
            isColdLaunch(shellStarted = false, isTv = false, hasSavedState = false)
        )
    }

    @Test
    fun `a television ignores the saved state`() {
        // TV is unchanged: each session starts under a chosen identity, whether
        // or not the system kept a task's state.
        assertTrue(isColdLaunch(shellStarted = false, isTv = true, hasSavedState = false))
        assertTrue(isColdLaunch(shellStarted = false, isTv = true, hasSavedState = true))
    }

    @Test
    fun `a rebuild in the same process is never a launch`() {
        // A rotation, the theme recreate, coming back from the player: all of
        // them re-create this same process's activity, so none is a launch.
        listOf(false, true).forEach { isTv ->
            listOf(false, true).forEach { saved ->
                assertFalse(
                    "a rebuild must never reopen the picker (isTv=$isTv saved=$saved)",
                    isColdLaunch(shellStarted = true, isTv = isTv, hasSavedState = saved)
                )
            }
        }
    }

    @Test
    fun `the cold-launch flag is claimed once per process and reaches the gate`() {
        val main = source(MAIN)

        assertTrue(
            "the first create of the process claims the launch; later ones are rebuilds",
            main.contains("shellStarted = true")
        )
        assertTrue(
            "and the decision is the shared one, made from the process flag, the " +
                "form factor and whatever the system restored",
            main.contains("val coldLaunch = isColdLaunch(") &&
                main.contains("shellStarted = shellStarted") &&
                main.contains("isTv = FormFactor.isTv") &&
                main.contains("hasSavedState = savedInstanceState != null")
        )
        assertTrue(
            "and the shell is told which one it is",
            main.contains("coldLaunch = coldLaunch")
        )
        assertTrue(
            "the gate reads that flag",
            main.contains("launchScreenSaver(profilesExist, coldLaunch, showProfilePicker)")
        )
    }

    @Test
    fun `the device-local switch and a PIN both reach the gate`() {
        val main = squash(source(MAIN))
        assertTrue(
            "the switch is read from AppPreferences, once, before the gate is built",
            main.contains("getShowProfilePicker(applicationContext)")
        )
        assertTrue(
            "a PIN-protected profile still forces the picker: the code is " +
                "entered there, so skipping it would silently unlock the profile",
            main.contains("launchProfileHasPin ||")
        )
        assertTrue(
            "and the saver is keyed on the answer, so the switch changes which " +
                "gate the state starts with",
            main.contains("remember(profilesExist, coldLaunch, showProfilePicker)")
        )
    }

    @Test
    fun `a restored screen cannot walk around the kids time lock`() {
        // The change above lets a phone restore into a live profile instead of
        // the picker. That must not become a way past the time lock: the veil
        // answers to the guard's evaluation of the ACTIVE profile, not to how
        // the screen was reached, and the guard is live before the first
        // composition on every create - a process-death return included.
        val main = source(MAIN)
        assertTrue(
            "the shell composes the veil for every screen",
            main.contains("KidsTimeLockOverlay()")
        )
        assertTrue(
            "and it opens only on the guard's locked state",
            main.contains("if (!lockState.locked) return")
        )
        assertFalse(
            "the launch flag must not be a key to the veil: a restored screen is " +
                "veiled exactly like one reached through the picker",
            main.substringAfter("private fun KidsTimeLockOverlay()")
                .substringBefore("\n@Composable")
                .contains("coldLaunch")
        )
        assertTrue(
            "the guard is started before anything composes, so a restored process " +
                "already has an answer for the active profile at the first frame",
            main.indexOf("KidsTimeGuard.start(applicationContext)") in
                0 until main.indexOf("setContent {")
        )
        val guard = source(GUARD)
        assertTrue(
            "and foregrounding re-evaluates it - which is exactly what a return " +
                "from another app is",
            guard.contains("fun onAppStart() {") &&
                guard.contains("foregrounded = true") &&
                guard.contains("evaluate()")
        )
    }

    // ── the wiring MainActivity has to keep ──────────────────────────

    @Test
    fun `the entry screen is seeded from the gate, not from Home`() {
        val main = squash(source(MAIN))
        assertTrue(
            "the state must start on the gate's answer",
            main.contains("{ mutableStateOf<Screen>(launchScreen(profilesExist, showProfilePicker)) }")
        )
        assertTrue(
            "the saver has to be the gated one",
            main.contains("stateSaver = entrySaver")
        )
        assertTrue(
            "and the gate is what both halves read",
            main.contains("launchScreenSaver(profilesExist, coldLaunch, showProfilePicker)")
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
        const val GUARD = "com/kennyb1201/kbstream/data/sync/KidsTimeGuard.kt"
    }
}
