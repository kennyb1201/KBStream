package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The subtitle fetched for the NEXT episode while the current one's credits
 * were still rolling.
 *
 * The auto-fetch is the missing press nobody asks for twice, but it runs where
 * the viewer is waiting: episode N+1 opens, finds no usable track a few seconds
 * in, searches OpenSubtitles, downloads a file and attaches it - and the attach
 * is a player rebuild, which is the picture/audio blink. None of that work has
 * to happen against a wall clock. The episode's identity is known from the
 * moment episode N's end panel comes up, so the search and the download can
 * happen during the credits and this episode's auto-fetch starts with the file
 * already on disk.
 *
 * Keyed on exactly the facts the auto-fetch searches with - title, season,
 * episode, language - so a hit can only ever answer for the episode it was
 * fetched for. A subtitle is text, so unlike a debrid link it does not expire
 * on a clock, but the entry still ages out ([TTL_MS]): left alone, a show's
 * whole season would accumulate in the subtitle cache.
 *
 * Profile-scoped and never synced: the entry names a file in THIS device's
 * cache, which means nothing on another TV.
 *
 * A hit only ever SKIPS the search and the download. Every guard the auto-fetch
 * itself runs (a preferred language, an OpenSubtitles key, auto-fetch on, and
 * no usable track already present) is still decided by the auto-fetch, so the
 * prefetch cannot attach anything that fetch would have refused.
 */
internal object SubtitlePrefetch {

    private const val TAG = "SUBTITLE_PREFETCH"
    private const val PREFS_BASE = "kbstream_subtitle_prefetch"
    private const val KEY_ENTRIES = "subtitle_prefetch_v1"

    /**
     * A week. The credits are the prompt, not a deadline: a viewer who watches
     * the next episode days later should still not pay for it twice, and the
     * file it points at lives in the subtitle cache the disk sweep owns.
     */
    internal const val TTL_MS = 7L * 24 * 60 * 60 * 1000L

    /**
     * Bounded: one show's worth of next episodes, plus a little room. Internal
     * so the cap itself is unit tested rather than restated in the test.
     */
    internal const val MAX_ENTRIES = 12

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Entry(
        val uri: String,
        val fileName: String,
        val atMs: Long = 0L
    )

    @Serializable
    private data class Store(val entries: Map<String, Entry> = emptyMap())

    /** A prefetched track that is still on disk. */
    data class Hit(val uri: Uri, val fileName: String)

    /**
     * The identity a prefetch is keyed on. Season and episode only take part
     * when the title HAS them: a movie and an episode 0 are both "no numbers",
     * and must not share a key. Title case and language case are folded, since
     * neither is presented consistently by the callers.
     */
    internal fun key(
        title: String,
        season: Int?,
        episode: Int?,
        language: String
    ): String = listOf(
        title.trim().lowercase(),
        season?.toString().orEmpty(),
        episode?.toString().orEmpty(),
        language.trim().lowercase()
    ).joinToString("|")

    /**
     * Records a downloaded subtitle as this episode's prefetch. A blank key, a
     * blank URI or a blank name is a no-op: there would be nothing to attach.
     */
    fun remember(
        context: Context,
        title: String,
        season: Int?,
        episode: Int?,
        language: String,
        fileName: String,
        uri: Uri
    ) {
        val entryKey = key(title, season, episode, language)
        if (title.isBlank() || fileName.isBlank() || uri.toString().isBlank()) return
        runCatching {
            val entries = LinkedHashMap(read(context).entries)
            entries[entryKey] = Entry(
                uri = uri.toString(),
                fileName = fileName,
                atMs = System.currentTimeMillis()
            )

            // Evict the oldest once past the cap.
            if (entries.size > MAX_ENTRIES) {
                entries.entries
                    .sortedBy { it.value.atMs }
                    .take(entries.size - MAX_ENTRIES)
                    .forEach { entries.remove(it.key) }
            }

            write(context, Store(entries))
        }.onFailure {
            Log.w(TAG, "remember prefetched subtitle failed: ${it.message}")
        }
    }

