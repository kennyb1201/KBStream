package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.settings.prefs.Prefs

/**
 * The source the viewer picked with their own hands, per episode (spec
 * 2026-11-15, "remember the manually picked source").
 *
 * ## The report
 *
 * An episode's link was bad video quality, so the viewer switched it in the
 * player. A few minutes later they backed out to Detail and pressed Play again,
 * and the app started the bad link once more - because a manual switch was
 * per-playback only: [com.kennyb1201.kbstream.ui.streams]'s `selectSource` hands
 * the row to the player and writes nothing, and the auto-play pick is a pure
 * function of the fresh source list. The same list yields the same top pick, and
 * the ranker puts a cached debrid copy above a better-looking HTTP link by
 * design (see [com.kennyb1201.kbstream.domain.streamengine.StreamRanker]), so
 * nothing in the ranking could ever have reached the link the viewer chose.
 *
 * ## What is stored
 *
 * One identity per EPISODE: the show's id plus season and episode (a movie is
 * just its id). A pick for one episode must never answer for the next - the
 * binge advance resolves the next episode fresh and only consults this for its
 * own key.
 *
 * The identity is the row's own address, not a position, and not a copy of the
 * link that was played:
 *
 *  - a row with a URL keeps its URL - which covers direct HTTP rows and
 *    `TorBoxCloudPicker` rows alike, since a cloud row's URL is the
 *    `kbstream-torbox-cloud://torrentId/fileId` reference it is drawn from;
 *  - a hash row (no URL) keeps its infohash and file index.
 *
 * What is stored is the row the viewer PRESSED, never the resolved link a hash
 * row turns into: the fresh resolve returns the row, and matching happens
 * there, so the memory only ever re-uses a link that this session's resolve
 * actually offered. A remembered link the add-on no longer returns is not
 * invented and not played - the ranker answers instead.
 *
 * ## Scope and lifetime
 *
 * Per profile, like every other viewing memory (the audio track signature, the
 * binge identity), and persisted: the viewer's pick has to survive backing out
 * to Detail AND a force-stop, which is the whole point of the report.
 *
 * [TTL_MS] bounds how long an old pick is worth applying; the fresh-list match
 * already makes a stale entry harmless, this only stops the file from growing
 * meanings nobody remembers setting.
 */
internal object SourcePickMemory {

    private const val TAG = "SOURCE_MEMORY"
    private const val PREFS_BASE = "kbstream_source_picks"
    private const val KEY_ENTRIES = "source_picks_v1"

    /** How long a remembered pick is worth applying. */
    internal const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** Bounded: a long binge is 20 episodes, and an install lasts. */
    private const val MAX_ENTRIES = 400

    /** One remembered pick: the row's identity, and when it was chosen. */
    private data class Stored(val identity: String, val atMs: Long)

    /**
     * The episode a pick belongs to, or null when the show is unknown (a
     * channel, a widget launch with no parent) - in which case nothing is
     * stored, because a key that cannot name its episode would answer for
     * another one. The rules live in [SourcePickKey].
     */
    fun episodeKey(parentId: String?, season: Int?, episode: Int?): String? =
        SourcePickKey.episodeKey(parentId, season, episode)

    /**
     * The address that identifies [stream] across two resolves of the same
     * episode, or null for a row that has neither a URL nor a hash.
     */
    fun identityOf(stream: Stream): String? =
        SourcePickKey.identityOf(stream.url, stream.infoHash, stream.fileIdx)

    /**
     * Remembers [stream] as the viewer's pick for this episode.
     *
     * Called only from an explicit row press - never from the auto-play pick,
     * never from the error walk (`tryNextSource`), which are not quality
     * choices. Overwrites whatever was there: the newest press is the pick.
     */
    fun remember(
        context: Context,
        parentId: String?,
        season: Int?,
        episode: Int?,
        stream: Stream
    ) {
        val key = episodeKey(parentId, season, episode) ?: return
        val identity = identityOf(stream) ?: return
        val current = read(context).toMutableMap()
        current[key] = Stored(identity, System.currentTimeMillis())
        val trimmed = current.entries
            .sortedByDescending { it.value.atMs }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value }
        cachedFor = prefsName(context)
        cached = trimmed
        runCatching {
            prefs(context).edit().putString(KEY_ENTRIES, encode(trimmed)).apply()
        }.onFailure { Log.w(TAG, "could not store a source pick: ${it.message}") }
    }

    /**
     * The viewer's own pick for this episode, taken from [fresh] - the rows the
     * current resolve actually returned - or null when there is none, when it
     * has aged out, or when the fresh list no longer offers it.
     *
     * The returned row is the FRESH one, not a copy of the stored address, so
     * its labels, headers and badges are this session's.
     */
    fun recall(
        context: Context,
        parentId: String?,
        season: Int?,
        episode: Int?,
        fresh: List<Stream>
    ): Stream? {
        val key = episodeKey(parentId, season, episode) ?: return null
        if (fresh.isEmpty()) return null
        val stored = read(context)[key] ?: return null
        if (System.currentTimeMillis() - stored.atMs > TTL_MS) return null

        fresh.firstOrNull { identityOf(it) == stored.identity }?.let { return it }

        // A direct link re-signed by its host: same address, new query string.
        // Matched only when EXACTLY one fresh row is that address, because a
        // looser match is how one file's pick would play another's.
        return fresh
            .filter { SourcePickKey.sameAddress(it.url, stored.identity) }
            .singleOrNull()
    }

    /**
     * Per-process copy, so an auto-play pick does not re-read the prefs file.
     *
     * Keyed by the prefs FILE, not just held: the store is per profile, and a
     * cache that ignored that would answer one profile's press with another's
     * pick.
     */
    @Volatile
    private var cachedFor: String? = null

    @Volatile
    private var cached: Map<String, Stored>? = null

    private fun read(context: Context): Map<String, Stored> {
        val name = prefsName(context)
        cached?.let { if (cachedFor == name) return it }
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyMap()
        val parsed = runCatching {
            raw.lineSequence()
                .mapNotNull { line ->
                    val parts = line.split('\u001F')
                    if (parts.size < 3) return@mapNotNull null
                    val at = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val key = parts[1]
                    val identity = parts[2]
                    if (key.isBlank() || identity.isBlank()) return@mapNotNull null
                    key to Stored(identity, at)
                }
                .toMap()
        }.getOrElse {
            Log.w(TAG, "could not read the source picks: ${it.message}")
            emptyMap()
        }
        cachedFor = name
        cached = parsed
        return parsed
    }

    private fun encode(entries: Map<String, Stored>): String =
        entries.entries.joinToString("\n") { (key, value) ->
            listOf(
                value.atMs.toString(),
                sanitize(key),
                value.identity.replace('\u001F', ' ').replace('\n', ' ')
            ).joinToString("\u001F")
        }

    private fun sanitize(value: String): String =
        value.replace('\u001F', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun prefsName(context: Context) = ProfileStorage.prefsName(context, PREFS_BASE)

    private fun prefs(context: Context) = Prefs.file(context, prefsName(context))
}
