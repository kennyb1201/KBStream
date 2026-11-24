package com.kennyb1201.kbstream.data.library

import android.content.Context
import com.kennyb1201.kbstream.data.anime.AnimeLists
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.bingecat.bingecatShelfId
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListUserList
import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListsAnswer
import com.kennyb1201.kbstream.data.reporting.UiFrameTrace
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import kotlinx.coroutines.delay

/**
 * The pinned Library lists, checked against the services at launch.
 *
 * A list deleted on Trakt's, MDBList's or BingeCat's own site has no event
 * behind it - there is nothing to subscribe to and nothing pushed - so the only
 * signal is the list's ABSENCE from a read. The Library reconciles that when it
 * refreshes (see LibraryViewModel.refresh); this does the same at launch, so a
 * Home rail pinned to a list that no longer exists does not wait for the viewer
 * to open the Library before it disappears.
 *
 * It costs a prefs read and nothing else on a profile with no pinned remote
 * list, which is most of them: [runOnce] returns before the delay, and the
 * network work only happens for a source that actually holds a pin. When there
 * is work, it is a handful of GETs on the Application's IO scope - never on the
 * launch path - and it waits [STARTUP_DELAY_MS] first so the first frame, the
 * Home rails and the catalog warm have the connection to themselves.
 *
 * The settle is not the whole story, because the walk usually starts after it:
 * the 0.9.425 cold-launch paste put the first Home step a second in
 * (`firstFrame=976ms`) and this pass finishing at `homeRailReconcile=5213ms` -
 * 2.5 s of settle and then 2.7 s of reads landing exactly under the first walk
 * (`frame: home-walk p50=33ms`, `image: decode duringFrame=380` of 589). So every
 * source waits the walk out before it reads (see [yieldToWalk]). Nothing here is
 * urgent enough to buy its own latency with the viewer's frames: a pin that
 * vanished at a website is a rail that should disappear, not one that has to
 * disappear this second.
 *
 * Everything here is best-effort: a failed or unconfigured source is left out
 * of [liveListIds] and prunes nothing (see [withoutVanishedPins]), because an
 * outage reported as "these lists are gone" would delete a working rail on this
 * device and publish that mistake to the account.
 *
 * What is "live" is the Library rail's own answer, saved community lists
 * included: a pin can name a list the viewer liked rather than one they own,
 * and only the source's full set can say whether that list still exists.
 */
object LibraryHomeRailReconcile {

    /** Housekeeping, not launch work: let the app settle before asking anyone. */
    const val STARTUP_DELAY_MS = 2_500L

    /**
     * One yield step: how long to wait before asking again whether a walk is on.
     *
     * The same 60 ms the catalog filter's behind-the-row pass sleeps, so the two
     * background legs concede on the same cadence instead of inventing one each.
     */
    private const val WALK_YIELD_STEP_MS = 60L

    /**
     * The longest one source will stand aside for a walk before it goes anyway.
     *
     * A bound rather than a pure wait, because the walk's own meter ends 350 ms
     * after its last step and a session that never closed would otherwise keep
     * every pin on this device unreconciled for the life of the process. Twenty
     * seconds is longer than any Home walk a viewer makes and shorter than a
     * launch, so the common case is "waited for the walk, then read", and the
     * stuck one is "read late" rather than "never read".
     */
    private const val WALK_YIELD_MAX_MS = 20_000L

    /** The sources a pin can name whose lists live at a service. */
    private val REMOTE_LIST_SOURCES = setOf(
        LibrarySource.TRAKT_LIST.name,
        LibrarySource.MDBLIST_LIST.name,
        LibrarySource.BINGECAT_LIST.name,
        // The anime trackers' lists live at their services too (spec F1): a list
        // deleted or renamed there is only visible as an absence, so a pinned
        // anime rail has to be checked with the rest at launch.
        LibrarySource.ANILIST_LIST.name,
        LibrarySource.KITSU_LIST.name,
        LibrarySource.MAL_LIST.name
    )

    /**
     * One pass over the pins, at most. Returns whether any pin was dropped.
     *
     * The pins are read twice on purpose: once before the delay, because the
     * common answer ("nothing pinned but a local list") must cost no wait at
     * all, and once after it, because a profile switch or a new pin in those
     * few seconds is what should be reconciled, not the older snapshot.
     *
     * The two services this pass reads arrive as parameters rather than being
     * fetched from their `getInstance` accessors (spec 2026-11-22, item 3). The
     * only caller is the Application, which is an injection point: handing the
     * instances in keeps the one-instance invariant while taking this object off
     * the accessor list. A test that wants a specific answer passes its own.
     */
    suspend fun runOnce(
        context: Context,
        traktRepository: TraktRepository,
        bingecatRepository: BingeCatRepository
    ): Boolean {
        val appContext = context.applicationContext
        if (remoteSourcesPinned(appContext).isEmpty()) return false
        delay(STARTUP_DELAY_MS)
        val wanted = remoteSourcesPinned(appContext)
        if (wanted.isEmpty()) return false
        return LibraryHomeRails.reconcilePins(
            appContext,
            liveListIds(appContext, wanted, traktRepository, bingecatRepository)
        )
    }

