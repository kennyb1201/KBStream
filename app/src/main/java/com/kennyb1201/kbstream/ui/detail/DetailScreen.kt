package com.kennyb1201.kbstream.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Size
import com.kennyb1201.kbstream.FormFactor
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.kb.BrowseHomeShortcut
import com.kennyb1201.kbstream.data.kb.BrowseHomeShortcuts
import com.kennyb1201.kbstream.data.airdates.AirDateCorrection
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.spoiler.SpoilerFree
import com.kennyb1201.kbstream.data.player.fileEpisodeStreamId
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbVideo
import com.kennyb1201.kbstream.data.tmdb.TrailerPick
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.bestReleaseDate
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.movieStatusTag
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.data.tmdb.director
import com.kennyb1201.kbstream.data.tmdb.displaySeasonName
import com.kennyb1201.kbstream.data.tmdb.list
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.tmdb.tmdbImage
import com.kennyb1201.kbstream.data.tmdb.writers
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.InnerTubeExtractor
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_WAIT_MS
import com.kennyb1201.kbstream.ui.browse.landFocusOn
import com.kennyb1201.kbstream.ui.components.AutoPlayLoadSplash
import com.kennyb1201.kbstream.ui.components.KB_ALPHA_FADE_MS
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBImdbTint
import com.kennyb1201.kbstream.ui.components.KBProgressBar
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.rememberKBFeedback
import com.kennyb1201.kbstream.ui.components.KB_STATUS_LOADING
import com.kennyb1201.kbstream.ui.components.formatRuntimeLabel
import com.kennyb1201.kbstream.ui.components.formatRuntimeMinutes
import com.kennyb1201.kbstream.ui.components.heroSharedElement
import com.kennyb1201.kbstream.ui.player.randomAiredEpisode
import com.kennyb1201.kbstream.ui.home.UNKNOWN_SHOW_NAME
import com.kennyb1201.kbstream.ui.home.upNextDisplayTitleOrNull
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.components.PlayFromBeginningSelection
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.rememberMotionMs
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.components.rememberPosterTileWidth
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.imageOverrideBackdropUrl
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.theme.DEFAULT_ACCENT_INDEX
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBScreenEdge
import com.kennyb1201.kbstream.ui.theme.KBFocusButton
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusNone
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.kbAccentIndexState
import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.ui.components.StudioChip
import com.kennyb1201.kbstream.ui.mobile.DetailScreenMobile
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Locale

data class StreamsTarget(
    val contentType: String,
    val streamId: String,
    val title: String,
    val displayName: String,
    val season: Int?,
    val episode: Int?,
    val resumePositionMs: Long,
    val totalEpisodesInSeason: Int? = null,
    val runtimeMinutes: Int? = null,
    /**
     * The user explicitly asked for the beginning (Home's long-press "Play
     * from Beginning"). [resumePositionMs] is 0 either way, so this is what
     * tells the player not to fall back to the saved watch-history position.
     */
    val startFromBeginning: Boolean = false,
    /**
     * Opened by the Random button: the player keeps chaining into random aired
     * episodes instead of the arithmetic next one.
     */
    val randomEpisodes: Boolean = false
)

private sealed interface PeopleRowItem {
    data class Person(val member: TmdbCastMember) : PeopleRowItem
    data object Separator : PeopleRowItem
}

/**
 * The key the people rail gives one card. Shared by the rail and the record a
 * drill-down leaves behind, because the two have to agree or Back cannot find
 * the card again.
 */
private fun personRowKey(member: TmdbCastMember): String =
    "person${member.id}${member.character.orEmpty()}"

/**
 * Files a rail card's [requester] under [key] for the life of the card.
 *
 * The map used to be written directly during composition, so an entry outlived
 * the card that registered it - and a drill-down reuses this composition for a
 * different title while the rail keys (`genre:`, `rec:`, `network:` ...) are
 * not namespaced by title. Hand-back and return-focus reads in that window
 * found an unattached requester, spent their retry budgets and abandoned.
 * Disposing the registration removes it, which is what Home's card rail has
 * always done. The removal is conditional on the value still being THIS
 * requester: a replacement registration under the same key must survive the
 * old card's disposal.
 */
@Composable
private fun RegisterRailRequester(
    requesters: MutableMap<String, FocusRequester>,
    key: String,
    requester: FocusRequester
) {
    DisposableEffect(key, requester) {
        requesters[key] = requester
        onDispose {
            if (requesters[key] === requester) requesters.remove(key)
        }
    }
}

/**
 * The key the episodes rail gives one episode of a season.
 *
 * Deliberately NOT the episode's stream id, which is the obvious choice and was
 * the one it used: that id is FILE numbering (see
 * [com.kennyb1201.kbstream.data.player.EpisodeScheme]), so it is not unique on
 * the shows the scheme exists for. A file holding two TMDB segments gives two
 * rows the SAME id - `sp2` maps episode 1 and episode 2 of a season onto its
 * first file - and Compose refuses a repeated LazyRow key with a hard
 * IllegalArgumentException rather than rendering anything. That is the reported
 * crash: `Key "tt3121722:4:1" was already used`, thrown as the Detail page
 * recomposed on return from playback (Sentry ANDROID-T), on a release where the
 * season listing had just started naming files instead of episodes.
 *
 * Season plus episode number is the identity the row actually HAS, and it is
 * unique per row however the files behind it are numbered - so the rail survives
 * every scheme, including one detected mid-season.
 *
 * The rail is deliberately ONE ROW PER EPISODE and not one row per file. A file
 * holding two segments is one thing to play and two things to watch, so merging
 * the pair into a single "E01 / E02" row would hide the second episode's
 * artwork, title and watch state behind the first. The shared file is already
 * expressed by the stream id and the scheme mapping; the rail keeps one row for
 * each episode that is in it (see EpisodeRowKeyTest).
 */
internal fun episodeRowKey(parentId: String, season: Int?, episodeNumber: Int): String =
    "$parentId:${season ?: "-"}:$episodeNumber"

/**
 * Keys of the rails a drill-down off this page can open from. [GENRE_ROW_KEY]
 * is the one that lives in the fixed header above the detail list rather than
 * as an item of it: it needs a name for the same reason the others do, so the
 * rail's own identity - never the position it happened to have - is what the
 * reveal looks for.
 */
private const val TAG = "DETAIL"

private const val GENRE_ROW_KEY = "genrerow"
private const val KEYWORDS_ROW_KEY = "keywordsrow"
private const val PEOPLE_ROW_KEY = "peoplerow"
private const val NETWORK_ROW_KEY = "networkrow"
private const val PRODUCTION_ROW_KEY = "productionrow"
private const val REVIEWS_ROW_KEY = "reviewsrow"
private const val COLLECTION_ROW_KEY = "collectionrow"
private const val MEDIA_STRIP_KEY = "mediastrip"
private const val MEDIA_ROW_KEY = "mediarow"
private const val RECS_ROW_KEY = "recsrow"

/**
 * The play press's own log tag.
 *
 * The field report this comes from - a poster menu's "Play Manually" leaving
 * the viewer on this page, and the same row on the Play button then doing
 * nothing at all - was invisible in a capture because the delivery and every
 * cancelled attempt were silent. One line per delivered press names the intent
 * and the target it opened, so the next capture says which press produced
 * which picker instead of leaving it to elimination.
 */
internal const val DETAIL_PLAY_TAG = "DETAIL_PLAY"

/** How many times the rail reveal and the focus request are retried. */
private const val RETURN_FOCUS_STEPS = 15

/** Delay between those retries, long enough for a frame to be laid out. */
private const val RETURN_FOCUS_DELAY_MS = 40L

/**
 * The one-frame wait before [retryChipColumn] asks a present-but-unready row
 * again: the 2026-10-14 GENRE -> KEYWORDS race, where
 * the rail is on screen but its chips have not registered yet. Long enough for
 * a frame to be laid out, short enough that a row which is genuinely not there
 * is still stepped over without a felt pause.
 */
private const val RETRY_CHIP_DELAY_MS = 50L

/**
 * Scrolls the detail list until the item keyed [rowKey] is on screen, trying
 * [hintIndex] first and then stepping outwards from it.
 *
 * Two things have to be waited for, because the list is lazy and the page
 * composes it in pieces: the list having been measured at all - until then a
 * scroll request has nothing to apply to - and the rail being composed, since
 * a card that is not composed has no FocusRequester to take the focus. The
 * index recorded on the way out is tried first, because the sections above a
 * rail do not normally change between two visits; the steps outwards are what
 * pick the rail up when one of them gained or lost an item while the
 * drill-down was open. The rail's own key is the anchor, never the position.
 */
private suspend fun LazyListState.revealRail(rowKey: String, hintIndex: Int): Boolean {
    var waited = 0
    while (layoutInfo.totalItemsCount == 0 && waited < RETURN_FOCUS_STEPS) {
        delay(RETURN_FOCUS_DELAY_MS)
        waited++
    }

    val hint = hintIndex.coerceAtLeast(0)
    val candidates =
        buildList {
            add(hint)
            for (delta in 1..8) {
                if (hint - delta >= 0) add(hint - delta)
                add(hint + delta)
            }
        }

    for (index in candidates) {
        runCatching { scrollToItem(index) }
        delay(RETURN_FOCUS_DELAY_MS)
        if (layoutInfo.visibleItemsInfo.any { it.key == rowKey }) return true
    }

    return false
}

private enum class EpisodeFocusEdge { START, END }

private data class EpisodeTransitionState(
    val edge: EpisodeFocusEdge? = null
)

private data class PosterMenuTarget(
    val tmdbId: Int,
    val mediaType: String,
    val name: String,
    /**
     * The rail card this poster is, for the menu's "Go to Details": the record
     * is raised when the action runs rather than when the menu opens, so a
     * menu dismissed on any other action leaves nothing behind. Null for a
     * menu raised from somewhere with no rail to return to.
     */
    val returnTarget: DetailReturnTarget? = null
)

private data class SeasonMenuTarget(
    val seasonNumber: Int,
    val seasonName: String,
    val episodeNumbers: List<Int>
)

private data class EpisodeMenuTarget(
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val seasonEpisodeNumbers: List<Int>,
    val streamId: String,
    val overview: String?,
    val runtimeMinutes: Int? = null
)

/**
 * A NETWORK or PRODUCTION chip whose long-press menu is open.
 *
 * The chip's own fields are all the Browse row needs: [categoryKey] is the
 * browse category the chip belongs to ("services" for a network, "studios"
 * for a production company), which is what decides the screen a Home tile
 * opens, and [id]/[name] are the entity the chip already links to. [onHome]
 * is read when the menu OPENS, so the action says Add or Remove for the state
 * the mirror is actually in.
 */
private data class StudioChipMenuTarget(
    val categoryKey: String,
    val id: Int,
    val name: String,
    val onHome: Boolean
)

