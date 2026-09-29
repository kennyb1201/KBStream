package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrashReporter.shouldInitCrashReporting] is the guard that keeps a test run
 * out of the production Sentry project.
 *
 * CI passes SENTRY_DSN at workflow level, so `testDebugUnitTest` bakes the real
 * DSN into the debug variant - the same variant `assembleDebug` ships - and
 * every Robolectric test that booted MainApplication therefore initialized the
 * live Sentry client and sent this JVM's caught exceptions to it. One CI run
 * produced roughly 400 events across six issues, all tagged
 * `device=robolectric`, all attributed to the release being built, which is how
 * test noise came to rank above the real device crashes on the dashboard.
 *
 * These cases are deliberately NOT vacuous locally. [Class.forName] sees the
 * whole classpath, and Robolectric is a `testImplementation` dependency, so the
 * probe is true right here however the DSN reached the build - CI env,
 * local.properties, or a developer's shell.
 */
class CrashReporterGuardTest {

    private val realDsn = "https://public@o1.ingest.sentry.io/1234"

    @Test
    fun `a JVM unit test never initializes crash reporting, even with a DSN`() {
        // The regression this exists for: a test run reporting production
        // events. The DSN is real here on purpose - it is exactly the case that
        // used to initialize the client.
        assertFalse(
            "a test JVM must refuse to report even when the build has a DSN " +
                "baked in, which is what CI produces",
            CrashReporter.shouldInitCrashReporting(realDsn)
        )
    }

    @Test
    fun `a build with no DSN does not initialize crash reporting`() {
        // Unchanged behaviour: without a DSN the app must be silent, test or not.
        assertFalse(CrashReporter.shouldInitCrashReporting(""))
        assertFalse(CrashReporter.shouldInitCrashReporting("   "))
    }

    @Test
    fun `the probe identifies this JVM as a unit test`() {
        // The probe must be TRUE here or the two cases above pass for the wrong
        // reason. It is the unit-test-side of a check whose other side - that
        // Robolectric is absent from a shipped APK - is a property of the build
        // graph rather than something this JVM can observe.
        assertTrue(
            "Robolectric is on the test classpath, so the probe must fire here",
            CrashReporter.isJvmUnitTest()
        )
    }
}
