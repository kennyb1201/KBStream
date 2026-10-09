package com.kennyb1201.kbstream.data.sports

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which channel the viewer picked for a game, so the matcher stops guessing
 * next time.
 *
 * Reported problem: the hub resolved a game to a channel, the viewer knew it
 * was the wrong one and picked another, and the correction evaporated - the
 * next game involving that team went back to the same wrong guess and had to
 * be corrected again. The matcher is a set of heuristics and it cannot learn;
 * this is where it is taught.
 *
 * Keyed by a team's [SportsTeam.favoriteKey] rather than its display name,
 * because the key is stable across the renames and network changes that make a
 * name a poor identity - and because a game has two teams, so a pick is
 * recorded under BOTH and either one can bring it back.
 *
 * Per profile and never synced, like the favorite teams beside it: a playlist
 * mapping ("my provider puts the Lightning on channel 2100") is a fact about
 * this viewer's lineup, not about the account.
 *
 * [recall] validates against the CURRENT channel list before answering. A
 * remembered id whose channel is gone - the provider renumbered, the playlist
 * was swapped - is not returned; the caller falls through to the tiers as
 * though nothing had been remembered, rather than being handed a dead id.
 */
internal object SportsChannelMemory {

    private const val TAG = "SPORTS_CHANNEL_MEMORY"

    /**
     * How many team -> channel mappings are kept.
     *
     * A viewer's playlist mapping does not expire - channel 2100 is still the
     * Lightning next season - so there is no TTL here, only a bound, so a
     * long-lived install cannot grow the file forever. 200 teams is more than
     * any fan follows; the oldest correction is dropped past it.
     */
    internal const val MAX_ENTRIES = 200

    private const val PREFS_BASE = "sports_channel_memory"
    private const val KEY_ENTRIES = "sports_channel_memory"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * teamKey -> channel id.
     *
     * The map's ITERATION ORDER is the recency order: [remember] re-inserts a
     * team at the end on every write, and [remember] evicts from the front, so
     * a plain map is the LRU list. kotlinx's decoder rebuilds a LinkedHashMap
     * in document order, so the order survives a round trip.
     */
    @Serializable
    private data class Store(val teams: Map<String, String> = emptyMap())

    /**
     * Records that the viewer chose [channelId] for a game involving [teamKey].
     *
     * Last write wins, which is the point: the freshest correction is the
     * truest one, so re-remembering a team supersedes what was there - and
     * moves it to the front of the eviction queue.
     */
    @Synchronized
    fun remember(context: Context, teamKey: String, channelId: String) {
        val key = teamKey.trim()
        val id = channelId.trim()
        if (key.isEmpty() || id.isEmpty()) return
        runCatching {
            val teams = LinkedHashMap(read(context).teams)
            teams.remove(key)
            teams[key] = id
            while (teams.size > MAX_ENTRIES) {
                val oldest = teams.keys.firstOrNull() ?: break
                teams.remove(oldest)
            }
            write(context, Store(teams))
        }.onFailure {
            Log.w(TAG, "remember failed: ${it.message}")
        }
    }

    /**
     * Records one manual pick against BOTH teams of [game]: a viewer who chose
     * a channel for "Lightning at Panthers" has told us where both are on this
     * lineup, and their next game with either team should use it.
     */
    fun rememberPick(context: Context, game: SportsGame, channelId: String) {
        remember(context, game.home.favoriteKey, channelId)
        remember(context, game.away.favoriteKey, channelId)
    }

    /**
     * The remembered channel for [teamKey], or null.
     *
     * Null when nothing is remembered, and null when the remembered id is not
     * in [channels] - a playlist that no longer carries it must not be handed a
     * dead id, and the caller falls back to the tiers.
     */
    fun recall(context: Context, teamKey: String, channels: List<IptvChannel>): IptvChannel? {
        val key = teamKey.trim()
        if (key.isEmpty() || channels.isEmpty()) return null
        val id = runCatching { read(context).teams[key] }.getOrNull() ?: return null
        return channels.firstOrNull { it.id == id }
    }

    /** Forgets every mapping: the settings panel's one row. */
    @Synchronized
    fun clear(context: Context) {
        runCatching { write(context, Store()) }.onFailure {
            Log.w(TAG, "clear failed: ${it.message}")
        }
    }

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
