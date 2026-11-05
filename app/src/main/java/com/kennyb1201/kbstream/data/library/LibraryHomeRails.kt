package com.kennyb1201.kbstream.data.library

import android.content.Context
import com.kennyb1201.kbstream.data.addon.AppContextHolder
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.trakt.TraktListRef
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * This profile's pinned Library lists, and the switch that puts one there.
 *
 * Stored under the profile-scoped prefs name (see [ProfileStorage.prefsName]),
 * like the browse chips and the rail arrangement: a list pinned on one profile
 * must not appear on another profile's Home.
 *
 * The blob travels with the account (see [PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS]),
 * with the same two rules the other hand-built Home rows follow: a device that
 * never pinned anything publishes nothing (so its empty row cannot erase the
 * account's rails), and a deliberate removal of the last pin DOES publish (so
 * the removal reaches the sibling instead of being undone by its next push).
 */
object LibraryHomeRails {

    private const val PREFS = "kbstream_library_home_rails"
    private const val KEY_BLOB = "library_home_rails_json"

    /** Local bookkeeping: when this device last edited or adopted the blob. */
    const val SYNCED_AT_KEY = "library_home_rails_synced_at"

    internal const val SYNC_STORE = PREFS
    internal const val SYNC_BLOB_KEY = KEY_BLOB

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(LibraryHomeRailsBlob::class.java)

    fun pins(context: Context): List<LibraryHomeRailPin> {
        val raw = prefs(context).getString(KEY_BLOB, null) ?: return emptyList()
        return runCatching { adapter.fromJson(raw) }.getOrNull()?.rails.orEmpty()
    }

    fun isPinned(context: Context, pin: LibraryHomeRailPin): Boolean =
        pins(context).any { it.key == pin.key }

    /**
     * Adds or removes one list, and mirrors the result to the account.
     *
     * A rename is handled by the same call: pinning a row that is already
     * pinned with a different title updates the title in place (its position
     * and every other pin are untouched), so the rail on Home follows a list
     * the viewer renamed in the Library.
     */
    fun setPinned(context: Context, pin: LibraryHomeRailPin, pinned: Boolean) {
        val current = pins(context)
        val updated = if (pinned) {
            val existing = current.firstOrNull { it.key == pin.key }
            when {
                existing == null -> current + pin
                // Same rail, new name: keep the slot, take the title.
                existing.name != pin.name -> current.map { if (it.key == pin.key) pin else it }
                else -> return
            }
        } else {
            val kept = current.filterNot { it.key == pin.key }
            if (kept.size == current.size) return
            kept
        }
        write(context, updated)
    }

    /**
     * Writes [rails] and mirrors them.
     *
     * The stamp is written BEFORE the enqueue so the pull's staleness guard
     * treats this edit as already synced, and an older sibling copy cannot
     * revert the pin the viewer just made (the ordering the browse chips and
     * the rail order both learned the hard way).
     */
    fun write(context: Context, rails: List<LibraryHomeRailPin>) {
        prefs(context).edit()
            .putString(KEY_BLOB, encode(rails))
            .putLong(SYNCED_AT_KEY, System.currentTimeMillis())
            .apply()
        val appContext = AppContextHolder.appContext ?: context.applicationContext
        runCatching {
            SupabaseSync.enqueuePrefs(
                appContext,
                PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS,
                buildBlob(appContext)
            )
        }
    }

    /**
     * The store's own encoding of [rails].
     *
     * Exposed for the cloud-adoption path (PrefsPayloadApplier): a blob written
     * here and a blob adopted from the account must share one shape, or the
     * next read of an adopted row would not parse.
     */
    internal fun encode(rails: List<LibraryHomeRailPin>): String =
        adapter.toJson(LibraryHomeRailsBlob(rails)) ?: "{}"

    /** The account copy: the pins plus a write time. */
    fun buildBlob(context: Context): JsonObject = buildJsonObject {
        put("updatedAt", System.currentTimeMillis())
        put(
            "rails",
            buildJsonArray {
                pins(context).forEach { pin ->
                    add(
                        buildJsonObject {
                            put("source", pin.source)
                            put("id", pin.id)
                            put("name", pin.name)
                        }
                    )
                }
            }
        )
    }

    /**
     * True when this profile has something to say about the account's pins.
     *
     * `SYNCED_AT_KEY > 0` means this device has EDITED (or adopted) the row at
     * least once - so it publishes, even when the current answer is "none",
     * which is how removing the last pin reaches the other TV. A device that
     * never touched the row publishes nothing, and cannot erase it.
     */
    fun shouldPublish(context: Context): Boolean =
        prefs(context).getLong(SYNCED_AT_KEY, 0L) > 0L

    /** The one read of a pushed blob: the pins it carries, or null when unreadable. */
    internal fun parseBlob(payload: JsonObject): List<LibraryHomeRailPin>? {
        val array = payload["rails"] as? JsonArray ?: return null
        return array.mapNotNull { element ->
            val object_ = element as? JsonObject ?: return@mapNotNull null
            fun text(name: String): String =
                (object_[name] as? JsonPrimitive)?.content.orEmpty()
            val source = text("source")
            val id = text("id")
            val name = text("name")
            if (source.isBlank() || id.isBlank() || name.isBlank()) return@mapNotNull null
            LibraryHomeRailPin(source = source, id = id, name = name)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        ProfileStorage.prefsName(context, PREFS),
        Context.MODE_PRIVATE
    )
}

/**
 * The items of one pinned list, in the order the service returns them.
 *
 * The Library pane's own loader (LibraryViewModel.fetchListItems) is private and
 * runs inside a ViewModel that also enriches and sorts for the grid; Home needs
 * one list's rows and nothing else, so it asks the same stores and services
 * directly. A failure is an empty row, never a crash: a dead service must not
 * take Home down with it, and the rail simply does not draw.
 */
object LibraryHomeRailItems {

