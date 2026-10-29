package com.kennyb1201.kbstream.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The backdrop + clearlogo resolver behind the global "Landscape Posters"
 * setting.
 *
 * The Home rails and the KB folders resolve their landscape artwork through the
 * vocabulary in `LandscapeArt.kt`, with a view model to cache it. Every OTHER
 * poster surface the setting reaches - Library, Collection, Actor credits, the
 * Detail rails, Search, the catalog grid - carries its data in a different shape
 * and has no view model of its own for this, so they all run the same
 * vocabulary through here instead: the same key ([landscapeArtKey]), the same
 * merge rule ([landscapeArtEntry]), the same TMDB lookup the rails use
 * ([TmdbRepository.fetchEnrichedMetaCached]), and one app-wide memo in front of
 * it.
 *
 * This is what turns "the setting reshapes the card" into "the card actually
 * has landscape art": a Library row that ships only a poster gets a real
 * backdrop and a corner clearlogo, exactly as if it had come off a Home rail.
 *
 * The memo is session-only by construction, so a cold start used to resolve
 * every card again. [resolve] therefore consults a disk layer in front of the
 * lookup (rules and codec in `LandscapeArtDiskCache.kt`): the PRE-MERGE TMDB
 * pair is written through on every answered resolve and merged with the
 * CURRENT request on the way back, so a changed add-on backdrop or `tmdbOnly`
 * flag can never be served stale.
 *
 * A caller only reaches this in landscape mode - [GlobalPosterCard] gates it -
 * so the lookups never run for a portrait grid.
 */
internal object GlobalLandscapeArtCache {

    /**
     * Same order of magnitude as Home's own memo. A memo that fills is simply
     * cleared: the worst case is one repeated lookup, which is the cost before
     * the memo existed, and the repository's own caches still absorb the network.
     */
    private const val MAX_KEYS = 512

    private val memo =
        java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()

    /**
     * Six at a time, like the rails. A grid can compose a screenful of cards in
     * one frame; without a ceiling that is a screenful of simultaneous TMDB
     * requests.
     */
    private val semaphore = Semaphore(permits = 6)

    /** The answer already filed for [key], so a recomposition never flashes the fallback. */
    fun peek(key: String): Pair<String?, String?>? = memo[key]

    /**
     * Resolves [request]'s landscape art: the memo, then the disk row, then one
     * TMDB lookup through the semaphore. Every answer - including "TMDB had
     * nothing" - is filed, so the same title is resolved once however many
     * cards and scrolls it appears under, and once per month across cold starts.
     *
     * [peek] deliberately does NOT read the disk layer: first composition has to
     * answer synchronously, and a suspend read cannot serve it.
     */
    suspend fun resolve(
        context: Context,
        request: LandscapeArtRequest
    ): Pair<String?, String?> {
        memo[request.key]?.let { return it }

        val art = semaphore.withPermit {
            memo[request.key]?.let { return@withPermit it }

            val repository = TmdbRepository.getInstance(context.applicationContext)

            // The disk layer, consulted before the lookup so a title resolved in
            // an earlier session costs no TMDB call at all. It is merged with
            // the CURRENT request because only the pre-merge pair is stored.
            readDiskArt(repository, request)?.let { cached ->
                return@withPermit landscapeArtEntry(tmdbArt = cached, request = request)
            }

            var answered = false
            val tmdbArt =
                if (isCollectionArtType(request.type)) {
                    // A collection's art comes from its own endpoint: there is no
                    // detail response to append `images` to (see
                    // TmdbApiService.getCollectionImages), so the title lookup
                    // below could only answer for whatever TITLE happens to
                    // share the collection's number. The ranked backdrop is the
                    // whole answer - TMDB publishes no logos for a collection -
                    // and the card keeps its plain title in the corner.
                    val collectionId = request.id.trim().toIntOrNull()
                    if (collectionId == null) {
                        null to null
                    } else {
                        val backdrop = runCatchingCancellable {
                            repository.fetchCollectionBackdropCached(
                                collectionId = collectionId,
                                primaryBackdropPath =
                                    TmdbRepository.tmdbImagePath(request.addonBackdrop)
                            )
                        }
                        answered = backdrop.isSuccess
                        backdrop.getOrNull() to null
                    }
                } else {
                    val detail = runCatchingCancellable {
                        repository.fetchEnrichedMetaCached(
                            imdbId = request.id,
                            type = request.type
                        )
                    }
                    answered = detail.isSuccess
                    detail.getOrNull().landscapeArtUrls()
                }

            // Write-through, and only for an answer the lookup actually
            // produced: a failed or cancelled fetch must never be filed as
            // "TMDB has nothing" (that is the MobLand trap, a day long).
            if (answered) writeDiskArt(repository, request, tmdbArt)

            landscapeArtEntry(
                tmdbArt = tmdbArt,
                request = request
            )
        }

        if (memo.size >= MAX_KEYS) memo.clear()
        memo[request.key] = art
        return art
    }

