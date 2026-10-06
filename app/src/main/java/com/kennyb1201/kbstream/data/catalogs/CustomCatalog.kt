package com.kennyb1201.kbstream.data.catalogs

import android.content.Context
import com.kennyb1201.kbstream.data.kb.KBFilters
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Media type of a built catalog: TMDB's own two values. */
const val CATALOG_MEDIA_MOVIE = "movie"
const val CATALOG_MEDIA_TV = "tv"

/**
 * The add-on name every built catalog rail is drawn under on Home.
 *
 * A built catalog has no manifest, so it has no add-on to name - and the rail
 * title setting ("show the add-on name") still has to say something true. This
 * is that something, and it is also the first half of the rail's pagination
 * identity (`My Catalogs::<id>::<type>`).
 */
const val CUSTOM_CATALOG_ADDON_NAME = "My Catalogs"

/** How many built catalogs one profile may keep. */
const val MAX_CUSTOM_CATALOGS = 40

/**
 * The watch region a new catalog starts on.
 *
 * TMDB resolves `with_watch_providers` against a region, so a provider rule is
 * meaningless without one - and the Browse browser's own service rails use the
 * same region, which is what makes "on Netflix" mean the same shelf in both
 * places.
 */
const val CATALOG_DEFAULT_REGION = "US"

/**
 * One user-built catalog: a named, re-runnable TMDB /discover query.
 *
 * This is the "rule-based smart catalog" the Catalog Builder composes. Nothing
 * about it is a snapshot - it stores the RULES (media type, sort, filter set)
 * and is re-run every time Home loads, so a catalog of "Action + Netflix + 8.0+"
 * is as fresh as the day it was written without anything being refreshed.
 *
 * [filters] is the same [KBFilters] the imported collection profiles use, which
 * is what makes "every filter the app has" literally true rather than a subset
 * that happens to look the same: the builder's controls write this one object
 * and [com.kennyb1201.kbstream.data.tmdb.TmdbRepository.discoverKB] consumes it.
 *
 * [sort] is a MEDIA-AGNOSTIC id rather than a TMDB `sort_by` value, because the
 * two media types spell their date sorts differently (`primary_release_date.*`
 * for movies, `first_air_date.*` for shows). Storing the agnostic id keeps a
 * catalog valid when its media type is changed after it was written - see
 * [tmdbSortKey].
 */
@JsonClass(generateAdapter = true)
data class CustomCatalog(
    val id: String,
    val name: String,
    val mediaType: String = CATALOG_MEDIA_MOVIE,
    val sort: String = CATALOG_SORT_POPULARITY,
    val filters: KBFilters = KBFilters()
) {
    /** The rail type Home draws this catalog as ("movie" / "series"). */
    val railType: String
        get() = if (mediaType == CATALOG_MEDIA_TV) "series" else "movie"

    /** True when this catalog has no rules at all beyond its sort. */
    val isUnfiltered: Boolean
        get() = filters == KBFilters()

    /** The `sort_by` value TMDB expects for this catalog's media type. */
    fun tmdbSortKey(): String = catalogSortKey(sort, mediaType)

    /**
     * The rules, ready for [com.kennyb1201.kbstream.data.tmdb.TmdbRepository.discoverKB]:
     * blank strings are dropped so a filter the builder cleared cannot travel as
     * an empty query parameter.
     */
    fun effectiveFilters(): KBFilters = normalizedFilters(filters)
}

/**
 * The sort choices the builder offers, in menu order.
 *
 * [id] is the media-agnostic value stored on the catalog; [label] is what the
 * builder draws. See [catalogSortKey] for the TMDB spelling.
 */
enum class CatalogSort(val id: String, val label: String) {
    POPULARITY("popularity", "Popularity"),
    NEWEST("newest", "Newest"),
    TOP_RATED("top_rated", "Top Rated"),
    MOST_VOTED("most_voted", "Most Voted"),
    OLDEST("oldest", "Oldest");

    companion object {
        fun fromId(id: String?): CatalogSort =
            entries.firstOrNull { it.id == id } ?: POPULARITY
    }
}

const val CATALOG_SORT_POPULARITY = "popularity"

