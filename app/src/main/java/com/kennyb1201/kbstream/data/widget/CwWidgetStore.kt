package com.kennyb1201.kbstream.data.widget

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.io.File

/**
 * Where the Continue Watching widget reads its list (spec 2026-11-13, F3).
 *
 * A small JSON file in the app's own storage, written by the app whenever Home's
 * Continue Watching list changes and read by the widget - the same file either
 * way, in the same process, so there is exactly ONE list.
 *
 * A FILE rather than a preference because it is a document, not a setting: it is
 * replaced whole, it is read whole, and it can be handed to a background
 * RemoteViews factory without SharedPreferences' single-instance caching games.
 * It is not a second database - it holds at most [CwWidgetRules.MAX_ROWS]
 * rendered rows and nothing here ever queries the history tables.
 *
 * A read NEVER throws: a missing file is "no list yet", an unreadable one falls
 * back to the last good copy and is drawn WITH the stale marker, and neither is a
 * crash (F2/F3's fail-soft). The `.bak` copy exists for exactly that case: a
 * torn write or a hand-mangled file must not cost the viewer their last list.
 */
internal object CwWidgetStore {

    private const val TAG = "CwWidgetStore"

    const val FILE_NAME = "cw_widget_snapshot.json"

    private const val BACKUP_NAME = "cw_widget_snapshot.bak"

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    private fun backupFile(context: Context): File =
        File(context.applicationContext.filesDir, BACKUP_NAME)

    /** Writes [snapshot], keeping the previous good copy as the fallback. */
    fun write(context: Context, snapshot: CwWidgetSnapshot) {
        val target = file(context)
        val encoded = runCatching { CwWidgetSnapshotJson.encode(snapshot) }.getOrNull() ?: return
        runCatching {
            if (target.isFile) {
                // The previous file was readable enough to be the last good list;
                // copying it (rather than renaming) means a crash mid-write leaves
                // BOTH copies in place.
                runCatching { target.copyTo(backupFile(context), overwrite = true) }
            }
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writeText(encoded)
            if (!temp.renameTo(target)) {
                // A rename can fail across a bound mount; the direct write is the
                // fallback, and a reader mid-write then decodes null and keeps the
                // previous list until the next refresh.
                target.writeText(encoded)
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "could not write snapshot: ${it.javaClass.simpleName}") }
    }

    /**
     * The snapshot to draw.
     *
     * A snapshot filed under ANOTHER profile is not drawn: a switch rewrites the
     * file, so a mismatch means the write never landed, and the last-active
     * profile's rows must not be shown under a different profile's name.
     */
    fun read(
        context: Context,
        activeProfileId: String? = ProfileStorage.activeProfileId(context)
    ): CwWidgetState {
        val target = file(context)
        val primary = readSnapshot(target)
        if (primary != null) {
            return stateFor(primary, activeProfileId, stale = CwWidgetRules.isStale(primary.writtenAtMs))
        }

        if (!target.isFile) {
            // Nothing was ever written: an honest empty state, not a stale one.
            return CwWidgetState(snapshot = null, stale = false, profileId = activeProfileId)
        }

        // There IS a file and it did not decode: fall back to the last good copy
        // and draw it WITH the stale marker.
        val backup = readSnapshot(backupFile(context))
        if (backup != null && backup.profileId == activeProfileId) {
            return stateFor(backup, activeProfileId, stale = true)
        }
        return CwWidgetState(snapshot = null, stale = true, profileId = activeProfileId)
    }

    private fun stateFor(
        snapshot: CwWidgetSnapshot,
        activeProfileId: String?,
        stale: Boolean
    ): CwWidgetState {
        if (snapshot.profileId != activeProfileId) {
            return CwWidgetState(snapshot = null, stale = true, profileId = activeProfileId)
        }
        return CwWidgetState(snapshot = snapshot, stale = stale, profileId = activeProfileId)
    }

    /** Reads and decodes one file; any failure at all is "no snapshot here". */
    private fun readSnapshot(file: File): CwWidgetSnapshot? = runCatching {
        if (!file.isFile) return@runCatching null
        CwWidgetSnapshotJson.decode(file.readText())
    }.getOrNull()
}
