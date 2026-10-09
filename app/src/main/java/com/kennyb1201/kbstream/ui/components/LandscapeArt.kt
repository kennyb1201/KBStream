package com.kennyb1201.kbstream.ui.components

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.cardBackdropPath
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The landscape-card artwork vocabulary, shared by the two screens that resolve
 * it: Home's rails and the KB folder layouts.
 *
 * Both draw [LandscapeCard]s, so both need the same three answers - what key an
 * item's artwork is filed under, which of TMDB's images to prefer, and what the
 * add-on's own fields are worth when TMDB has nothing. Each screen had its own
 * copy of all three, and they had already drifted: the folder resolver dropped
 * any media type it did not recognize while its layouts read every unrecognized
 * type as "movie", so those items were looked up under a key that could not
 * exist and were re-attempted on every rails change. One implementation, pinned
 * by `LandscapeArtTest`, is what keeps that from being a thing that can happen
 * twice.
 */

/**
 * The key one item's landscape-card artwork is filed under.
 *
 * Four places share this string and none of them is compiled against the
 * others' use of it: the rail builders write `Rail.landscapeArt` under it,
 * HomeScreen's landscape card reads it back, the view model files the art it has
 * already resolved under it (see `previousLandscapeArt` and the per-build memo
 * beside it), and the folder screens write and read their own map with it. A
 * spelling that drifted in any one of them would neither fail to compile nor
 * throw: the lookup would return null, every card would fall back to its add-on
 * backdrop, and every rebuild would resolve artwork it had already resolved.
 * That is a silent failure wearing a plausible symptom ("the landscape cards
 * look plain"), so the format is built here, in one place, and pinned by
 * `LandscapeArtTest`.
 *
 * The type in the key is the type the artwork was looked up BY
 * ([TmdbRepository.normalizeMediaType]), not the add-on's raw string, because
 * that is the only spelling two sources of the same title can be expected to
 * agree on: an add-on calling a show "tv" and another calling it "series" - or
 * "anime.series" - have to file one entry, or the second spelling pays for a
 * second lookup. It is also what makes a film's backdrop unreachable from a
 * show's card, since a TMDB and an add-on id can be the same string.
 *
 * [id] is whatever the add-on called the title and is deliberately not parsed
 * here - for TMDB-sourced rails it already carries its own namespace
 * ("tmdb:603"), so the result has more than one colon in it. The key is opaque:
 * only ever built and compared, never taken apart.
 */
internal fun landscapeArtKey(type: String, id: String): String =
    "${TmdbRepository.normalizeMediaType(type)}:$id"

/**
 * [landscapeArtKey] for a caller holding the item rather than its parts - the
 * card, and anything else that has a [MetaPreview] to hand.
 *
 * One spelling of the format, not two: this must stay a delegation.
 */
internal fun MetaPreview.landscapeArtKey(): String = landscapeArtKey(type, id)

/**
 * The number a landscape card shows at [index] of a rail, or null when the rail
 * is not a ranking and the card shows none.
 *
 * The rank of a title is its position in the rail, not a field of the item: the
 * add-on sends its ranking as the order of its catalog ("Top Movies Today"
 * arrives ranked, first to tenth), and every rail keeps the order it was given.
 * It is read off the items *on screen* rather than off what the add-on sent, so
 * a title the digital-release filter or the kids ceiling dropped leaves no gap:
 * the numbers stay contiguous and keep matching the row the viewer is reading.
 *
 * Only the pinned rows are rankings - see `Rail.ranked` for which ones and why
 * they cannot simply show the ranking their own artwork carries.
 */
internal fun landscapeRank(ranked: Boolean, index: Int): Int? =
    if (ranked) index + 1 else null

/**
 * One item to resolve artwork for.
 *
 * The two screens hold different item types (Home's [MetaPreview], the folder
 * screens' `KBContentItem`) with different field names for the same things, so
 * this is the shape they can both state: what to look up, and what the add-on
 * already offers for the backdrop (its clearlogo is deliberately NOT carried -
 * see [landscapeArtEntry]).
 */
