package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.reporting.ComposeChurn
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun WatchedCheckBadge(
    modifier: Modifier = Modifier,
    /**
     * The disc's diameter. 26dp is the size every poster tile draws; a small
     * surface (the phone's 132x74 episode thumbnail) asks for a compact marker
     * of the same family rather than a disc a third of its own height.
     */
    diameter: Dp = 26.dp
) {
    // The markers' gold is the app's own accent, not a literal of its own:
    // they were once painted the LIGHT end of the launcher play-button
    // gradient (#F7CE86) so a 15sp glyph stayed legible over bright artwork,
    // but that made the marker a second, paler gold matching nothing else on
    // screen. Legibility now lives in the scrim (0.92 - a mid-luminance gold
    // on a near-black disc carries the contrast, where a pale gold sat on the
    // artwork itself).
    //
    // Read [KBAccent] live HERE rather than captured into a top-level val: the
    // accent is profile-scoped state, and a captured copy froze the marker at
    // whatever colour was active when this file first loaded, so the watch
    // markers stopped matching after a profile switch or an accent change.
    val accent = KBAccent
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            // A more opaque scrim is what buys the glyph its contrast: the
            // badge must stay legible over a bright poster as well as a dark
            // one, and the old 0.8 let busy artwork through behind it.
            .background(KBVoid.copy(alpha = 0.92f))
            .border(1.5.dp, accent, CircleShape)
            // The badge is the only place this state is stated, so it needs a
            // spoken label: without it a screen reader announces the bare
            // "✓" glyph (or nothing) beside the poster's title.
            .clearAndSetSemantics { contentDescription = "Watched" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "✓",
            color = accent,
            // 15sp at the 26dp default, scaled with the disc so a compact
            // marker keeps the glyph inside its ring.
            fontSize = (diameter.value * (15f / 26f)).sp
        )
    }
}

/**
 * Eye badge for shows the user has STARTED but not finished. Identical
 * treatment to [WatchedCheckBadge] — same circle, same scrim, same live
 * theme accent for border and glyph — so the two read as one marker family; the
 * eye shape itself is what distinguishes started-but-unfinished from the
 * completed check.
 */
@Composable
fun WatchedEyeBadge(
    modifier: Modifier = Modifier,
    /** The disc's diameter; see [WatchedCheckBadge] - the two always match. */
    diameter: Dp = 26.dp
) {
    // Same live-accent read as [WatchedCheckBadge], for the same reason: the
    // eye and the check are one marker family and must track the profile's
    // theme together.
    val accent = KBAccent
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(KBVoid.copy(alpha = 0.92f))
            .border(1.5.dp, accent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Visibility,
            contentDescription = "Started, not finished",
            tint = accent,
            modifier = Modifier.size(diameter * (15f / 26f))
        )
    }
}

/**
 * Shared poster tile for every screen that renders a catalog/meta poster
 * (Home rails, search, detail recommendations, etc.). Wraps KBCard with the
 * image and, when isWatched is true, a small checkmark badge in the corner.
 * A show that is started-but-not-finished (isPartiallyWatched) shows the
 * eye badge instead — the completed checkmark always wins.
 */
