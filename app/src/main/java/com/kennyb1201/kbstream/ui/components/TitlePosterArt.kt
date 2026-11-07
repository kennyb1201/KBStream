package com.kennyb1201.kbstream.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The poster of a card whose own data carries none, resolved by the title's id.
 *
 * A pinned Library rail is the case this exists for: BingeCat's wire media is a
 * title, a year and ids and nothing else (see `BingeCatMedia`), so a BingeCat
 * rail's cards had no poster to draw and fell back to their own titles while
 * every Trakt rail beside them - whose rows ship Trakt's images - looked right.
 * The rail card cannot fix that from an override map: [ImageOverrides] holds the
 * images the VIEWER chose, one row per choice, and a title nobody has chosen art
 * for has no entry in it at all.
 *
 * So this is the lookup the rest of the app already makes for art it does not
 * carry: TMDB's own detail for the row's id - `getDetailByTmdbId` for a
 * "tmdb:<n>" row, `fetchEnrichedMetaCached` for an IMDb one, the same two calls
 * [GlobalLandscapeArtCache] resolves a card's backdrop and clearlogo with, and
 * already memory- and disk-cached under one key, so a card costs at most one
 * read per title per session and a cold start re-reads a cached row rather than
 * re-asking TMDB. The memo here only stops a rail that is scrolled away and back from
 * entering the suspend call again; the semaphore is the same six-at-a-time
 * ceiling a screenful of cards composes under.
 *
 * Nothing is invented: an id TMDB cannot resolve answers null, and the caller
 * keeps the card's own title fallback (never a blank tile, never a dropped
 * rail). A caller with art of its own passes `enabled = false` and pays
 * nothing - that is why the Trakt and MDBList rails are untouched by this.
 */
internal object TitlePosterArt {

    /** Same order of magnitude as the other card memos; full means cleared. */
    private const val MAX_KEYS = 1024

    /**
     * `"<type>:<id>"` -> the poster URL, or [NO_POSTER] for "TMDB had nothing".
     *
     * Never a nullable value: a ConcurrentHashMap's `put` throws
     * NullPointerException on a null instead of storing it, which is how the
     * first art-less card crashed the rail (see [posterMemoValue]).
     */
    private val memo =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    private val semaphore = Semaphore(permits = 6)

    suspend fun resolve(context: Context, id: String, type: String): String? {
        val key = type + ":" + id
        memo[key]?.let { return posterFromMemoValue(it) }
        return semaphore.withPermit {
            // Asked again inside the permit: twelve cards composing in one frame
            // are twelve callers for six permits, and the second half must not
            // repeat a lookup the first half already filed.
            memo[key]?.let { return@withPermit posterFromMemoValue(it) }
            val repository = TmdbRepository.getInstance(context.applicationContext)
            val detail = runCatchingCancellable {
                // The row's id IS the address, and the app's two id namespaces
                // need two different calls: an IMDb id is resolved through
                // TMDB's /find, while a "tmdb:<n>" id is already a TMDB id. A
                // BingeCat media often carries no IMDb id at all (its identity
                // is TMDB), so handing it to the IMDb lookup - which only ever
                // asks /find?external_source=imdb_id - can never answer, and
                // the tile stayed title-only. Both calls file their answer
                // under the SAME cache key ("movie:tmdb:12345"), so the two
                // doors share every entry and neither repeats a fetch.
                val tmdbId = ImageOverrides.tmdbIdOf(id)
                if (tmdbId != null) {
                    repository.getDetailByTmdbId(tmdbId, type)
                } else {
                    repository.fetchEnrichedMetaCached(imdbId = id, type = type)
                }
            }.getOrNull()
            val poster = detail?.posterPath
                ?.takeIf { it.isNotBlank() }
                ?.let { path -> TmdbRepository.POSTER_BASE + path }
            if (memo.size >= MAX_KEYS) memo.clear()
            // The one write the memo can make: never a null value (see
            // [NO_POSTER]) - a null here is an NPE, not a cached miss.
            memo[key] = posterMemoValue(poster)
            poster
        }
    }
}

/**
 * [TitlePosterArt] for one card, as state.
 *
 * [enabled] is the caller's "this row has no art of its own" test, so a rail of
 * Trakt or MDBList rows (which carry their own posters) resolves nothing. The
 * value starts null and fills in when TMDB answers; the card draws its title
 * until then, which is exactly the treatment it had before - there is no frame
 * where a tile is blank.
 */
@Composable
internal fun rememberTitlePosterArt(
    id: String?,
    type: String?,
    enabled: Boolean
): String? {
    val context = LocalContext.current
    val state = produceState<String?>(
        initialValue = null,
        key1 = id,
        key2 = type,
        key3 = enabled
    ) {
        value = if (enabled && !id.isNullOrBlank() && !type.isNullOrBlank()) {
            TitlePosterArt.resolve(context, id, type)
        } else {
            null
        }
    }
    return state.value
}
