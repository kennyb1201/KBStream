package com.kennyb1201.kbstream.data.tmdb

import android.content.Context
import com.kennyb1201.kbstream.data.BackgroundWork
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.util.concurrent.ConcurrentHashMap
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.cache.ImdbResolutionEntity
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheMaintenance
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.memory.evictOldest
import com.kennyb1201.kbstream.data.player.EpisodeSchemeStore
import com.kennyb1201.kbstream.data.player.fileEpisodeStreamId
import com.kennyb1201.kbstream.data.sync.KidsMode
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import com.kennyb1201.kbstream.data.settings.AppPreferences
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.kennyb1201.kbstream.data.runCatchingCancellable

/**
 * Artwork for a Home Browse tile: a backdrop to draw as its cover, plus the
 * brand mark a service or a studio sits over it.
 *
 * There is deliberately no wordmark field for a genre, a keyword or a decade.
 * The only logo one of those could borrow is a spotlight title's, and drawing
 * that says the wrong thing: it put a Spider-Man wordmark on the Adventure tile
 * and across the hero while the viewer scrolled Genres or Decades. Those
 * shortcuts come back with no clearlogo at all, and their tiles and hero say
 * the chip's own name instead.
 *
 * Null / empty fields are the honest answer for a shortcut TMDB has no art for
 * (a keyword with no backdrop-carrying titles, a service whose brand mark is
 * not in the registry); the tile then falls back to its own name rather than
 * drawing a blank card.
 */
data class BrowseShortcutArt(
    val backdropUrl: String? = null,
    val clearlogoUrl: String? = null,
    /**
     * Every brand-mark candidate for a service or studio, best first. Empty for
     * a genre / keyword / decade / collection, none of which owns a mark.
     * [clearlogoUrl] is the first of these; the tile and the hero both walk the
     * list, so a brand whose top mark is undrawable still gets one of its own
     * instead of showing nothing.
     */
    val clearlogoUrls: List<String> = emptyList()
)

data class StudioItem(val item: TmdbDiscoverItem, val mediaType: String)
data class StudioSection(val title: String, val items: List<StudioItem>)

/**
 * Whether a discover row is a real catalog entry - something a poster grid can
 * draw - rather than a TMDB placeholder (an announced announcement, a festival
 * stub, a merge target) that carries a title and nothing else.
 *
 * A missing poster is NOT on its own that test, which is the mistake the rail
 * gate used to make. TMDB routinely holds a real, already-airing entry with no
 * artwork for months: its Game Show Network pages for the 2025 `Bingo Blitz`
 * and `Tic Tac Dough` revivals have no poster, The CW's 2026 `The Great
 * American Road Rally: Celebrity Edition` has none, nor do The Weather
 * Channel's 2023 `Search Party with Brandon Jordan` or Rakuten Viki's 2025
 * `Business As Usual` - every one of them a substantive entry with a synopsis.
 * Dropping those on the artwork test alone pushed the RECENT rail's head back
 * a year or more (Game Show Network: 2025 -> 2024; The CW: 2026 -> 2025),
 * which is precisely the recency that rail exists to show.
 *
 * So an entry with no poster survives when TMDB has something behind it - a
 * synopsis, or any audience at all - and only the empty stubs are dropped. A
 * surviving entry without artwork renders as a titled card, which is
 * `PosterCard`'s documented fallback, so nothing draws blank.
 */
/**
 * The rail/resume projection of a TMDB detail response: everything a card, a
 * badge, a next-episode walk or a playback target reads, and none of the bulk.
 *
 * The five fields it blanks are the ones every consumer outside the Detail
 * screen, the Home/KB heroes and anime detection ignores. Measured against the
 * live API, this is 27-81 KB against 65-280 KB for the full row — a saving of
 * 35-58% on TV rows and 59-84% on movies, where the credits blob dominates.
 *
 * Blanking fields on the existing model rather than introducing a slim type
 * keeps `slim + bulk == full` structurally true and lets both rows share one
 * JSON adapter.
 *
 * Three field groups deliberately survive, and each has a caller that is easy
 * to miss because it reads a HELPER rather than a field:
 *
 *  - `images` — [bestLogoPath], [cardBackdropPath] and [alternatePosterPath]
 *    are all defined off it, and the rail landscape-art path (the per-card
 *    backdrop + clearlogo prefetch on Home and in KB folders) calls the first
 *    two. This is the one that nearly shipped as a bug: it reads as artwork
 *    enrichment rather than as the detail payload, so dropping it would have
 *    silently blanked every rail card's alternate backdrop and clearlogo.
 *  - `releaseDates`/`contentRatings` — the Kids Mode ceiling is enforced from
 *    [certification], which reads them, and that check runs over every rail
 *    page. A projection without them would fail OPEN, which is the wrong way
 *    for a parental control to fail.
 *
 * Top-level and pure on purpose: this is the rule the whole disk-budget story
 * rests on (a slim row must never be served to a surface that needs the bulk),
 * and as a testable function it can be asserted directly instead of through a
 * repository that needs a Context to build.
 */
internal fun TmdbDetail.railProjection(): TmdbDetail = copy(
    credits = null,
    videos = null,
    recommendations = null,
    reviews = null,
    keywords = null
)

internal fun hasSomethingToDraw(item: StudioItem): Boolean =
    !item.item.posterPath.isNullOrBlank() ||
        !item.item.overview.isNullOrBlank() ||
        (item.item.voteCount ?: 0) > 0

/**
 * The non-genre dimension a discover screen is already filtered by. Genre
 * chip rails compose this WITH a genre (KBFilters fields AND together), so
 * every screen can offer "browse this dimension by genre" without new
 * endpoints. kind: provider | company | network | keyword | decade.
 */
data class CrossBase(
    val kind: String,
    val id: Int,
    /**
     * For a `network` base: the brand's TMDB company id. Network discover is
     * TV-only, so a genre-filtered network screen's MOVIES rails run through
     * company discover instead (null = no movie rails on that screen).
     */
    val companyId: Int? = null
)

/**
 * One page of a browse rail.
 *
 * [nextPage] is the first TMDB page this rail has NOT merged yet. Page
 * deepening (see [TmdbRepository.finishDeepRailPage]) consumes several TMDB
 * pages before a rail first renders, so a later "load more" has to resume
 * after the last one instead of re-fetching pages that are already on screen
 * (which would return nothing new and look like a dead press).
 */
data class TagRailPage(
    val items: List<StudioItem>,
    val hasMore: Boolean,
    val nextPage: Int = 2
)


data class ResolvedEpisode(
    val streamId: String,
    val episodeNumber: Int,
    val name: String?,
    val overview: String?,
    val thumbnail: String?,
    val runtimeMinutes: Int?,
    val airDate: String?,
    val voteAverage: Double?
)

/**
 * The season list with any episode a source never really named dropped.
 *
 * [TmdbRepository.getSeasonEpisodes] already refuses such a row while it
 * builds the list from TMDB, but both of that call's caches outlive the build
 * that filled them - a memory entry for twelve hours and a disk entry for a
 * week, the latter written into the app's own database. A list cached before
 * the rule existed therefore kept handing its "episode 0" row back: a season
 * browser of blank chips reading EPISODE 0, and a resume row numbered 0
 * matched onto that row, so the show resolved straight back to E00 instead of
 * the next episode TMDB actually knows about. That is why the report came
 * back after the rule itself had been fixed - the data outlived the fix.
 * Re-applying the filter on the way OUT is what makes the rule reach a season
 * list cached before it.
 *
 * Top level rather than a member so a test can reach it without standing up a
 * repository, the same way the rule it applies is pinned (see
 * EpisodeNumberingTest).
 */
internal fun List<ResolvedEpisode>.namedEpisodesOnly(): List<ResolvedEpisode> =
    filter { episode -> namedEpisodeNumber(episode.episodeNumber) != null }

/**
 * Whether a season's episode list may be treated as TMDB's ANSWER for that
 * (show, season).
 *
 * An empty list is not one. Continue Watching scans every season of a show to
 * find the next episode and to count watched/total, and the Detail screen
 * fetches the season the viewer is looking at - both through
 * [TmdbRepository.getSeasonEpisodes]. A lookup that never answered (a rate
 * limit, a dropped connection, an API key that had not loaded) and a season
 * TMDB genuinely has no episodes for both come out as an empty list, and both
 * were stored under the same twelve-hour memory / seven-day disk TTLs the real
 * ones get. One interrupted refresh therefore pinned "this show has no
 * episodes" for a week: the season browser read "No episodes found for this
 * season." on EVERY season of that show, and the Continue Watching walk could
 * not resolve a single episode - so the card for a show the viewer was
 * part-way through fell through to its last-ditch pair and read "S1 · E1".
 * Both are the same report.
 *
 * The rule is therefore: an empty answer is a MISS - never cached, never
 * served - and the lookup is retried, so a bad answer cannot outlive the
 * session that produced it. Top level and pure so a test can pin it without
 * standing up a repository (see NamedSeasonEpisodesTest).
 */
internal fun seasonEpisodesAreAnAnswer(
    episodes: List<ResolvedEpisode>?
): Boolean = !episodes.isNullOrEmpty()

