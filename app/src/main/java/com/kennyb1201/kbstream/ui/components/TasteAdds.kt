package com.kennyb1201.kbstream.ui.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kennyb1201.kbstream.data.bingecat.BingeCatFavoriteRules
import com.kennyb1201.kbstream.data.bingecat.BingeCatFavoriteRules.Leg
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.library.TasteSignals
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one write path behind every thumb in the app - Detail's row, the phone's
 * row, the because-you-watched panel and the poster menus' rows.
 *
 * The local taste signal is the press's real effect and is written here, on the
 * caller's thread, exactly as before: the store owns it, repaints from it, and
 * feeds the blend. What this adds is the BingeCat leg the favorites spec asks
 * for - the thumbs are a favorites toggle there - fired beside it:
 *
 *  - it never blocks, replaces or undoes the local write, and a failure leaves
 *    the thumb painting what the store holds;
 *  - it only fires for a mapped profile whose grant carries `lists:write`
 *    (see [BingeCatRepository.canFavorite]), so an unmapped or unapproved
 *    profile keeps its thumbs local-only and silent;
 *  - it runs on a process-lifetime scope, because the surface that pressed it is
 *    gone a frame later (the same reason [LibraryAdds] has one).
 *
 * Two surfaces once computed `nextSignal` and called `setSignal` themselves, and
 * a third added later would have added a fourth copy - which is how one of them
 * ends up without the leg. There is one copy: this.
 */
internal object TasteAdds {

    /**
     * What one press produced: the signal the thumb was painting, the one it
     * left behind (`null` = cleared), and whether the STORE accepted the write.
     *
     * Surfaces report from this rather than from what they asked for, the
     * bookmark toggle's own rule: a press can never claim a signal that did not
     * persist.
     */
    data class Press(val current: String?, val next: String?, val stored: Boolean)

    /**
     * Process-lifetime, like [LibraryAdds]'s: the write is started by a press on
     * a row that is being dismissed as it returns, and it must not die with the
     * composition that started it. The [SupervisorJob] keeps one leg's failure
     * from canceling the next.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One warm-up read at a time, however many thumbs are on screen. */
    private val warming = AtomicBoolean(false)

    /**
     * Bumped when a favorites leg (or the warm-up read) has finished.
     *
     * A thumb's lit state is the local signal OR BingeCat membership, and the
     * membership comes from a snapshot this device re-reads - so the surface
     * painting it has to re-read too when a leg lands, or a thumb that just
     * favorited a title would keep painting itself as unfavorited.
     * [rememberTasteSignal] keys on this, the same way the Library button keys
     * on [LibraryAdds.revision].
     */
    var revision by mutableIntStateOf(0)
        private set

    /**
     * The press itself: the local signal, then the favorites leg.
     *
     * The leg follows the spec's table (see [BingeCatFavoriteRules.legFor]) and
     * is launched, never awaited - a thumb must not wait on a network round trip
     * to paint. It is skipped entirely when the store refused the local write: a
     * press this app could not record must not become a remote change either.
     */
    fun press(
        context: Context,
        mediaType: String?,
        title: String?,
        posterUrl: String?,
        year: Int?,
        ids: List<String?>,
        signal: String
    ): Press {
        // The signal the thumb was PAINTING, which is the local one or BingeCat
        // membership (F3) - not the local one alone. A title favorited on the
        // website paints a lit thumb with no local signal, and pressing that lit
        // thumb has to be the clear the viewer sees it as; keying the press on
        // the local store alone would add the title it is already on.
        val current = paintedSignal(context, mediaType, title, year, ids)
        val next = TasteSignals.nextSignal(current, signal)
        val stored = if (next == null) {
            TasteSignals.clearSignal(
                context = context,
                mediaType = mediaType,
                title = title,
                year = year,
                ids = ids
            )
        } else {
            TasteSignals.setSignal(
                context = context,
                mediaType = mediaType,
                title = title,
                posterUrl = posterUrl,
                year = year,
                ids = ids,
                signal = next
            )
        }
        if (stored) launchFavoritesLeg(context, mediaType, title, ids, current, next)
        return Press(current = current, next = next, stored = stored)
    }

    /**
     * The signal a thumb paints for one title: the local one, or `LIKE` when the
     * profile holds no local signal but the title is on the mapped profile's
     * BingeCat Favorites (the favorites spec's F3).
     *
     * `DISLIKE` is never read back from BingeCat: the API has no dislike to read,
     * so a dislike is this device's own opinion and paints only from the store.
     *
     * The first call also asks for the snapshot when this device has none - one
     * TTL'd read, the same one the Library tab makes, so a title favorited on
     * Bingecat's website opens its page with the thumb already lit instead of
     * waiting for the Library to be visited first.
     */
    fun paintedSignal(
        context: Context,
        mediaType: String?,
        title: String?,
        year: Int?,
        ids: List<String?>
    ): String? =
        TasteSignals.signalFor(context, mediaType, title, year, ids)
            ?: favoriteSignal(context, mediaType, ids)

