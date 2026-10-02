package com.kennyb1201.kbstream.data.reporting

import io.sentry.Sentry

/**
 * Mirrors [PerfTrace]'s SLOW samples into Sentry as distributions.
 *
 * Why this exists: [PerfTrace] is an in-process ring buffer - useful on the
 * device it was recorded on, invisible from anywhere else. The tracing
 * MainApplication turns on (tracesSampleRate) carries Sentry's own auto
 * instrumentation (app start, activity lifecycle) but has no idea about this
 * app's own labels, so a rail that is slow *only on one model of TV* still
 * cannot be seen from the dashboard. Forwarding the slow samples as metric
 * distributions - keyed by the same release/dist as the crash reports - is
 * what makes that question answerable, and it is deliberately limited to
 * [PerfTrace]'s slow threshold so routine traffic costs nothing.
 *
 * Deliberately a facade, like [CrashReporter]: the Sentry classes are touched
 * only inside the try, so a hot path (and, in particular, a startup path) can
 * call a [PerfTrace] method without a hard dependency on the SDK loading.
 *
 * No-op without a DSN baked into the build, and in any JVM unit test even when
 * one is - CI passes the production DSN to the debug variant, and a test that
 * booted the SDK would report into the live project (see
 * [CrashReporter.isJvmUnitTest]).
 */
internal object SentryPerf {

    /** Metric key namespace, so these never collide with anything else. */
    private const val PREFIX = "kbstream.perf."

    /**
     * A ceiling on how many samples one process may send.
     *
     * The ring buffer keeps 400 samples, and every one of them at or above the
     * slow threshold would otherwise leave the device; a session with a
     * persistently slow backend could send hundreds. Bounded here so the
     * metric stream stays a signal rather than a flood, matching how the rest
     * of this package treats Sentry quota.
     */
    private const val MAX_PER_SESSION = 80

    private val lock = Any()
    private var sent = 0

    /**
     * Sends one slow sample as a distribution, or does nothing when reporting
     * is off or the session's ceiling is reached. Safe from any thread, and
     * never throws.
     */
    fun slowSample(label: String, ms: Long) {
        try {
            if (com.kennyb1201.kbstream.BuildConfig.SENTRY_DSN.isBlank()) return
            if (CrashReporter.isJvmUnitTest()) return
            synchronized(lock) {
                if (sent >= MAX_PER_SESSION) return
                sent++
            }
            Sentry.metrics().distribution(metricKey(label), ms.toDouble())
        } catch (_: Throwable) {
            // Reporting must never be the thing that breaks a hot path.
        }
    }

    /**
     * A Sentry metric key for [label]: the namespace prefix plus the label
     * with anything outside `[A-Za-z0-9_.]` folded to `_`. Most labels are
     * already dotted (`addon.catalog`, `http.ip`), which passes through
     * unchanged; this only guards a label that ever grew a character the
     * metric key format does not allow.
     */
    internal fun metricKey(label: String): String = buildString {
        append(PREFIX)
        label.forEach { ch ->
            val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '.'
            append(if (ok) ch else '_')
        }
    }
}
