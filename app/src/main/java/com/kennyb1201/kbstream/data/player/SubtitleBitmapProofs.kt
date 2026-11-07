package com.kennyb1201.kbstream.data.player

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which embedded subtitle tracks this DEVICE has seen draw as BITMAPS (PGS
 * images) in a given title, so the next play can avoid auto-selecting them.
 *
 * Why memory at all: media3 transcodes every supported subtitle format to
 * `application/x-media3-cues` during extraction, so the track's mime cannot say
 * whether it is text or an authored image (see SubtitleTrackRules). The only
 * evidence available to the app is what the track DID when it played - a cue
 * group carrying `Cue.bitmap` - and that evidence has to survive the session to
 * be useful, because the first play of a title is the one that proves it.
 *
 * Keyed per SHOW by the same [PlayerTitlePrefs.titleKeyFor] identity the other
 * per-title playback memory uses (episode 2 of a season shares its siblings'
 * tracks). Each record carries the track's flat position in the text-track
 * list, its language tag and its kind, and the guard is those last two: a
 * record only applies to a candidate whose language and kind still match it, so
 * a different rip of the same show with reordered tracks cannot misfire.
 *
 * Profile-scoped, device-local, and NEVER synced - it is a fact about the files
 * this device happened to play, not a display preference. Bounded
 * ([MAX_TITLES]), oldest first out, so a long-lived install cannot grow the
 * file forever.
 */
internal object SubtitleBitmapProofs {

    private const val TAG = "SUBTITLE_BITMAP_PROOFS"

    // Same profile-scoped prefs file as PlayerTitlePrefs / PlayerTrackMemory;
    // a distinct key keeps the blobs independent.
    private const val PREFS_BASE = "kbstream_player_memory"
    private const val KEY_ENTRIES = "bitmap_proofs_v1"

    /** Titles remembered, not tracks: a show's tracks are one record set. */
    private const val MAX_TITLES = 200

    @Serializable
    private data class Entry(
        val proofs: List<BitmapTrackProof> = emptyList(),
        val at: Long = 0L
    )

    @Serializable
    private data class Store(
        val entries: Map<String, Entry> = emptyMap()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The proven-bitmap tracks stored for [titleKey] (empty when none, or no key). */
    fun proofs(context: Context, titleKey: String?): List<BitmapTrackProof> {
        if (titleKey.isNullOrBlank()) return emptyList()
        return runCatching { read(context).entries[titleKey]?.proofs.orEmpty() }
            .getOrDefault(emptyList())
    }

    /**
     * Records that the track at [position] drew bitmaps in [titleKey]'s files.
     *
     * Called only when a cue group actually carried an image (see
     * `SubtitleCueHandler.showBitmapCues`), which is the proof this store is
     * named after - never from a mime type. Re-recording the same position
     * replaces its record, so the entry always describes the latest play.
     */
    @Synchronized
    fun record(
        context: Context,
        titleKey: String?,
        position: Int,
        language: String?,
        kind: String
    ) {
        if (titleKey.isNullOrBlank() || position < 0) return
        runCatching {
            val entries = LinkedHashMap(read(context).entries)
            val now = System.currentTimeMillis()
            val proofs = entries[titleKey]?.proofs.orEmpty()
                .filterNot { it.position == position } +
                BitmapTrackProof(
                    position = position,
                    language = language,
                    kind = kind,
                    at = now
                )
            entries[titleKey] = Entry(proofs = proofs, at = now)

            if (entries.size > MAX_TITLES) {
                entries.entries
                    .sortedBy { it.value.at }
                    .take(entries.size - MAX_TITLES)
                    .forEach { entries.remove(it.key) }
            }

            write(context, Store(entries))
        }.onFailure {
            Log.w(TAG, "record failed: ${it.message}")
        }
    }

    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_ENTRIES).apply() }
    }

    private fun read(context: Context): Store {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return Store()
        return runCatching { json.decodeFromString(Store.serializer(), raw) }
            .getOrElse {
                // A corrupt blob must never affect playback.
                Log.w(TAG, "proof blob unreadable; resetting (${it.message})")
                Store()
            }
    }

    private fun write(context: Context, store: Store) {
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
