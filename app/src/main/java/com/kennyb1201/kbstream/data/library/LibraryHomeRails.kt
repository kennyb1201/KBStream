package com.kennyb1201.kbstream.data.library

import android.content.Context
import com.kennyb1201.kbstream.data.addon.AppContextHolder
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.reporting.LibraryRailArtTrace
import com.kennyb1201.kbstream.data.reporting.libraryRailCarriedArtLine
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.trakt.TraktListEntry
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.kennyb1201.kbstream.data.trakt.traktListReadsForPinnedId
import com.squareup.moshi.JsonClass
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
 * The store's own encoding of the pins (see [LibraryHomeRails.encode]).
 *
 * One field, and deliberately the same `rails` array the account row carries
 * (see [LibraryHomeRails.buildBlob]), so a blob written here and one adopted
 * from a sibling read back through one shape rather than two that can drift.
 *
 * Annotated like every other stored model in the app: without a generated
 * adapter Moshi falls back to kotlin-reflect, which a release build answers
 * with "No JsonAdapter for class java.util.ArrayList" while the object
 * initialises - a crash on Home rather than a parse failure (Sentry ANDROID-16,
 * 0.9.160).
 */
@JsonClass(generateAdapter = true)
internal data class LibraryHomeRailsBlob(
    val rails: List<LibraryHomeRailPin> = emptyList()
)

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
     * Re-points a list's pin at the identity it has now that it was renamed.
     *
     * The same breath as the rename, the way the delete path unpins in the
     * same breath as the delete: [setPinned] alone could only ever reach the
     * pin whose key survived the rename, and a service that derives the id from
     * the name (Trakt's slug, BingeCat's ref) leaves the old pin pointing at a
     * list that no longer answers. [previousIds] is why the old pin is still
     * found - see [repinnedAfterRename].
     *
     * ONE write for the remove-old/add-new pair: a sibling reading the account
     * row mid-rename must never see two pins for one list, or it would draw the
     * second rail this fix exists to prevent. Returns true when the store
     * changed; no pin for the list, or an identical rewrite, writes nothing (a
     * rename must never CREATE a pin).
     */
    fun repinAfterRename(
        context: Context,
        renamed: LibraryHomeRailPin,
        previousIds: Set<String>
    ): Boolean {
        val current = pins(context)
        val updated = repinnedAfterRename(current, renamed, previousIds)
        if (updated == current) return false
        write(context, updated)
        return true
    }

    /**
     * Renames one pinned rail, in place.
     *
     * The pin's stored name IS the rail's heading (see [LibraryHomeRailPin]), so
     * a rename here is a same-key replace through [setPinned] - never an
     * arrangement title override, which would give the heading a second source
     * of truth that the Library and the next sync would disagree with.
     *
     * Works on a pin whose list cannot be loaded (a stale pin): the key is
     * stored, not derived from the service.
     */
    fun renamePinned(context: Context, pin: LibraryHomeRailPin, name: String): Boolean {
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return false
        if (trimmed == pin.name) return false
        val current = pins(context)
        if (current.none { it.key == pin.key }) return false
        setPinned(context, pin.copy(name = trimmed), true)
        return true
    }

    /**
     * Drops [spellings] in one write.
     *
     * What the toggle's Remove needs once a list can be pinned under more than
     * one spelling (see [pinsNamingList]): removing only the spelling this build
     * would write today leaves the ghost rail exactly where it was. Publishing is
     * the point, as it is for every other pin edit: the sibling loses the rail
     * too.
     */
    fun removePins(context: Context, spellings: Collection<LibraryHomeRailPin>): Boolean {
        if (spellings.isEmpty()) return false
        val keys = spellings.mapTo(mutableSetOf()) { it.key }
        val current = pins(context)
        val kept = current.filterNot { it.key in keys }
        if (kept.size == current.size) return false
        write(context, kept)
        return true
    }

    /**
     * Drops the pins of the local lists in [deletedListIds], as a local edit.
     *
     * Called from [LocalLibraryStore.deleteList], so the pin goes in the same
     * breath as the list on every surface that can delete one. Publishing is
     * the point: the sibling that still holds the pin must lose it too, which
     * is why this goes through [write] rather than filtering quietly. The
     * removal of the LAST pin still publishes (see [shouldPublish]), so an
     * empty blob here is a statement, not a silence.
     */
    fun dropLocalPins(context: Context, deletedListIds: Set<Int>): Boolean {
        if (deletedListIds.isEmpty()) return false
        val current = pins(context)
        val kept = withoutDeletedLocalPins(current, deletedListIds)
        if (kept.size == current.size) return false
        write(context, kept)
        return true
    }

    /**
     * Drops the pins of lists that no longer exist at the service, and mirrors
     * the result.
     *
     * Called by the Library's full refresh with the ids each list source just
     * answered with (see [withoutVanishedPins]). A list deleted on a website is
     * only visible as an absence, so this is the one place that absence is read
     * as a statement - and only for a source that DID answer, which is why the
     * map is built from successful reads alone.
     *
     * Publishing matters twice over: the sibling devices lose the rail, and the
     * account's pin row stops naming a list that is gone, so no later adoption
     * can bring it back.
     *
     * [live] carries each live list's current id AND its spellings (see
     * [libraryHomeRailLiveList]), because keeping a pin is only half the job: a
     * pin an older build wrote under another spelling names a list the Home
     * load cannot read, so [reconciledPins] also re-points it at the id the row
     * carries today - in this same write, so the corrected pin reaches the
     * account as one edit and the old spelling is never needed again. An
     * unchanged answer writes nothing: a reconcile that moved no pin must not
     * publish, and a re-point must not publish an empty blob either.
     */
    fun reconcilePins(
        context: Context,
        live: Map<String, List<LibraryHomeRailLiveList>>
    ): Boolean {
        if (live.isEmpty()) return false
        val current = pins(context)
        val kept = reconciledPins(current, live)
        if (kept == current) return false
        write(context, kept)
        return true
    }

    /**
     * The same drop, for a tombstone that arrived from another device.
     *
     * [profileId] rather than the active profile: the applier may be writing a
     * sibling's pins. Nothing is published here - a pull is not this device's
     * edit, and the device that deleted the list already published both the
     * tombstone and its own pin row, so republishing would be this device
     * claiming an edit it did not make. The stamp is still moved forward, so an
     * older account copy adopted later cannot put the dead pin back.
     */
    internal fun dropLocalPinsAdopted(
        context: Context,
        profileId: String?,
        deletedListIds: Set<Int>
    ): Boolean {
        if (deletedListIds.isEmpty()) return false
        val prefs = prefsFor(context, profileId)
        val raw = prefs.getString(KEY_BLOB, null) ?: return false
        val current = runCatching { adapter.fromJson(raw) }.getOrNull()?.rails ?: return false
        val kept = withoutDeletedLocalPins(current, deletedListIds)
        if (kept.size == current.size) return false
        prefs.edit()
            .putString(KEY_BLOB, encode(kept))
            .putLong(SYNCED_AT_KEY, System.currentTimeMillis())
            .apply()
        return true
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

    /**
     * This profile's stored pins as the store wrote them, or null when the blob
     * is absent or unreadable.
     *
     * For the adoption path (PrefsPayloadApplier.applyLibraryHomeRails): it holds
     * the prefs file already, and a null-stamped pull has to union against what
     * is ON DISK for the profile the row names, not against the active one.
     */
    internal fun storedPins(raw: String?): List<LibraryHomeRailPin>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { adapter.fromJson(raw) }.getOrNull()?.rails
    }

    private fun prefs(context: Context) = prefsFor(context, null)

    /** The pins blob of one profile (null = the profile scoped to right now). */
    private fun prefsFor(context: Context, profileId: String?) = context.getSharedPreferences(
        if (profileId == null) ProfileStorage.prefsName(context, PREFS)
        else ProfileStorage.prefsName(profileId, PREFS),
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
            // Read the pin by the path its id names (see
            // [traktListReadsForPinnedId]): a liked PUBLIC list is pinned by its
            // numeric Trakt id, and that endpoint takes no owner - reading it as
            // `users/me/lists/{numeric}` 404'd, which the repository answers
            // with an empty list, so the rail drew nothing at all.
            val repository = TraktRepository.getInstance(context)
            var entries = emptyList<TraktListEntry>()
            for (ref in traktListReadsForPinnedId(pin.id)) {
                entries = repository.listItems(ref)
                if (entries.isNotEmpty()) break
            }
            val rows = entries.mapNotNull { entry ->
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
            // Which fork this load was, for COPY DIAGNOSTICS: how many rows
            // shipped a poster URL and how many did not. A Trakt rail drawn
            // title-only is either a read that carried no art or one that
            // carried art that does not load, and the card cannot tell those
            // apart from the outside (see [LibraryRailArtTrace]).
            LibraryRailArtTrace.record(
                rail = "trakt-list:${pin.id}",
                line = libraryRailCarriedArtLine(
                    source = "trakt list",
                    listName = pin.name,
                    listId = pin.id,
                    carried = rows.count { !it.posterUrl.isNullOrBlank() },
                    missing = rows.count { it.posterUrl.isNullOrBlank() }
                )
            )
            rows
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

        // The anime trackers' own lists (spec 2026-11-13 F1), read live from
        // the service exactly as the Library reads them: a pinned rail follows
        // the list, and the read already dropped every entry with no TMDB match
        // (so no rail draws a blank card). A service that has since lost its
        // username/connection answers empty and the rail simply does not draw -
        // the same rule the other remote seats follow.
        LibrarySource.ANILIST_LIST,
        LibrarySource.KITSU_LIST,
        LibrarySource.MAL_LIST ->
            com.kennyb1201.kbstream.data.anime.AnimeLists.items(context, source, pin.id)

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
