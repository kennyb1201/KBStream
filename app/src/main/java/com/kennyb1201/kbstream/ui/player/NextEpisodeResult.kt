package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared state used to communicate "next episode" navigation from
 * NativePlayerActivity back to MainActivity without relying on
 * activity results (which can be unreliable when onStop blocks).
 *
 * On Fire TV devices under memory pressure the OS frequently destroys the
 * backgrounded MainActivity while the player is up (4K HEVC playback makes
 * this much more likely). A purely in-memory handoff is lost in that case:
 * the player's result callback then runs on a RECREATED activity that has no
 * saved result, falls through to the "normal back exit" branch, and the user
 * lands on the restored screen — which feels like "Next Episode sends me
 * Home". Persisting the pending handoff to SharedPreferences makes it
 * survive process death; the pending result marker additionally lets the
 * callback recover the intent extras it saved before finishing.
 */
object NextEpisodeResult {
    private const val PREFS_NAME = "kbstream_next_episode"
    private const val KEY_PENDING = "pending_next_episode_json"

    @Volatile var pendingNextEpisode: PendingNext? = null

    data class PendingNext(
        val season: Int,
        val episode: Int,
        val title: String,
        val streamId: String,
        val runtimeMinutes: Int? = null
    )

    // -- In-memory handoff (same process) -----------------------------------

    fun consume(): PendingNext? = synchronized(this) {
        val p = pendingNextEpisode
        pendingNextEpisode = null
        p
    }

    // -- Persisted handoff (survives process death) --------------------------

    fun persist(context: Context, pending: PendingNext) {
        pendingNextEpisode = pending
        prefs(context).edit()
            .putString(KEY_PENDING, encode(pending))
            .apply()
    }

    /**
     * Called from the player-result callback. Returns the pending handoff,
     * first from memory, then from prefs (process was recreated) and clears
     * whichever store it consumed from, so no stale handoff can replay later.
     */
    fun consumePersisted(context: Context): PendingNext? = synchronized(this) {
        // Prefs must be cleared in BOTH branches: when the in-memory handoff
        // wins, the persisted copy still holds KEY_PENDING, and the next cold
        // launch's restoreIfDropped() would replay this stale episode.
        val fromMemory = consume()
        if (fromMemory != null) {
            clearPrefs(context)
            return fromMemory
        }
        decode(prefs(context).getString(KEY_PENDING, null))
            ?.also { clearPrefs(context) }
    }

    /**
     * Startup safety net: if the app was killed while a handoff was pending
     * and the result callback never ran, this resurfaces it so the user still
     * gets the next episode instead of silently landing on Home.
     */
    fun restoreIfDropped(context: Context): PendingNext? = synchronized(this) {
        val pending = decode(prefs(context).getString(KEY_PENDING, null))
        if (pending != null) {
            clearPrefs(context)
        }
        pending
    }

    fun clear(context: Context) {
        pendingNextEpisode = null
        clearPrefs(context)
    }

    private fun clearPrefs(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encode(p: PendingNext): String =
        "${p.season}|${p.episode}|${p.title}|${p.streamId}|${p.runtimeMinutes ?: -1}"

    private fun decode(raw: String?): PendingNext? {
        if (raw.isNullOrBlank()) return null
        // Titles can contain '|'; streamId never does, so split from the end.
        val parts = raw.split("|")
        if (parts.size < 5) return null
        val season = parts[0].toIntOrNull() ?: return null
        val episode = parts[1].toIntOrNull() ?: return null
        val runtime = parts.last().toIntOrNull()?.takeIf { it >= 0 }
        val streamId = parts[parts.size - 2]
        val title = parts.subList(2, parts.size - 2).joinToString("|")
        return PendingNext(
            season = season,
            episode = episode,
            title = title,
            streamId = streamId,
            runtimeMinutes = runtime
        )
    }
}
