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
import androidx.compose.ui.layout.ContentScale
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
    overlayContent: (@Composable BoxScope.() -> Unit)? = null 
) {
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

    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
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
            // own art rather than as a hole in the rail.
            if (posterUrl.isNullOrBlank() || hasError || !posterPainted) {
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
