package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.domain.streamengine.SourceAddonPreference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which addon last worked - and which last failed - for a given title.
 *
 * Reported problem: a show whose every AIOStreams link was dead played fine
 * from another addon, but nothing remembered that, so the next episode put the
 * same unplayable links back at the top of the list and the picker's All tab
 * kept showing them first. A bingeGroup cannot cover it: it carries "same link
 * as last time", and an addon that would not open has no link to continue.
 *
 * The record is per TITLE, because that is the question being asked: an addon
 * whose links are dead for one show may serve the next one perfectly - a bad
 * release, a debrid cache that does not hold this title, a scraper that cannot
 * find this season. Keeping it per addon (or per journey) would put a show's
 * bad luck on every other show.
 *
 * Per profile and never synced, like [PlayedLinkCache]: a debrid cache is the
 * viewer's own, so what an addon can serve is not a fact that transfers.
 *
 * Entries EXPIRE rather than stick: a dead link is a statement about today (a
 * cache refills, a hoster recovers, an addon is reconfigured), so a month-old
 * failure must not keep demoting an addon that now works. The window is long
 * enough to cover a binge - the case the report is about - and short enough
 * that a stale verdict cannot outlive its cause permanently.
 *
 * A record only ever REORDERS (see [SourceAddonPreference]): nothing is hidden,
 * nothing is deleted from the picker, and a title with no record resolves
 * exactly as it did before.
 */
internal object SourceAddonMemory {

    private const val TAG = "SOURCE_ADDON_MEMORY"

    /**
     * How long an outcome counts for. A month covers a binge of any length -
     * weekly episodes included - and still lets an addon that was broken come
     * back on its own.
     */
    internal const val TTL_MS = 30L * 24 * 60 * 60_000

    /** Bounded so a long-lived install cannot grow this file forever. */
    private const val MAX_SHOWS = 200

    /**
     * Addons worth remembering for one show. A title reachable from a dozen
     * addons is already unusual; past this, the oldest outcome is dropped.
     */
    private const val MAX_ADDONS_PER_SHOW = 12

    private const val PREFS_BASE = "kbstream_source_addons"
    private const val KEY_ENTRIES = "source_addons_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Outcome(
        val worked: Boolean,
        val atMs: Long
    )

    @Serializable
    private data class ShowEntry(
        val addons: Map<String, Outcome> = emptyMap(),
        /** Last touched, for the eviction order. */
        val atMs: Long = 0L
    )

    @Serializable
    private data class Store(val shows: Map<String, ShowEntry> = emptyMap())

    /** An addon opened a file for this title: it leads the next list. */
    fun rememberWorked(context: Context, showKey: String, addonName: String?) =
        record(context, showKey, addonName, worked = true)

    /** An addon's link would not open here: it goes behind everything else. */
    fun rememberFailed(context: Context, showKey: String, addonName: String?) =
        record(context, showKey, addonName, worked = false)

    /**
     * What this title's addons last did, or empty when nothing is known - in
     * which case callers leave the source order alone.
     */
    fun outcomes(context: Context, showKey: String): SourceAddonPreference.Outcomes {
        val key = SourceAddonPreference.showKeyOf(showKey)
        if (key.isBlank()) return SourceAddonPreference.Outcomes()
        val entry = runCatching { read(context).shows[key] }.getOrNull()
            ?: return SourceAddonPreference.Outcomes()
        val fresh = entry.addons.filterValues { isFresh(it.atMs) }
        return SourceAddonPreference.Outcomes(
            worked = fresh.filterValues { it.worked }.keys.toSet(),
            failed = fresh.filterValues { !it.worked }.keys.toSet()
        )
    }

    /** Drops this title's record: the per-title "stop remembering this". */
    fun forget(context: Context, showKey: String) {
        val key = SourceAddonPreference.showKeyOf(showKey)
        if (key.isBlank()) return
        runCatching {
            val shows = LinkedHashMap(read(context).shows)
            if (shows.remove(key) != null) write(context, Store(shows))
        }.onFailure {
            Log.w(TAG, "forget failed: ${it.message}")
        }
    }

    private fun record(context: Context, showKey: String, addonName: String?, worked: Boolean) {
        val key = SourceAddonPreference.showKeyOf(showKey)
        val addon = SourceAddonPreference.normalize(addonName)
        // A blank key or a nameless label has nothing to record. A label the
        // player could not resolve to an installed addon still records - it
        // costs one slot here and only ever fails to MATCH the picker's own
        // naming of an addon, which leaves that addon in the unknown tier
        // exactly as it was. Nothing is ever demoted on a name that cannot
        // refer to a stream's addon.
        if (key.isBlank() || addon.isEmpty()) return

        runCatching {
            val now = System.currentTimeMillis()
            val shows = LinkedHashMap(read(context).shows)
            val entry = shows[key] ?: ShowEntry()
            val addons = LinkedHashMap(entry.addons)
            // Last outcome wins, so an addon that failed and later played is
            // remembered as working - the store holds one fact per addon, not
            // two contradictory ones.
            addons[addon] = Outcome(worked = worked, atMs = now)
            if (addons.size > MAX_ADDONS_PER_SHOW) {
                addons.entries
                    .sortedBy { it.value.atMs }
                    .take(addons.size - MAX_ADDONS_PER_SHOW)
                    .forEach { addons.remove(it.key) }
            }
            shows[key] = ShowEntry(addons = addons, atMs = now)

            if (shows.size > MAX_SHOWS) {
                shows.entries
                    .sortedBy { it.value.atMs }
                    .take(shows.size - MAX_SHOWS)
                    .forEach { shows.remove(it.key) }
            }
            write(context, Store(shows))
        }.onFailure {
            Log.w(TAG, "record failed: ${it.message}")
        }
    }

    private fun isFresh(atMs: Long): Boolean = System.currentTimeMillis() - atMs <= TTL_MS

    private fun read(context: Context): Store {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return Store()
        return runCatching { json.decodeFromString(Store.serializer(), raw) }
            .getOrElse {
                // A corrupt blob must never change what plays: drop and restart.
                Log.w(TAG, "store unreadable; resetting (${it.message})")
                Store()
            }
    }

    private fun write(context: Context, store: Store) {
        // apply(), never commit(): this runs as playback starts and ends.
        prefs(context)
            .edit()
            .putString(KEY_ENTRIES, json.encodeToString(Store.serializer(), store))
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            ProfileStorage.prefsName(context.applicationContext, PREFS_BASE),
            Context.MODE_PRIVATE
        )
}
