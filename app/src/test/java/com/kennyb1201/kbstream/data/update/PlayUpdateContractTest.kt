package com.kennyb1201.kbstream.data.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Play build gets Play's updater, and only the Play build does.
 *
 * `-PplayBuild` strips REQUEST_INSTALL_PACKAGES from the merged manifest, which
 * makes the GitHub-release updater a dead end on that install: it could offer
 * an update the build cannot install, and asking for that permission is a Play
 * policy risk. The answer is a sibling flow through Play's In-App Updates API,
 * not a branch inside [AppUpdater] - the two own different things (an APK and a
 * store session), and a shared state type would carry a download URL and a
 * staged file into a flow that has neither.
 *
 * These pins hold the split together at the three places it can drift: the
 * sibling itself, the build wiring that ships it, and the About row that picks
 * which one to show.
 */
class PlayUpdateContractTest {

    private fun projectFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("not found: $relative")
    }

    private fun source(relative: String): String =
        File(projectFile("app/build.gradle.kts").parentFile, relative).readText()

    private val playUpdater: String
        get() = source("src/main/java/com/kennyb1201/kbstream/data/update/PlayUpdater.kt")

    @Test
    fun `the Play path downloads and installs nothing itself`() {
        val updater = playUpdater
        assertTrue(
            "a Play update must go through the store: the flexible flow",
            updater.contains("AppUpdateType.FLEXIBLE")
        )
        assertTrue(
            "and the store, not the app, installs it",
            updater.contains("completeUpdate()")
        )
        listOf(
            "packageInstaller",
            "SessionParams",
            "FileProvider.getUriForFile",
            "Intent.ACTION_INSTALL_PACKAGE"
        ).forEach { needle ->
            assertFalse(
                "the Play build cannot install an APK, so the sibling must not try: $needle",
                updater.contains(needle)
            )
        }
        assertFalse(
            "no APK is downloaded by the app here either",
            updater.contains(".apk")
        )
    }

    @Test
    fun `the Play updater is phone-only and Play-build-only`() {
        val updater = playUpdater
        val enabled = updater
            .substringAfter("fun isEnabled()")
            .substringBefore("fun begin(")
        assertTrue(
            "a sideloaded install has no Play record to update from",
            enabled.contains("BuildConfig.PLAY_BUILD")
        )
        assertTrue(
            "a debug build is a side-by-side install Play does not own",
            enabled.contains("!BuildConfig.DEBUG")
        )
        assertTrue(
            "and a television has no touch surface for Play's update sheet",
            enabled.contains("!FormFactor.isTv")
        )
    }

    @Test
    fun `every entry point asks isEnabled first`() {
        val updater = playUpdater
        listOf("fun begin(", "fun checkForUpdate(", "fun startUpdate(").forEach { entry ->
            val body = updater
                .substringAfter(entry)
                .substringBefore("\n    fun ")
            assertTrue(
                "$entry must check the build and form factor before touching Play",
                body.contains("if (!isEnabled()) return")
            )
        }
    }

    @Test
    fun `the store is told once per process, not once per check`() {
        val updater = playUpdater
        assertTrue(
            "progress comes from an install listener",
            updater.contains("InstallStateUpdatedListener")
        )
        assertTrue(
            "registered once: a second registration is a warning and duplicate deliveries",
            updater.contains("if (listenerRegistered) return")
        )
    }

    @Test
    fun `the Play build ships the Play library`() {
        assertTrue(
            "the dependency must be on the classpath",
            projectFile("app/build.gradle.kts").readText()
                .contains("implementation(libs.google.play.app.update)")
        )
        assertTrue(
            "and versioned once, in the catalog",
            projectFile("gradle/libs.versions.toml").readText()
                .contains("com.google.android.play:app-update")
        )
    }

    @Test
    fun `the phone About pane picks the row that matches the build`() {
        val mobile = source(
            "src/main/java/com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        )
        val playBranch = mobile
            .substringAfter("if (BuildConfig.PLAY_BUILD) {")
            .substringBefore("} else if (!BuildConfig.DEBUG) {")
        assertTrue(
            "a Play install gets the Play row",
            playBranch.contains("PlayUpdateCardMobile()")
        )
        assertTrue(
            "and the GitHub-release row is not drawn there",
            !playBranch.contains("AppUpdater")
        )
        assertTrue(
            "the sideloaded row still exists for non-debug installs",
            mobile.contains("} else if (!BuildConfig.DEBUG) {") &&
                mobile.contains("PlayUpdater.checkForUpdate(context)")
        )
    }

    @Test
    fun `the television About pane is untouched`() {
        assertFalse(
            "TV carries no Play updates, so it composes no Play row",
            source("src/main/java/com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
                .contains("PlayUpdater")
        )
    }

    @Test
    fun `a download that finished while the app was closed is adopted at launch`() {
        val app = source("src/main/java/com/kennyb1201/kbstream/MainApplication.kt")
        assertTrue(
            "Play keeps the download, so the next process has to pick it up",
            app.contains("PlayUpdater\n                    .begin(this@MainApplication)")
        )
    }
}
