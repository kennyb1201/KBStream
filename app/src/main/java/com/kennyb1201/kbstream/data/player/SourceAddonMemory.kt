package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.domain.streamengine.SourceAddonPreference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which addon last worked, which kept stalling, and which last failed - for a
 * given title.
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
 * The third outcome is the source that opens and cannot keep up. V1 recorded
 * only open-failures, so a source that plays and stalls was demoted for the
 * session (the player's own rebuffer downshift moved the viewer off it) and
 * then forgotten - the next episode started from the same bad source again.
 * A stall is therefore counted per addon here and the count is what decides the
 * slow tier (see [SLOW_STALL_THRESHOLD]); one good session clears it, exactly
 * as one good session already supersedes a failure.
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

    /**
     * Fresh stall-downshifts that make an addon "slow" for one title.
     *
     * Two, not one: a single downshift is an evening (a CDN hiccup, a household
     * saturating the line), and the in-session downshift has already moved the
     * viewer off that source - this record is only about the order the NEXT
     * episode starts from, which is too much to hand to one bad night. Two
     * separate sessions that both failed to keep up is the pattern.
     */
    internal const val SLOW_STALL_THRESHOLD = 2

    private const val PREFS_BASE = "kbstream_source_addons"
    private const val KEY_ENTRIES = "source_addons_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * One fact per addon, and only ever the latest one.
     *
     * [worked] is deliberately three-valued: `null` means this addon has only
     * ever STALLED here, which is neither "it opened a file" nor "its link
     * would not open" - and reading an unset boolean as a failure is exactly
     * how a source that plays perfectly well would be sent to the back of the
     * list.
     */
    @Serializable
    private data class Outcome(
        val worked: Boolean? = null,
        val atMs: Long = 0L,
        /** Stall-downshifts recorded since this addon's last success. */
        val stalls: Int = 0,
        /** When the most recent stall landed, 0 when it never has. */
        val lastStallAtMs: Long = 0L,
        /**
         * When the OPEN verdict ([worked]) landed - deliberately separate from
         * [atMs], which a stall also refreshes. A stall is later evidence that
         * the link opens, so it must neither re-arm a failure's expiry by
         * bumping [atMs] nor be read as still-failed; keeping the verdict's own
         * timestamp lets [outcomes] age it out and lets a later stall supersede
         * it. 0 on entries written before this field existed; the accessor
         * falls back to [atMs] for those.
         */
        val openAtMs: Long = 0L
    )

    @Serializable
    private data class ShowEntry(
        val addons: Map<String, Outcome> = emptyMap(),
        /** Last touched, for the eviction order. */
        val atMs: Long = 0L
    )

    @Serializable
    private data class Store(val shows: Map<String, ShowEntry> = emptyMap())

    /**
     * An addon opened a file for this title: it leads the next list.
     *
     * A success also clears this addon's stall count - one good session forgives
     * the stalls that came before it, the same way it supersedes a failure. The
     * record holds ONE verdict per addon, and "it is playing fine now" is it.
     */
    fun rememberWorked(context: Context, showKey: String, addonName: String?) =
        update(context, showKey, addonName) { _ ->
            val now = System.currentTimeMillis()
            Outcome(worked = true, atMs = now, openAtMs = now)
        }

    /** An addon's link would not open here: it goes behind everything else. */
    fun rememberFailed(context: Context, showKey: String, addonName: String?) =
        update(context, showKey, addonName) { _ ->
            // Supersedes a stall count as well as a success: a link that will
            // not open at all is the worse fact about the same addon, and this
            // store keeps one.
            val now = System.currentTimeMillis()
            Outcome(worked = false, atMs = now, openAtMs = now)
        }

    /**
     * An addon opened here and could not keep up: the player's rebuffer
     * downshift moved the viewer off it, and this is that verdict, kept for the
     * next session.
     *
     * Reached only where the downshift actually starts a switch, so a stall the
     * session chose not to act on (no rung left, a seek's own buffering) is not
     * counted. One good session clears the count again ([rememberWorked]).
     */
    fun rememberStalled(context: Context, showKey: String, addonName: String?) =
        update(context, showKey, addonName) { previous ->
            val now = System.currentTimeMillis()
            Outcome(
                // Whatever this addon's open outcome was stays as it was: a
                // stall says the source was too slow, not that it failed to
                // open, and the two are separate tiers. Its TIME is carried
                // over rather than refreshed, though, so the failure is not
                // re-armed by a stall and can be superseded below.
                worked = previous?.worked,
                atMs = now,
                stalls = (previous?.stalls ?: 0) + 1,
                lastStallAtMs = now,
                openAtMs = previous?.let { openVerdictAtMs(it) } ?: 0L
            )
        }

    /**
     * What this title's addons last did, or empty when nothing is known - in
     * which case callers leave the source order alone.
     */
    fun outcomes(context: Context, showKey: String): SourceAddonPreference.Outcomes {
        val key = SourceAddonPreference.showKeyOf(showKey)
        if (key.isBlank()) return SourceAddonPreference.Outcomes()
        val entry = runCatching { read(context).shows[key] }.getOrNull()
            ?: return SourceAddonPreference.Outcomes()
        // The open verdict (worked/failed) ages off its OWN timestamp, so a
        // stall cannot re-arm a 29-day-old failure. A stall that landed AFTER
        // the failure is later evidence that the link opens, so it supersedes
        // the failure instead of leaving the addon reported as both failed and
        // slow - where the tier rule then picked failed.
        val freshOpen = entry.addons.filterValues { isFresh(openVerdictAtMs(it)) }
        return SourceAddonPreference.Outcomes(
            worked = freshOpen.filterValues { it.worked == true }.keys.toSet(),
            failed = freshOpen
                .filterValues { it.worked == false && !stallSupersedesOpen(it) }
                .keys
                .toSet(),
            slow = entry.addons
                .filterValues { freshStallCount(it) >= SLOW_STALL_THRESHOLD }
                .keys
                .toSet()
        )
    }

    /** Drops this title's record: the per-title "stop remembering this". */
    @Synchronized
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

    @Synchronized
    private fun update(
        context: Context,
        showKey: String,
        addonName: String?,
        // The entry this addon has now, or null when it has none: every write
        // is expressed as "what the new fact makes of the old one", so the
        // stall count can build on the outcome that is already there.
        next: (Outcome?) -> Outcome
    ) {
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
            addons[addon] = next(addons[addon])
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

    /**
     * This addon's stall-downshifts that still count: the count itself when the
     * most recent one is inside the window, zero otherwise.
     *
     * Keyed off the newest stall, like the outcome is keyed off [Outcome.atMs]:
     * a month with no stall since means the source has had a month of chances,
     * which is the same reason a failure expires rather than sticking.
     */
    private fun freshStallCount(outcome: Outcome): Int =
        if (outcome.lastStallAtMs > 0L && isFresh(outcome.lastStallAtMs)) outcome.stalls else 0

    /**
     * When this addon's open verdict landed. [Outcome.openAtMs] where set; an
     * entry written before that field existed falls back to [Outcome.atMs],
     * which for those entries was the verdict's own time (stalls only bumped it
     * afterwards, and the first stall after this change carries the value over).
     */
    private fun openVerdictAtMs(outcome: Outcome): Long =
        if (outcome.openAtMs > 0L) outcome.openAtMs else outcome.atMs

    /**
     * True when a stall landed after the open verdict: the link demonstrably
     * opened (the player moved off it for slowness, not a failure to open), so
     * the failure is stale and must not keep the addon in the failed tier.
     */
    private fun stallSupersedesOpen(outcome: Outcome): Boolean =
        outcome.lastStallAtMs > 0L &&
            outcome.lastStallAtMs > openVerdictAtMs(outcome)

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
