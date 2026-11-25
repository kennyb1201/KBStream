package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 of the adaptive-layout spec: what a density fold is allowed to drop.
 *
 * MainActivity's `configChanges` deliberately omits `density|uiMode` - it is the
 * TV window policy and the manifest is shared with the TV app, so the spec says
 * not to change it. The consequence is that a foldable changing density on unfold
 * re-creates the activity, and every plain `remember` in the phone surfaces is
 * lost with it. Saver-backed list states and ViewModels survive; the Settings
 * pane selection did not, so an unfold mid-edit put the viewer back on the pane
 * list.
 *
 * The two states pinned here are the ones the spec names. The rest are verified
 * as already surviving (list/grid states come from `IdleListCacheWindow`'s Savers,
 * the search query is ViewModel state) and are deliberately NOT converted.
 */
class SettingsPaneSurvivalContractTest {

    private val screen: String by lazy { stripComments(source(SCREEN)) }
    private val manifest: String by lazy { source(MANIFEST) }

    @Test
    fun `the pane selection is saver-backed`() {
        assertTrue(
            "the Settings pane is a plain `remember`: a density unfold re-creates " +
                "MainActivity and drops it, sending the viewer back to the pane list",
            screen.contains("var pane by rememberSaveable {")
        )
        assertTrue(
            "and it must still be the SettingsPane value the panes read, with the " +
                "stored name as the cold-entry initial value",
            screen.contains("mutableStateOf<SettingsPane?>(") &&
                screen.contains("AppPreferences.getLastSettingsPane(context)")
        )
        assertFalse(
            "the old plain remember must be gone, not left beside the new one",
            screen.contains("var pane by remember {")
        )
    }

    @Test
    fun `the Downloads page is saver-backed too`() {
        assertTrue(
            "being on the Downloads page is lost on the same recreation",
            screen.contains("var downloadsOpen by rememberSaveable { mutableStateOf(false) }")
        )
        assertFalse(
            "the old plain remember must be gone",
            screen.contains("var downloadsOpen by remember {")
        )
    }

    @Test
    fun `the manifest still omits density from MainActivity's configChanges`() {
        // The reason the two states above had to change at all. If this ever
        // stops being true the conversion is harmless, but the assertion is what
        // tells the next reader why the pair is Saveable rather than remembered.
        val activity = manifest
            .substringAfter(" android:name=\".MainActivity\"")
            .substringBefore("<activity")
        assertTrue(
            "MainActivity no longer declares any configChanges at all, so this " +
                "file's comment about the density fold is stale",
            activity.contains("android:configChanges=")
        )
        assertFalse(
            "MainActivity now declares density itself: that is the manifest the TV " +
                "app shares, and the spec forbids changing it",
            activity.contains("density")
        )
        assertTrue(
            "screenSize must stay declared - resize and fold must keep " +
                "recomposing instead of re-creating",
            activity.contains("screenSize")
        )
    }

    private fun source(relativePath: String): String =
        File(repoRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("app/src/main/AndroidManifest.xml not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SCREEN =
            "app/src/main/java/com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val MANIFEST = "app/src/main/AndroidManifest.xml"
    }
}
