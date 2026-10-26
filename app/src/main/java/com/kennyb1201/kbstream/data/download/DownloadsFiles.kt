package com.kennyb1201.kbstream.data.download

import java.io.File
import android.content.Context

/**
 * Where a download's bytes live.
 *
 * App-specific external storage (`getExternalFilesDir`), with `filesDir` as the
 * fallback for the devices that report no external volume at all - an Android
 * box with the storage unmounted mid-session, which used to be a null
 * dereference waiting to happen. The fallback is a real path, not an error: a
 * download that cannot go to external storage still downloads.
 *
 * The consequence the spec asks to be stated out loud, and which the Downloads
 * screen's empty state says in as many words: this directory is the APP's, so
 * the files are invisible to gallery apps and to the system's Files browser, and
 * uninstalling the app takes them with it. Nothing here is a MediaStore entry,
 * deliberately - a phone's photo app has no business listing a viewer's
 * downloads, and a download that survives uninstall would be a file nobody owns.
 *
 * ## One cache directory for the device, one record set per profile
 *
 * Media3's `SimpleCache` holds an exclusive lock on its directory, so there can
 * be exactly ONE download cache in an app. Per-profile isolation therefore lives
 * in the records and in each download's cache key (see [DownloadIdentity]), not
 * in the directory layout: a profile switch changes which records are listed and
 * never which bytes are reachable, because a record is what addresses them.
 */
object DownloadsFiles {

    private const val DIR_NAME = "downloads"

    /**
     * The download cache directory, created if it is not there yet.
     *
     * Null only when neither location can be created, which the caller treats as
     * "this device cannot hold downloads" rather than as a crash.
     */
    fun directory(context: Context): File? {
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        val base = external ?: context.filesDir
        val dir = File(base, DIR_NAME)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }

    /** The bytes this app's downloads occupy, for the Settings row that reports them. */
    fun sizeBytes(context: Context): Long {
        val dir = directory(context) ?: return 0L
        return runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)
    }
}
