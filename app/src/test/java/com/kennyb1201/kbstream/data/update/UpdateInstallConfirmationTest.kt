package com.kennyb1201.kbstream.data.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * An install is reported across two launches, so this pins both halves.
 *
 * Android kills the process that starts an install, so the session that
 * downloads the APK can never say whether it worked. Before the handoff the
 * updater writes which build it is handing over; every later launch checks
 * whether the running build is that one and, if it is, raises a one-shot
 * "installed" state the UI shows once.
 *
 * The failure modes this guards are silent by construction: a marker that is
 * never consulted means the user is never told (the bug this exists for), and
 * a marker that is trusted too long means a prompt waved away months ago
 * announces a success that never happened.
 *
 * Runs under a no-op [Application] on purpose. The real one starts the 12h
 * update check on `Dispatchers.IO` from `onCreate`, which mutates the same
 * process-wide [AppUpdater.state] this test asserts on — so the default setup
 * makes every assertion below a race, not a check.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = UpdateInstallConfirmationTest.NoopApplication::class)
class UpdateInstallConfirmationTest {

    class NoopApplication : Application()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** What the running app reports, i.e. what [AppUpdater] compares against. */
    private val installed: Long = BuildConfig.VERSION_CODE.toLong()

    @Before
    fun setUp() {
        AppUpdater.state.value = AppUpdater.UpdateState.Idle
    }

    /** A staged APK named the way the updater names one. */
    private fun staged(versionName: String, versionCode: Long): File =
        File(context.cacheDir, "kbstream-$versionName-build$versionCode.apk")

    private fun assertNothingReported(what: String) {
        val current = AppUpdater.state.value
        assertFalse(
            "$what must not raise an install confirmation (was $current)",
            current is AppUpdater.UpdateState.Updated
        )
    }

    @Test
    fun `no handoff means no confirmation`() {
        AppUpdater.confirmInstallOnLaunch(context)
        assertNothingReported("a launch with no handoff recorded")
    }

    @Test
    fun `a handoff above the running build stays pending`() {
        AppUpdater.recordPendingInstall(context, staged("9.9.9", installed + 1))

        AppUpdater.confirmInstallOnLaunch(context)

        // Nothing to celebrate: the user waved the prompt away, or is still
        // looking at it.
        assertNothingReported("a handoff the user has not accepted")
    }

    @Test
    fun `a launch at the handed-off build reports it`() {
        AppUpdater.recordPendingInstall(context, staged("9.9.9", installed))

        AppUpdater.confirmInstallOnLaunch(context)

        assertEquals(
            AppUpdater.UpdateState.Updated(versionName = "9.9.9", versionCode = installed),
            AppUpdater.state.value
        )
    }

    @Test
    fun `the confirmation is one-shot`() {
        AppUpdater.recordPendingInstall(context, staged("9.9.9", installed))
        AppUpdater.confirmInstallOnLaunch(context)

        AppUpdater.acknowledge()
        assertEquals(AppUpdater.UpdateState.Idle, AppUpdater.state.value)

        // The marker was consumed, so a second launch is quiet rather than
        // re-announcing an install from a week ago.
        AppUpdater.confirmInstallOnLaunch(context)
        assertNothingReported("a second launch after the confirmation was shown")
    }

    @Test
    fun `an expired handoff is dropped, not reported`() {
        val longAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        AppUpdater.recordPendingInstall(context, staged("9.9.9", installed), atMs = longAgo)

        AppUpdater.confirmInstallOnLaunch(context)

        assertNothingReported("a handoff older than the trust window")
    }

    @Test
    fun `a name with no build number records nothing`() {
        AppUpdater.recordPendingInstall(context, File(context.cacheDir, "kbstream-9.9.9.apk"))

        AppUpdater.confirmInstallOnLaunch(context)

        // Nothing was recorded, so there is nothing to confirm (and no guess
        // at a version to announce wrongly).
        assertNothingReported("an unparseable staged file name")
    }

    @Test
    fun `acknowledge only clears the states the user owns`() {
        // An offer the app found is not the user's to dismiss: acknowledging
        // must not make a real update silently disappear.
        val offer = AppUpdater.UpdateState.Available(
            versionName = "9.9.9",
            versionCode = installed + 1,
            notes = "",
            downloadUrl = "https://example.invalid/app.apk",
            sizeBytes = 1L
        )
        AppUpdater.state.value = offer
        AppUpdater.acknowledge()
        assertTrue(AppUpdater.state.value is AppUpdater.UpdateState.Available)

        // The handoff is: the package-installer fallback reports nothing back,
        // so without this the dialog would have no way off the screen.
        AppUpdater.state.value =
            AppUpdater.UpdateState.ReadyToInstall(staged("9.9.9", installed))
        AppUpdater.acknowledge()
        assertEquals(AppUpdater.UpdateState.Idle, AppUpdater.state.value)

        AppUpdater.state.value = AppUpdater.UpdateState.Failed("Install cancelled")
        AppUpdater.acknowledge()
        assertEquals(AppUpdater.UpdateState.Idle, AppUpdater.state.value)
    }
}
