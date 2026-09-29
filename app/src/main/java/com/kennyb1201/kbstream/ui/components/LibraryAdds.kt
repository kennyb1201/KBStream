package com.kennyb1201.kbstream.ui.components

import android.content.Context
import com.kennyb1201.kbstream.data.library.LibraryMirror
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListEntry
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library actions of a long-press menu, spelled once.
 *
 * Every surface that renders a title has the same menu, and the rows in it are
 * the same two: save the title to My List, or pick a personal list or watchlist
 * for it. What differs between screens is only what the screen happens to know
 * about the title it was long-pressed on — the Home rails and Search carry a
 * full id pair, the browse screens (actor, studio/network, genre, decade,
 * collection) carry a TMDB id and look the IMDB one up for their watched
 * badges, and the KB folder rows carry whatever their add-on sent.
 *
 * That difference used to decide whether an add worked. A target built with a
 * TMDB id and a null IMDB id added to My List but reached no tracker: Simkl and
 * MDBList both key their watchlists off whichever ids they are given, and the
 * one they were given here was the id the *other* half of the app's own
 * lookups does not use. Nothing said so — the row simply did nothing visible.
 *
 * So the IMDB id is resolved here, once, for every entry point, every add goes
 * through the same pipeline, and every add reports its outcome. [resolve] is
 * the only place that knows a TMDB id can be turned into an IMDB one, and it is
 * deliberately best-effort: an offline device adds to My List with the id it
 * has rather than refusing.
 *
 * All of it runs on [scope], never on the caller's. A menu is dismissed the
 * instant one of these is pressed, so anything started on the menu's own
 * composition scope is cancelled before it can resolve an id or reach a
 * tracker — which is exactly how an add could tick, close, and change nothing.
 */
internal object LibraryAdds {

    /**
     * Process-lifetime, like [LibraryMirror]'s mirror scope and for the same
     * reason: the thing that started the work is gone a frame later. The
     * [SupervisorJob] keeps one add's failure from cancelling the next one.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True when [target] is already on this profile's My List. */
    fun inMyList(context: Context, target: LibraryAddTarget): Boolean =
        LocalLibraryStore.isInMyList(
            context,
            target.mediaType,
            target.imdbId,
            target.tmdbId
        )

    /**
     * The ", Simkl and MDBList" tail of the add row's description, from what is
     * actually connected — so the row promises exactly the mirrors it has.
     */
    fun mirrorSuffix(context: Context): String = buildString {
        if (LibraryMirror.simklConnected(context)) append(", Simkl")
        if (LibraryMirror.mdbListConnected(context)) append(" and MDBList")
    }

    /**
     * Adds [target] to My List and mirrors it to every connected tracker,
     * handing the outcome to [onResult] on the main thread.
     *
     * Fire-and-forget on purpose: the menu that called this is being dismissed
     * as it returns, and the alternative — a suspended call on the caller's
     * scope — is the bug this whole object exists to close.
     */
    fun add(
        context: Context,
        target: LibraryAddTarget,
        onResult: (Boolean) -> Unit = {}
    ) {
        runAdd(onResult) { addNow(context, target) }
    }

    /**
     * [add] without the launch, for callers already inside a coroutine that
     * wants the result inline (the picker, whose dialog stays open so the row
     * it pressed can report itself).
     */
    suspend fun addNow(context: Context, target: LibraryAddTarget): Boolean {
        val resolved = resolve(context, target)
        return LibraryMirror.addToLibrary(
            context = context,
            mediaType = resolved.mediaType,
            imdbId = resolved.imdbId,
            tmdbId = resolved.tmdbId,
            title = resolved.title,
            year = resolved.year,
            posterUrl = resolved.posterUrl
        )
    }

    /**
     * Runs one add on [scope] and reports whether it landed. The body never
     * sees a cancellation from the UI, so [onResult] is the only place a
     * caller learns the outcome — on the main thread, where Compose state
     * (a picker row's tick) can be written directly.
     */
    fun runAdd(onResult: (Boolean) -> Unit, body: suspend () -> Boolean) {
        scope.launch {
            val ok = runCatchingCancellable { body() }.getOrDefault(false)
            withContext(Dispatchers.Main.immediate) { onResult(ok) }
        }
    }

    /**
     * Adds [target] to whatever destination [row] names, with the row's own
     * outcome (not merely "a list was connected") as the answer.
     *
     * The routing lives in [libraryAddRoute] because that is the part that can
     * be wrong in silence: a row whose destination resolves to nothing used to
     * fall through to `false`, which the old dialog rendered as *no feedback at
     * all* — indistinguishable, from the sofa, from the press not registering.
     */
    suspend fun addToRow(
        context: Context,
        row: LibraryPickerRow,
        target: LibraryAddTarget
    ): Boolean = when (val route = libraryAddRoute(row)) {
        LibraryAddRoute.MyList -> addNow(context, target)

        LibraryAddRoute.MdbListWatchlist -> addToMdbListWatchlist(context, target)

        is LibraryAddRoute.MdbListList -> LibraryMirror.addToList(
            context = context,
            listId = route.id,
            mediaType = target.mediaType,
            imdbId = target.imdbId,
            tmdbId = target.tmdbId,
            title = target.title,
            year = target.year,
            posterUrl = target.posterUrl
        )

        is LibraryAddRoute.LocalList -> LocalLibraryStore.addToLocalList(
            context = context,
            listId = route.id,
            mediaType = target.mediaType,
            imdbId = target.imdbId,
            tmdbId = target.tmdbId,
            title = target.title,
            year = target.year,
            posterUrl = target.posterUrl
        )

        LibraryAddRoute.Unroutable -> false
    }

