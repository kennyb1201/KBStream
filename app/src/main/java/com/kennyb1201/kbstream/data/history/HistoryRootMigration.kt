package com.kennyb1201.kbstream.data.history

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.player.rerootedEpisodeStreamId
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The root a STORED row points at, or null when it names none to ask about.
 *
 * An episode row's root is the show part of its own id — the id the stream was
 * actually resolved with, which is the one the request was named by. Everything
 * else (a film, a show-level row) is filed under its parent id, which IS the
 * root for those.
 */
internal fun rootOfStoredRow(row: WatchHistoryEntity): String? =
    EpisodeMatch.seriesRootFrom(row.id)?.takeIf { it.isNotBlank() }
        ?: row.parentId.takeIf { it.isNotBlank() }

/**
 * [row] with the ids it wears re-rooted under [verifiedRoot], or the row
 * untouched when it already names that root.
 *
 * This is the WRITE half of `HomeViewModel.rerootStoredStreamId`: the rail
 * re-roots a stored id as it reads it (the fix that covers continue-watching
 * playback today), and this rewrites the row so the stored id is the canonical
 * one in the first place — the same rule, applied to the row instead of to the
 * read of it ([rerootedEpisodeStreamId], shared with the resume rows and the
 * player's random hop).
 *
 * Three ids can carry the root and all three are rewritten together:
 *  - [WatchHistoryEntity.parentId], the id the title is grouped by;
 *  - [WatchHistoryEntity.id], which for an episode row is the episode stream id
 *    (and for a film or a show-level row is the parent id itself);
 *  - [WatchHistoryEntity.episodeStreamId], when the row carries one.
 *
 * Only the root moves; the `season:episode` suffix stays exactly as filed,
 * because it names the FILE that was played (see [rerootedEpisodeStreamId]).
 * Every other field — the position, the completion, `updatedAt` above all — is
 * left alone: a migration is not a write by the viewer, and stamping the row
 * would let it out-rank a sibling device's genuinely newer write.
 *
 * A blank [verifiedRoot] answers the row as it stands: there is nothing to
 * verify the stored root against, which is every unresolved case (no TMDB key,
 * an offline device, a title TMDB has no record for) and is the same answer
 * `streamRootIdFor` gives its own callers.
 */
internal fun canonicalHistoryRow(
    row: WatchHistoryEntity,
    verifiedRoot: String
): WatchHistoryEntity {
    val root = verifiedRoot.trim()
    if (root.isEmpty()) return row

    val namesAnEpisode = EpisodeMatch.seriesRootFrom(row.id) != null
    val id = if (namesAnEpisode) rerootedEpisodeStreamId(row.id, root) else root
    val parentId = if (root != row.parentId) root else row.parentId
    val episodeStreamId = row.episodeStreamId?.let { stored ->
        if (EpisodeMatch.seriesRootFrom(stored) != null) {
            rerootedEpisodeStreamId(stored, root)
        } else {
            stored
        }
    }

    if (id == row.id && parentId == row.parentId && episodeStreamId == row.episodeStreamId) {
        return row
    }
    return row.copy(id = id, parentId = parentId, episodeStreamId = episodeStreamId)
}

/**
 * What one profile's root migration would do to its rows: the rows it writes,
 * the ids it deletes, and the ids whose CLOUD copies have to be tombstoned.
 *
 * [deletedIds] and [tombstonedIds] are not the same list, and the difference is
 * the whole reason a rename is safe to do at all: an id that another row is
 * written INTO must be deleted locally (the old row is gone) but must NOT be
 * tombstoned, because the cloud row under it is the one being replaced by this
 * device's own upload — a tombstone there would delete the row that is staying.
 * Every other departing id is abandoned and gets a tombstone, without which the
 * next pull hands the stale row straight back (see
 * [SupabaseSync.deleteHistoryRows]).
 */
internal data class HistoryRootMigrationPlan(
    val writes: List<WatchHistoryEntity>,
    val deletedIds: List<String>,
    val tombstonedIds: List<String>,
    val unmigrated: Int
) {
    /** True when the profile needs no work at all, which is the normal case. */
    val isEmpty: Boolean get() = deletedIds.isEmpty() && writes.isEmpty()
}