/**
 * Maps a media-agnostic sort id to the `sort_by` string TMDB expects for
 * [mediaType].
 *
 * The two media types disagree on the DATE sorts only: a movie is ordered by
 * `primary_release_date.*` and a show by `first_air_date.*`, and sending the
 * movie spelling to `/discover/tv` is a 400, not an empty page. The rating and
 * popularity keys are shared. This is why the builder stores [CatalogSort.id]
 * and not the TMDB string - a catalog whose media type is switched in the
 * builder stays valid.
 *
 * An unknown sort id falls back to popularity, so a catalog written by a newer
 * build still runs on this one instead of failing.
 */
internal fun catalogSortKey(sort: String?, mediaType: String?): String {
    val isTv = mediaType?.trim()?.lowercase() == CATALOG_MEDIA_TV
    return when (sort?.trim()?.lowercase()) {
        CatalogSort.NEWEST.id ->
            if (isTv) "first_air_date.desc" else "primary_release_date.desc"

        CatalogSort.OLDEST.id ->
            if (isTv) "first_air_date.asc" else "primary_release_date.asc"

        CatalogSort.TOP_RATED.id -> "vote_average.desc"

        CatalogSort.MOST_VOTED.id -> "vote_count.desc"

        else -> "popularity.desc"
    }
}

/**
 * Drops the filter fields the builder leaves blank.
 *
 * A cleared control has to mean "no constraint", and an empty string is not
 * that on the wire: TMDB reads `with_genres=` as a filter on nothing and
 * returns an empty page, so a user who cleared a genre would see their catalog
 * go blank. Normalizing here means every consumer - the loader, a preview, a
 * test - sees the same, safe rules.
 */