    /** The MDBList watchlist row: remote-only, and reports the real POST. */
    private suspend fun addToMdbListWatchlist(
        context: Context,
        target: LibraryAddTarget
    ): Boolean {
        if (!LibraryMirror.mdbListConnected(context)) return false
        return runCatchingCancellable {
            MdbListClient.addToWatchlist(
                context,
                listOf(
                    MdbListEntry(
                        title = target.title,
                        mediaType = LocalLibraryStore.normalizedType(target.mediaType),
                        year = target.year,
                        poster = target.posterUrl,
                        imdbId = target.imdbId?.takeIf { it.startsWith("tt") },
                        tmdbId = target.tmdbId
                    )
                )
            )
        }.getOrDefault(false)
    }

    /**
     * [target] with the two facts an add needs that the screen may not have
     * had: the IMDB id when it only knew the TMDB one, and the release year
     * when the catalog sent none.
     *
     * The year matters for the same reason the id does. A catalog is not
     * obliged to send one — the pinned "Top Today" rails send none at all — and
     * a title saved without one had no year under its poster in the Library
     * while every other row had one, and reached the tracker mirrors without it.
     * The Library tab's own enrichment pass can backfill the poster, but a
     * tracker that was never told cannot be fixed later.
     *
     * Skipped outright when both are already known, which is every TMDB-sourced
     * rail, and the lookup is the cached TMDB detail those rails' own artwork
     * and the Kids Mode ceiling already read — normally a memory hit rather than
     * a request. Best-effort throughout: an offline device adds with the ids it
     * has rather than refusing, and a title TMDB has no IMDB id for (unreleased,
     * or TMDB-only) keeps the TMDB one.
     */
    suspend fun resolve(context: Context, target: LibraryAddTarget): LibraryAddTarget {
        if (target.imdbId != null && target.year != null) return target

        val repository = TmdbRepository.getInstance(context)
        val mediaType = LocalLibraryStore.normalizedType(target.mediaType)
        var resolved = target

        if (resolved.imdbId == null) {
            val tmdbId = resolved.tmdbId ?: return resolved
            resolved = runCatchingCancellable {
                repository.resolveImdbId(tmdbId, mediaType)
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { resolved.copy(imdbId = it) }
                ?: resolved
        }

        if (resolved.year != null) return resolved

        val rawId = resolved.imdbId?.takeIf { it.isNotBlank() }
            ?: resolved.tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" }
            ?: return resolved

        val detail = runCatchingCancellable {
            repository.fetchEnrichedMetaCached(rawId, mediaType)
        }.getOrNull() ?: return resolved

        return resolved.copy(year = detail.releaseYear()?.toIntOrNull())
    }
}

/**
 * Where one Add-to-list row's press actually goes.
 *
 * A row is built from a list the app found — the built-in My List, a local
 * personal list, the MDBList watchlist, an MDBList personal list — and each of
 * those has its own store behind it. Spelling that out as a value instead of a
 * `when` inside the click handler is what makes the mapping testable, and
 * [LibraryAddRoute.Unroutable] is the row that used to fail in silence.
 */
internal sealed interface LibraryAddRoute {
    /** The profile's local My List, mirrors to every connected tracker. */
    data object MyList : LibraryAddRoute

    /** The account's MDBList watchlist (remote only). */
    data object MdbListWatchlist : LibraryAddRoute

    /** One MDBList personal list. */
    data class MdbListList(val id: Int) : LibraryAddRoute

    /** One local personal list. */
    data class LocalList(val id: Int) : LibraryAddRoute

    /** A row naming no destination at all: nothing to press, nothing to do. */
    data object Unroutable : LibraryAddRoute
}

/**
 * The destination [row] names. Local list ids are negative and MDBList ids
 * positive (local ids are derived from their creation time and negated), so
 * the sign is the whole test — and an id of zero is neither, which is
 * [LibraryAddRoute.Unroutable] rather than a list that cannot exist.
 */
internal fun libraryAddRoute(row: LibraryPickerRow): LibraryAddRoute {
    val list = row.list
    return when {
        row.isMyList -> LibraryAddRoute.MyList
        row.isMdbListWatchlist -> LibraryAddRoute.MdbListWatchlist
        list == null || list.id == 0 -> LibraryAddRoute.Unroutable
        list.id > 0 -> LibraryAddRoute.MdbListList(list.id)
        else -> LibraryAddRoute.LocalList(list.id)
    }
}