/**
 * The plan for one profile's stored rows, with the root of each row supplied by
 * [resolvedRoot] — the resolver is the caller's, so this stays pure and the
 * interesting part (which ids survive a collision, and what leaves) is pinned
 * by test rather than by a database.
 *
 * Two rows can want the SAME id after re-rooting: a title's stale row and the
 * canonical row a later session already filed for the same episode. That is one
 * episode, so it is one row, and the NEWER stamp wins — the same rule every
 * other history merge in the app uses. The loser leaves locally (and, if its id
 * is not the one being written, leaves the cloud with a tombstone).
 *
 * [HistoryRootMigrationPlan.unmigrated] counts the rows [resolvedRoot] could not
 * answer for. They are left exactly as they were — the read side re-roots them
 * anyway — and the count is what keeps the one-shot from retiring itself while
 * rows are still waiting for an answer.
 */
internal fun planHistoryRootMigration(
    rows: List<WatchHistoryEntity>,
    resolvedRoot: (WatchHistoryEntity) -> String?
): HistoryRootMigrationPlan {
    val winners = LinkedHashMap<String, Pair<String, WatchHistoryEntity>>()
    val departing = LinkedHashSet<String>()
    var unmigrated = 0
    var changed = false

    // Oldest first, so a row only ever replaces an older one and the winner of
    // a collision is decided by `updatedAt` alone — never by table order.
    for (row in rows.sortedBy { it.updatedAt }) {
        val root = resolvedRoot(row)
        if (root == null) {
            unmigrated++
            continue
        }
        val canonical = canonicalHistoryRow(row, root)
        val previous = winners[canonical.id]
        val wins = previous == null || canonical.updatedAt >= previous.second.updatedAt
        if (wins) {
            winners[canonical.id] = row.id to canonical
            previous?.let { departing += it.first }
        } else {
            departing += row.id
        }
        if (canonical.id != row.id) departing += row.id
        if (canonical != row) changed = true
    }

    if (!changed) {
        return HistoryRootMigrationPlan(emptyList(), emptyList(), emptyList(), unmigrated)
    }

    val writes = winners.values
        .map { it.second }
        .filter { canonical -> rows.firstOrNull { it.id == canonical.id } != canonical }
    return HistoryRootMigrationPlan(
        writes = writes,
        deletedIds = departing.toList(),
        tombstonedIds = departing.filterNot { it in winners.keys },
        unmigrated = unmigrated
    )
}

/**
 * The one-shot rewrite of stored continue-watching rows onto the roots the app
 * names show ids by today.
 *
 * Field case it exists for: a series catalog hands out `tt3121722` for a show
 * whose TMDB record says `tt4873036` (see
 * [com.kennyb1201.kbstream.data.tmdb.preferredStreamRootId]). Everything the app
 * builds names ids with TMDB's answer now, and every READ of an old row
 * re-roots it, so such a row plays correctly — but the row itself is still
 * filed under an id no other surface speaks, and the sync layer keeps handing
 * that stale id to the account's other devices. This is the repair of the rows
 * themselves.
 *
 * It runs once per device, on the startup scope, gated by its own prefs flag
 * and NOT by the Room schema version: what is being fixed is data, not a table,
 * and the ids involved cannot be resolved in SQL.
 *
 * Every profile's history database is repaired, each one through its own
 * by-name handle ([WatchHistoryDatabase.openForProfile]) rather than the
 * active-profile singleton, so a profile switch mid-pass cannot retire the
 * connection under it and every profile is covered even though only one is on
 * screen.
 *
 * Deliberately NOT covered:
 *  - the watched-override and watched-marker stores, which are keyed by parent
 *    id but read through id TWINS by design (see
 *    `localHistoryParentIdsForShow`), so a re-rooted row is still found there;
 *  - a session writing progress WHILE the pass runs: it writes under the id it
 *    was launched with, which a re-root then leaves behind as a second row for
 *    the same episode. The read side re-roots both, so the card is right, and
 *    "Remove from Continue Watching" clears it — the alternative (locking the
 *    table across a pass that resolves roots over the network) is worse than
 *    the residue;
 *  - a device whose TMDB lookups cannot answer. Those rows are left alone (see
 *    [HistoryRootMigrationPlan.unmigrated]) and the pass is NOT retired, so the
 *    next launch tries again.
 */
object HistoryRootMigration {

    private const val TAG = "HISTORY_ROOT_MIGRATION"
    private const val PREFS = "kbstream_history_root_migration"
    private const val KEY_DONE = "root_migration_v1"

