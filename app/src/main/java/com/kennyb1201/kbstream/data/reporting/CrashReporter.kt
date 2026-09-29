package com.kennyb1201.kbstream.data.reporting

import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Tiny facade over Sentry for NON-FATAL reporting from defensive catch sites.
 *
 * Why a facade instead of calling SentryAndroid at each call site: the
 * startup-hardening paths (SupabaseSync, Application/Activity onCreate) must
 * be safe to execute even if the Sentry SDK itself were to fail to load (e.g.
 * an R8 edge case in a release build). Indirection keeps those paths free of
 * any hard dependency on the SDK's classes, and gives one place to tag where
 * a non-fatal came from.
 *
 * Non-fatal events land in the same Sentry project as crashes (when a DSN is
 * baked into the build); without a DSN everything here is a silent no-op.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    /** How many non-fatals are retained for the diagnostics export. */
    private const val MAX_RECENT = 10

    /**
     * True only inside a JVM unit test, false in every build that ships.
     *
     * Robolectric is a `testImplementation` dependency, so its classes are on
     * the unit-test classpath and in no APK - which makes loading one a
     * reliable "am I a test?" probe. In a shipped build it is a single failed
     * class lookup.
     *
     * Reporting sites use it to refuse to report. CI passes SENTRY_DSN at
     * workflow level, so `testDebugUnitTest` bakes the PRODUCTION DSN into the
     * debug variant - the same variant `assembleDebug` ships - and a
     * Robolectric test that boots MainApplication would otherwise initialize
     * the real Sentry client and send this JVM's caught exceptions to the live
     * project. One CI test run did exactly that: roughly 400 events across six
     * issues, every one of them tagged `device=robolectric` and attributed to
     * the release being built, which is how test noise came to rank above the
     * real device crashes on the dashboard.
     */
    internal fun isJvmUnitTest(): Boolean =
        runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess

    /**
     * Whether this process should initialize Sentry at all.
     *
     * Two independent refusals: a build with no DSN baked in does not report,
     * and neither does a JVM unit test even though one IS baked in - CI passes
     * SENTRY_DSN at workflow level, so `testDebugUnitTest` compiles it into the
     * debug variant. See [isJvmUnitTest] for what that cost when it was
     * missing.
     *
     * Pure in [dsn] so the truth table is testable without a DSN and without
     * standing up the SDK.
     */
    internal fun shouldInitCrashReporting(dsn: String): Boolean =
        dsn.isNotBlank() && !isJvmUnitTest()

    /** One retained non-fatal, as shown in Settings → Sync diagnostics. */
    data class RecentError(val atMs: Long, val source: String, val summary: String)

    private val recentLock = Any()
    private val recent = ArrayDeque<RecentError>()

    /**
     * Report a caught Throwable (typically an Error, or an Exception from a
     * scope with no handler) that did NOT crash the process because a
     * defensive layer swallowed it. Never throws.
     */
    fun recordNonFatal(throwable: Throwable, context: Map<String, String> = emptyMap()) {
        // Retained FIRST, and regardless of whether a DSN is baked in: the
        // diagnostics export has to work on dev builds too, where nothing is
        // ever sent to Sentry.
        remember(throwable, context)
        // Note on what is sent: the raw Throwable goes to Sentry (its stack is
        // the point), but Sentry scrubs the message text on the way out via the
        // beforeSend/beforeBreadcrumb hooks in MainApplication.initCrashReporting
        // -- supabase-kt messages embed the request URL and bearer token.
        // Everything WE build from the message (the retained summary, log lines)
        // is redacted here.
        try {
            if (com.kennyb1201.kbstream.BuildConfig.SENTRY_DSN.isBlank()) return
            // SentryAndroid.init() in MainApplication installs the global
            // scope; captureException attaches to that scope so events carry
            // the release/dist/git_sha tags set there.
            Sentry.captureException(throwable) { scope ->
                scope.level = SentryLevel.WARNING
                context.forEach { (key, value) -> scope.setTag(key, value) }
            }
        } catch (t: Throwable) {
            // Reporting must never be the thing that kills the app.
            Log.w(TAG, "non-fatal report failed: ${Redaction.text(t.message)}")
        }
    }

    /**
     * Retains a one-off EVENT for the diagnostics export, without sending it to
     * Sentry.
     *
     * [recordNonFatal] is for a defensive catch site - something went wrong that
     * should not have. A stream that will not open is the opposite: sources die
     * constantly by design, the viewer is shown a card and offered another one,
     * and shipping every dead link to Sentry would bury the real defects under
     * routine churn. But the report still has to be able to name it, because on
     * a TV the card on screen is otherwise the ONLY record that anything
     * failed - a capture taken while the viewer is looking at "Playback failed"
     * said "recent errors: none this session", which is exactly the wrong
     * answer to the question the report exists to answer.
     */
    fun recordEvent(source: String, summary: String) {
        val entry = RecentError(
            atMs = System.currentTimeMillis(),
            source = source,
            summary = Redaction.text(summary).take(160)
        )
        synchronized(recentLock) {
            recent.addLast(entry)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
    }

    private fun remember(throwable: Throwable, context: Map<String, String>) {
        val summary = throwable::class.java.simpleName +
            (throwable.message?.let { ": ${Redaction.text(it).take(160)}" } ?: "")
        val entry = RecentError(
            atMs = System.currentTimeMillis(),
            source = context["source"] ?: "unknown",
            summary = summary
        )
        synchronized(recentLock) {
            recent.addLast(entry)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
    }

    /**
     * Non-fatals caught since launch, newest last. This is what the user can
     * see in the diagnostics export — the whole point being that a TV app has
     * no console and "it did something weird" is otherwise unfalsifiable.
     */
    fun recentErrors(): List<RecentError> =
        synchronized(recentLock) { recent.toList() }
}
