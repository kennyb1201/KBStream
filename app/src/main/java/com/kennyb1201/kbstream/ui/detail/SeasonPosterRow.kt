package com.kennyb1201.kbstream.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
// Coil's own size type, not compose's (there is no `Size` in ui.unit): this is
// what `ImageRequest.Builder.size(...)` takes.
import coil3.size.Size
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kennyb1201.kbstream.data.art.SeasonPosterAnswer
import com.kennyb1201.kbstream.data.art.SeasonPosterMemory
import com.kennyb1201.kbstream.data.fanart.FanartArtRepository
import com.kennyb1201.kbstream.data.reporting.SeasonPosterTrace
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.KB_ALPHA_FADE_MS
import com.kennyb1201.kbstream.ui.components.rememberMotionMs
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The season poster row's shared half (spec 2026-11-15, the poster season picker).
 *
 * Both Detail surfaces now draw the season section as a row of POSTERS with a
 * caption under each - the chips and the "EPISODES" header are gone - so the two
 * things that row needs live here once, for the TV page and the phone:
 *
 *  - the per-season resolution (fanart's `seasonposter` first, TMDB's season
 *    poster second), asked lazily as each poster composes, with the keep/clear
 *    rule and the trace the single header poster used to have;
 *  - the poster-and-caption unit itself, including the selected ring, the focus
 *    ring and the one-retry image draw.
 *
 * What is NOT here is the row: a LazyRow's wiring is a per-surface question (the
 * TV row hands its state to the focus landing, the phone row is a touch strip),
 * so each screen keeps its own row and shares this.
 */

/** How much a focused poster grows. The chip scaled 1.06; the poster keeps it. */
internal const val SEASON_POSTER_FOCUS_SCALE = 1.06f

/** The poster's 2:3 shape, at every size the two surfaces draw (84dp / 72dp). */
internal const val SEASON_POSTER_ASPECT = 2f / 3f

/**
 * The ring around a season poster.
 *
 * SELECTED is the accent - the mark the chip's selected state used to carry -
 * and FOCUS is the bright text colour, so a focused poster and a selected poster
 * are never confused for one another while the row is walked. Neither is a
 * decoration the other states pay for: an unselected, unfocused poster has no
 * ring at all.
 */
internal fun seasonPosterRingColor(selected: Boolean, focused: Boolean): Color = when {
    selected -> KBAccent
    focused -> KBTextHi
    else -> Color.Transparent
}

/**
 * One season's poster lookup, or null when this page cannot run one (a movie, or
 * a show whose TMDB id has not landed yet).
 *
 * [tvdbProvenAbsent] rides along because a null TVDB id cannot otherwise be told
 * from "TMDB proved there is no mapping", and only the proven absence may count
 * as an answered none (see [SeasonPosterMemory]).
 */
internal data class SeasonPosterLookup(
    val answer: SeasonPosterAnswer,
    val tvdbProvenAbsent: Boolean
)

/** The per-season resolution, held for the life of one Detail page. */
internal class SeasonPosterResolver(
    private val fanart: FanartArtRepository,
    private val tmdb: TmdbRepository,
    private val showId: Int?,
    private val isSeries: Boolean
) {
    /** False for a movie, and for a show whose TMDB detail has not arrived. */
    val canResolve: Boolean
        get() = isSeries && showId != null

    /** This season's poster answer, through the existing fanart -> TMDB path. */
    suspend fun lookup(season: Int): SeasonPosterLookup? {
        val id = showId?.takeIf { isSeries } ?: return null
        val tvdbId = runCatching { tmdb.resolveTvdbId(id, "series") }.getOrNull()
        val tvdbProvenAbsent = runCatching {
            tmdb.hasProvenNoTvdbMapping(id, "series")
        }.getOrDefault(false)
        return SeasonPosterLookup(
            answer = fanart.seasonPosterAnswer(
                tvdbId = tvdbId,
                tmdbTvId = id,
                season = season
            ),
            tvdbProvenAbsent = tvdbProvenAbsent
        )
    }
}

@Composable
internal fun rememberSeasonPosterResolver(
    showId: Int?,
    isSeries: Boolean
): SeasonPosterResolver {
    val context = LocalContext.current
    return remember(context, showId, isSeries) {
        SeasonPosterResolver(
            fanart = FanartArtRepository.getInstance(context),
            tmdb = TmdbRepository.getInstance(context),
            showId = showId,
            isSeries = isSeries
        )
    }
}

/**
 * One season item's poster, resolved lazily as the item composes.
 *
 * LAZY is the repository contract and the spec's own rule: a LazyRow composes the
 * seasons in and near the viewport, so asking HERE means the row resolves exactly
 * those and the rest as they scroll in - no rail-wide prefetch, and one fanart
 * call per title however many seasons are drawn (the bundle is cached and shared
 * with the clearlogo / backdrop / Media-rail surfaces).
 *
 * The keep rule is [SeasonPosterMemory]'s, unchanged: a failed lookup keeps the
 * poster already drawn, and only an ANSWERED none clears it - the fix from
 * `df08ed43e`, now applied per season instead of to one header slot. Every
 * resolution records its [SeasonPosterTrace] run line, so a paste can say which
 * source supplied each season.
 */