    /**
     * Repairs every profile's rows if this device has not done it yet. Safe to
     * call on every launch: the gate is one prefs read once it is done.
     *
     * @return true when the pass ran and retired itself.
     */
    suspend fun runOnce(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (isDone(appContext)) return@withContext false

        val profileIds = knownProfileIds(appContext)
        // No profiles yet (the blob has not been written, a first launch): the
        // ids belong to a device state that does not exist, so do not retire
        // the pass on it - the next launch asks again.
        if (profileIds.isEmpty()) return@withContext false

        val repository = TmdbRepository.getInstance(appContext)
        val tmdbIds = HashMap<String, Int?>()
        val roots = HashMap<String, String?>()

        // One lookup per (type, root), not per row: a season's worth of
        // episodes is one show, and the TMDB calls behind this are cached
        // memory-and-disk, so a history of a few dozen shows costs a few dozen
        // cached reads.
        suspend fun rootFor(row: WatchHistoryEntity): String? {
            val route = rootOfStoredRow(row) ?: return null
            val type = row.type.ifBlank { "series" }
            val key = "$type:$route"
            roots[key]?.let { return it }
            if (!roots.containsKey(key)) {
                val tmdbId = if (tmdbIds.containsKey(key)) {
                    tmdbIds[key]
                } else {
                    // The row's own name is what recovers a catalog id TMDB
                    // only knows under the other type (PAW Patrol's tt3121722
                    // is the Dino Movie's). Without it this lookup is the same
                    // unresolvable one that filed the row wrong, which is why
                    // these rows used to sit in `unmigrated` forever.
                    runCatchingCancellable {
                        repository.fetchEnrichedMetaCached(
                            route,
                            type,
                            fallbackName = row.name
                                .takeIf { it.isNotBlank() && it.trim() != route.trim() }
                        )?.id
                    }.getOrNull()
                        ?.also { tmdbIds[key] = it }
                }
                // No TMDB record for this id: nothing can say what the verified
                // root is, so the row keeps the id it has.
                roots[key] = tmdbId?.takeIf { it > 0 }?.let { id ->
                    runCatchingCancellable {
                        repository.streamRootIdFor(
                            routeId = route,
                            tmdbId = id,
                            type = type
                        )
                    }.getOrNull()
                }
            }
            return roots[key]
        }

        var migrated = 0
        var unmigrated = 0
        for (profileId in profileIds) {
            val db = runCatchingCancellable {
                WatchHistoryDatabase.openForProfile(appContext, profileId)
            }.getOrNull()
            if (db == null) {
                unmigrated++
                continue
            }
            try {
                val dao = db.watchHistoryDao()
                val rows = dao.getAllRows()
                if (rows.isEmpty()) continue
                // Roots first, then the plan: the resolver suspends (it is
                // TMDB-backed), and the plan is a pure function over the rows
                // AND their roots, so it takes the answers as a map rather than
                // a lambda it would have to call.
                val rootsByRowId = rows.associate { row -> row.id to rootFor(row) }
                val plan = planHistoryRootMigration(rows) { row -> rootsByRowId[row.id] }
                unmigrated += plan.unmigrated
                if (plan.isEmpty) continue

                // Local first, then the cloud: the tombstones are what stop the
                // next pull from handing the old ids back, and they are only
                // meaningful once the local rows are canonical.
                dao.applyRootMigration(plan.deletedIds, plan.writes)
                if (plan.tombstonedIds.isNotEmpty()) {
                    SupabaseSync.deleteHistoryRows(plan.tombstonedIds, profileId)
                }
                plan.writes.forEach { row -> SupabaseSync.enqueueHistory(row, profileId) }
                migrated += plan.writes.size
                Log.i(
                    TAG,
                    "profile $profileId: ${plan.writes.size} row(s) re-rooted, " +
                        "${plan.deletedIds.size} deleted, ${plan.tombstonedIds.size} tombstoned"
                )
            } catch (t: Throwable) {
                unmigrated++
                Log.w(TAG, "profile $profileId: migration failed", t)
            } finally {
                runCatching { db.close() }
            }
        }

        if (unmigrated > 0) {
            // Something could not be verified. The rows are untouched and the
            // read side re-roots them as always, so this is not a failure to
            // report - it is a reason to ask again next launch.
            Log.i(TAG, "$migrated row(s) re-rooted, $unmigrated left for a later pass")
            return@withContext false
        }
        markDone(appContext)
        Log.i(TAG, "done: $migrated row(s) re-rooted")
        return@withContext true
    }

    /**
     * The profiles whose history has to be repaired: the app's own list plus
     * the active id, which is resolvable before `ProfileManager.init` has run
     * (a startup pass can race it) and would otherwise be missed.
     */
    private fun knownProfileIds(context: Context): List<String> =
        (ProfileManager.profileIds(context) +
            listOfNotNull(ProfileManager.resolveActiveId(context)))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun isDone(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_DONE, false) }.getOrDefault(false)

    private fun markDone(context: Context) {
        runCatching { prefs(context).edit().putBoolean(KEY_DONE, true).apply() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
