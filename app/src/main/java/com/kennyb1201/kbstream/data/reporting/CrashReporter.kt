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

    /**
     * Report a caught Throwable (typically an Error, or an Exception from a
     * scope with no handler) that did NOT crash the process because a
     * defensive layer swallowed it. Never throws.
     */
    fun recordNonFatal(throwable: Throwable, context: Map<String, String> = emptyMap()) {
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
            Log.w(TAG, "non-fatal report failed: ${t.message}")
        }
    }
}
