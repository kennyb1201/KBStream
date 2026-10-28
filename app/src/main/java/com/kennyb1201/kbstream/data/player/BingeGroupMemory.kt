package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * The binge identity a show was last played with: the Stremio `bingeGroup` of
 * the stream that started, and the add-on it came from.
 *
 * ## Why a stored copy is needed at all
 *
 * Every CONTINUATION already carries both - the player hands them to the next
 * episode's launch, and the auto-play path reorders that episode's sources by
 * them (see BingeGroupResolver). What had no memory was a COLD start: press Play
 * on an episode from Detail after the app was closed, or after browsing away and
 * back, and neither field exists anywhere, because nothing was handed over. The
 * sources then arrived in plain rank order, so a binge could keep the same
 * provider for ten episodes and then, on the first cold press, jump to another
 * provider's file for the same show - the "it changes quality between episodes
 * for no reason" report (audit S-F5).
 *
 * The group belongs to the SHOW, not the episode or the link, which is exactly
 * what makes it worth storing per show: it is the provider's own tag for "these
 * files continue each other".
 *
 * ## Scope and lifetime
 *
 * Per profile, like every other viewing memory in the app, and bounded to the
 * shows the viewer actually played. An entry expires with [TTL_MS]: a group from
 * a month ago describes a provider's catalogue that may have changed, and the
 * cost of being wrong - a stale tag reordering a fresh list - is worse than the
 * plain rank order it falls back to. Nothing here is synced: the sources a
 * provider offers differ per session, not per device.
 */
internal object BingeGroupMemory {

    private const val TAG = "BINGE_MEMORY"
    private const val PREFS_BASE = "kbstream_binge_groups"
    private const val KEY_ENTRIES = "binge_groups_v1"

    /** How long a remembered identity stays worth applying. */
    internal const val TTL_MS = 14L * 24 * 60 * 60 * 1000

    /** Bounded so a long-lived install cannot grow this file forever. */
    private const val MAX_ENTRIES = 40

    /** One remembered identity. */
    data class Identity(val bingeGroup: String?, val addonName: String?)

    /**
     * Records the identity a stream started with, against its show.
     *
     * A blank group AND a blank add-on is a no-op: there is nothing to continue
     * from, and writing an empty entry would only age out a useful older one.
     */
    fun remember(context: Context, showKey: String, bingeGroup: String?, addonName: String?) {
        if (showKey.isBlank()) return
        val group = bingeGroup?.takeIf { it.isNotBlank() }
        val addon = addonName?.takeIf { it.isNotBlank() }
        if (group == null && addon == null) return
        val current = read(context).toMutableMap()
        current[showKey] = Stored(group, addon, System.currentTimeMillis())
        val trimmed = current.entries
            .sortedByDescending { it.value.atMs }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value }
        runCatching {
            prefs(context).edit().putString(KEY_ENTRIES, encode(trimmed)).apply()
        }.onFailure { Log.w(TAG, "could not store a binge identity: ${it.message}") }
    }

    /** The show's remembered identity, or null when there is none (or it is stale). */
    fun recall(context: Context, showKey: String): Identity? {
        if (showKey.isBlank()) return null
        val stored = read(context)[showKey] ?: return null
        if (System.currentTimeMillis() - stored.atMs > TTL_MS) return null
        if (stored.bingeGroup.isNullOrBlank() && stored.addonName.isNullOrBlank()) return null
        return Identity(stored.bingeGroup, stored.addonName)
    }

    private data class Stored(val bingeGroup: String?, val addonName: String?, val atMs: Long)

    /**
     * The stored entries.
     *
     * Delimited text rather than JSON deliberately: the values are two opaque
     * tokens, and a hand-rolled encode is one place a quote or a backslash in a
     * provider's tag can break a decode. The wire format is
     * `atMs \u001F group \u001F addon` per line, with the two impossible-in-a-tag
     * characters as separators and a blank field meaning null.
     */
    private fun read(context: Context): Map<String, Stored> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyMap()
        return runCatching {
            raw.lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('\u001F')
                    if (parts.size < 4) return@mapNotNull null
                    val key = parts[0]
                    val at = parts[1].toLongOrNull() ?: return@mapNotNull null
                    key to Stored(
                        bingeGroup = parts[2].takeIf { it.isNotBlank() },
                        addonName = parts[3].takeIf { it.isNotBlank() },
                        atMs = at
                    )
                }
                .toMap()
        }.getOrElse {
            Log.w(TAG, "could not read the binge identities: ${it.message}")
            emptyMap()
        }
    }

    private fun encode(entries: Map<String, Stored>): String =
        entries.entries.joinToString("\n") { (key, value) ->
            listOf(
                key.replace('\u001F', ' ').replace('\n', ' '),
                value.atMs.toString(),
                value.bingeGroup.orEmpty(),
                value.addonName.orEmpty()
            ).joinToString("\u001F")
        }

    private fun prefs(context: Context) = context.getSharedPreferences(
        ProfileStorage.prefsName(context, PREFS_BASE),
        Context.MODE_PRIVATE
    )
}