    /**
     * Stands aside while a metered walk is on screen.
     *
     * The walk is the viewer's own work and this pass is housekeeping, so the
     * contention has one correct direction: these reads are what should wait.
     * On the 0.9.425 launch paste the pass ran 2.7 s of reads beside a walk at
     * `p50=33ms` with `decode duringFrame=380` of 589 decodes and a slowest
     * decode of 2026 ms for a 640x360 out of the disk cache - a decode that is
     * waiting on the disk and the CPU, not on the decoder. Six GETs and their
     * JSON parses are exactly that kind of neighbour.
     *
     * Read through [UiFrameTrace.inWalkWindow], the same question the catalog
     * filter asks, so "the walk" means one thing in the whole app: a metered
     * gesture open, or closed within its 350 ms tail. The tail matters here for
     * the same reason it does there - the art a step asked for finishes after
     * the last step.
     */
    private suspend fun yieldToWalk() {
        var waited = 0L
        while (waited < WALK_YIELD_MAX_MS && UiFrameTrace.inWalkWindow()) {
            delay(WALK_YIELD_STEP_MS)
            waited += WALK_YIELD_STEP_MS
        }
    }

    /** The remote sources this profile has a pin for. */
    private fun remoteSourcesPinned(context: Context): Set<String> =
        LibraryHomeRails.pins(context)
            .map { it.source }
            .filterTo(mutableSetOf()) { it in REMOTE_LIST_SOURCES }

