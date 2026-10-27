package com.kennyb1201.kbstream.data.download

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The download records, and the two download toggles, on this device only.
 *
 * ## Why this is its own prefs file, and not two keys in AppPreferences
 *
 * Three separate rules say the same thing from three directions, and one file
 * satisfies all three by construction:
 *
 *  - downloads are DEVICE state, so they must not sync (§2.5, §8). The sync
 *    payload is an allow-list of key names ([com.kennyb1201.kbstream.data.sync
 *    .PrefsPayloadBuilder.SYNCED_PREF_KEYS]), and none of these names is on it -
 *    and nothing here is in the player-prefs file that builder reads;
 *  - the toggles must not appear in any Supabase blob either (§8);
 *  - and neither the records nor the toggles may ride a PROFILE EXPORT.
 *    [com.kennyb1201.kbstream.data.backup.BackupManager] exports the whole
 *    `kbstream_player_prefs` file key-by-key - every key in it, without an
 *    allow-list - so a toggle stored there would be carried in every backup the
 *    viewer makes, with no further code change needed and nothing to notice it.
 *    This file is not one BackupManager knows about, which is what makes the
 *    exclusion structural instead of a promise.
 *
 * Scoping follows every other preference in the app: `ProfileStorage.prefsName`
 * puts this file's name behind the active profile, so a profile switch shows only
 * that profile's downloads, and a profile's records go away with the profile
 * (ProfileManager's wipe clears every preference namespace it owns). "Device
 * local" here means "not synced", exactly as it does for the device-local
 * display prefs beside it in Settings.
 */
object DownloadsStore {

    /**
     * The preference file's base name. Public so the isolation test can assert it
     * is not one of the files a profile export carries, and that no key of it is
     * on the sync allow-list - the two rules that make a download impossible to
     * carry off this phone.
     */
    const val PREFS_NAME = "kbstream_downloads"

    internal const val KEY_RECORDS = "records_json"

    /**
     * Where the first blob this build could not read is kept, before a write
     * replaces it. See [preserveUnreadableBlob].
     */
    internal const val KEY_RECORDS_UNREADABLE = "records_json_unreadable"

    internal const val KEY_DOWNLOAD_OVER_CELLULAR = "download_over_cellular"
    internal const val KEY_DELETE_AFTER_FINISH = "delete_after_finish"

    private const val TAG = "DOWNLOADS"

    private val json = Json {
        // A field a future build stops writing must not make an existing file
        // unreadable: the record is still worth listing and playing.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(DownloadRecord.serializer())

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, PREFS_NAME),
            Context.MODE_PRIVATE
        )

    /** Every record for the active profile, newest enqueue first. */
    fun records(context: Context): List<DownloadRecord> = read(prefs(context))

    /**
     * Every record for ONE NAMED profile, whoever is active.
     *
     * Needed by exactly one caller: a profile being DELETED has to give up its
     * downloads whether or not it is the profile on screen, and the wipe that
     * follows cannot read a store whose records it is about to clear.
     */
    fun recordsFor(context: Context, profileId: String): List<DownloadRecord> {
        if (profileId.isBlank()) return emptyList()
        val scoped = context.getSharedPreferences(
            ProfileStorage.prefsName(profileId, PREFS_NAME),
            Context.MODE_PRIVATE
        )
        return read(scoped)
    }

    private fun read(prefs: SharedPreferences): List<DownloadRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        runCatching { json.decodeFromString(serializer, raw) }
            .onSuccess { decoded -> return decoded.sortedByDescending { it.addedAt } }
        // The blob did not decode as a whole list. It used to become the same
        // empty list a fresh install has - and because EVERY download event
        // persists a merge (see [DownloadsManager.refresh]), the next pause,
        // completion or delete wrote that emptiness back, so one unreadable blob
        // meant the whole queue was gone for good (audit C1, 2026-10-27). What
        // it decodes to IN PIECES is now what it reads as: a blob truncated
        // mid-write - the shape `apply()` racing a process death leaves - loses
        // its tail instead of everything.
        val salvaged = salvageRecords(raw)
        Log.w(
            TAG,
            "download records blob could not be read as a list; salvaged ${salvaged.size}"
        )
        return salvaged.sortedByDescending { it.addedAt }
    }

    /**
     * Every record that still decodes out of a blob the LIST decoder rejected.
     *
     * Each top-level `{...}` group is offered to the record decoder on its own,
     * so a blob whose tail was cut off - or whose one bad record has a value no
     * build understands - gives up only what is actually unreadable. The brace
     * walk respects strings and escapes, because a record's own text (a title, a
     * description) contains both braces and quotes.
     */
    internal fun salvageRecords(raw: String): List<DownloadRecord> {
        val out = ArrayList<DownloadRecord>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        raw.forEachIndexed { index, character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (character) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }

                '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) {
                        runCatching {
                            json.decodeFromString(
                                DownloadRecord.serializer(),
                                raw.substring(start, index + 1)
                            )
                        }.getOrNull()?.let(out::add)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    /** Writes one record, replacing the record with the same id when there is one. */
    fun put(context: Context, record: DownloadRecord) {
        val current = records(context)
        val next = if (current.any { it.id == record.id }) {
            current.map { if (it.id == record.id) record else it }
        } else {
            current + record
        }
        putAll(context, next)
    }

    /**
     * Replaces the stored records, keeping an unreadable blob first.
     *
     * The refusal shape the add-on list and the built-catalog store use - keep
     * the blob, refuse every write - is deliberately NOT what this store does,
     * because the two features fail in opposite directions: a builder that
     * cannot save can TELL the viewer the catalog is not there, while a
     * downloads store that refuses to write cannot list a new download either,
     * so the feature would be dead until the viewer cleared app data. The raw
     * blob is preserved instead ([KEY_RECORDS_UNREADABLE]) and the store carries
     * on with what it could salvage, so nothing is thrown away silently and
     * nothing stops working.
     */
    fun putAll(context: Context, records: List<DownloadRecord>) {
        val target = prefs(context)
        preserveUnreadableBlob(target)
        runCatching {
            target.edit()
                .putString(KEY_RECORDS, json.encodeToString(serializer, records))
                .apply()
        }
    }

    /**
     * Keeps the FIRST blob this build could not read, before a write replaces it.
     *
     * One copy, and only the first: a later unreadable blob is the same data as
     * far as a repair is concerned, and overwriting the kept copy with it would
     * be the same silent loss one step removed.
     */
    private fun preserveUnreadableBlob(prefs: SharedPreferences) {
        val raw = prefs.getString(KEY_RECORDS, null)
        if (raw.isNullOrBlank()) return
        if (runCatching { json.decodeFromString(serializer, raw) }.isSuccess) return
        if (prefs.getString(KEY_RECORDS_UNREADABLE, null) != null) return
        runCatching { prefs.edit().putString(KEY_RECORDS_UNREADABLE, raw).apply() }
            .onFailure { Log.w(TAG, "could not keep the unreadable records blob: ${it.message}") }
    }

    /** Drops one record. A deleted download leaves no tombstone, see [DownloadState.DELETED]. */
    fun remove(context: Context, id: String) {
        val current = records(context)
        if (current.none { it.id == id }) return
        putAll(context, current.filterNot { it.id == id })
    }

    /** Drops every record for this profile. The bytes are the manager's business. */
    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_RECORDS).apply() }
    }

    /**
     * Whether a download may use a metered connection. OFF by default: the app's
     * standing cellular caution is that a phone on a data plan is not asked to
     * spend it without being told to, and a download is the single largest thing
     * this app can start spending it on.
     */
    fun getDownloadOverCellular(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DOWNLOAD_OVER_CELLULAR, false)

    fun setDownloadOverCellular(context: Context, enabled: Boolean) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_DOWNLOAD_OVER_CELLULAR, enabled).apply()
        }
    }

    /**
     * Whether a download is deleted once the title it holds is fully watched.
     *
     * OFF by default, and the deletion is deliberately not part of playback: the
     * player files the completion and the download goes afterwards, so a viewer
     * who finishes an episode and immediately wants it again still has it for
     * that moment (spec §4.5).
     */
    fun getDeleteAfterFinish(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DELETE_AFTER_FINISH, false)

    fun setDeleteAfterFinish(context: Context, enabled: Boolean) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_DELETE_AFTER_FINISH, enabled).apply()
        }
    }
}
