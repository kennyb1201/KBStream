package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier

/**
 * One poster surface's tile, in whichever shape the viewer chose.
 *
 * The app has two card vocabularies: the 2:3 [PosterCard] every catalog screen
 * was built on, and the 16:9 [LandscapeCard] the Home rails and KB folders
 * offer behind their own "Landscape Cards on Home Rails" switch. The global
 * "Landscape Posters" setting is meant to reach every screen, not just those
 * two, so the shape question lives here in one place instead of being answered
 * again at each call site - and a screen converts by swapping its `PosterCard`
 * for this, passing the backdrop/clearlogo it already has.
 *
 * The size is taken as a pair rather than left in [modifier] because the two
 * shapes cannot share it, and a caller can only express that by letting the card
 * own its own bounds. Portrait keeps the caller's exact box; landscape keeps the
 * Home rails' proportion to it - a 124dp poster becomes a 210x118 landscape
 * card (see [landscapeTileWidth]), not the 124x70 thumbnail a plain 16:9 crop
 * of the same width used to give. The tile is therefore WIDER than the poster,
 * so a caller that pins its own container to the poster width has to widen it
 * too; [rememberGlobalLandscape] and [landscapeTileWidth] are there for that.
 *
 * [backdropUrl] is what the caller already has. When it is missing, landscape
 * mode resolves the item's own art from [artId]/[artType] through
 * [rememberGlobalLandscapeArt] - the shared backdrop + clearlogo resolver - so a
 * surface that ships only a poster still draws a real backdrop with a corner
 * clearlogo. The clearlogo is TMDB's English wordmark or nothing: an add-on's
 * own `logo` is a bare URL with no language on it, so it is not drawn (a
 * localized catalog would put its locale's wordmark on the card). Whatever
 * backdrop is still absent falls back to [posterUrl], because a cropped poster
 * reads better than a blank 16:9 hole.
 */
@Composable
fun GlobalPosterCard(
    posterUrl: String?,
    contentDescription: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    posterWidth: Dp,
    posterHeight: Dp,
    modifier: Modifier = Modifier,
    backdropUrl: String? = null,
    /**
     * The item's raw id and type for the shared landscape-art resolver (any id
     * `TmdbRepository.fetchEnrichedMetaCached` can resolve: "tt...",
     * "tmdb:...", or a bare numeric TMDB id). Null skips the lookup and keeps
     * [backdropUrl] and [posterUrl] as the whole story.
     *
     * [artType] may also be [ART_TYPE_COLLECTION], for a tile that draws a TMDB
     * collection rather than a title: the resolver then reads the collection's
     * own images, and the id is deliberately not looked up as a title id (see
     * the image-override read below).
     */
    artId: String? = null,
    artType: String? = null,
    isPartiallyWatched: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onPosterError: ((Throwable?) -> Unit)? = null,
    /**
     * Override for a surface with its own rule. Null asks the global
     * preference ([AppPreferences.landscapePostersActive]). The KB folder Grid
     * passes only the *global* switch here, so a viewer who turned on the
     * Home-rails landscape mode does not also reshape the Grid - the original
     * "Grid keeps posters" behaviour - while the new global setting does.
     */
    landscape: Boolean? = null
) {
    val context = LocalContext.current
    // The global switch is a SYNCED pref, so a change that arrives from the
    // account (the other TV's guest profile flips it) has to repaint this card.
    // A bare read cannot: the pref file changed, nothing in the composition
    // knows. The revision is bumped on every applied blob, and keying on it
    // re-reads exactly once per real change (see PrefsPayloadApplier.revision).
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    val useLandscape = landscape ?: remember(syncedRevision) {
        AppPreferences.landscapePostersActive(context)
    }
    // Landscape mode resolves the item's own backdrop + clearlogo when the
    // caller's data carries none; portrait never looks anything up.
    val art = rememberGlobalLandscapeArt(
        enabled = useLandscape,
        addonId = artId,
        addonType = artType,
        addonBackdrop = backdropUrl
    )
    // The artwork the viewer chose for this title (see ImageOverrides). This is
    // the ONE place every poster surface in the app converges on - Home's rails
    // aside, which draw the two cards directly - so applying the choice here is
    // what makes "Set as Poster" mean everywhere the poster appears, and "Set
    // as Landscape card" mean the cards those rails do not draw themselves.
    // Read from the mirror as a state: a choice made elsewhere repaints these
    // cards on the next frame.
    val overridePaths = rememberImageOverridePaths()
    if (useLandscape) {
        LandscapeCard(
            backdropUrl = imageOverrideCardBackdropUrl(
                paths = overridePaths,
                // A collection's id is a different id space from a title's
                // (collection 55 is not film 55), and every choice the viewer
                // makes is filed under a TITLE id - so none of them may be read
                // for one. Without this, a film's chosen still would land on
                // the tile of an unrelated franchise that happens to share its
                // number.
                rawId = artId.takeUnless { isCollectionArtType(artType) },
                fallbackUrl = art.first?.takeIf { it.isNotBlank() } ?: posterUrl
            ),
            logoUrl = art.second,
            fallbackTitle = contentDescription,
            contentDescription = contentDescription,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(landscapeTileWidth(posterWidth))
                .height(landscapeTileHeight(posterWidth))
        )
    } else {
        PosterCard(
            posterUrl = imageOverridePosterUrl(
                paths = overridePaths,
                rawId = artId,
                fallbackUrl = posterUrl
            ),
            contentDescription = contentDescription,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            onPosterError = onPosterError,
            modifier = modifier
                .width(posterWidth)
                .height(posterHeight)
        )
    }
}

/**
 * Whether the global "Landscape Posters" setting is on, for a caller that has to
 * size its own container to match the tile [GlobalPosterCard] is about to draw.
 *
 * [GlobalPosterCard] makes the same read internally; this exists only so a
 * wrapper (a Column pinned to the poster width, a caption line under the card)
 * does not disagree with the card about the shape it is drawing.
 */
@Composable
fun rememberGlobalLandscape(): Boolean {
    val context = LocalContext.current
    // Reactive like the card itself: this reads the same synced pref, and a
    // wrapper that sized itself from a stale answer would clip the landscape
    // tile the card is drawing. The rule for every Compose read of a
    // SYNCED_PREF_KEYS-backed pref: collect PrefsPayloadApplier.revision, never
    // a bare remember.
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    return remember(syncedRevision) { AppPreferences.landscapePostersActive(context) }
}