    /**
     * The live list of each of [wanted]. A source that did not answer - not
     * configured, no token, a failed read - is ABSENT rather than empty: its
     * pins are then left alone.
     *
     * Each row contributes EVERY spelling a pin for it may carry, beside the id
     * this build writes today (see [libraryHomeRailLiveList]) - not just the
     * current one. A pin written before the current form is not a deleted list,
     * and the prune used to read it as one, delete it and publish the deletion.
     * The row's own builder supplies both, so a list cannot be known by one name
     * here and another at the rail.
     *
     * A remote source answers with BOTH of its reads or with neither: a saved
     * community list is a Library row on the same rail as an own list (the
     * Library's full refresh reads them together for the same reason), so an
     * own-lists answer with the liked read failed would read every pinned saved
     * list as gone.
     */
    private suspend fun liveListIds(
        context: Context,
        wanted: Set<String>,
        traktRepository: TraktRepository,
        bingecatRepository: BingeCatRepository
    ): Map<String, List<LibraryHomeRailLiveList>> {
        val live = mutableMapOf<String, List<LibraryHomeRailLiveList>>()

        if (LibrarySource.TRAKT_LIST.name in wanted) {
            yieldToWalk()
            val trakt = traktRepository
            if (trakt.isConfigured() && trakt.hasToken()) {
                val own = runCatchingCancellable { trakt.userLists() }.getOrNull()
                // Only a Loaded answer is a statement about which lists exist:
                // NotConnected / Failed / Blocked mean this source did not
                // answer, and the map then leaves its pins alone.
                val liked = runCatchingCancellable { trakt.likedPublicLists() }.getOrNull()
                    ?.let { answer -> (answer as? PublicListsAnswer.Loaded)?.value }
                if (own != null && liked != null) {
                    val ownRows = own.mapNotNull { list ->
                        val traktId = list.ids?.trakt ?: 0
                        val remoteId = list.ids?.slug
                            ?: traktId.takeIf { it > 0 }?.toString()
                            ?: return@mapNotNull null
                        LibraryList(
                            id = traktListShelfId(traktId),
                            name = list.name.orEmpty(),
                            itemCount = list.itemCount ?: 0,
                            source = LibrarySource.TRAKT_LIST,
                            remoteId = remoteId
                        )
                    }
                    // A saved community list is a row on the same rail, so its
                    // pin has to be counted as live or every launch would prune
                    // it. The row comes from the Library's own builder, which is
                    // what makes its `id:<band>` spelling the one the rail and
                    // the pin both use - deriving that id here a second way is
                    // how a live pin was read as a gone list before.
                    val likedRows = liked.map { card ->
                        likedListRow(LibrarySource.TRAKT_LIST, card)
                    }
                    live[LibrarySource.TRAKT_LIST.name] =
                        (ownRows + likedRows).map { libraryHomeRailLiveList(it) }
                }
            }
        }

        if (LibrarySource.MDBLIST_LIST.name in wanted && MdbListClient.isConfigured(context)) {
            yieldToWalk()
            // OrNull: a failed MDBList read answers empty from the plain call,
            // and an empty answer here would be read as "every list is gone".
            val own = runCatchingCancellable { MdbListClient.getUserListsOrNull(context) }.getOrNull()
            // Only a Loaded answer is a statement about which lists exist:
            // NotConnected / Failed / Blocked mean this source did not answer,
            // and the map then leaves its pins alone. Same rule as the Trakt leg.
            val liked = runCatchingCancellable { MdbListClient.likedPublicLists(context) }.getOrNull()
                ?.let { answer -> (answer as? PublicListsAnswer.Loaded)?.value }
            mdbListLiveListIds(own, liked)?.let { ids ->
                live[LibrarySource.MDBLIST_LIST.name] = ids
            }
        }

        if (LibrarySource.BINGECAT_LIST.name in wanted) {
            yieldToWalk()
            val bingecat = bingecatRepository
            if (bingecat.isReady()) {
                // Stale-while-revalidate, and it keeps its cache after a FAILED
                // revalidation: only a snapshot this pass actually fetched may
                // prune, or a half-hour-old cache missing a list created on
                // another device would delete a live rail on this one.
                //
                // And only when the LISTS leg answered: a refresh stamps the
                // snapshot even when half of it failed, and this is the caller
                // that WRITES what it decides - `reconcilePins` persists the
                // pruned set and enqueues it to the account, so one failed lists
                // read here would delete every BingeCat rail on every device.
                val readAt = System.currentTimeMillis()
                val snapshot = runCatchingCancellable { bingecat.snapshot() }.getOrNull()
                if (snapshot != null && snapshot.listsComplete &&
                    snapshot.refreshedAtMs >= readAt
                ) {
                    live[LibrarySource.BINGECAT_LIST.name] =
                        snapshot.lists.mapIndexedNotNull { index, list ->
                            list.name.takeIf { it.isNotBlank() }?.let { name ->
                                libraryHomeRailLiveList(
                                    LibraryList(
                                        // Shelf id by position, exactly as the
                                        // rail builds it; the pin's identity is
                                        // the ref, and this only covers a row
                                        // whose ref is blank.
                                        id = bingecatShelfId(index),
                                        name = name,
                                        itemCount = list.itemCount,
                                        source = LibrarySource.BINGECAT_LIST,
                                        remoteId = list.ref
                                    )
                                )
                            }
                        }
                }
            }
        }

        // Every anime source reads together, once: one object owns the three
        // services' reads (AniList/Kitsu by username, MAL by connection), and a
        // source that did not answer is ABSENT from `answered` - so its pins are
        // left alone rather than pruned. Only a real answer contributes, exactly
        // as the Library's own refresh builds this map.
        val animeWanted = wanted.mapNotNull { name ->
            runCatching { LibrarySource.valueOf(name) }.getOrNull()?.takeIf { it.isAnimeList }
        }
        if (animeWanted.isNotEmpty()) {
            yieldToWalk()
            val own = runCatchingCancellable { AnimeLists.own(context) }.getOrNull()
            own?.answered?.forEach { source ->
                if (source in animeWanted) {
                    live[source.name] = own.lists
                        .filter { it.source == source }
                        .map { row ->
                            libraryHomeRailLiveList(
                                LibraryList(
                                    id = animeListShelfId(row.source, row.ref),
                                    name = row.name,
                                    itemCount = row.itemCount,
                                    source = row.source,
                                    remoteId = row.ref
                                )
                            )
                        }
                }
            }
        }

        return live
    }

    /**
     * The MDBList source's live lists, or null when that source did not answer.
     *
     * Both reads behind the source must have answered before either
     * contributes: an own-lists answer with the liked read failed would
     * otherwise read every pinned saved list as gone (same rule as the Library
     * refresh's live-set build). A successful read with a genuinely empty set
     * is a real answer, so it still publishes.
     *
     * Own lists are rows whose `remoteId` is absent, so the identity this build
     * writes is `"id:<n>"` and an older pin carrying the bare `"<n>"` is the
     * same list (see [libraryHomeRailLiveSpellings]). A LIKED (saved public)
     * list is a row built by [likedListRow] - the Library's own builder - whose
     * `remoteId` is the card's ref, so the pin's current spelling is that bare
     * ref and its `id:<band>` form is the other one. Contributing only the bare
     * ref is what pruned every pin written under the band at launch.
     */
    internal fun mdbListLiveListIds(
        own: List<MdbListUserList>?,
        liked: List<PublicListCard>?
    ): List<LibraryHomeRailLiveList>? {
        if (own == null || liked == null) return null
        val rows = own.map { list ->
            LibraryList(
                id = list.id,
                name = list.name,
                itemCount = list.itemCount,
                source = LibrarySource.MDBLIST_LIST
            )
        } + liked.map { card ->
            likedListRow(LibrarySource.MDBLIST_LIST, card)
        }
        return rows.map { libraryHomeRailLiveList(it) }
    }
}
