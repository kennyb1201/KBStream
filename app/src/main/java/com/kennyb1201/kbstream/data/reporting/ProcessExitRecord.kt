package com.kennyb1201.kbstream.data.reporting

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The OS's own account of how the previous process ended.
 *
 * Why this exists: [PlaybackDeathMarker] proves that a playback session never
 * finished, but it cannot say *why* - it is written by the process that dies,
 * so the last thing it can record is what the player was doing. The one report
 * it produced before this read "exo playback: the process died with the player
 * playing — at 0:00, open 0:01": true, and unactionable, because "the process
 * died" has a dozen causes that want opposite responses (a native abort wants a
 * tombstone, a low-memory kill wants a smaller heap at the player's peak, an
 * app update wants nothing at all).
 *
 * Android keeps that answer for us. `getHistoricalProcessExitReasons` reports
 * every recent termination of this package with a reason, a signal where one
 * applies, and - for the ordinary cases - the system's own sentence. One call
 * at the next launch therefore turns the marker's question mark into the one
 * fact that decides what to do about it.
 *
 * [deliberate] is the other half: a process the viewer force-stopped, or that
 * the installer replaced mid-session, leaves the same marker a crash does, and
 * reporting those as playback deaths is how a crash feed fills with events
 * nobody can act on.
 */
internal data class ProcessExit(
    /** Reads at the end of the death sentence, e.g. "killed for memory". */
    val label: String,
    /** True when the exit was performed on purpose, so it is no death at all. */
    val deliberate: Boolean
)

/**
 * Reads [ProcessExit] for the process that left a [PlaybackDeathMarker]
 * behind.
 *
 * Null is a real answer: below API 30 the platform keeps no such history, and
 * on any version a termination the system did not record stays "unknown" in the
 * report rather than being guessed at.
 */
internal object ProcessExitRecord {

    /**
     * How many recent exits to ask for. The one that matters is the newest, but
     * a handoff can leave a short-lived second process behind and the record
     * for the process that actually played has to be found past it.
     */
    private const val EXIT_RECORD_LIMIT = 8

    /**
     * The most recent termination of this package's main process, or null when
     * the platform has none recorded (a first launch, or pre-API 30).
     *
     * The newest record is the previous process by definition: this one is
     * still running, and its own record cannot exist until it ends. Records
     * belonging to a `:remote` process are skipped - they never hold the
     * player - and so is any record whose name is not this package, because
     * reporting a helper's exit as the player's would be worse than reporting
     * nothing.
     */
    fun previous(context: Context): ProcessExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return null
            val records = manager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                EXIT_RECORD_LIMIT
            )
            val record = records.firstOrNull { it.processName == context.packageName }
                ?: return null
            describe(record.reason, record.status, record.description)
        }.getOrNull()
    }

    /**
     * The reason, as the report reads it.
     *
     * One arm per cause the platform distinguishes, grouped by what it means for
     * a playback session that was running when the process ended: the first
     * block is a death, the second is an exit nobody should be paged for. The
     * signal number is carried where the platform supplies one - a native abort
     * is a signal, and 11 next to "native crash" is the difference between a
     * codec tombstone and a null dereference in our own JNI.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(reason: Int, status: Int, description: String?): ProcessExit = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ProcessExit("native crash${signal(status)}", false)
        ApplicationExitInfo.REASON_CRASH -> ProcessExit("crash", false)
        ApplicationExitInfo.REASON_ANR -> ProcessExit("not responding (ANR)", false)
        ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExit("killed for memory", false)
        ApplicationExitInfo.REASON_SIGNALED -> ProcessExit("killed by signal${signal(status)}", false)
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            ProcessExit("killed for excessive resource use", false)
        ApplicationExitInfo.REASON_USER_REQUESTED ->
            ProcessExit("force-stopped by the viewer or the system", true)
        ApplicationExitInfo.REASON_USER_STOPPED -> ProcessExit("closed by the viewer", true)
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE ->
            ProcessExit("the app was updated or reinstalled", true)
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> ProcessExit("permissions changed", true)
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ProcessExit("failed to start", true)
        ApplicationExitInfo.REASON_EXIT_SELF -> ProcessExit("exited on its own", true)
        else -> ProcessExit(
            // The system's own sentence is the only useful thing left for a
            // reason this build does not know ("Reboot", "force-stop"), and it
            // is also the answer for REASON_OTHER, which is where a reboot
            // lands - so it is carried rather than dropped.
            description?.trim()?.takeIf { it.isNotBlank() }?.let { "other ($it)" } ?: "other",
            false
        )
    }

    /** ` (signal 11)`, or nothing when the platform recorded no signal. */
    private fun signal(status: Int): String = if (status > 0) " (signal $status)" else ""
}
