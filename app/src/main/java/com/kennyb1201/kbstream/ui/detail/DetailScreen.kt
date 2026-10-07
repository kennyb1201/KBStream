package com.kennyb1201.kbstream.ui.detail

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
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
import com.kennyb1201.kbstream.data.tmdb.TrailerPick
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.bestReleaseDate
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.movieStatusTag
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.tmdb.director
import com.kennyb1201.kbstream.data.tmdb.displaySeasonName
import com.kennyb1201.kbstream.data.tmdb.list
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.tmdb.tmdbImage
import com.kennyb1201.kbstream.data.tmdb.writers
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.ui.components.AutoPlayLoadSplash
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBImdbTint
import com.kennyb1201.kbstream.ui.components.KBProgressBar
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_STATUS_LOADING
import com.kennyb1201.kbstream.ui.components.formatRuntimeLabel
import com.kennyb1201.kbstream.ui.components.formatRuntimeMinutes
import com.kennyb1201.kbstream.ui.components.heroSharedElement
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity
import com.kennyb1201.kbstream.ui.player.randomAiredEpisode
import com.kennyb1201.kbstream.ui.home.UNKNOWN_SHOW_NAME
import com.kennyb1201.kbstream.ui.home.upNextDisplayTitleOrNull
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.ManualSourceSelection
import com.kennyb1201.kbstream.ui.components.PlayFromBeginningSelection
import com.kennyb1201.kbstream.ui.components.PosterCaptions
import com.kennyb1201.kbstream.ui.components.GlobalPosterCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.components.rememberPosterTileWidth
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.theme.DEFAULT_ACCENT_INDEX
import com.kennyb1201.kbstream.ui.theme.KBAccent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
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
private const val COLLECTION_ROW_KEY = "collectionrow"
private const val RECS_ROW_KEY = "recsrow"

/** How many times the rail reveal and the focus request are retried. */
private const val RETURN_FOCUS_STEPS = 15