    /**
     * The fresh disk answer for [request], or null to fall through to a lookup.
     *
     * A stale, absent or corrupt row is a miss here and gets overwritten by the
     * write-through that follows the lookup - the same expiry-on-read the detail
     * disk rows use, with no pruning job of its own.
     */
    private suspend fun readDiskArt(
        repository: TmdbRepository,
        request: LandscapeArtRequest
    ): Pair<String?, String?>? {
        val now = System.currentTimeMillis()
        val (json, updatedAt) = repository.readJsonCacheRow(landscapeArtDiskKey(request.key))
            ?: return null
        val entry = decodeLandscapeArtDiskEntry(json) ?: return null
        if (!landscapeArtDiskEntryIsFresh(now - updatedAt, entry)) return null
        return entry.toPair()
    }

    /**
     * Files [tmdbArt] for later sessions. Best-effort: the caller already holds
     * the pair, so a failed write costs the next cold start a lookup and nothing
     * else.
     */
    private suspend fun writeDiskArt(
        repository: TmdbRepository,
        request: LandscapeArtRequest,
        tmdbArt: Pair<String?, String?>
    ) {
        runCatchingCancellable {
            repository.writeJsonCacheRow(
                key = landscapeArtDiskKey(request.key),
                json = encodeLandscapeArtDiskEntry(LandscapeArtDiskEntry.of(tmdbArt)),
                now = System.currentTimeMillis()
            )
        }
    }
}

/**
 * The landscape artwork for one card, resolved off [GlobalLandscapeArtCache].
 *
 * Returns the caller's own fallback pair immediately when [enabled] is false or
 * [addonId]/[addonType] is missing - so a portrait card, or a landscape card for
 * an item with no id to look up, costs nothing - and upgrades to the resolved
 * pair once the TMDB answer lands. The fallback's logo half is always null: a
 * card's clearlogo is TMDB's English wordmark or nothing (see
 * [LandscapeArtRequest]), never the add-on's own untestable one.
 *
 * [addonId] and [addonType] are the raw id and type the item is filed under
 * (anything [TmdbRepository.fetchEnrichedMetaCached] can resolve: "tt...",
 * "tmdb:...", a bare numeric TMDB id).
 */
@Composable
fun rememberGlobalLandscapeArt(
    enabled: Boolean,
    addonId: String?,
    addonType: String?,
    addonBackdrop: String? = null
): Pair<String?, String?> {
    val context = LocalContext.current
    val fallback = remember(addonBackdrop) {
        addonBackdrop to null
    }
    val request = remember(enabled, addonId, addonType, addonBackdrop) {
        if (enabled && !addonId.isNullOrBlank() && !addonType.isNullOrBlank()) {
            LandscapeArtRequest(
                id = addonId,
                type = addonType,
                addonBackdrop = addonBackdrop
            )
        } else {
            null
        }
    }

    val art by produceState(
        initialValue = request?.let { GlobalLandscapeArtCache.peek(it.key) } ?: fallback,
        key1 = request,
        key2 = fallback
    ) {
        value = request?.let { GlobalLandscapeArtCache.resolve(context, it) } ?: fallback
    }
    return art
}
