package com.kennyb1201.kbstream.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures the baseline profile for the app.
 *
 * How to run it (needs the device attached - a TV, a Fire TV stick or an
 * emulator, `adb devices` should list it):
 *
 * ```
 * ./gradlew :app:generateBaselineProfile
 * ```
 *
 * The result is written to
 * `app/src/release/generated/baselineProfiles/baseline-prof.txt` (see
 * `baselineProfile { saveInSrc = true }` in app/build.gradle.kts), which is
 * committed: it is input to the release build, not a build product. Without it
 * the app still works - it just gets the interpreted, slower first frames.
 *
 * What the profile covers is decided by what this test touches, so the
 * traversal below is the interesting part, not the test: it must reach the code
 * the user waits for. Currently that is app start plus the Home rails, which is
 * where virtually all of the cold-start cost sits (Application.onCreate
 * enqueues WorkManager jobs and builds the Coil loader, then the rail build
 * fans out over network and database work). Extend it - and re-run it - when a
 * screen gets slower, or when a new one joins the startup path.
 *
 * `pressHome()` first is deliberate: it clears whatever the device was showing
 * (the launcher's own work is not ours to profile) so the capture starts from a
 * cold launch each iteration.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndHome() {
        rule.collect(packageName = PACKAGE) {
            pressHome()
            startActivityAndWait()

            // The first frame is up; the rails are not. They are built after
            // onCreate's dispatch (addon manifest refresh, TMDB rails, the
            // Continue Watching cursor), so wait for the app's window to be
            // settled rather than stopping at the first frame - the profile is
            // worth most where the second of user-visible work happens.
            device.wait(Until.hasObject(By.pkg(PACKAGE)), TIMEOUT_MS)
            device.waitForIdle(IDLE_MS)
        }
    }

    private companion object {
        const val PACKAGE = "com.kennyb1201.kbstream"
        const val TIMEOUT_MS = 10_000L
        const val IDLE_MS = 2_000L
    }
}