/** Delay between those retries, long enough for a frame to be laid out. */
private const val RETURN_FOCUS_DELAY_MS = 40L

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
    val value: String
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
                // Focus-aware: the card's content color is KBTextHi while
                // idle and KBAccent while focused, so the bar highlights with
                // the card it sits in.
                val barColor = androidx.tv.material3.LocalContentColor.current
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
    // Keyed per (type, id): DetailScreen is the only consumer of a shared
    // DetailViewModel, and an unscoped (Activity-wide) instance carries the
    // previous title's meta/episodes/resume state into the next one — the
    // Continue Watching auto-play effect then fired with the previous card's
    // play target (second card opened the first card's stream screen).
    viewModel: DetailViewModel = viewModel(key = "detail_${type}_$id")
) {
    val scope = rememberCoroutineScope()
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
        studioChipMenu = null
        lastStudioChipFocusRequester?.requestFocus()
    }
    var selectedReview by remember { mutableStateOf<TmdbReview?>(null) }

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
        posterMenu = null
        lastPosterFocusRequester?.requestFocus()
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
        seasonMenu = null
        lastSeasonFocusRequester?.requestFocus()
    }

    // Spoiler-free mode, read once for the screen: the episode cards already
    // hide an episode the viewer has not started, and the long-press menu they
    // open has to agree with them (see the menu's heading).
    val spoilerFreeContext = LocalContext.current
    val spoilerFreeEnabled = remember(spoilerFreeContext) {
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
        episodeMenu = null
        lastEpisodeFocusRequester?.requestFocus()
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
    val recsRowState = rememberLazyListState()
    val railFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val seasonFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

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
    val episodeFocusRequesters =
        remember { mutableMapOf<Pair<Int, Int>, FocusRequester>() }
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
    val allReviews by viewModel.allReviews.collectAsStateWithLifecycle()
    val tmdbDetail by viewModel.tmdbDetail.collectAsStateWithLifecycle()
    // The trailer the button offers IS the trailer the button plays: one pick,
    // one rule. Movies almost always carry a video typed exactly "Trailer", so
    // this matters for series, which TMDB frequently files as a "Teaser".
    val trailerVideo = remember(tmdbDetail) {
        TrailerPick.best(tmdbDetail?.videos?.results)
    }
    // TMDB clearlogo first (more reliable); add-on logo (fanart.tv etc.) as
    // fallback when TMDB has nothing for this title.
    val clearLogoUrl = tmdbImage(tmdbDetail?.bestLogoPath(), "w500")
        ?: meta?.logo?.takeIf { it.isNotBlank() }
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
    val error by viewModel.error.collectAsStateWithLifecycle()
    val vmTargetEpisode by viewModel.targetEpisode.collectAsStateWithLifecycle()
    val vmLoadedSeason by viewModel.loadedSeason.collectAsStateWithLifecycle()
    val vmPlayButtonText by viewModel.playButtonText.collectAsStateWithLifecycle()

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

            val castItems = tmdbCast.distinctBy { it.id }.take(25).map { PeopleRowItem.Person(it) }
            if (castItems.isNotEmpty() && isNotEmpty()) {
                add(PeopleRowItem.Separator)
            }
            addAll(castItems)
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

    val premiereDateFormatter = remember {
        DateFormats.longDate()
    }

    val movieDetailsFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }

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
            if (
                seasonNum == effectiveSeason &&
                episodes.isNotEmpty()
            ) {
                return episodes
                    // A season mark/unmark must not sweep up UNAIRED episodes:
                    // the loaded season lists them (and greys the cards), but a
                    // whole-season push would tell the tracker the whole season
                    // is watched. Air dates that are not trusted for this
                    // season are left alone rather than guessed at.
                    .filter { ep ->
                        effectiveSeason in seasonsWithStaleDates ||
                            !isEpisodeUnavailable(ep.airDate)
                    }
                    .map {
                        it.episodeNumber
                    }
                    .distinct()
                    .sorted()
            }
            val count = tmdbDetail?.seasons
                ?.firstOrNull {
                    it.seasonNumber == seasonNum
                }
                ?.episodeCount
            if (count != null && count > 0) {
                return (1..count).toList()
            }
            return null
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
     */
    suspend fun focusSeasonChip(season: Int) {
        val index = seasonChipIndex(season)
        if (index < 0) {
            return
        }

        bringSeasonChipIntoView(index)

        repeat(10) {
            val requester = seasonFocusRequesters[season]
            if (
                requester != null &&
                runCatching {
                    requester.requestFocus()
                }.isSuccess
            ) {
                return
            }
            delay(40L)
        }
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

    fun focusSeasonOrEpisode(): Boolean {
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
            // composed yet and requestFocus() would silently no-op. Scroll
            // it into view first, then focus after a brief pause.
            val targetIndex = targetEpisodeIndex
            scope.launch {
                if (targetIndex >= 0) {
                    episodesRowState.scrollToItem(targetIndex)
                    delay(90)
                }
                runCatching {
                    episodeRequester.requestFocus()
                }
            }
            return true
        }

        val seasonRequester = season?.let {
            seasonFocusRequesters[it]
        }

        if (seasonRequester != null) {
            runCatching {
                seasonRequester.requestFocus()
            }
            return true
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

        runCatching {
            episodeFocusRequesters[
                season to targetEpisode.episodeNumber
            ]?.requestFocus()
        }

        // Focus is off the sink now — disarm it so it never captures
        // focus during ordinary spatial navigation.
        seasonSwapSinkArmed = false

        clearEpisodeTransitionState()
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
    fun trailerPlayerIntent(
        context: Context,
        source: PlayableSource
    ): Intent =
        Intent(context, NativePlayerActivity::class.java).apply {
            val userAgent = when (source) {
                is PlayableSource.Muxed -> {
                    putExtra("stream_url", source.url)
                    source.userAgent
                }

                is PlayableSource.Adaptive -> {
                    putExtra("stream_url", source.videoUrl)
                    putExtra("audio_url", source.audioUrl)
                    source.userAgent
                }
            }

            userAgent?.let { ua ->
                putExtra("stream_headers", "User-Agent: $ua")
            }

            putExtra("parent_type", "movie")
            putExtra("item_name", "Trailer")
        }

    fun playTrailer(context: Context) {
        val key = trailerVideo?.key?.takeIf { it.isNotBlank() } ?: return

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

            context.startActivity(trailerPlayerIntent(context, source))
        }
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
                title = initialTarget.displayName.ifBlank { id }
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

            // Add-on art first (fanart etc. from the meta provider), TMDB
            // backdrop as fallback. When no meta add-on answered, the fallback
            // meta was built from TMDB itself, so this still lands on TMDB art.
            val backdropUrl = remember(tmdbDetail, m) {
                tmdbDetail?.backdropPath?.let {
                    TmdbRepository.BACKDROP_BASE + it
                }
                    ?: m.background
                    ?: m.poster
                    ?: initialPoster
            }

            val context = LocalContext.current

            val playLabel: String
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
                playButtonFocusRequester.requestFocus()
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

                playTarget = remember(
                    resumeInfo,
                    id,
                    displayName,
                    tmdbDetail?.runtime,
                    wantsBeginning
                ) {
                    // Prefer the actual recorded duration (accurate for the
                    // file that was watched); fall back to TMDB's runtime.
                    val recordedMinutes =
                        resumeInfo?.durationMs
                            ?.div(60_000L)
                            ?.toInt()
                            ?.takeIf { it > 0 }
                    StreamsTarget(
                        contentType = "movie",
                        streamId = id,
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
                    targetSeason,
                    targetEpisode,
                    tmdbDetail?.id
                ) {
                    fileEpisodeStreamId(
                        context = context,
                        rootId = id,
                        tmdbId = tmdbDetail?.id,
                        season = targetSeason,
                        tmdbEpisode = targetEpisode
                    )
                }

                val targetStreamId =
                    resolvedTargetEpisode?.streamId
                        ?: resumeStreamId?.takeIf {
                            isResumingHere
                        }
                        ?: initialTarget?.streamId?.takeIf {
                            initialTarget.season == targetSeason &&
                                initialTarget.episode == targetEpisode
                        }
                        ?: fallbackStreamId

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

                playTarget = remember(
                    id,
                    displayName,
                    targetSeason,
                    targetEpisode,
                    targetStreamId,
                    isResumingHere,
                    resumeInfo?.positionMs,
                    resumeInfo?.episodeTitle,
                    playTargetEpisodeName,
                    playTargetRuntimeMinutes,
                    wantsBeginning
                ) {
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
                        totalEpisodesInSeason = episodes.size,
                        runtimeMinutes = playTargetRuntimeMinutes
                    )
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

            LaunchedEffect(Unit) {
                runCatching {
                    playButtonFocusRequester.requestFocus()
                }
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

                    var attempts = 0
                    var focused = false
                    while (!focused && attempts < RETURN_FOCUS_STEPS) {
                        val requester = railFocusRequesters[target.targetKey]
                        focused =
                            requester != null &&
                                runCatching { requester.requestFocus() }.isSuccess
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
            LaunchedEffect(type, id) {
                manualPick = ManualSourceSelection.consume()
            }

            var autoPlayed by remember { mutableStateOf(false) }
            LaunchedEffect(
                initialTarget,
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
                if (
                    normalizedType == "series" &&
                    (episodesLoading || episodes.isEmpty())
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

                autoPlayed = true
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
                        "${playTarget.contentType}:${playTarget.streamId}"
                    )
                }
                onNavigateStreams(
                    playTarget,
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
            model = remember(backdropUrl) {
                ImageRequest.Builder(context)
                    .data(backdropUrl)
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
                            .scrollToTopOnFocus(detailListState, scope)
                    ) {
                        val openStreams = {
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
                            onNavigateStreams(
                                playTarget,
                                id,
                                type,
                                m.poster,
                                backdropUrl,
                                clearLogoUrl,
                                m.description,
                                tmdbDetail?.credits?.cast.orEmpty()
                            )
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
                                            // target the Play button would have.
                                            onNavigateStreams(
                                                playTarget,
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
                            .scrollToTopOnFocus(detailListState, scope)
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

                        val tmdbGenres =
                            tmdbDetail?.genres.orEmpty()

                        if (tmdbGenres.isNotEmpty()) {
                            LazyRow(
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
                                modifier = Modifier
                                    .focusGroup()
                                    .focusRestorer()
                                    .onPreviewKeyEvent {
                                        keyEvent: KeyEvent ->
                                        if (
                                            normalizedType == "series" &&
                                            keywords.isEmpty() &&
                                            keyEvent.type ==
                                                KeyEventType.KeyDown &&
                                            keyEvent.key ==
                                                Key.DirectionDown
                                        ) {
                                            focusSeasonOrEpisode()
                                        } else {
                                            false
                                        }
                                    }
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
                                    railFocusRequesters[chipKey] =
                                        chipFocusRequester

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
                                                .onFocusChanged { focusState ->
                                                    if (focusState.hasFocus) {
                                                        scope.launch {
                                                            detailListState.animateScrollToItem(0)
                                                        }
                                                    }
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

                            m.awards
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    add(
                                        DetailFactItem(
                                            "Awards",
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
                                        .onPreviewKeyEvent {
                                            keyEvent: KeyEvent ->
                                            if (
                                                keyEvent.type !=
                                                    KeyEventType.KeyDown ||
                                                keyEvent.key !=
                                                    Key.DirectionDown
                                            ) {
                                                false
                                            } else if (
                                                normalizedType == "movie"
                                            ) {
                                                // On movies there is no episode
                                                // section, so DOWN from tags goes
                                                // straight into the people rail
                                                // (its focusRestorer picks the
                                                // first card). When the rail isn't
                                                // composed (no cast at all) the
                                                // request throws — fall through so
                                                // default search can reach the rows
                                                // further down the list.
                                                runCatching {
                                                    movieDetailsFocusRequester
                                                        .requestFocus()
                                                }.isSuccess
                                            } else if (
                                                normalizedType == "series"
                                            ) {
                                                focusSeasonOrEpisode()
                                            } else {
                                                false
                                            }
                                        }
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
                                        railFocusRequesters[chipKey] =
                                            chipFocusRequester

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

                                                    val isWatchedFlow =
                                                        remember(
                                                            id,
                                                            type,
                                                            episodeKey
                                                        ) {
                                                            viewModel
                                                                .observeIsWatched(
                                                                    id,
                                                                    type
                                                                )
                                                        }

                                                    val isWatchedCached
                                                            by isWatchedFlow
                                                                .collectAsStateWithLifecycle()

                                                    val isEpisodeWatched =
                                                        remember(
                                                            ep.episodeNumber,
                                                            watchedEpisodesForSeason,
                                                            episodeKey,
                                                            watchedEpisodeKeys,
                                                            isWatchedCached
                                                        ) {
                                                            isWatchedCached ||
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
                                                                    totalEpisodesInSeason = episodes.size,
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

                                                                        seasonEpisodeNumbers =
                                                                            episodes
                                                                                .map {
                                                                                    it.episodeNumber
                                                                                }
                                                                                .distinct()
                                                                                .sorted(),
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
                                                                            ) < 0
                                                                        ) {
                                                                            false
                                                                        } else {
                                                                            scope.launch {
                                                                                focusSeasonChip(
                                                                                    chipSeason
                                                                                )
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


                        if (peopleItems.isNotEmpty()) {
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
                                                railFocusRequesters[cardKey] =
                                                    cardFocusRequester

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

                        val companies =
                            tmdbDetail?.productionCompanies
                                .orEmpty()

                        val networks =
                            tmdbDetail?.networks.orEmpty()

                        if (
                            normalizedType == "series" &&
                            networks.isNotEmpty()
                        ) {
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
                                        // UP belongs to PEOPLE, the rail
                                        // directly above (the same fix the
                                        // episodes rail already makes for its
                                        // own UP). The default search is
                                        // geometric: a chip that sits to the
                                        // right of the cast rail's last card
                                        // finds nothing above it and lands two
                                        // rows up on an episode card instead.
                                        // Asking for the people rail directly
                                        // lets its focusRestorer land on the
                                        // card the viewer last had there, or
                                        // the first one - never the episodes.
                                        .onPreviewKeyEvent {
                                            keyEvent ->
                                            if (
                                                keyEvent.type !=
                                                    KeyEventType.KeyDown ||
                                                keyEvent.key !=
                                                    Key.DirectionUp
                                            ) {
                                                false
                                            } else {
                                                // Only when the rail is
                                                // composed (there is a cast
                                                // list); otherwise the request
                                                // throws and the press falls
                                                // through to the default
                                                // search.
                                                runCatching {
                                                    movieDetailsFocusRequester
                                                        .requestFocus()
                                                }.isSuccess
                                            }
                                        }
                                ) {
                                    itemsIndexed(
                                        networks,
                                        key = { _, n -> n.id }
                                    ) { networkIndex, n ->
                                        val chipKey = "network:${n.id}"
                                        val chipFocusRequester =
                                            remember(n.id) { FocusRequester() }
                                        railFocusRequesters[chipKey] =
                                            chipFocusRequester

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
                                                                n.name
                                                            )
                                                    )
                                            },
                                            modifier = Modifier
                                                .focusRequester(
                                                    chipFocusRequester
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (companies.isNotEmpty()) {
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
                                        railFocusRequesters[chipKey] =
                                            chipFocusRequester

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
                                                                c.name
                                                            )
                                                    )
                                            },
                                            modifier = Modifier
                                                .focusRequester(
                                                    chipFocusRequester
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        // Bundled page-1 reviews plus extra pages fetched in
                        // the background (allReviews) - more reviews appear as
                        // the additional pages land.
                        val reviews = allReviews
                            .ifEmpty { tmdbDetail?.reviews?.results.orEmpty() }

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


                            item(key = "reviewsrow", contentType = "section-row") {
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
                                    items(
                                        reviews.take(60),
                                        key = { it.id }
                                    ) { review ->
                                        ReviewCard(
                                            review = review,
                                            onClick = {
                                                selectedReview = it
                                            }
                                        )
                                    }
                                }
                            }
                        }


                        if (collectionParts.isNotEmpty()) {
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
                                        railFocusRequesters[partKey] = requester

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
                                                } == true,
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
                                                } == true,
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
                                        )
                                    }
                                }
                            }
                        }

                        val recs = recommendations

                        if (recs.isNotEmpty()) {
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
                                        recs.take(30),
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
                                        railFocusRequesters[recKey] = requester

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
                                                } == true,
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
                                                } == true,
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

                selectedReview?.let { review ->
                    ReviewOverlay(
                        review = review,
                        onDismiss = {
                            selectedReview = null
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
                        } == true

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
                                lastPosterFocusRequester?.requestFocus()
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
                                lastSeasonFocusRequester?.requestFocus()
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
                                lastSeasonFocusRequester?.requestFocus()
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
                        actions = listOf(
                            PosterContextAction(
                                label = "Play from Beginning",
                                description =
                                    "Start this title over, ignoring saved progress"
                            ) {
                                dismissPlayButtonMenu()
                                startOver = true
                                autoPlayed = false
                            },
                            PosterContextAction(
                                label = "Play Manually",
                                description = "Pick a source instead of auto-selecting"
                            ) {
                                dismissPlayButtonMenu()
                                manualPick = true
                                autoPlayed = false
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
                                    lastEpisodeFocusRequester?.requestFocus()
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
                                        lastEpisodeFocusRequester?.requestFocus()
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
                                    lastEpisodeFocusRequester?.requestFocus()
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
                                    lastEpisodeFocusRequester?.requestFocus()
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
                                            totalEpisodesInSeason =
                                                selected
                                                    .seasonEpisodeNumbers
                                                    .size,
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

                                    val playTarget =
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
                                            totalEpisodesInSeason =
                                                selected
                                                    .seasonEpisodeNumbers
                                                    .size,
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
                                        playTarget,
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
                .width(150.dp)
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
                maxLines = 2,
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
                onLongClick = if (seasonUnavailable) {
                    // No episodes to mark yet - hide the watch-menu gesture
                    // on not-yet-released seasons.
                    null
                } else {
                    {
                        val numbers =
                            episodeNumbers
                                ?: (1..(watchedSet
                                    .maxOrNull()
                                    ?: 0)
                                    .coerceAtLeast(1))
                                    .toList()
                        onSeasonLongPress(
                            season,
                            seasonName,
                            numbers,
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

    val imageScale by animateFloatAsState(
        targetValue =
            if (isFocused) 1.08f else 1f,
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
    // first is behind them, the second is where they left off. Read once per
    // card; the mode is a preference and re-entering the screen re-reads it.
    val spoilerFreeContext = LocalContext.current
    val spoilerFree = remember(spoilerFreeContext) {
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
 */
@Composable
private fun Modifier.scrollToTopOnFocus(
    listState: LazyListState,
    scope: kotlinx.coroutines.CoroutineScope
): Modifier = this.onFocusChanged { state ->
    if (
        state.hasFocus &&
        (
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
            )
    ) {
        scope.launch { listState.animateScrollToItem(0) }
    }
}

@Composable
private fun ReviewCard(
    review: TmdbReview,
    onClick: (TmdbReview) -> Unit
) {
    KBCard(
        onClick = {
            onClick(review)
        },
        modifier = Modifier
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

private fun formatEpisodeAirDate(
    raw: String
): String =
    formatDisplayDate(raw) ?: raw

/**
 * True only when the episode has an announced air date that's still
 * in the future. A missing/unparseable air date is NOT treated as
 * unavailable -- TMDB just doesn't always have one for already-aired
 * episodes, and we don't want to mislabel those.
 */
private fun isEpisodeUnavailable(
    airDate: String?
): Boolean {
    val trimmed = airDate?.trim()

    if (trimmed.isNullOrBlank()) {
        return false
    }

    return runCatching {
        LocalDate.parse(trimmed).isAfter(LocalDate.now())
    }.getOrDefault(false)
}

private fun formatUsd(
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

private fun formatSeriesYearRange(
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