    suspend fun load(context: Context, pin: LibraryHomeRailPin): List<LibraryItem> {
        val source = runCatching { LibrarySource.valueOf(pin.source) }.getOrNull()
            ?: return emptyList()
        val items = runCatching { loadFor(context, source, pin) }.getOrDefault(emptyList())
        // What the viewer told the Library to forget stays forgotten here too:
        // the tombstone record is the Library's own answer about a title, and a
        // rail that skipped it would put back what a removal took out.
        return LibraryTombstones.removedFrom(items, LocalLibraryStore.tombstones(context))
    }

    private suspend fun loadFor(
        context: Context,
        source: LibrarySource,
        pin: LibraryHomeRailPin
    ): List<LibraryItem> = when (source) {
        LibrarySource.LOCAL_LIST -> {
            val id = pin.id.removePrefix("id:").toIntOrNull() ?: return emptyList()
            LocalLibraryStore.listItems(context, id)
        }

        LibrarySource.BINGECAT_LIST -> {
            val rows = BingeCatRepository.getInstance(context).listRows(pin.id).orEmpty()
            rows.map { row ->
                LibraryItem(
                    source = source,
                    mediaType = row.mediaType,
                    title = row.title,
                    year = row.year,
                    imdbId = row.imdbId,
                    tmdbId = row.tmdbId,
                    listName = pin.name,
                    listRemoteId = pin.id
                )
            }
        }

        LibrarySource.TRAKT_LIST -> {
            val entries = TraktRepository.getInstance(context)
                .listItems(TraktListRef(user = "me", listId = pin.id))
            entries.mapNotNull { entry ->
                val media = entry.media
                val title = media.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val imdbId = media.ids?.imdb?.takeIf { it.isNotBlank() }
                val tmdbId = media.ids?.tmdb?.takeIf { it > 0 }
                if (imdbId == null && tmdbId == null) return@mapNotNull null
                LibraryItem(
                    source = source,
                    mediaType = if (entry.isMovie) "movie" else "series",
                    title = title,
                    year = media.year?.takeIf { it > 0 },
                    posterUrl = media.images?.poster?.firstOrNull()?.takeIf { it.isNotBlank() },
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    listName = pin.name,
                    listRemoteId = pin.id
                )
            }
        }

        LibrarySource.MDBLIST_LIST -> {
            val id = pin.id.removePrefix("id:").toIntOrNull() ?: return emptyList()
            MdbListClient.getListItems(context, id).orEmpty().map { entry ->
                LibraryItem(
                    source = source,
                    mediaType = entry.mediaType,
                    title = entry.title ?: "Untitled",
                    year = entry.year,
                    posterUrl = entry.poster,
                    imdbId = entry.imdbId,
                    tmdbId = entry.tmdbId,
                    listName = pin.name
                )
            }
        }

        // One account's WATCHLIST. The WATCHLIST filter's merged grid is still
        // the only place the four are drawn together, but each of them is a
        // list of its own, so each is asked for its own rows here.
        LibrarySource.TRAKT_WATCHLIST -> {
            TraktRepository.getInstance(context).watchlist().mapNotNull { entry ->
                val media = entry.media
                val title = media.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val imdbId = media.ids?.imdb?.takeIf { it.isNotBlank() }
                val tmdbId = media.ids?.tmdb?.takeIf { it > 0 }
                if (imdbId == null && tmdbId == null) return@mapNotNull null
                LibraryItem(
                    source = source,
                    mediaType = if (entry.isMovie) "movie" else "series",
                    title = title,
                    year = media.year?.takeIf { it > 0 },
                    posterUrl = media.images?.poster?.firstOrNull()?.takeIf { it.isNotBlank() },
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    listName = pin.name
                )
            }
        }

        LibrarySource.MDBLIST_WATCHLIST -> {
            // A null is an INCOMPLETE walk (see MdbListClient.getWatchlist), so
            // it is the same "no answer" the Library treats as a failure: the
            // rail does not draw rather than drawing a truncated list as if it
            // were the whole watchlist.
            MdbListClient.getWatchlist(context).orEmpty().map { entry ->
                LibraryItem(
                    source = source,
                    mediaType = entry.mediaType,
                    title = entry.title ?: "Untitled",
                    year = entry.year,
                    posterUrl = entry.poster,
                    imdbId = entry.imdbId,
                    tmdbId = entry.tmdbId,
                    listName = pin.name
                )
            }
        }

        LibrarySource.BINGECAT_WATCHLIST -> {
            BingeCatRepository.getInstance(context).snapshot()?.watchlist.orEmpty().map { row ->
                LibraryItem(
                    source = source,
                    mediaType = row.mediaType,
                    title = row.title,
                    year = row.year,
                    imdbId = row.imdbId,
                    tmdbId = row.tmdbId,
                    listName = pin.name
                )
            }
        }

        // Simkl's watchlist read already answers in Library rows (it is the one
        // source the Library does not have to map).
        LibrarySource.SIMKL_WATCHLIST -> {
            SimklRepository.getInstance(context).getWatchlistItems()
                .map { item -> item.copy(listName = pin.name) }
        }

        // The aggregate sources have no list of their own: My List is its own
        // filter, and BingeCat's saved list is read-only favourites.
        else -> emptyList()
    }
}