    /**
     * The prefetched subtitle for this episode, or null when there is nothing
     * to reuse: not stored, past [TTL_MS], or the file the entry names is gone
     * (the disk sweep can reclaim the subtitle cache).
     *
     * Reading does NOT refresh the timestamp - the window runs from the fetch,
     * so an entry cannot be kept alive forever by reading it.
     */
    fun get(
        context: Context,
        title: String,
        season: Int?,
        episode: Int?,
        language: String
    ): Hit? {
        if (title.isBlank()) return null
        val entryKey = key(title, season, episode, language)
        val entry = runCatching { read(context).entries[entryKey] }.getOrNull() ?: return null
        if (System.currentTimeMillis() - entry.atMs > TTL_MS) return null
        if (entry.uri.isBlank()) return null
        val uri = runCatching { Uri.parse(entry.uri) }.getOrNull() ?: return null
        val path = uri.path ?: return null
        if (!File(path).isFile) return null
        return Hit(uri, entry.fileName)
    }

    fun forget(
        context: Context,
        title: String,
        season: Int?,
        episode: Int?,
        language: String
    ) {
        if (title.isBlank()) return
        runCatching {
            val entries = LinkedHashMap(read(context).entries)
            if (entries.remove(key(title, season, episode, language)) != null) {
                write(context, Store(entries))
            }
        }
    }

    /**
     * Fetches [episode]'s subtitle now, for the auto-fetch that will run when
     * it starts. This is the same search and the same download the auto-fetch
     * does, run early - deliberately, so the two cannot disagree about which
     * track an episode wants.
     *
     * Best-effort by design: nothing here can fail the session, and every
     * failure just leaves the auto-fetch to do what it did before.
     */
    suspend fun prefetchFor(
        context: Context,
        title: String,
        season: Int?,
        episode: Int?,
        language: String
    ) {
        if (title.isBlank() || language.isBlank()) return
        // Already fetched: fetching again would be the very round-trip this
        // exists to remove.
        if (get(context, title, season, episode, language) != null) return
        val results = SubtitleSearchHelper.search(
            context,
            title = title,
            season = season,
            episode = episode,
            languageHint = language
        )
        val pick = AutoSubtitleRules.pick(results, language) ?: return
        when (val result = SubtitleSearchHelper.download(context, pick)) {
            is SubtitleDownload.Failed ->
                Log.w(TAG, "next-episode subtitle prefetch came up empty: ${result.reason}")

            is SubtitleDownload.Ready -> {
                // Same rule as every other download route: a 200 that parses to
                // nothing is not a subtitle, and must not be cached as one.
                // The engine's own answer, not a hardcoded true: on a build
                // without libass an ASS body is NOT renderable, so a prefetch
                // that accepts one caches a track the player will draw as 0
                // cues (PB-P2-4).
                if (!SubtitleSearchHelper.isUsableSubtitleBody(
                        result.body,
                        assRenderable = AssSubtitleRenderer.available
                    )
                ) {
                    Log.w(TAG, "next-episode subtitle prefetch: download parsed to 0 cues")
                    return
                }
                remember(
                    context = context,
                    title = title,
                    season = season,
                    episode = episode,
                    language = language,
                    fileName = pick.fileName,
                    uri = SubtitleSearchHelper.toCacheUri(context, pick, result.body)
                )
            }
        }
    }

    private fun read(context: Context): Store {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return Store()
        return runCatching { json.decodeFromString(Store.serializer(), raw) }
            .getOrElse {
                // A corrupt blob must never break a session: drop and restart.
                Log.w(TAG, "prefetch blob unreadable; resetting (${it.message})")
                Store()
            }
    }

    private fun write(context: Context, store: Store) {
        // apply(), never commit(): this runs off the credits, not the UI path.
        prefs(context).edit()
            .putString(KEY_ENTRIES, json.encodeToString(Store.serializer(), store))
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileStorage.prefsName(context.applicationContext, PREFS_BASE),
            Context.MODE_PRIVATE
        )
}