    /**
     * The BingeCat half of [paintedSignal]: `LIKE` while the title is on the
     * mapped profile's favorites, null otherwise (including for every reason the
     * leg would not fire - unmapped, no token, no `lists:write`).
     */
    fun favoriteSignal(context: Context, mediaType: String?, ids: List<String?>): String? {
        val identity = identityOf(mediaType, ids) ?: return null
        val app = context.applicationContext
        val repository = BingeCatRepository.getInstance(app)
        if (!repository.canFavorite()) return null
        warmFavorites(app, repository)
        val favorited = repository.favoritesContains(
            mediaType = identity.mediaType,
            imdbId = identity.imdbId,
            tmdbId = identity.tmdbId
        )
        return if (favorited) TasteSignals.LIKE else null
    }

    /**
     * Runs the favorites leg for one press, or returns without a request when
     * the answer is [Leg.NONE] - an unmapped profile, a grant without
     * `lists:write`, a title this app cannot name, or a title the cache does not
     * hold as a favorite.
     */
    private fun launchFavoritesLeg(
        context: Context,
        mediaType: String?,
        title: String?,
        ids: List<String?>,
        current: String?,
        next: String?
    ) {
        val identity = identityOf(mediaType, ids) ?: return
        val app = context.applicationContext
        val repository = BingeCatRepository.getInstance(app)
        // Gate 1 and gate 2 in one answer: ready (configured, token, mapped) and
        // granted lists:write. Either one false is the silent local-only state.
        if (!repository.canFavorite()) return
        scope.launch {
            val leg = BingeCatFavoriteRules.legFor(
                current = current,
                next = next,
                favorited = repository.favoritesContains(
                    mediaType = identity.mediaType,
                    imdbId = identity.imdbId,
                    tmdbId = identity.tmdbId
                )
            )
            if (leg == Leg.NONE) return@launch
            // The write's own boolean is logged by the repository; a failure
            // here is not the thumb's business, because the local signal stands.
            when (leg) {
                Leg.ADD -> repository.addToFavorites(
                    isMovie = identity.isMovie,
                    imdbId = identity.imdbId,
                    tmdbId = identity.tmdbId,
                    title = title
                )

                Leg.REMOVE -> repository.removeFromFavorites(
                    isMovie = identity.isMovie,
                    imdbId = identity.imdbId,
                    tmdbId = identity.tmdbId,
                    title = title
                )

                Leg.NONE -> Unit
            }
            withContext(Dispatchers.Main.immediate) { revision += 1 }
        }
    }

    /**
     * One snapshot read when nothing is cached FOR THIS PROFILE, so the paint has
     * rows to read.
     *
     * Deliberately not per paint: [BingeCatRepository.snapshot] answers from its
     * TTL'd cache, and [warming] makes sure a screenful of thumbs asks once. A
     * snapshot read under another profile does not count as cached here - it is
     * that profile's Favorites, and [BingeCatRepository.favoritesContains]
     * refuses it anyway, so warming again is what makes a switch paint the truth
     * instead of an empty thumb.
     */
    private fun warmFavorites(context: Context, repository: BingeCatRepository) {
        val cached = repository.cachedSnapshot()
        if (cached != null && cached.profileId == ProfileStorage.activeProfileId(context)) return
        if (!warming.compareAndSet(false, true)) return
        scope.launch {
            try {
                repository.snapshot()
            } finally {
                warming.set(false)
                withContext(Dispatchers.Main.immediate) { revision += 1 }
            }
        }
    }

    /** The BingeCat identity of one title, or null when this app cannot name it. */
    private data class Identity(
        val mediaType: String,
        val isMovie: Boolean,
        val imdbId: String?,
        val tmdbId: Int?
    )

    /**
     * Reads the two id flavors out of the spellings a surface hands the store
     * (`tt0133093`, `tmdb:603`), the same ones the taste keys are built from.
     *
     * A media type is required: BingeCat's `MediaIds` has no "unknown" type, and
     * guessing movie for a series would favorite the wrong medium. An id the app
     * does not have in either flavor is not sent - the write rules would refuse
     * it anyway, and this is the earlier, cheaper refusal.
     */
    private fun identityOf(mediaType: String?, ids: List<String?>): Identity? {
        val type = mediaType?.trim()?.lowercase()
        if (type.isNullOrBlank()) return null
        val imdb = ids.firstNotNullOfOrNull { raw ->
            raw?.trim()?.takeIf { it.startsWith("tt") && it.length > 2 }
        }
        val tmdb = ids.firstNotNullOfOrNull { raw ->
            raw?.trim()
                ?.takeIf { it.startsWith("tmdb:") }
                ?.removePrefix("tmdb:")
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        }
        if (imdb == null && tmdb == null) return null
        return Identity(
            mediaType = type,
            // The same pair the row matcher reads (BingeCatRow.matches), so a
            // membership test here and a membership test there can never
            // disagree about what "a show" means.
            isMovie = type !in setOf("tv", "series"),
            imdbId = imdb,
            tmdbId = tmdb
        )
    }
}
