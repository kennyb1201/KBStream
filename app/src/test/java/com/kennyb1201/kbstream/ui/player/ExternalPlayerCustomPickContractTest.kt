package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two external-player detection fixes, as wiring.
 *
 * Both are cases where the viewer's player was not usable and nothing on screen
 * said why:
 *
 *  1. A file manager was listed as an app to hand a title to. They register
 *     `ACTION_VIEW` for every file type there is, so the video MIME probe
 *     matches them by construction - the filter has to be on the match, and it
 *     must stay off the scheme probe, where no manager can appear.
 *  2. A player whose TV build declares no video handler at all could never be
 *     found, however wide the probe: VidHub's Play Store TV APK is the field
 *     case. Naming the app by hand is the way in, and it only works end to end
 *     if FOUR things line up - the picker can see the app, the engine prefers
 *     the pick, the launch falls back to the app's own launcher entry when there
 *     is no video request to resolve, and clearing the pick returns the engine
 *     to detection. Missing any one of them leaves the feature half-wired.
 *
 * [com.kennyb1201.kbstream.data.player.ExternalPlayerFileManagerFilterTest]
 * pins the filter rule itself; this pins the plumbing around both.
 */
class ExternalPlayerCustomPickContractTest {

    private val projectRoot: File by lazy { findProjectRoot() }