internal data class LandscapeArtRequest(
    val id: String,
    val type: String,
    val addonBackdrop: String? = null,
    /**
     * Pinned "Top ... Today" rails: their add-on backgrounds carry burned-in
     * promo text, so they are never usable as card art - TMDB or nothing.
     */
    val tmdbOnly: Boolean = false
) {

    /** The entry key; see [landscapeArtKey]. */
    val key: String get() = landscapeArtKey(type, id)
}

/**
 * The two artwork URLs TMDB offers for this detail, or a pair of nulls when it
 * has none - the answer every caller's lookup has to produce.
 *
 * The backdrop is [cardBackdropPath], never the detail's primary backdrop: the
 * primary is what the hero is already showing, and cards that mirror it read as
 * a mistake rather than as artwork.
 */
internal fun TmdbDetail?.landscapeArtUrls(): Pair<String?, String?> {

    val backdrop =
        this?.cardBackdropPath()
            ?.takeIf { it.isNotBlank() }
            ?.let { TmdbRepository.BACKDROP_BASE + it }

    val logo =
        this?.bestLogoPath()
            ?.takeIf { it.isNotBlank() }
            ?.let { TmdbRepository.LOGO_BASE + it }

    return backdrop to logo
}

/**
 * The entry [request] is filed under, given [tmdbArt].
 *
 * Pure, and the only place the merge rules exist. Both branches write an entry
 * for EVERY request, including one TMDB had nothing for: "no entry" is how a
 * rail says "resolve me again" (see `previousLandscapeArt` and
 * `applyLandscapeArt`), so dropping an empty answer would make the same title
 * cost a lookup on every build.
 */
internal fun landscapeArtEntry(
    tmdbArt: Pair<String?, String?>,
    request: LandscapeArtRequest
): Pair<String?, String?> {

    if (request.tmdbOnly) {
        // TMDB or nothing, spelled as BLANK markers rather than as nulls:
        // LandscapeCard treats blank exactly like missing, so the card renders
        // its clean title-only treatment while the entry still counts as
        // resolved. Nothing on such a rail may ever fall back to the add-on
        // background, which is the whole point of the flag.
        return (tmdbArt.first ?: "") to (tmdbArt.second ?: "")
    }

    val addonBackdrop = request.addonBackdrop?.takeIf { it.isNotBlank() }

    // Backdrop: an ALTERNATE TMDB image wins over the add-on's own background,
    // which is usually the same primary image the hero is showing.
    //
    // Logo: TMDB's English wordmark is the ONLY source. The add-on's own
    // clearlogo used to be preferred here, on the theory that a provider ships
    // the right language for the title it carries - but a catalog's `logo` is a
    // bare URL with no language on it, and a localized catalog ships its own
    // locale's wordmark, so that rule is what put a FOREIGN clearlogo on the
    // cards even after the TMDB pick had been made English-only. There is
    // nothing in the URL to test the language against, so the untestable source
    // is simply not drawn: with no English TMDB mark the card shows its plain
    // title rather than a mark of unknown language.
    return (tmdbArt.first ?: addonBackdrop) to tmdbArt.second
}

/**
 * Resolves artwork for every request, in parallel, keyed ready to merge into a
 * rail's or folder's map.
 *
 * [tmdbArtOf] is the caller's lookup, because what a screen caches, throttles
 * and traces is genuinely its own business: Home keeps a per-build memo of the
 * answers (see `landscapeLookupMemo`, which is what stops a title TMDB has
 * nothing for being asked about once per rail) plus its own semaphore, while the
 * folder screens have a semaphore and no memo. The key, the fallbacks and the
 * fan-out are the parts they have to agree on, and they are here.
 *
 * A caller with nothing worth asking - a media type TMDB cannot answer for -
 * returns a pair of nulls from [tmdbArtOf] rather than skipping the item, so the
 * miss is filed and not retried.
 */
internal suspend fun landscapeArtFor(
    requests: List<LandscapeArtRequest>,
    tmdbArtOf: suspend (LandscapeArtRequest) -> Pair<String?, String?>
): Map<String, Pair<String?, String?>> {

    if (requests.isEmpty()) return emptyMap()

    return coroutineScope {
        requests.map { request ->
            async {
                request.key to landscapeArtEntry(
                    tmdbArt = tmdbArtOf(request),
                    request = request
                )
            }
        }.awaitAll().toMap()
    }
}
