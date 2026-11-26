package com.kennyb1201.kbstream.data.update

import android.app.Application
import android.app.NotificationManager
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sideloaded install ends by saying so (spec 2026-11-25).
 *
 * The success callback is the only thing that runs after an install lands, and
 * it used to do nothing: the process it belonged to was already replaced, the
 * viewer was on the launcher, and the "installed" dialog belonged to a manual
 * relaunch that never happened. Now it starts this app's prompt (an activity,
 * carrying [AppUpdater.EXTRA_UPDATE_INSTALLED]) and, when the platform refuses
 * that start, posts a notification instead - never neither.
 *
 * Both arms are exercised on a real receiver and a real Context: the intent the
 * prompt launch carries is the whole contract with the shell (see
 * `UpdateInstalledPrompt` in MainActivity), so a typo in the extra or a missing
 * NEW_TASK would leave the viewer with a dialog that never appears.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = UpdateInstalledPromptReceiverTest.NoopApplication::class)
class UpdateInstalledPromptReceiverTest {

    /** The real app's startup does network work; this test wants the receiver only. */
    class NoopApplication : Application()

    private fun success(): Intent =
        Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)

    @Test
    fun `install success opens the prompt over the app`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        AppUpdater.InstallStatusReceiver().onReceive(app, success())

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(
            "an install nobody is told about is the bug this prompt exists to fix",
            started
        )
        assertEquals(
            "the prompt is this app's own activity",
            MainActivity::class.java.name,
            started.component?.className
        )
        assertTrue(
            "and it carries the extra the shell draws the prompt from",
            started.getBooleanExtra(AppUpdater.EXTRA_UPDATE_INSTALLED, false)
        )
        assertTrue(
            "started from a receiver, so it needs a task of its own",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }

    @Test
    fun `a refused start posts the fallback notification`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The alert is what the fallback is FOR, and on API 33+ the app cannot
        // post without the runtime grant (`NotificationCenter.canPost` reads
        // areNotificationsEnabled, which is that grant).
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Exactly what a vendor ROM does to a background activity start from a
        // broadcast receiver.
        val refusing = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                throw SecurityException("background activity start not allowed")
            }
        }

        AppUpdater.InstallStatusReceiver().onReceive(refusing, success())

        val manager = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications
        assertEquals(
            "no prompt and no alert is not an option - the install happened",
            1,
            posted.size
        )
        assertNotNull(
            "and the alert has to be tappable, or it is only a sentence",
            posted.first().contentIntent
        )
    }
}