class TmdbRepository private constructor(context: Context) :
    MemoryPressure.Releasable {

    // Process-wide singleton (see [Companion.getInstance]): Retrofit +
    // Moshi(KotlinJsonAdapterFactory) are heavyweight reflection setups and
    // per-instance detail/episode/imdb caches fragmented across ~18 call
    // sites — the player alone used to build several instances per
    // session. One shared instance means one Retrofit stack and warm caches
    // for the whole process.
    //
    // Everything heavyweight below is `by lazy` on purpose. This singleton is
    // constructed on the MAIN thread — the first ViewModel that needs it is
    // built during the first composition — so building Retrofit and Moshi here
    // put their reflection on the first-frame path. Constructing the object now
    // costs only the cheap fields; warmUpReflectionStack() builds the rest on IO.
    private val moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    internal val api: TmdbApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(TmdbHttpClient.get())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApiService::class.java)
    }

    internal val apiKey = BuildConfig.TMDB_API_KEY
    private val appContext = context.applicationContext

    // Caps parallel TMDB availability lookups for the digital-release filter.
    private val availabilitySemaphore = Semaphore(permits = 6)

    // Vote floors per rail kind. RECENT is light (newest-first, just skip
    // no-title junk); POPULAR and TOP RATED use 10 so smaller catalogs
    // (Tubi, Pluto, Crunchyroll, keywords, mid-size studios) keep real rows
    // instead of starving to empty.
    internal val minVoteCount = 10

    /** TOP-RATED / "Most Voted" rail floor (sort is vote_count.desc). */
    internal val minTopRatedVoteCount = 10

    /**
     * RECENT-rail floor: none. These rails are strictly newest-first, and a
     * vote floor can only punch holes in a chronological list — a brand-new
     * title has almost no votes yet, which is exactly the title a "recent"
     * rail exists to show. (History's whole 2026 slate sat behind the old
     * floor of 5.) Junk without artwork is dropped by [finishRailPage]
     * instead, which is what the floor was really guarding against — see
     * [dropPosterless].
     */
    internal val minRecentVoteCount = 0

    /**
     * Rail depth policy for the Search browse chips' screens (genre,
     * keyword, network, studio, service, decade, cross-genre). TMDB returns
     * 20 rows per discover page and the rail loaders used to render exactly
     * that first page — so after the artwork and availability filters a
     * chip's rails showed barely a dozen rows and read as a half-empty
     * catalog. Page 1 now keeps pulling until a rail reaches
     * [RAIL_DEPTH_TARGET_ITEMS] rows, capped at [RAIL_DEPTH_MAX_PAGE] so
     * opening one screen costs at most a few requests per rail.
     */
    internal val RAIL_DEPTH_TARGET_ITEMS = 140
    internal val RAIL_DEPTH_MAX_PAGE = 6

    internal val today: String
        get() = LocalDate.now().toString()

    // Lazy for the same reason, and in practice first touched by the prune
    // coroutines in init — which run on IO — rather than by whatever thread
    // happened to construct this object.
    private val database by lazy { WatchHistoryDatabase.getInstance(context) }
    private val imdbResolutionDao by lazy { database.imdbResolutionDao() }
    private val tmdbJsonCacheDao: TmdbJsonCacheDao by lazy { database.tmdbJsonCacheDao() }

    // ConcurrentHashMap: continue-watching lookups run several rows in
    // parallel, so these caches are written from multiple coroutines.
    private val detailCache = ConcurrentHashMap<String, Pair<Long, TmdbDetail?>>()

    // In-flight detail fetches, keyed like [detailCache]. Home's enrichment
    // pass and a focus-driven hero resolve routinely ask for the SAME title at
    // once; without this the second caller missed the cache (the first had not
    // filled it yet) and fired a duplicate request. A concurrent caller now
    // awaits the pending fetch instead.
    private val detailFetchInFlight =
        ConcurrentHashMap<String, CompletableDeferred<TmdbDetail?>>()
    private val detailCacheTtlMs = 12L * 60L * 60L * 1000L
    private val detailCacheDiskTtlMs = 30L * 24L * 60L * 60L * 1000L

    /**
     * Rail/resume projections, keyed exactly like [detailCache] but never
     * holding anything the Detail screen or a hero strip reads.
     *
     * A separate map rather than one map of mixed shapes, because the two
     * answer different questions: a slim entry that landed in [detailCache]
     * would be handed to DetailViewModel as the title's detail, and the cast
     * list, trailer and "More like this" rail would all quietly come back
     * empty. Keeping the slim path with nowhere to write that is what makes the
     * split safe without every call site having to know which one it wants.
     */
    private val railDetailCache = ConcurrentHashMap<String, Pair<Long, TmdbDetail?>>()

    /**
     * Disk key for a cached detail response.
     *
     * Versioned (`_en`) because the detail request now restricts its appended
     * `images` to English/textless art (see [TmdbApiService.getMovie]). The
     * cache lives 30 days, so without a new prefix a device would keep serving
     * the foreign logos and title-burned backdrops it cached before the filter
     * existed - the fix would look like it did nothing. A key bump drops them
     * all at once instead; the orphaned `detail:` rows age out of the shared
     * cache through the same cleanup that trims every other stale entry.
     */
    private fun detailDiskKey(key: String): String = "detail_en:$key"

    /**
     * Disk key for the rail/resume projection of the same response.
     *
     * Versioned for the same reason as [detailDiskKey]: the projection is a
     * different payload behind the same title, and reusing the `detail_en`
     * prefix would make a slim row indistinguishable from a full one that
     * simply never had a cast list.
     */
    private fun railDetailKey(key: String): String = "railenrich_en:$key"

    private val seasonEpisodesCache =
        ConcurrentHashMap<String, Pair<Long, List<ResolvedEpisode>>>()
    private val seasonEpisodesCacheTtlMs = 12L * 60L * 60L * 1000L
    private val seasonEpisodesDiskTtlMs = 7L * 24L * 60L * 60L * 1000L

    /**
     * Short-TTL memory cache for the browse rails' RAW per-page rows.
     *
     * Opening a browse screen (genre / network / studio / decade / tag)
     * fires six rails at once, and every rail deepens through up to
     * [RAIL_DEPTH_MAX_PAGE] TMDB pages - so each open was ~36 fresh requests,
     * and coming back to the screen you were just on (Detail and back, or a
     * genre chip toggled twice) cost exactly as much as the first visit. The
     * raw rows are reusable for a few minutes: these are the same lists for
     * every viewer, so a slightly stale popularity order is invisible.
     *
     * The RAW rows are cached rather than the finished page, on purpose:
     * [finishRailPage] applies the digital-release filter and the active
     * profile's kids ceiling, so caching a finished rail would keep serving
     * one profile's filtered page to the next.
     */
    private val railPageCache =
        ConcurrentHashMap<String, Pair<Long, List<StudioItem>>>()

    private val railPageTtlMs = 5L * 60L * 1000L

    /** Bound so a long browsing session cannot grow the map without end. */
    private val railPageCacheMaxEntries = 512

    /**
     * One raw rail page, served from [railPageCache] while it is fresh.
     *
     * Only non-empty pages are stored: an empty one is either the genuine end
     * of a short catalog (cheap to re-ask) or a failed request, and caching a
     * failure would blank that rail until the TTL expired.
     */
    private suspend fun cachedRailPage(
        cacheKey: String?,
        page: Int,
        load: suspend (Int) -> List<StudioItem>
    ): List<StudioItem> {
        if (cacheKey == null) return load(page)

        val key = "$cacheKey|$page"
        val now = System.currentTimeMillis()

        railPageCache[key]?.let { (storedAt, items) ->
            if (now - storedAt < railPageTtlMs) return items
        }

        val items = load(page)

        if (items.isNotEmpty()) {
            if (railPageCache.size > railPageCacheMaxEntries) {
                // Evict the OLDEST pages, not the whole map. clear() threw away
                // up to 511 still-fresh pages because ONE overflowed the cap,
                // so the next rail the viewer opened - however recently it had
                // been loaded - paid the full round-trip again. Same oldest-first
                // trim as the season-episode cache (see HomeViewModel).
                railPageCache.entries
                    .sortedBy { it.value.first }
                    .take(railPageCache.size - railPageCacheMaxEntries)
                    .forEach { railPageCache.remove(it.key) }
            }
            railPageCache[key] = now to items
        }

        return items
    }

    /**
     * Short-TTL memory cache for the RAW `/collection/{id}` response.
     *
     * The Collection screen is opened from a title's "Belongs to collection"
     * row - and building that row has just fetched this exact payload. Without
     * a cache the screen paid a second identical round-trip for data already in
     * hand, holding its whole page behind a skeleton until it returned.
     *
     * The RAW detail is what is stored, not the finished result: [getCollection]
     * applies the active profile's kids ceiling and the digital-release filter
     * below it, so caching the filtered version would hand one profile's page to
     * the next.
     */
    private val collectionCache =
        ConcurrentHashMap<Int, Pair<Long, TmdbCollectionDetail>>()

    private val collectionCacheTtlMs = 10L * 60L * 1000L

    /** Bound so a long browse cannot grow the map without end. */
    private val collectionCacheMaxEntries = 64

    private val detailJsonAdapter: JsonAdapter<TmdbDetail> by lazy {
        moshi.adapter(TmdbDetail::class.java)
    }

    private val reviewsJsonAdapter: JsonAdapter<TmdbReviews> by lazy {
        moshi.adapter(TmdbReviews::class.java)
    }

    private val seasonEpisodesJsonAdapter: JsonAdapter<List<ResolvedEpisode>> by lazy {
        moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                ResolvedEpisode::class.java
            )
        )
    }

    private val genresJsonAdapter: JsonAdapter<List<TmdbGenre>> by lazy {
        moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                TmdbGenre::class.java
            )
        )
    }

    private val imdbResolutionMemoryCache =
        ConcurrentHashMap<String, Pair<Long, String?>>()

    /** Reverse of [imdbResolutionMemoryCache]: the TMDB id for an IMDB id,
     *  keyed "<type>::<imdb>". Same TTL and persistence table. */
    private val tmdbResolutionMemoryCache =
        ConcurrentHashMap<String, Pair<Long, Int?>>()

    /**
     * Per-page TMDB reviews for a title, keyed "<tmdbId>:<type>:<page>".
     *
     * Detail's enrichment probes page 2 and then pages 3..N on every open,
     * and reviews change on the order of days, so re-fetching them each visit
     * was pure latency. Hits only -- never a null (a failed probe must be
     * retried, not pinned). Bounded with the rest via [pruneMemoryCaches].
     */
    private val reviewsCache =
        ConcurrentHashMap<String, Pair<Long, TmdbReviews>>()
    private val reviewsCacheTtlMs = 12L * 60L * 60L * 1000L
    private val reviewsCacheDiskTtlMs = 7L * 24L * 60L * 60L * 1000L

    private val imdbResolutionTtlMs = 30L * 24L * 60L * 60L * 1000L

    private var movieGenresCache: List<TmdbGenre>? = null
    private var tvGenresCache: List<TmdbGenre>? = null

    private val cachePruned = AtomicBoolean(false)
    private val jsonCachePruned = AtomicBoolean(false)

    /** Writes since the last JSON-cache budget check (see [cacheJson]). */
    private val jsonCacheWrites = AtomicInteger(0)

    init {
        pruneImdbCacheOnce()
        pruneJsonCacheOnce()
        // Registered so the system (or the player opening fullscreen) can take
        // these back the way it takes Coil's bitmaps and the guide's snapshots.
        MemoryPressure.register(this)
        // Constructing this object is now cheap; this is where the expensive
        // half actually gets built, and it does it off the main thread.
        warmUpReflectionStack()
    }

    /**
     * Builds the reflection-heavy half of this class on IO.
     *
     * The cost has to be paid somewhere. Paying it here is deliberate rather
     * than leaving it to first use, because "first use" is a `viewModelScope`
     * coroutine — `Dispatchers.Main.immediate` — so on-demand initialization
     * would move the same work to the main thread a few milliseconds later,
     * and delay the first real data instead of the first frame.
     *
     * Nothing here depends on user action, so building it speculatively is
     * free. A caller that does beat this coroutine waits on the lazy's lock for
     * whatever is left of the build — never more than it would have spent doing
     * the work itself. The database and its DAOs are absent because the prune
     * coroutines above already force them, on this same dispatcher.
     */
    private fun warmUpReflectionStack() {
        BackgroundWork.launch {
            runCatching {
                // Passing each lazy to listOf() is what forces it; the result is
                // discarded, which is why it is not assigned to anything.
                listOf(
                    moshi,
                    api,
                    detailJsonAdapter,
                    seasonEpisodesJsonAdapter,
                    genresJsonAdapter
                )
            }
        }
    }

    /**
     * Caps for the in-memory caches above.
     *
     * A long browse session opens hundreds of titles, and until now nothing
     * removed an entry before its TTL was *consulted* -- the maps only grew.
     * Each entry is small, but [detailCache] holds whole enriched responses
     * (cast, images, videos, recommendations) and this app has already died at
     * the heap limit with a browse session live (see MemoryPressure), so these
     * are bounded the way Coil's caches are. The TTLs still apply on top of the
     * cap: an entry evicted here is rebuilt from the JSON cache table on disk,
     * or from one request.
     */
    private val MAX_DETAIL_ENTRIES = 150
    private val MAX_SEASON_ENTRIES = 200
    private val MAX_RESOLUTION_ENTRIES = 500
    private val MAX_REVIEW_ENTRIES = 300

    /**
     * Keeps all four in-memory caches bounded.
     *
     * Called from the lookup paths rather than from the dozen write sites:
     * growth can only happen through a lookup, and the size check makes this
     * free while a cache is under its cap. The eviction itself is [evictOldest]
     * (data.memory), shared with the trailer source cache so the sizing rule is
     * written once.
     */
    private fun pruneMemoryCaches() {
        // Every map here keeps its timestamp in Pair.first, which is the stamp
        // evictOldest orders by.
        evictOldest(detailCache, { it.first }, MAX_DETAIL_ENTRIES)
        evictOldest(railDetailCache, { it.first }, MAX_DETAIL_ENTRIES)
        evictOldest(seasonEpisodesCache, { it.first }, MAX_SEASON_ENTRIES)
        evictOldest(imdbResolutionMemoryCache, { it.first }, MAX_RESOLUTION_ENTRIES)
        evictOldest(tmdbResolutionMemoryCache, { it.first }, MAX_RESOLUTION_ENTRIES)
        evictOldest(reviewsCache, { it.first }, MAX_REVIEW_ENTRIES)
    }

    /**
     * Drops every in-memory cache. All of them are rebuilt from the JSON cache
     * table on disk (30-day TTL) or from one request, so the worst case cost is
     * a database read -- which is what makes them worth giving back when the
     * heap is tight, like the guide's snapshots.
     */
    override fun releaseCaches() {
        detailCache.clear()
        railDetailCache.clear()
        seasonEpisodesCache.clear()
        imdbResolutionMemoryCache.clear()
        tmdbResolutionMemoryCache.clear()
        reviewsCache.clear()
    }

    /**
     * Each cache against its own cap, for the diagnostics dump.
     *
     * The cap is printed next to the size on purpose: a cache that sits at its
     * ceiling all session is a working cap, while one that climbs past it means
     * [pruneMemoryCaches] is not being reached — and those two read identically
     * if only the size is shown.
     */
    override fun cacheStats(): String =
        "tmdb: detail=${detailCache.size}/$MAX_DETAIL_ENTRIES" +
            " rail=${railDetailCache.size}/$MAX_DETAIL_ENTRIES" +
            " season=${seasonEpisodesCache.size}/$MAX_SEASON_ENTRIES" +
            " imdbRes=${imdbResolutionMemoryCache.size}/$MAX_RESOLUTION_ENTRIES" +
            " tmdbRes=${tmdbResolutionMemoryCache.size}/$MAX_RESOLUTION_ENTRIES" +
            " reviews=${reviewsCache.size}/$MAX_REVIEW_ENTRIES"

    private fun pruneImdbCacheOnce() {
        if (cachePruned.compareAndSet(false, true)) {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
            BackgroundWork.launch {
                runCatchingCancellable {
                    imdbResolutionDao.deleteOlderThan(cutoff)
                }
            }
        }
    }

    /**
     * Ages the JSON cache and enforces its byte budget, once per process.
     *
     * The table used to be bounded by age alone, which is not a bound at all:
     * `updatedAt` is refreshed by every re-fetch, so the titles the user keeps
     * looking at never expired, and nothing capped the sum of the rest. It
     * reached gigabytes on real devices (see [TmdbJsonCacheMaintenance] for
     * the measurements and the third reason - a file that never gives pages
     * back). This is the launch-time pass; [cacheJson] keeps it bounded while
     * the app is up.
     */
    private fun pruneJsonCacheOnce() {
        if (jsonCachePruned.compareAndSet(false, true)) {
            BackgroundWork.launch {
                runCatchingCancellable { TmdbJsonCacheMaintenance.trim(tmdbJsonCacheDao) }
            }
        }
    }

    /**
     * The single write path for the JSON cache: stores one payload and, every
     * [JSON_CACHE_TRIM_EVERY_WRITES] writes, re-checks the table against its
     * budget.
     *
     * Every write in this class goes through here rather than calling the DAO
     * directly, because "which call sites remember to maintain the cache" is
     * not a property anyone can review - the budget has to be enforced by the
     * write path itself. Counting rather than trimming on every write is what
     * keeps that free: a trim reads the whole size index, and at the measured
     * 65-280 KB per row, one check per 32 writes leaves at most a few
     * megabytes of drift against a 64 MB budget.
     *
     * A failed write is swallowed exactly as the inline `runCatching` blocks
     * it replaces did: this is a cache, and the caller already holds the
     * object that was just fetched.
     */
    private suspend fun cacheJson(key: String, json: String, now: Long) {
        runCatchingCancellable {
            tmdbJsonCacheDao.upsert(
                TmdbJsonCacheEntity(key = key, json = json, updatedAt = now)
            )
        }
        if (jsonCacheWrites.incrementAndGet() >= JSON_CACHE_TRIM_EVERY_WRITES) {
            jsonCacheWrites.set(0)
            BackgroundWork.launch {
                runCatchingCancellable { TmdbJsonCacheMaintenance.trim(tmdbJsonCacheDao) }
            }
        }
    }

    /**
     * Resolves raw Stremio-style ids to a full TMDB detail record.
     *
     * Catalogs don't just hand out Imdb ids: AIOStreams and search emit
     * "tmdb:12345", TVDB-sourced addons (AIOMetadata, BingeCat) emit
     * "tvdb:12345", some catalogs emit bare numeric ids, and history rows
     * can carry an episode suffix ("tt1234:1:2"). Only the show-level
     * id matters here, and each flavor is resolved accordingly:
     *  - tmdb:/bare-numeric -> fetch {tv|movie}/{id} directly
     *  - tvdb:             -> TMDB /find with external_source=tvdb_id
     *  - else (tt...)      -> TMDB /find with external_source=imdb_id
     */
    suspend fun fetchEnrichedMeta(rawId: String, type: String): TmdbDetail? {
        if (apiKey.isBlank()) return null

        val normalizedType = normalizeType(type)
        val trimmed = rawId.trim()
        val lower = trimmed.lowercase()

        // Episode-suffixed ids only arrive from history rows; keep the prefix.
        val tmdbId = when {
            lower.startsWith("tmdb:") ->
                trimmed.substringAfter(':').substringBefore(':').trim().toIntOrNull()
            trimmed.all(Char::isDigit) -> trimmed.toIntOrNull()
            else -> null
        }
        if (tmdbId != null) {
            return fetchDetailByTmdbId(tmdbId, normalizedType)
        }

        return if (lower.startsWith("tvdb:")) {
            val tvdbId = trimmed.substringAfter(':').substringBefore(':').trim()
            if (tvdbId.isBlank()) null else findAndFetch(tvdbId, "tvdb_id", normalizedType)
        } else {
            val imdbId = trimmed.substringBefore(':')
            if (imdbId.isBlank()) null else findAndFetch(imdbId, "imdb_id", normalizedType)
        }
    }

    private suspend fun findAndFetch(
        externalId: String,
        externalSource: String,
        normalizedType: String
    ): TmdbDetail? {
        val found =
            runCatchingCancellable { api.find(externalId, apiKey, externalSource) }.getOrNull()
                ?: return null
        val tmdbId =
            if (normalizedType == "series") {
                found.tvResults.firstOrNull()?.id
            } else {
                found.movieResults.firstOrNull()?.id
            } ?: return null
        return fetchDetailByTmdbId(tmdbId, normalizedType)
    }

    private suspend fun fetchDetailByTmdbId(
        tmdbId: Int,
        normalizedType: String
    ): TmdbDetail? {
        if (normalizedType == "series") {
            return runCatchingCancellable {
                api.getTv(tmdbId, apiKey)
            }.getOrNull()
        }
        if (normalizedType == "movie") {
            return runCatchingCancellable {
                api.getMovie(tmdbId, apiKey)
            }.getOrNull()
        }
        // Unknown type (anime, custom collection, ...): try series first,
        // fall back to movie. Both failures are silently swallowed.
        return runCatchingCancellable {
            api.getTv(tmdbId, apiKey)
        }.getOrNull()
            ?: runCatchingCancellable {
                api.getMovie(tmdbId, apiKey)
            }.getOrNull()
    }

    /**
     * The enriched TMDB detail for one raw id: in-memory cache, then the disk
     * cache, then the network.
     *
     * [full] picks WHICH projection is served and persisted. It is a parameter
     * rather than a second function because the callers differ in that one
     * thing only:
     *
     *  - the default (`full = false`) is the rail/resume/badge projection: see
     *    [railProjection] for what it keeps and drops, and for the helpers that
     *    nearly made this a bug. Rails, browse grids, KB folders, resume rows,
     *    next-episode walks and the Kids Mode ceiling all land here, and none
     *    of them read the bulk.
     *  - `full = true` is for the surfaces that DO read it: the Detail screen
     *    (cast, trailer, "More like this", keywords, reviews), the Home and
     *    KB-folder heroes (cast line + trailer) and anime detection (keywords).
     *    It reads and writes the full row exactly as before, so a slim row can
     *    never reach them.
     *
     * A fetch is always the FULL response — one API call serves both — so the
     * full in-memory cache is warmed either way. A slim request therefore keeps
     * the hero instant for the rest of the session, and only a process restart
     * pays the extra request when the user finally opens the title.
     */
    suspend fun fetchEnrichedMetaCached(
        imdbId: String,
        type: String,
        full: Boolean = false
    ): TmdbDetail? {
        val key = "${normalizeType(type)}:$imdbId"
        val now = System.currentTimeMillis()
        pruneMemoryCaches()

        // In-memory TTL cache (fast path for the current session). The full
        // cache is consulted first by BOTH paths, because a full object answers
        // a slim question: a title the Detail screen already loaded serves its
        // own rail card with no extra work.
        val cached = detailCache[key]
        if (cached != null && now - cached.first < detailCacheTtlMs) {
            return cached.second
        }
        if (!full) {
            val railCached = railDetailCache[key]
            if (railCached != null && now - railCached.first < detailCacheTtlMs) {
                return railCached.second
            }
        }

        // Disk cache so resolved metadata survives restarts. Full first, for the
        // same reason; the slim row is only consulted when there is no full one.
        val diskKey = detailDiskKey(key)
        val diskCached = runCatchingCancellable {
            tmdbJsonCacheDao.getByKey(diskKey)
        }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < detailCacheDiskTtlMs) {
            val parsed = runCatching {
                detailJsonAdapter.fromJson(diskCached.json)
            }.getOrNull()
            if (parsed != null) {
                detailCache[key] = now to parsed
                railDetailCache[key] = now to parsed
                return parsed
            }
        }
        if (!full) {
            val railKey = railDetailKey(key)
            val railDiskCached = runCatchingCancellable {
                tmdbJsonCacheDao.getByKey(railKey)
            }.getOrNull()
            if (railDiskCached != null && now - railDiskCached.updatedAt < detailCacheDiskTtlMs) {
                val parsed = runCatching {
                    detailJsonAdapter.fromJson(railDiskCached.json)
                }.getOrNull()
                if (parsed != null) {
                    // Only the rail map: this object has no cast, trailer or
                    // recommendations, and must never answer a `full` caller.
                    railDetailCache[key] = now to parsed
                    return parsed
                }
            }
        }

        val result = runCatchingCancellable { fetchEnrichedMeta(imdbId, type) }.getOrNull()
        // A MISS is deliberately not cached. A transient TMDB failure (or an
        // add-on-only title with no TMDB record) used to pin a null in the 12h
        // memory cache, after which every caller - the Detail screen included -
        // got "no TMDB metadata" for the rest of the session and could never
        // retry. Only a real answer is remembered, in memory and on disk.
        if (result != null) {
            detailCache[key] = now to result
            // The slim memory entry holds the PROJECTION, not the response:
            // rails never read the bulk, and keeping a full copy in both maps
            // would double this class's retained heap for no gain. (The disk
            // row is already the projection in the slim case.)
            railDetailCache[key] = now to result.railProjection()
            if (full) {
                cacheJson(diskKey, detailJsonAdapter.toJson(result), now)
            } else {
                cacheJson(
                    railDetailKey(key),
                    detailJsonAdapter.toJson(result.railProjection()),
                    now
                )
            }
        }
        return result
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? {
        if (apiKey.isBlank()) return null

        val normalizedType = normalizeType(type)
        val key = imdbResolutionKey(tmdbId, normalizedType)
        val now = System.currentTimeMillis()

        imdbResolutionMemoryCache[key]?.let { (cachedAt, imdbId) ->
            if (now - cachedAt < imdbResolutionTtlMs) {
                return imdbId
            }
        }

        val diskCached = runCatchingCancellable { imdbResolutionDao.getByKey(key) }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < imdbResolutionTtlMs) {
            imdbResolutionMemoryCache[key] = diskCached.updatedAt to diskCached.imdbId
            return diskCached.imdbId
        }

        val imdbId = runCatchingCancellable {
            val ext = if (normalizedType == "series") {
                api.getTvExternalIds(tmdbId, apiKey)
            } else {
                api.getMovieExternalIds(tmdbId, apiKey)
            }
            ext.imdbId
        }.getOrNull()

        if (!imdbId.isNullOrBlank()) {
            imdbResolutionMemoryCache[key] = now to imdbId

            runCatchingCancellable {
                imdbResolutionDao.upsert(
                    ImdbResolutionEntity(
                        key = key,
                        tmdbId = tmdbId,
                        mediaType = normalizedType,
                        imdbId = imdbId,
                        updatedAt = now
                    )
                )
            }
        }

        return imdbId
    }

    /**
     * Resolves the IMDB id of a title back to its TMDB id (the reverse of
     * [resolveImdbId]), caching both in memory and in the resolution table so
     * a later [resolveImdbId] is free too.
     *
     * Needed by callers that only ever hold the "tt..." form (playback
     * history, the watched-override twins) and still have to name the same
     * title's "tmdb:<n>" flavor.
     */
    suspend fun resolveTmdbId(imdbId: String, type: String): Int? {
        val trimmedId = imdbId.trim()
        if (apiKey.isBlank() || !trimmedId.startsWith("tt")) return null

        val normalizedType = normalizeType(type)
        val cacheKey = "$normalizedType::$trimmedId"
        val now = System.currentTimeMillis()

        tmdbResolutionMemoryCache[cacheKey]?.let { (cachedAt, tmdbId) ->
            if (now - cachedAt < imdbResolutionTtlMs) {
                return tmdbId
            }
        }

        val diskCached = runCatchingCancellable { imdbResolutionDao.getByImdbId(trimmedId, normalizedType) }
            .getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < imdbResolutionTtlMs) {
            tmdbResolutionMemoryCache[cacheKey] = diskCached.updatedAt to diskCached.tmdbId
            imdbResolutionMemoryCache[
                imdbResolutionKey(diskCached.tmdbId, normalizedType)
            ] = diskCached.updatedAt to diskCached.imdbId
            return diskCached.tmdbId
        }

        val tmdbId = runCatchingCancellable {
            val found = api.find(trimmedId, apiKey, "imdb_id")
            if (normalizedType == "series") {
                found.tvResults.firstOrNull()?.id
            } else {
                found.movieResults.firstOrNull()?.id
            }
        }.getOrNull()?.takeIf { it > 0 } ?: return null

        tmdbResolutionMemoryCache[cacheKey] = now to tmdbId
        imdbResolutionMemoryCache[
            imdbResolutionKey(tmdbId, normalizedType)
        ] = now to trimmedId

        runCatchingCancellable {
            imdbResolutionDao.upsert(
                ImdbResolutionEntity(
                    key = imdbResolutionKey(tmdbId, normalizedType),
                    tmdbId = tmdbId,
                    mediaType = normalizedType,
                    imdbId = trimmedId,
                    updatedAt = now
                )
            )
        }

        return tmdbId
    }

    /**
     * Records an already-known IMDB<->TMDB pair in the resolution cache, both
     * ways, exactly as a successful [resolveTmdbId] would.
     *
     * [resolveTmdbId] learns a pair by asking TMDB; this is the same
     * bookkeeping for a pair a caller ALREADY holds - so a later lookup in
     * either direction answers from memory or the disk table instead of the
     * network. Callers that resolve a "tt..." id through a cheaper path (the
     * enriched-meta cache) use this to leave the same trail behind them, which
     * is what lets a later "tmdb:<n>" route find the title's history offline
     * (see PlaybackHistoryIds).
     */
    suspend fun recordResolution(imdbId: String, tmdbId: Int, type: String) {
        val trimmedId = imdbId.trim()
        if (!trimmedId.startsWith("tt") || tmdbId <= 0) return

        val normalizedType = normalizeType(type)
        val now = System.currentTimeMillis()
        val key = imdbResolutionKey(tmdbId, normalizedType)

        tmdbResolutionMemoryCache["$normalizedType::$trimmedId"] = now to tmdbId
        imdbResolutionMemoryCache[key] = now to trimmedId

        runCatchingCancellable {
            imdbResolutionDao.upsert(
                ImdbResolutionEntity(
                    key = key,
                    tmdbId = tmdbId,
                    mediaType = normalizedType,
                    imdbId = trimmedId,
                    updatedAt = now
                )
            )
        }
    }

    suspend fun getPerson(personId: Int): TmdbPersonDetail? {
        if (apiKey.isBlank()) return null
        val loaded = api.getPerson(personId, apiKey) ?: return null
        // Kids Mode: trim the combined filmography to the active ceiling at
        // the source. One chokepoint covers every consumer — the actor
        // screen, detail person chips, and KB PERSON/DIRECTOR/WRITER
        // rails (which read combinedCredits straight off this payload).
        val ceiling = kidsMaxAge()
        val credits = loaded.combinedCredits
        return if (ceiling != null && credits != null) {
            loaded.copy(
                combinedCredits = TmdbCombinedCredits(
                    cast = kidsFilterPersonCredits(credits.cast),
                    crew = kidsFilterPersonCredits(credits.crew)
                )
            )
        } else {
            loaded
        }
    }

    suspend fun searchPerson(query: String): List<TmdbSearchPersonResult> {
        if (apiKey.isBlank()) return emptyList()
        return runCatchingCancellable { api.searchPerson(query, apiKey).results }
            .getOrDefault(emptyList())
    }

    suspend fun searchCompany(query: String): List<TmdbSearchStudioResult> {
        if (apiKey.isBlank()) return emptyList()
        return runCatchingCancellable { api.searchCompany(query, apiKey).results }
            .getOrDefault(emptyList())
    }

    /*
     * TMDB-native title search. This is the primary title source for the
     * search screen so it works without any add-on installed (e.g. Cinemeta);
     * add-on catalog search is only a supplement layered on top by the
     * ViewModel. Split into movies/TV (not multi-search) so every result
     * carries a reliable type.
     */
    suspend fun searchMovies(query: String): List<TmdbSearchTitleResult> {
        if (apiKey.isBlank()) return emptyList()
        val results =
            runCatchingCancellable { api.searchMovie(query, apiKey).results }
                .getOrDefault(emptyList())

        if (!isDigitalFilterEnabled()) return results

        return filterByHomeAvailability(results) {
            it.id to "movie"
        }
    }

    suspend fun searchTv(query: String): List<TmdbSearchTitleResult> {
        if (apiKey.isBlank()) return emptyList()
        return runCatchingCancellable { api.searchTv(query, apiKey).results }
            .getOrDefault(emptyList())
    }

    suspend fun getMovieGenres(): List<TmdbGenre> {
        if (apiKey.isBlank()) return emptyList()
        movieGenresCache?.let { return it }
        readGenresFromDisk("movie")?.let {
            movieGenresCache = it
            return it
        }
        return runCatchingCancellable { api.getMovieGenreList(apiKey).genres }
            .getOrDefault(emptyList())
            .also { movieGenresCache = it }
            .also { writeGenresToDisk("movie", it) }
    }

    suspend fun getTvGenres(): List<TmdbGenre> {
        if (apiKey.isBlank()) return emptyList()
        tvGenresCache?.let { return it }
        readGenresFromDisk("tv")?.let {
            tvGenresCache = it
            return it
        }
        return runCatchingCancellable { api.getTvGenreList(apiKey).genres }
            .getOrDefault(emptyList())
            .also { tvGenresCache = it }
            .also { writeGenresToDisk("tv", it) }
    }

    private suspend fun readGenresFromDisk(key: String): List<TmdbGenre>? {
        val now = System.currentTimeMillis()
        val diskCached = runCatchingCancellable {
            tmdbJsonCacheDao.getByKey("genres:$key")
        }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < detailCacheDiskTtlMs) {
            return runCatching {
                genresJsonAdapter.fromJson(diskCached.json)
            }.getOrNull()
        }
        return null
    }

    private suspend fun writeGenresToDisk(key: String, genres: List<TmdbGenre>) {
        cacheJson("genres:$key", genresJsonAdapter.toJson(genres), System.currentTimeMillis())
    }

    suspend fun getCollection(collectionId: Int): TmdbCollectionDetail? {
        if (apiKey.isBlank()) return null

        val now = System.currentTimeMillis()

        val detail = collectionCache[collectionId]
            ?.takeIf { (storedAt, _) -> now - storedAt < collectionCacheTtlMs }
            ?.second
            ?: runCatchingCancellable { api.getCollection(collectionId, apiKey) }
                .getOrNull()
                ?.also { fetched ->
                    if (collectionCache.size > collectionCacheMaxEntries) {
                        collectionCache.clear()
                    }
                    collectionCache[collectionId] = now to fetched
                }
                ?: return null

        // Kids Mode first: a kids profile never sees franchise pages whose
        // parts are rated above its ceiling (and the whole page drops when
        // no part survives).
        val kidsParts = if (kidsMaxAge() == null) {
            detail.parts
        } else {
            kidsFilter(detail.parts) { it.id to "movie" }
        }
        if (kidsParts.isEmpty() && detail.parts.isNotEmpty()) {
            return detail.copy(parts = emptyList())
        }

        if (!isDigitalFilterEnabled()) {
            return detail.copy(parts = kidsParts)
        }

        val filteredParts =
            filterByHomeAvailability(kidsParts) {
                it.id to "movie"
            }

        return detail.copy(parts = filteredParts)
    }

    // ------------------------------------------------------------------
    // KB collections: raw TMDB source loaders (discover / list /
    // collection / company / network). A null return means the request
    // failed; an empty list means the query legitimately has no results.
    // ------------------------------------------------------------------

    /** Generic /discover with the full KB filter-builder parameter set. */
    suspend fun discoverKB(
        mediaType: String,
        page: Int = 1,
        sortBy: String? = null,
        filters: com.kennyb1201.kbstream.data.kb.KBFilters? = null
    ): List<TmdbDiscoverItem>? {
        if (apiKey.isBlank()) return null
        val isTv = mediaType.lowercase() == "tv"
        val yearRange = filters?.yearRange()
        // Explicit date bounds (e.g. the rolling RECENT window) — only used
        // when no year range is set, so decade/decade-style year filters win.
        val dateGte = yearRange?.first ?: filters?.releaseDateGte
        val dateLte = yearRange?.second ?: filters?.releaseDateLte
        // with_release_type is read against a REGION, and the only country this
        // app ever asks about is the viewer's own - the same one the
        // watch-provider chips use. Sent only with a release type, so a catalog
        // that has none keeps asking exactly what it asked before.
        val releaseRegion = filters?.withReleaseType?.let { filters.watchRegion }
        return runCatchingCancellable {
            if (isTv) {
                api.discoverTvGeneric(
                    apiKey = apiKey,
                    page = page,
                    sortBy = sortBy,
                    withGenres = filters?.withGenres,
                    withoutGenres = filters?.withoutGenres,
                    withKeywords = filters?.withKeywords,
                    withoutKeywords = filters?.withoutKeywords,
                    withCompanies = filters?.withCompanies,
                    withoutCompanies = filters?.withoutCompanies,
                    withNetworks = filters?.withNetworks,
                    withWatchProviders = filters?.withWatchProviders,
                    withoutWatchProviders = filters?.withoutWatchProviders,
                    watchRegion = filters?.watchRegion,
                    withOriginalLanguage = filters?.withOriginalLanguage,
                    withOriginCountry = filters?.withOriginCountry,
                    voteCountGte = filters?.voteCountGte,
                    voteAverageGte = filters?.voteAverageGte,
                    voteAverageLte = filters?.voteAverageLte,
                    firstAirDateGte = dateGte,
                    firstAirDateLte = dateLte,
                    // TV shape + episode runtime + certification. with_cast is
                    // deliberately absent: /discover/tv has no person filter.
                    withRuntimeGte = filters?.withRuntimeGte,
                    withRuntimeLte = filters?.withRuntimeLte,
                    certificationCountry = filters?.certificationCountry,
                    certification = filters?.certification,
                    certificationLte = filters?.certificationLte,
                    withStatus = filters?.withStatus,
                    withType = filters?.withType,
                    withoutNetworks = filters?.withoutNetworks
                )
            } else {
                api.discoverMovieGeneric(
                    apiKey = apiKey,
                    page = page,
                    sortBy = sortBy,
                    withGenres = filters?.withGenres,
                    withoutGenres = filters?.withoutGenres,
                    withKeywords = filters?.withKeywords,
                    withoutKeywords = filters?.withoutKeywords,
                    withCompanies = filters?.withCompanies,
                    withoutCompanies = filters?.withoutCompanies,
                    withNetworks = filters?.withNetworks,
                    withWatchProviders = filters?.withWatchProviders,
                    withoutWatchProviders = filters?.withoutWatchProviders,
                    watchRegion = filters?.watchRegion,
                    withOriginalLanguage = filters?.withOriginalLanguage,
                    withOriginCountry = filters?.withOriginCountry,
                    voteCountGte = filters?.voteCountGte,
                    voteAverageGte = filters?.voteAverageGte,
                    voteAverageLte = filters?.voteAverageLte,
                    primaryReleaseDateGte = dateGte,
                    primaryReleaseDateLte = dateLte,
                    withRuntimeGte = filters?.withRuntimeGte,
                    withRuntimeLte = filters?.withRuntimeLte,
                    withCast = filters?.withCast,
                    certificationCountry = filters?.certificationCountry,
                    certification = filters?.certification,
                    certificationLte = filters?.certificationLte,
                    withReleaseType = filters?.withReleaseType,
                    region = releaseRegion
                )
            }.results
        }.getOrNull()
            ?.let { items ->
                // Kids Mode: this is the raw loader behind every KB
                // folder rail, so the ceiling check runs here once and
                // covers all folder screens (movies + series mixed).
                val ceilingFiltered =
                    if (kidsMaxAge() == null) items
                    else kidsFilterItems(
                        items.map { StudioItem(it, if (isTv) "series" else "movie") }
                    ).map { it.item }

                // The app-wide digital-release filter runs here too: this is
                // the raw loader behind the kids Home rails and every KB
                // folder's TMDB discover source, so one check covers them all.
                if (!isDigitalFilterEnabled()) {
                    ceilingFiltered
                } else {
                    filterByHomeAvailability(ceilingFiltered) {
                        it.id to (if (isTv) "series" else "movie")
                    }
                }
            }
    }

    /**
     * TMDB /tv/on_the_air: shows airing in the next seven days. The real
     * "airing now" feed - discover has no equivalent, which is why the built-in
     * guest rails leaned on a premiere-date window before this existed.
     */
    suspend fun onTheAir(page: Int = 1): List<TmdbDiscoverItem>? {
        if (apiKey.isBlank()) return null
        return runCatchingCancellable {
            api.getTvOnTheAir(apiKey, page).results
        }.getOrNull()
    }

    /**
     * TMDB /trending/{movie,tv}/week. [mediaType] is "movie" or "tv"; the
     * response carries the same item shape as a discover page.
     */
    suspend fun trendingWeek(mediaType: String, page: Int = 1): List<TmdbDiscoverItem>? {
        if (apiKey.isBlank()) return null
        val isTv = mediaType.lowercase() == "tv"
        return runCatchingCancellable {
            if (isTv) api.getTrendingTvDiscover(apiKey, page).results
            else api.getTrendingMoviesDiscover(apiKey, page).results
        }.getOrNull()
    }

    /**
     * TMDB "LIST" source: items of a hosted TMDB list id. Kids Mode note:
     * /list items are heterogeneous (movies + series mixed), so the ceiling
     * check keys off each item's own media type — inferred the same way the
     * rail renderer does (first_air_date present => series) since /list
     * results carry no media_type field.
     */
    suspend fun getKBListItems(listId: Int, page: Int = 1): List<TmdbDiscoverItem>? {
        if (apiKey.isBlank()) return null
        return runCatchingCancellable {
            api.getListItems(listId, apiKey, page).results
        }.getOrNull()?.let { items ->
            if (kidsMaxAge() == null) items
            else kidsFilterItems(
                items.map {
                    StudioItem(it, if (it.firstAirDate != null) "series" else "movie")
                }
            ).map { it.item }
        }
    }

    /** TMDB "COLLECTION" source: the collection's parts. */
    /**
     * Titles sharing a TMDB keyword ("heist", "space western", ...) via the
     * generic discover endpoint. Powers the "same vibe" tier of the player's
     * because-you-watched blend; sorted by TMDB's default relevance.
     */
    suspend fun getKeywordItems(
        keywordId: Int,
        type: String,
        page: Int = 1
    ): List<com.kennyb1201.kbstream.data.tmdb.TmdbKeywordDiscoverItem>? {
        if (apiKey.isBlank()) return null
        val isTv = normalizeType(type) == "series"
        return runCatchingCancellable {
            if (isTv) {
                api.discoverTvGeneric(
                    apiKey = apiKey,
                    page = page,
                    sortBy = "popularity.desc",
                    withKeywords = keywordId.toString()
                )
            } else {
                api.discoverMovieGeneric(
                    apiKey = apiKey,
                    page = page,
                    sortBy = "popularity.desc",
                    withKeywords = keywordId.toString()
                )
            }.results.map { item ->
                com.kennyb1201.kbstream.data.tmdb.TmdbKeywordDiscoverItem(
                    id = item.id,
                    title = item.title,
                    name = item.name,
                    posterPath = item.posterPath,
                    backdropPath = item.backdropPath,
                    overview = null
                )
            }
        }.getOrNull()
    }

    suspend fun getKBCollectionItems(collectionId: Int): List<TmdbCollectionPart>? {
        if (apiKey.isBlank()) return null
        return runCatchingCancellable {
            getCollection(collectionId)?.parts
        }.getOrNull()
    }

    /**
     * One page of user reviews for a title via the standalone paginated
     * endpoint. The detail payload only bundles page 1; the Detail screen
     * uses this to pull in pages 2..N (bounded by totalPages from the
     * response) for review-heavy titles. Returns the full page object so
     * callers can read totalPages; fails soft (null) — reviews are
     * supplementary.
     */
    suspend fun getReviews(tmdbId: Int, type: String, page: Int): TmdbReviews? {
        if (apiKey.isBlank() || page < 1) return null
        val normalized = normalizeType(type)
        val key = "$tmdbId:$normalized:$page"
        val now = System.currentTimeMillis()
        reviewsCache[key]?.let { (cachedAt, cached) ->
            if (now - cachedAt < reviewsCacheTtlMs) return cached
        }
        // Disk cache so a reopen after a restart does not refetch every page.
        val diskKey = "reviews:$key"
        val diskCached = runCatchingCancellable { tmdbJsonCacheDao.getByKey(diskKey) }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < reviewsCacheDiskTtlMs) {
            val parsed = runCatching { reviewsJsonAdapter.fromJson(diskCached.json) }.getOrNull()
            if (parsed != null) {
                reviewsCache[key] = now to parsed
                pruneMemoryCaches()
                return parsed
            }
        }
        val result = runCatchingCancellable {
            if (normalized == "series") {
                api.getTvReviews(tmdbId, apiKey, page)
            } else {
                api.getMovieReviews(tmdbId, apiKey, page)
            }
        }.getOrNull()
        // A miss is not cached: Detail probes page 2 to discover the page
        // count, and a null there must not pin \"no reviews\" for the session.
        if (result != null) {
            pruneMemoryCaches()
            reviewsCache[key] = now to result
            cacheJson(diskKey, reviewsJsonAdapter.toJson(result), now)
        }
        return result
    }

    suspend fun getSeasonEpisodes(
        tvId: Int,
        season: Int,
        imdbId: String
    ): List<ResolvedEpisode> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("TMDB API key is missing")
        }

        // Continue-watching resolution scans many seasons per show (and does so
        // once per history/Simkl row), so cache each (show, season) lookup in
        // memory with a TTL instead of hitting TMDB every time.
        //
        // The show's detected episode scheme is PART of the key, because it is
        // part of every stream id in the list: keyed without it, a season
        // cached before the scheme was detected would keep naming the file the
        // old mapping chose - for up to a week, on disk - and the detail screen
        // would hand the player a file that does not hold the episode (see
        // EpisodeScheme). A scheme change is a new key, so the next lookup
        // rebuilds the list instead of aging out the old one.
        val key = "$tvId:$season:$imdbId:${episodeSchemeTag(imdbId, tvId)}"
        val now = System.currentTimeMillis()
        pruneMemoryCaches()
        val cached = seasonEpisodesCache[key]

        // Neither cache may serve an empty list as an answer: see
        // [seasonEpisodesAreAnAnswer]. A cached failure is skipped, not
        // returned, so the lookup below runs and the real list replaces it.
        if (cached != null && now - cached.first < seasonEpisodesCacheTtlMs) {
            val named = cached.second.namedEpisodesOnly()
            if (seasonEpisodesAreAnAnswer(named)) {
                return named
            }
        }

        // Disk cache so the season scans also survive restarts.
        val diskKey = "season:$key"
        val diskCached = runCatchingCancellable {
            tmdbJsonCacheDao.getByKey(diskKey)
        }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < seasonEpisodesDiskTtlMs) {
            val parsed = runCatching {
                seasonEpisodesJsonAdapter.fromJson(diskCached.json)
            }.getOrNull()
            val named = parsed?.namedEpisodesOnly()
            if (named != null && seasonEpisodesAreAnAnswer(named)) {
                seasonEpisodesCache[key] = now to named
                return named
            }
        }

        val seasonDetail = api.getSeasonDetail(tvId, season, apiKey)

        // TMDB files a "- Specials" row numbered 0 inside a regular
        // season from time to time. It is not an episode the season browser
        // can show: it has no name and no still, so it rendered as a blank
        // card reading EPISODE 0 (see EpisodeNumbering).
        val episodes = seasonDetail.episodes
            .filter { ep -> namedEpisodeNumber(ep.episodeNumber) != null }
            .map { ep ->
            ResolvedEpisode(
                // FILE numbering, not TMDB's: on a show whose files hold two
                // segments each, this is the file that HOLDS the episode (see
                // EpisodeScheme), which is what the addons resolve and what
                // the watch-history row is filed under. The episode number
                // itself stays TMDB's, on the field below.
                streamId = fileEpisodeStreamId(
                    context = appContext,
                    rootId = imdbId,
                    tmdbId = tvId,
                    season = season,
                    tmdbEpisode = ep.episodeNumber
                ),
                episodeNumber = ep.episodeNumber,
                name = ep.name,
                overview = ep.overview,
                thumbnail = ep.stillPath?.let {
                    "https://image.tmdb.org/t/p/w780$it"
                },
                runtimeMinutes = ep.runtime,
                airDate = ep.airDate,
                voteAverage = ep.voteAverage
            )
        }

        // An empty list is a question, not an answer: remembering it is what
        // kept a transient failure on screen for a week.
        if (seasonEpisodesAreAnAnswer(episodes)) {
            seasonEpisodesCache[key] = now to episodes
            cacheJson(diskKey, seasonEpisodesJsonAdapter.toJson(episodes), now)
        }
        return episodes
    }

    /**
     * The show's detected episode scheme as a cache-key part: `1:1` when there
     * is none, `sp2` / `fe3` otherwise (see [EpisodeSchemeStore.encode]).
     */
    private fun episodeSchemeTag(imdbId: String, tvId: Int): String {
        val stable = EpisodeSchemeStore.stableShowId(imdbId, tvId) ?: return "1:1"
        return EpisodeSchemeStore.get(appContext, stable).encode() ?: "1:1"
    }

    suspend fun getEpisodeRating(
    tmdbId: Int,
    season: Int,
    episode: Int
): Double? {
    if (apiKey.isBlank()) return null

    return runCatchingCancellable {
        api.getSeasonDetail(
            id = tmdbId,
            seasonNumber = season,
            apiKey = apiKey
        )
            .episodes
            .firstOrNull { it.episodeNumber == episode }
            ?.voteAverage
            ?.takeIf { it > 0.0 }
    }.getOrNull()
    }

    suspend fun getDetailByTmdbId(tmdbId: Int, type: String): TmdbDetail? {
        if (apiKey.isBlank()) return null

        val key = "${normalizeType(type)}:tmdb:$tmdbId"
        val now = System.currentTimeMillis()
        pruneMemoryCaches()

        // In-memory TTL cache (fast path for the current session).
        val cached = detailCache[key]
        if (cached != null && now - cached.first < detailCacheTtlMs) {
            return cached.second
        }

        // Disk cache so resolved metadata survives restarts.
        val diskKey = detailDiskKey(key)
        val diskCached = runCatchingCancellable {
            tmdbJsonCacheDao.getByKey(diskKey)
        }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < detailCacheDiskTtlMs) {
            val parsed = runCatching {
                detailJsonAdapter.fromJson(diskCached.json)
            }.getOrNull()
            if (parsed != null) {
                detailCache[key] = now to parsed
                return parsed
            }
        }

        // Another caller is already fetching this key: share its result instead
        // of firing a duplicate request. putIfAbsent closes the window between
        // the check and the registration, so two callers that both missed the
        // cache cannot both start a fetch.
        detailFetchInFlight[key]?.let { pending -> return pending.await() }

        val deferred = CompletableDeferred<TmdbDetail?>()
        detailFetchInFlight.putIfAbsent(key, deferred)?.let { pending ->
            return pending.await()
        }

        val result =
            try {
                runCatchingCancellable {
                    if (normalizeType(type) == "series") {
                        api.getTv(tmdbId, apiKey)
                    } else {
                        api.getMovie(tmdbId, apiKey)
                    }
                }.getOrNull()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                detailFetchInFlight.remove(key)
                // Unblock anyone sharing this fetch with a miss rather than a
                // cancellation they did not ask for.
                deferred.complete(null)
                throw cancellation
            }

        // A MISS is deliberately not cached, here exactly as in
        // fetchEnrichedMetaCached above: one transient failure (500 / 429 /
        // timeout) used to pin a null in the 12h memory cache, after which every
        // caller - Detail, Home, the watched-state preload - was told this title
        // has no metadata, with no way to retry inside the TTL. Only the disk
        // write was guarded; the memory map was not.
        if (result != null) {
            detailCache[key] = now to result
            cacheJson(diskKey, detailJsonAdapter.toJson(result), now)
        }
        // Complete before dropping the in-flight marker, so a caller that
        // arrives between the two sees the cache and never double-fetches.
        deferred.complete(result)
        detailFetchInFlight.remove(key)
        return result
    }

    /**
     * Cache-only variant of [getDetailByTmdbId]: memory + disk TTL caches, no
     * network. Used by the watched-state preload path, which runs for every
     * visible poster and must never issue a request per item. A miss returns
     * null, and the caller treats "unknown" the way it treats "not finished".
     */
    suspend fun cachedDetailByTmdbId(
        tmdbId: Int,
        type: String
    ): TmdbDetail? {
        val key = "${normalizeType(type)}:tmdb:$tmdbId"
        val now = System.currentTimeMillis()
        pruneMemoryCaches()

        val cached = detailCache[key]
        if (cached != null && now - cached.first < detailCacheTtlMs) {
            return cached.second
        }

        val diskCached = runCatchingCancellable {
            tmdbJsonCacheDao.getByKey(detailDiskKey(key))
        }.getOrNull()
        if (diskCached != null && now - diskCached.updatedAt < detailCacheDiskTtlMs) {
            val parsed = runCatching {
                detailJsonAdapter.fromJson(diskCached.json)
            }.getOrNull()
            if (parsed != null) {
                detailCache[key] = now to parsed
                return parsed
            }
        }

        return null
    }

    suspend fun getByCompany(
        companyId: Int,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> = getInitialCompanySections(companyId, onSection)

    /**
     * Rail titles a genre / keyword / studio page loads, in display order —
     * the same six every one of those screens draws, which is also why they
     * share one list and one loader shape.
     */
    private val STANDARD_BROWSE_TITLES = listOf(
        "MOVIES · RECENT",
        "MOVIES · POPULAR",
        "MOVIES · TOP RATED",
        "SERIES · RECENT",
        "SERIES · POPULAR",
        "SERIES · TOP RATED"
    )

    // The six rail fetches used to run serially, making screen load time
    // the SUM of all round-trips. In parallel it is just the slowest one
    // (~6x faster wall clock). Sections keep the original render order and
    // a failed rail still drops out (listOfNotNull semantics kept).
    //
    // [onSection] is the screen watching the rails arrive as they are built
    // (see [streamBrowseSections]): rails reach it in display order and page 1
    // reaches it before the deepening, so the page draws its first rail
    // instead of waiting for its slowest.
    suspend fun getInitialGenreSections(
        genreId: Int,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> = streamBrowseSections(STANDARD_BROWSE_TITLES, onSection) { title, deepen ->
        getGenreRailPage(genreId, title, 1, deepen)
    }

    // Same parallelization as getInitialGenreSections.
    suspend fun getInitialKeywordSections(
        keywordId: Int,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> = streamBrowseSections(STANDARD_BROWSE_TITLES, onSection) { title, deepen ->
        getKeywordRailPage(keywordId, title, 1, deepen)
    }

    // Same parallelization as getInitialGenreSections.
    /**
     * A network's rails: its series, plus its movies when the browse entry
     * also carries the brand's TMDB company id ([companyId]) — network
     * discover is TV-only, so the movie rails go through company discover
     * (see [TmdbRailPages.networkPage]). Series stay first: they are what a
     * network page is for, and the movie rails are the bonus.
     */
    suspend fun getInitialNetworkSections(
        networkId: Int,
        companyId: Int? = null,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> = streamBrowseSections(
        TmdbRailPages.networkRailTitles(companyId),
        onSection
    ) { title, deepen ->
        getNetworkRailPage(networkId, title, 1, companyId, deepen)
    }

    // Same parallelization as getInitialGenreSections.
    suspend fun getInitialCompanySections(
        companyId: Int,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> = streamBrowseSections(STANDARD_BROWSE_TITLES, onSection) { title, deepen ->
        getCompanyRailPage(companyId, title, 1, deepen)
    }

    // ------------------------------------------------------------------
    // Genre chip rails: compose a genre filter with whatever dimension a
    // discover screen already uses (provider, company, network, keyword,
    // or decade). Uses discoverKB so the genre ANDs with the base filter,
    // instead of adding new API endpoints per screen.
    // ------------------------------------------------------------------

    /** Cached union of TMDB movie + TV genre lists (merged by id). */
    @Volatile
    private var browseGenreCache: List<TmdbGenre>? = null

    suspend fun getBrowseGenres(): List<TmdbGenre> {
        browseGenreCache?.let { return it }
        val movie = runCatchingCancellable { api.getMovieGenreList(apiKey).genres }.getOrDefault(emptyList())
        val tv = runCatchingCancellable { api.getTvGenreList(apiKey).genres }.getOrDefault(emptyList())
        val merged = (movie + tv.filter { tvGenre -> movie.none { it.id == tvGenre.id } })
            .sortedBy { it.name }
        if (merged.isNotEmpty()) browseGenreCache = merged
        return merged
    }

    /**
     * One page of one rail where a genre is ANDed onto the screen's base
     * dimension. Rail title parses exactly like the per-dimension rail
     * pages ("MOVIES · POPULAR", ...). A network base is TV-only; movie
     * rails against it return an empty page.
     */
    suspend fun getCrossGenreRailPage(
        base: CrossBase,
        genreId: Int,
        title: String,
        page: Int,
        deepen: Boolean = true
    ): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val parts = title.split("\u00B7").map { it.trim() }
        val mediaType = when (parts.getOrNull(0)?.uppercase()) {
            "MOVIES" -> "movie"
            "SERIES" -> "tv"
            else -> return TagRailPage(emptyList(), false)
        }
        val isTv = mediaType == "tv"
        val mode = parts.getOrNull(1)?.uppercase()
        val sortBy = when (mode) {
            "RECENT" -> if (isTv) "first_air_date.desc" else "primary_release_date.desc"
            "POPULAR" -> "popularity.desc"
            "TOP RATED" -> "vote_count.desc"
            else -> return TagRailPage(emptyList(), false)
        }
        val voteFloor = when (mode) {
            "RECENT" -> minRecentVoteCount
            "POPULAR" -> minVoteCount
            else -> minTopRatedVoteCount
        }
        // A network base is TV-only in TMDB's discover (a network id is not
        // a company id), so its MOVIES rails run through the brand's company
        // id when the screen has one. Without it they stay empty, exactly as
        // before — never another brand's catalog.
        val networkMovieCompanyId = when {
            base.kind != "network" || isTv -> null
            base.companyId != null -> base.companyId
            else -> return TagRailPage(emptyList(), false)
        }

        val filters = com.kennyb1201.kbstream.data.kb.KBFilters(
            voteCountGte = voteFloor,
            // English-only catalogs while that switch is on (see
            // [browseLanguage]) — this is the cross-genre and decade rails
            // behind the Search browse chips.
            withOriginalLanguage = browseLanguage(),
            // A genre-base screen (Tag on a genre) ANDs the chip genre via
            // TMDB's comma OR semantics within the same filter field.
            withGenres = if (base.kind == "genre") {
                if (base.id == genreId) base.id.toString() else "${base.id},$genreId"
            } else {
                genreId.toString()
            },
            releaseDateLte = today,
            withWatchProviders = if (base.kind == "provider") base.id.toString() else null,
            watchRegion = if (base.kind == "provider") "US" else null,
            withCompanies = when {
                base.kind == "company" -> base.id.toString()
                networkMovieCompanyId != null -> networkMovieCompanyId.toString()
                else -> null
            },
            withNetworks = if (base.kind == "network" && isTv) base.id.toString() else null,
            withKeywords = if (base.kind == "keyword") base.id.toString() else null,
            year = if (base.kind == "decade") base.id else null
        )

        val (items, nextPage) = deepenDiscover(
            mediaType = if (isTv) "tv" else "movie",
            sortBy = sortBy,
            filters = filters,
            page = page,
            deepen = deepen
        )

        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(items) { it.item.id to it.mediaType }
            } else {
                items
            }
        return kidsFilterPage(
            TagRailPage(filtered, items.size >= 20, nextPage = nextPage)
        )
    }

    /**
     * Initial rail set for a genre-filtered discover screen. Rail titles
     * (and therefore section keys) match the unfiltered screens so the
     * ViewModels' paging code works unchanged.
     */
    suspend fun getInitialCrossGenreSections(
        base: CrossBase,
        genreId: Int,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> {
        val titles = when (base.kind) {
            "decade" -> DECADE_RAIL_TITLES
            // Same rail set when a genre chip is active — the genre filter
            // must not silently drop the page's movie rails.
            "network" -> TmdbRailPages.networkRailTitles(base.companyId)
            else -> SERVICE_RAIL_TITLES
        }
        return streamBrowseSections(titles, onSection) { title, deepen ->
            getCrossGenreRailPage(base, genreId, title, 1, deepen)
        }
    }

    /**
     * True when the Home digital-release filter is enabled in Settings.
     */
    fun isDigitalFilterEnabled(): Boolean =
        AppPreferences.getHomeRailHideUpcoming(appContext)

    /**
     * The `with_original_language` value every browse/discover rail should
     * filter with: "en" while Settings' English-only switch is on (the
     * default), null when it is off.
     *
     * Deliberately not applied to free-text search — a search for a title by
     * name must still find it whatever its original language. This gates the
     * discover surfaces only: the Search browse chips (Genre / Keyword /
     * Service / Network / Studio / Decade) and the rails they open.
     */
    internal fun browseLanguage(): String? =
        if (AppPreferences.getBrowseEnglishOnly(appContext)) "en" else null

    /**
     * App-wide availability filter (Home digital-release toggle). Given
     * items keyed by TMDB id + media type, drops movies the shared TMDB
     * cache says are not available at home yet (theatrical-only window,
     * unreleased lifecycle status, or only future dates). Series always
     * pass, unknown verdicts always pass, and id lookup failures keep
     * the item — the filter only hides what it can positively tell.
     * Verdicts reuse fetchEnrichedMetaCached (12h memory / 30d disk),
     * throttled by a semaphore.
     */
    suspend fun <T> filterByHomeAvailability(
        items: List<T>,
        key: (T) -> Pair<Int, String>
    ): List<T> {

        if (items.isEmpty()) return items

        return coroutineScope {

            items.map { item ->

                async {

                    val (tmdbId, mediaType) = key(item)

                    if (!mediaType.equals("movie", ignoreCase = true)) {
                        return@async item
                    }

                    val verdict = availabilitySemaphore.withPermit {

                        runCatchingCancellable {

                            fetchEnrichedMetaCached(
                                imdbId = "tmdb:$tmdbId",
                                type = "movie"
                            )?.isAvailableAtHome()

                        }.getOrNull()
                    }

                    when (verdict) {
                        false -> null
                        else -> item
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * [filterByHomeAvailability] for rows that carry their own RAW id rather
     * than a TMDB id: an add-on catalog page, a KB folder row, an add-on search
     * hit. The id may be "tmdb:<n>", an IMDb "tt..." id, a bare TMDB id or
     * anything else [fetchEnrichedMeta] resolves; a row whose id cannot be
     * resolved is kept.
     *
     * This is the single implementation the non-discover surfaces share, so an
     * un-released movie is hidden the same way on the KB folder, in add-on
     * search and on a Home rail. Gated on the Settings switch here (unlike
     * [filterByHomeAvailability], whose callers check it) because these callers
     * are spread across the app.
     */
    suspend fun <T> filterByHomeAvailabilityById(
        items: List<T>,
        id: (T) -> String,
        type: (T) -> String
    ): List<T> {

        if (items.isEmpty() || !isDigitalFilterEnabled()) return items

        val today = LocalDate.now()

        return coroutineScope {

            items.map { item ->

                async {

                    if (!type(item).equals("movie", ignoreCase = true)) {
                        return@async item
                    }

                    val verdict = availabilitySemaphore.withPermit {

                        runCatchingCancellable {

                            fetchEnrichedMetaCached(
                                imdbId = id(item),
                                type = "movie"
                            )?.isAvailableAtHome(today)

                        }.getOrNull()
                    }

                    when (verdict) {
                        false -> null
                        else -> item
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    // ------------------------------------------------------------------
    // Kids Mode enforcement. Every discover-backed rail (genres, keywords,
    // studios, networks, services, decades, KB folders) funnels through
    // the *RailPage / *Section helpers below, so certifying each page once
    // here covers the whole browse surface for the ACTIVE profile. Titles
    // above the profile's rating ceiling are dropped; the check reuses the
    // shared detail cache, so it costs nothing once a title has been
    // enriched elsewhere (and each item is one cached TMDB detail fetch
    // the first time it appears on any kids profile).
    // ------------------------------------------------------------------

    /** Kids Mode ceiling of the ACTIVE profile (null = off). */
    fun kidsMaxAge(): Int? =
        ProfileManager.activeProfile.value?.kidsMaxAge

    /** True when kids mode is on and the ceiling is strict (PG/G). */
    /**
     * True when kids mode is on and the ceiling is strict (PG/G): strict
     * ceilings drop titles whose certification cannot be resolved, and
     * hide person credits that carry no media type at all.
     */
    fun isKidsModeStrict(): Boolean = kidsMaxAge() != null && kidsMaxAge() != KidsMode.CEIL_PG13

    /**
     * US certification for one (tmdbId, mediaType) pair, read from the same
     * cached detail the ceiling check uses, or null when it cannot be
     * resolved (a title with no US rating, a miss, a timeout).
     *
     * Null is deliberately not "allowed": every caller hands the answer to
     * [KidsMode.allowed], which owns the known-unknown rule (kept only on the
     * loosest ceiling).
     *
     * The detail appends `release_dates` for a film and `content_ratings` for
     * a series (see TmdbApiService), and [railProjection] keeps both on the slim
     * row - so this is cached exactly like every other detail read (12 h in
     * memory, 30 d on disk) and a repeat read issues no request at all. That is
     * what makes the player's credits-row gate free on a second view.
     *
     * [timeoutMs] bounds the fetch for callers that cannot wait (see
     * BecauseYouWatched); null is the unbounded form the rails use, where the
     * page is already parallel and a slow title must not read as "unrated"
     * earlier than the request would have failed anyway.
     */
    internal suspend fun usCertification(
        tmdbId: Int,
        mediaType: String,
        timeoutMs: Long? = null
    ): String? {
        val isSeries = mediaType.equals("series", ignoreCase = true) ||
            mediaType.equals("tv", ignoreCase = true)
        val fetch: suspend () -> TmdbDetail? = {
            availabilitySemaphore.withPermit {
                runCatchingCancellable {
                    fetchEnrichedMetaCached(
                        imdbId = "tmdb:$tmdbId",
                        type = if (isSeries) "series" else "movie"
                    )
                }.getOrNull()
            }
        }
        val detail = if (timeoutMs == null) fetch() else withTimeoutOrNull(timeoutMs) { fetch() }
        return detail?.certification(isMovie = !isSeries)
    }

    /**
     * Certification check for one (tmdbId, mediaType) pair under the active
     * ceiling. Internal so a caller that resolves a SINGLE id rather than
     * filtering a list - a tapped global-search suggestion's deep link - can
     * apply the same ceiling (see [kidsFilterMetas]).
     */
    internal suspend fun kidsAllowed(tmdbId: Int, mediaType: String): Boolean {
        val ceiling = kidsMaxAge() ?: return true
        return KidsMode.allowed(ceiling, usCertification(tmdbId, mediaType))
    }

    /**
     * Drop every item above the active profile's rating ceiling. Returns
     * the list unchanged when kids mode is off, so non-kids profiles pay
     * a single null-check per page.
     */
    suspend fun <T> kidsFilter(
        items: List<T>,
        key: (T) -> Pair<Int, String>
    ): List<T> {
        if (items.isEmpty() || kidsMaxAge() == null) return items
        return coroutineScope {
            items.map { item ->
                async {
                    val (tmdbId, mediaType) = key(item)
                    if (kidsAllowed(tmdbId, mediaType)) item else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Meta-level kids filter for Stremio catalog rails (Home, addon
     * results): keys by the meta's raw id — IMDB ids resolve through TMDB
     * /find, "tmdb:"/numeric ids fetch directly — via the same enriched-
     * detail cache every other surface uses. The generic [kidsFilter]
     * above only handles TMDB-id-keyed items, which catalog metas are not.
     */
    suspend fun kidsFilterMetas(metas: List<com.kennyb1201.kbstream.data.addon.MetaPreview>): List<com.kennyb1201.kbstream.data.addon.MetaPreview> {
        if (metas.isEmpty() || kidsMaxAge() == null) return metas
        return coroutineScope {
            metas.map { meta ->
                async {
                    val isSeries = meta.type.equals("series", ignoreCase = true) ||
                        meta.type.equals("tv", ignoreCase = true)
                    val ceiling = kidsMaxAge()
                    val detail = availabilitySemaphore.withPermit {
                        runCatchingCancellable {
                            fetchEnrichedMetaCached(
                                imdbId = meta.id,
                                type = if (isSeries) "series" else "movie"
                            )
                        }.getOrNull()
                    }
                    val allowed = if (detail == null) {
                        ceiling == KidsMode.CEIL_PG13
                    } else {
                        KidsMode.allowed(ceiling, detail.certification(isMovie = !isSeries))
                    }
                    if (allowed) meta else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Kids filter for person combined-credits (cast or crew). Credits carry
     * media_type on /person/{id}?append_to_response=combined_credits; items
     * without one are dropped only under a strict ceiling (PG/G hides
     * unknowns), matching the known-unknown rule elsewhere.
     */
    suspend fun kidsFilterPersonCredits(credits: List<TmdbPersonCredit>): List<TmdbPersonCredit> {
        if (credits.isEmpty() || kidsMaxAge() == null) return credits
        return coroutineScope {
            credits.map { credit ->
                async {
                    val mediaType = when (credit.mediaType?.lowercase()) {
                        "movie" -> "movie"
                        "tv", "series" -> "series"
                        else -> return@async if (isKidsModeStrict()) null else credit
                    }
                    if (kidsAllowed(credit.id, mediaType)) credit else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Kids filter keyed on the discover item itself. */
    private suspend fun kidsFilterItems(items: List<StudioItem>): List<StudioItem> {
        if (items.isEmpty() || kidsMaxAge() == null) return items
        return kidsFilter(items) { it.item.id to it.mediaType }
    }

    /** Kids filter for a rail page (shared return shape of every rail loader). */
    internal suspend fun kidsFilterPage(page: TagRailPage): TagRailPage {
        if (page.items.isEmpty() || kidsMaxAge() == null) return page
        return page.copy(items = kidsFilterItems(page.items))
    }

    /**
     * Entries with no artwork. TMDB carries placeholder entries (announced
     * announcements, festival stubs, merges) that have a title and nothing
     * else; a poster grid can only render them as an empty card, and the
     * RECENT rails no longer have a vote floor to keep them out.
     *
     * Artwork alone is not that test: a posterless but otherwise real entry is
     * kept, so a brand's newest show cannot sit hidden behind its missing art.
     * See [hasSomethingToDraw] for the exact rule and why.
     */
    private fun dropPosterless(items: List<StudioItem>): List<StudioItem> =
        items.filter { hasSomethingToDraw(it) }

    /**
     * Shared tail of every rail-page loader (see [TmdbRailPages]): drop
     * duplicates, drop artwork-less placeholders, apply the digital-release
     * filter when it is on, then the active profile's kids ceiling. `hasMore`
     * reflects the RAW result set, so filtering can never stop a rail from
     * paging.
     */
    internal suspend fun finishRailPage(
        results: List<StudioItem>,
        nextPage: Int = 2
    ): TagRailPage {
        val distinct = dropPosterless(results.distinctBy { it.item.id })

        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(distinct) {
                    it.item.id to it.mediaType
                }
            } else {
                distinct
            }

        return kidsFilterPage(
            TagRailPage(
                items = filtered,
                hasMore = results.isNotEmpty(),
                nextPage = nextPage
            )
        )
    }

    /**
     * [finishRailPage] with depth: the four dimension rail loaders in
     * [TmdbRailPages] (genre / keyword / network / company) fetch page 1
     * through [load] and, while the rail is still under
     * [RAIL_DEPTH_TARGET_ITEMS], through the pages after it. A page is only
     * followed by another when it came back non-empty — a short catalog ends
     * the loop instead of asking TMDB for pages that cannot exist.
     *
     * Merged pages go through [finishRailPage] once, so the artwork and
     * availability filters still run a single time over the whole rail, and
     * the reported [TagRailPage.nextPage] is the first page not merged yet.
     * Paging past page 1 is untouched: a "load more" fetches exactly the page
     * it asked for.
     *
     * [deepen] = false is the same rail without the deepening: page 1, filtered
     * and finished exactly as the last page of a deepened rail would be. It is
     * what a streamed screen publishes first (see `streamBrowseSections`) so
     * the rail is on screen after one round-trip instead of three while the
     * deepening pass runs behind it. It reports `nextPage = 2` — the same value
     * a rail that needed no deepening reports — because the deepening pass
     * that follows starts from page 2, and only a paging request the user
     * actually makes moves past it.
     */
    internal suspend fun finishDeepRailPage(
        page: Int,
        /**
         * Identity of the request (dimension, id, rail title, language) so
         * [cachedRailPage] can reuse it; null disables caching.
         */
        cacheKey: String? = null,
        deepen: Boolean = true,
        load: suspend (Int) -> List<StudioItem>
    ): TagRailPage = coroutineScope {
        val first = cachedRailPage(cacheKey, page, load)
        if (page != 1) {
            return@coroutineScope finishRailPage(first, nextPage = page + 1)
        }

        if (!deepen || first.size >= RAIL_DEPTH_TARGET_ITEMS) {
            return@coroutineScope finishRailPage(first, nextPage = 2)
        }

        // The deepening pages are independent of one another, so they go out
        // TOGETHER. As a serial loop this was one round-trip per page, and
        // that - not the number of requests - is what a screen open actually
        // waited on. They are still merged in order and still stop at the
        // first empty page, so the merged rails and the page a later "load
        // more" asks for are unchanged; the only difference is that a rail
        // whose page 2 is empty has already paid for page 3.
        val deeper = (2..RAIL_DEPTH_MAX_PAGE).map { p ->
            async { cachedRailPage(cacheKey, p, load) }
        }

        val merged = first.toMutableList()
        var next = 2
        for (pending in deeper) {
            val more = pending.await()
            if (more.isEmpty()) break
            merged += more
            next++
        }
        finishRailPage(merged, nextPage = next)
    }

    /**
     * The same deepening for the discover-backed rails (service, decade,
     * cross-genre), which build their own `KBFilters` instead of going through
     * [TmdbRailPages]. Returns the merged rows paired with the page a later
     * "load more" should ask for.
     */
    private suspend fun deepenDiscover(
        mediaType: String,
        sortBy: String,
        filters: com.kennyb1201.kbstream.data.kb.KBFilters,
        page: Int,
        deepen: Boolean = true
    ): Pair<List<StudioItem>, Int> = coroutineScope {
        val itemType = if (mediaType.equals("tv", ignoreCase = true)) "series" else "movie"

        // Keyed off the REQUEST rather than off the screen: two screens that
        // ask the same discover question (a genre chip and a Tag screen over
        // the same genre, say) are entitled to the same answer, and [filters]
        // already carries every dimension that shapes it - the ids, the
        // language and the vote floor.
        val cacheKey = "discover|$mediaType|$sortBy|$filters"

        suspend fun load(p: Int): List<StudioItem> =
            cachedRailPage(cacheKey, p) { requestedPage ->
                runCatchingCancellable {
                    discoverKB(
                        mediaType = mediaType,
                        page = requestedPage,
                        sortBy = sortBy,
                        filters = filters
                    )
                }.getOrNull().orEmpty()
                    .map { StudioItem(it, itemType) }
                    .distinctBy { it.item.id }
            }

        val first = load(page)
        if (page != 1) return@coroutineScope first to (page + 1)

        if (!deepen || first.size >= RAIL_DEPTH_TARGET_ITEMS) {
            return@coroutineScope first.distinctBy { it.item.id } to 2
        }

        // Independent pages go out together, exactly as in
        // [finishDeepRailPage].
        val deeper = (2..RAIL_DEPTH_MAX_PAGE).map { p ->
            async { load(p) }
        }

        val merged = first.toMutableList()
        var next = 2
        for (pending in deeper) {
            val more = pending.await()
            if (more.isEmpty()) break
            merged += more
            next++
        }
        // Across pages, not just within one: a popularity-sorted discover
        // page shifts as items gain votes, so page 2 can repeat a row page 1
        // already had. (The [finishDeepRailPage] path is deduped by
        // [finishRailPage] instead.)
        merged.distinctBy { it.item.id } to next
    }

    suspend fun getGenreRailPage(
        genreId: Int,
        title: String,
        page: Int,
        deepen: Boolean = true
    ): TagRailPage =
        TmdbRailPages.genrePage(this, genreId, title, page, deepen)

    suspend fun getKeywordRailPage(
        keywordId: Int,
        title: String,
        page: Int,
        deepen: Boolean = true
    ): TagRailPage =
        TmdbRailPages.keywordPage(this, keywordId, title, page, deepen)

    suspend fun getNetworkRailPage(
        networkId: Int,
        title: String,
        page: Int,
        companyId: Int? = null,
        deepen: Boolean = true
    ): TagRailPage = TmdbRailPages.networkPage(this, networkId, title, page, companyId, deepen)

    suspend fun getCompanyRailPage(
        companyId: Int,
        title: String,
        page: Int,
        deepen: Boolean = true
    ): TagRailPage =
        TmdbRailPages.companyPage(this, companyId, title, page, deepen)

    /**
     * Keyword id lookup for the Search browse browser: TMDB's search/keyword
     * endpoint resolves a user-facing name ("Zombie", "Time Travel") to its
     * stable keyword id, which the Tag screen then discovers with. Cached in
     * the ViewModel so a name is resolved at most once per session.
     */
    suspend fun searchKeywords(query: String): List<TmdbSearchKeywordResult> {
        if (apiKey.isBlank()) return emptyList()
        return runCatchingCancellable { api.searchKeyword(query, apiKey).results }
            .getOrDefault(emptyList())
    }


    /**
     * One decade rail, single media type — movies and series stay separate
     * like the genre/keyword screens. Decades reuse the KB filter
     * plumbing: year="1980-1989" becomes primary_release_date /
     * first_air_date bounds. [mediaType] is "movie" or "tv".
     */
    suspend fun getDecadeSectionPage(
        decadeStart: Int,
        mediaType: String,
        sortBy: String,
        page: Int,
        deepen: Boolean = true
    ): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)
        val decadeEnd = decadeStart + 9
        val yearRange = "$decadeStart-$decadeEnd"
        val isTv = mediaType.equals("tv", ignoreCase = true)

        // TOP RATED sorts by vote COUNT ("most voted"): vote_average
        // surfaces obscure 8.5-rated shorts with 12 votes (heavily anime);
        // vote_count surfaces what people actually voted on. Floors are
        // light so mid-size catalogs keep real rails; "Popular"
        // (popularity.desc) needs just enough to skip no-title entries.
        val voteFloor = if (sortBy.startsWith("vote_count")) {
            minTopRatedVoteCount
        } else {
            minVoteCount
        }
        val filters = com.kennyb1201.kbstream.data.kb.KBFilters(
            year = yearRange,
            voteCountGte = voteFloor,
            withOriginalLanguage = browseLanguage()
        )

        val (items, nextPage) = deepenDiscover(
            mediaType = if (isTv) "tv" else "movie",
            sortBy = sortBy,
            filters = filters,
            page = page,
            deepen = deepen
        )

        // A discover page caps at 20 items; a full page means more exist.
        return kidsFilterPage(TagRailPage(items, items.size >= 20, nextPage = nextPage))
    }

    /**
     * One rail of a streaming service's dedicated screen. [mediaType] picks
     * which single discover call runs, so a service always gets both movie
     * and series rails (each with full TMDB page depth, like the genre
     * screens). Modes:
     *  - "originals": what the service produced — network discover for
     *    series, company discover for movies ([networkOrCompanyId] required;
     *    a pure network id has no movie-discover equivalent, so its movie
     *    rail is simply empty).
     *  - "recent" / "popular" / "voted": everything on the service now via
     *    watch-provider discover (US watch region, [providerId] required).
     */
    suspend fun getProviderRailPage(
        mode: String,
        mediaType: String,
        providerId: Int?,
        networkOrCompanyId: Int?,
        networkIsCompany: Boolean,
        page: Int,
        deepen: Boolean = true
    ): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)
        val isTv = mediaType.equals("tv", ignoreCase = true)

        // Recent sorts by release date, which differs per media type; a
        // vote floor keeps "recent" from surfacing announced-but-unreleased
        // entries (they have almost no votes yet). "voted" sorts by
        // vote COUNT (not average): average surfaces obscure 10-vote
        // foreign/anime titles; count is what "most voted" means and is
        // anime-resistant without the language filter. RECENT has NO year
        // cap — newest first, whatever the service's catalog holds.
        val sortBy = when (mode) {
            "recent" -> if (isTv) "first_air_date.desc" else "primary_release_date.desc"
            "voted" -> "vote_count.desc"
            else -> "popularity.desc"
        }
        val voteFloor = when (mode) {
            "recent" -> minRecentVoteCount
            "popular" -> minVoteCount
            "voted" -> minTopRatedVoteCount
            else -> minRecentVoteCount
        }

        val base = com.kennyb1201.kbstream.data.kb.KBFilters(
            voteCountGte = voteFloor,
            withOriginalLanguage = browseLanguage()
        )
        val filters = if (mode == "originals") {
            // A network id only makes sense for TV; a company id applies to
            // both discover endpoints. Mixing them returns wrong/empty results.
            base.copy(
                withNetworks = if (isTv && !networkIsCompany) {
                    networkOrCompanyId?.toString()
                } else {
                    null
                },
                withCompanies = if (networkIsCompany) {
                    networkOrCompanyId?.toString()
                } else {
                    null
                }
            )
        } else {
            base.copy(
                withWatchProviders = providerId?.toString(),
                watchRegion = "US",
                // Same released-content cap as the genre/keyword/company rails:
                // a floor of 5 doesn't stop a heavily-anticipated unreleased
                // blockbuster (thousands of pre-release votes) from topping RECENT.
                releaseDateLte = today
            )
        }
        val (items, nextPage) = deepenDiscover(
            mediaType = if (isTv) "tv" else "movie",
            sortBy = sortBy,
            filters = filters,
            page = page,
            deepen = deepen
        )

        // A discover page caps at 20 items; a full page means more exist.
        return kidsFilterPage(TagRailPage(items, items.size >= 20, nextPage = nextPage))
    }

    // ------------------------------------------------------------------
    // Decade discover screens (Screen.Decade): same rail structure as the
    // genre/keyword screens, minus the RECENT rails — every decade is old
    // by definition, so "recent" adds nothing. Popular + Top Rated for
    // movies and series.
    // ------------------------------------------------------------------

    /** Rail titles a decade screen loads, in display order. */
    private val DECADE_RAIL_TITLES = listOf(
        "MOVIES · POPULAR",
        "MOVIES · TOP RATED",
        "SERIES · POPULAR",
        "SERIES · TOP RATED"
    )

    suspend fun getInitialDecadeSections(
        decadeStart: Int,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> = streamBrowseSections(DECADE_RAIL_TITLES, onSection) { title, deepen ->
        getDecadeRailPage(decadeStart, title, 1, deepen)
    }

    /**
     * One page of one decade rail, keyed off the rail title the same way
     * the genre/keyword rail pages are ("MOVIES · POPULAR", ...).
     */
    suspend fun getDecadeRailPage(
        decadeStart: Int,
        title: String,
        page: Int,
        deepen: Boolean = true
    ): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val parts = title.split("·").map { it.trim() }
        val mediaType = when (parts.getOrNull(0)?.uppercase()) {
            "MOVIES" -> "movie"
            "SERIES" -> "tv"
            else -> return TagRailPage(emptyList(), false)
        }
        val sortBy = when (parts.getOrNull(1)?.uppercase()) {
            "POPULAR" -> "popularity.desc"
            "TOP RATED" -> "vote_count.desc"
            else -> return TagRailPage(emptyList(), false)
        }

        val result = getDecadeSectionPage(decadeStart, mediaType, sortBy, page, deepen)
        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(result.items) { it.item.id to it.mediaType }
            } else {
                result.items
            }
        return kidsFilterPage(
            TagRailPage(filtered, result.hasMore, nextPage = result.nextPage)
        )
    }

    // ------------------------------------------------------------------
    // Streaming-service screens: the consolidated Services & Networks
    // submenu opens one screen per brand whose rails cover BOTH media
    // types via watch-provider discover (US region) — so opening Netflix
    // shows its movies and shows, not series-only like the old network
    // page. Plain network entries (no provider id) keep the old rails.
    // ------------------------------------------------------------------

    /** Rail titles a service screen loads, in display order. */
    private val SERVICE_RAIL_TITLES = listOf(
        "MOVIES · RECENT",
        "MOVIES · POPULAR",
        "MOVIES · TOP RATED",
        "SERIES · RECENT",
        "SERIES · POPULAR",
        "SERIES · TOP RATED"
    )

    /**
     * The brand's OWN catalog, in three orders per media type.
     *
     * A service whose watch-provider catalog is thin - ESPN+ is the reported
     * case, since TMDB lists almost none of its live sports as movies or
     * shows - would otherwise draw almost all of its screen from six provider
     * rails that have little to say. The three orders of what the brand
     * produced (its network/company discover: 30-for-30 films and ESPN
     * originals for that entry) are the dimensions that actually have depth.
     *
     * The third segment is the order and defaults to RECENT, so the original
     * two-segment titles ("ORIGINALS · SERIES") still parse to what they always
     * meant.
     */
    private val ORIGINALS_RAIL_TITLES = listOf(
        "ORIGINALS · SERIES · RECENT",
        "ORIGINALS · SERIES · POPULAR",
        "ORIGINALS · SERIES · TOP RATED",
        "ORIGINALS · MOVIES · RECENT",
        "ORIGINALS · MOVIES · POPULAR",
        "ORIGINALS · MOVIES · TOP RATED"
    )

    /**
     * Service-page rails. The six provider rails cover everything streaming
     * on the service NOW; the ORIGINALS rails (network + company discover)
     * add everything the brand PRODUCED — including titles that have since
     * left the service and co-productions TMDB tags with the company but
     * never listed under the provider. Originals sections are inserted
     * FIRST (they are the identity of the page); provider rails follow.
     *
     * Both originals ids are optional and independent:
     *  - [networkOrCompanyId] + [networkIsCompany=false] → TV-only originals
     *    (network discover; a network id has no movie equivalent).
     *  - [originalsCompanyId] → full originals (company discover: movies +
     *    TV). When the header id IS the company (niche streamers), the
     *    network rail would duplicate it, so only the company rail runs.
     */
    suspend fun getInitialServiceSections(
        providerId: Int?,
        networkOrCompanyId: Int? = null,
        networkIsCompany: Boolean = false,
        originalsCompanyId: Int? = null,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> {
        // Both ORIGINALS titles are listed whether or not this page can serve
        // them: a page without the id returns an empty rail, which drops out of
        // the list exactly as the old build-a-list-and-filter-the-empties
        // assembly did, and the rails behind it keep their positions.
        val titles = buildList {
            addAll(ORIGINALS_RAIL_TITLES)
            addAll(SERVICE_RAIL_TITLES)
        }

        // Every service rail swallows its own failure (as it always has): one
        // unreachable discover query means that rail is missing from the page,
        // not that the whole service screen fails.
        return streamBrowseSections(titles, onSection) { title, deepen ->
            runCatchingCancellable {
                // Originals and provider rails both go through
                // [getServiceRailPage] - the same entry point
                // [StudioViewModel.loadMoreSection] uses for a rail's second
                // page - so a rail's first page and its later pages take one
                // identical path.
                if (title.startsWith("ORIGINALS")) {
                    getServiceRailPage(
                        providerId = providerId,
                        title = title,
                        page = 1,
                        networkOrCompanyId = networkOrCompanyId,
                        networkIsCompany = networkIsCompany,
                        originalsCompanyId = originalsCompanyId,
                        deepen = deepen
                    )
                } else {
                    providerId
                        ?.let { getServiceRailPage(it, title, 1, deepen = deepen) }
                        ?: TagRailPage(emptyList(), false)
                }
            }.getOrDefault(TagRailPage(emptyList(), false))
        }
    }

    suspend fun getServiceRailPage(
        providerId: Int?,
        title: String,
        page: Int,
        networkOrCompanyId: Int? = null,
        networkIsCompany: Boolean = false,
        originalsCompanyId: Int? = null,
        deepen: Boolean = true
    ): TagRailPage {
        if (apiKey.isBlank()) return TagRailPage(emptyList(), false)

        val parts = title.split("·").map { it.trim() }
        val mediaType = when (parts.getOrNull(0)?.uppercase()) {
            "MOVIES" -> "movie"
            "SERIES" -> "tv"
            else -> return TagRailPage(emptyList(), false)
        }

        // ORIGINALS rails discover through the brand's network/company ids
        // (what it made) instead of the watch provider (what is streaming).
        // A company id discovers movies AND TV; a network id is TV-only.
        if (parts.getOrNull(0)?.uppercase() == "ORIGINALS") {
            val isMoviesRail = parts.getOrNull(1)?.uppercase() == "MOVIES"
            // The third segment is the ORDER, and RECENT is the default: the
            // two-segment spelling this used to be called with still means the
            // newest-first originals rail. POPULAR and TOP RATED are the extra
            // dimensions a service with a thin provider catalog needs (see
            // [ORIGINALS_RAIL_TITLES]).
            val chart = when (parts.getOrNull(2)?.uppercase()) {
                "POPULAR" -> "POPULAR"
                "TOP RATED", "TOP_RATED" -> "TOP RATED"
                else -> "RECENT"
            }
            val companyId = when {
                isMoviesRail -> originalsCompanyId
                networkIsCompany -> networkOrCompanyId
                else -> originalsCompanyId
            }
            if (companyId != null) {
                val railTitle = (if (isMoviesRail) "MOVIES" else "SERIES") + " \u00B7 " + chart
                return getCompanyRailPage(companyId, railTitle, page, deepen)
            }
            if (!networkIsCompany && networkOrCompanyId != null) {
                return getNetworkRailPage(
                    networkOrCompanyId,
                    "SERIES \u00B7 " + chart,
                    page,
                    null,
                    deepen
                )
            }
            return TagRailPage(emptyList(), false)
        }
        val mode = when (parts.getOrNull(1)?.uppercase()) {
            "RECENT" -> "recent"
            "POPULAR" -> "popular"
            "TOP RATED" -> "voted"
            else -> return TagRailPage(emptyList(), false)
        }

        val result = getProviderRailPage(
            mode = mode,
            mediaType = mediaType,
            providerId = providerId,
            networkOrCompanyId = null,
            networkIsCompany = false,
            page = page,
            deepen = deepen
        )
        val filtered =
            if (isDigitalFilterEnabled()) {
                filterByHomeAvailability(result.items) { it.item.id to it.mediaType }
            } else {
                result.items
            }
        return kidsFilterPage(
            TagRailPage(filtered, result.hasMore, nextPage = result.nextPage)
        )
    }

    suspend fun searchCollection(query: String): List<TmdbSearchCollectionResult> {
    if (apiKey.isBlank()) return emptyList()
    return runCatchingCancellable { api.searchCollection(query, apiKey).results }
        .getOrDefault(emptyList())
    }

    suspend fun getByGenre(
        genreId: Int,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> =
        getInitialGenreSections(genreId, onSection)

    suspend fun getByKeyword(
        keywordId: Int,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> =
        getInitialKeywordSections(keywordId, onSection)

    suspend fun getByNetwork(
        networkId: Int,
        companyId: Int? = null,
        onSection: (suspend (Int, StudioSection) -> Unit)? = null
    ): List<StudioSection> =
        getInitialNetworkSections(networkId, companyId, onSection)

    /**
     * Logo candidates, in id order, for a network or company page that has no
     * artwork of its own; see [watchProviderLogoUrl]. Null until the registry
     * is fetched successfully, which is once per process.
     */
    @Volatile
    private var watchProviderLogos: Map<Int, String>? = null

    /**
     * Every transparent clear-logo the header may draw for a studio, network
     * or streaming service, best first.
     *
     * A LIST rather than one URL, because the best-ranked mark is not always
     * drawable: TMDB ships some networks only as a plate the header reads as
     * blank, or as a 1x1 stub, and the screen walks past those to the next
     * candidate (see BrandLogo). The candidates, in order:
     *
     *  1. the entity's own logos, in its own ID space (a network id is not a
     *     company id) - see [brandIdSpaces] for why the other space is offered
     *     second rather than not at all;
     *  2. the SAME BRAND's other entity, where the caller knows one. A service
     *     chip carries its brand's production company alongside its
     *     network/company id (see BrowseHomeShortcut), and that company entry
     *     is often the one TMDB gave a mark to;
     *  3. the same NUMBER in the other space, and only when the two entries
     *     really are one brand (see [entityNamesMatch]). The number alone
     *     means nothing - 41 is TNT as a network and Orion Pictures as a
     *     company, 76 is E! and Zentropa Entertainments - so an unverified
     *     twin is how an unrelated studio's mark ended up on a network whose
     *     own artwork the header rejects;
     *  4. for a service whose brand has no entity artwork at all (The Roku
     *     Channel, Plex, ALLBLK, fuboTV, Xumo Play), the logo TMDB holds for
     *     its WATCH PROVIDER - the only place that brand mark lives;
     *  5. failing all of that, the brand's own name looked up as a company
     *     page. TMDB carries one studio under several ids and the artwork
     *     often sits on a different one: the app's Dimension Films (147786)
     *     has nothing, while 7405 and 51166 hold the mark. This is the step
     *     the catalogue used to do by hand. An exact-or-contained name match
     *     is required, so a search result cannot put another brand's logo on
     *     the page.
     *
     * Every step is cheap before it is reached and only runs when the ones
     * above it came back empty, so a page whose own artwork is real pays for
     * nothing but that first read.
     */
    suspend fun getEntityLogoUrls(
        entityId: Int,
        isNetwork: Boolean,
        providerId: Int? = null,
        /** The brand's own name, for the twin check and the name search. */
        name: String? = null,
        /** The brand's production company, when the caller holds one. */
        originalsCompanyId: Int? = null
    ): List<String> {
        if (apiKey.isBlank()) return emptyList()

        val spaces = brandIdSpaces(isNetwork)
        val twinSpace = spaces.getOrNull(1)

        // The brand's name, read once and only if a step needs it: callers with
        // a route or chip label hand it over for free, and a caller without one
        // pays a single entity read - and only when a fallback got that far.
        var brandName = name?.takeIf { it.isNotBlank() }
        suspend fun brand(): String? {
            if (brandName == null) {
                brandName = getEntityDetail(entityId, isNetwork)?.name
            }
            return brandName
        }

        val candidates = buildList {
            addAll(
                entityLogoUrls(entityId, company = spaces.first().isCompany)
            )

            if (originalsCompanyId != null && originalsCompanyId != entityId) {
                addAll(entityLogoUrls(originalsCompanyId, company = true))
            }

            if (twinSpace != null) {
                val twin = entityLogoUrls(entityId, company = twinSpace.isCompany)
                if (twin.isNotEmpty()) {
                    val twinName = getEntityDetail(
                        entityId,
                        twinSpace == BrandIdSpace.NETWORK
                    )?.name
                    // Same brand or nothing: the two id spaces are numbered
                    // independently, so the number alone proves no relation.
                    if (entityNamesMatch(brand(), twinName)) addAll(twin)
                }
            }
        }.distinct()

        if (candidates.isNotEmpty()) return candidates

        providerId?.let { id ->
            val providerLogo = runCatchingCancellable {
                listOfNotNull(watchProviderLogoUrl(id))
            }.getOrDefault(emptyList())
            if (providerLogo.isNotEmpty()) return providerLogo
        }

        val sibling = brand()?.let { label ->
            companyLogosByName(label, setOfNotNull(entityId, originalsCompanyId))
        }.orEmpty()
        return sibling
    }

    /**
     * The mark TMDB holds on a DIFFERENT company page for the same brand.
     *
     * TMDB's company ids are full of duplicates - one studio filed two or
     * three times, with the logo uploaded to whichever entry a contributor
     * happened to pick - so a brand with no artwork of its own can still have
     * a mark one search away. Only an exact or contained name match counts
     * (see [entityNamesMatch]), and the ids the caller already tried are left
     * out, so this can never hand back the page it was asked about.
     */
    private suspend fun companyLogosByName(
        brand: String,
        excludeIds: Set<Int>
    ): List<String> {
        val query = brand.trim()
        if (query.isBlank()) return emptyList()

        val results = runCatchingCancellable {
            api.searchCompany(query, apiKey).results
        }.getOrDefault(emptyList())

        // A handful of candidates, not the whole page: the duplicates TMDB
        // holds for one studio sit at the top of its own relevance ranking, and
        // a brand nobody uploaded artwork for must not cost twenty reads. The
        // search itself is one request; everything after it is guarded by the
        // name match.
        var checked = 0
        for (result in results) {
            if (result.id in excludeIds) continue
            if (!entityNamesMatch(brand, result.name)) continue
            if (checked++ >= MAX_BRAND_NAME_CANDIDATES) break
            val logos = entityLogoUrls(result.id, company = true)
            if (logos.isNotEmpty()) return logos
        }
        return emptyList()
    }

    /**
     * The top title of one browse dimension, for a tile's backdrop: a single
     * discover page, filtered by the dimension the shortcut names.
     *
     * One page on purpose. A tile is a shortcut, not a screen: the genre /
     * network / decade page behind it resolves its own rails when it opens,
     * and paying a screen's worth of discover calls here would put that work
     * in front of the first frame of Home. The popularity sort plus the same
     * vote floor the browse rails use keeps an audience-less stub off the
     * tile, and only a result that actually carries a backdrop is worth
     * returning - the tile draws it full-bleed.
     */
    private suspend fun browseSpotlightItem(
        categoryKey: String,
        entryId: Int,
        providerId: Int?,
        networkOrCompanyId: Int?,
        networkIsCompany: Boolean
    ): TmdbDiscoverItem? {
        if (apiKey.isBlank()) return null

        // Exactly ONE dimension filter is set: TMDB ANDs a discover's filters,
        // and a service that carries both a watch-provider id and a brand id
        // would then ask for titles on the service AND made by the brand,
        // which is a different (and much smaller) shelf than either. The
        // provider id wins where there is one, matching the screen's rails.
        val withCompanies =
            when {
                categoryKey == "studios" -> entryId.toString()

                categoryKey == "services" &&
                    providerId == null &&
                    networkIsCompany ->
                    (networkOrCompanyId ?: entryId).toString()

                else -> null
            }

        val withNetworks =
            if (
                categoryKey == "services" &&
                providerId == null &&
                !networkIsCompany
            ) {
                (networkOrCompanyId ?: entryId).toString()
            } else {
                null
            }

        return runCatchingCancellable {
            api.discoverMovieGeneric(
                apiKey = apiKey,
                page = 1,
                sortBy = "popularity.desc",
                voteCountGte = 50,
                withGenres =
                    entryId.toString().takeIf { categoryKey == "genres" },
                withKeywords =
                    entryId.toString().takeIf { categoryKey == "keywords" },
                withCompanies = withCompanies,
                withNetworks = withNetworks,
                withWatchProviders =
                    providerId?.toString().takeIf { categoryKey == "services" },
                watchRegion = "US".takeIf { categoryKey == "services" },
                primaryReleaseDateGte =
                    "$entryId-01-01".takeIf { categoryKey == "decades" },
                primaryReleaseDateLte =
                    "${entryId + 9}-12-31".takeIf { categoryKey == "decades" }
            ).results
                .firstOrNull { !it.backdropPath.isNullOrBlank() }
        }.getOrNull()
    }

    /**
     * The artwork a Home Browse shortcut's tile - and the hero above it -
     * should draw, resolved in the shortcut's OWN dimension.
     *
     * A browse chip carries no manifest and no curated art: the browse
     * browser's chips are text, and the destination screen resolves its own
     * header. So the tile is given what that screen shows first - one
     * spotlight title from the dimension itself (a genre's most popular, a
     * studio's, a decade's, a service's, a keyword's) - as its backdrop. A
     * service or a studio additionally draws its BRAND mark over that backdrop,
     * the same rule the studio screen's header follows (see
     * [getEntityLogoUrls]). A genre, a keyword or a decade draws nothing over it
     * and says its own name instead: the spotlight title's wordmark is not that
     * category's name, and standing it in for one put a Spider-Man logo on the
     * Adventure tile and across the hero while the viewer scrolled Genres or
     * Decades. A collection needs neither, because it has artwork of its own
     * (see [getCollection]).
     *
     * Failure-tolerant throughout: a shortcut TMDB has no art for comes back
     * with null fields (or as null), and the tile keeps the name it draws today
     * rather than showing a blank card.
     */
    suspend fun getBrowseShortcutArt(
        categoryKey: String,
        entryId: Int,
        providerId: Int? = null,
        networkOrCompanyId: Int? = null,
        networkIsCompany: Boolean = false,
        name: String? = null,
        originalsCompanyId: Int? = null
    ): BrowseShortcutArt? {
        if (apiKey.isBlank()) return null

        var backdropUrl: String? = null
        var clearlogoUrl: String? = null
        var clearlogoUrls: List<String> = emptyList()

        if (categoryKey == "collections") {
            val collection = getCollection(entryId)
            backdropUrl =
                collection?.backdropPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { BACKDROP_BASE + it }
                    ?: collection?.posterPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { POSTER_BASE + it }
        } else {
            val spotlight = browseSpotlightItem(
                categoryKey = categoryKey,
                entryId = entryId,
                providerId = providerId,
                networkOrCompanyId = networkOrCompanyId,
                networkIsCompany = networkIsCompany
            )

            // The spotlight still is the tile's and the hero's backdrop - a
            // category does reach the catalog through titles, so one of its
            // popular ones is what the card has to show - and deliberately ONLY
            // the backdrop. The spotlight's wordmark used to be taken here as
            // the tile's clearlogo, which is what put a Spider-Man logo on the
            // Adventure tile: a category's identity is its name, not one
            // title's, so nothing title-shaped is borrowed here.
            if (spotlight != null) {
                backdropUrl =
                    spotlight.backdropPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { BACKDROP_BASE + it }
            }
        }

        // The only clearlogo a browse shortcut has: a service's or a studio's
        // own brand mark (see [getEntityLogoUrls]). Nothing else may lend a
        // title's wordmark to a category that owns no mark.
        if (categoryKey == "services" || categoryKey == "studios") {
            // The WHOLE ranked list, not just its head: the tile and the hero
            // walk it past marks that cannot be drawn on a dark surface
            // (BrandMarkLogo), and a service whose top mark is a blank plate
            // then still gets one of its own.
            val brandCandidates = runCatchingCancellable {
                getEntityLogoUrls(
                    entityId = networkOrCompanyId ?: entryId,
                    isNetwork = categoryKey == "services" && !networkIsCompany,
                    providerId = providerId,
                    // The chip's own label and production company: the name
                    // is what lets a twin be verified, and the company id is
                    // the brand's other entity TMDB may have given the mark
                    // to (see getEntityLogoUrls).
                    name = name,
                    originalsCompanyId = originalsCompanyId
                )
            }.getOrDefault(emptyList())

            if (brandCandidates.isNotEmpty()) {
                clearlogoUrls = brandCandidates
                clearlogoUrl = brandCandidates.first()
            }
        }

        if (backdropUrl == null && clearlogoUrl == null) return null

        return BrowseShortcutArt(
            backdropUrl = backdropUrl,
            clearlogoUrl = clearlogoUrl,
            clearlogoUrls = clearlogoUrls
        )
    }

    /**
     * One entity's ranked logo URLs (company or network space), best first,
     * with marks too small to draw filtered out.
     */
    private suspend fun entityLogoUrls(entityId: Int, company: Boolean): List<String> {
        val fetched = runCatchingCancellable {
            if (company) {
                api.getCompanyImages(entityId, apiKey).logos
            } else {
                api.getNetworkImages(entityId, apiKey).logos
            }
        }.getOrDefault(emptyList())

        val ranked = fetched
            .filter { !it.filePath.isNullOrBlank() }
            // Junk stubs, which would otherwise win on their dimensions alone:
            // TNT's network entry is one 1x1 pixel. Dropping them is what lets
            // the entity's other marks - or its twin's - be offered at all.
            .filter {
                (it.width ?: 0) >= MIN_LOGO_PIXELS && (it.height ?: 0) >= MIN_LOGO_PIXELS
            }
            .sortedWith(
                compareByDescending<TmdbCompanyLogo> { it.iso6391 == "en" }
                    .thenByDescending { it.iso6391 == null }
                    .thenByDescending { it.voteAverage ?: 0.0 }
                    .thenByDescending { it.width ?: 0 }
            )
        if (ranked.isEmpty()) return emptyList()

        // An English logo first, then the highest-voted, widest one - except
        // that a solid filled badge (ABC's top-voted logo is a filled disc)
        // whitens into an anonymous circle, so when the top-ranked mark is one
        // and the entity offers a letterform too, the letterform goes first
        // and the badge is kept as the last resort.
        val ordered = if (ranked.first().filePath?.let { isSolidBadge(it) } == true) {
            ranked.drop(1) + ranked.first()
        } else {
            ranked
        }
        return preferWideWordmark(ordered).map { LOGO_BASE + it.filePath }
    }

    /**
     * Promotes a wide wordmark above a square-ish icon.
     *
     * A brand's mark is frequently filed twice - the ICON alone (Peacock's
     * colorful dots, a stand-alone glyph) and the wide wordmark lockup - and
     * the icon can win the ranking on its vote average. Drawn on a tile or in
     * the Home hero, the icon is a fragment of the logo: Peacock showed its
     * row of dots and no name. When the current head is clearly square and some
     * candidate is clearly wide, the wide one leads. Ties, single-candidate
     * lists, and lists already headed by a wide mark are returned untouched, so
     * the common case costs nothing and changes nothing.
     */
    private fun preferWideWordmark(
        ranked: List<TmdbCompanyLogo>
    ): List<TmdbCompanyLogo> {
        val head = ranked.firstOrNull() ?: return ranked
        if (markAspect(head) >= SQUARE_MARK_MAX_ASPECT) return ranked
        val wide = ranked.firstOrNull { markAspect(it) >= WIDE_MARK_MIN_ASPECT }
            ?: return ranked
        return listOf(wide) + ranked.filterNot { it === wide }
    }

    /** A mark's width/height, or 0 when TMDB reported no dimensions. */
    private fun markAspect(logo: TmdbCompanyLogo): Float {
        val width = logo.width ?: return 0f
        val height = logo.height ?: return 0f
        return if (height <= 0) 0f else width.toFloat() / height
    }

    /**
     * The logo TMDB holds for a US watch provider, or null when the registry
     * has none for it.
     *
     * The provider registry is what drives every "where to watch" row, and its
     * entry carries the brand's own mark - the only source for a streamer whose
     * company/network pages have no artwork at all. Both media types are read
     * because a service can be listed under one and not the other (fuboTV is
     * TV-only, ALLBLK movie-and-TV).
     *
     * Memoized for the session: this changes about as often as TMDB adds a
     * service. A failure is not cached, so it can be retried.
     */
    private suspend fun watchProviderLogoUrl(providerId: Int): String? {
        watchProviderLogos?.let { cached ->
            return cached[providerId]?.let { LOGO_BASE + it }
        }
        val byId = mutableMapOf<Int, String>()
        listOf(true, false).forEach { movie ->
            val results = runCatchingCancellable {
                if (movie) api.getWatchProvidersMovie("US", apiKey).results
                else api.getWatchProvidersTv("US", apiKey).results
            }.getOrDefault(emptyList())
            results.forEach { provider ->
                provider.logoPath?.takeIf { it.isNotBlank() }?.let { path ->
                    byId[provider.providerId] = path
                }
            }
        }
        if (byId.isNotEmpty()) watchProviderLogos = byId
        return byId[providerId]?.let { LOGO_BASE + it }
    }

    /**
     * True when the logo is a solid filled badge - a near-square mark with most
     * of its bounding box opaque (ABC's top-voted logo is a filled disc).
     * White-tinted on the dark header these read as an anonymous circle, so the
     * ranking demotes them when a letterform option exists. Downloads a w185
     * thumbnail (a few KB) for the path it is asked about; any failure returns
     * false (keep the pick).
     */
    private suspend fun isSolidBadge(filePath: String): Boolean = runCatchingCancellable {
        // Network + decode must stay off the caller's (Main) dispatcher.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = okhttp3.Request.Builder()
                .url("https://image.tmdb.org/t/p/w185$filePath")
                .build()
            TmdbRepository.sharedOkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val bytes = response.body?.bytes() ?: return@withContext false
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size
                ) ?: return@withContext false
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                if (pixels.isEmpty()) return@withContext false
                var opaque = 0
                for (pixel in pixels) {
                    if (((pixel ushr 24) and 0xFF) > 200) opaque++
                }
                val coverage = opaque.toFloat() / pixels.size
                val aspect = bitmap.width.toFloat() / bitmap.height
                coverage > 0.80f && aspect in 0.70f..1.40f
            }
        }
    }.getOrDefault(false)

    /** Company/network metadata (description, headquarters, origin country). */
    suspend fun getEntityDetail(entityId: Int, isNetwork: Boolean): TmdbCompanyDetail? {
        if (apiKey.isBlank()) return null

        if (isNetwork) {
            // Networks have their own endpoint; fall back to the company shape.
            val networkDetail = runCatchingCancellable {
                api.getNetworkDetail(entityId, apiKey)
            }.getOrNull()
            if (networkDetail != null && !networkDetail.name.isNullOrBlank()) {
                return networkDetail
            }
            return runCatchingCancellable { api.getCompanyDetail(entityId, apiKey) }.getOrNull()
        }
        return runCatchingCancellable { api.getCompanyDetail(entityId, apiKey) }.getOrNull()
    }

    private fun imdbResolutionKey(tmdbId: Int, type: String): String {
        return "${normalizeType(type)}::$tmdbId"
    }

    private fun normalizeType(type: String): String =
        normalizeMediaType(type)

    companion object {

        /**
         * The media type every TMDB call - and every key derived from one - is
         * filed under.
         *
         * Shared rather than duplicated: the landscape artworks on Home and in
         * the KB folders key their entries on this, and a reader spelling it
         * differently from the writer finds nothing. That failure is silent and
         * shows up only for the titles an add-on happens to type oddly ("tv",
         * "anime.series"), which is exactly how two normalizers lived here long
         * enough to disagree.
         */
        internal fun normalizeMediaType(type: String): String {
            return when (type.lowercase().trim()) {
                "movie", "anime.movie" -> "movie"
                "series", "show", "tv", "anime", "anime.series" -> "series"
                else -> type.lowercase().trim()
            }
        }

        @Volatile
        private var instance: TmdbRepository? = null

        /**
         * Process-wide instance. Context is only used on first construction
         * (applicationContext is retained); afterwards it is ignored, so
         * passing an Activity context from any call site is leak-safe.
         */
        fun getInstance(context: Context): TmdbRepository =
            instance ?: synchronized(this) {
                instance ?: TmdbRepository(context).also { instance = it }
            }

        @Volatile
        private var sharedClient: OkHttpClient? = null

        /**
         * Lazily built, process-wide client for TMDB API traffic - the shared
         * base client itself, so TMDB reuses the sockets and threads every
         * other feature client in the process already holds open. Identical
         * configuration to a private builder (both are OkHttp defaults), just
         * one pool instead of two.
         */
        fun sharedOkHttpClient(): OkHttpClient =
            sharedClient ?: synchronized(this) {
                sharedClient ?: BaseHttpClient.get().also { sharedClient = it }
            }

        const val PROFILE_BASE = "https://image.tmdb.org/t/p/w185"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
        const val POSTER_BASE = "https://image.tmdb.org/t/p/w500"
        // w780, not original: company/network logo PNGs at "original" are
        // routinely 1500-2500px wide (hundreds of KB to a few MB). They
        // are drawn into a 360dp header slot and force-decoded in software
        // for the pixel analysis behind BrandLogo, so the original size
        // only made headers look logo-less for as long as the download
        // took.
        const val LOGO_BASE = "https://image.tmdb.org/t/p/w780"

        /**
         * The stub threshold, on both axes. TMDB carries a handful of
         * degenerate entries - TNT's network logo is one 1x1 pixel - that make
         * a brand look logo-less once the header has drawn them.
         *
         * Deliberately far below the size of a real mark: plenty of brands
         * publish nothing but a small, short-and-wide wordmark (REELZ at
         * 255x42, BET at 140x40, Angel Studios at 90x22) and they render
         * today, so a filter that removed them would be the bug. Only artwork
         * too small to be anything but a placeholder is dropped here; the
         * screen walks past an unreadable mark on its own.
         */
        private const val MIN_LOGO_PIXELS = 16

        /**
         * Aspect bounds for the icon-vs-wordmark choice in [preferWideWordmark].
         * A mark at or under the first is treated as an icon; a candidate at or
         * over the second is a wordmark. The gap is deliberate: only a clear
         * difference reorders the list.
         */
        private const val SQUARE_MARK_MAX_ASPECT = 1.25f
        private const val WIDE_MARK_MIN_ASPECT = 1.6f

        /** Company pages the brand-name fallback will read before giving up. */
        private const val MAX_BRAND_NAME_CANDIDATES = 5
        private const val MAX_IMDB_DISK_AGE_MS = 90L * 24L * 60L * 60L * 1000L

        /**
         * How many JSON-cache writes go by between budget checks. See
         * [TmdbRepository.cacheJson]: small enough that one session cannot
         * meaningfully overshoot the budget, large enough that the check
         * (which reads the table's whole size index) is invisible next to the
         * network fetches that produced those writes.
         */
        private const val JSON_CACHE_TRIM_EVERY_WRITES = 32
    }
}
