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
     * Resolves [request]'s landscape art: the memo, then one TMDB lookup through
     * the semaphore. Every answer - including "TMDB had nothing" - is filed, so
     * the same title is resolved once however many cards and scrolls it appears
     * under.
     */
    suspend fun resolve(
        context: Context,
        request: LandscapeArtRequest
    ): Pair<String?, String?> {
        memo[request.key]?.let { return it }

        val art = semaphore.withPermit {
            memo[request.key]?.let { return@withPermit it }

            val detail = runCatchingCancellable {
                TmdbRepository.getInstance(context.applicationContext)
                    .fetchEnrichedMetaCached(
                        imdbId = request.id,
                        type = request.type
                    )
            }.getOrNull()

            landscapeArtEntry(
                tmdbArt = detail.landscapeArtUrls(),
                request = request
            )
        }

        if (memo.size >= MAX_KEYS) memo.clear()
        memo[request.key] = art
        return art
    }
}

/**
 * The landscape artwork for one card, resolved off [GlobalLandscapeArtCache].
 *
 * Returns the caller's own fallback pair immediately when [enabled] is false or
 * [addonId]/[addonType] is missing - so a portrait card, or a landscape card for
 * an item with no id to look up, costs nothing and shows exactly what it always
 * did - and upgrades to the resolved pair once the TMDB answer lands.
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
    addonBackdrop: String? = null,
    addonLogo: String? = null
): Pair<String?, String?> {
    val context = LocalContext.current
    val fallback = remember(addonBackdrop, addonLogo) {
        addonBackdrop to addonLogo
    }
    val request = remember(enabled, addonId, addonType, addonBackdrop, addonLogo) {
        if (enabled && !addonId.isNullOrBlank() && !addonType.isNullOrBlank()) {
            LandscapeArtRequest(
                id = addonId,
                type = addonType,
                addonBackdrop = addonBackdrop,
                addonLogo = addonLogo
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