    private fun findProjectRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/AndroidManifest.xml")
                if (candidate.isFile) return File(dir, prefix.trimEnd('/'))
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "project root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(relative: String): String {
        val file = File(projectRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val engine: String
        get() = source("src/main/java/com/kennyb1201/kbstream/data/player/ExternalPlayer.kt")

    private val prefs: String
        get() = source("src/main/java/com/kennyb1201/kbstream/data/settings/AppPreferences.kt")

    private val settings: String
        get() = source("src/main/java/com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")

    private val wrapper: String
        get() = source("src/main/java/com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt")

    private fun manifestQueries(): String =
        source("src/main/AndroidManifest.xml")
            .substringAfter("<queries>")
            .substringBefore("</queries>")

    @Test
    fun `the probe drops file managers from the matches it collects`() {
        val installed = engine.substringAfter("fun installed(context: Context)")
            .substringBefore("fun allApps(context: Context)")
        val mimeLoop = installed.substringAfter("PROBE_MIME_TYPES.forEach")
            .substringBefore("SCHEME_PLAYERS.forEach")
        assertTrue(
            "the filter belongs on the MIME matches - that is what a file manager answers",
            mimeLoop.contains("isFileManager(pkg, label)")
        )
        // The scheme probe resolves a private URL, which no file manager
        // registers: filtering there would only risk dropping a real scheme
        // player.
        val schemeLoop = installed.substringAfter("SCHEME_PLAYERS.forEach")
        assertTrue(
            "the scheme matches must not go through the file-manager filter",
            !schemeLoop.contains("isFileManager(")
        )
    }

    @Test
    fun `the custom picker can see every installable app Android 11 will show us`() {
        val queries = manifestQueries()
        // Under Android 11+ an app this manifest does not declare is invisible
        // to queryIntentActivities, so the picker is exactly as long as what is
        // declared: both launcher categories, because a TV build usually
        // registers only the leanback one.
        assertTrue(
            "the phone launcher category must be declared",
            queries.contains("android.intent.category.LAUNCHER")
        )
        assertTrue(
            "and the TV one, or the TV build this feature exists for stays invisible",
            queries.contains("android.intent.category.LEANBACK_LAUNCHER")
        )
        assertTrue(
            "both are MAIN-action intents",
            queries.contains("android.intent.action.MAIN")
        )
        assertTrue(
            "the engine must query those same categories",
            engine.contains("Intent.CATEGORY_LAUNCHER") &&
                engine.contains("Intent.CATEGORY_LEANBACK_LAUNCHER")
        )
    }

    @Test
    fun `the hand-picked app outranks everything the probe found`() {
        val body = engine
        val target = body.substringAfter("fun target(context: Context)")
            .substringBefore("fun rememberedLabel(")
        assertTrue(
            "a pick the viewer made by hand is the most specific answer there is",
            target.contains("customPlayer(context)?.let { return it }")
        )
        // ...and it has to keep the engine reachable at all: PlayerEngine falls
        // back to ExoPlayer whenever isAvailable() is false, so a box whose
        // probe found nothing would never reach the engine the viewer just
        // configured.
        val available = body.substringAfter("fun isAvailable(context: Context)")
            .substringBefore("fun target(context: Context)")
        assertTrue(
            "a custom pick counts as an available external player",
            available.contains("customPlayer(context) != null")
        )
        val prompt = body.substringAfter("fun shouldPrompt(context: Context)")
            .substringBefore("fun appLaunchIntent(")
        assertTrue(
            "and must not be overridden by the chooser: the viewer named the app",
            prompt.contains("if (customPlayer(context) != null) return false")
        )
    }

    @Test
    fun `an uninstalled pick falls back to detection instead of answering null`() {
        val custom = engine.substringAfter("private fun customPlayer(context: Context)")
            .substringBefore("/**\n     * The launcher categories")
        assertTrue(
            "a package that is gone must resolve to null, which is the rollback",
            custom.contains("runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null")
        )
        assertTrue(
            "and the pick must come from its own preference, so clearing it keeps the " +
                "probe's pick",
            custom.contains("AppPreferences.getCustomExternalPlayerPackage(context)")
        )
    }

    @Test
    fun `the launch falls back to the app's own entry when it takes no video request`() {
        val launch = engine.substringAfter("fun appLaunchIntent(context: Context, packageName: String?)")
            .substringBefore("fun launchIntent(url: String")
        assertTrue(
            "the fallback is the app's launcher entry",
            launch.contains("getLaunchIntentForPackage(pkg)")
        )
        // A new task drops the Activity Result the wrapper needs to see the
        // viewer come back at all - the same rule the scheme hand-off follows.
        assertTrue(
            "and it must not start a new task",
            launch.contains("flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()")
        )
        val handOff = wrapper.substringAfter("var launched = ErrorLog.runCatching {")
            .substringBefore("if (launched.isSuccess)")
        assertTrue(
            "the hand-off must try the launcher entry when the direct launch is refused",
            handOff.contains("ExternalPlayer.appLaunchIntent(this, target?.packageName)")
        )
        assertTrue(
            "and launch it through the same result-aware launcher",
            handOff.contains("launched = ErrorLog.runCatching")
        )
    }

    @Test
    fun `the pick is stored separately from the remembered probe match`() {
        assertTrue(
            "the custom pick needs its own keys, or clearing one clears both",
            prefs.contains("KEY_CUSTOM_EXTERNAL_PLAYER_PACKAGE") &&
                prefs.contains("KEY_CUSTOM_EXTERNAL_PLAYER_LABEL")
        )
        assertTrue(
            "and a setter that can clear it with a null package",
            prefs.contains("fun setCustomExternalPlayer(context: Context, packageName: String?, label: String?)")
        )
        // Device-local, like the pick beside it: which video apps are installed
        // is a property of the box, not of the account.
        val syncRules = source("src/main/java/com/kennyb1201/kbstream/data/sync/SyncRules.kt")
        assertTrue(
            "the hand-picked app must not travel with a synced account",
            !syncRules.contains("custom_external_player_package")
        )
    }

    @Test
    fun `Settings offers the picker and can undo it`() {
        val screen = settings
        assertTrue(
            "the app list must be read through the engine",
            screen.contains("ExternalPlayer.allApps(context)")
        )
        assertTrue(
            "and offered as a row beside the detected players",
            screen.contains("\"Choose custom app…\"")
        )
        val dialog = screen.substringAfter("private fun SettingsCustomPlayerDialog(")
            .substringBefore("/**\n * A labeled option row for the audio-tuning settings")
        assertTrue(
            "the dialog scrolls a bounded list rather than growing past the panel",
            dialog.contains("LazyColumn(") && dialog.contains(".height(280.dp)")
        )
        assertTrue(
            "picking an app stores it and turns the chooser off",
            dialog.contains("onPick(app)") &&
                screen.contains("AppPreferences.setCustomExternalPlayer(context, app.packageName, app.label)")
        )
        // Clearing has to be offered, not implied: a pick that can only be
        // replaced is a pick the viewer cannot back out of.
        assertTrue(
            "the dialog puts detection back",
            dialog.contains("\"Detected players only\"") &&
                screen.contains("AppPreferences.setCustomExternalPlayer(context, null, null)")
        )
        // A hand-picked app outranks the probe, so a detected chip that is
        // pressed has to drop the override - otherwise the press looks dead.
        assertTrue(
            "and a detected chip clears the override when it is picked",
            screen.contains("AppPreferences.setCustomExternalPlayer(context, null, null)") &&
                screen.contains("customExternalPackage = null\n                                customExternalLabel = null")
        )
    }
}