@Composable
internal fun rememberSeasonPoster(
    season: Int,
    resolver: SeasonPosterResolver,
    // Spec 2026-11-21, rule 7: poster art must never compete with the episode
    // list for the network. While the selected season's episodes are loading
    // the TV row pauses its poster lookups (they rerun when this flips back),
    // so the row the viewer is waiting for is served first. A poster already
    // resolved stays drawn - this only gates new requests.
    paused: Boolean = false
): String? {
    var poster by remember(season) { mutableStateOf<String?>(null) }
    var runs by remember(season) { mutableIntStateOf(0) }
    LaunchedEffect(season, resolver, paused) {
        if (!resolver.canResolve || paused) return@LaunchedEffect
        val lookup = resolver.lookup(season) ?: return@LaunchedEffect
        val trigger = if (runs == 0) "open" else "rerun"
        runs += 1
        val decision = SeasonPosterMemory.decide(
            current = poster,
            answer = lookup.answer,
            forSeries = true,
            season = season,
            tvdbProvenAbsent = lookup.tvdbProvenAbsent
        )
        SeasonPosterTrace.record(
            SeasonPosterTrace.runLine(trigger, season, lookup.answer, decision)
        )
        when (decision) {
            SeasonPosterMemory.Decision.REPLACE -> poster = lookup.answer.url
            SeasonPosterMemory.Decision.CLEAR -> poster = null
            SeasonPosterMemory.Decision.KEEP -> Unit
        }
    }
    return poster
}

/**
 * One season item: its poster, and the caption under it.
 *
 * NO POSTER = the caption alone, with no frame (F1/F2 of the spec). The poster
 * box is not reserved and no placeholder is drawn, so the item is exactly as wide
 * as its caption - and a season whose poster fanart and TMDB both lack reads as a
 * plain, tappable label rather than a hole in the row.
 *
 * The caption is TEXT, not a chip: no pill, no background. Its focus cue is the
 * accent ring drawn around it (there is no poster to ring) and the selected label
 * is the accent colour with a heavier weight, which is the mark the chip carried.
 * A poster's focus cue is the scale plus the bright ring - see
 * [seasonPosterRingColor].
 *
 * [surface] only labels the trace ("tv" / "phone"), so a paste can say which page
 * drew the image.
 */
@Composable
internal fun SeasonPosterArt(
    posterUrl: String?,
    caption: String,
    posterWidth: Dp,
    selected: Boolean,
    available: Boolean,
    focused: Boolean,
    surface: String,
    modifier: Modifier = Modifier
) {
    // One retry for the image, and the trace that names which half failed - the
    // same pair the header poster carried (spec 2026-11-14, F1/F3), now per
    // season: a healthy URL whose image did not redraw is a different bug from a
    // URL that was never resolved.
    var retry by remember(posterUrl) { mutableIntStateOf(0) }
    var retried by remember(posterUrl) { mutableStateOf(false) }
    val reducedMotion = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (focused && !reducedMotion) SEASON_POSTER_FOCUS_SCALE else 1f,
        animationSpec = tween(rememberMotionMs(KB_ALPHA_FADE_MS)),
        label = "seasonPosterScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        if (posterUrl != null) {
            // Re-keyed on the retry so a failed draw gets exactly one more
            // request instead of a silent blank.
            key(retry) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(posterUrl)
                        // 300x450 is the rung the 84dp and 72dp posters both
                        // drawn from (see the size contract this replaces).
                        .size(Size(300, 450))
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { state ->
                        SeasonPosterTrace.record(
                            SeasonPosterTrace.imageLine(
                                surface,
                                state.result.dataSource.name.lowercase()
                            )
                        )
                    },
                    onError = {
                        SeasonPosterTrace.record(
                            SeasonPosterTrace.imageLine(surface, "error")
                        )
                        if (!retried) {
                            retried = true
                            retry++
                        }
                    },
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .width(posterWidth)
                        .aspectRatio(SEASON_POSTER_ASPECT)
                        .clip(KBShapeSmall)
                        .border(
                            2.dp,
                            seasonPosterRingColor(selected = selected, focused = focused),
                            KBShapeSmall
                        )
                )
            }
        }

        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                selected -> KBAccent
                !available -> KBTextLo
                else -> KBTextHi
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(
                    top = if (posterUrl != null) 6.dp else 0.dp,
                    start = 4.dp,
                    end = 4.dp
                )
                // The caption-only item's focus cue: there is no poster to ring,
                // and a label with no cue at all is an invisible focus stop on a
                // TV. A ring around the text is the smallest honest answer - it
                // is not a poster frame and it is not a chip.
                //
                // THE SPACE IS RESERVED IN BOTH STATES (spec audit 2026-11-18,
                // Detail L2). The ring used to arrive with its own 6dp/3dp of
                // padding, so focusing a poster-less season widened the label and
                // nudged every poster beside it - a focus cue that moves the row
                // is a focus cue that reads as a layout glitch. A border draws
                // inside its bounds, so padding always and colour on focus is the
                // same cue with a still row (poster items scale via graphicsLayer
                // and never move anything either).
                .then(
                    if (posterUrl == null) {
                        Modifier
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .border(
                                2.dp,
                                if (focused) KBAccent else Color.Transparent,
                                KBShapeSmall
                            )
                    } else {
                        Modifier
                    }
                )
        )
    }
}
