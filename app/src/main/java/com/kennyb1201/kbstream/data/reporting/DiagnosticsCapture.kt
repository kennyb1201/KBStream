package com.kennyb1201.kbstream.data.reporting

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The file half of COPY DIAGNOSTICS, and the words the row says about it.
 *
 * Field-verified 2026-11-12 on a TCL: presses of COPY DIAGNOSTICS kept handing
 * back the SAME report from an hour earlier, and the row said "copied" every
 * time. The copy path was the problem, not the build - [Diagnostics.build]
 * stamps `generated:` fresh on every call, so a paste that still carries
 * 13:05:44 is, by definition, not this press's report. `copyToClipboard`
 * swallowed its own failure into a log line and returned `Unit`, so the row
 * could not tell "the clipboard took it" from "the clipboard refused it".
 *
 * Two things follow, and this file is both of them:
 *
 *  - EVERY press is written to a file, not only a failed one. The clipboard is
 *    the convenient channel and stays the primary one, but it is also the
 *    channel that failed here, and a file in the app's own directory needs no
 *    permission, survives a force-stop and can be pulled with adb - so it is
 *    the copy that cannot lie.
 *  - The status line is built from the SAME two facts the report carries (its
 *    generated time and the installed build), so the row and the pasted text
 *    can never disagree about how fresh the report is.
 *
 * Kept free of Android imports on purpose, [Diagnostics] being the Context-side
 * wrapper: the naming, the pruning and the wording are the parts worth testing,
 * and they are testable only if they do not need a device.
 */
internal object DiagnosticsCapture {

    /** The folder under the app's files dir. Named, not timestamped, on purpose. */
    const val DIR_NAME = "diagnostics"

    /**
     * The always-current copy. Overwritten on every press, so a viewer who only
     * remembers "there is a file somewhere" has exactly one to look at.
     */
    const val LATEST_NAME = "kbstream-diagnostics-latest.txt"

    private const val PREFIX = "kbstream-diagnostics-"
    private const val SUFFIX = ".txt"

    /**
     * Per-press files kept besides [LATEST_NAME]. Five is a week of reports for
     * someone chasing a bug, and small enough that the folder can never grow
     * unbounded - the whole point of the cap is that a returned-to bug leaves
     * older presses behind it.
     */
    const val MAX_KEPT = 5

    private val NAME_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    private val TIME_OF_DAY: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /**
     * `kbstream-diagnostics-20261112-152744.txt`, in the device's own zone.
     *
     * The stamp sorts lexicographically, which is what [prune] relies on to find
     * the newest without trusting mtimes (a clock change or a copy can make
     * those lie).
     */
    fun timestampedName(at: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = NAME_STAMP.format(Instant.ofEpochMilli(at).atZone(zone))
        return "$PREFIX$stamp$SUFFIX"
    }

    /** The report's own time of day, for the status line: `15:27`. */
    fun timeOfDay(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME_OF_DAY.format(Instant.ofEpochMilli(at).atZone(zone))

    /**
     * Writes [text] as a fresh timestamped file AND over [LATEST_NAME], prunes
     * the old presses, and returns the file the status should name.
     *
     * Null means the file was not written at all (a full disk, a read-only
     * data dir) - the caller must then say so rather than pointing at nothing.
     */
    fun save(dir: File, text: String, at: Long): File? = runCatching {
        dir.mkdirs()
        val file = File(dir, timestampedName(at))
        file.writeText(text)
        // The stable name is best-effort: a per-press file already exists, so a
        // failure here must not turn a good save into a null.
        runCatching { File(dir, LATEST_NAME).writeText(text) }
        prune(dir)
        file
    }.getOrNull()

    /** Deletes all but the [MAX_KEPT] newest per-press files. */
    private fun prune(dir: File) {
        val presses = dir.listFiles { file ->
            file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) &&
                file.name != LATEST_NAME
        }?.sortedByDescending { it.name } ?: return
        presses.drop(MAX_KEPT).forEach { stale -> runCatching { stale.delete() } }
    }

    /**
     * The success line, from the report's OWN generated time and the installed
     * build - so "is this fresh?" is answerable from the row, without pasting.
     */
    fun copiedStatus(at: Long, build: String): String =
        "copied \u00b7 ${timeOfDay(at)} \u00b7 build $build \u00b7 also logged as DIAGNOSTICS"

    /**
     * The failure line. Never says "copied": the clipboard was not updated, so
     * the sentence says that, why, and where the fresh report actually is.
     */
    fun failedStatus(reason: String, at: Long, build: String, file: File?): String {
        val where = file?.let { "saved to ${it.name}" } ?: "file write also failed"
        return "copy failed: $reason \u2014 report logged as DIAGNOSTICS and $where " +
            "\u00b7 ${timeOfDay(at)} \u00b7 build $build"
    }
}
