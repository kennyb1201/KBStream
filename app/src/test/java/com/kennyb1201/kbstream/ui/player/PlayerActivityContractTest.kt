package com.kennyb1201.kbstream.ui.player

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.kennyb1201.kbstream.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the three player activities promise the SYSTEM, read back from the
 * merged manifest.
 *
 * The audit finding this closes is that the two big player engines -
 * `NativePlayerActivity` (9,700 lines) and `MpvPlayerActivity` (3,900) - had no
 * automated coverage of any kind, and that this project has no instrumented
 * test variant at all. Launching either one under Robolectric is not the
 * answer: they build ExoPlayer/libmpv, take a video surface and load native
 * libraries, so a smoke-launch would test the mocks rather than the player.
 *
 * What CAN be checked, and is worth checking, is the contract they are declared
 * with - which is where the expensive mistakes live, because every one of them
 * is invisible until it happens on a device:
 *
 *  - `exported="false"`: all three start from an intent carrying a stream URL
 *    and launch extras. An exported player would let any other app on the TV
 *    open a session with a URL of its choosing, and the app carries the user's
 *    Simkl/MDBList sessions.
 *  - `configChanges` including `density` and `uiMode`: a TV switches display
 *    mode (resolution / HDR) a second or two into playback. An undeclared
 *    change recreates the activity - the decoder is torn down and rebuilt
 *    mid-title, which the viewer sees as a black screen with a spinner and then
 *    the film simply carrying on. That is the failure mode this assertion
 *    exists to catch, and it is exactly the kind of attribute a manifest edit
 *    drops without anyone noticing.
 *
 * Robolectric is what makes this a JVM test in the same
 * `./gradlew testDebugUnitTest` gate as everything else: it parses the merged
 * manifest and answers PackageManager queries from it, so no device or emulator
 * is involved. The no-op [Application] mirrors `LibraryAddPersistenceTest`: the
 * real one starts background work from `onCreate` that nothing here needs.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = PlayerActivityContractTest.NoopApplication::class)
class PlayerActivityContractTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val packageManager: PackageManager
        get() = context.packageManager

    private val engines = listOf(
        NativePlayerActivity::class.java,
        MpvPlayerActivity::class.java,
        ExternalPlayerActivity::class.java
    )

    private fun activityInfo(activityClass: Class<*>): ActivityInfo {
        val declared = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            .activities
            ?.firstOrNull { it.name == activityClass.name }
        assertNotNull(
            "${activityClass.name} is not declared in the merged manifest",
            declared
        )
        return declared!!
    }

    @Test
    fun `all three engines are declared`() {
        engines.forEach { engine -> activityInfo(engine) }
    }

    @Test
    fun `no engine can be launched by another app`() {
        engines.forEach { engine ->
            assertFalse(
                "${engine.simpleName} is exported: another app on the TV could " +
                    "open a playback session with a stream URL of its choosing",
                activityInfo(engine).exported
            )
        }
    }

    @Test
    fun `every engine survives the TV switching display mode mid-playback`() {
        // The set the manifest documents, as the bitmask the platform compares.
        val required =
            ActivityInfo.CONFIG_SCREEN_SIZE or
                ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
                ActivityInfo.CONFIG_SCREEN_LAYOUT or
                ActivityInfo.CONFIG_ORIENTATION or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_DENSITY or
                ActivityInfo.CONFIG_UI_MODE

        engines.forEach { engine ->
            val declared = activityInfo(engine).configChanges
            assertEquals(
                "${engine.simpleName} does not declare every config change it " +
                    "must absorb; an undeclared one recreates the activity and " +
                    "tears the decoder down mid-title (declared=0x" +
                    Integer.toHexString(declared) + ")",
                required,
                required and declared
            )
        }
    }

    @Test
    fun `the launcher is still the only entry point from the TV home screen`() {
        val main = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            .activities
            ?.firstOrNull { it.name == MainActivity::class.java.name }
        assertNotNull("MainActivity is not declared", main)
        assertTrue(
            "the TV launcher can no longer start the app",
            main!!.exported
        )
    }

    @Test
    fun `the self-update plumbing stays private`() {
        val providers = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            .providers
        val fileProvider = providers?.firstOrNull {
            it.authority == "${context.packageName}.updateprovider"
        }
        assertNotNull(
            "the update FileProvider (${context.packageName}.updateprovider) is gone; " +
                "the self-update install step shares the downloaded APK through it",
            fileProvider
        )
        assertFalse(
            "the update FileProvider is exported: it hands out APK files by content URI",
            fileProvider!!.exported
        )

        val receivers = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers
        val installReceiver = receivers?.firstOrNull {
            it.name == "com.kennyb1201.kbstream.data.update.AppUpdater\$InstallStatusReceiver"
        }
        assertNotNull("the install-status receiver is gone", installReceiver)
        assertFalse(
            "the install-status receiver is exported; only the system and this " +
                "app should be able to answer a PackageInstaller session",
            installReceiver!!.exported
        )
    }
}