@Composable
fun PosterCard(
    posterUrl: String?,
    contentDescription: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPartiallyWatched: Boolean = false,
    onPosterError: ((Throwable?) -> Unit)? = null,
    /**
     * Whether the tile draws the title as its own placeholder while the art is
     * missing, failed or still loading.
     *
     * That placeholder is the tile's whole identity on a rail, where nothing
     * else says which title it is. A Continue Watching card is the opposite
     * case: it already prints the title (and the S/E) in its own overlay, so
     * the placeholder there printed the same words twice - the dim duplicate
     * the field reports call "the title twice". Those callers pass false; the
     * accessibility label is untouched either way, because [contentDescription]
     * still carries the title to a screen reader.
     */
    showFallbackText: Boolean = true,
    /**
     * The card's own release/air date, when the rail carries one.
     *
     * Spotlight's expanded card offers no PLAY for a date still in the future
     * (Detail's UNAVAILABLE rule). A catalog preview carries it in
     * `releaseInfo` - a year for a film, a full date for an upcoming episode -
     * so the rails hand it over and the panel decides; a card that is not on a
     * Home rail never passes one and reports nothing.
     */
    airDate: String? = null,
    /**
     * The title's own Detail identity, for Spotlight's panel pills (audit A4).
     *
     * The panel used to gate PLAY and DETAILS on the resolved hero meta, which is
     * debounced and can fail - so on a slow or dead network both pills were
     * silent dead presses. The card already knows which title it IS, so it hands
     * that over and the pills act on the identity rather than on the enrichment.
     */
    spotlightId: String? = null,
    spotlightType: String? = null,
    /**
     * Whether this card may ever expand into Spotlight's preview panel.
     *
     * Expand is CATALOG ONLY (spec 2026-11-12, F1): the Continue Watching and
     * Upcoming rails pass false, so their cards never set a snapshot, never arm
     * the dwell and never open a panel - while their focus ring, their click and
     * their resume/progress bar are exactly what they always were. The rail kind
     * lives here because a card is the only thing that knows which rail handed it
     * its art, and the alternative (guessing from the title's media type) cannot
     * tell a catalog card from a CW card ABOUT THE SAME SHOW.
     */
    spotlightExpandable: Boolean = true,
    overlayContent: (@Composable BoxScope.() -> Unit)? = null
) {
    // Walk diagnostics: the number of CARD bodies a walk runs, which is what a
    // rail walk multiplies anything a card does by (see ComposeChurn).
    ComposeChurn.note("poster")
    val context = LocalContext.current
    var hasError by remember(posterUrl) { mutableStateOf(false) }
    // ...and a poster URL that exists but has not LOADED is not art on screen
    // either. The tile used to be an empty KBSurface for as long as the fetch
    // took - a grid of them is what a first paint looks like - while the
    // fallback text below only ever covered a poster that was missing or had
    // already failed. Same class of blank-box bug as the splash's and the rail
    // card's corner logo.
    var posterPainted by remember(posterUrl) { mutableStateOf(false) }

    // Settings toggle: the eye badge (started-but-not-finished shows) can be
    // switched off app-wide; the completed checkmark is always unaffected.
    //
    // Keyed on the sync revision, because the toggle is a SYNCED pref: a tile
    // already on screen has to lose (or gain) its eye when the other TV flips
    // it, and a plain read would only have followed the setting whenever
    // something else invalidated the card (see [PrefsPayloadApplier.revision]).
    val syncedRevision = posterTileRevision()
    // Read unconditionally: a `remember` behind `&&` is a CONDITIONAL call, and
    // Compose's slot table is positional - the call would be skipped on the
    // cards that are not partially watched, shifting every slot after it.
    val eyeBadgeEnabled = remember(syncedRevision) {
        AppPreferences.getPosterPartialWatchBadge(context)
    }
    val showEyeBadge = isPartiallyWatched && eyeBadgeEnabled

    // Spotlight's card preview (spec: Home second layout). Null on Modern and on
    // every surface that is not a Home rail, in which case this card reports
    // nothing and behaves exactly as it always has.
    val spotlightFocus = com.kennyb1201.kbstream.ui.home.LocalSpotlightCardFocus.current
    // F5 of the 2026-11-14 scrolling spec: the card's bounds are measured on
    // every layout but are NOT Compose state any more. A `mutableStateOf` write
    // here invalidated this card on every scroll frame - the bounds change
    // continuously while a list moves - and the value is only ever READ when
    // the card takes focus, which is when the panel is placed. So it lives in a
    // plain holder, written by layout and read by the focus callback.
    val spotlightBounds = remember { CardBoundsHolder() }
    // The card's own node, so the panel's pills can send UP back to it - the
    // only way out of the row that does not collapse the panel by accident.
    val spotlightCardRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val spotlightModifier =
        if (spotlightFocus == null || !spotlightExpandable) {
            Modifier
        } else {
            Modifier
                // Attached for the card's whole life in Spotlight, not only
                // while focused: the panel's pill row sends UP back to this node
                // at a moment the card is blurred.
                .focusRequester(spotlightCardRequester)
                .onGloballyPositioned { coordinates ->
                    spotlightBounds.rect = coordinates.boundsInRoot()
                }
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        spotlightFocus.cardRequester = spotlightCardRequester
                        // Where the card is NOW, not where it was when the
                        // snapshot was last copied - a rail that settled under
                        // a held focus still opens the panel at the card.
                        spotlightFocus.updateBounds(spotlightBounds.rect)
                    }
                    spotlightFocus.onCardFocus(state.isFocused) {
                        com.kennyb1201.kbstream.ui.home.SpotlightCardSnapshot(
                            title = contentDescription,
                            id = spotlightId,
                            type = spotlightType,
                            stillUrl = posterUrl,
                            isWatched = isWatched,
                            isPartiallyWatched = isPartiallyWatched,
                            airDate = airDate,
                            bounds = spotlightBounds.rect
                        )
                    }
                }
        }

    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.then(spotlightModifier),
        shape = posterEdgeShape()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBSurface) // Visual fallback background container
                // The faint edge every poster tile draws (Settings → Interface).
                // On the tile's own box, so it outlines the artwork and not the
                // card's focus glow.
                .then(posterBorderModifier())
        ) {
            if (!posterUrl.isNullOrBlank() && !hasError) {
                AsyncImage(
                    // Rail tiles never crossfade. A rail paints many posters
                    // at once, and the global ImageLoader default was making
                    // every one of them fade in during a scroll - a per-frame
                    // cost on a 1.7 GB box for a tile the user is about to
                    // scroll past. The hero (and the few full-screen art
                    // surfaces) opt back into crossfade on their own request.
                    model = remember(posterUrl) {
                        ImageRequest.Builder(context)
                            .data(posterUrl)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { posterPainted = true },
                    onError = { state -> 
                        hasError = true
                        onPosterError?.invoke(state.result.throwable) 
                    }
                )
            }

            // The fallback text, for a poster that is missing, failed, or still
            // on its way in (see posterPainted). It is the tile's designed
            // placeholder either way, so a loading tile reads as this card's
            // own art rather than as a hole in the rail. A caller whose overlay
            // already says the title turns it off (see [showFallbackText]).
            if (showFallbackText &&
                (posterUrl.isNullOrBlank() || hasError || !posterPainted)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contentDescription ?: "No Image",
                        color = KBTextLo,
                        fontSize = 12.sp,
                        maxLines = 3
                    )
                }
            }

            // The caller's overlay (an episode card's readability gradient, a
            // progress scrim) is painted FIRST so the watch badge is the last
            // thing on the tile. It used to be the other way round, which is
            // why the marker looked faintest exactly on the episode cards:
            // their dark gradient was being drawn over it.
            overlayContent?.invoke(this)

            if (isWatched) {
                WatchedCheckBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            } else if (showEyeBadge) {
                WatchedEyeBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
        
    }
}

/**
 * A card's bounds in the root, held WITHOUT Compose state.
 *
 * Spotlight's panel is placed from the focused card's bounds, and it needs them
 * at one moment: the focus change. Measuring into a `mutableStateOf` (which is
 * what this replaced) wrote on every layout pass - and a scrolling list lays out
 * continuously - so every card in a moving rail invalidated itself per frame for
 * a value nothing read until the D-pad stopped. Plain field, no invalidation,
 * read from the focus callback (spec 2026-11-14, F5).
 */
internal class CardBoundsHolder {
    var rect: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero
}
