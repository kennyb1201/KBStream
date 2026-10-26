package com.kennyb1201.kbstream.data.download

import android.content.Context
import android.content.SharedPreferences
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
    internal const val KEY_DOWNLOAD_OVER_CELLULAR = "download_over_cellular"
    internal const val KEY_DELETE_AFTER_FINISH = "delete_after_finish"

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
        val decoded = runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse { return emptyList() }
        return decoded.sortedByDescending { it.addedAt }
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

    fun putAll(context: Context, records: List<DownloadRecord>) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_RECORDS, json.encodeToString(serializer, records))
                .apply()
        }
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