private data class DetailFactItem(
    val label: String,
    val value: String,
    /**
     * Whether [value] may run past the two lines every other fact card is
     * capped at. Only the Awards fact sets it: OMDB answers with a whole
     * sentence ("Won 2 Oscars. 159 wins & 220 nominations total"), and
     * truncating that to "Won 2 Oscars. 159 wins & 220 nom…" would hide the
     * half of it that carries the counts. Left false elsewhere so the existing
     * facts keep the exact sizing they have today.
     */
    val wrap: Boolean = false
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private class TvPivotBringIntoViewSpec(
    private val parentFraction: Float = 0.3f,
    private val childFraction: Float = 0f
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float {
        val targetOffset = parentFraction * containerSize
        val childOffset = childFraction * size
        val destination = targetOffset - childOffset
        return offset - destination
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private val LocalTvBringIntoViewSpec = TvPivotBringIntoViewSpec()

/** Icon size for the header row's icon-only buttons (play / random / trailer). */
private val BUTTON_ICON_SIZE = 18.dp

/** Gap between an icon button's glyph and the progress row reserved under it. */
private val BUTTON_PROGRESS_GAP = 3.dp

/** Resume progress bar; its height is also the row every icon button reserves. */
private val BUTTON_PROGRESS_HEIGHT = 2.dp
private val BUTTON_PROGRESS_WIDTH = 26.dp

/**
 * Body shared by the header row's icon buttons: the glyph, then a row
 * underneath that only PLAY paints into when there is progress to resume.
 *
 * The row is reserved in EVERY button rather than only in the resume state,
 * so the three cards stay exactly the same height with their glyphs on one
 * baseline, and the row does not shift as progress appears or gets finished.
 *
 * The marks are the control bar's own geometry - ic_player_play for PLAY, and
 * ic_detail_shuffle / ic_detail_trailer for the other two - rather than
 * Material glyphs. The bar's icons were redrawn as vectors so that its buttons
 * would match each other (see ic_player_next); its play mark and Material's
 * PlayArrow happen to be the same triangle, but the shuffle and film-strip
 * glyphs are a different hand, and this row was the last place mixing them in.
 * They are still tinted by the card's content color, as the Material ones
 * were, so focus/idle coloring is unchanged.
 */
@Composable
private fun IconButtonBody(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    progress: Float? = null,
    // The brand mark (the detail PLAY control) carries its own brass gradients:
    // at the default accent it renders UNTINTED so it reads as the logo, and
    // under a chosen global accent it tints with that accent instead, so the
    // PLAY control follows the theme. The plain control-bar glyphs still tint
    // with focus/idle like every other icon button.
    brandMark: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(
            horizontal = 9.dp,
            vertical = 7.dp
        )
    ) {
        Icon(
            // painterResource + Icon tints with LocalContentColor, exactly as
            // the ImageVector form did — except the brand mark, which keeps its
            // own brass at the default accent and takes the theme accent once
            // one is chosen (the ring-and-triangle silhouette survives the flat
            // tint, so it still reads as the logo).
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = when {
                !brandMark -> androidx.tv.material3.LocalContentColor.current
                kbAccentIndexState.value == DEFAULT_ACCENT_INDEX -> Color.Unspecified
                else -> KBAccent
            },
            modifier = Modifier.size(BUTTON_ICON_SIZE)
        )

        Spacer(
            modifier = Modifier.height(BUTTON_PROGRESS_GAP)
        )

        Box(
            modifier = Modifier
                .width(BUTTON_PROGRESS_WIDTH)
                .height(BUTTON_PROGRESS_HEIGHT),
            contentAlignment = Alignment.CenterStart
        ) {
            if (progress != null) {
                // The bar follows the THEME, not the button's content color.
                // It used to paint with LocalContentColor, on the theory that
                // the card's content color is KBAccent while focused - but
                // KBCard keeps its content bright in BOTH states on purpose
                // (KBTextHi idle and focused, see its colors block), so this
                // bar sat there as plain white: the one progress bar in the
                // app that did not move with the viewer's accent, beside
                // poster tiles and Up Next cards that do (see KBProgressBar,
                // whose fill is this same token).
                //
                // Read HERE, inside the composable, rather than through a
                // top-level val: [KBAccent] is live state that an accent or
                // profile switch changes, and capturing it once is exactly how
                // the watch markers froze on the first-loaded theme (see
                // WatchMarkerAccentContractTest).
                val barColor = KBAccent
                val barShape = KBShapePill

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(barShape)
                        .background(barColor.copy(alpha = 0.3f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(barShape)
                        .background(barColor)
                )
            }
        }
    }
}

/**
 * The Detail screen's display name, or [UNKNOWN_SHOW_NAME] when there is no
 * real name to show.
 *
 * The screen's meta can carry the internal id as its `name` in three ways: an
 * unresolved add-on/TMDB merge (`buildMergedMeta`'s `?: id`), a TMDB record
 * whose `name` and `title` are both missing, and the cold-launch hand-off's
 * `displayName = showId` placeholder. That name is what this screen prints and
 * what every StreamsTarget on it copies into `displayName` - which the player
 * carries as `itemName` and prints as the show title in its Up Next panel. So
 * each candidate is filtered the way the Continue Watching cards already are
 * (see [upNextDisplayTitleOrNull]): an internal id is never a name.
 */
internal fun detailDisplayName(
    metaName: String?,
    tmdbName: String?,
    tmdbTitle: String?,
    hasArtwork: Boolean
): String =
    listOf(metaName, tmdbName, tmdbTitle)
        .firstNotNullOfOrNull { candidate ->
            upNextDisplayTitleOrNull(candidate, hasArtwork)
        }
        ?: UNKNOWN_SHOW_NAME

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DetailScreen(
    type: String,
    id: String,
    onNavigateDetail: (String, String) -> Unit,
    onNavigateActor: (Int) -> Unit,
    onNavigateStudio: (Int, String, Boolean) -> Unit,
    onNavigateTag: (Int, String, Boolean, String) -> Unit,
    onNavigateStreams: (
        StreamsTarget,
        String,
        String,
        String?,
        String?,
        String?,
        String?,
        List<TmdbCastMember>
    ) -> Unit,
    initialTarget: StreamsTarget? = null,
    initialPoster: String? = null,
    initialBackdrop: String? = null,
    initialClearLogo: String? = null,
    initialOverview: String? = null,
    // Reports the clearlogo this screen has resolved for the title, so a caller
    // covering the screen with the pre-playback splash can show that art the
    // moment it lands. See MainActivity's cover splash: it is painted before
    // Detail has its artwork, and without this it fell back to the plain name
    // while the NEXT splash showed the pulsing clearlogo.
    onClearLogoResolved: (String) -> Unit = {},
    // The same hand-off for the title's WIDESCREEN art, and for the same reason:
    // the Continue Watching / Up Next deep link that opens this screen carries
    // only the history row's meta, whose backdrop is usually absent, so the
    // cover splash ran artless while the player's own showed a still. Only a
    // real backdrop is reported - never [backdropUrl]'s poster fallback, which
    // is a portrait and would read as a zoomed, wrong still across the splash.
    onBackdropResolved: (String) -> Unit = {},
    // Keyed per (type, id): DetailScreen is the only consumer of a shared
    // DetailViewModel, and an unscoped (Activity-wide) instance carries the
    // previous title's meta/episodes/resume state into the next one — the
    // Continue Watching auto-play effect then fired with the previous card's
    // play target (second card opened the first card's stream screen).
    viewModel: DetailViewModel = viewModel(key = "detail_${type}_$id")
) {
    // The phone entry: the same ViewModel and callbacks, laid out for touch.
    // One branch; the TV page below is unchanged (see DetailScreenMobile).
    if (!FormFactor.isTv) {
        DetailScreenMobile(
            type = type,
            id = id,
            onNavigateDetail = onNavigateDetail,
            onNavigateActor = onNavigateActor,
            // A network or production-company chip opens that Studio page,
            // exactly as the TV page's chips do (the same arguments).
            onNavigateStudio = onNavigateStudio,
            // The genre and keyword chips open the same Tag route the TV
            // page's rails push, with the same four arguments.
            onNavigateTag = onNavigateTag,
            onNavigateStreams = onNavigateStreams,
            // The rest of the route's own state: a Continue Watching deep link
            // carries its target, the cover splash is painted from the art it
            // arrived with, and both hand-backs below feed that same splash.
            initialTarget = initialTarget,
            initialPoster = initialPoster,
            initialBackdrop = initialBackdrop,
            initialClearLogo = initialClearLogo,
            initialOverview = initialOverview,
            onClearLogoResolved = onClearLogoResolved,
            onBackdropResolved = onBackdropResolved,
            viewModel = viewModel
        )
        return
    }

    val scope = rememberCoroutineScope()

    // The vertical move in flight, if any (see [startFocusMove]).
    var focusMoveInFlight by remember { mutableStateOf<Job?>(null) }

    /**
     * Starts [move] as the page's one in-flight D-pad move, cancelling any move
     * still waiting on a frame.
     *
     * The chain's moves are asynchronous by necessity - a rail the scroll has
     * disposed has to be scrolled back in and then asked again a frame at a
     * time, up to [RETURN_FOCUS_STEPS] of them - so a press is free to arrive in
     * the middle of one. Two moves in flight then race each other: a DOWN that
     * is still retrying a rail it could not land on would finish AFTER the
     * viewer had already pressed UP, and snap them back down to where they came
     * from. A move only ever answers the press that started it, so the newest
     * press cancels the older one and the focus ends up where the last press
     * asked for it.
     */
    fun startFocusMove(move: suspend () -> Unit) {
        focusMoveInFlight?.cancel()
        focusMoveInFlight = scope.launch { move() }
    }

    /**
     * The page's one hand-back driver (see [rememberDetailHandBack]).
     *
     * A dismissal RECORDS the surface it was raised from and then closes; the
     * driver's single effect delivers the hand-back a frame later, once the
     * menu's scrim and its focused node have left the tree and the card
     * underneath has been recomposed. The ask is retried across frames and its
     * landing is the request's own answer - a request against a node that
     * cannot take focus yet answers FALSE rather than throwing, so the one-shot
     * ask missed silently and the D-pad restarted at the top of the page (the
     * episode row, which is the reported scroll-up).
     *
     * A record may carry a LIVE resolver: the rail targets are filed in the
     * maps below as their rails compose, and a card whose requester was rebuilt
     * while the overlay was up is found there again instead of being asked
     * through a dead instance. Whatever the resolver answers, the target is the
     * surface that opened the menu or overlay - only its delivery changed.
     */
    val handFocusBack = rememberDetailHandBack()

    // The requesters every rail files as it composes: what a hand-back's live
    // resolver reads (see the driver above) and what the page's own vertical
    // handoff aims at. Declared before the dismiss functions because they
    // record through them.
    val railFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val seasonFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val episodeFocusRequesters =
        remember { mutableMapOf<Pair<Int, Int>, FocusRequester>() }
    // One ordered slot per COLUMN of the narrow chip rails whose vertical
    // neighbours are decided here (the NETWORK, PRODUCTION and PEOPLE rails),
    // the row key naming the rail and the list index being the column: what a
    // vertical press steps by. A column nothing has been laid out for is null,
    // so a press into a rail that is not composing falls through rather than
    // pulling the focus out of the page.
    val chipColumnRequesters =
        remember { mutableMapOf<String, MutableList<FocusRequester?>>() }

    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    // A NETWORK / PRODUCTION chip opens its screen on a press, so its
    // long-press menu is where the Search browse chips' "keep this on Home"
    // action lives. The prefs are read when the menu opens and written from
    // the action; Home re-reads them when it next composes (KBHomeViewModel).
    val chipMenuContext = androidx.compose.ui.platform.LocalContext.current
    var studioChipMenu by remember {
        mutableStateOf<StudioChipMenuTarget?>(null)
    }

    // The chip that raised the menu. The menu is a focus group drawn over the
    // page (see PosterContextMenu), so without an explicit hand-back closing it
    // left nothing focused and the D-pad restarted at the top of the screen.
    var lastStudioChipFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissStudioChipMenu() {
        val chipKey = studioChipMenu?.let { menu ->
            if (menu.categoryKey == "services") {
                "network:${menu.id}"
            } else {
                "company:${menu.id}"
            }
        }
        studioChipMenu = null
        handFocusBack("network/production chip menu", lastStudioChipFocusRequester) {
            chipKey?.let { railFocusRequesters[it] }
        }
    }
    var selectedReview by remember { mutableStateOf<TmdbReview?>(null) }

    // The review tile that raised the fullscreen ReviewOverlay. Same rule as the
    // long-press menus (see [dismissPosterMenu]): the overlay is a focus group
    // drawn OVER the page, so closing it without a hand-back left the D-pad
    // focused on a disposed node, which the framework answers by restarting at
    // the top of the screen.
    var lastReviewTileFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissReviewOverlay() {
        val reviewId = selectedReview?.id
        selectedReview = null
        handFocusBack("review overlay", lastReviewTileFocusRequester) {
            reviewId?.let { railFocusRequesters["review:$it"] }
        }
    }

    // Long-press context menu for More Like This / collection rail posters.
    var posterMenu by remember {
        mutableStateOf<PosterMenuTarget?>(
            null
        )
    }

    var lastPosterFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissPosterMenu() {
        val railKey = posterMenu?.returnTarget?.targetKey
        posterMenu = null
        handFocusBack("poster menu", lastPosterFocusRequester) {
            railKey?.let { railFocusRequesters[it] }
        }
    }

    // Long-press context menu for the season chips (mark a whole season
    // watched / unwatched).
    var seasonMenu by remember {
        mutableStateOf<SeasonMenuTarget?>(
            null
        )
    }

    var lastSeasonFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissSeasonMenu() {
        val seasonNumber = seasonMenu?.seasonNumber
        seasonMenu = null
        handFocusBack("season menu", lastSeasonFocusRequester) {
            seasonNumber?.let { seasonFocusRequesters[it] }
        }
    }

    // Spoiler-free mode: the episode cards already hide an episode the viewer
    // has not started, and the long-press menu they open has to agree with them
    // (see the menu's heading).
    //
    // Read through the synced revision, not once for the screen: the mode is a
    // SYNCED pref, so a change made on another device (or a pull on this one)
    // has to repaint what is already on screen. A bare `remember(context)` only
    // re-read it when this screen was left and re-entered, so the menu kept the
    // old answer (see [PrefsPayloadApplier.revision]).
    val spoilerFreeContext = LocalContext.current
    val spoilerFreeRevision by com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
        .revision.collectAsStateWithLifecycle()
    val spoilerFreeEnabled = remember(spoilerFreeContext, spoilerFreeRevision) {
        AppPreferences.getSpoilerFree(spoilerFreeContext)
    }

    // Long-press context menu for the episode cards (mark this / previous /
    // whole season, or play manually).
    var episodeMenu by remember {
        mutableStateOf<EpisodeMenuTarget?>(
            null
        )
    }

    var lastEpisodeFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissEpisodeMenu() {
        val slot = episodeMenu?.let { it.season to it.episode }
        episodeMenu = null
        handFocusBack("episode menu", lastEpisodeFocusRequester) {
            slot?.let { episodeFocusRequesters[it] }
        }
    }

    var userManuallyChangedSeason by remember { mutableStateOf(false) }
    val episodesRowState = rememberLazyListState()
    val seasonRowState = rememberLazyListState()
    val detailListState = rememberLazyListState()
    // Every rail a drill-down off this page can be opened from, each with the
    // scroll state and the per-card requesters a return needs: the rail is put
    // back on screen, scrolled to the card that was pressed, and that card
    // takes the focus by requester - a rail that is not composing it has
    // nothing for a plain requestFocus to land on. One requester map serves
    // them all: the key a card is filed under names its rail.
    val genreRowState = rememberLazyListState()
    val keywordsRowState = rememberLazyListState()
    val peopleRowState = rememberLazyListState()
    val networkRowState = rememberLazyListState()
    val productionRowState = rememberLazyListState()
    val collectionRowState = rememberLazyListState()
    val mediaRowState = rememberLazyListState()
    val recsRowState = rememberLazyListState()

    /**
     * Where a card of the rail keyed [rowKey] sits right now: the record a
     * return is rebuilt from, and the index of the rail itself as it stands,
     * so the rail can be put back on screen (see [DetailReturnFocus]).
     */
    fun returnTargetFor(
        rowKey: String,
        targetKey: String,
        itemIndex: Int
    ): DetailReturnTarget =
        DetailReturnTarget(
            detailKey = "$type:$id",
            targetKey = targetKey,
            rowKey = rowKey,
            rowIndex = detailListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == rowKey }?.index ?: -1,
            itemIndex = itemIndex
        )

    /**
     * Remembers the rail card a drill-down is being opened from, so backing
     * out of it lands back on that card instead of the play button.
     */
    fun rememberReturnTarget(rowKey: String, targetKey: String, itemIndex: Int) {
        DetailReturnFocus.record(returnTargetFor(rowKey, targetKey, itemIndex))
    }

    /**
     * Files a chip's [requester] under its COLUMN in the rail named [rowKey]
     * (see [focusChipColumn]). Called as the rail composes, so an index the
     * rail has not reached yet - or one a separator takes - stays null.
     */
    fun registerChipColumn(rowKey: String, column: Int, requester: FocusRequester) {
        if (column < 0) {
            return
        }

        val slots = chipColumnRequesters.getOrPut(rowKey) { mutableListOf() }
        while (slots.size <= column) {
            slots.add(null)
        }
        slots[column] = requester
    }

    /**
     * Hands focus to the chip in the same COLUMN of [rowKey], and reports
     * whether that chip took it.
     *
     * Every one of these rails carries a focusRestorer, so a vertical press
     * that is left to the default search - or to that rail's restorer - lands
     * on the chip that rail was LAST left on. That is the reported bug: DOWN
     * from the leftmost NETWORK chip reached a PRODUCTION chip several columns
     * to its right, because the production rail still remembered one. Asking
     * for the column directly is the whole fix; the rails cannot be trusted to
     * agree on geometry, and they are measured independently.
     *
     * A rail that is not composed at all, or a column nothing has been laid out
     * for yet, answers false so the press falls through to the default search
     * rather than going nowhere. A rail SHORTER than the one the press came
     * from clamps to its last chip ([DetailChipColumns.columnFor]).
     *
     * The answer is the request's OWN report, not "the call did not throw". An
     * unattached requester - which is exactly what a disposed rail's slot holds,
     * because [registerChipColumn] files requesters as rails compose and nothing
     * unfiles them - prints the focus warning and RETURNS false rather than
     * throwing. `runCatching { ... }.isSuccess` was therefore true for every
     * call ever made, and this answered true for a rail that was not on screen
     * at all: the press was consumed with the focus left on the row it came
     * from, and [revealAndFocusChipColumn] - the arm that puts the rail back -
     * could never run. A miss is the caller's cue to retry, which is what the
     * reveal does and what the retries in [focusTopOfPage] do.
     */
    fun focusChipColumn(rowKey: String, fromColumn: Int): Boolean {
        val slots =
            chipColumnRequesters[rowKey]?.takeIf { it.isNotEmpty() } ?: return false
        val column = DetailChipColumns.columnFor(fromColumn, slots.size) ?: return false
        val requester = slots[column] ?: return false
        return runCatching { requester.requestFocus() }.getOrDefault(false)
    }

    /**
     * The horizontal scroll state of the rail named [rowKey], when the row is a
     * single LazyRow of chips or cards.
     *
     * A chip rail is lazy in BOTH directions: the vertical reveal puts the rail
     * item on screen, but the chip the press asks for can still be disposed
     * inside it - the rail left at the far end by a RIGHT press, or by a
     * drill-down return, whose `scrollToItem(itemIndex)` puts the returned chip
     * at the rail's LEFT edge and disposes every chip before it. A disposed
     * chip's requester answers false forever, so the column has to be scrolled
     * into composition before the ask (see [revealAndFocusChipColumn]).
     *
     * The same mapping the drill-down return uses for its own rail; kept here so
     * a rail added to one is visible to the other.
     */
    fun railStateFor(rowKey: String): LazyListState? = when (rowKey) {
        GENRE_ROW_KEY -> genreRowState
        KEYWORDS_ROW_KEY -> keywordsRowState
        PEOPLE_ROW_KEY -> peopleRowState
        NETWORK_ROW_KEY -> networkRowState
        PRODUCTION_ROW_KEY -> productionRowState
        COLLECTION_ROW_KEY -> collectionRowState
        RECS_ROW_KEY -> recsRowState
        MEDIA_ROW_KEY -> mediaRowState
        else -> null
    }

    /**
     * [focusChipColumn], asked once more a frame later.
     *
     * The 2026-10-14 field race: a rail this page HAS can be composed on screen
     * while its chips are still registering, so the first ask fails and the
     * press used to step straight over a row that was there - the reported
     * "GENRE -> KEYWORDS looked like a skip". One frame is the whole point: the
     * chips either register or the row really cannot answer, and the caller
     * then walks on. Only [focusRow]'s present-but-unanswered arm calls it, so a
     * row that is not on this page is still stepped over with no wait at all.
     */
    suspend fun retryChipColumn(rowKey: String, fromColumn: Int): Boolean {
        delay(RETRY_CHIP_DELAY_MS)
        return focusChipColumn(rowKey, fromColumn)
    }

    /**
     * Puts the chip rail keyed [rowKey] back into composition and gives the chip
     * in the same COLUMN the focus: the move a vertical press makes when the
     * rail it aims at exists but the scroll has disposed it (see
     * [focusChipColumn]).
     *
     * A disposed rail answers false to [focusChipColumn] because it has no chip
     * to take the focus - the requesters it filed as it composed are unattached
     * once it is gone - and nothing on the press side can tell that from the
     * row genuinely not being there. Treating disposal as absence is what
     * skipped the NETWORK rail on the way up out of PRODUCTION, and what left
     * the press with nowhere to land on the way up out of NETWORK. [revealRail]
     * scrolls the rail back in, anchored on [fromRowKey] - the rail a vertical
     * press came from is that rail's neighbour, so its index is the closest
     * hint there is - and the focus request is then held back until the row is
     * composed again and retried while its cards register.
     *
     * [fromRowKey] is NULL for the two chain rows that are not a list item - the
     * header row above the list, and the episode section, which is a season chip
     * row and an episode row at once (see [detailRowKey]). The hint is then the
     * top of the list: a worse anchor than a neighbour's index, but a press
     * coming out of those rows has nothing better to offer, and refusing to
     * reveal at all is what made DOWN off the episode rail jump over a row that
     * was only one screen below it.
     *
     * Answers whether the focus landed, but the caller consumes the press
     * either way (see [focusTopOfPage]): a key handed to an async scroll must
     * never also be handed to the default search.
     */
    suspend fun revealAndFocusChipColumn(
        rowKey: String,
        fromRowKey: String?,
        fromColumn: Int
    ): Boolean {
        val hint = detailListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == fromRowKey }?.index ?: 0
        detailListState.revealRail(rowKey, hint)
        var attempts = 0
        var railScrolled = false
        while (attempts < RETURN_FOCUS_STEPS) {
            // The rail has to be back in composition before its card can take
            // the focus, and a lazy list composes it a frame or more after the
            // scroll that brings it back: the row's own key among the visible
            // items is the proof that asking is worth doing. Firing at a card
            // that is not composed yet fails silently, which is the stranding.
            val composed =
                detailListState.layoutInfo.visibleItemsInfo.any { it.key == rowKey }
            if (composed) {
                // The chip rail is lazy a second time: the chip in the press's
                // COLUMN can be disposed inside a rail that is on screen (see
                // [railStateFor]), and then no number of vertical reveals will
                // make it answer. Scroll the rail to that column once - its own
                // item count is the clamp, so a shorter rail lands on its last
                // chip exactly as [DetailChipColumns] says it should - and only
                // then ask. This is the GENRE -> KEYWORDS skip: after a keyword
                // drill-down the rail sits at the returned chip's index, the
                // column the press asks for is off the left edge, and the press
                // fell through to the episode card below.
                if (!railScrolled) {
                    val rail = railStateFor(rowKey)
                    val columns = rail?.layoutInfo?.totalItemsCount ?: 0
                    val column =
                        if (columns > 0) {
                            DetailChipColumns.columnFor(fromColumn, columns)
                        } else {
                            null
                        }
                    if (rail != null && column != null) {
                        runCatching { rail.scrollToItem(column) }
                        delay(RETURN_FOCUS_DELAY_MS)
                        railScrolled = true
                    }
                }
                if (focusChipColumn(rowKey, fromColumn)) return true
            }
            delay(RETURN_FOCUS_DELAY_MS)
            attempts++
        }
        return false
    }

    // The card a drill-down off this page was opened from, read once as the
    // page composes; null on an ordinary open (see [DetailReturnFocus]).
    var returnTarget by remember { mutableStateOf<DetailReturnTarget?>(null) }
    LaunchedEffect(type, id) {
        returnTarget = DetailReturnFocus.consume("$type:$id")
    }

    // Set once a return has been honored, so the page's own auto-focus (the
    // resume episode) does not pull the focus back out of the rail the viewer
    // just returned to when a slow episode list lands a moment later.
    var restoredReturnFocus by remember { mutableStateOf(false) }
    var episodeTransitionState by remember {
        mutableStateOf(EpisodeTransitionState())
    }

    // Focus sink for season changes triggered from the episode cards
    // (Left/Right past a row edge, or OK on a chip). Swapping seasons
    // replaces the whole episodes row - and while episodes reload it is
    // swapped for a placeholder item - which disposes the focused card.
    // Compose's fallback search then throws the ring at the Play button
    // until the edge-restore effect below lands it on the target card:
    // the visible up-then-back jump. This ZERO-SIZE, always-composed node
    // (attached at the meta-content root, before any state swap) is parked
    // on SYNCHRONOUSLY before the season change is applied, so the focused
    // card's disposal never leaves focus ownerless and the fallback search
    // never runs. It is invisible, has zero bounds, and takes part in no
    // manual navigation because focus always leaves it within milliseconds
    // - via the edge-restore effect (key presses) or focusSeasonOrEpisode
    // (chip presses).
    val seasonSwapFocusSink = remember { FocusRequester() }
    var seasonSwapSinkArmed by remember { mutableStateOf(false) }

    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val mdbListRatings by viewModel.mdbListRatings.collectAsStateWithLifecycle()
    val awards by viewModel.awards.collectAsStateWithLifecycle()
    val allReviews by viewModel.allReviews.collectAsStateWithLifecycle()
    val tmdbDetail by viewModel.tmdbDetail.collectAsStateWithLifecycle()
    // The app's transient message channel, used where a press has nowhere to
    // go (a poster TMDB cannot resolve to an IMDb id): silence there reads as a
    // broken remote, not as a title with no destination.
    val feedback = rememberKBFeedback()
    // The trailer the button offers IS the trailer the button plays: one pick,
    // one rule. Movies almost always carry a video typed exactly "Trailer", so
    // this matters for series, which TMDB frequently files as a "Teaser".
    val trailerVideo = remember(tmdbDetail) {
        TrailerPick.best(tmdbDetail?.videos?.results)
    }
    // PO-token pre-warm: a title that HAS a trailer means the press is seconds to
    // minutes away, and the cold WebView is what a resolve pays its whole mint
    // budget for (see InnerTubeExtractor.prewarmPoTokens). Off the main thread
    // and never on the screen's path; a pre-warm that fails changes nothing.
    LaunchedEffect(trailerVideo?.key) {
        trailerVideo?.key?.takeIf { it.isNotBlank() }?.let { key ->
            InnerTubeExtractor.prewarmPoTokens(key)
        }
    }
    // TMDB's English clearlogo, or nothing. The add-on's own `logo` (fanart.tv
    // etc.) used to be the fallback, but it is a bare URL with no language on
    // it, so a localized catalog's wordmark could reach this header and the
    // pre-playback splash it feeds. With no English TMDB mark the header shows
    // its plain title instead.
    val clearLogoUrl = tmdbImage(tmdbDetail?.bestLogoPath(), "w500")
    // Hand the resolved logo up as soon as it exists.
    LaunchedEffect(clearLogoUrl) {
        clearLogoUrl?.takeIf { it.isNotBlank() }?.let(onClearLogoResolved)
    }
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    // Air dates from a second metadata source (see AirDateCorrection). TMDB's
    // own dates are volunteer-edited and lag a currently-airing season, which
    // is what dimmed a live season's chip and badged its episodes UNAVAILABLE.
    val airDateCorrections by viewModel.airDateCorrections.collectAsStateWithLifecycle()
    val episodesLoading by viewModel.episodesLoading.collectAsStateWithLifecycle()
    val episodeError by viewModel.episodeError.collectAsStateWithLifecycle()
    val resumeInfo by viewModel.resumeInfo.collectAsStateWithLifecycle()
    // All in-progress rows for this title, keyed by episodeStreamId: lets
    // EVERY in-progress episode card show its own progress bar / time left,
    // not just the single most recent one in resumeInfo.
    val inProgressByStreamId by viewModel.inProgressByStreamId.collectAsStateWithLifecycle()
    val collection by viewModel.collection.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    // Titles this profile hid, so the rails on this screen drop them too.
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val resolvedPosterIds by viewModel.resolvedPosterIds.collectAsStateWithLifecycle()
    val completedEpisodeIds by viewModel.completedEpisodeIds.collectAsStateWithLifecycle()
    val watchedEpisodeKeys by viewModel.watchedEpisodeKeys.collectAsStateWithLifecycle()
    val simklWatchedEpisodes by viewModel.simklWatchedEpisodes.collectAsStateWithLifecycle()
    val simklSeriesWatched by viewModel.simklSeriesWatched.collectAsStateWithLifecycle()
    // The Simkl page for this title: the watched ticks this screen draws come
    // from the account's Simkl record, and Simkl's API rules ask for a link back
    // to that exact item wherever their data shows. See the season menu below.
    val simklPageUrl by viewModel.simklPageUrl.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val vmTargetEpisode by viewModel.targetEpisode.collectAsStateWithLifecycle()
    val vmLoadedSeason by viewModel.loadedSeason.collectAsStateWithLifecycle()
    val vmPlayButtonText by viewModel.playButtonText.collectAsStateWithLifecycle()

    // The id this title's stream requests are named by: the IMDB id TMDB's own
    // record maps to, not the catalog id this screen was opened with (see
    // TmdbRepository.streamRootIdFor). Blank until that lookup answers, and the
    // route id stands in then - which is what every request used before this
    // existed, so nothing regresses when there is no TMDB record to ask about.
    val vmStreamRootId by viewModel.streamRootId.collectAsStateWithLifecycle()
    val streamRootId = vmStreamRootId.ifBlank { id }

    fun clearEpisodeTransitionState() {
        episodeTransitionState = EpisodeTransitionState()
    }

    val hasStreamAddons by viewModel.hasStreamAddons.collectAsStateWithLifecycle()

    val normalizedType = when (type.lowercase()) {
        "tv", "show" -> "series"
        // Anime catalogs emit type "anime"; TMDB serves those shows under
        // series, so the season/episode UI must treat anime as series.
        "anime", "anime.series" -> "series"
        "anime.movie" -> "movie"
        else -> type.lowercase()
    }

    val seasons = remember(tmdbDetail) {
        tmdbDetail?.seasons.orEmpty()
            .map { it.seasonNumber }
            .distinct()
            .sortedWith(compareBy({ it == 0 }, { it }))
    }

    // Seasons TMDB lists with a future air_date are announced but not yet
    // released (e.g. Silo S4). Keep their premiere dates so the UI can dim
    // those chips and explain why the episode list is empty instead of a
    // bare "No episodes found for this season."
    //
    // A season's own air_date can be STALE, though: one that is already
    // running sometimes still carries its original announced premiere.
    // American Horror Story: 13 shipped "2026-10-01" while the show's own
    // next_episode_to_air said "2026-09-25" - the date the Home Upcoming
    // rail showed, and the right one. So fold that show-level pointer in and
    // let the EARLIER date win: it can only make a season look more
    // released, never less.
    //
    // That pointer only helps when TMDB's two answers disagree. When they
    // agree and are BOTH stale, a second metadata source supplies the date
    // (see AirDateCorrection); its answer wins only when TMDB still claims a
    // date that has already passed.
    val today = remember { LocalDate.now() }
    val seasonPremiereDates = remember(tmdbDetail, airDateCorrections) {
        val bySeason = LinkedHashMap<Int, LocalDate>()

        fun offer(seasonNumber: Int, raw: String?) {
            val date = raw
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return
            val existing = bySeason[seasonNumber]
            if (existing == null || date.isBefore(existing)) {
                bySeason[seasonNumber] = date
            }
        }

        tmdbDetail?.seasons.orEmpty().forEach { season ->
            offer(season.seasonNumber, season.airDate)
        }
        // Held in a local: `tmdbDetail` is a delegated property, which Kotlin
        // cannot smart cast, and the lambda below needs it non-null.
        val nextEpisode = tmdbDetail?.nextEpisodeToAir
        nextEpisode?.seasonNumber?.let { seasonNumber ->
            offer(seasonNumber, nextEpisode.airDate)
        }

        AirDateCorrection.correctPremieres(
            primary = bySeason,
            secondary = airDateCorrections.seasonPremieres,
            today = today
        )
    }

    // The seasons where the season row and the show-level pointer disagree,
    // i.e. the season rows are the stale source. Per-episode dates for those
    // seasons cannot be trusted either, so an episode card keeps its date but
    // drops the UNAVAILABLE verdict (see [EpisodeCard]).
    val seasonsWithStaleDates = remember(tmdbDetail) {
        val pointer = tmdbDetail?.nextEpisodeToAir
        val seasonNumber = pointer?.seasonNumber
        val rowDate = tmdbDetail?.seasons.orEmpty()
            .firstOrNull { it.seasonNumber == seasonNumber }
            ?.airDate

        if (
            seasonNumber != null &&
            !pointer?.airDate.isNullOrBlank() &&
            rowDate != null &&
            rowDate != pointer.airDate
        ) {
            setOf(seasonNumber)
        } else {
            emptySet()
        }
    }

    fun seasonUnavailable(season: Int): Boolean =
        seasonPremiereDates[season]?.isAfter(today) == true

    // The people rail's items: writer, director, then the billed cast,
    // de-duplicated and capped. Memoized because the detail list builder below
    // runs on EVERY recomposition of a screen that recomposes constantly
    // (position ticks, badge updates, focus moves), while this list depends
    // only on tmdbDetail - so building it inline re-deduplicated and
    // re-allocated the whole rail for a value that had not changed.
    val peopleItems = remember(tmdbDetail) {
        val tmdbCast = tmdbDetail?.credits?.cast.orEmpty()
        val tmdbDirector = tmdbDetail?.credits?.director()
        val mainWriter = tmdbDetail?.credits?.writers().orEmpty().distinctBy { it.id }.firstOrNull()

        buildList<PeopleRowItem> {
            mainWriter?.let { writer ->
                add(
                    PeopleRowItem.Person(
                        TmdbCastMember(writer.id, writer.name, "Writer", writer.profilePath)
                    )
                )
            }

            tmdbDirector?.let { director ->
                if (director.id != mainWriter?.id) {
                    add(
                        PeopleRowItem.Person(
                            TmdbCastMember(
                                director.id,
                                director.name,
                                "Director",
                                director.profilePath
                            )
                        )
                    )
                }
            }

            // Deduped by the CREDIT, not the person: a performer playing two
            // roles in one title (routine for animation voice casts) is two
            // rows on this rail, and the rail's own key is the composite
            // person+character. Deduping by the bare id dropped the second
            // character and left the character half of that key unable to ever
            // distinguish anything.
            val castItems = tmdbCast
                .distinctBy { it.id to it.character.orEmpty() }
                .take(25)
                .map { PeopleRowItem.Person(it) }
            if (castItems.isNotEmpty() && isNotEmpty()) {
                add(PeopleRowItem.Separator)
            }
            addAll(castItems)
        }
    }

    /**
     * Whether this page has a PEOPLE rail at all, which is what renders it (see
     * the `peopleheader`/`peoplerow` items below) and what the chip handlers
     * above and below tell apart from a rail the scroll has disposed. Hoisted
     * out of the list builder so both read the same expression.
     */
    val hasPeopleRow = peopleItems.isNotEmpty()

    // The COLUMN each people item occupies, by its index in [peopleItems]: the
    // writer, the director and the cast number 0, 1, 2, ... down the rail, and
    // the separators between the groups take none. This is what the network and
    // production chips below step by, so "the chip directly above this one" is
    // the writer/actor directly above it rather than the card the rail was last
    // left on.
    val peopleColumns = remember(peopleItems) {
        var column = -1
        peopleItems.map { item ->
            if (item is PeopleRowItem.Person) {
                column += 1
                column
            } else {
                null
            }
        }
    }

    // Same reasoning: a filter over the collection's parts, recomputed on every
    // recomposition of the list builder for a value that only changes when the
    // collection or the detail payload does.
    val collectionParts = remember(collection, tmdbDetail?.id) {
        collection?.parts
            .orEmpty()
            .filter { it.id != tmdbDetail?.id }
    }

    /**
     * The More Like This rail, minus hidden titles.
     *
     * Recomputed on every recomposition of the content when it is inlined into
     * the list builder. The three inputs below are the only things it reads, so
     * it is remembered on them the way [collectionParts] is.
     *
     * A hidden title stays out of this rail as well - otherwise the one rail
     * that is all about the title you just hid is the first place it comes
     * back.
     */
    val recommendations = remember(tmdbDetail, hiddenTitleKeys, normalizedType) {
        tmdbDetail?.recommendations
            ?.results
            .orEmpty()
            .filterNot { rec ->
                HiddenTitles.hides(
                    hiddenTitleKeys,
                    normalizedType,
                    rec.title ?: rec.name,
                    rec.releaseDate?.take(4)?.toIntOrNull()
                        ?: rec.firstAirDate
                            ?.take(4)?.toIntOrNull(),
                    "tmdb:${rec.id}"
                )
            }
    }

    // ── The Media row: one row, three pages (see DetailMediaRow) ────────

    /**
     * The videos, extra backdrops and posters the row draws, straight off the
     * detail payload.
     *
     * All three are already in hand: `videos` and `images` are two of the parts
     * this screen's single `append_to_response` asks TMDB for, so the full list
     * of any of them costs no extra call - the header's trailer button picks its
     * one entry out of the first (see [TrailerPick.best]), the hero draws one
     * entry of the second, and this is the first surface to show the rest of
     * them.
     */
    val mediaVideos = remember(tmdbDetail) {
        tmdbDetail?.videos?.results.orEmpty()
    }
    val mediaBackdrops = remember(tmdbDetail) {
        tmdbDetail?.images?.backdrops.orEmpty()
    }
    val mediaPosters = remember(tmdbDetail) {
        tmdbDetail?.images?.posters.orEmpty()
    }

    /**
     * The row's pages, in the order its strip shows them, and the page the
     * viewer is on.
     *
     * A page with nothing on it is not offered at all (see
     * [DetailMediaRow.tabs]), which is also what keeps the strip's DOWN always
     * landing on a card: the row is only painted when one of the three lists is
     * non-empty, and each tab then has content under it.
     */
    val mediaTabs = remember(mediaVideos, mediaBackdrops, mediaPosters) {
        DetailMediaRow.tabs(
            mediaVideos.size,
            mediaBackdrops.size,
            mediaPosters.size
        )
    }
    // Remembered for the LIFE of the title, not keyed on the tab list: the list
    // instance is rebuilt whenever the detail payload re-emits (a rating or
    // runtime landing, a TMDB cache refresh), and keying the state on it snapped
    // the viewer back to the first tab mid-browse. The `getOrNull` clamp below
    // already handles a list that shrank.
    //
    // Keyed on (type, id) because this composable is one unkeyed slot that a
    // drill-down (More Like This, a collection part) reuses for a different
    // title: unkeyed, title A's tab carried onto title B's page and B opened on
    // Posters when the viewer had left A on Posters. The phone fixed this exact
    // leak by keying its tab on the title; this is the same rule.
    var mediaTabIndex by remember(type, id) { mutableIntStateOf(0) }
    val mediaTab = mediaTabs.getOrNull(mediaTabIndex) ?: mediaTabs.firstOrNull()

    // The active page's cards, projected once per payload rather than per
    // recomposition of a screen that recomposes constantly (position ticks,
    // badge updates, focus moves): which URL a card shows, what its badge says,
    // what shape it draws and which video it plays are [DetailMediaRow]'s rules.
    val mediaCards = remember(mediaTab, mediaVideos, mediaBackdrops, mediaPosters) {
        when (mediaTab) {
            MediaTab.TRAILERS -> DetailMediaRow.trailers(mediaVideos)
            MediaTab.BACKDROPS -> DetailMediaRow.backdrops(mediaBackdrops)
            MediaTab.POSTERS -> DetailMediaRow.posters(mediaPosters)
            null -> emptyList()
        }
    }

    // The tab strip is ONE focus node (see mediaStripFocus): the tabs are the
    // strip's own pages, not one node each to be walked between, so a
    // LEFT/RIGHT press can never leave the row sideways.
    val mediaStripFocusRequester = remember { FocusRequester() }

    // The horizontal position the viewer last held in the Media row, as a CARD
    // column. It is written as the cards take focus (walking them with
    // LEFT/RIGHT) and when a press from the row ABOVE hands over to the strip,
    // because to a viewer those are one thing - where they are in the Media
    // section - and both directions read it: DOWN back into the cards returns to
    // the card they were on (see [focusFirstMediaCard]) and UP out of the strip
    // keeps that column in the More Like This rail above (see [mediaStripFocus]).
    var lastMediaCardColumn by remember { mutableIntStateOf(0) }

    // The backdrop a press opened fullscreen, or null (see BackdropOverlay).
    var selectedBackdrop by remember { mutableStateOf<String?>(null) }

    // The Media card that raised that fullscreen still, for the same hand-back
    // as the review overlay above - this is the page's only destination-less
    // zoom, so closing it is the only way back to the row that opened it.
    var lastBackdropCardFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissBackdropOverlay() {
        selectedBackdrop = null
        handFocusBack("backdrop overlay", lastBackdropCardFocusRequester)
    }


    /**
     * The image choices the viewer has made, and the TMDB id they are filed
     * under for this title.
     *
     * Read once here and handed to every place that needs it, so the hero and
     * the long-press menu below cannot disagree about which title's choices
     * they are looking at: the route id is the app's own spelling (a
     * "tmdb:603" route, a bare number), while the choices are always written
     * under the NUMBER (see [ImageOverrides.tmdbIdOf]).
     */
    val overridePaths = rememberImageOverridePaths()
    val overrideTmdbId = tmdbDetail?.id ?: ImageOverrides.tmdbIdOf(id)

    // The Media card a long press opened the image menu on, or null (see
    // ImageOverrideMenu). One menu for the row: the card it was opened on is
    // what it writes (see DetailMediaRow.overrideRows for which rows it gets).
    var mediaImageMenu by remember { mutableStateOf<MediaCardItem?>(null) }

    // The Media card that long press was on, for the hand-back. The menu draws
    // in its OWN focus group (see PosterContextMenu), so it takes focus with it
    // and clearing the flag alone dropped the viewer at the top of the page -
    // the one menu on this screen that did not put focus back (the studio chip,
    // review, poster, season, episode and play-button menus all do, each
    // through its own requester).
    var mediaImageMenuCardFocusRequester by remember {
        mutableStateOf<FocusRequester?>(
            null
        )
    }

    fun dismissMediaImageMenu() {
        mediaImageMenu = null
        handFocusBack("media image menu", mediaImageMenuCardFocusRequester)
    }

    val premiereDateFormatter = remember {
        DateFormats.longDate()
    }

    val movieDetailsFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }

    /**
     * The last resort of a vertical chip press whose target rail does not exist
     * at all - the other half of [revealAndFocusChipColumn], which covers the
     * rail that does exist but has been disposed. The list goes back to its
     * top and the focus goes to the page's own controls, in this order: the
     * PLAY button, which lives in the fixed header ABOVE the list and is the
     * row an UP press out of the genre chips names (see the chips' own
     * handler), then the people rail's requester, which is what the movie path
     * uses to jump into that rail.
     *
     * The order is the whole of the fix for the reported UP trap. The people
     * rail is an item INSIDE the list, below the header, so a landing there is
     * the press's own direction reversed: UP on a genre chip scrolled the page
     * to its top and then dropped the focus into the actors rail, whose own UP
     * hands it straight back to the chips - a loop with the header, the PLAY
     * button and the top bar all unreachable. Requesting that rail's focus
     * without this scroll - and with no node behind it when there is no rail -
     * is the only thing that could throw, and a request that threw left the
     * viewer trapped on the chip they pressed, which is why the header control
     * goes first and the rail is kept only as the last resort.
     *
     * Consumes the press by construction: the launched block owns the move, so
     * every caller answers true rather than letting the default search - and the
     * rail's restorer - move the focus a second time.
     *
     * Answers whether the focus landed. The chain's last arm is the one caller
     * that needs to know: a header with nothing left to hand the focus to is the
     * only way this can answer false.
     *
     * Each ask reports its own answer, for the reason [focusChipColumn] spells
     * out: the people rail's requester is unattached whenever that rail is below
     * the fold, and "the call did not throw" read that as a landing on the first
     * pass - so the loop exited before the page had composed what it was waiting
     * for. The return value is what makes the retry real, and what keeps a
     * request that took nothing from being counted as the landing.
     */
    suspend fun focusTopOfPage(): Boolean {
        runCatching { detailListState.scrollToItem(0) }
        var attempts = 0
        while (attempts < RETURN_FOCUS_STEPS) {
            // Header control first, the in-list people rail only if the header
            // cannot take it: the header is the row UP points at, and it is
            // composed whatever the list has scrolled away (see the trap note
            // above).
            val landed =
                runCatching { playButtonFocusRequester.requestFocus() }.getOrDefault(false) ||
                    runCatching { movieDetailsFocusRequester.requestFocus() }.getOrDefault(false)
            if (landed) return true
            delay(RETURN_FOCUS_DELAY_MS)
            attempts += 1
        }
        return false
    }

    val effectiveSeason = remember(
        type,
        selectedSeason,
        seasons,
        initialTarget?.season,
        resumeInfo?.season,
        vmLoadedSeason,
        isLoading
    ) {
        if (normalizedType != "series") {
            null
        } else {
            selectedSeason
                ?: initialTarget?.season?.takeIf { it in seasons }
                ?: resumeInfo?.season?.takeIf { it in seasons }
                ?: vmLoadedSeason?.takeIf { it in seasons }
                ?: seasons.firstOrNull { !seasonUnavailable(it) }.takeIf { !isLoading }
        }
    }

    /**
     * The loaded season's AIRED episode count - the total every episode line on
     * this screen and every episode total this screen hands the player (and so
     * every row it writes to history) is built from.
     *
     * Kept as one value rather than calling [airedEpisodeCount] at each use so
     * the count on screen and the count stored for the same season cannot drift
     * apart; see [airedEpisodeCount] for why the list's size is not it.
     *
     * Declared here, after [effectiveSeason], because that is the first point
     * the season being shown is known: `seasons.firstOrNull { !seasonUnavailable
     * (it) }` above is what picks it when the caller names no season.
     */
    val airedEpisodesInSeason = remember(
        episodes,
        effectiveSeason,
        seasonsWithStaleDates,
        today
    ) {
        airedEpisodeCount(
            episodes = episodes,
            airDatesTrusted =
                effectiveSeason == null || effectiveSeason !in seasonsWithStaleDates,
            today = today
        )
    }

    // A season can name itself: American Horror Story's "Coven", Monster's
    // "The Jeffrey Dahmer Story". For an anthology the chip reading "SEASON 3"
    // says nothing about what is underneath it, so the section heading borrows
    // the season's own name and follows the chips as the selection moves.
    // Shows whose seasons TMDB only numbers keep the old "EPISODES".
    val episodesHeader = remember(tmdbDetail, effectiveSeason) {
        effectiveSeason
            ?.let { season -> tmdbDetail?.displaySeasonName(season) }
            ?.uppercase(Locale.US)
            ?: "EPISODES"
    }

    val hasExplicitSeasonSource = normalizedType == "series" && (
        selectedSeason != null ||
            initialTarget?.season?.takeIf { it in seasons } != null ||
            resumeInfo?.season?.takeIf { it in seasons } != null ||
            vmLoadedSeason?.takeIf { it in seasons } != null
        )

    // Bumped by the error card's retry. Both load effects key on this next to
    // their own inputs, because re-running them is what re-opening the screen
    // does: a retry then cannot leave the page in a state a fresh open would
    // not have produced (the season's episode list included).
    var detailRetryTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(
        type,
        id,
        initialTarget?.season,
        initialTarget?.episode,
        detailRetryTick
    ) {
        selectedSeason = initialTarget?.season
        userManuallyChangedSeason = false
        seasonFocusRequesters.clear()
        episodeFocusRequesters.clear()
        // A drill-down ("More Like This") reuses this composition with a new
        // type/id, so the previous title's drill-down-return latch has to go
        // with the rest of its state: left set, it would suppress the play
        // button's default focus on a title that was never returned to.
        restoredReturnFocus = false
        viewModel.load(
            type = type,
            id = id,
            initialMeta = Meta(
                id = id,
                type = type,
                name = initialTarget?.displayName?.takeIf { it.isNotBlank() } ?: id,
                poster = initialPoster,
                background = initialBackdrop,
                logo = initialClearLogo,
                description = initialOverview
            ).takeIf {
                !initialPoster.isNullOrBlank() ||
                    !initialBackdrop.isNullOrBlank() ||
                    !initialClearLogo.isNullOrBlank() ||
                    !initialOverview.isNullOrBlank()
            },
            isDeepLinkAutoPlay = initialTarget != null
        )
    }

    LaunchedEffect(
        type,
        id,
        effectiveSeason,
        hasExplicitSeasonSource,
        detailRetryTick
    ) {
        if (
            normalizedType == "series" &&
            effectiveSeason != null &&
            hasExplicitSeasonSource
        ) {
            viewModel.loadEpisodesForSeason(effectiveSeason)
        }
    }

    val resumeSeason = resumeInfo?.season
    val resumeEpisode = resumeInfo?.episode
    val resumeStreamId = resumeInfo?.episodeStreamId

    val hasSeriesResume =
        normalizedType == "series" &&
            (resumeInfo?.positionMs ?: 0L) > 0L &&
            resumeSeason != null &&
            resumeEpisode != null

    val simklSeasonEpisodes = remember(
        type,
        effectiveSeason,
        simklWatchedEpisodes
    ) {
        if (normalizedType != "series" || effectiveSeason == null) {
            emptySet<Int>()
        } else {
            simklWatchedEpisodes
                .filter { (season, _) -> season == effectiveSeason }
                .map { (_, episode) -> episode }
                .toSet()
        }
    }

    val locallyWatchedSeasonEpisodes = remember(
        type,
        id,
        effectiveSeason,
        watchedEpisodeKeys
    ) {
        if (normalizedType != "series" || effectiveSeason == null) {
            emptySet<Int>()
        } else {
            watchedEpisodeKeys.mapNotNull { key ->
                val parts = key.split(":")
                if (parts.size < 3) return@mapNotNull null

                val keyId = parts.dropLast(2).joinToString(":")
                val season = parts[parts.size - 2].toIntOrNull()
                val episode = parts[parts.size - 1].toIntOrNull()

                if (
                    keyId == id &&
                    season == effectiveSeason &&
                    episode != null
                ) {
                    episode
                } else {
                    null
                }
            }.toSet()
        }
    }

    val watchedEpisodesForSeason = remember(
        simklSeasonEpisodes,
        locallyWatchedSeasonEpisodes
    ) {
        if (simklSeasonEpisodes.isNotEmpty()) {
            simklSeasonEpisodes
        } else {
            locallyWatchedSeasonEpisodes
        }
    }

    // Episode numbers per season, used by the season-chip long-press menu to
    // decide watched state and to mark/unmark the whole season. The loaded
    // season uses the resolved episode list; other seasons fall back to the
    // TMDB episode_count (null when unknown).
    val seasonEpisodeNumbersFor = remember(
        tmdbDetail,
        episodes,
        effectiveSeason,
        seasonsWithStaleDates
    ) {
        fun numbersFor(seasonNum: Int): List<Int>? {
            val summary = tmdbDetail?.seasons
                ?.firstOrNull {
                    it.seasonNumber == seasonNum
                }
            // One rule for both screens and the ViewModel (see
            // [DetailSeasonEpisodes]): a season mark/unmark must not sweep up
            // UNAIRED episodes - not on the loaded season (whose listing
            // includes next week's card), and not on an unloaded one (whose
            // only number used to be the scheduled episode count).
            return DetailSeasonEpisodes.numbers(
                season = seasonNum,
                loadedSeason = effectiveSeason,
                episodes = episodes,
                episodeCount = summary?.episodeCount,
                seasonAirDate = summary?.airDate,
                nextEpisodeToAir = tmdbDetail?.nextEpisodeToAir,
                airDatesTrusted = seasonNum !in seasonsWithStaleDates
            )
        }

        ::numbersFor
    }

    // Whole-series long-press action: every season's episode numbers in one
    // map, so a show with a lot of seasons can be marked watched or unwatched
    // in a single shot instead of season by season.
    val wholeSeriesEpisodeNumbers = remember(
        seasons,
        tmdbDetail,
        episodes,
        effectiveSeason
    ) {
        seasons.mapNotNull { seasonNum ->
            seasonEpisodeNumbersFor(seasonNum)
                ?.takeIf { it.isNotEmpty() }
                ?.let { numbers ->
                    seasonNum to numbers
                }
        }
    }

    // True when every known episode of the show is already watched, so the
    // whole-series action knows which way to toggle.
    val wholeSeriesWatched = remember(
        wholeSeriesEpisodeNumbers,
        watchedEpisodeKeys,
        simklWatchedEpisodes,
        id
    ) {
        wholeSeriesEpisodeNumbers.isNotEmpty() &&
            wholeSeriesEpisodeNumbers.all { (season, numbers) ->
                val watchedSet =
                    WatchedEpisodeState
                        .effectiveWatchedEpisodesForSeason(
                            parentId = id,
                            season = season,
                            simklWatchedEpisodes =
                                simklWatchedEpisodes,
                            watchedEpisodeKeys =
                                watchedEpisodeKeys
                        )

                numbers.all { it in watchedSet }
            }
    }

    val resolvedTargetEpisode = remember(
        type,
        episodes,
        initialTarget?.season,
        initialTarget?.episode,
        initialTarget?.streamId,
        effectiveSeason,
        vmTargetEpisode
    ) {
        if (normalizedType != "series") {
            null
        } else if (
            initialTarget?.season == effectiveSeason &&
            initialTarget?.episode != null
        ) {
            episodes.firstOrNull { episode ->
                if (!initialTarget?.streamId.isNullOrBlank() &&
                    episode.streamId == initialTarget?.streamId
                ) {
                    true
                } else {
                    episode.episodeNumber == initialTarget?.episode
                }
            } ?: episodes.firstOrNull {
                it.episodeNumber == initialTarget?.episode
            } ?: episodes.firstOrNull()
        } else {
            vmTargetEpisode?.takeIf { ep ->
                episodes.any { it.streamId == ep.streamId }
            } ?: episodes.firstOrNull()
        }
    }

    val targetEpisodeNumber = remember(
        type,
        resolvedTargetEpisode
    ) {
        if (normalizedType != "series") {
            null
        } else {
            resolvedTargetEpisode?.episodeNumber
        }
    }

    val targetEpisodeIndex = remember(
        episodes,
        resolvedTargetEpisode?.streamId,
        targetEpisodeNumber
    ) {
        when {
            resolvedTargetEpisode?.streamId != null ->
                episodes.indexOfFirst {
                    it.streamId == resolvedTargetEpisode.streamId
                }

            targetEpisodeNumber != null ->
                episodes.indexOfFirst {
                    it.episodeNumber == targetEpisodeNumber
                }

            else -> -1
        }
    }

    LaunchedEffect(
        type,
        effectiveSeason,
        episodesLoading,
        targetEpisodeIndex,
        userManuallyChangedSeason
    ) {
        if (
            normalizedType == "series" &&
            effectiveSeason != null &&
            !episodesLoading &&
            targetEpisodeIndex >= 0 &&
            !userManuallyChangedSeason
        ) {
            episodesRowState.scrollToItem(targetEpisodeIndex)
        }
    }

    /** Index of [season] in the chip row, or -1 when the row has no such chip. */
    fun seasonChipIndex(season: Int?): Int = seasons.indexOf(season)

    /**
     * Whether the season chip at [index] is inside the row's visible window.
     * The row is lazy, so a chip that has not been laid out has no
     * FocusRequester attached yet: focusing it needs a scroll first, and a
     * scroll that is not needed must be skipped or the row jumps.
     */
    fun seasonChipIsLaidOut(index: Int): Boolean =
        index >= 0 &&
            seasonRowState.layoutInfo.visibleItemsInfo.any { it.index == index }

    /**
     * Scrolls the chip row until the chip at [index] is really on screen.
     *
     * Retried rather than fired once, because the row is a lazy row inside
     * the detail list: until it has measured, "scroll there" is a request
     * with nothing to apply to yet, and a single fire-and-forget call is
     * exactly how a viewer resuming season 13 of a 13-season show ended up
     * with a chip row still showing seasons 1-10. An already-visible chip
     * returns on the first pass, so a scroll that is not needed never
     * happens - which matters, because every D-pad step through the chips
     * moves the selection and would otherwise drag the row backwards.
     */
    suspend fun bringSeasonChipIntoView(index: Int) {
        if (index < 0) {
            return
        }

        repeat(25) {
            if (seasonChipIsLaidOut(index)) {
                return
            }
            // Wrapped because a row that has not attached yet has nothing to
            // apply the position to, which is exactly the case the retry is
            // here for.
            runCatching {
                seasonRowState.scrollToItem(index)
            }
            delay(40L)
        }
    }

    /**
     * Puts focus on one season chip, scrolling it in first and retrying until
     * the chip actually takes the focus.
     *
     * Both halves are needed:
     *
     *  - the scroll, because a lazy row only composes the chips it shows, so
     *    a chip that is off screen has no FocusRequester attached and
     *    requestFocus() silently no-ops; and
     *  - the retry, because the request can land in the same frame the chip
     *    is being composed - the first attempt is the one that races the
     *    layout it just asked for.
     *
     * Without this, UP from the episode rail fell through to the default
     * focus search, which picked whichever chip happened to sit above the
     * rail (season 2) and - since focusing a chip also SELECTS it (see
     * onSeasonFocused) - threw the viewer out of the season they were in.
     *
     * Answers whether the focus landed. The episode card's UP is consumed on
     * the answer: a press eaten by a chip that never took the focus is an UP
     * that visibly did nothing, so the card falls back to the chain's own UP
     * when this says no.
     */
    suspend fun focusSeasonChip(season: Int): Boolean {
        val index = seasonChipIndex(season)
        if (index < 0) {
            return false
        }

        bringSeasonChipIntoView(index)

        // The landing is the request's OWN report, not "the call did not
        // throw": an unattached requester prints the focus warning and returns
        // false, so `isSuccess` was true for every call ever made and this
        // returned on the first attempt whether the chip was there or not (see
        // [focusChipColumn]).
        repeat(10) {
            val requester = seasonFocusRequesters[season]
            if (
                requester != null &&
                runCatching {
                    requester.requestFocus()
                }.getOrDefault(false)
            ) {
                return true
            }
            delay(40L)
        }

        return false
    }

    /**
     * Keeps the SELECTED season chip on screen.
     *
     * The chip row is lazy and used to be left wherever it started, so a
     * viewer resuming season 13 of a 13-season show saw seasons 1-10 with no
     * sign of where they were. Skipped when the chip is already visible, so
     * walking the row never snaps it back to its start edge.
     */
    LaunchedEffect(effectiveSeason, seasons) {
        bringSeasonChipIntoView(seasonChipIndex(effectiveSeason))
    }

    /**
     * Puts focus on the episode card the page is aimed at - or, with no target
     * episode, on the season chip the viewer is inside - and reports whether
     * the focus actually landed.
     *
     * Both branches keep the same two-step shape [focusSeasonChip] was fixed
     * to use: the request is retried while the lazy rail composes the card, and
     * the answer is the request's OWN report rather than "the call did not
     * throw". The old code fired the request inside `scope.launch` and returned
     * true regardless, so an off-screen card whose requester was not attached
     * yet swallowed the press: the key was consumed with the focus still on the
     * row it came from, and the caller's `|| focusTopOfPage()` fallback could
     * never run because the left side was always true.
     */
    suspend fun focusSeasonOrEpisode(): Boolean {
        val season = effectiveSeason
        val episodeNum = targetEpisodeNumber

        val episodeRequester =
            if (season != null && episodeNum != null) {
                episodeFocusRequesters[season to episodeNum]
            } else {
                null
            }

        if (episodeRequester != null) {
            // The episode row is lazy, so an off-screen target card isn't
            // composed yet and requestFocus() would silently no-op. Scroll it
            // into view first, then retry the focus while the row composes.
            val targetIndex = targetEpisodeIndex
            if (targetIndex >= 0) {
                runCatching { episodesRowState.scrollToItem(targetIndex) }
                delay(90)
            }

            var attempts = 0
            while (attempts < RETURN_FOCUS_STEPS) {
                if (
                    runCatching {
                        episodeRequester.requestFocus()
                    }.getOrDefault(false)
                ) {
                    return true
                }
                delay(RETURN_FOCUS_DELAY_MS)
                attempts++
            }
            return false
        }

        val seasonRequester = season?.let {
            seasonFocusRequesters[it]
        }

        if (seasonRequester != null) {
            var attempts = 0
            while (attempts < RETURN_FOCUS_STEPS) {
                if (
                    runCatching {
                        seasonRequester.requestFocus()
                    }.getOrDefault(false)
                ) {
                    return true
                }
                delay(RETURN_FOCUS_DELAY_MS)
                attempts++
            }
        }

        return false
    }

    LaunchedEffect(
        effectiveSeason,
        episodesLoading,
        episodes,
        episodeTransitionState.edge
    ) {
        val edge = episodeTransitionState.edge ?: return@LaunchedEffect
        if (episodesLoading || episodes.isEmpty()) return@LaunchedEffect

        val season = effectiveSeason ?: return@LaunchedEffect
        val targetIndex =
            if (edge == EpisodeFocusEdge.START) 0 else episodes.lastIndex

        val targetEpisode =
            episodes.getOrNull(targetIndex) ?: return@LaunchedEffect

        episodesRowState.scrollToItem(targetIndex)
        delay(90)

        // The landing is verified rather than assumed: the card was just
        // disposed by the season swap and is being recomposed as this runs, so
        // the one request this used to make could land on nothing - and the
        // sink was then disarmed anyway, which left the focus with nowhere to
        // be. [landFocusOn] retries across frames and reads the request's own
        // answer (see [focusChipColumn] for why that answer is the only one
        // worth trusting).
        landFocusOn(
            episodeFocusRequesters[
                season to targetEpisode.episodeNumber
            ]
        )

        // Focus is off the sink now — disarm it so it never captures
        // focus during ordinary spatial navigation.
        seasonSwapSinkArmed = false

        clearEpisodeTransitionState()
    }

    /**
     * The detail-list item key a chain row is, or null for the two rows that
     * are not a single rail: the HEADER, which lives above the list rather than
     * in it, and the EPISODES section, which is a season chip row and an episode
     * card row at once.
     *
     * The only place the chain's names ([DetailRow]) meet the list's keys: the
     * chain decides WHERE a press goes, and this decides which rail that is.
     */
    fun detailRowKey(row: DetailRow): String? = when (row) {
        DetailRow.HEADER -> null
        DetailRow.GENRE -> GENRE_ROW_KEY
        DetailRow.KEYWORDS -> KEYWORDS_ROW_KEY
        DetailRow.EPISODES -> null
        DetailRow.PEOPLE -> PEOPLE_ROW_KEY
        DetailRow.NETWORK -> NETWORK_ROW_KEY
        DetailRow.PRODUCTION -> PRODUCTION_ROW_KEY
        DetailRow.REVIEWS -> REVIEWS_ROW_KEY
        DetailRow.COLLECTION -> COLLECTION_ROW_KEY
        DetailRow.MEDIA -> MEDIA_ROW_KEY
        DetailRow.MORE_LIKE_THIS -> RECS_ROW_KEY
    }

    /**
     * Hands the focus to the row [moveFocusVertical] picked, putting it back on
     * screen first when the scroll has disposed it, and never leaving the press
     * without a landing.
     *
     * [fromColumn] is the column the press came from, which every chip rail
     * keeps ([focusChipColumn]); [from] is the row it came from, which is the
     * closest scroll hint there is for a rail that is off screen
     * ([revealAndFocusChipColumn]).
     *
     * The three kinds of target, and why none of them can strand:
     *
     *  - HEADER is the fixed play row above the list, so it needs no scroll; it
     *    is the chain's own fallback ([focusTopOfPage]).
     *  - EPISODES is two rails, not one, and its own rule already knows which of
     *    them a press belongs on - the target episode, or the chip for the
     *    season on screen ([focusSeasonOrEpisode]).
     *  - a card rail - the chip rails and the poster rails alike - keeps its
     *    column, and is revealed when it exists but the scroll disposed it.
     *
     * [direction] and [presence] are the press's own, and they are what lets the
     * last arm walk on: a rail that exists but will not take the focus hands the
     * press to the NEXT row that way rather than throwing the viewer back to the
     * top of the page, which is a move nobody asked for and, from the bottom of
     * a long page, a disorienting one. The walk is bounded by the chain's own
     * row list and ends in the top-of-page arm.
     *
     * A rail this page HAS but that did not answer is asked ONE more time a
     * frame later ([retryChipColumn]) before the walk moves on: the 2026-10-14
     * field race, where the row is on screen but its chips are still
     * registering, so the first ask fails and the press looked like it skipped
     * a row that was there. [presence] is what tells that apart from a row the
     * page does not have, which is stepped over with no wait.
     */
    suspend fun focusRow(
        target: DetailRow,
        fromColumn: Int,
        from: DetailRow,
        direction: DetailFocusDirection,
        presence: DetailRowPresence
    ): Boolean = when (target) {
        DetailRow.HEADER -> focusTopOfPage()
        DetailRow.EPISODES -> focusSeasonOrEpisode() || focusTopOfPage()
        DetailRow.GENRE,
        DetailRow.KEYWORDS,
        DetailRow.PEOPLE,
        DetailRow.NETWORK,
        DetailRow.PRODUCTION,
        DetailRow.REVIEWS,
        DetailRow.COLLECTION,
        DetailRow.MORE_LIKE_THIS,
        DetailRow.MEDIA -> {
            val targetKey = detailRowKey(target)
            val anchorKey = detailRowKey(from)
            // Does this page actually have the row the press aims at? A row
            // that is not there is stepped over by the chain already, but the
            // answer also decides whether an unanswered ask is a race worth a
            // retry or a row that simply is not on this page.
            val present = DetailFocusChain.exists(target, presence)
            when {
                targetKey == null -> focusTopOfPage()
                focusChipColumn(targetKey, fromColumn) -> true
                // Present but not ready: the rail is on screen but its chips
                // have not finished registering, so the first ask failed. One
                // more ask a frame later, then the reveal, then the walk - a
                // row that is there is never mistaken for one that is not.
                present && retryChipColumn(targetKey, fromColumn) -> true
                // The reveal needs no anchor to be worth trying. [from] is the
                // hint it would rather have, but the episode section and the
                // header row are not single list items and have no key to give
                // (see [detailRowKey]) - and gating the arm on one is what made
                // DOWN off an episode card skip the people rail below it
                // whenever that rail was still under the fold, which is exactly
                // the disposal the reveal exists to undo (see
                // [revealAndFocusChipColumn]). With no anchor the reveal starts
                // its search at the top of the list, as the Media row's own
                // strip already did.
                revealAndFocusChipColumn(targetKey, anchorKey, fromColumn) -> true
                else -> {
                    // The row is not there, or it is and still would not take
                    // the focus even after the retry. Field report: GENRE ->
                    // KEYWORDS looked like a skip, and static reading cannot
                    // tell a row that is not there from one that is there and
                    // cannot answer. So the state is logged here, where the
                    // failure is, naming whether a retry was spent.
                    if (target == DetailRow.KEYWORDS) {
                        Log.w(
                            TAG,
                            "focusRow(KEYWORDS) failed" +
                                (if (present) " on retry" else "") +
                                ": hasKeywordsRow=${presence.hasKeywordsRow}, " +
                                "column=$fromColumn, from=$from, direction=$direction, " +
                                "visibleKeys=" +
                                detailListState.layoutInfo.visibleItemsInfo.map { it.key }
                        )
                    }
                    // Then the NEXT row that way, before giving up on the move
                    // altogether: a rail that will not take the focus must not
                    // teleport the viewer to the top of the page. The walk can
                    // only get shorter - each step moves strictly the way the
                    // press was going - so this recursion is bounded by the
                    // page's row count and ends in the arm below.
                    val nextTarget =
                        DetailFocusChain.nextRow(target, direction, presence)
                    if (nextTarget == null) {
                        Log.w(TAG, "focusRow: could not focus $target or any row beyond it")
                        true
                    } else {
                        focusRow(nextTarget, fromColumn, target, direction, presence)
                    }
                }
            }
        }
    }

    /**
     * UP out of a Media card: back to the row's tab strip, and the landing
     * [moveFocusVertical] uses when a press comes DOWN into the row from above.
     *
     * The strip and the cards are SEPARATE LazyColumn items, and scrolling the
     * card row in can push the strip off screen; a disposed strip's
     * FocusRequester is unattached, so `requestFocus()` prints the focus warning
     * and returns false rather than throwing. That false used to be swallowed,
     * which left the press consumed with the focus still on the card - the
     * "sometimes UP does nothing" the viewer saw. The report is read here
     * instead: the strip is scrolled back in, anchored on its OWN index while
     * it has one and on the row the press came from otherwise, and the focus is
     * retried while it recomposes, exactly as [revealAndFocusChipColumn] does
     * for the rails.
     *
     * Answers whether the focus landed, so the caller is never left guessing.
     *
     * [from] is the row the press came from, and it is the scroll hint when the
     * strip is not on screen at all (see the hint below).
     */
    suspend fun focusMediaStrip(from: DetailRow): Boolean {
        if (
            runCatching { mediaStripFocusRequester.requestFocus() }.getOrDefault(false)
        ) {
            return true
        }

        // The hint is, in order: the strip's OWN index while it is still among
        // the laid-out items, then the row the press came from - a rail's
        // neighbour is the closest thing to its index there is - and only then
        // the top. Anchoring on the CARDS unconditionally (as this did) sent a
        // press that came from a rail ABOVE the row past the strip it was
        // looking for; a missing anchor's 0 walked the page back to its top.
        val hint = detailListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == MEDIA_STRIP_KEY }?.index
            ?: detailListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == detailRowKey(from) }?.index
            ?: 0
        detailListState.revealRail(MEDIA_STRIP_KEY, hint)

        var attempts = 0
        while (attempts < RETURN_FOCUS_STEPS) {
            val composed =
                detailListState.layoutInfo.visibleItemsInfo.any { it.key == MEDIA_STRIP_KEY }
            if (
                composed &&
                runCatching { mediaStripFocusRequester.requestFocus() }.getOrDefault(false)
            ) {
                return true
            }
            delay(RETURN_FOCUS_DELAY_MS)
            attempts++
        }
        return false
    }

    /**
     * Moves the focus vertically out of [from] in [direction]: the page's ONE
     * vertical handoff.
     *
     * The row it lands on comes from one list, read the same way by every rail
     * ([DetailFocusChain]), so UP and DOWN cannot disagree about what is above
     * and below. A row that exists but the scroll has disposed is revealed
     * rather than mistaken for a row that is not there, and a row that is not
     * there is stepped over. Null - nothing in that direction - is only possible
     * going DOWN out of the bottom row, and answers false without moving: the
     * handler is what turns that into a consumed press that stays where it is
     * rather than a press handed to the default search (see
     * [detailRowVerticalFocus]).
     *
     * The one row that is not landed through [focusRow] is the Media row on the
     * way DOWN: its strip is what that press means (see below).
     */
    suspend fun moveFocusVertical(
        from: DetailRow,
        direction: DetailFocusDirection,
        fromColumn: Int,
        presence: DetailRowPresence
    ): Boolean {
        val target = DetailFocusChain.nextRow(from, direction, presence) ?: return false
        // The GENRE -> KEYWORDS handoff, logged when it does NOT happen. The
        // chain steps over a row the page does not have by design, so a title
        // with no keywords skips the row and looks exactly like a broken
        // handoff - and a failure INSIDE that row is logged where it fails (see
        // focusRow). Between the two, the log says which cause this is: no row
        // at all, the row there but not composed, or the focus landing going
        // elsewhere.
        if (
            from == DetailRow.GENRE &&
            direction == DetailFocusDirection.DOWN &&
            target != DetailRow.KEYWORDS
        ) {
            Log.w(
                TAG,
                "GENRE DOWN stepped over KEYWORDS: hasKeywordsRow=" +
                    "${presence.hasKeywordsRow}, target=$target, visibleKeys=" +
                    detailListState.layoutInfo.visibleItemsInfo.map { it.key }
            )
        }
        // DOWN into the Media row lands on its tab strip, never on a card: the
        // strip is the row's selector, and a press that skipped it left the
        // tab-switcher behind an UP the viewer has no reason to expect - the
        // reported "I land on the first trailer and have to press UP to reach
        // the tabs". The column the press arrived with is recorded either way,
        // so the strip can hand it back on the way up.
        if (target == DetailRow.MEDIA && direction == DetailFocusDirection.DOWN) {
            lastMediaCardColumn = fromColumn
            return focusMediaStrip(from)
        }
        return focusRow(target, fromColumn, from, direction, presence)
    }

    /**
     * The key handler EVERY row of the page uses for its vertical presses; no
     * rail carries its own UP/DOWN logic any more.
     *
     * A press the chain can act on is consumed and handed to
     * [moveFocusVertical], which owns the (asynchronous) move: consuming it is
     * what stops the default search - and the rail's own focusRestorer, which
     * remembers the item it was last left on - from moving the focus a second
     * time.
     *
     * A DOWN the chain has no row for - the bottom of the page, where the Media
     * row is the last row painted - is consumed too, and goes NOWHERE: the
     * focus stays on the card or the strip the viewer is holding. The Media row
     * is terminal, so there is nothing below it to aim at, and throwing the
     * focus back up to the header PLAY from the bottom of a long page reads as
     * the D-pad having lost the viewer's place. UP never reaches that arm -
     * [DetailRow.HEADER] always exists and sits at the top, so an upward walk
     * always ends on it.
     *
     * [directions] is the set of presses this row answers. The episodes row
     * takes only DOWN; its UP belongs to the episode card, which owns the season
     * chip (see [focusSeasonChip]).
     */
    fun detailRowVerticalFocus(
        row: DetailRow,
        column: Int,
        presence: DetailRowPresence,
        directions: Set<DetailFocusDirection> =
            setOf(DetailFocusDirection.UP, DetailFocusDirection.DOWN)
    ): (KeyEvent) -> Boolean = { keyEvent ->
        val direction = when {
            keyEvent.type != KeyEventType.KeyDown -> null
            keyEvent.key == Key.DirectionUp -> DetailFocusDirection.UP
            keyEvent.key == Key.DirectionDown -> DetailFocusDirection.DOWN
            else -> null
        }
        if (direction == null || direction !in directions) {
            false
        } else if (DetailFocusChain.nextRow(row, direction, presence) == null) {
            // The bottom of the chain, and the press stops HERE: the Media row
            // is the last row the page paints, so a DOWN off its cards is the
            // end of the page and the focus stays on the card the viewer is
            // holding. Consumed like every other press this handler owns, so
            // the key is never handed to the default search - and not moved
            // either: being thrown back to the header PLAY from the bottom of
            // the page is the disorientation this arm used to cause.
            true
        } else {
            startFocusMove { moveFocusVertical(row, direction, column, presence) }
            true
        }
    }

    /**
     * DOWN out of the Media row's tab strip: the card of the page that is
     * active in the column the viewer last held ([lastMediaCardColumn]), not
     * column 0 - a viewer who stepped UP out of the fifth card expects the
     * DOWN back to return to it.
     *
     * Asked for directly first, because the cards are laid out immediately
     * under the strip - and then through the same reveal every other rail uses,
     * for the case where the scroll has put the row out of composition (see
     * [revealAndFocusChipColumn]).
     *
     * The remembered column needs no bounds check of its own: the column rule
     * clamps to the rail's own last card (a page with fewer cards than the one
     * the column was remembered on), and the second ask covers the other way a
     * direct request can miss - the remembered slot being the one card the
     * scroll has not composed yet, while the first card is there.
     */
    suspend fun focusFirstMediaCard() {
        if (focusChipColumn(MEDIA_ROW_KEY, lastMediaCardColumn)) return
        if (lastMediaCardColumn != 0 && focusChipColumn(MEDIA_ROW_KEY, 0)) return
        revealAndFocusChipColumn(MEDIA_ROW_KEY, MEDIA_ROW_KEY, lastMediaCardColumn)
    }

    /**
     * The Media row's tab strip: LEFT/RIGHT walk the pages of the row, DOWN
     * steps into the cards of the page that is now active, and UP is the
     * chain's press out of the row - the review rail above it, or More Like
     * This on a title with no reviews.
     *
     * A tab strip is a row inside a row, which is the one shape the page's
     * chain does not model ([DetailRow.MEDIA] is a single row as far as the
     * handoff between rows goes), so the rails inside it are traded between
     * here rather than by [DetailFocusChain]. That is also why the strip reads
     * the chain for UP alone: a DOWN belongs to the cards under it, not to the
     * row below.
     *
     * Every press is consumed, LEFT/RIGHT included: there is no second tab node
     * for the default search to find, and a LEFT that fell through to it would
     * walk the focus out of the row entirely.
     */
    fun mediaStripFocus(
        tabs: List<MediaTab>,
        activeIndex: () -> Int,
        onActiveIndex: (Int) -> Unit,
        onFocusContent: () -> Unit,
        presence: DetailRowPresence
    ): (KeyEvent) -> Boolean = { keyEvent ->
        val step = when (keyEvent.key) {
            Key.DirectionLeft -> -1
            Key.DirectionRight -> 1
            else -> 0
        }
        when {
            keyEvent.type != KeyEventType.KeyDown -> false
            step != 0 -> {
                onActiveIndex(DetailMediaRow.switched(activeIndex(), step, tabs.size))
                true
            }
            keyEvent.key == Key.DirectionDown -> {
                onFocusContent()
                true
            }
            else -> detailRowVerticalFocus(
                DetailRow.MEDIA,
                // The column the section remembers, not 0: coming DOWN out of
                // the More Like This rail, this press has to be able to give
                // back the column it arrived on.
                lastMediaCardColumn,
                presence,
                setOf(DetailFocusDirection.UP)
            )(keyEvent)
        }
    }

    /**
     * A card of the Media row: UP returns to the row's tab strip, and DOWN is
     * the chain's press out of the row - the bottom of the page, which the
     * handler lands on the header PLAY (see [detailRowVerticalFocus]).
     * LEFT/RIGHT are the lazy row's own and are left alone.
     *
     * UP belongs to the row rather than to the chain because it is about the
     * strip, not about the row above: a viewer on a trailer who presses UP means
     * the tabs, and the collection rail is one press further out (see
     * [mediaStripFocus]). DOWN hands over the card's COLUMN like every other
     * card rail here, so a return press lands on the card above the one it was
     * aimed at rather than on whichever card the rail was last left on.
     */
    fun mediaCardFocus(
        index: Int,
        onFocusStrip: () -> Unit,
        presence: DetailRowPresence
    ): (KeyEvent) -> Boolean = { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp) {
            // The card the viewer is leaving IS the column the strip owes them
            // on the way back down (see [lastMediaCardColumn]). Recorded here
            // as well as when a card takes focus, so the round trip through
            // the strip is exact for a card that got the focus without a focus
            // event of its own.
            lastMediaCardColumn = index
            onFocusStrip()
            true
        } else {
            detailRowVerticalFocus(
                DetailRow.MEDIA,
                index,
                presence,
                setOf(DetailFocusDirection.DOWN)
            )(keyEvent)
        }
    }

    /**
     * Opens a title's trailer in the fullscreen player.
     *
     * The resolve (InnerTube → NewPipe → Piped, with its caches) stays in
     * `data.youtube.TrailerPlayerLauncher`; the INTENT that starts the player
     * lives here, in the UI layer that owns it. Building it used to happen
     * inside the data object, which is what made `data/` import
     * `ui.player.NativePlayerActivity` - a data-layer type reaching into an
     * Activity it cannot launch without a Context anyway.
     */
    /**
     * The player launch for a resolved trailer source.
     *
     * `stream_headers` carries the User-Agent of the YouTube client the
     * googlevideo URL was signed for: that host only serves a signed URL to
     * that UA, and the fullscreen player applies the header over its own
     * default. Null for NewPipe/Piped sources.
     */
    // The intent shape now lives beside its phone caller (see
    // DetailTrailerLaunch), so the two form factors cannot file a trailer two
    // different ways.
    fun trailerPlayerIntent(
        context: Context,
        source: PlayableSource,
        videoId: String
    ): Intent =
        DetailTrailerLaunch.intent(context, source, videoId)

    /**
     * Plays ONE of the title's videos, which is what the [DetailRow.MEDIA]
     * row's cards ask for: a card plays the video it shows, and the header
     * button plays its own pick through the same call (below).
     */
    fun playTrailer(context: Context, video: TmdbVideo?) {
        val key = video?.key?.takeIf { it.isNotBlank() } ?: return

        scope.launch {
            val source =
                TrailerPlayerLauncher.resolvePlayableUrl(
                    "https://www.youtube.com/watch?v=$key"
                ).getOrElse { error ->
                    Log.e(
                        TAG,
                        "Failed to resolve playable trailer URL",
                        error
                    )
                    return@launch
                }

            context.startActivity(trailerPlayerIntent(context, source, key))
        }
    }

    /** The header button's trailer: the one pick its label was built from. */
    fun playTrailer(context: Context) {
        playTrailer(context, trailerVideo)
    }

    val loadedMeta = meta
    when {
        // A Continue Watching / Up Next deep link opens this screen only to
        // hand the player the title's backdrop/overview/cast - it auto-plays
        // the moment metadata lands, so the detail page itself is never
        // actually seen. A centered spinner on black for that whole load
        // reads as a stalled playback, and the spinner means "the video is
        // buffering" everywhere else in the app, so this open shows the same
        // loading splash the player uses instead (backdrop + pulsing
        // clearlogo, or the name when there is no logo art). An ordinary
        // detail open keeps the plain spinner.
        isLoading && initialTarget != null -> {
            // The poster is deliberately NOT used as a backdrop fallback: a
            // portrait stretched full-screen reads as a zoomed, wrong
            // backdrop. No widescreen art keeps the dark splash + pulsing
            // logo, exactly like the player's own first load.
            AutoPlayLoadSplash(
                backdropUrl = initialBackdrop,
                clearLogoUrl = initialClearLogo,
                title = initialTarget.displayName.ifBlank { id },
                // The splash is the cover for this title's playback, so it
                // honors the still the viewer chose for it (see ImageOverrides).
                tmdbId = overrideTmdbId
            )
        }

        isLoading -> {
            // The app's one status card (spinner + word), the same shape the
            // browse screens show while they load. This page had its own
            // hand-rolled centered spinner, one screen away from the card.
            KBStatusMessage(loading = true, message = KB_STATUS_LOADING)
        }

        error != null -> {
            // Was a bare Text("Error: ...") in a Box with no color at all, so
            // it inherited the tv theme's default bright content color and
            // sat against the left edge while every sibling screen showed a
            // centered card.
            //
            // The retry is the reason DetailViewModel.isFreshDetailLoad treats
            // a failed load as never-fresh ("the error screen has to be able to
            // retry"): without it a load that died was a dead end you had to
            // back out of and re-open -- and on a Continue Watching deep link,
            // back out of entirely. Bumping the tick re-runs the two load
            // effects above, which is exactly what re-opening the screen does.
            KBStatusMessage(
                message = "Error: $error",
                onRetry = { detailRetryTick++ }
            )
        }

        loadedMeta != null -> {
            val m = loadedMeta

            // The name this screen prints and copies into every play target:
            // the meta's own name, then TMDB's, with any raw internal id
            // rejected at each rung (see upNextDisplayTitleOrNull). This
            // screen is the producer the player's Up Next panel reads its
            // show title from, so an id that got past here used to surface
            // there - and ride every autoplay after it.
            val displayName = remember(m, tmdbDetail) {
                detailDisplayName(
                    metaName = m.name,
                    tmdbName = tmdbDetail?.name,
                    tmdbTitle = tmdbDetail?.title,
                    hasArtwork = !m.poster.isNullOrBlank()
                )
            }

            val keywords = remember(tmdbDetail) {
                tmdbDetail?.keywords?.list().orEmpty()
            }

            // The rows this page actually paints, in one place: the chain
            // ([DetailFocusChain]) reads these and nothing else, and every field
            // is the same expression that renders its row below - so "the row
            // exists" and "the row is on screen" cannot drift apart.
            val networks = tmdbDetail?.networks.orEmpty()
            val companies = tmdbDetail?.productionCompanies.orEmpty()
            // The genre chips live in the fixed header above the list and the
            // review rail is the section between More Like This and the Media
            // row; both are read here - with the same expression that renders
            // them - so their presence in the chain means "on screen", exactly
            // like every other row's.
            val tmdbGenres = tmdbDetail?.genres.orEmpty()
            val reviews = allReviews.ifEmpty { tmdbDetail?.reviews?.results.orEmpty() }
            val hasProductionRow = companies.isNotEmpty()
            val hasNetworkRow = normalizedType == "series" && networks.isNotEmpty()
            val hasCollectionRow = collectionParts.isNotEmpty()
            val hasMoreLikeThisRow = recommendations.isNotEmpty()
            // The Media row is painted LAST, under More Like This (see the
            // LazyColumn below and [DetailRow.MEDIA]).
            val hasMediaRow = mediaTabs.isNotEmpty()
            val detailRowPresence = DetailRowPresence(
                hasGenreRow = tmdbGenres.isNotEmpty(),
                hasKeywordsRow = keywords.isNotEmpty(),
                hasEpisodesRow = normalizedType == "series" && seasons.isNotEmpty(),
                hasPeopleRow = hasPeopleRow,
                hasNetworkRow = hasNetworkRow,
                hasProductionRow = hasProductionRow,
                hasReviewsRow = reviews.isNotEmpty(),
                hasCollectionRow = hasCollectionRow,
                hasMoreLikeThisRow = hasMoreLikeThisRow,
                hasMediaRow = hasMediaRow
            )

            // The title's art, in two shapes, because two consumers want
            // different things from it.
            //
            // [backdropArtUrl] is WIDESCREEN art only: TMDB's still at full
            // resolution (the hero is the whole screen, so its still is pulled
            // at full size), else the add-on's own backdrop - fanart etc. from
            // the meta provider, and when no add-on answered, the fallback meta
            // was built from TMDB itself, so this still lands on TMDB art. This
            // is the copy handed on to the player's loading splash, and the
            // only value the COVER splash may draw: the poster rungs below are
            // a PORTRAIT, and the splash fills the whole frame, so a poster
            // there is the zoomed, wrong still this screen's own loading splash
            // already refuses to show.
            //
            // [backdropUrl] is that plus the poster rungs, for the two surfaces
            // that only need SOMETHING behind the title and have no widescreen
            // art to fall back on: this screen's hero, and the hand-off to the
            // player.
            val backdropArtUrl = remember(tmdbDetail, m) {
                tmdbDetail?.backdropPath?.let {
                    TmdbRepository.fullResBackdropUrl(TmdbRepository.BACKDROP_BASE + it)
                }
                    ?: m.background
            }

            val backdropUrl = remember(backdropArtUrl, m, initialPoster) {
                backdropArtUrl
                    ?: m.poster
                    ?: initialPoster
            }

            // Hand the resolved still up as soon as it exists, exactly as the
            // clearlogo above is handed up. Without it the cover splash has no
            // way to learn this value: MainActivity paints it from the deep
            // link's own art, which for a Continue Watching / Up Next card is
            // the history row's - usually empty - backdrop.
            LaunchedEffect(backdropArtUrl) {
                backdropArtUrl?.takeIf { it.isNotBlank() }?.let(onBackdropResolved)
            }

            // What the HERO draws: the still the viewer chose for this page when
            // they chose one, and [backdropUrl] otherwise (see ImageOverrideArt).
            // Only the hero: a still picked for this page is not a choice about
            // the streams screen or the player's loading splash, which have
            // their own - and those keep the art the route carries.
            val heroBackdropUrl = remember(backdropUrl, overridePaths, overrideTmdbId) {
                imageOverrideBackdropUrl(
                    paths = overridePaths,
                    tmdbId = overrideTmdbId,
                    screen = ImageOverrides.SCREEN_DETAIL,
                    fallbackUrl = backdropUrl
                )
            }

            val context = LocalContext.current

            val playLabel: String

            // Builds this screen's play target for a root id the CALLER
            // supplies: the screen's own `streamRootId` for the target painted
            // here, or the root a play press waited for - which is a different
            // id while the TMDB external-ids lookup is still in flight, and the
            // one that has to name the request (see
            // DetailViewModel.awaitStreamRootId).
            val playTargetFor: (String) -> StreamsTarget
            val playTarget: StreamsTarget

            // Two ways a viewer can ask to start over, and both win over the
            // saved progress the target would otherwise resume from: Home's
            // Continue Watching row opens this screen with a target that
            // already carries the flag, while a poster menu's "Play from
            // Beginning" on any other screen can only raise a one-shot request
            // and navigate here (see PlayFromBeginningSelection). Read once, as
            // this title's screen appears; declared before the auto-play effect
            // below so it has landed by the time that effect reads it.
            var startOver by remember(type, id) {
                mutableStateOf(false)
            }
            LaunchedEffect(type, id) {
                startOver = PlayFromBeginningSelection.consume()
            }

            // Long press on the hero Play button opens this menu instead of
            // jumping straight into the picker. The two rows are the same two
            // a poster's long press offers (see PosterContextMenu's derived
            // rows), because the button's long press means the same thing as
            // every other long press in the app; the difference is that this
            // screen already holds the target, so each row drives the press
            // itself rather than navigating somewhere that would.
            var playButtonMenu by remember {
                mutableStateOf(false)
            }

            // Closing this menu used to clear the flag and nothing else: the
            // menu is drawn over the page in its own focus group (see
            // PosterContextMenu), so it took focus with it and the D-pad
            // restarted from the top of the screen. Focus goes back to the
            // button that raised it, on the action rows and on Back alike -
            // the same hand-back the poster, season and episode menus already
            // do (dismissPosterMenu and friends).
            fun dismissPlayButtonMenu() {
                playButtonMenu = false
                handFocusBack("play button menu", playButtonFocusRequester)
            }

            val wantsBeginning =
                initialTarget?.startFromBeginning == true || startOver

            if (type == "movie") {
                val hasResume = resumeInfo?.positionMs?.let { it > 0 } == true

                playLabel =
                    // Asking to play from the beginning means the button will
                    // NOT resume, so it must not claim to: the label follows
                    // what the press actually does.
                    if (hasResume && !wantsBeginning) "RESUME" else "PLAY"

                playTargetFor = { streamRootId ->
                    // Prefer the actual recorded duration (accurate for the
                    // file that was watched); fall back to TMDB's runtime.
                    val recordedMinutes =
                        resumeInfo?.durationMs
                            ?.div(60_000L)
                            ?.toInt()
                            ?.takeIf { it > 0 }
                    StreamsTarget(
                        contentType = "movie",
                        streamId = streamRootId,
                        title = displayName,
                        displayName = displayName,
                        season = null,
                        episode = null,
                        resumePositionMs =
                            if (wantsBeginning) 0L else (resumeInfo?.positionMs ?: 0L),
                        startFromBeginning = wantsBeginning,
                        runtimeMinutes =
                            recordedMinutes
                                ?: tmdbDetail?.runtime
                                    ?.takeIf { it > 0 }
                    )
                }
                playTarget = remember(
                    resumeInfo,
                    id,
                    streamRootId,
                    displayName,
                    tmdbDetail?.runtime,
                    wantsBeginning
                ) {
                    playTargetFor(streamRootId)
                }
            } else {
                val targetSeason =
                    effectiveSeason
                        ?: initialTarget?.season?.takeIf {
                            it in seasons
                        }
                        ?: seasons.firstOrNull()
                        ?: 1

                val targetEpisode =
                    resolvedTargetEpisode?.episodeNumber
                        ?: initialTarget?.episode
                        ?: resumeEpisode
                        ?: 1

                val isResumingHere =
                    hasSeriesResume &&
                        targetSeason == resumeSeason &&
                        targetEpisode == resumeEpisode

                val isDeepLinkedHere =
                    initialTarget?.season == effectiveSeason &&
                        initialTarget?.episode != null &&
                        !isResumingHere

                // The last resort - no resolved row, no resume, no incoming
                // target - names the episode from its TMDB number, so it is
                // mapped to the FILE the addons resolve: on a show whose files
                // hold two segments each, the TMDB number is not a file (see
                // EpisodeScheme). Read once per target rather than per
                // recomposition, because the store is a prefs lookup.
                val fallbackStreamId = remember(
                    context,
                    id,
                    streamRootId,
                    targetSeason,
                    targetEpisode,
                    tmdbDetail?.id
                ) {
                    fileEpisodeStreamId(
                        context = context,
                        rootId = streamRootId,
                        tmdbId = tmdbDetail?.id,
                        season = targetSeason,
                        tmdbEpisode = targetEpisode
                    )
                }

                // The same file under a GIVEN root id: what a play press that
                // waited for a root this screen did not hold yet re-derives
                // (see DetailViewModel.awaitStreamRootId), and where a stored
                // id lands when it was written under a different root (see
                // filedUnder below). The resolved row is the one rung already
                // built from the root the season listing verified (see
                // TmdbRepository.getSeasonEpisodes).
                fun fallbackIdFor(rootId: String): String =
                    if (rootId == streamRootId) {
                        fallbackStreamId
                    } else {
                        fileEpisodeStreamId(
                            context = context,
                            rootId = rootId,
                            tmdbId = tmdbDetail?.id,
                            season = targetSeason,
                            tmdbEpisode = targetEpisode
                        )
                    }

                // An id this episode is ALREADY filed under - by the history
                // row being resumed, or by the deep link that opened this
                // screen - kept only while it was written under the root this
                // request is being named by. A row filed before the root was
                // verified carries the CATALOG id instead of TMDB's (see
                // TmdbRepository.streamRootIdFor), and playing it as-is is how
                // every episode of one show asked for another title's streams;
                // dropping such an id hands the target to the fallback above,
                // which names the same episode under the root the press waited
                // for. A Continue Watching row is exactly this case, and it is
                // the reason the rail cannot be trusted to know the root.
                fun filedUnder(streamId: String?, rootId: String): String? =
                    streamId?.takeIf { EpisodeMatch.seriesRootFrom(it) == rootId }

                playLabel = when {
                    isDeepLinkedHere ->
                        "PLAY S${targetSeason} E${targetEpisode}"

                    resolvedTargetEpisode != null ->
                        // The ViewModel labels a paused episode "Resume S..E..",
                        // but a Play-from-Beginning request means this press
                        // will not resume, so the label follows the press.
                        if (wantsBeginning && vmPlayButtonText.startsWith("Resume")) {
                            "Play" + vmPlayButtonText.removePrefix("Resume")
                        } else {
                            vmPlayButtonText
                        }

                    else ->
                        "PLAY"
                }

                // Only trust the resolved episode's name when it actually is
                // the episode this target plays (the resolution can fall back
                // to a different row, e.g. the first episode of the season).
                // When the resolved row doesn't line up but we're resuming the
                // target episode, fall back to the episode name stored in the
                // watch-history entry.
                val playTargetEpisodeName =
                    resolvedTargetEpisode
                        ?.takeIf {
                            it.episodeNumber == targetEpisode
                        }
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: resumeInfo?.episodeTitle
                            ?.takeIf { isResumingHere }
                            ?.takeIf { it.isNotBlank() }

                // Same gate for the runtime: the resolved row's runtime only
                // counts when it is the target episode; when resuming, prefer
                // the actual recorded duration of the watched file.
                val playTargetRuntimeMinutes: Int? =
                    if (isResumingHere) {
                        resumeInfo?.durationMs
                            ?.div(60_000L)
                            ?.toInt()
                            ?.takeIf { it > 0 }
                    } else {
                        resolvedTargetEpisode
                            ?.takeIf {
                                it.episodeNumber == targetEpisode
                            }
                            ?.runtimeMinutes
                            ?.takeIf { it > 0 }
                    }

                playTargetFor = { streamRootId ->
                    val targetStreamId =
                        resolvedTargetEpisode?.streamId
                            ?: resumeStreamId
                                ?.takeIf { isResumingHere }
                                ?.let { filedUnder(it, streamRootId) }
                            ?: initialTarget?.streamId
                                ?.takeIf {
                                    initialTarget.season == targetSeason &&
                                        initialTarget.episode == targetEpisode
                                }
                                ?.let { filedUnder(it, streamRootId) }
                            ?: fallbackIdFor(streamRootId)
                    val episodeSuffix =
                        playTargetEpisodeName?.let {
                            " • $it"
                        } ?: ""
                    StreamsTarget(
                        contentType = "series",
                        streamId = targetStreamId,
                        title =
                            "$displayName S$targetSeason E$targetEpisode$episodeSuffix",
                        displayName = displayName,
                        season = targetSeason,
                        episode = targetEpisode,
                        resumePositionMs =
                            if (wantsBeginning) {
                                0L
                            } else if (isResumingHere) {
                                resumeInfo?.positionMs ?: 0L
                            } else {
                                0L
                            },
                        startFromBeginning = wantsBeginning,
                        totalEpisodesInSeason = airedEpisodesInSeason,
                        runtimeMinutes = playTargetRuntimeMinutes
                    )
                }
                playTarget = remember(
                    id,
                    displayName,
                    targetSeason,
                    targetEpisode,
                    streamRootId,
                    tmdbDetail?.id,
                    resolvedTargetEpisode,
                    resumeStreamId,
                    initialTarget,
                    isResumingHere,
                    resumeInfo?.positionMs,
                    resumeInfo?.episodeTitle,
                    playTargetEpisodeName,
                    playTargetRuntimeMinutes,
                    wantsBeginning
                ) {
                    playTargetFor(streamRootId)
                }
            }

            // Resume affordance, read off the target the button will actually
            // play: anything above 0 means this press picks up where you left
            // off, so the button shows the play glyph plus how far in you
            // already are. A "from the beginning" target and a target that is
            // not the in-progress episode both carry position 0 — which is why
            // they correctly show no bar rather than a bar at 0%.
            val resumeProgress: Float? = run {
                val position = playTarget.resumePositionMs
                val duration = resumeInfo?.durationMs ?: 0L
                if (position > 0L && duration > 0L) {
                    (position.toFloat() / duration.toFloat())
                        .coerceIn(0.02f, 1f)
                } else {
                    null
                }
            }

            // The page's default focus. Two things this used to get wrong:
            //
            //  - `runCatching` around `requestFocus()` reports success for every
            //    call ever made, because an unattached node answers FALSE rather
            //    than throwing. [landFocusOn] reads that answer and retries across
            //    frames, which is the app's one rule for landing a focus.
            //  - It also ran on a DRILL-DOWN RETURN, where the return effect
            //    below takes the focus to the rail card a moment later: the play
            //    button visibly grabbed it first and was then yanked away. A page
            //    with a pending return record for this title belongs to the rail.
            //  - And the guard had a hole of its own: `returnTarget` is CLEARED
            //    by the return effect below (`returnTarget = null`, "read once,
            //    whatever came of it"), which restarts this effect with a null
            //    key - the guard then passed - so the play button grabbed the
            //    focus straight back off the rail card the return effect had just
            //    landed on. The field behaviour was a flash of the right card
            //    followed by Play taking it: still "loses the viewer's place".
            //    [restoredReturnFocus] is the latch that says the rail owns focus
            //    for this page (the resume-episode focus already honours it), so
            //    the default-focus effect has to honour it too.
            LaunchedEffect(returnTarget, restoredReturnFocus) {
                if (returnTarget != null || restoredReturnFocus) return@LaunchedEffect
                landFocusOn(playButtonFocusRequester)
            }

            // A rail on this page opened a drill-down: an actor page, a
            // network or production-company page, a genre or keyword chip, or a
            // title off the collection / More Like This rail. Coming back, the
            // page has
            // been rebuilt from scratch - its scroll offset, its focus and the
            // restorer that remembered which card was selected are all gone -
            // so the rail is put back on screen and the card that was pressed
            // takes the focus again. Reopening at the top with the play button
            // focused loses the viewer's place in the rail they were working
            // through.
            LaunchedEffect(returnTarget, isLoading, tmdbDetail, collection) {
                val target = returnTarget ?: return@LaunchedEffect
                if (isLoading || tmdbDetail == null) return@LaunchedEffect

                val rowState = when (target.rowKey) {
                    GENRE_ROW_KEY -> genreRowState
                    KEYWORDS_ROW_KEY -> keywordsRowState
                    PEOPLE_ROW_KEY -> peopleRowState
                    NETWORK_ROW_KEY -> networkRowState
                    PRODUCTION_ROW_KEY -> productionRowState
                    COLLECTION_ROW_KEY -> collectionRowState
                    RECS_ROW_KEY -> recsRowState
                    else -> return@LaunchedEffect
                }

                // The rail owns the focus from here, so the resume-episode
                // focus below must leave it alone (see the flag's declaration).
                restoredReturnFocus = true

                // The genre chips are the one rail in the fixed header above
                // the list, so there is nothing to scroll them into view.
                val onScreen =
                    target.rowKey == GENRE_ROW_KEY ||
                        detailListState.revealRail(target.rowKey, target.rowIndex)

                if (onScreen) {
                    if (target.itemIndex >= 0) {
                        runCatching { rowState.scrollToItem(target.itemIndex) }
                    }

                    // Two budgets, not one - the same rule the browse pages'
                    // own hand-back states ([browseReturnDecision]): a card that
                    // has not COMPOSED yet has no requester in this map, and
                    // that is an arrival, not a missed landing. Charging the
                    // landing budget for it spent every one of these attempts
                    // on the rail still building itself, so the loop gave up a
                    // frame or two before the card it was returning to
                    // existed - and the page reopened with the focus where the
                    // reopen had left it. The arrival is waited for on TIME
                    // instead, and only a request made against a card that has
                    // a focus node spends an attempt.
                    val waitStartMs = System.currentTimeMillis()
                    var attempts = 0
                    var focused = false
                    while (!focused && attempts < RETURN_FOCUS_STEPS) {
                        val requester = railFocusRequesters[target.targetKey]
                        if (requester == null) {
                            if (System.currentTimeMillis() - waitStartMs >
                                BROWSE_RETURN_WAIT_MS
                            ) {
                                break
                            }
                            delay(RETURN_FOCUS_DELAY_MS)
                            continue
                        }
                        // The landing is the request's own answer, not "the
                        // call did not throw": a requester whose card the rail
                        // has not composed returns false rather than throwing,
                        // so `isSuccess` ended this loop on its first pass and
                        // left the focus wherever the reopen had put it - the
                        // stranding this record exists to undo (see
                        // [focusChipColumn] and [BrowseReturnFocus]).
                        focused = runCatching { requester.requestFocus() }
                            .getOrDefault(false)
                        if (!focused) {
                            delay(RETURN_FOCUS_DELAY_MS)
                        }
                        attempts++
                    }
                }

                // Read once, whatever came of it: a later update to the detail
                // must not scroll the page out from under the viewer.
                returnTarget = null
            }

            // Continue Watching / Up Next deep-links auto-resume playback
            // once the metadata (and episodes, for series) are ready, so the
            // streams screen gets the rich backdrop/clearlogo/overview/cast
            // without the user having to press Play again.
            // A poster menu's "Play Manually" on any other screen can only get
            // here: the play target (a series' next episode in particular) is
            // resolved on this screen. Read once, as this title's screen
            // appears, and cleared - the request belongs to whatever title was
            // asked for, so one left behind by a poster whose navigation never
            // happened cannot turn a later, unrelated Play press into a
            // picker. Declared before the auto-play effect below so it has
            // landed by the time that effect reads it.
            var manualPick by remember(type, id) {
                mutableStateOf(false)
            }

            // Every press that has to end in the picker is RECORDED here, and
            // the effect below delivers it.
            //
            // The counter is what makes a press a press. The flags a press
            // sets (`manualPick`, `startOver`) are also what that effect keys
            // on, so a press whose flag is ALREADY in the state it wants - the
            // field report: arrive here from a poster menu's "Play Manually",
            // long-press Play, choose "Play Manually" again - changed no key
            // at all and did nothing, twice over: `manualPick` was already
            // true, and `autoPlayed = false` is not a key, so the effect never
            // ran. A recorded press always re-runs it.
            var playPress by remember(type, id) { mutableIntStateOf(0) }

            LaunchedEffect(type, id) {
                manualPick = ManualSourceSelection.consume()
                // A poster menu's marker is a press like any other: recorded,
                // so the delivery below is the one thing that acts on it and
                // the log names which press opened the picker.
                if (manualPick) playPress++
            }

            var autoPlayed by remember { mutableStateOf(false) }
            LaunchedEffect(
                initialTarget,
                playPress,
                manualPick,
                startOver,
                isLoading,
                meta,
                tmdbDetail,
                episodes,
                episodesLoading,
                resumeInfo
            ) {
                if (
                    autoPlayed ||
                    isLoading ||
                    meta == null
                ) return@LaunchedEffect
                if (
                    !manualPick &&
                    !startOver &&
                    initialTarget == null
                ) return@LaunchedEffect
                // The episode list: a press waits for it while it is LOADING -
                // the target is the season's next episode, and the list is what
                // names it - but never on an empty one. `playTargetFor` builds
                // exactly the target the Play button builds, which falls back to
                // the season's first file, so parking on "empty" is the second
                // way "Play Manually" did nothing while the button beside it
                // played: on a show whose listing had failed, no key was ever
                // going to change again. The deep link keeps waiting for the
                // list itself: it has to know the season's episode count before
                // it names a target.
                val waitingForEpisodes =
                    if (manualPick || startOver) {
                        episodesLoading
                    } else {
                        episodesLoading || episodes.isEmpty()
                    }
                if (
                    normalizedType == "series" &&
                    waitingForEpisodes
                ) return@LaunchedEffect
                // A manual pick and a "play from the beginning" are both
                // fresh starts: unlike the Continue Watching deep link they
                // have no progress to wait for, so a movie does not need
                // resume info to be ready before opening.
                if (
                    !manualPick &&
                    !startOver &&
                    normalizedType != "series" &&
                    resumeInfo == null
                ) return@LaunchedEffect

                // Wait for the resolved root id before the request is named.
                // This effect fires as soon as the target is ready, and for a
                // Continue Watching deep link that can be before the TMDB
                // external-ids lookup has answered - the target built then
                // names the catalog id, which is another title's for a show
                // whose catalog mapping is wrong (see
                // DetailViewModel.awaitStreamRootId).
                //
                // The wait comes BEFORE the delivered flag is latched, and
                // that order is the whole point: this is a suspension point,
                // and every one of this effect's other keys - resumeInfo, the
                // episode list, the meta - publishes during a load, which
                // CANCELS the run here and restarts it. Latching first meant
                // the restarted run answered "already auto-played" and the
                // press was gone for good: the picker never opened (a poster
                // menu's "Play Manually" left the viewer on this screen), and
                // no later press could revive it. Latched after the wait, a
                // cancelled run delivers nothing and the restart delivers the
                // press.
                val autoPlayTarget =
                    playTargetFor(viewModel.awaitStreamRootId(id))
                autoPlayed = true
                Log.d(
                    DETAIL_PLAY_TAG,
                    "play delivered: manual=$manualPick startOver=$startOver " +
                        "deepLink=${initialTarget != null} press=$playPress " +
                        "target=${autoPlayTarget.contentType}:${autoPlayTarget.streamId}"
                )
                if (manualPick) {
                    // Handed on rather than consumed here: MainActivity is what
                    // reads this, to open the picker for the target instead of
                    // auto-selecting a source. The target rides along so the
                    // picker it opens does not then auto-select the top result
                    // and jump to the player - with Auto-select on, "Play
                    // Manually" is the one action that must reach the picker,
                    // and this is the only route that knows the target before
                    // MainActivity builds the screen.
                    ManualSourceSelection.request(
                        "${autoPlayTarget.contentType}:${autoPlayTarget.streamId}"
                    )
                }
                onNavigateStreams(
                    autoPlayTarget,
                    id,
                    type,
                    m.poster,
                    backdropUrl,
                    clearLogoUrl,
                    resolvedTargetEpisode?.overview ?: m.description,
                    tmdbDetail?.credits?.cast.orEmpty()
                )
            }

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Zero-size focus sink for season swaps (see declaration).
                // A child, not a modifier on this Box: children of a Box
                // measure independently, so its 1px bounds constrain
                // nothing while giving the focus system a stable node.
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(seasonSwapFocusSink)
                        .focusProperties { canFocus = seasonSwapSinkArmed }
                        .focusable()
                )
                AsyncImage(
            model = remember(heroBackdropUrl) {
                ImageRequest.Builder(context)
                    .data(heroBackdropUrl)
                    .size(Size(1280, 720))
                    .allowHardware(true)
                    // The full-bleed hero is one of the few surfaces that
                    // keeps the fade: it is a single image the user is
                    // looking at, not a tile in a rail, so the handover
                    // reads as intentional rather than as flicker.
                    .crossfade(true)
                    .build()
            },
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        // Outermost so the recorded bounds are the
                        // full-bleed hero itself. Same key the poster
                        // declared, which is what pairs the two ends.
                        .heroSharedElement(type, id)
                        .fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    KBVoid.copy(alpha = 0.32f),
                                    KBVoid.copy(alpha = 0.80f)
                                )
                            )
                        )
                )

                // FIXED: the title/meta-line/genre header sits in the top
                // portion of the screen, where the vertical gradient above
                // is still mostly transparent -- so on bright backdrops the
                // dim KBTextLo meta line (rating/year/runtime/IMDb) was
                // barely legible. This adds a left-side horizontal scrim
                // behind the whole header block, independent of how bright
                // or dark the backdrop happens to be, without darkening the
                // rest of the image.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to KBVoid.copy(alpha = 0.55f),
                                    .40f to KBVoid.copy(alpha = 0.36f),
                                    .70f to KBVoid.copy(alpha = 0.10f),
                                    1f to Color.Transparent
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Spacer(
                        modifier = Modifier.height(20.dp)
                    )

                    Column(
                        modifier = Modifier.padding(
                            horizontal = 24.dp
                        )
                    ) {
                        if (!clearLogoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(clearLogoUrl)
                                    // The hero logo arrives with the backdrop
                                    // under it, so it fades in as one piece
                                    // with the art behind it rather than
                                    // popping onto a finished hero.
                                    .crossfade(true)
                                    .build(),
                                contentDescription = displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(68.dp)
                            )
                        } else {
                            Text(
                                displayName,
                                style = MaterialTheme.typography.headlineLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .padding(
                                start = 24.dp,
                                top = 8.dp,
                                bottom = 6.dp
                            )
                            .focusGroup()
                            .focusRestorer()
                            .scrollToTopOnFocus(detailListState, scope) {
                                // Not while a menu's hand-back is in flight: the
                                // focus the header sees then is the framework's
                                // temporary pick, not the viewer's destination
                                // (see the modifier).
                                handFocusBack.pending == null
                            }
                    ) {
                        val openStreams: () -> Unit = {
                            // The viewer has taken the play action themselves, so
                            // retire the pending auto-play effect before this
                            // navigation (HD-P2-1). For a series that is still
                            // loading episodes the effect is deliberately
                            // parked - it has not set `autoPlayed` yet - and the
                            // Play button is already live, so a short press in
                            // that window used to navigate here AND then have the
                            // effect fire a second `onNavigateStreams` once the
                            // episodes landed. That second pass also issued its
                            // own ManualSourceSelection.request, which is what
                            // made the picker open instead of auto-selecting.
                            // The menu rows below clear this again on purpose:
                            // they hand the press back to the effect.
                            autoPlayed = true
                            // This press NAMES the request, so it waits for the
                            // resolved root id before building the target: a
                            // press that lands while the TMDB external-ids
                            // lookup is still in flight would otherwise use the
                            // catalog id and ask for another title's streams
                            // (see DetailViewModel.awaitStreamRootId).
                            scope.launch {
                                onNavigateStreams(
                                    playTargetFor(viewModel.awaitStreamRootId(id)),
                                    id,
                                    type,
                                    m.poster,
                                    backdropUrl,
                                    clearLogoUrl,
                                    m.description,
                                    tmdbDetail?.credits?.cast.orEmpty()
                                )
                            }
                        }

                        KBCard(
                            onClick = openStreams,
                            // Long press opens the play menu (Play Manually /
                            // Play from Beginning) rather than going straight
                            // into the picker: with only the manual route there
                            // was no way to ask this button to start the title
                            // over.
                            onLongClick = { playButtonMenu = true },
                            // The three icon-only controls in this row are
                            // BUTTONS, not cards: a pill edge and the button
                            // step of the focus scale, so they read the same as
                            // every other control the viewer presses.
                            shape = KBShapePill,
                            focusedScale = KBFocusButton,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .focusRequester(
                                    playButtonFocusRequester
                                )
                        ) {
                            IconButtonBody(
                                // The logo/icon's ringed brass play button
                                // rather than the bare control-bar triangle, so
                                // the detail PLAY control matches the brand.
                                iconRes = R.drawable.ic_brand_play,
                                // Keeps the label the eye no longer sees
                                // ("PLAY S1 E3" / "RESUME") available to
                                // TalkBack and to anyone reading the screen.
                                contentDescription = playLabel,
                                progress = resumeProgress,
                                brandMark = true
                            )
                        }

                        if (normalizedType == "series") {
                            val randomScope = scope
                            KBCard(
                                onClick = {
                                    // A random episode needs an aired pick, which
                                    // takes a TMDB lookup — so the button resolves it
                                    // first and then opens the same way Play does.
                                    // Every branch navigates, so the pending
                                    // auto-play effect is retired here too
                                    // (HD-P2-1).
                                    autoPlayed = true
                                    randomScope.launch {
                                        val pick = randomAiredEpisode(
                                            context = context,
                                            showId = id,
                                            showType = type,
                                            excludeSeason = effectiveSeason,
                                            excludeEpisode =
                                                resolvedTargetEpisode?.episodeNumber
                                        )
                                        if (pick == null) {
                                            // Nothing aired to choose from: play the
                                            // target the Play button would have -
                                            // built from the root id this screen
                                            // waited for, like that press.
                                            onNavigateStreams(
                                                playTargetFor(
                                                    viewModel.awaitStreamRootId(id)
                                                ),
                                                id,
                                                type,
                                                m.poster,
                                                backdropUrl,
                                                clearLogoUrl,
                                                m.description,
                                                tmdbDetail?.credits?.cast.orEmpty()
                                            )
                                            return@launch
                                        }
                                        val randomName = pick.name
                                            ?.takeIf { it.isNotBlank() }
                                        onNavigateStreams(
                                            StreamsTarget(
                                                contentType = "series",
                                                streamId = pick.streamId,
                                                title = buildString {
                                                    append("$displayName S${pick.season}")
                                                    append(" E${pick.episode}")
                                                    if (randomName != null) append(" • $randomName")
                                                },
                                                displayName = displayName,
                                                season = pick.season,
                                                episode = pick.episode,
                                                // The player resumes this episode's own
                                                // saved progress when it has any.
                                                resumePositionMs = 0L,
                                                totalEpisodesInSeason = pick.episodeCount,
                                                runtimeMinutes = pick.runtimeMinutes,
                                                randomEpisodes = true
                                            ),
                                            id,
                                            type,
                                            m.poster,
                                            backdropUrl,
                                            clearLogoUrl,
                                            m.description,
                                            tmdbDetail?.credits?.cast.orEmpty()
                                        )
                                    }
                                },
                                shape = KBShapePill,
                                focusedScale = KBFocusButton,
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                IconButtonBody(
                                    iconRes = R.drawable.ic_detail_shuffle,
                                    contentDescription = "Random episode"
                                )
                            }
                        }

                        if (trailerVideo != null) {
                            KBCard(
                                onClick = {
                                    playTrailer(context)
                                },
                                shape = KBShapePill,
                                focusedScale = KBFocusButton
                            ) {
                                IconButtonBody(
                                    iconRes = R.drawable.ic_detail_trailer,
                                    contentDescription = "Trailer"
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .padding(
                                start = 24.dp,
                                end = 24.dp,
                                bottom = 6.dp
                            )
                            .scrollToTopOnFocus(detailListState, scope) {
                                handFocusBack.pending == null
                            }
                    ) {
                        val metaLine = remember(
                            m,
                            tmdbDetail,
                            type,
                            mdbListRatings
                        ) {
                            val yearInfo =
                                if (normalizedType == "series") {
                                    formatSeriesYearRange(
                                        tmdbDetail?.firstAirDate,
                                        tmdbDetail?.lastEpisodeToAir?.airDate,
                                        tmdbDetail?.status
                                    ) ?: tmdbDetail?.releaseYear()
                                        ?: m.releaseInfo
                                } else {
                                    tmdbDetail?.releaseYear()
                                        ?: m.releaseInfo
                                }

                            listOfNotNull(
                                tmdbDetail?.certification(
                                    type == "movie"
                                ),
                                yearInfo,
                                when {
                                    type.equals(
                                        "series",
                                        ignoreCase = true
                                    ) ->
                                        seriesStatusTag(
                                            tmdbDetail?.status
                                        )

                                    type.equals(
                                        "movie",
                                        ignoreCase = true
                                    ) ->
                                        tmdbDetail
                                            ?.movieStatusTag()
                                            ?.uppercase()

                                    else -> null
                                },
                                formatRuntimeLabel(
                                    tmdbDetail?.runtime,
                                    m.runtime
                                ),
                                m.language?.takeIf { it.isNotBlank() }?.uppercase(),
                                // IMDb score. MDBList's figure wins over the
                                // meta add-on's so this line can never
                                // disagree with the IMDb chip in the RATINGS
                                // strip a few rows below it (the strip is the
                                // only place either value is sourced from).
                                (
                                    mdbListRatings?.imdb
                                        ?.takeIf { it.isNotBlank() }
                                        ?: m.imdbRating
                                            ?.takeIf { it.isNotBlank() }
                                    )?.let { "IMDb $it" }
                            ).joinToString(" • ")
                        }

                        if (metaLine.isNotBlank()) {
                            Text(
                                metaLine,
                                color = KBTextHi,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    top = 2.dp
                                )
                            )
                        }

                        if (tmdbGenres.isNotEmpty()) {
                            LazyRow(
                                state = genreRowState,
                                // Start/end insets live INSIDE the row's clip
                                // bounds so the focused chip's 2dp border +
                                // glow (which paint outside the chip bounds)
                                // don't get sliced at the row's edge — same
                                // treatment as the keyword rail below.
                                contentPadding = PaddingValues(
                                    start = 6.dp,
                                    end = 6.dp,
                                    top = 6.dp,
                                    bottom = 6.dp
                                ),
                                // No row-level handler: UP and DOWN are decided
                                // by the chip itself (see its own handler
                                // below), exactly as the network and production
                                // rails do. The row's focusRestorer would answer
                                // a vertical press with whichever chip it was
                                // LAST left on, and its old single-case DOWN
                                // (a series with no tags) is now the chain's own
                                // job (see DetailFocusChain).
                                modifier = Modifier
                                    .focusGroup()
                                    .focusRestorer()
                            ) {
                                itemsIndexed(
                                    tmdbGenres,
                                    key = { _, genre -> genre.id }
                                ) { genreIndex, genre ->
                                    val chipKey = "genre:${genre.id}"
                                    val chipFocusRequester =
                                        remember(genre.id) {
                                            FocusRequester()
                                        }
                                    RegisterRailRequester(
                                        railFocusRequesters,
                                        chipKey,
                                        chipFocusRequester
                                    )
                                    // Filed by column like every rail inside the
                                    // list, so a press INTO this header rail from
                                    // the tag rail below is aimed at the chip in
                                    // the column it came from (see focusRow).
                                    registerChipColumn(
                                        GENRE_ROW_KEY,
                                        genreIndex,
                                        chipFocusRequester
                                    )

                                    GenreChip(
                                        name = genre.name,
                                        onClick = {
                                            rememberReturnTarget(
                                                GENRE_ROW_KEY,
                                                chipKey,
                                                genreIndex
                                            )
                                            onNavigateTag(
                                                genre.id,
                                                genre.name,
                                                false,
                                                type
                                            )
                                        },
                                        modifier = Modifier
                                            .focusRequester(
                                                chipFocusRequester
                                            )
                                            // The page's one vertical handoff,
                                            // answered by the chip because this
                                            // rail sits in the fixed header: UP
                                            // is the PLAY row above it and DOWN is
                                            // the tag rail below (or the episode
                                            // section on a series with no tags),
                                            // both decided by the chain (see
                                            // DetailFocusChain).
                                            .onPreviewKeyEvent(
                                                detailRowVerticalFocus(
                                                    DetailRow.GENRE,
                                                    genreIndex,
                                                    detailRowPresence
                                                )
                                            )
                                            .padding(
                                                end = 8.dp
                                            )
                                    )
                                }
                            }
                        } else {
                            m.genres
                                ?.takeIf { it.isNotEmpty() }
                                ?.let {
                                    Text(
                                        it.joinToString(", "),
                                        color = KBTextLo,
                                        maxLines = 1,
                                        overflow =
                                            TextOverflow.Ellipsis,
                                        modifier =
                                            Modifier.padding(top = 2.dp)
                                    )
                                }
                        }
                    }

                    LazyColumn(
                        state = detailListState,
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                            .focusGroup()
                            .focusRestorer(),
                        contentPadding = PaddingValues(
                            top = 6.dp
                        )
                    ) {
                        item(key = "infoblock", contentType = "infoblock") {
                            Column(
                                modifier = Modifier.padding(
                                    start = 24.dp,
                                    end = 24.dp,
                                    bottom = 12.dp
                                )
                            ) {
                                val descriptionText =
                                    tmdbDetail?.overview
                                        ?.takeIf {
                                            it.isNotBlank()
                                        }
                                        ?: m.description

                                descriptionText?.let { overview ->
                                    // Same "View more" treatment as the actor
                                    // biography: collapsed to 4 lines with a
                                    // real focusable toggle that only appears
                                    // when the text actually overflows. States
                                    // are keyed on the text so they reset when
                                    // the richer TMDB overview replaces the
                                    // addon-provided one during load.
                                    var overviewExpanded by remember(overview) {
                                        mutableStateOf(false)
                                    }
                                    var overviewOverflows by remember(overview) {
                                        mutableStateOf(false)
                                    }
                                    Text(
                                        overview,
                                        maxLines = if (overviewExpanded) Int.MAX_VALUE else 4,
                                        overflow = TextOverflow.Ellipsis,
                                        onTextLayout = { textLayoutResult ->
                                            if (!overviewExpanded && textLayoutResult.hasVisualOverflow) {
                                                overviewOverflows = true
                                            }
                                        },
                                        modifier = Modifier.padding(
                                            top = 4.dp
                                        )
                                    )
                                    if (overviewOverflows) {
                                        Surface(
                                            onClick = { overviewExpanded = !overviewExpanded },
                                            // The overview is the list's first item
                                            // but is not focusable, so this toggle is
                                            // the topmost focusable element in the
                                            // list. Focusing it (e.g. walking D-pad up
                                            // from the rails) snaps the list so the
                                            // item's top is at the viewport top, which
                                            // recovers a partially scrolled-out overview
                                            // that the header hook below cannot fix (it
                                            // only fires when item 0 scrolled fully out).
                                            shape = ClickableSurfaceDefaults.shape(
                                                shape = KBShapeSmall
                                            ),
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = Color.Transparent,
                                                contentColor = KBTextLo,
                                                focusedContainerColor = KBSurfaceRaised,
                                                focusedContentColor = KBAccent,
                                                pressedContainerColor = KBSurfaceRaised,
                                                pressedContentColor = KBAccent
                                            ),
                                            scale = ClickableSurfaceDefaults.scale(
                                                focusedScale = KBFocusChip,
                                                pressedScale = KBFocusPressed
                                            ),
                                            border = ClickableSurfaceDefaults.border(
                                                border = Border(
                                                    border = BorderStroke(
                                                        1.dp,
                                                        KBTextLo.copy(alpha = 0.35f)
                                                    ),
                                                    shape = KBShapeSmall
                                                ),
                                                focusedBorder = Border(
                                                    border = BorderStroke(
                                                        2.dp,
                                                        KBAccent
                                                    ),
                                                    shape = KBShapeSmall
                                                )
                                            ),
                                            glow = ClickableSurfaceDefaults.glow(
                                                focusedGlow = Glow(
                                                    elevationColor = KBAccent,
                                                    elevation = KBFocusGlowSmall
                                                )
                                            ),
                                            modifier = Modifier
                                                // The same snap as the header's, with the
                                                // same gates: this toggle is the topmost
                                                // focusable INSIDE the list, so it is the
                                                // other node a dismissing menu's transient
                                                // focus can land on - and the ad-hoc
                                                // `animateScrollToItem(0)` that used to sit
                                                // here fired for that frame too. See
                                                // [scrollToTopOnFocus].
                                                .scrollToTopOnFocus(detailListState, scope) {
                                                    handFocusBack.pending == null
                                                }
                                                .padding(top = 8.dp)
                                        ) {
                                            Text(
                                                text = if (overviewExpanded) "View less" else "View more",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(
                                                    horizontal = 10.dp,
                                                    vertical = 5.dp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val detailFacts = buildList {
                            tmdbDetail?.bestReleaseDate()
                                ?.let {
                                    formatDisplayDate(it)
                                }
                                ?.let {
                                    add(
                                        DetailFactItem(
                                            "Release Date",
                                            it
                                        )
                                    )
                                }

                            if (type == "movie") {
                                tmdbDetail?.budget
                                    ?.takeIf { it > 0L }
                                    ?.let {
                                        add(
                                            DetailFactItem(
                                                "Budget",
                                                formatUsd(it)
                                            )
                                        )
                                    }

                                tmdbDetail?.revenue
                                    ?.takeIf { it > 0L }
                                    ?.let {
                                        add(
                                            DetailFactItem(
                                                "Revenue",
                                                formatUsd(it)
                                            )
                                        )
                                    }
                            }

                            // Awards, from OMDB (TMDB carries none). Movies
                            // AND shows: unlike budget/revenue above, a series
                            // has awards too. Shown only when the lookup
                            // answered with real text — no "Awards: —"
                            // placeholder — and in full: the fact row wraps
                            // rather than truncating.
                            awards
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    add(
                                        DetailFactItem(
                                            "Awards",
                                            it,
                                            wrap = true
                                        )
                                    )
                                }

                            m.country
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    add(
                                        DetailFactItem(
                                            "Country",
                                            it
                                        )
                                    )
                                }
                        }

                        // Critic/audience ratings, right under the overview.
                        // They used to render at the very BOTTOM of the page
                        // inside the REVIEWS section — under cast, network and
                        // production — so in practice they were never seen.
                        //
                        // The row now needs EITHER source: MDBList's chips, or
                        // TMDB's own score as the TMDB chip. Requiring an
                        // MDBList figure meant a title with no tracker ratings
                        // showed nothing here at all, even though TMDB's score
                        // was already loaded for this screen.
                        val tmdbScore =
                            tmdbDetail
                                ?.voteAverage
                                ?.takeIf { it > 0.0 }
                        if (mdbListRatings?.hasAny == true || tmdbScore != null) {
                            item(key = "ratingsrow", contentType = "section-row") {
                                MdbListRatingsStrip(
                                    ratings = mdbListRatings,
                                    tmdbFallback = tmdbScore
                                )
                            }
                        }

                        if (detailFacts.isNotEmpty()) {
                            item(key = "detailfacts", contentType = "section-row") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 2.dp,
                                            bottom = 6.dp
                                        ),
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        detailFacts,
                                        key = { it.label }
                                    ) { fact ->
                                        DetailFactCard(
                                            fact = fact,
                                            modifier =
                                                Modifier.padding(
                                                    end = 8.dp
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (keywords.isNotEmpty()) {
                            item(key = "keywordsrow", contentType = "section-row") {
                                LazyRow(
                                    state = keywordsRowState,
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 6.dp,
                                            bottom = 6.dp
                                        ),
                                    modifier = Modifier
                                        .padding(bottom = 10.dp)
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    itemsIndexed(
                                        keywords,
                                        key = { _, kw -> kw.id }
                                    ) { keywordIndex, kw ->
                                        val chipKey = "keyword:${kw.id}"
                                        val chipFocusRequester =
                                            remember(kw.id) {
                                                FocusRequester()
                                            }
                                        RegisterRailRequester(
                                            railFocusRequesters,
                                            chipKey,
                                            chipFocusRequester
                                        )
                                        // Filed by column like the chip rails
                                        // below, so the chain can move INTO this
                                        // rail from the episode row as well as
                                        // out of it (see focusRow).
                                        registerChipColumn(
                                            KEYWORDS_ROW_KEY,
                                            keywordIndex,
                                            chipFocusRequester
                                        )

                                        KeywordChip(
                                            name = kw.name,
                                            onClick = {
                                                rememberReturnTarget(
                                                    KEYWORDS_ROW_KEY,
                                                    chipKey,
                                                    keywordIndex
                                                )
                                                onNavigateTag(
                                                    kw.id,
                                                    kw.name,
                                                    true,
                                                    type
                                                )
                                            },
                                            modifier = Modifier
                                                .focusRequester(
                                                    chipFocusRequester
                                                )
                                                // The page's one vertical handoff,
                                                // answered by the CHIP so the
                                                // press keeps its COLUMN: the
                                                // row-level handler passed 0,
                                                // so UP out of the fifth tag
                                                // landed on the first genre and
                                                // DOWN came back to the first
                                                // tag - a rail left scrolled to
                                                // the fifth chip then had its
                                                // first chip disposed, and the
                                                // next DOWN skipped the row
                                                // entirely (see focusRow).
                                                .onPreviewKeyEvent(
                                                    detailRowVerticalFocus(
                                                        DetailRow.KEYWORDS,
                                                        keywordIndex,
                                                        detailRowPresence
                                                    )
                                                )
                                                .padding(
                                                    end = 6.dp
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (
                            normalizedType == "series" &&
                            seasons.isNotEmpty()
                        ) {
                            item(key = "episodesheader", contentType = "section-header") {
                                KBSectionHeader(
                                    title = episodesHeader,
                                    modifier = Modifier.padding(
                                        start = KBScreenEdge,
                                        top = 14.dp
                                    )
                                )
                            }

                            item(key = "seasonrow", contentType = "section-row") {
                                SeasonRow(
                                    state = seasonRowState,
                                    seasons = seasons,
                                    seasonPremiereDates = seasonPremiereDates,
                                    today = today,
                                    currentSelectedSeason =
                                        effectiveSeason,
                                    onSeasonSelected = {
                                        seasonNum ->
                                        userManuallyChangedSeason =
                                            true
                                        clearEpisodeTransitionState()
                                        selectedSeason = seasonNum
                                    },
                                    onSeasonFocused = {
                                        seasonNum ->
                                        if (
                                            selectedSeason !=
                                                seasonNum
                                        ) {
                                            userManuallyChangedSeason =
                                                true
                                            clearEpisodeTransitionState()
                                            selectedSeason =
                                                seasonNum
                                        }
                                    },
                                    seasonFocusRequesters =
                                        seasonFocusRequesters,
                                    parentId = id,
                                    watchedEpisodeKeys =
                                        watchedEpisodeKeys,
                                    simklWatchedEpisodes =
                                        simklWatchedEpisodes,
                                    seasonEpisodeNumbersFor =
                                        seasonEpisodeNumbersFor,
                                    onSeasonLongPress = {
                                        seasonNum,
                                        seasonName,
                                        episodeNumbers,
                                        focusRequester ->
                                        lastSeasonFocusRequester =
                                            focusRequester
                                        seasonMenu =
                                            SeasonMenuTarget(
                                                seasonNumber =
                                                    seasonNum,
                                                seasonName =
                                                    seasonName,
                                                episodeNumbers =
                                                    episodeNumbers
                                            )
                                    }
                                )
                            }

                            when {
                                episodesLoading -> {
                                    item(
                                        key = "episodesloading"
                                    ) {
                                        EpisodesStatusMessage(
                                            icon = "⏳",
                                            message =
                                                "Loading episodes…"
                                        )
                                    }
                                }

                                episodeError != null -> {
                                    item(
                                        key = "episodeserror"
                                    ) {
                                        EpisodesStatusMessage(
                                            icon = "⚠️",
                                            message =
                                                "Couldn't load episodes: $episodeError"
                                        )
                                    }
                                }

                                effectiveSeason != null &&
                                    seasonUnavailable(effectiveSeason) -> {
                                    // Announced-but-unreleased season: TMDB still returns a
                                    // placeholder episode row for these, so intercept BEFORE the
                                    // episode list renders and explain with the premiere date.
                                    item(
                                        key = "seasonunavailable"
                                    ) {
                                        val premiere =
                                            seasonPremiereDates[effectiveSeason]
                                        if (premiere != null) {
                                            EpisodesStatusMessage(
                                                icon = "📅",
                                                message = "Season $effectiveSeason airs soon — premieres " +
                                                    premiere.format(premiereDateFormatter) +
                                                    ". Episodes will appear here once they're released."
                                            )
                                        }
                                    }
                                }

                                episodes.isEmpty() -> {
                                    item(
                                        key = "episodesempty"
                                    ) {
                                        EpisodesStatusMessage(
                                            icon = "📭",
                                            message =
                                                "No episodes found for this season."
                                        )
                                    }
                                }

                                else -> {
                                    item(
                                        key = "episodesrow"
                                    ) {
                                        // ONE collector for the whole rail. Each
                                        // episode card used to ask the ViewModel
                                        // for this same series-level flow and
                                        // collect it (observeIsWatched returns
                                        // one shared flow per id/type), so a
                                        // 24-episode season paid 24 collectors
                                        // for one boolean - on the app's hottest
                                        // screen. The poster tiles' half of this
                                        // pattern was hoisted by PosterTileEnv;
                                        // this is the rail's.
                                        val seriesWatched by
                                            remember(id, type) {
                                                viewModel.observeIsWatched(
                                                    id,
                                                    type
                                                )
                                            }.collectAsStateWithLifecycle()
                                        CompositionLocalProvider(
                                            LocalBringIntoViewSpec
                                                provides
                                                LocalTvBringIntoViewSpec
                                        ) {
                                            LazyRow(
                                                state =
                                                    episodesRowState,
                                                contentPadding =
                                                    PaddingValues(
                                                        start = 24.dp,
                                                        end = 24.dp,
                                                        top = 10.dp,
                                                        bottom = 10.dp
                                                    ),
                                                modifier = Modifier
                                                    .padding(
                                                        bottom = 14.dp
                                                    )
                                                    .focusGroup()
                                                    .focusRestorer()
                                                    // DOWN is the page's vertical
                                                    // handoff, decided by the
                                                    // chain like every other row.
                                                    // UP is NOT answered here: the
                                                    // episode card owns it, and it
                                                    // exists to land on the season
                                                    // chip the viewer is inside
                                                    // (see focusSeasonChip) - an
                                                    // ancestor handler runs first,
                                                    // so taking UP here would throw
                                                    // that away.
                                                    .onPreviewKeyEvent(
                                                        detailRowVerticalFocus(
                                                            DetailRow.EPISODES,
                                                            0,
                                                            detailRowPresence,
                                                            setOf(DetailFocusDirection.DOWN)
                                                        )
                                                    )
                                            ) {
                                                items(
                                                    items = episodes,
                                                    // The episode's own identity, not its
                                                    // stream id: the id is the FILE that
                                                    // holds it and is shared by every
                                                    // episode of a segmented show (see
                                                    // episodeRowKey - keying by it is the
                                                    // "Key ... was already used" crash).
                                                    key = { ep ->
                                                        episodeRowKey(
                                                            id,
                                                            effectiveSeason,
                                                            ep.episodeNumber
                                                        )
                                                    }
                                                ) { ep ->
                                                    val episodeKey =
                                                        remember(
                                                            id,
                                                            effectiveSeason,
                                                            ep.episodeNumber
                                                        ) {
                                                            effectiveSeason?.let {
                                                                season ->
                                                                "$id:$season:${ep.episodeNumber}"
                                                            }
                                                        }

                                                    val isEpisodeWatched =
                                                        remember(
                                                            ep.episodeNumber,
                                                            watchedEpisodesForSeason,
                                                            episodeKey,
                                                            watchedEpisodeKeys,
                                                            seriesWatched
                                                        ) {
                                                            seriesWatched ||
                                                                ep.episodeNumber in
                                                                    watchedEpisodesForSeason ||
                                                                (
                                                                    episodeKey != null &&
                                                                        episodeKey in
                                                                            watchedEpisodeKeys
                                                                    )
                                                        }

                                                    val focusRequester =
                                                        remember {
                                                            FocusRequester()
                                                        }

                                                    effectiveSeason?.let {
                                                        season ->
                                                        episodeFocusRequesters[
                                                            season to ep.episodeNumber
                                                        ] = focusRequester
                                                    }

                                                    val shouldFocusThisCard =
                                                        when {
                                                            resolvedTargetEpisode
                                                                ?.streamId != null ->
                                                                ep.streamId ==
                                                                    resolvedTargetEpisode
                                                                        .streamId

                                                            targetEpisodeNumber != null ->
                                                                ep.episodeNumber ==
                                                                    targetEpisodeNumber

                                                            else -> false
                                                        }

                                                    LaunchedEffect(
                                                        shouldFocusThisCard,
                                                        episodesLoading,
                                                        effectiveSeason,
                                                        episodes.size,
                                                        userManuallyChangedSeason,
                                                        episodeTransitionState.edge
                                                    ) {
                                                        if (
                                                            shouldFocusThisCard &&
                                                            !episodesLoading &&
                                                            episodes.isNotEmpty() &&
                                                            !userManuallyChangedSeason &&
                                                            !restoredReturnFocus &&
                                                            episodeTransitionState.edge ==
                                                                null
                                                        ) {
                                                            focusRequester
                                                                .requestFocus()
                                                        }
                                                    }

                                                    val isFirstEpisode =
                                                        ep.streamId ==
                                                            episodes
                                                                .firstOrNull()
                                                                ?.streamId

                                                    val isLastEpisode =
                                                        ep.streamId ==
                                                            episodes
                                                                .lastOrNull()
                                                                ?.streamId

                                                    val currentSeasonIndex =
                                                        seasons.indexOf(
                                                            effectiveSeason
                                                        )

                                                    EpisodeCard(
                                                        ep = ep,
                                                        isWatched =
                                                            isEpisodeWatched,
                                                        airDatesTrusted =
                                                            effectiveSeason !in seasonsWithStaleDates,
                                                        progressFraction = run {
                                                            // A watched episode never shows a progress
                                                            // bar. The tick and a partial bar are two
                                                            // contradictory statements about the same
                                                            // episode, and the row behind the bar is a
                                                            // leftover: leaving in the closing minutes
                                                            // before the end card was raised files a
                                                            // resume point locally while the tracker is
                                                            // told the episode is watched. The tick is
                                                            // the tracker's verdict, so it wins.
                                                            //
                                                            // Per-episode progress first (any
                                                            // in-progress episode), then the
                                                            // resume row (covers the Simkl
                                                            // cloud-derived fallback which has
                                                            // no local history row).
                                                            val row =
                                                                inProgressByStreamId[ep.streamId]
                                                                    ?: resumeInfo?.takeIf {
                                                                        it.episodeStreamId == ep.streamId
                                                                    }
                                                            if (isEpisodeWatched) {
                                                                0f
                                                            } else if (
                                                                row != null &&
                                                                row.durationMs > 0L
                                                            ) {
                                                                (row.positionMs.toFloat() /
                                                                    row.durationMs.toFloat())
                                                                    .coerceIn(0f, 1f)
                                                            } else {
                                                                0f
                                                            }
                                                        },
                                                        fallbackImageUrl =
                                                            backdropUrl
                                                                ?: m.poster,
                                                        onClick = {
                                                            // The row the progress bar above reads: per-episode
                                                            // history first, then the resume fallback. Clicking a
                                                            // card that shows 40% must resume at 40%, not 0 - the
                                                            // old code only passed a position when this episode
                                                            // happened to be the single newest resume row.
                                                            val resumeRow =
                                                                inProgressByStreamId[ep.streamId]
                                                                    ?: resumeInfo
                                                                        ?.takeIf {
                                                                            it.episodeStreamId ==
                                                                                ep.streamId
                                                                        }

                                                            val epSuffix =
                                                                ep.name?.let {
                                                                    " • $it"
                                                                } ?: ""

                                                            val target =
                                                                StreamsTarget(
                                                                    contentType =
                                                                        "series",
                                                                    streamId =
                                                                        ep.streamId,
                                                                    title =
                                                                        "$displayName S${effectiveSeason ?: 1} E${ep.episodeNumber}$epSuffix",
                                                                    displayName =
                                                                        displayName,
                                                                    season =
                                                                        effectiveSeason,
                                                                    episode =
                                                                        ep.episodeNumber,
                                                                    resumePositionMs =
                                                                        resumeRow
                                                                            ?.positionMs
                                                                            ?: 0L,
                                                                    totalEpisodesInSeason = airedEpisodesInSeason,
                                                                    runtimeMinutes =
                                                                        resumeRow
                                                                            ?.durationMs
                                                                            ?.div(60_000L)
                                                                            ?.toInt()
                                                                            ?.takeIf {
                                                                                it > 0
                                                                            }
                                                                            ?: ep.runtimeMinutes
                                                                                ?.takeIf {
                                                                                    it > 0
                                                                                }
                                                                )

                                                            onNavigateStreams(
                                                                target,
                                                                id,
                                                                type,
                                                                m.poster,
                                                                backdropUrl,
                                                                clearLogoUrl,
                                                                ep.overview
                                                                    ?: m.description,
                                                                tmdbDetail?.credits?.cast.orEmpty()
                                                            )
                                                        },
                                                        onLongClick = {
                                                            val seasonForMenu =
                                                                effectiveSeason
                                                            if (
                                                                seasonForMenu !=
                                                                null
                                                            ) {
                                                                lastEpisodeFocusRequester =
                                                                    focusRequester
                                                                episodeMenu =
                                                                    EpisodeMenuTarget(
                                                                        season =
                                                                            seasonForMenu,
                                                                        episode =
                                                                            ep.episodeNumber,
                                                                        episodeTitle =
                                                                            ep.name,

                                                                        // The same number list the
                                                                        // season-chip menu marks,
                                                                        // filtered for unaired
                                                                        // episodes. This used to
                                                                        // pass the raw loaded
                                                                        // listing, so the
                                                                        // episode-card menu marked
                                                                        // next week's episode (and
                                                                        // pushed it to the
                                                                        // trackers) while the chip
                                                                        // menu two rows above did
                                                                        // not.
                                                                        seasonEpisodeNumbers =
                                                                            seasonEpisodeNumbersFor(
                                                                                seasonForMenu
                                                                            )
                                                                                .orEmpty(),
                                                                        streamId =
                                                                            ep.streamId,
                                                                        overview =
                                                                            ep.overview,
                                                                        runtimeMinutes =
                                                                            ep.runtimeMinutes
                                                                    )
                                                            }
                                                        },
                                                        modifier = Modifier
                                                            .focusRequester(
                                                                focusRequester
                                                            )
                                                            // Preview, not the ordinary key pass:
                                                            // the focus system moves focus with
                                                            // the same key, and a handler that runs
                                                            // afterwards can only correct where
                                                            // focus landed, never prevent the wrong
                                                            // chip from being focused and selected
                                                            // on the way.
                                                            .onPreviewKeyEvent {
                                                                keyEvent ->
                                                                if (
                                                                    keyEvent.type !=
                                                                        KeyEventType.KeyDown
                                                                ) {
                                                                    return@onPreviewKeyEvent false
                                                                }

                                                                when {
                                                                    // UP belongs to the
                                                                    // season on screen. This
                                                                    // rail can be scrolled to
                                                                    // an episode deep into a
                                                                    // late season while the
                                                                    // chip row starts at
                                                                    // season 1, and the
                                                                    // default focus search
                                                                    // lands on whichever chip
                                                                    // sits above the rail -
                                                                    // season 2 - which also
                                                                    // SELECTS it.
                                                                    keyEvent.key ==
                                                                        Key.DirectionUp &&
                                                                        episodeTransitionState.edge ==
                                                                            null -> {
                                                                        val chipSeason =
                                                                            effectiveSeason
                                                                        if (
                                                                            chipSeason ==
                                                                                null ||
                                                                            seasonChipIndex(
                                                                                chipSeason
                                                                            ) < 0                                        ) {
                                            false
                                        } else {
                                            startFocusMove {
                                                // The chip is the press's real
                                                // target - but it is a lazy
                                                // row's card, and the ask can
                                                // miss every one of its
                                                // retries when the row was
                                                // never laid out (see
                                                // focusSeasonChip). The press
                                                // is consumed either way, so a
                                                // miss used to be an UP that
                                                // did nothing at all; the
                                                // chain's own UP is where it
                                                // goes instead.
                                                if (
                                                    !focusSeasonChip(
                                                        chipSeason
                                                    )
                                                ) {
                                                    moveFocusVertical(
                                                        DetailRow.EPISODES,
                                                        DetailFocusDirection.UP,
                                                        0,
                                                        detailRowPresence
                                                    )
                                                }
                                            }
                                            true
                                        }
                                    }

                                                                    isFirstEpisode &&
                                                                        keyEvent.key ==
                                                                            Key.DirectionLeft &&
                                                                        currentSeasonIndex >
                                                                            0 -> {
                                                                        userManuallyChangedSeason =
                                                                            false
                                                                        episodeTransitionState =
                                                                            EpisodeTransitionState(
                                                                                edge =
                                                                                    EpisodeFocusEdge.END
                                                                            )
                                                                        // Park focus on the
                                                                        // root sink BEFORE the
                                                                        // season swap disposes
                                                                        // the focused card.
                                                                        seasonSwapSinkArmed =
                                                                            true
                                                                        runCatching {
                                                                            seasonSwapFocusSink
                                                                                .requestFocus()
                                                                        }
                                                                        selectedSeason =
                                                                            seasons[
                                                                                currentSeasonIndex -
                                                                                    1
                                                                            ]
                                                                        true
                                                                    }

                                                                    isLastEpisode &&
                                                                        keyEvent.key ==
                                                                            Key.DirectionRight &&
                                                                        currentSeasonIndex !=
                                                                            -1 &&
                                                                        currentSeasonIndex <
                                                                            seasons.size -
                                                                                1 -> {
                                                                        userManuallyChangedSeason =
                                                                            false
                                                                        episodeTransitionState =
                                                                            EpisodeTransitionState(
                                                                                edge =
                                                                                    EpisodeFocusEdge.START
                                                                            )
                                                                        // Park focus on the
                                                                        // root sink BEFORE the
                                                                        // season swap disposes
                                                                        // the focused card.
                                                                        seasonSwapSinkArmed =
                                                                            true
                                                                        runCatching {
                                                                            seasonSwapFocusSink
                                                                                .requestFocus()
                                                                        }
                                                                        selectedSeason =
                                                                            seasons[
                                                                                currentSeasonIndex +
                                                                                    1
                                                                            ]
                                                                        true
                                                                    }

                                                                    else -> false
                                                                }
                                                            }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }


                        if (hasPeopleRow) {
                            item(key = "peopleheader", contentType = "section-header") {
                                // Plain label, NOT focusable: the movie DOWN
                                // path (keywords row) jumps straight into the
                                // people rail instead. A focusable label here
                                // used to be the only focusable node between
                                // the tag rail and the people cards on movies,
                                // so D-pad scrolling caught on invisible
                                // "empty space" above the people row.
                                KBSectionHeader(
                                    title = "PEOPLE",
                                    modifier = Modifier.padding(
                                        start = KBScreenEdge,
                                        top = 14.dp
                                    )
                                )
                            }

                            item(key = "peoplerow", contentType = "section-row") {
                                LazyRow(
                                    state = peopleRowState,
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 16.dp,
                                            bottom = 16.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 12.dp
                                        )
                                        // Attached BEFORE the group so
                                        // requestFocus() redirects into it
                                        // (focusRestorer then lands on the
                                        // last-viewed or first card).
                                        .focusRequester(
                                            movieDetailsFocusRequester
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    itemsIndexed(
                                        items = peopleItems,
                                        key = { _, person ->
                                            when (person) {
                                                is PeopleRowItem.Person ->
                                                    personRowKey(person.member)

                                                PeopleRowItem.Separator ->
                                                    "peopleseparator"
                                            }
                                        }
                                    ) { personIndex, person ->
                                        when (person) {
                                            is PeopleRowItem.Person -> {
                                                val cardKey =
                                                    personRowKey(person.member)
                                                val cardFocusRequester =
                                                    remember(
                                                        person.member.id,
                                                        person.member.character
                                                    ) {
                                                        FocusRequester()
                                                    }
                                                RegisterRailRequester(
                                                    railFocusRequesters,
                                                    cardKey,
                                                    cardFocusRequester
                                                )
                                                // The card's COLUMN in the rail
                                                // (a separator has none), which
                                                // is what the chips below step
                                                // by.
                                                val column =
                                                    peopleColumns.getOrNull(
                                                        personIndex
                                                    )
                                                if (column != null) {
                                                    registerChipColumn(
                                                        PEOPLE_ROW_KEY,
                                                        column,
                                                        cardFocusRequester
                                                    )
                                                }

                                                CastCard(
                                                    member =
                                                        person.member,
                                                    onClick = {
                                                        rememberReturnTarget(
                                                            PEOPLE_ROW_KEY,
                                                            cardKey,
                                                            personIndex
                                                        )
                                                        onNavigateActor(
                                                            person.member.id
                                                        )
                                                    },
                                                    modifier = Modifier
                                                        .focusRequester(
                                                            cardFocusRequester
                                                        )
                                                        // The page's one vertical
                                                        // handoff, with the COLUMN
                                                        // kept on the way: into the
                                                        // chips below, or up to the
                                                        // episode section above
                                                        // (see DetailFocusChain).
                                                        .onPreviewKeyEvent(
                                                            detailRowVerticalFocus(
                                                                DetailRow.PEOPLE,
                                                                column ?: 0,
                                                                detailRowPresence
                                                            )
                                                        )
                                                )
                                            }

                                            PeopleRowItem.Separator ->
                                                PeopleSeparatorCard()
                                        }
                                    }
                                }
                            }
                        } else if (!m.cast.isNullOrEmpty()) {
                            item(key = "castfallback", contentType = "status") {
                                Text(
                                    "Cast ${m.cast.orEmpty().joinToString(", ")}",
                                    modifier = Modifier.padding(
                                        start = 24.dp,
                                        top = 10.dp,
                                        end = 24.dp
                                    )
                                )
                            }
                        } else {
                            m.director
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { directors ->
                                    item(key = "directorfallback", contentType = "status") {
                                        Text(
                                            "Director ${directors.joinToString(", ")}",
                                            color = KBTextLo,
                                            modifier =
                                                Modifier.padding(
                                                    start = 24.dp,
                                                    top = 6.dp,
                                                    end = 24.dp
                                                )
                                        )
                                    }
                                }
                        }

                        if (hasNetworkRow) {
                            item(key = "networkheader", contentType = "section-header") {
                                KBSectionHeader(
                                    title = "NETWORK",
                                    modifier = Modifier.padding(
                                        start = KBScreenEdge,
                                        top = 14.dp
                                    )
                                )
                            }

                            item(key = "networkrow", contentType = "section-row") {
                                LazyRow(
                                    state = networkRowState,
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 8.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                        // No UP handler on the ROW any more.
                                        // UP and DOWN are decided by the chip
                                        // itself (see its own key handler
                                        // below), because this rail's
                                        // focusRestorer - and the default
                                        // search behind it - answers a vertical
                                        // press with the chip or card that rail
                                        // was LAST left on rather than the one
                                        // in the column the viewer is actually
                                        // in. A row-level handler cannot know
                                        // that column; a chip can.
                                ) {
                                    itemsIndexed(
                                        networks,
                                        key = { _, n -> n.id }
                                    ) { networkIndex, n ->
                                        val chipKey = "network:${n.id}"
                                        val chipFocusRequester =
                                            remember(n.id) { FocusRequester() }
                                        RegisterRailRequester(
                                            railFocusRequesters,
                                            chipKey,
                                            chipFocusRequester
                                        )
                                        registerChipColumn(
                                            NETWORK_ROW_KEY,
                                            networkIndex,
                                            chipFocusRequester
                                        )

                                        StudioChip(
                                            name = n.name,
                                            logoPath = n.logoPath,
                                            onClick = {
                                                rememberReturnTarget(
                                                    NETWORK_ROW_KEY,
                                                    chipKey,
                                                    networkIndex
                                                )
                                                onNavigateStudio(
                                                    n.id,
                                                    n.name,
                                                    true
                                                )
                                            },
                                            onLongClick = {
                                                lastStudioChipFocusRequester =
                                                    chipFocusRequester
                                                studioChipMenu =
                                                    StudioChipMenuTarget(
                                                        categoryKey = "services",
                                                        id = n.id,
                                                        name = n.name,
                                                        onHome =
                                                            BrowseHomeShortcuts.contains(
                                                                BrowseHomeShortcuts.list(
                                                                    chipMenuContext
                                                                ),
                                                                "services",
                                                                n.id,
                                                                n.name
                                                            )
                                                    )
                                            },
                                            modifier = Modifier
                                                .focusRequester(
                                                    chipFocusRequester
                                                )
                                                // UP and DOWN are the page's one
                                                // vertical handoff: the chain
                                                // decides which row this press
                                                // leaves for, and the COLUMN is
                                                // kept on the way - the people
                                                // card directly above this chip,
                                                // the production chip directly
                                                // below it, and neither rail is
                                                // asked to remember where it was
                                                // (see DetailFocusChain and
                                                // DetailChipColumns).
                                                .onPreviewKeyEvent(
                                                    detailRowVerticalFocus(
                                                        DetailRow.NETWORK,
                                                        networkIndex,
                                                        detailRowPresence
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (hasProductionRow) {
                            item(key = "productionheader", contentType = "section-header") {
                                KBSectionHeader(
                                    title = "PRODUCTION",
                                    modifier = Modifier.padding(
                                        start = KBScreenEdge,
                                        top = 14.dp
                                    )
                                )
                            }

                            item(key = "productionrow", contentType = "section-row") {
                                LazyRow(
                                    state = productionRowState,
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 8.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    itemsIndexed(
                                        companies,
                                        key = { _, c -> c.id }
                                    ) { companyIndex, c ->
                                        val chipKey = "company:${c.id}"
                                        val chipFocusRequester =
                                            remember(c.id) { FocusRequester() }
                                        RegisterRailRequester(
                                            railFocusRequesters,
                                            chipKey,
                                            chipFocusRequester
                                        )
                                        registerChipColumn(
                                            PRODUCTION_ROW_KEY,
                                            companyIndex,
                                            chipFocusRequester
                                        )

                                        StudioChip(
                                            name = c.name,
                                            logoPath = c.logoPath,
                                            onClick = {
                                                rememberReturnTarget(
                                                    PRODUCTION_ROW_KEY,
                                                    chipKey,
                                                    companyIndex
                                                )
                                                onNavigateStudio(
                                                    c.id,
                                                    c.name,
                                                    false
                                                )
                                            },
                                            onLongClick = {
                                                lastStudioChipFocusRequester =
                                                    chipFocusRequester
                                                studioChipMenu =
                                                    StudioChipMenuTarget(
                                                        categoryKey = "studios",
                                                        id = c.id,
                                                        name = c.name,
                                                        onHome =
                                                            BrowseHomeShortcuts.contains(
                                                                BrowseHomeShortcuts.list(
                                                                    chipMenuContext
                                                                ),
                                                                "studios",
                                                                c.id,
                                                                c.name
                                                            )
                                                    )
                                            },
                                            modifier = Modifier
                                                .focusRequester(
                                                    chipFocusRequester
                                                )
                                                // UP keeps its column like every
                                                // other rail: the chain decides
                                                // which row is above this one and
                                                // the column is carried across
                                                // (see DetailFocusChain).
                                                .onPreviewKeyEvent(
                                                    detailRowVerticalFocus(
                                                        DetailRow.PRODUCTION,
                                                        companyIndex,
                                                        detailRowPresence
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (hasCollectionRow) {
                            item(key = "collectionheader", contentType = "section-header") {
                                KBSectionHeader(
                                    title = collection?.name?.uppercase()
                                        ?: "COLLECTION",
                                    modifier = Modifier.padding(
                                        start = KBScreenEdge,
                                        top = 14.dp
                                    )
                                )
                            }

                            item(key = "collectionrow", contentType = "section-row") {
                                LazyRow(
                                    state = collectionRowState,
                                    // Poster rails need an explicit gap: without
                                    // one the 124dp tiles sit flush and captions
                                    // run into the neighboring poster.
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 14.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    itemsIndexed(
                                        collectionParts,
                                        key = { _, part -> part.id }
                                    ) { partIndex, part ->
                                        // Focus requester for restoring focus
                                        // after the long-press menu dismisses.
                                        val requester = remember(
                                            part.id
                                        ) {
                                            FocusRequester()
                                        }
                                        val partKey = "collection:${part.id}"
                                        RegisterRailRequester(
                                            railFocusRequesters,
                                            partKey,
                                            requester
                                        )
                                        // Filed by index like the chip rails, so
                                        // a vertical press INTO this rail can be
                                        // aimed at the column it came from (see
                                        // focusRow).
                                        registerChipColumn(
                                            COLLECTION_ROW_KEY,
                                            partIndex,
                                            requester
                                        )

                                        PosterGridCard(
                                            posterPath =
                                                part.posterPath,
                                            artId = part.id.toString(),
                                            artType = "movie",
                                            contentDescription =
                                                part.title ?: "",
                                            captionYear =
                                                part.releaseDate?.take(4),
                                            captionRating =
                                                part.voteAverage,
                                            isWatched =
                                                resolvedPosterIds[
                                                    viewModel
                                                        .posterLookupKey(
                                                            part.id,
                                                            "movie"
                                                        )
                                                ]?.let {
                                                    imdbId ->
                                                    viewModel.watchedKey(
                                                        imdbId,
                                                        "movie"
                                                    ) in watchedKeys
                                                } == true ||
                                                // The poster's own `tmdb:<n>`
                                                // id: the only key a part TMDB
                                                // has no `imdb_id` for ever
                                                // has (see
                                                // [WatchedStatusKeys]).
                                                viewModel.watchedKey(
                                                    WatchedStatusKeys
                                                        .tmdbKeyId(part.id),
                                                    "movie"
                                                ) in watchedKeys,
                                            isPartiallyWatched =
                                                resolvedPosterIds[
                                                    viewModel
                                                        .posterLookupKey(
                                                            part.id,
                                                            "movie"
                                                        )
                                                ]?.let {
                                                    imdbId ->
                                                    viewModel.watchedKey(
                                                        imdbId,
                                                        "movie"
                                                    ) in partialWatchedKeys
                                                } == true ||
                                                viewModel.watchedKey(
                                                    WatchedStatusKeys
                                                        .tmdbKeyId(part.id),
                                                    "movie"
                                                ) in partialWatchedKeys,
                                            onClick = {
                                                scope.launch {
                                                    val imdbId =
                                                        viewModel
                                                            .resolveImdbId(
                                                                part.id,
                                                                "movie"
                                                            )

                                                    if (
                                                        imdbId != null
                                                    ) {
                                                        rememberReturnTarget(
                                                            COLLECTION_ROW_KEY,
                                                            partKey,
                                                            partIndex
                                                        )
                                                        onNavigateDetail(
                                                            "movie",
                                                            imdbId
                                                        )
                                                    } else {
                                                        // No IMDb id means no
                                                        // detail route. Say so
                                                        // instead of eating the
                                                        // press.
                                                        feedback.show(
                                                            "Couldn't open this title",
                                                            isError = true
                                                        )
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                lastPosterFocusRequester =
                                                    requester
                                                posterMenu =
                                                    PosterMenuTarget(
                                                        tmdbId = part.id,
                                                        mediaType = "movie",
                                                        name = part.title ?: "",
                                                        returnTarget =
                                                            returnTargetFor(
                                                                COLLECTION_ROW_KEY,
                                                                partKey,
                                                                partIndex
                                                            )
                                                    )
                                            },
                                            modifier = Modifier
                                                .focusRequester(requester)
                                                // The page's one vertical
                                                // handoff, so UP out of a
                                                // collection poster reaches the
                                                // production row above it and
                                                // DOWN reaches More Like This
                                                // (see DetailFocusChain).
                                                .onPreviewKeyEvent(
                                                    detailRowVerticalFocus(
                                                        DetailRow.COLLECTION,
                                                        partIndex,
                                                        detailRowPresence
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (hasMoreLikeThisRow) {
                            item(key = "recsheader", contentType = "section-header") {
                                KBSectionHeader(
                                    title = "MORE LIKE THIS",
                                    modifier = Modifier.padding(
                                        start = KBScreenEdge,
                                        top = 14.dp
                                    )
                                )
                            }

                            item(key = "recsrow", contentType = "section-row") {
                                LazyRow(
                                    state = recsRowState,
                                    // Poster rails need an explicit gap: without
                                    // one the 124dp tiles sit flush and captions
                                    // run into the neighboring poster.
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 14.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    itemsIndexed(
                                        recommendations.take(30),
                                        key = { _, rec -> rec.id }
                                    ) { recIndex, rec ->
                                        // Focus requester for restoring focus
                                        // after the long-press menu dismisses.
                                        val requester = remember(
                                            rec.id
                                        ) {
                                            FocusRequester()
                                        }
                                        val recKey = "rec:${rec.id}"
                                        RegisterRailRequester(
                                            railFocusRequesters,
                                            recKey,
                                            requester
                                        )
                                        // Same as the collection rail: the
                                        // poster's index is the column a
                                        // vertical press keeps.
                                        registerChipColumn(
                                            RECS_ROW_KEY,
                                            recIndex,
                                            requester
                                        )

                                        PosterGridCard(
                                            posterPath =
                                                rec.posterPath,
                                            artId = rec.id.toString(),
                                            artType = normalizedType,
                                            contentDescription =
                                                rec.title
                                                    ?: rec.name
                                                    ?: "",
                                            captionYear =
                                                (rec.releaseDate
                                                    ?: rec.firstAirDate)?.take(4),
                                            captionRating =
                                                rec.voteAverage,
                                            isWatched =
                                                resolvedPosterIds[
                                                    viewModel
                                                        .posterLookupKey(
                                                            rec.id,
                                                            normalizedType
                                                        )
                                                ]?.let {
                                                    imdbId ->
                                                    viewModel.watchedKey(
                                                        imdbId,
                                                        type.lowercase()
                                                    ) in watchedKeys
                                                } == true ||
                                                // Same fallback as the
                                                // collection rail above.
                                                viewModel.watchedKey(
                                                    WatchedStatusKeys
                                                        .tmdbKeyId(rec.id),
                                                    type.lowercase()
                                                ) in watchedKeys,
                                            isPartiallyWatched =
                                                resolvedPosterIds[
                                                    viewModel
                                                        .posterLookupKey(
                                                            rec.id,
                                                            normalizedType
                                                        )
                                                ]?.let {
                                                    imdbId ->
                                                    viewModel.watchedKey(
                                                        imdbId,
                                                        type.lowercase()
                                                    ) in partialWatchedKeys
                                                } == true ||
                                                viewModel.watchedKey(
                                                    WatchedStatusKeys
                                                        .tmdbKeyId(rec.id),
                                                    type.lowercase()
                                                ) in partialWatchedKeys,
                                            onClick = {
                                                scope.launch {
                                                    val imdbId =
                                                        viewModel
                                                            .resolveImdbId(
                                                                rec.id,
                                                                type
                                                            )

                                                    if (
                                                        imdbId != null
                                                    ) {
                                                        rememberReturnTarget(
                                                            RECS_ROW_KEY,
                                                            recKey,
                                                            recIndex
                                                        )
                                                        onNavigateDetail(
                                                            type,
                                                            imdbId
                                                        )
                                                    } else {
                                                        feedback.show(
                                                            "Couldn't open this title",
                                                            isError = true
                                                        )
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                lastPosterFocusRequester =
                                                    requester
                                                posterMenu =
                                                    PosterMenuTarget(
                                                        tmdbId = rec.id,
                                                        mediaType = type,
                                                        name = rec.title
                                                            ?: rec.name
                                                            ?: "",
                                                        returnTarget =
                                                            returnTargetFor(
                                                                RECS_ROW_KEY,
                                                                recKey,
                                                                recIndex
                                                            )
                                                    )
                                            },
                                            modifier = Modifier
                                                .focusRequester(requester)
                                                // The same handoff as the rails
                                                // above: UP reaches the
                                                // collection, DOWN the review
                                                // rail painted below this one (or
                                                // the Media row, with no reviews).
                                                .onPreviewKeyEvent(
                                                    detailRowVerticalFocus(
                                                        DetailRow.MORE_LIKE_THIS,
                                                        recIndex,
                                                        detailRowPresence
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        // Bundled page-1 reviews plus extra pages fetched in
                        // the background (allReviews) - more reviews appear as
                        // the additional pages land. `reviews` itself is read
                        // once above, so the chain's presence and this guard are
                        // the same value.
                        //
                        // Painted here, under Collection and More Like This and
                        // above the Media row: the reviews are an addendum to a
                        // title the viewer has already decided about, while the
                        // collection and the recommendations are how they find
                        // the next one (see DetailRow's painted order).
                        if (reviews.isNotEmpty()) {
                            item(key = "reviewsheader", contentType = "section-header") {
                                KBSectionHeader(
                                    title = "REVIEWS",
                                    modifier = Modifier.padding(
                                        start = KBScreenEdge,
                                        top = 14.dp
                                    )
                                )
                            }

                            item(key = REVIEWS_ROW_KEY, contentType = "section-row") {
                                LazyRow(
                                    contentPadding =
                                        PaddingValues(
                                            start = 24.dp,
                                            end = 24.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    modifier = Modifier
                                        .padding(
                                            bottom = 12.dp
                                        )
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    itemsIndexed(
                                        reviews.take(60),
                                        key = { _, review -> review.id }
                                    ) { reviewIndex, review ->
                                        val reviewFocusRequester =
                                            remember(review.id) {
                                                FocusRequester()
                                            }
                                        // Filed by id as well as by column, so the
                                        // fullscreen overlay's hand-back can find
                                        // THIS tile again at delivery time even if
                                        // its requester instance was rebuilt while
                                        // the overlay was up (see the page's
                                        // hand-back driver).
                                        RegisterRailRequester(
                                            railFocusRequesters,
                                            "review:${review.id}",
                                            reviewFocusRequester
                                        )
                                        // Filed by column like every rail in the
                                        // list, so a press into the review rail
                                        // from the More Like This rail above (or
                                        // the Media row below) is aimed at the
                                        // review in the column it came from (see
                                        // focusRow).
                                        registerChipColumn(
                                            REVIEWS_ROW_KEY,
                                            reviewIndex,
                                            reviewFocusRequester
                                        )

                                        ReviewCard(
                                            review = review,
                                            onClick = {
                                                lastReviewTileFocusRequester =
                                                    reviewFocusRequester
                                                selectedReview = it
                                            },
                                            modifier = Modifier
                                                .focusRequester(
                                                    reviewFocusRequester
                                                )
                                                // The review rail is a chain row
                                                // of its own now, so its cards
                                                // answer UP and DOWN through the
                                                // page's one handoff: UP reaches
                                                // More Like This, DOWN the Media
                                                // row (see DetailFocusChain).
                                                .onPreviewKeyEvent(
                                                    detailRowVerticalFocus(
                                                        DetailRow.REVIEWS,
                                                        reviewIndex,
                                                        detailRowPresence
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        // The Media row - the title's videos, its other
                        // backdrops and its posters - is painted LAST, under
                        // More Like This and the reviews: one row of the chain,
                        // several rails inside it.
                        if (hasMediaRow) {
                            item(key = "mediaheader", contentType = "section-header") {
                                KBSectionHeader(
                                    title = "MEDIA",
                                    modifier = Modifier.padding(
                                        start = KBScreenEdge,
                                        top = 14.dp
                                    )
                                )
                            }

                            // The strip, then the cards of the page it is on:
                            // one row of the chain, several rails inside it
                            // (see mediaStripFocus / mediaCardFocus).
                            item(key = MEDIA_STRIP_KEY, contentType = "section-row") {
                                MediaTabStrip(
                                    tabs = mediaTabs,
                                    activeIndex = mediaTabIndex,
                                    focusRequester = mediaStripFocusRequester,
                                    onKey = mediaStripFocus(
                                        tabs = mediaTabs,
                                        activeIndex = { mediaTabIndex },
                                        onActiveIndex = { index ->
                                            mediaTabIndex = index
                                        },
                                        onFocusContent = {
                                            // DOWN out of the strip: the page's
                                            // cards, in the column the section
                                            // remembers (see
                                            // focusFirstMediaCard).
                                            startFocusMove { focusFirstMediaCard() }
                                        },
                                        presence = detailRowPresence
                                    ),
                                    modifier = Modifier.padding(
                                        start = KBScreenEdge,
                                        bottom = 10.dp
                                    )
                                )
                            }

                            item(key = MEDIA_ROW_KEY, contentType = "section-row") {
                                // A fresh column map per page, parked under the
                                // row's key as the row composes: a card of the
                                // page the viewer just switched away from is
                                // disposed, and a disposed card's requester
                                // still sitting in the row's slots is exactly
                                // the "rail that exists but cannot answer" the
                                // vertical handoff would then have to guess
                                // about (see focusChipColumn).
                                val mediaRowColumns = remember(mediaTab) {
                                    mutableListOf<FocusRequester?>()
                                }
                                chipColumnRequesters[MEDIA_ROW_KEY] = mediaRowColumns

                                LazyRow(
                                    state = mediaRowState,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(
                                        start = 24.dp,
                                        end = 24.dp,
                                        top = 10.dp,
                                        bottom = 10.dp
                                    ),
                                    modifier = Modifier
                                        .padding(bottom = 14.dp)
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    itemsIndexed(
                                        mediaCards,
                                        key = { _, card -> card.id }
                                    ) { cardIndex, card ->
                                        val requester = remember(card.id) {
                                            FocusRequester()
                                        }
                                        // Filed by column like every other rail,
                                        // so a press into this one from the
                                        // review rail above it (More Like This
                                        // when there are none) is aimed at the
                                        // column it came from (see focusRow).
                                        registerChipColumn(
                                            MEDIA_ROW_KEY,
                                            cardIndex,
                                            requester
                                        )

                                        MediaThumbCard(
                                            imageUrl = card.imageUrl,
                                            badge = card.badge,
                                            contentDescription = card.title,
                                            // A poster draws portrait (see
                                            // MediaCardItem.portrait).
                                            portrait = card.portrait,
                                            // A video the player cannot speak
                                            // says so on the card (see
                                            // MediaThumbCard), so the press is
                                            // never a guess.
                                            unplayable = card.video != null &&
                                                !card.playable,
                                            onClick = {
                                                val video = card.video
                                                when {
                                                    // A video the trailer player
                                                    // cannot open: the card
                                                    // carries the mark, and the
                                                    // press resolves nothing.
                                                    // It used to build a
                                                    // youtube.com URL out of a
                                                    // Vimeo key and fail in a
                                                    // logged exception the
                                                    // viewer never saw - a dead
                                                    // press that looked like a
                                                    // broken remote.
                                                    video != null && !card.playable -> Unit
                                                    video != null ->
                                                        playTrailer(context, video)
                                                    else -> {
                                                        lastBackdropCardFocusRequester =
                                                            requester
                                                        selectedBackdrop =
                                                            card.fullImageUrl
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .focusRequester(requester)
                                                // Where the section's column
                                                // memory is kept current as the
                                                // viewer walks the cards, so
                                                // both the strip's DOWN and its
                                                // UP can give the column back
                                                // (see lastMediaCardColumn).
                                                .onFocusChanged { state ->
                                                    if (state.hasFocus) {
                                                        lastMediaCardColumn = cardIndex
                                                    }
                                                }
                                                // The row's own handoff: UP is
                                                // the tab strip, DOWN is the
                                                // chain's press out of the page.
                                                .onPreviewKeyEvent(
                                                    mediaCardFocus(
                                                        cardIndex,
                                                        onFocusStrip = {
                                                            startFocusMove {
                                                                focusMediaStrip(
                                                                    DetailRow.MEDIA
                                                                )
                                                            }
                                                        },
                                                        presence = detailRowPresence
                                                    )
                                                ),
                                            // The row's long press: which surface of
                                            // this title the pressed still becomes.
                                            // Only artwork offers one - a trailer
                                            // card carries no imagePath, and the
                                            // Trailers page offers no rows either.
                                            onLongClick = {
                                                if (card.imagePath != null) {
                                                    mediaImageMenuCardFocusRequester =
                                                        requester
                                                    mediaImageMenu = card
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "bottomspacer", contentType = "spacer") {
                            Box(
                                modifier = Modifier.height(40.dp)
                            )
                        }
                    }
                }

                // The Media row's backdrop press: the still, fullscreen, over a
                // scrim, and dismissed by Back or either vertical press. This
                // page's only destination-less zoom (see BackdropOverlay).
                selectedBackdrop?.let { backdrop ->
                    BackdropOverlay(
                        imageUrl = backdrop,
                        onDismiss = { dismissBackdropOverlay() }
                    )
                }

                // The Media row's long-press menu (see DetailMediaRow.overrideRows).
                // Rows are written with the SURFACE the card's own page keyed
                // them by: a still on the Backdrops page is a candidate for the
                // four surfaces that draw a backdrop, a poster for the one that
                // draws a poster everywhere.
                mediaImageMenu?.let { card ->
                    val menuType = if (mediaTab == MediaTab.POSTERS) {
                        ImageOverrides.TYPE_POSTER
                    } else {
                        ImageOverrides.TYPE_BACKDROP
                    }
                    PosterContextMenu(
                        title = tmdbDetail?.title
                            ?: tmdbDetail?.name
                            ?: id,
                        subtitle = mediaTab?.label,
                        actions = DetailMediaRow
                            .overrideRows(
                                tab = mediaTab,
                                hasOverride = ImageOverrides.hasOverride(
                                    paths = overridePaths,
                                    tmdbId = overrideTmdbId,
                                    type = menuType
                                )
                            )
                            .map { row ->
                                PosterContextAction(
                                    label = row.label,
                                    description = row.description,
                                    isDestructive = row.isDestructive,
                                    onClick = {
                                        val path = card.imagePath
                                        val targetId = overrideTmdbId
                                        if (path != null && targetId != null) {
                                            val surface = row.screen
                                            if (surface == null) {
                                                // "Reset to default": every
                                                // surface of the kind, since a
                                                // viewer asking for TMDB's art
                                                // back is asking about the title.
                                                ImageOverrides.reset(
                                                    context = context,
                                                    tmdbId = targetId,
                                                    type = menuType
                                                )
                                            } else {
                                                // Published by the store itself,
                                                // so the choice reaches the hero
                                                // on the next frame - this menu
                                                // is gone by then.
                                                ImageOverrides.choose(
                                                    context = context,
                                                    tmdbId = targetId,
                                                    type = menuType,
                                                    screen = surface,
                                                    imagePath = path
                                                )
                                            }
                                        }
                                        // A row press closes the menu, like every
                                        // other action in it - and hands focus
                                        // back to the card it was raised on.
                                        dismissMediaImageMenu()
                                    }
                                )
                            },
                        onDismiss = { dismissMediaImageMenu() }
                    )
                }

                selectedReview?.let { review ->
                    ReviewOverlay(
                        review = review,
                        onDismiss = {
                            dismissReviewOverlay()
                        }
                    )
                }

                // Long-press on a NETWORK or PRODUCTION chip: mirror it onto
                // Home exactly like a Search browse chip, so a network the
                // viewer keeps coming back to gets its own tile on Home. The
                // chip's own category decides the rail - a network lands on
                // the Services & Networks rail, a production company on the
                // Studios rail - so the tile joins the right row without the
                // menu having to choose one. A press already opens the chip's
                // screen, so this menu holds only what a press cannot say.
                studioChipMenu?.let { target ->
                    val railTitle =
                        com.kennyb1201.kbstream.data.kb
                            .browseShortcutRail(target.categoryKey)
                            ?.title
                            ?: "Browse"
                    PosterContextMenu(
                        title = target.name,
                        subtitle = if (target.categoryKey == "studios") {
                            "Production company"
                        } else {
                            "Network"
                        },
                        actions = listOf(
                            PosterContextAction(
                                label = if (target.onHome) {
                                    "Remove from Home"
                                } else {
                                    "Add to Home"
                                },
                                description = if (target.onHome) {
                                    "Take this chip off the $railTitle rail on Home"
                                } else {
                                    "Keep this chip on Home, in the $railTitle rail"
                                },
                                isDestructive = target.onHome
                            ) {
                                if (target.onHome) {
                                    BrowseHomeShortcuts.remove(
                                        chipMenuContext,
                                        target.categoryKey,
                                        target.id,
                                        target.name
                                    )
                                } else {
                                    BrowseHomeShortcuts.add(
                                        chipMenuContext,
                                        BrowseHomeShortcut(
                                            categoryKey = target.categoryKey,
                                            id = target.id,
                                            name = target.name
                                        )
                                    )
                                }
                                dismissStudioChipMenu()
                            }
                        ),
                        onDismiss = {
                            dismissStudioChipMenu()
                        }
                    )
                }

                posterMenu?.let { target ->
                    // Same key/type the rail badges use, so the toggle always
                    // matches what the poster currently shows.
                    val menuMediaType =
                        target.mediaType.lowercase()

                    // The IMDB flavor first, then the poster's own
                    // `tmdb:<n>` one, so the mark/unmark toggle matches a
                    // poster that badges off the unresolved flavor.
                    val isWatched =
                        resolvedPosterIds[
                            viewModel.posterLookupKey(
                                target.tmdbId,
                                menuMediaType
                            )
                        ]?.let { imdbId ->
                            viewModel.watchedKey(
                                imdbId,
                                menuMediaType
                            ) in watchedKeys
                        } == true ||
                            viewModel.watchedKey(
                                WatchedStatusKeys.tmdbKeyId(target.tmdbId),
                                menuMediaType
                            ) in watchedKeys

                    PosterContextMenu(
                        title = target.name.ifBlank { "Untitled" },
                        // The recommendation's TMDB id plus the IMDB one this
                        // screen already resolved for the poster badge, so an
                        // add from a recommendation reaches the trackers with
                        // the pair they key off instead of a TMDB id alone.
                        libraryTarget = LibraryAddTarget(
                            mediaType = target.mediaType,
                            imdbId = resolvedPosterIds[
                                viewModel.posterLookupKey(
                                    target.tmdbId,
                                    menuMediaType
                                )
                            ],
                            tmdbId = target.tmdbId,
                            title = target.name.ifBlank { "Untitled" },
                            posterUrl = viewModel.currentPosterUrl()
                        ),
                        hideTarget = hideTarget(
                            target.name.ifBlank { "Untitled" },
                            target.mediaType,
                            viewModel.currentPosterUrl(),
                            listOf(
                                "tmdb:${target.tmdbId}",
                                resolvedPosterIds[
                                    viewModel.posterLookupKey(
                                        target.tmdbId,
                                        menuMediaType
                                    )
                                ]
                            )
                        ),
                        actions = listOf(
                            PosterContextAction(
                                label = "Go to Details",
                                description = "Open this title's detail page"
                            ) {
                                val selected = target
                                posterMenu = null
                                selected.returnTarget?.let {
                                    DetailReturnFocus.record(it)
                                }
                                scope.launch {
                                    val imdbId = viewModel
                                        .resolveImdbId(
                                            selected.tmdbId,
                                            selected.mediaType
                                        )

                                    if (imdbId != null) {
                                        onNavigateDetail(
                                            selected.mediaType,
                                            imdbId
                                        )
                                    }
                                }
                            },
                            PosterContextAction(
                                label = watchedMenuLabel(
                                    isWatched = isWatched,
                                    mediaType = target.mediaType
                                ),
                                description = watchedMenuDescription(
                                    isWatched = isWatched,
                                    mediaType = target.mediaType
                                )
                            ) {
                                val selected = target
                                posterMenu = null
                                if (isWatched) {
                                    viewModel.markPosterUnwatched(
                                        selected.tmdbId,
                                        selected.mediaType
                                    )
                                } else {
                                    viewModel.markPosterWatched(
                                        selected.tmdbId,
                                        selected.mediaType
                                    )
                                }
                                handFocusBack("poster menu", lastPosterFocusRequester) {
                                    selected.returnTarget?.targetKey
                                        ?.let { railFocusRequesters[it] }
                                }
                            }
                        ),
                        onDismiss = {
                            dismissPosterMenu()
                        }
                    )
                }

                seasonMenu?.let { target ->
                    val seasonWatchedSet =
                        WatchedEpisodeState
                            .effectiveWatchedEpisodesForSeason(
                                parentId = id,
                                season = target.seasonNumber,
                                simklWatchedEpisodes =
                                    simklWatchedEpisodes,
                                watchedEpisodeKeys =
                                    watchedEpisodeKeys
                            )
                    val isSeasonWatched =
                        target.episodeNumbers.isNotEmpty() &&
                            target.episodeNumbers.all {
                                it in seasonWatchedSet
                            }
                    val menuContext = LocalContext.current

                    PosterContextMenu(
                        title = displayName.ifBlank {
                            "Untitled"
                        },
                        subtitle = target.seasonName,
                        actions = listOf(
                            PosterContextAction(
                                label = if (isSeasonWatched) {
                                    "Mark as Unwatched"
                                } else {
                                    "Mark as Watched"
                                }
                            ) {
                                val selected = target
                                seasonMenu = null
                                if (isSeasonWatched) {
                                    viewModel.markSeasonUnwatched(
                                        selected.seasonNumber,
                                        selected.episodeNumbers
                                    )
                                } else {
                                    viewModel.markSeasonWatched(
                                        selected.seasonNumber,
                                        selected.episodeNumbers
                                    )
                                }
                                handFocusBack("season menu", lastSeasonFocusRequester) {
                                    seasonFocusRequesters[target.seasonNumber]
                                }
                            },
                            PosterContextAction(
                                label = if (wholeSeriesWatched) {
                                    "Mark Entire Series as Unwatched"
                                } else {
                                    "Mark Entire Series as Watched"
                                },
                                description = if (wholeSeriesWatched) {
                                    "Clear every season on this device and your trackers"
                                } else {
                                    "Every season at once, not just this one"
                                }
                            ) {
                                seasonMenu = null
                                if (wholeSeriesWatched) {
                                    viewModel.markSeriesUnwatched()
                                } else {
                                    viewModel.markSeriesWatched(
                                        wholeSeriesEpisodeNumbers
                                    )
                                }
                                handFocusBack("season menu", lastSeasonFocusRequester) {
                                    seasonFocusRequesters[target.seasonNumber]
                                }
                            }
                        ) + listOfNotNull(
                            // Simkl's attribution rule, at the one place on this
                            // screen where their data is what the menu is about:
                            // the season it is marking is watched ACCORDING TO
                            // SIMKL when those ticks came from the account. Null
                            // (no Simkl record, no account, or a movie) means
                            // there is no item to link to.
                            simklPageUrl?.let { url ->
                                PosterContextAction(
                                    label = "View on Simkl",
                                    description = "Open this title's page on simkl.com"
                                ) {
                                    dismissSeasonMenu()
                                    try {
                                        menuContext.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        // No browser on this box: the row is a
                                        // link out, so there is nothing to show.
                                    }
                                }
                            }
                        ),
                        onDismiss = {
                            dismissSeasonMenu()
                        }
                    )
                }

                // The hero Play button's long-press menu. Both rows hand the
                // press back to the auto-play effect above (which owns the one
                // navigation into the picker) rather than building a second
                // copy of it here: the flag each row sets is what that effect
                // reads, and clearing `autoPlayed` lets it run again for a
                // title whose screen has already auto-played once.
                if (playButtonMenu) {
                    PosterContextMenu(
                        title = displayName.ifBlank { "Play" },
                        subtitle = playLabel,
                        // The title's own page is the most natural place to
                        // add it to My List; before this the only Library rows
                        // were on OTHER titles' poster menus (a More Like This
                        // or collection card), so a viewer had to leave the
                        // page they were on to save it. The shared menu adds
                        // the Add to Library / Add to list rows itself.
                        libraryTarget = LibraryAddTarget(
                            mediaType = normalizedType,
                            imdbId = id.takeIf { it.startsWith("tt") },
                            tmdbId = tmdbDetail?.id,
                            title = displayName.ifBlank { "Untitled" },
                            posterUrl = viewModel.currentPosterUrl()
                        ),
                        actions = listOf(
                            PosterContextAction(
                                label = "Play from Beginning",
                                description =
                                    "Start this title over, ignoring saved progress"
                            ) {
                                dismissPlayButtonMenu()
                                startOver = true
                                autoPlayed = false
                                playPress++
                            },
                            PosterContextAction(
                                label = "Play Manually",
                                description = "Pick a source instead of auto-selecting"
                            ) {
                                dismissPlayButtonMenu()
                                manualPick = true
                                autoPlayed = false
                                playPress++
                            }
                        ),
                        onDismiss = { dismissPlayButtonMenu() }
                    )
                }

                episodeMenu?.let { target ->
                    val seasonWatchedSet =
                        WatchedEpisodeState
                            .effectiveWatchedEpisodesForSeason(
                                parentId = id,
                                season = target.season,
                                simklWatchedEpisodes =
                                    simklWatchedEpisodes,
                                watchedEpisodeKeys =
                                    watchedEpisodeKeys
                            )
                    val isEpisodeWatched =
                        target.episode in seasonWatchedSet
                    val isSeasonWatched =
                        target.seasonEpisodeNumbers.isNotEmpty() &&
                            target.seasonEpisodeNumbers.all {
                                it in seasonWatchedSet
                            }

                    val menuActions =
                        buildList {
                            add(
                                PosterContextAction(
                                    label = if (isEpisodeWatched) {
                                        "Mark as Unwatched"
                                    } else {
                                        "Mark as Watched"
                                    }
                                ) {
                                    val selected = target
                                    episodeMenu = null
                                    if (isEpisodeWatched) {
                                        viewModel.markEpisodeUnwatched(
                                            selected.season,
                                            selected.episode
                                        )
                                    } else {
                                        // The stream id is this episode's
                                        // progress-row identity, so the
                                        // cleanup matches even if the
                                        // numbers disagree with the source.
                                        viewModel.markEpisodeWatched(
                                            selected.season,
                                            selected.episode,
                                            selected.streamId
                                        )
                                    }
                                    handFocusBack("episode menu", lastEpisodeFocusRequester) {
                                        episodeFocusRequesters[target.season to target.episode]
                                    }
                                }
                            )

                            if (target.episode > 1) {
                                add(
                                    PosterContextAction(
                                        label = if (isEpisodeWatched) {
                                            "Mark Previous as Unwatched"
                                        } else {
                                            "Mark Previous as Watched"
                                        }
                                    ) {
                                        val selected = target
                                        episodeMenu = null
                                        if (isEpisodeWatched) {
                                            viewModel.markPreviousUnwatched(
                                                selected.season,
                                                selected.episode
                                            )
                                        } else {
                                            viewModel.markPreviousWatched(
                                                selected.season,
                                                selected.episode
                                            )
                                        }
                                        handFocusBack("episode menu", lastEpisodeFocusRequester) {
                                            episodeFocusRequesters[target.season to target.episode]
                                        }
                                    }
                                )
                            }

                            add(
                                PosterContextAction(
                                    label = if (isSeasonWatched) {
                                        "Mark Season as Unwatched"
                                    } else {
                                        "Mark Season as Watched"
                                    }
                                ) {
                                    val selected = target
                                    episodeMenu = null
                                    if (isSeasonWatched) {
                                        viewModel.markSeasonUnwatched(
                                            selected.season,
                                            selected.seasonEpisodeNumbers
                                        )
                                    } else {
                                        viewModel.markSeasonWatched(
                                            selected.season,
                                            selected.seasonEpisodeNumbers
                                        )
                                    }
                                    handFocusBack("episode menu", lastEpisodeFocusRequester) {
                                        episodeFocusRequesters[target.season to target.episode]
                                    }
                                }
                            )

                            add(
                                PosterContextAction(
                                    label = if (wholeSeriesWatched) {
                                        "Mark Entire Series as Unwatched"
                                    } else {
                                        "Mark Entire Series as Watched"
                                    },
                                    description = if (wholeSeriesWatched) {
                                        "Clear every season on this device and your trackers"
                                    } else {
                                        "Every season at once, not just this one"
                                    }
                                ) {
                                    episodeMenu = null
                                    if (wholeSeriesWatched) {
                                        viewModel.markSeriesUnwatched()
                                    } else {
                                        viewModel.markSeriesWatched(
                                            wholeSeriesEpisodeNumbers
                                        )
                                    }
                                    handFocusBack("episode menu", lastEpisodeFocusRequester) {
                                        episodeFocusRequesters[target.season to target.episode]
                                    }
                                }
                            )

                            add(
                                PosterContextAction(
                                    label = "Play from Beginning",
                                    description =
                                        "Start this episode over from the beginning"
                                ) {
                                    val selected = target
                                    episodeMenu = null

                                    val epSuffix =
                                        selected.episodeTitle?.let {
                                            " • $it"
                                        } ?: ""

                                    // The same target the row below plays, with the
                                    // one difference that matters: position 0, plus
                                    // the flag that stops the player falling back to
                                    // the saved watch history.
                                    onNavigateStreams(
                                        StreamsTarget(
                                            contentType = "series",
                                            streamId =
                                                selected.streamId,
                                            title =
                                                "$displayName S${selected.season} E${selected.episode}$epSuffix",
                                            displayName =
                                                displayName,
                                            season =
                                                selected.season,
                                            episode =
                                                selected.episode,
                                            resumePositionMs = 0L,
                                            startFromBeginning = true,
                                            totalEpisodesInSeason = airedEpisodesInSeason,
                                            runtimeMinutes =
                                                selected.runtimeMinutes
                                                    ?.takeIf {
                                                        it > 0
                                                    }
                                        ),
                                        id,
                                        type,
                                        m.poster,
                                        backdropUrl,
                                        clearLogoUrl,
                                        selected.overview
                                            ?: m.description,
                                        tmdbDetail?.credits?.cast
                                            .orEmpty()
                                    )
                                }
                            )

                            add(
                                PosterContextAction(
                                    label = "Play Manually"
                                ) {
                                    val selected = target
                                    episodeMenu = null
                                    // Same rule as the episode card's click: resume
                                    // from whatever the progress bar showed for this
                                    // episode, not just the newest resume row.
                                    val resumeRow =
                                        inProgressByStreamId[selected.streamId]
                                            ?: resumeInfo
                                                ?.takeIf {
                                                    it.episodeStreamId ==
                                                        selected.streamId
                                                }

                                    val epSuffix =
                                        selected.episodeTitle?.let {
                                            " • $it"
                                        } ?: ""

                                    // Named apart from this screen's own
                                    // `playTarget`: this one is a single
                                    // episode row's id, already rooted by the
                                    // season listing, and never the target the
                                    // Play button paints.
                                    val episodePlayTarget =
                                        StreamsTarget(
                                            contentType = "series",
                                            streamId =
                                                selected.streamId,
                                            title =
                                                "$displayName S${selected.season} E${selected.episode}$epSuffix",
                                            displayName =
                                                displayName,
                                            season =
                                                selected.season,
                                            episode =
                                                selected.episode,
                                            resumePositionMs =
                                                resumeRow
                                                    ?.positionMs
                                                    ?: 0L,
                                            totalEpisodesInSeason = airedEpisodesInSeason,
                                            runtimeMinutes =
                                                resumeRow
                                                    ?.durationMs
                                                    ?.div(60_000L)
                                                    ?.toInt()
                                                    ?.takeIf {
                                                        it > 0
                                                    }
                                                    ?: selected.runtimeMinutes
                                                        ?.takeIf {
                                                            it > 0
                                                        }
                                        )

                                    onNavigateStreams(
                                        episodePlayTarget,
                                        id,
                                        type,
                                        m.poster,
                                        backdropUrl,
                                        clearLogoUrl,
                                        selected.overview
                                            ?: m.description,
                                        tmdbDetail?.credits?.cast
                                            .orEmpty()
                                    )
                                }
                            )
                        }

                    // Spoiler-free: the card that opened this menu draws an
                    // unstarted episode without its own name, and the heading has
                    // to agree - a heading carrying the real name is the same
                    // reveal, with the screen reader announcing it on top. The
                    // name stays on the target for the stream picker's label,
                    // which is built only after the viewer chose to play it.
                    val hidesEpisodeMenuTitle = SpoilerFree.hidesIdentity(
                        enabled = spoilerFreeEnabled,
                        watched = isEpisodeWatched,
                        started = inProgressByStreamId[target.streamId] != null ||
                            resumeInfo?.episodeStreamId == target.streamId
                    )
                    PosterContextMenu(
                        title = if (hidesEpisodeMenuTitle) {
                            SpoilerFree.episodeLabel(
                                hidden = true,
                                episodeNumber = target.episode,
                                realTitle = null
                            )
                        } else {
                            target.episodeTitle
                                ?.ifBlank { null }
                                ?: "Episode ${target.episode}"
                        },
                        subtitle = "S${target.season.toString().padStart(2, '0')} · E${target.episode.toString().padStart(2, '0')}",
                        actions = menuActions,
                        onDismiss = {
                            dismissEpisodeMenu()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailFactCard(
    fact: DetailFactItem,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = KBShapeChip,
        colors = SurfaceDefaults.colors(
            containerColor =
                KBSurfaceRaised.copy(alpha = 0.96f),
            contentColor = KBTextHi
        )
    ) {
        Column(
            modifier = Modifier
                // A wrapping fact gets room to be read: the two-line cap below
                // is what truncated it, but at 150dp even an uncapped sentence
                // would stack into a column taller than the posters above it.
                .width(if (fact.wrap) 280.dp else 150.dp)
                .padding(
                    horizontal = 11.dp,
                    vertical = 9.dp
                )
        ) {
            Text(
                text = fact.label.uppercase(),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = fact.value,
                color = KBTextHi,
                style = MaterialTheme.typography.bodySmall,
                // Only a wrapping fact may exceed two lines (see [wrap]):
                // everything else keeps the ellipsis it has always had.
                maxLines = if (fact.wrap) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SeasonChip(
    seasonNumber: Int,
    seasonName: String,
    isSelected: Boolean,
    available: Boolean = true,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // A chip, not a card: the season row is a strip of interactive chips, so it
    // follows the chip treatment (10dp corner, 1.06 focus) rather than the
    // 12dp/1.03 card scale it shared with the posters around it.
    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        focusedScale = KBFocusChip,
        shape = KBShapeChip,
        modifier = modifier.onFocusChanged {
            if (it.isFocused) {
                onFocus()
            }
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = seasonName,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    when {
                        isSelected -> KBAccent
                        // Dimmed label for announced-but-not-yet-released
                        // seasons (their premiere year is in the chip text).
                        else -> if (available) KBTextHi else KBTextLo
                    },
                fontWeight =
                    if (isSelected) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                modifier = Modifier.padding(
                    start = 12.dp,
                    top = 7.dp,
                    end = 12.dp
                )
            )

            Box(
                modifier = Modifier
                    .padding(
                        top = 3.dp,
                        bottom = 6.dp
                    )
                    .width(20.dp)
                    .height(2.dp)
                    .background(
                        if (isSelected) {
                            KBAccent
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

@Composable
private fun SeasonRow(
    state: LazyListState,
    seasons: List<Int>,
    seasonPremiereDates: Map<Int, LocalDate>,
    today: LocalDate,
    currentSelectedSeason: Int?,
    onSeasonSelected: (Int) -> Unit,
    onSeasonFocused: (Int) -> Unit,
    seasonFocusRequesters: MutableMap<Int, FocusRequester>,
    parentId: String,
    watchedEpisodeKeys: Set<String>,
    simklWatchedEpisodes: Set<Pair<Int, Int>>,
    seasonEpisodeNumbersFor: (Int) -> List<Int>?,
    onSeasonLongPress: (
        Int,
        String,
        List<Int>,
        FocusRequester
    ) -> Unit
) {
    LazyRow(
        state = state,
        contentPadding = PaddingValues(
            start = 24.dp,
            end = 24.dp,
            top = 6.dp,
            bottom = 6.dp
        ),
        modifier = Modifier
            .padding(bottom = 10.dp)
            .focusGroup()
    ) {
        items(
            items = seasons,
            key = { it }
        ) { season ->
            val selected =
                season == currentSelectedSeason

            val seasonPremiere =
                seasonPremiereDates[season]

            val seasonUnavailable =
                seasonPremiere?.isAfter(today) == true

            val chipFocusRequester =
                remember(season) {
                    FocusRequester()
                }

            seasonFocusRequesters[
                season
            ] = chipFocusRequester

            val seasonName =
                when {
                    season == 0 -> "SPECIALS"
                    seasonUnavailable -> "SEASON $season \u00b7 ${seasonPremiere?.year}"
                    else -> "SEASON $season"
                }

            val episodeNumbers =
                seasonEpisodeNumbersFor(season)

            val watchedSet =
                if (episodeNumbers == null) {
                    emptySet<Int>()
                } else {
                    WatchedEpisodeState
                        .effectiveWatchedEpisodesForSeason(
                            parentId = parentId,
                            season = season,
                            simklWatchedEpisodes =
                                simklWatchedEpisodes,
                            watchedEpisodeKeys =
                                watchedEpisodeKeys
                        )
                }

            val isSeasonWatched =
                episodeNumbers != null &&
                    episodeNumbers.isNotEmpty() &&
                    episodeNumbers.all {
                        it in watchedSet
                    }

            SeasonChip(
                seasonNumber = season,
                seasonName = seasonName,
                isSelected = selected,
                available = !seasonUnavailable,
                onClick = {
                    onSeasonSelected(season)
                },
                onFocus = {
                    onSeasonFocused(season)
                },
                onLongClick = if (seasonUnavailable || episodeNumbers.isNullOrEmpty()) {
                    // No episodes to mark yet - hide the watch-menu gesture
                    // on not-yet-released seasons.
                    //
                    // ...and on a season no source can enumerate. The old
                    // fallback was `1..highest already watched`, so the menu
                    // offered "Mark as Watched" for a list that stopped at the
                    // last tick and marked nothing new - and because
                    // isSeasonWatched is false whenever the numbers are null,
                    // it offered that row even for a season the viewer had
                    // finished. With no episode set there is nothing honest to
                    // offer, so the gesture is withheld rather than guessing.
                    null
                } else {
                    {
                        onSeasonLongPress(
                            season,
                            seasonName,
                            episodeNumbers,
                            chipFocusRequester
                        )
                    }
                },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .focusRequester(
                        chipFocusRequester
                    )
            )
        }
    }
}

@Composable
private fun CastCard(
    member: TmdbCastMember,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember {
        mutableStateOf(false)
    }

    // Reduced motion: the portrait does not zoom. It also took the default
    // spring where every sibling focus cue in the app uses a short tween, so
    // the same press eased differently here than on the poster beside it.
    val reducedMotion = rememberReducedMotion()
    val imageScale by animateFloatAsState(
        targetValue =
            if (isFocused && !reducedMotion) 1.08f else 1f,
        animationSpec = tween(rememberMotionMs(KB_ALPHA_FADE_MS)),
        label = "castImageScale"
    )

    val posterUrl = remember(
        member.profilePath
    ) {
        member.profilePath?.let {
            // w342 gives enough pixels for the 88dp circle + 1.08f focus
            // scale without looking grainy on high-density TV panels.
            "https://image.tmdb.org/t/p/w342$it"
        }
    }

    val initials = remember(member.name) {
        member.name
            .trim()
            .split(" ")
            .mapNotNull {
                it.firstOrNull()?.uppercaseChar()
            }
            .take(2)
            .joinToString("")
    }

    Column(
        horizontalAlignment =
            Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .padding(end = 16.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(
                shape = CircleShape
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = KBSurfaceRaised,
                contentColor = KBTextHi,
                focusedContainerColor =
                    KBSurfaceRaised,
                focusedContentColor = KBTextHi,
                pressedContainerColor =
                    KBSurfaceRaised,
                pressedContentColor = KBTextHi
            ),
            scale = ClickableSurfaceDefaults.scale(
                // No growth: the cast rail packs several circles per row, and growing
                // one would shove its neighbors; the press-in still gives feedback.
                focusedScale = KBFocusNone,
                pressedScale = KBFocusPressed
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(
                        1.dp,
                        KBTextLo.copy(alpha = 0.25f)
                    ),
                    shape = CircleShape
                ),
                focusedBorder = Border(
                    // 2dp, the ring every focused surface in the app draws; a
                    // cast circle is a row-class target, not a heavier one.
                    border = BorderStroke(
                        2.dp,
                        KBAccent
                    ),
                    shape = CircleShape
                )
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = KBAccent,
                    elevation = KBFocusGlowSmall
                )
            ),
            modifier = modifier
                .size(88.dp)
                .onFocusChanged {
                    isFocused = it.isFocused
                }
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    // No ImageRequest and no crossfade: this rail paints a
                    // row of circles at once, so it takes the loader's
                    // no-fade default (see MainApplication.newImageLoader).
                    model = posterUrl,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        // graphicsLayer, not Modifier.scale: the focus
                        // animation reads imageScale INSIDE the layer block,
                        // so it invalidates the layer per frame instead of
                        // recomposing this card (and re-resolving its image)
                        // on every focus transition.
                        .graphicsLayer {
                            scaleX = imageScale
                            scaleY = imageScale
                        }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        style =
                            MaterialTheme.typography.titleLarge,
                        color = KBTextLo
                    )
                }
            }
        }

        Text(
            member.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style =
                MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
        )

        member.character?.let {
            Text(
                it,
                color = KBTextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style =
                    MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PeopleSeparatorCard() {
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(88.dp)
            .padding(end = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(6.dp)
                .background(
                    KBTextLo.copy(alpha = 0.7f),
                    CircleShape
                )
        )
    }
}

@Composable
private fun EpisodeCard(
    ep: ResolvedEpisode,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    fallbackImageUrl: String? = null,
    progressFraction: Float = 0f,
    /**
     * False when TMDB's dates for this season are known to be contradicted
     * elsewhere (see seasonsWithStaleDates): a future air date then no longer
     * marks the card UNAVAILABLE, because the row is stale rather than the
     * episode unaired.
     */
    airDatesTrusted: Boolean = true
) {
    val posterUrl = remember(ep.thumbnail, fallbackImageUrl) {
        ep.thumbnail?.takeIf {
            it.isNotBlank()
        } ?: fallbackImageUrl?.takeIf {
            it.isNotBlank()
        } ?: ""
    }

    val isUnavailable = remember(ep.airDate, airDatesTrusted) {
        airDatesTrusted && isEpisodeUnavailable(ep.airDate)
    }

    // Spoiler-free mode. An episode the viewer has not started is listed
    // without its own identity - no name, no synopsis, no frame from it - so
    // browsing a season cannot hand them a plot they have not reached. Watched
    // episodes and the one they are part-way through keep everything: the
    // first is behind them, the second is where they left off. Read through the
    // synced revision, so a mode switched on another device un-hides (or hides)
    // the cards already on screen rather than at the next screen entry.
    val spoilerFreeContext = LocalContext.current
    val spoilerFreeRevision by com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
        .revision.collectAsStateWithLifecycle()
    val spoilerFree = remember(spoilerFreeContext, spoilerFreeRevision) {
        AppPreferences.getSpoilerFree(spoilerFreeContext)
    }
    val hidesSpoiler = SpoilerFree.hidesIdentity(
        enabled = spoilerFree,
        watched = isWatched,
        started = progressFraction > 0f
    )
    val listedTitle = if (hidesSpoiler) {
        SpoilerFree.episodeLabel(
            hidden = true,
            episodeNumber = ep.episodeNumber,
            realTitle = ep.name
        )
    } else {
        ep.name ?: ""
    }

    PosterCard(
        posterUrl = posterUrl,
        // Never the real name when hidden: a screen reader announcing "The
        // Funeral" is the same spoiler the visible title is.
        contentDescription = listedTitle,
        // The spoiler cover below (a 0.94-alpha void scrim over the artwork)
        // is what guarantees an unwatched episode's frame is never legible, on
        // every API level. The per-frame RenderEffect blur that used to soften
        // it on API 31+ is gone: it re-evaluated while this episode row
        // scrolled, and under a 94% scrim it was not visible anyway.
        isWatched = isWatched,
        // An unaired episode still draws the UNAVAILABLE badge; it must not
        // also be playable. Clicking it used to launch source resolution for an
        // episode that has not aired, which then failed with a source error.
        onClick = { if (!isUnavailable) onClick() },
        onLongClick = if (isUnavailable) null else onLongClick,
        modifier = modifier
            .width(260.dp)
            .height(170.dp)
            .padding(end = 10.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (hidesSpoiler) {
                // The guarantee, on every API level, that no frame of an
                // unwatched episode is legible - blurred artwork above it
                // notwithstanding, since the blur is a platform no-op below
                // API 31.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(KBVoid.copy(alpha = 0.94f))
                )
                Surface(
                    shape = KBShapeChip,
                    colors = SurfaceDefaults.colors(
                        containerColor = KBVoid.copy(alpha = 0.85f)
                    ),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(
                            horizontal = 5.dp,
                            vertical = 1.dp
                        )
                    ) {
                        Text(
                            text = SpoilerFree.HIDDEN_STILL_LABEL,
                            style = MaterialTheme.typography.labelSmall,
                            color = KBTextLo
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                KBVoid.copy(alpha = 0.2f),
                                KBVoid.copy(alpha = 0.7f),
                                KBVoid.copy(alpha = 0.95f)
                            )
                        )
                    )
            )

            if (isUnavailable) {
                Surface(
                    shape =
                        KBShapeChip,
                    colors =
                        SurfaceDefaults.colors(
                            containerColor =
                                KBTextLo.copy(
                                    alpha = 0.35f
                                )
                        ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(
                            horizontal = 5.dp,
                            vertical = 1.dp
                        )
                    ) {
                        Text(
                            text =
                                "UNAVAILABLE",
                            style =
                                MaterialTheme.typography.labelSmall,
                            color = KBTextHi
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement =
                    Arrangement.SpaceBetween
            ) {
                Column {
                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(
                            bottom = 4.dp
                        )
                    ) {
                        Surface(
                            shape =
                                KBShapeChip,
                            colors =
                                SurfaceDefaults.colors(
                                    containerColor =
                                        KBVoid.copy(
                                            alpha = 0.75f
                                        )
                                )
                        ) {
                            Box(
                                modifier = Modifier.padding(
                                    horizontal = 5.dp,
                                    vertical = 1.dp
                                )
                            ) {
                                Text(
                                    text =
                                        "EPISODE ${ep.episodeNumber}",
                                    style =
                                        MaterialTheme.typography.labelSmall,
                                    color = KBTextHi
                                )
                            }
                        }
                    }

                    (if (hidesSpoiler) listedTitle else ep.name)?.let { episodeName ->
                        Text(
                            text = episodeName,
                            color = KBTextHi,
                            style =
                                MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow =
                                TextOverflow.Ellipsis
                        )
                    }
                }

                (if (hidesSpoiler) {
                    SpoilerFree.HIDDEN_SYNOPSIS
                } else {
                    ep.overview?.takeIf { it.isNotBlank() }
                })
                    ?.let { overviewText ->
                        Text(
                            text = overviewText,
                            color = KBTextLo,
                            style =
                                MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow =
                                TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                vertical = 3.dp
                            )
                        )
                    }

                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ep.runtimeMinutes?.let {
                        runtime ->
                        // In-progress episodes show time-left instead of
                        // total runtime, matching the Home continue-watching
                        // card's "42m left" / "1h 12m left" formatting.
                        val runtimeLabel =
                            if (progressFraction > 0f && progressFraction < 1f) {
                                val remaining =
                                    (runtime * (1f - progressFraction))
                                        .toInt()
                                        .coerceAtLeast(1)
                                "${formatRuntimeMinutes(remaining)} left"
                            } else {
                                formatRuntimeMinutes(runtime)
                            }
                        Text(
                            text = "🕒 $runtimeLabel",
                            color = KBTextLo,
                            style =
                                MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(
                                Alignment.CenterStart
                            )
                        )
                    }

                    // No score on an UNAVAILABLE card. A future episode's
                    // TMDB vote average is computed from a handful of early
                    // votes, so it lands on flat junk (a 2.0 is common) and
                    // reads as a real rating next to the badge saying the
                    // episode is not out yet.
                    val rating =
                        ep.voteAverage
                            ?.takeIf { !isUnavailable }

                    if (
                        rating != null &&
                        rating > 0.0
                    ) {
                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(6.dp),
                            verticalAlignment =
                                Alignment.CenterVertically,
                            modifier = Modifier.align(
                                Alignment.Center
                            )
                        ) {
                            Surface(
                                shape =
                                    KBShapeChip,
                                // The brand's own yellow, from the shared const
                                // the MDBList rating chips use (this was a
                                // second literal of the same value).
                                colors =
                                    SurfaceDefaults.colors(
                                        containerColor = KBImdbTint
                                    )
                            ) {
                                Box(
                                    modifier =
                                        Modifier.padding(
                                            horizontal = 3.dp,
                                            vertical = 1.dp
                                        )
                                ) {
                                    Text(
                                        text = "IMDb",
                                        color =
                                            Color.Black,
                                        style =
                                            MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            Text(
                                text =
                                    "%.1f".format(
                                        rating
                                    ),
                                color = KBTextHi,
                                style =
                                    MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    ep.airDate
                        ?.takeIf { it.isNotBlank() }
                        ?.let { airDate ->
                            Text(
                                text =
                                    formatEpisodeAirDate(
                                        airDate
                                    ),
                                color = KBTextLo,
                                style =
                                    MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow =
                                    TextOverflow.Ellipsis,
                                modifier = Modifier.align(
                                    Alignment.CenterEnd
                                )
                            )
                        }
                }

                if (progressFraction > 0f) {
                    KBProgressBar(progress = progressFraction)
                }
            }
        }
    }
}

@Composable
private fun GenreChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(
            shape = KBShapePill
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor =
                KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor =
                KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusChip,
            pressedScale = KBFocusPressed
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(
                    2.dp,
                    KBAccent
                ),
                shape = KBShapePill
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                // Chip glow, not the card's: this strip is chips (see
                // KBFocusGlowSmall in Theme.kt).
                elevation = KBFocusGlowSmall
            )
        )
    ) {
        Text(
            name,
            style =
                MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 6.dp
            )
        )
    }
}

@Composable
private fun KeywordChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(
            shape = KBShapePill
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = KBTextLo,
            focusedContainerColor =
                KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor =
                KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusChip,
            pressedScale = KBFocusPressed
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    KBTextLo.copy(alpha = 0.35f)
                ),
                shape = KBShapePill
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    2.dp,
                    KBAccent
                ),
                shape = KBShapePill
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = KBFocusGlowSmall
            )
        )
    ) {
        Text(
            "#$name",
            // A chip label, like the genre chip beside it.
            style =
                MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = 11.dp,
                vertical = 5.dp
            )
        )
    }
}

@Composable
private fun EpisodesStatusMessage(
    icon: String,
    message: String
) {
    // The app's one status card, inline in the episodes list. The old
    // hand-rolled chip row was the last browse-style status outside
    // KBStatusMessage; its "⏳" glyph is now the shared loading flag.
    KBStatusMessage(
        loading = icon == "⏳",
        icon = icon,
        message = message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
    )
}

/**
 * Shared poster tile used by both the collection row and
 * recommendations row.
 */
@Composable
private fun PosterGridCard(
    posterPath: String?,
    contentDescription: String,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPartiallyWatched: Boolean = false,
    captionYear: String? = null,
    captionRating: Double? = null,
    // Raw id + type for the shared landscape-art resolver, so a recommendation
    // or collection part (which carries only a poster path) still draws a real
    // backdrop with a corner clearlogo when landscape mode is on.
    artId: String? = null,
    artType: String? = null
) {
    // The Column must be pinned to the poster width: a LazyRow measures
    // children with unbounded width, so an unconstrained caption would let
    // single-line titles run wide and overlap the next tile.

    // The whole tile — poster plus caption — owns the focus state, so the
    // caption can marquee while the tile it belongs to is focused.
    var focused by remember { mutableStateOf(false) }
    // Follow the app-wide Settings poster size so these rails match every
    // other poster surface.
    val posterSize = rememberPosterSize()
    // Landscape tiles are wider than the poster they replace, so the tile's own
    // container follows the shape the card is about to draw.
    val tileWidth = rememberPosterTileWidth(posterSize.width)

    Column(
        modifier = Modifier
            .width(tileWidth)
            .onFocusChanged { focused = it.hasFocus }
    ) {
        GlobalPosterCard(
            posterUrl = remember(posterPath) {
                posterPath?.let {
                    TmdbRepository.POSTER_BASE + it
                }
            },
            contentDescription = contentDescription,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            posterWidth = posterSize.width,
            posterHeight = posterSize.height,
            artId = artId,
            artType = artType,
            modifier = modifier
        )

        PosterCaptions(
            title = contentDescription,
            year = captionYear,
            rating = captionRating,
            focused = focused,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
        )
    }
}

/**
 * Snap the detail list back to its top when focus returns to the header
 * region (title/logo, Play/Trailer row) above the LazyColumn. The overview
 * is the list's first, non-focusable item: walking focus back up used to
 * keep the old scroll offset, leaving the overview permanently scrolled out
 * of view. Composable so it can read `detailListState` composition-locally.
 *
 * The snap is the one thing on this page that moves the list on its own, so
 * it is spent only on the focus it was written for: the viewer WALKING UP to
 * the header. It used to fire on any focus the header reported, and the field
 * report (0.9.36) is what that costs - long-press an episode card, mark it
 * watched, and the page scrolled up to the PLAY button while the hand-back put
 * the focus back on the card: the overlay's focused node leaves the tree in
 * the same recomposition that records the hand-back, the header is the row the
 * framework picks while the ask is still a frame away, and this modifier turned
 * that one transient frame into a full `animateScrollToItem(0)` - which then
 * kept animating after the focus was already back on the card. Two gates:
 *
 *  - [canSnap] is false while a hand-back is pending, and a pending hand-back
 *    means focus is being delivered to a recorded surface (the card that raised
 *    the menu) rather than being left here by the viewer;
 *  - the snap waits a frame and re-reads the header's focus, so a focus that
 *    lands and leaves again within that frame can never move the page. A snap
 *    that already started is cancelled the moment the header loses focus, and
 *    the check is repeated on the way in for a focus the gate could not predict.
 */
@Composable
private fun Modifier.scrollToTopOnFocus(
    listState: LazyListState,
    scope: kotlinx.coroutines.CoroutineScope,
    canSnap: () -> Boolean
): Modifier {
    var headerFocused by remember { mutableStateOf(false) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    return this.onFocusChanged { state ->
        headerFocused = state.hasFocus
        if (!state.hasFocus) {
            // Whatever was travelling belongs to the focus that just left the
            // header: let it stop here rather than settle the page somewhere
            // nobody asked for.
            snapJob?.cancel()
            return@onFocusChanged
        }
        val scrolled =
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
        if (!scrolled || snapJob?.isActive == true) return@onFocusChanged
        snapJob =
            scope.launch {
                awaitFrame()
                // A transient focus has been replaced by now (the hand-back asks
                // one frame after the dismissal), so this is the viewer's own
                // arrival at the header and only it may move the list.
                if (!headerFocused || !canSnap()) return@launch
                listState.animateScrollToItem(0)
            }
    }
}

/**
 * One review tile. [modifier] is where the rail hangs its focus requester and
 * the page's vertical key handler (see DetailScreen's review rail), so the
 * card is reachable by D-pad like every other row.
 */
@Composable
private fun ReviewCard(
    review: TmdbReview,
    onClick: (TmdbReview) -> Unit,
    modifier: Modifier = Modifier
) {
    KBCard(
        onClick = {
            onClick(review)
        },
        modifier = modifier
            .width(240.dp)
            .padding(end = 10.dp)
    ) {
        Column(
            modifier = Modifier.padding(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = review.author,
                    style =
                        MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                review.authorDetails?.rating?.let { rating ->
                    Text(
                        text = "\u2605 %.1f".format(rating),
                        color = KBAccent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            // Spoiler-flagged reviews never leak text on the card: the
            // preview shows a fixed gate notice (no excerpt), and the full
            // text is only revealed inside the overlay after opt-in.
            if (review.spoiler) {
                Text(
                    text = "Spoiler \u2014 click to reveal",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Text(
                    text = review.content,
                    color = KBTextLo,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        top = 6.dp
                    )
                )
            }
        }
    }
}

@Composable
private fun ReviewOverlay(
    review: TmdbReview,
    onDismiss: () -> Unit
) {
    val scrollFocusRequester =
        remember { FocusRequester() }

    val scrollState = ScrollState(0)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scrollFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                KBVoid.copy(alpha = 0.88f)
            )
            .onKeyEvent { keyEvent ->
                if (
                    keyEvent.type !=
                        KeyEventType.KeyDown
                ) {
                    return@onKeyEvent false
                }

                when (keyEvent.key) {
                    Key.Back,
                    Key.Escape -> {
                        onDismiss()
                        true
                    }

                    Key.DirectionDown -> {
                        scope.launch {
                            scrollState.scrollTo(
                                scrollState.value + 220
                            )
                        }
                        true
                    }

                    Key.DirectionUp -> {
                        scope.launch {
                            scrollState.scrollTo(
                                (
                                    scrollState.value - 220
                                ).coerceAtLeast(0)
                            )
                        }
                        true
                    }

                    else -> false
                }
            }
    ) {
        Card(
            onClick = {},
            colors = CardDefaults.colors(
                containerColor =
                    KBSurfaceRaised,
                contentColor = KBTextHi
            ),
            modifier = Modifier
                .padding(
                    horizontal = 64.dp,
                    vertical = 40.dp
                )
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Column(
                        modifier =
                            Modifier.weight(1f)
                    ) {
                        Text(
                            text = review.author,
                            style =
                                MaterialTheme.typography.headlineMedium
                        )

                        review.authorDetails?.rating?.let {
                            rating ->
                            Text(
                                text =
                                    "%.1f".format(
                                        rating
                                    ),
                                color = KBAccent,
                                style =
                                    MaterialTheme.typography.bodyMedium,
                                modifier =
                                    Modifier.padding(
                                        top = 4.dp
                                    )
                            )
                        }

                        review.createdAt
                            ?.substringBefore("T")
                            ?.let {
                                formatDisplayDate(it)
                            }
                            ?.let { createdAt ->
                                Text(
                                    text = createdAt,
                                    color = KBTextLo,
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                    modifier =
                                        Modifier.padding(
                                            top = 4.dp
                                        )
                                )
                            }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .focusRequester(
                            scrollFocusRequester
                        )
                        .focusable()
                ) {
                    // Spoiler gate inside the overlay: the review body stays
                    // blurred out until the user clicks (D-pad OK/enter).
                    // Revealing is per-session local state, never persisted.
                    var spoilerRevealed by remember(review.id) {
                        mutableStateOf(false)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(
                                scrollState
                            )
                    ) {
                        if (review.spoiler && !spoilerRevealed) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(KBShapeCard)
                                    .background(KBSurface)
                                    .border(
                                        1.dp,
                                        KBAccent.copy(alpha = 0.45f),
                                        KBShapeCard
                                    )
                                    .clickable { spoilerRevealed = true }
                                    .padding(horizontal = 18.dp, vertical = 22.dp)
                            ) {
                                Text(
                                    text = "This review contains spoilers." +
                                        " Click to reveal.",
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                        Text(
                            text = review.content,
                            color = KBTextLo,
                            style =
                                MaterialTheme.typography.bodyLarge,
                            modifier =
                                Modifier.padding(
                                    bottom = 32.dp
                                )
                        )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Parses an ISO-8601 date string (e.g. TMDB's "yyyy-MM-dd")
 * and formats it as "MM/dd/yyyy".
 */
private fun formatDisplayDate(
    raw: String?
): String? {
    val trimmed = raw?.trim()

    if (trimmed.isNullOrBlank()) {
        return null
    }

    return runCatching {
        LocalDate.parse(trimmed)
            .format(DateFormats.DISPLAY_DATE)
    }.getOrDefault(trimmed)
}

/**
 * An episode's air date as the cards print it.
 *
 * Internal rather than private because the phone's episode rail draws the same
 * line: the two surfaces must format one date the same way, and the TV page is
 * where every episode-card rule lives (see [episodeAirDate]).
 */
internal fun formatEpisodeAirDate(
    raw: String
): String =
    episodeAirDate(raw)
        ?.format(DateFormats.DISPLAY_DATE)
        ?: formatDisplayDate(raw)
        ?: raw

/**
 * The calendar day [raw] names, or null when it names none.
 *
 * Two shapes reach an episode card's [ResolvedEpisode.airDate]: TMDB's plain
 * `yyyy-MM-dd`, and - for a title TMDB has no record for, whose seasons are
 * synthesized from the add-on's own `Meta.videos` - Stremio's full ISO-8601
 * stamp (`2026-11-01T00:00:00.000Z`). The card used to parse only the first
 * shape, so an unreleased add-on episode's date failed to parse and read as
 * "aired": no UNAVAILABLE badge and a playable card that then died resolving a
 * stream for an episode nobody has. Accept either shape, and a local timestamp
 * with no offset too; a truly unreadable value stays null, never a guess.
 */
private fun episodeAirDate(
    raw: String?
): LocalDate? {
    val trimmed = raw?.trim().orEmpty()

    if (trimmed.isEmpty()) {
        return null
    }

    return runCatching {
        LocalDate.parse(trimmed)
    }.recoverCatching {
        OffsetDateTime.parse(trimmed).toLocalDate()
    }.recoverCatching {
        LocalDateTime.parse(trimmed).toLocalDate()
    }.getOrNull()
}

/**
 * True only when the episode has an announced air date that's still
 * in the future. A missing/unparseable air date is NOT treated as
 * unavailable -- TMDB just doesn't always have one for already-aired
 * episodes, and we don't want to mislabel those.
 *
 * [today] is injectable so the boundary can be checked without a clock; the
 * episode cards take the default.
 */
internal fun isEpisodeUnavailable(
    airDate: String?,
    today: LocalDate = LocalDate.now()
): Boolean {
    val date = episodeAirDate(airDate) ?: return false
    return date.isAfter(today)
}

/**
 * How many of a season listing have actually aired - the total behind every
 * "X of Y aired episodes watched" line and every per-season episode total a
 * history row stores.
 *
 * The loaded season lists unaired episodes on purpose (their cards are drawn
 * greyed rather than dropped, see [isEpisodeUnavailable]), so the list's own
 * size is not the aired count. Counting the whole list is what made a show with
 * two scheduled episodes left read "14 of 16 aired episodes watched" instead of
 * "14 of 14" - and, because the same number is what a resume row stores as its
 * season total, what kept such a show looking unfinished in Continue Watching
 * once every aired episode had been watched.
 *
 * [airDatesTrusted] is false for a season whose dates are contradicted
 * elsewhere (see `seasonsWithStaleDates`): a future date there means the ROW is
 * stale rather than the episode unaired, so the list is left alone instead of
 * guessed at - the same rule the season mark/unmark path applies. The rule is
 * one-sided on purpose: an episode is only ever removed from the total when a
 * date proves it has not aired, so a missing or unparseable date keeps its
 * episode counted ([isEpisodeUnavailable] returns false for both).
 *
 * [today] is injectable so the boundary can be checked without a clock; every
 * caller in the app takes the default.
 */
internal fun airedEpisodeCount(
    episodes: List<ResolvedEpisode>,
    airDatesTrusted: Boolean = true,
    today: LocalDate = LocalDate.now()
): Int =
    if (airDatesTrusted) {
        episodes.count { episode -> !isEpisodeUnavailable(episode.airDate, today) }
    } else {
        episodes.size
    }

/**
 * `$12,000,000` - the TV meta line's money format, shared with the phone's
 * facts block so the two pages cannot word a budget two ways.
 */
internal fun formatUsd(
    amount: Long
): String =
    NumberFormat
        .getCurrencyInstance(Locale.US)
        .format(amount)

private fun extractYear(
    value: String?
): String? =
    value
        ?.takeIf { it.isNotBlank() }
        ?.take(4)
        ?.takeIf {
            it.length == 4 &&
                it.all(Char::isDigit)
        }

/**
 * `2022-` while a series is still running, `2019-2023` once it has stopped.
 *
 * Internal rather than private because the phone's Show Details block prints
 * the same span under its Release Info row: one rule, two layouts.
 */
internal fun formatSeriesYearRange(
    firstAirDate: String?,
    lastAirDate: String?,
    status: String?
): String? {
    val startYear =
        extractYear(firstAirDate)

    val endYear =
        extractYear(lastAirDate)

    val normalizedStatus =
        status?.trim().orEmpty()

    if (startYear == null) {
        return null
    }

    return when {
        normalizedStatus.equals(
            "Returning Series",
            ignoreCase = true
        ) ||
            normalizedStatus.equals(
                "In Production",
                ignoreCase = true
            ) ->
            "$startYear-"

        !endYear.isNullOrBlank() &&
            endYear != startYear ->
            "$startYear-$endYear"

        !endYear.isNullOrBlank() ->
            endYear

        else ->
            "$startYear-"
    }
}

private fun seriesStatusTag(status: String?): String? {
    val normalizedStatus = status?.trim().orEmpty()
    return when {
        normalizedStatus.equals("Returning Series", ignoreCase = true) -> "ONGOING"
        normalizedStatus.equals("In Production", ignoreCase = true) -> "IN PRODUCTION"
        normalizedStatus.equals("Planned", ignoreCase = true) -> "PLANNED"
        normalizedStatus.equals("Canceled", ignoreCase = true) ||
        normalizedStatus.equals("Canceled", ignoreCase = true) -> "CANCELED"
        normalizedStatus.equals("Ended", ignoreCase = true) -> "ENDED"
        else -> null
    }
}

/**
 * The Media row's tab strip: the row's pages as a row of pills, the active
 * one tinted.
 *
 * ONE focus node rather than one per tab, and that is the whole design: the tab
 * a viewer is on is the strip's own selection, walked by LEFT/RIGHT (see
 * mediaStripFocus), not a focus position. Two focusable tabs would put the two
 * pages on opposite ends of a D-pad press the default search resolves
 * geometrically, which is the same geometric guess the page's chip rails had to
 * stop making; with one node there is nothing sideways to guess about.
 *
 * Focus is spoken by a FILLED BAR, and that is deliberate: the cards one press
 * below carry the app's ring-and-glow, so an outlined pill sitting directly
 * above an outlined card read as the same kind of thing at TV distance, and the
 * viewer could not tell whether the D-pad was on the row's selector or on its
 * content. A filled bar is a selector; a ring is content (see KBCard). The
 * selected page goes solid accent inside the bar so it stays legible on the
 * wash, and the labels are otherwise unchanged - focus is never spoken by the
 * text alone.
 */
@Composable
private fun MediaTabStrip(
    tabs: List<MediaTab>,
    activeIndex: Int,
    focusRequester: FocusRequester,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    // Read with hasFocus rather than isFocused: the strip's focusable node is
    // the end of this chain, so what has to be observed is the subtree - the
    // same reading the app's poster tiles use for a tile plus its caption.
    val barTint = if (isFocused) KBAccent.copy(alpha = 0.22f) else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .clip(KBShapePill)
            .background(barTint)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.hasFocus }
            .onPreviewKeyEvent(onKey)
            .focusable()
    ) {
        tabs.forEachIndexed { index, tab ->
            val active = index == activeIndex
            Box(
                modifier = Modifier
                    .clip(KBShapePill)
                    .background(
                        when {
                            // The selected page goes SOLID while the bar has
                            // focus, so the selection is still unmistakable on
                            // top of the wash.
                            active && isFocused -> KBAccent
                            active -> KBAccent.copy(alpha = 0.22f)
                            // An unselected pill lets the bar's wash through,
                            // so the strip reads as one focused control rather
                            // than as three separate plates.
                            isFocused -> Color.Transparent
                            else -> KBSurface
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = tab.label.uppercase(Locale.US),
                    // KBVoid on the solid accent: the app's background color is
                    // the readable end of pairing with an accent fill.
                    color = when {
                        active && isFocused -> KBVoid
                        active -> KBTextHi
                        else -> KBTextLo
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * One card of the Media row: a still with the video's type badged over its
 * top-left corner, or - on the Posters page - 2:3 portrait artwork.
 *
 * The badge is what tells a trailer from the teaser and the clips beside it -
 * TMDB files a title's videos as a flat list, and on a rail of near-identical
 * stills the type is the only thing distinguishing one card from the next.
 *
 * [portrait] is [MediaCardItem.portrait]: a poster is 2:3, so it is drawn at the
 * viewer's own poster size (Settings → Display, the same width every poster
 * surface in the app uses) instead of a 16:9 crop through the middle of it.
 */
@Composable
private fun MediaThumbCard(
    imageUrl: String?,
    badge: String?,
    contentDescription: String,
    onClick: () -> Unit,
    portrait: Boolean = false,
    /**
     * A video card the trailer player cannot open (see
     * [MediaCardItem.playable]): the card is drawn with an UNAVAILABLE mark so
     * the press is answered BEFORE it is made, and the press itself resolves
     * nothing.
     */
    unplayable: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val posterSize = rememberPosterSize()
    val tileWidth = if (portrait) posterSize.width else 240.dp
    val tileHeight = if (portrait) posterSize.height else 135.dp

    KBCard(
        onClick = onClick,
        // The image menu on a backdrop or a poster (see the row's long press).
        onLongClick = onLongClick,
        // A 16:9 tile has no room to grow inside a rail of them without shoving
        // its neighbours - the same call the cast rail makes - so focus is
        // spoken by the ring and the glow here rather than by the lift (see
        // KBCard).
        focusedScale = KBFocusNone,
        modifier = modifier.width(tileWidth)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(tileHeight)
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    // No ImageRequest and no crossfade: this rail paints a row
                    // of stills at once, so it takes the loader's no-fade
                    // default (see MainApplication.newImageLoader).
                    model = imageUrl,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contentDescription,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            badge?.takeIf { it.isNotBlank() }?.let { label ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(KBShapeSmall)
                        .background(KBVoid.copy(alpha = 0.78f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = label,
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            // The other corner, and the other kind of fact: the type badge
            // names WHAT the video is, this one says it cannot be opened. Its
            // own chip rather than a change to the badge, because a teaser is
            // still a teaser - and it is the whole answer to "the press does
            // nothing", which is what a trailer the player cannot speak used to
            // be (see MediaCardItem.playable). Danger-tinted so it reads as the
            // exception at TV distance, not as another label.
            if (unplayable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(KBShapeSmall)
                        .background(KBDanger.copy(alpha = 0.22f))
                        .border(1.dp, KBDanger.copy(alpha = 0.65f), KBShapeSmall)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "UNAVAILABLE",
                        color = KBDanger,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * A backdrop off the Media row, fullscreen over a scrim.
 *
 * A still has nowhere to go, so the press is answered with the thing itself
 * rather than with a card that does nothing: the whole rail is a viewer asking
 * to look at the title's art, and the card is a 240dp crop of it. Dismissed by
 * Back or by either vertical press, and it holds the focus while it is up, so
 * the D-pad cannot reach the page behind the scrim.
 */
@Composable
private fun BackdropOverlay(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    val dismissFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        dismissFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid.copy(alpha = 0.94f))
            .focusRequester(dismissFocusRequester)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) {
                    return@onKeyEvent false
                }

                when (keyEvent.key) {
                    Key.Back,
                    Key.Escape,
                    Key.DirectionDown,
                    Key.DirectionUp -> {
                        onDismiss()
                        true
                    }

                    else -> false
                }
            }
            .focusable()
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Backdrop",
            // Fit, not Crop: the point of the press is to see the whole frame,
            // and a crop would answer with the same framing the card already
            // showed, only larger.
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(KBScreenEdge)
        )
    }
}