internal fun normalizedFilters(filters: KBFilters?): KBFilters {
    val source = filters ?: return KBFilters()
    fun clean(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    return KBFilters(
        // A year value is kept verbatim when present: it is either a single year
        // ("1999") or a range ("1990-1999"), and both spellings are meaningful.
        year = source.year,
        withGenres = clean(source.withGenres),
        watchRegion = clean(source.watchRegion),
        voteCountGte = source.voteCountGte?.takeIf { it > 0 },
        withKeywords = clean(source.withKeywords),
        withNetworks = clean(source.withNetworks),
        withCompanies = clean(source.withCompanies),
        withoutGenres = clean(source.withoutGenres),
        releaseDateGte = clean(source.releaseDateGte),
        releaseDateLte = clean(source.releaseDateLte),
        voteAverageGte = source.voteAverageGte?.takeIf { it > 0 },
        voteAverageLte = source.voteAverageLte?.takeIf { it > 0 },
        withoutKeywords = clean(source.withoutKeywords),
        withoutCompanies = clean(source.withoutCompanies),
        withOriginCountry = clean(source.withOriginCountry),
        withWatchProviders = clean(source.withWatchProviders),
        withOriginalLanguage = clean(source.withOriginalLanguage),
        withoutWatchProviders = clean(source.withoutWatchProviders),
        withRuntimeGte = source.withRuntimeGte?.takeIf { it > 0 },
        withRuntimeLte = source.withRuntimeLte?.takeIf { it > 0 },
        withCast = clean(source.withCast),
        certificationCountry = clean(source.certificationCountry),
        certification = clean(source.certification),
        withStatus = clean(source.withStatus),
        withType = clean(source.withType),
        withoutNetworks = clean(source.withoutNetworks),
        withReleaseType = clean(source.withReleaseType)
    )
}

// ---------------------------------------------------------------------------
// Filter-control helpers.
//
// The builder's multi-select chips are comma-joined id lists (what TMDB's
// `with_genres` / `with_watch_providers` / `with_companies` expect) and
// pipe-joined code lists (`with_original_language` / `with_origin_country`).
// Both are pure transforms over the filter string so the toggling rule - add if
// absent, remove if present, and NEVER leave a dangling separator - is unit
// tested instead of re-implemented per chip group.
// ---------------------------------------------------------------------------

/** Whether an id is one of the comma-joined ids in [csv]. */
internal fun csvContains(csv: String?, id: Int): Boolean =
    splitIds(csv).contains(id)

/**
 * Adds [id] to a comma-joined id list, or removes it when already present.
 * Returns null when the result is empty, so "cleared" is a real state rather
 * than an empty string.
 */
internal fun csvToggle(csv: String?, id: Int): String? {
    val current = splitIds(csv)
    val next = if (current.contains(id)) current - id else current + id
    return next.takeIf { it.isNotEmpty() }?.joinToString(",")
}

/**
 * Adds [id] to a comma-joined id list. An id already present is left where it
 * is, so a double press cannot drop the rule the viewer just added (the
 * toggling form above is for a chip that is meant to turn off when pressed
 * again).
 */
internal fun csvAdd(csv: String?, id: Int): String =
    (splitIds(csv) + id).distinct().joinToString(",")

/** Removes [id] from a comma-joined id list; null when nothing is left. */
internal fun csvRemove(csv: String?, id: Int): String? =
    (splitIds(csv) - id).takeIf { it.isNotEmpty() }?.joinToString(",")

/** Whether a code is one of the pipe-joined codes in [codes]. */
internal fun codeListContains(codes: String?, code: String): Boolean =
    splitCodes(codes).contains(code)

/**
 * Adds [code] to a pipe-joined code list, or removes it when already present.
 * Returns null when the result is empty.
 */
internal fun codeListToggle(codes: String?, code: String): String? {
    val current = splitCodes(codes)
    val next = if (current.contains(code)) current - code else current + code
    return next.takeIf { it.isNotEmpty() }?.joinToString("|")
}

/** The comma-joined ids of a filter value, in order and de-duplicated. */
internal fun splitIds(csv: String?): List<Int> =
    csv.orEmpty()
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .distinct()

/** The pipe-joined codes of a filter value, in order and de-duplicated. */
internal fun splitCodes(codes: String?): List<String> =
    codes.orEmpty()
        .split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** "1990" / "1990-1999" spelling of a year constraint, or null when open. */
internal fun yearFilterValue(start: Int?, end: Int?): String? {
    val from = start ?: return null
    val to = end ?: from
    return if (from == to) from.toString() else "$from-$to"
}

// ---------------------------------------------------------------------------
// List transforms: the builder's store operations, pure so the rule that a
// rename cannot reorder the list or duplicate an id is testable without a
// Context, a prefs file or a screen.
// ---------------------------------------------------------------------------

/**
 * A fresh catalog id that cannot collide with [existing].
 *
 * [seed] is a millisecond clock by default. Base-36 of it plus a counter on
 * collision, which keeps the common case a stable, readable key the builder can
 * hand back after a save.
 */
internal fun newCatalogId(seed: Long, existing: Set<String>): String {
    val base = "cat" + seed.toString(36)
    if (base !in existing) return base
    var n = 2
    while ("$base-$n" in existing) n++
    return "$base-$n"
}

/**
 * Inserts [catalog] or replaces the entry with its id, keeping its position.
 * Returns the list unchanged when the id already exists and nothing differs, so
 * a save that changed nothing does not dirty the synced blob.
 */
internal fun upsertCatalog(
    catalogs: List<CustomCatalog>,
    catalog: CustomCatalog
): List<CustomCatalog> {
    val index = catalogs.indexOfFirst { it.id == catalog.id }
    if (index < 0) return catalogs + catalog
    if (catalogs[index] == catalog) return catalogs
    return catalogs.toMutableList().also { it[index] = catalog }
}

/** Removes one catalog by id; the list is returned unchanged when it is gone. */
internal fun removeCatalog(
    catalogs: List<CustomCatalog>,
    id: String
): List<CustomCatalog> = catalogs.filterNot { it.id == id }

/**
 * Moves one catalog [delta] places in the BUILDER's own list order.
 *
 * This is only the order the builder lists them in - where each rail sits on
 * Home is the home arrangement's business, exactly like every other rail. A
 * move that would not change the order returns the list as-is so the caller can
 * skip the write.
 */
internal fun moveCatalog(
    catalogs: List<CustomCatalog>,
    id: String,
    delta: Int
): List<CustomCatalog> {
    val from = catalogs.indexOfFirst { it.id == id }
    if (from < 0) return catalogs
    val to = (from + delta).coerceIn(0, catalogs.lastIndex)
    if (from == to) return catalogs
    return catalogs.toMutableList().also { list -> list.add(to, list.removeAt(from)) }
}

/** A catalog is saveable when it has a name; the rules themselves may be open. */
internal fun isSaveableCatalog(catalog: CustomCatalog?): Boolean =
    catalog != null && catalog.name.trim().isNotEmpty()

/**
 * Per-profile store of the user's built catalogs.
 *
 * One JSON blob under the profile-scoped prefs name, like the browse chips and
 * the rail arrangement it shares Home with. Writes publish to the account
 * through the same ordered path those do (blob first, then the sync stamp, then
 * the enqueue), which is what keeps a pull from an older sibling device from
 * reverting a catalog the user just wrote.
 */
object CustomCatalogStore {

    private const val PREFS = "kbstream_custom_catalogs"
    private const val KEY_BLOB = "custom_catalogs_json"

    /** The store's own encoding, handed verbatim to the cross-device payload. */
    internal const val SYNC_STORE = PREFS
    internal const val SYNC_BLOB_KEY = KEY_BLOB

    /**
     * When this device last edited OR adopted the blob. Never published: it is
     * what lets the pull tell a sibling's copy from this device's own newer
     * edit (the same job `BrowseHomeShortcuts.SYNCED_AT_KEY` does).
     */
    const val SYNCED_AT_KEY = "custom_catalogs_synced_at"

    /**
     * Bumped on every local write.
     *
     * Home watches this so a catalog that was just built, edited, hidden or
     * deleted rebuilds the rails as soon as the viewer comes back from the
     * builder - a rail list that only refreshed on a stale-resume timer would
     * leave the new row missing for up to ten minutes.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(CustomCatalogBlob::class.java)

    fun list(context: Context): List<CustomCatalog> {
        val raw = prefs(context).getString(KEY_BLOB, null) ?: return emptyList()
        return runCatching { adapter.fromJson(raw) }
            .getOrNull()
            ?.catalogs
            .orEmpty()
    }

    fun find(context: Context, id: String?): CustomCatalog? =
        id?.let { wanted -> list(context).firstOrNull { it.id == wanted } }

    /**
     * Replaces the stored list. A blank list is written rather than cleared, so
     * "the user deleted their last catalog" is a state the other devices can
     * adopt - see the applier, which skips only a payload that carries no blob
     * at all.
     */
    fun save(context: Context, catalogs: List<CustomCatalog>): List<CustomCatalog> {
        val capped = catalogs.take(MAX_CUSTOM_CATALOGS)
        prefs(context).edit()
            .putString(KEY_BLOB, adapter.toJson(CustomCatalogBlob(capped)) ?: "{}")
            .putLong(SYNCED_AT_KEY, System.currentTimeMillis())
            .apply()

        // AFTER the write, so anything observing the revision reads the
        // already-updated list (the same ordering the sync enqueue below and
        // the pulled-prefs revision follow).
        _revision.value += 1

        // Mirror to the account so a catalog built on the TV shows up on the
        // other one. Enqueued AFTER the blob write and the stamp, so the push
        // carries this edit rather than the previous list.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { app ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                app,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_CUSTOM_CATALOGS,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
                    .buildCustomCatalogs(app)
            )
        }
        return capped
    }

    /** Inserts or replaces one catalog; returns the stored list. */
    fun upsert(context: Context, catalog: CustomCatalog): List<CustomCatalog> =
        save(context, upsertCatalog(list(context), catalog))

    /** Removes one catalog by id; returns the stored list. */
    fun remove(context: Context, id: String): List<CustomCatalog> =
        save(context, removeCatalog(list(context), id))

    /** Moves one catalog in the builder's list order; returns the stored list. */
    fun move(context: Context, id: String, delta: Int): List<CustomCatalog> =
        save(context, moveCatalog(list(context), id, delta))

    private fun prefs(context: Context) = context.getSharedPreferences(
        ProfileStorage.prefsName(context, PREFS),
        Context.MODE_PRIVATE
    )
}

/** The stored blob: one list, so a read is a single pref lookup. */
@JsonClass(generateAdapter = true)
internal data class CustomCatalogBlob(
    val catalogs: List<CustomCatalog> = emptyList()
)
