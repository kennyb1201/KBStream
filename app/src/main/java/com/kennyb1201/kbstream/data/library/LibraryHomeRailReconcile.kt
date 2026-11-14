package com.kennyb1201.kbstream.data.library

import android.content.Context
import com.kennyb1201.kbstream.data.anime.AnimeLists
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.bingecat.bingecatShelfId
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListUserList
import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListsAnswer
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
     */
    suspend fun runOnce(context: Context): Boolean {
        val appContext = context.applicationContext
        if (remoteSourcesPinned(appContext).isEmpty()) return false
        delay(STARTUP_DELAY_MS)
        val wanted = remoteSourcesPinned(appContext)
        if (wanted.isEmpty()) return false
        return LibraryHomeRails.reconcilePins(appContext, liveListIds(appContext, wanted))
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
        wanted: Set<String>
    ): Map<String, List<LibraryHomeRailLiveList>> {
        val live = mutableMapOf<String, List<LibraryHomeRailLiveList>>()

        if (LibrarySource.TRAKT_LIST.name in wanted) {
            val trakt = TraktRepository.getInstance(context)
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
            val bingecat = BingeCatRepository.getInstance(context)
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
