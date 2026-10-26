package com.kennyb1201.kbstream.ui.home

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlin.math.abs
import androidx.compose.foundation.shape.CircleShape
import com.kennyb1201.kbstream.FormFactor
import com.kennyb1201.kbstream.data.kb.BrowseHomeShortcut
import com.kennyb1201.kbstream.data.kb.browseChipSuccessor
import com.kennyb1201.kbstream.data.kb.chipKey
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.BrowseShortcutArt
import com.kennyb1201.kbstream.data.tmdb.displayDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import com.kennyb1201.kbstream.ui.player.SplitModeRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import coil3.toBitmap
import com.kennyb1201.kbstream.data.tmdb.displaySeasonEpisodeCount
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.kb.KBFolder
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.movieStatusTag
import com.kennyb1201.kbstream.data.youtube.InnerTubeExtractor
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerLauncher
import com.kennyb1201.kbstream.data.youtube.TrailerPlayerPool
import com.kennyb1201.kbstream.data.youtube.isYouTubeIdentityBlocked
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.library.LibraryIds
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_DELAY_MS
import com.kennyb1201.kbstream.ui.browse.FocusLandingVerdict
import com.kennyb1201.kbstream.ui.browse.rememberFocusHandBack
import com.kennyb1201.kbstream.ui.components.BrandMarkLogo
import com.kennyb1201.kbstream.ui.components.InfiniteScrollEffect
import com.kennyb1201.kbstream.ui.components.KBProgressBar
import com.kennyb1201.kbstream.ui.components.KBSkeletonRailStack
import com.kennyb1201.kbstream.ui.components.KBSectionHeader
import com.kennyb1201.kbstream.ui.components.KBStatusMessage
import com.kennyb1201.kbstream.ui.components.KB_EASE
import com.kennyb1201.kbstream.ui.components.KB_PANEL_ENTER_MS
import com.kennyb1201.kbstream.ui.components.KB_STATUS_ICON_EMPTY
import com.kennyb1201.kbstream.ui.components.KB_SCREEN_TRANSITION_MS
import com.kennyb1201.kbstream.ui.components.heroSourceElement
import com.kennyb1201.kbstream.ui.mobile.HomeScreenMobile
import com.kennyb1201.kbstream.ui.components.LibraryAddTarget
import com.kennyb1201.kbstream.ui.components.LandscapeCard
import com.kennyb1201.kbstream.ui.components.landscapeArtKey
import com.kennyb1201.kbstream.ui.components.landscapeRank
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.components.rememberMotionMs
import com.kennyb1201.kbstream.ui.components.rememberPosterSize
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.components.kbFocusMarquee
import com.kennyb1201.kbstream.ui.kb.KBHomeCollectionRail
import com.kennyb1201.kbstream.ui.kb.homeRailHeadLeftEdgeStop
import com.kennyb1201.kbstream.ui.kb.homeTopRailUpHook
import com.kennyb1201.kbstream.ui.kb.rememberHomeTopBarHoldUpHook
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.spoiler.SpoilerFree
import com.kennyb1201.kbstream.ui.components.hideTarget
import com.kennyb1201.kbstream.ui.components.PosterContextAction
import com.kennyb1201.kbstream.ui.components.PosterContextMenu
import com.kennyb1201.kbstream.ui.components.rememberHiddenTitleKeys
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths
import com.kennyb1201.kbstream.ui.components.imageOverrideBackdropUrl
import com.kennyb1201.kbstream.ui.components.imageOverrideCardBackdropUrl
import com.kennyb1201.kbstream.ui.components.imageOverridePosterUrl
import com.kennyb1201.kbstream.ui.components.watchedMenuLabel
import com.kennyb1201.kbstream.ui.components.watchedMenuDescription
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBFocusNone
import com.kennyb1201.kbstream.ui.theme.KBPlum
import com.kennyb1201.kbstream.ui.theme.KBRust
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBSteel
import com.kennyb1201.kbstream.ui.theme.KBSuccess
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.data.youtube.PlayableSource
import com.kennyb1201.kbstream.data.youtube.PoTokenTrace
import com.kennyb1201.kbstream.data.youtube.YoutubeChunkedDataSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.kennyb1201.kbstream.data.runCatchingCancellable

private val HomePosterWidth = 124.dp
private val HomePosterHeight = 180.dp
private val HomeLandscapeWidth = 210.dp
private val HomeLandscapeHeight = 118.dp
private val HomeRailGap = 12.dp

/**
 * How long a focused card must stay focused before the hero META resolve runs.
 *
 * Holding the D-pad fires the hero effect once per card crossed, and the
 * resolve is not cheap: it cancels the previous coroutine, writes several
 * StateFlows in HomeViewModel, and starts a fresh backdrop request. The dwell
 * is what the viewer actually sees, so the resolve waits out this debounce
 * first - the effect re-launches on every focus change, so a fast scroll
 * collapses onto the card the viewer settles on and one resolve runs instead of
 * twenty. Long enough to swallow a held D-pad's repeat rate, short enough that a
 * deliberate single step still resolves immediately.
 */
private const val HERO_RESOLVE_DEBOUNCE_MS = 150L

/**
 * The most frames the top bar's focus hand-off keeps asking for focus before it
 * gives up, loudly.
 *
 * This is a runaway guard, not a budget the way a menu's hand-back is a budget
 * for the landing DOWN to a card (see [rememberFocusHandBack]). The hand-off
 * into the bar now runs
 * for as long as the bar is showing and focus has not landed inside it, so the
 * only thing bounded here is a bar that can never take focus at all. Three
 * seconds at 60 fps is an order of magnitude past any fade the ask can race, and
 * reaching it logs (tag HOME_FOCUS) rather than vanishing: the old twelve-frame
 * budget expired silently and left the bar shown with the D-pad stranded.
 */
private const val HOME_TOP_BAR_DRIVE_MAX_FRAMES = 180

/**
 * The key families Home's own rail cards report their focus under (see
 * [HomeViewModel.lastFocusedRailItemKey] and this screen's
 * `railFocusRequesters`).
 *
 * A catalog card keys itself by content ("movie:123"). A built-in row's cards
 * are not catalog content, so each names its own family rather than borrowing
 * that shape: the same title can be a Continue Watching card, an Upcoming card
 * and a catalog card at once, and one key must never mean two places to put
 * the D-pad back.
 */
private const val CONTINUE_WATCHING_CARD_KEY_PREFIX = "upnext:"
private const val UPCOMING_CARD_KEY_PREFIX = "upcoming:"

/**
 * How long a Continue Watching resume waits for its stored stream id's root to
 * be re-verified before playing the id it already has.
 *
 * The same five-second budget the Detail screen's root await uses: a cold,
 * wrong id gets one TMDB lookup, a cached one answers instantly, and a slow or
 * unreachable TMDB keeps today's behavior rather than holding the D-pad.
 */
private const val CW_ROOT_VERIFY_TIMEOUT_MS = 5_000L

private val HomeHeroHeight = 300.dp

private val RailTopContentPadding = 0.dp

// No bottom padding: a card's box already reserves [PosterFocusHeadroom] below
// the artwork for the focus glow and its 1.03 scale, so anything here is dead
// air charged against the next rail's title — which is exactly what made that
// title a row of dots at the panel edge. See [RailLanding] for the packing.
private val RailBottomContentPadding = 0.dp

internal val RailHorizontalStartPadding = 12.dp

// The numbers the landing is built from live in [RailLanding], together with
// the geometry they have to satisfy: which slack a focused rail gives up, and
// why the next rail's title has to come out of it. Named here so this screen
// keeps reading as one set of layout numbers.
private val RailSectionGap = RailLanding.SectionGap
private val RailHeaderFocusInset = RailLanding.HeaderInset
private val RailHeaderFloor = RailLanding.HeaderFloor

private val HeroToFirstRailGap = 2.dp

// Hero clearlogo box (ContentScale.Fit inside). Collection (KB folder)
// manifests supply their own titleLogoUrl, which is frequently a wordmark
// that reads small at the shared size — render it noticeably larger.
private val HeroLogoWidth = 300.dp
private val HeroLogoHeight = 82.dp
private val CollectionHeroLogoWidth = 400.dp
private val CollectionHeroLogoHeight = 120.dp

private val PosterFocusHeadroom = 24.dp

internal val TvSafeAreaHorizontal = 12.dp
private val TvSafeAreaVertical = 0.dp

/**
 * Formats UpNextItem.remainingMinutes as "1h 12m left" / "42m left".
 *
 * Internal rather than private because the touch Home draws the same Continue
 * Watching card, and the two must not spell the same fact two ways.
 */
internal fun formatTimeLeft(remainingMinutes: Int?): String? {
    if (remainingMinutes == null || remainingMinutes <= 0) return null

    val hours = remainingMinutes / 60
    val minutes = remainingMinutes % 60

    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m left"
        hours > 0 -> "${hours}h left"
        else -> "${minutes}m left"
    }
}

/**
 * The D-pad presses that tell Home the viewer has taken over.
 *
 * The first of these after a fresh entry is the viewer's intent (see
 * `viewerMovedFocusSinceEntry`), which is what the Continue Watching landing is
 * gated on instead of where focus happened to rest: a default is not a choice,
 * whichever rail the framework left it on.
 *
 * Select counts - pressing it chooses whatever is under focus, the strongest
 * statement of intent there is. Back deliberately does not: it is not a
 * navigation step, and a Back that only dismisses an overlay has moved no focus.
 */
internal fun isHomeFocusMoveKey(key: Key): Boolean = when (key) {
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
    Key.DirectionCenter -> true
    else -> false
}

/**
 * One chip of the top bar: the shared surface chrome and nothing else.
 *
 * The bar holds two kinds of chip now - word chips and the profile avatar - and
 * they must stay identical in size, focus ring and press behavior, so the
 * chrome lives here once instead of being copied per chip.
 */
@Composable
private fun TopBarChip(
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                onFocusChange(it.isFocused)
            }
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionDown
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = KBShapeSmall
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = KBTextHi.copy(alpha = 0.76f),
            focusedContainerColor = KBAccent.copy(alpha = 0.28f),
            focusedContentColor = KBTextHi,
            pressedContainerColor = KBAccent.copy(alpha = 0.28f),
            pressedContentColor = KBTextHi
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.Transparent
                ),
                shape = KBShapeSmall
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    KBAccent
                ),
                shape = KBShapeSmall
            )
        ),
        glow = androidx.tv.material3.ClickableSurfaceDefaults.glow(),
        // No growth, on purpose: a top-bar chip is about as wide as its label
        // plus the dismiss affordance beside it, so growing it would move the
        // bar. This is exactly the case KBFocusNone in the theme names -- the
        // container / border / content swap is the whole focus cue. It was the
        // last raw focus-scale literal left in the app.
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusNone
        )
    ) {
        content()
    }
}

@Composable
private fun TopActionItem(
    label: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    TopBarChip(
        onClick = onClick,
        onDismiss = onDismiss,
        onFocusChange = { focused = it },
        modifier = modifier
    ) {
        Text(
            text = label,
            // A chip label, so it takes the chip's text slot rather than a
            // 13sp literal: no such slot exists in the type scale, and the
            // whole cluster of 13sp text on this screen (chip, meta line,
            // separator, resume label) was the last place the app wrote its
            // own size instead of naming a role.
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 8.dp
            )
        )
    }
}

/**
 * The top bar's profile button: the active profile's avatar instead of its name.
 *
 * The name was a word taking a chip's worth of width to say what the avatar
 * already says, and the avatar is the mark the viewer just chose on the picker -
 * so the bar carries the same one, and the name survives only as the initial on
 * a generic avatar (same precedence as the picker: an uploaded or linked image
 * wins, otherwise the index's hue pair).
 *
 * The avatar is the one control on this bar that is a picture rather than a
 * word, and at 26dp it read as too small from the couch - the word chips
 * around it are ~32dp of type set at 13sp. [avatarSize] is that size again,
 * picked for the same viewing distance rather than for the chip.
 *
 * The chip's own padding absorbs the difference, so the bar gains a little
 * height and nothing else moves: the avatar still leads the bar before
 * SEARCH, and the focus ring still comes from the chip's own chrome.
 */
@Composable
private fun TopProfileItem(
    name: String,
    avatarIndex: Int,
    customAvatarUrl: String?,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val itemContext = LocalContext.current
    val (avatarBg, avatarFg) = ProfileManager.AVATAR_COLORS[
        avatarIndex.coerceIn(0, ProfileManager.AVATAR_COUNT - 1)
    ]

    // One size for both branches: the linked image and the generic tile are the
    // same control, so they must measure the same.
    val avatarSize = 38.dp

    TopBarChip(
        onClick = onClick,
        onDismiss = onDismiss,
        onFocusChange = { focused = it },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = 10.dp,
                vertical = 1.dp
            ),
            contentAlignment = Alignment.Center
        ) {
            if (customAvatarUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(itemContext)
                        .data(customAvatarUrl)
                        .crossfade(false)
                        .build(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(Color(avatarBg)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.take(1).uppercase(),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(avatarFg)
                    )
                }
            }
        }
    }
}

@Composable
private fun TopActionBar(
    onSearch: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchProfile: () -> Unit,
    firstActionFocusRequester: FocusRequester,
    onBarFocusChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // Quick profile switch lives on the top bar: the button IS the active
    // profile, so it's obvious what's running and one click swaps profiles
    // (ProfilePicker → setActive → Home rails reload). Before any profile
    // exists the active one is null and the chip has nothing to show - it falls
    // back to the letter tile, exactly like the picker's fallback.
    //
    // It leads the bar instead of trailing the words, because the avatar is the
    // identity control the viewer reaches for first. SEARCH keeps the row's
    // first-focus requester, so opening the bar still lands there and a single
    // D-pad left reaches the profile.
    val activeProfile by
        ProfileManager.activeProfile
            .collectAsStateWithLifecycle()
    val profileName = activeProfile?.name?.takeIf { it.isNotBlank() } ?: "PROFILES"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 20.dp,
                vertical = 14.dp
            )
            // The bar's own report of where focus is: `hasFocus` is true for a
            // node whose descendant holds it, so this covers every chip and
            // needs no per-chip wiring. Home's hand-off reads it instead of
            // trusting requestFocus()'s answer (see the hand-off effect).
            .onFocusChanged { onBarFocusChanged(it.hasFocus) },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopProfileItem(
            name = profileName,
            avatarIndex = activeProfile?.avatarIndex ?: 0,
            customAvatarUrl = activeProfile?.customAvatarUrl ?: activeProfile?.avatarData,
            onClick = onSwitchProfile,
            onDismiss = onDismiss,
            modifier = Modifier.padding(end = 8.dp)
        )

        TopActionItem(
            label = "SEARCH",
            onClick = onSearch,
            onDismiss = onDismiss,
            modifier = Modifier
                .focusRequester(firstActionFocusRequester)
                .padding(end = 8.dp)
        )

        TopActionItem(
            label = "TV GUIDE",
            onClick = onOpenGuide,
            onDismiss = onDismiss,
            modifier = Modifier.padding(end = 8.dp)
        )

        TopActionItem(
            label = "LIBRARY",
            onClick = onOpenLibrary,
            onDismiss = onDismiss,
            modifier = Modifier.padding(end = 8.dp)
        )

        TopActionItem(
            label = "SETTINGS",
            onClick = onOpenSettings,
            onDismiss = onDismiss
        )
    }
}

/** Human-readable origin of a resolved trailer source (host only, no signed URL). */
private fun heroSourceOrigin(source: PlayableSource): String =
    when (source) {
        is PlayableSource.Muxed ->
            "muxed host=" +
                (runCatching {
                    android.net.Uri.parse(source.url).host
                }.getOrNull() ?: "?")

        is PlayableSource.Adaptive ->
            "adaptive videoHost=" +
                (runCatching {
                    android.net.Uri.parse(source.videoUrl).host
                }.getOrNull() ?: "?") +
                " audioHost=" +
                (runCatching {
                    android.net.Uri.parse(source.audioUrl).host
                }.getOrNull() ?: "?")
    }

/**
 * How long a trailer that has NOT yet rendered a frame may sit before the
 * hero gives up and shows the backdrop.
 *
 * This one exists only to keep a blank hero from hanging on a source that
 * never starts, so it stays short.
 */
private const val HeroTrailerStartGraceMs = 8_000L

/**
 * How long an already-playing trailer may sit in a mid-play re-buffer before
 * the hero gives up and crossfades back to the backdrop.
 *
 * A trailer that has started is not a blank screen, and YouTube's 1 MB-chunked
 * googlevideo streams re-buffer routinely. The old 5 s grace killed healthy
 * trailers mid-play — the "trailers keep stopping" report. Thirty seconds is
 * long enough for a chunk to come back on a slow link while still bounding a
 * genuinely dead stream.
 */
private const val HeroTrailerRebufferGraceMs = 30_000L

/**
 * How close to a trailer's END counts as "it finished".
 *
 * An error inside this window must not be retried: re-resolving a fresh signed
 * URL, remounting the player and re-preparing the media to replay the last two
 * seconds costs a visible blip and shows nothing new, when the hero can just
 * crossfade to the backdrop the way a clean ending does.
 */
internal const val HeroTrailerNearEndMs = 3_000L

/**
 * Whether a hero trailer that just failed should be retried from
 * [positionMs], or left ended.
 *
 * Pure, so the rule is unit tested without a player. A failure inside the last
 * [HeroTrailerNearEndMs] of a KNOWN duration means the trailer effectively
 * ended, so the hero falls back to the backdrop instead of replaying its tail.
 *
 * An UNKNOWN duration ([durationMs] <= 0, which is what ExoPlayer reports as
 * `C.TIME_UNSET`) RETRIES, and that is deliberate. An unknown duration means the
 * media never got far enough to report one, which is exactly the stale-signed-URL
 * failure the retry exists for: googlevideo 403s on the first chunk, long before
 * the container is read far enough to know how long the video is. Treating
 * "unknown" as "near the end" would therefore disable the fresh-URL retry in
 * the one case it was built for, and a once-playable trailer would stay dead
 * until its 6 h cache entry aged out.
 *
 * [isIdentityBlocked] is the other "do not retry" answer, and it is not an
 * arithmetic one: it is the shared classifier's verdict that YouTube refused
 * the client IDENTITY (a googlevideo 403 that the source's own mode ladder
 * already cycled every UA / range / ratebypass mode for). The 2026-10-18 field
 * log settles what a retry buys there: the one-shot retry re-resolved, got a
 * FRESH signed URL, and that URL 403'd on its very first request. Retrying a
 * refused identity cannot produce playback - only another mount, another
 * buffer, another failure - so a trailer treats it like the end of the clip and
 * crossfades to the backdrop. Every other failure keeps the retry, because a
 * fresh URL genuinely does recover those.
 */
internal fun shouldRetryHeroTrailerAfterError(
    positionMs: Long,
    durationMs: Long,
    isIdentityBlocked: Boolean = false
): Boolean {
    if (isIdentityBlocked) return false
    if (durationMs <= 0L) return true
    return durationMs - positionMs.coerceAtLeast(0L) >= HeroTrailerNearEndMs
}

/**
 * A resolved trailer plus the position its playback must start at, as ONE
 * value.
 *
 * The start position is only ever meaningful for the source it was resolved
 * for, so holding it in a second piece of state is holding a value that can
 * outlive its source - a foreground re-arm or an autoplay toggle would then seek
 * a FRESH resolve to a position left over from a previous playback session.
 * Swapping this object IS the consume: a new source arrives with its own start
 * position, or with 0.
 */
internal data class HeroTrailerPlayback(
    val source: PlayableSource,
    val startPositionMs: Long = 0L
)

/**
 * The app's one inline trailer renderer: a pooled ExoPlayer in a `PlayerView`,
 * driven by the source [HeroTrailerPlayback] carries.
 *
 * Internal rather than private because the phone's Detail page draws it too - a
 * trailer pressed on that page plays in its hero, with the trailer settings
 * applied, exactly as Home's hero plays it - and a second implementation of
 * this stack (chunked googlevideo requests, the adaptive video+audio merge, the
 * start/re-buffer watchdogs, the identity-refusal handling) is how the two
 * would drift. The owner still owns the stops: a clean end, a failure and a
 * navigation away are the caller's callbacks here, not this composable's.
 */
@Composable
internal fun HeroInlineTrailerPlayer(
    source: PlayableSource,
    muted: Boolean,
    /**
     * Where playback starts. 0 for every normal play - this is only non-zero
     * for the one-shot retry after a mid-stream failure, where the trailer
     * picks up where it died instead of restarting.
     */
    startPositionMs: Long = 0L,
    modifier: Modifier = Modifier,
    /**
     * The player has drawn its FIRST frame. Until this is called the pooled
     * player's SurfaceView is an unpainted black rectangle - the hero keeps the
     * backdrop mounted over it until then (see HomeHero's crossfade), so the
     * trailer's first frame is what the crossfade reveals rather than the empty
     * surface behind it.
     */
    onFirstFrame: () -> Unit = {},
    onEnded: () -> Unit = {},
    /**
     * Playback failed; [positionMs] is where it failed, read while the player
     * still had the media loaded (see the error handler). Never called for a
     * failure in the last [HeroTrailerNearEndMs] of the trailer, because there
     * is nothing worth resuming there.
     */
    onFailed: (positionMs: Long) -> Unit = {},
    /**
     * YouTube refused the client identity for this playback (see
     * [isYouTubeIdentityBlocked]). The watch config has already been
     * invalidated by the time this fires.
     *
     * Returns whether the owner took its one client-exclusion re-resolve. The
     * refusal is NOT an identity verdict on the 2026-10-22 evidence - the same
     * signed URL served bytes=0-0, 0-1M and 0-<pos> while every offset read was
     * refused - so the owner is asked first, and only the owner knows whether
     * the session's one attempt is still unspent (see the hero's own
     * `trailerAttempt` budget). [durationMs] is passed so the owner can stamp
     * the death line the diagnostics report reads.
     */
    onIdentityBlocked: (positionMs: Long, durationMs: Long) -> Boolean = { _, _ -> false }
) {
    val context = LocalContext.current
    // One pooled player for every hero trailer: renderer initialization is
    // the expensive part of ExoPlayer startup, so TrailerPlayerPool hands
    // back the same instance across focus changes and this composable only
    // swaps the MediaSource. (remember(source) used to rebuild the whole
    // player on every resolved-source change.)
    // The owner the autoplay gate below reads. On a TV the app is frequently
    // only PAUSED behind the launcher rather than stopped, and the trailer
    // resolve is a network call that can outlive the Home press - so a trailer
    // that mounts AFTER the ON_PAUSE below already fired would otherwise start
    // playing over the launcher. The mount therefore decides whether to
    // autoplay from the owner's CURRENT state, not from an event it may have
    // missed (see the ON_PAUSE effect further down).
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember {
        TrailerPlayerPool.acquire {
            // SplitModeRenderersFactory, not a plain DefaultRenderersFactory: it
            // is what installs the downmix sink/processor, and the pipeline only
            // has to fold a >8-channel FLTP layout down to the output's channel
            // count for libswresample's swr_convert not to overflow its output
            // buffer (the ANDROID-Z SIGSEGV). A trailer is almost always <=5.1,
            // but "almost always" is not a guard - a stray 7.1 trailer would
            // crash Home the same way it once crashed the player. Same factory
            // the full player builds, same extension-renderer mode it used to
            // have (ON), so audio behavior is unchanged.
            val renderersFactory =
                SplitModeRenderersFactory(
                    context.applicationContext,
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                )

            ExoPlayer.Builder(context.applicationContext, renderersFactory)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(2_000, 12_000, 500, 1_000)
                        .build()
                )
                .build()
        }
    }

    DisposableEffect(exoPlayer, source) {

        // Fire TV suppresses debug logs, so surface what the player actually
        // receives -- this proves which resolver produced the source.
        Log.w(
            "HOME_HERO",
            "Hero player mounting with source: " + heroSourceOrigin(source)
        )

        // googlevideo signed URLs 403 unless the request carries the same
        // YouTube client User-Agent that resolved them and uses bounded
        // range requests. YoutubeChunkedDataSourceFactory handles that for
        // googlevideo hosts (1 MB chunks via OkHttp, ratebypass fallbacks)
        // and passes every other host straight through - the same stack the
        // full player uses for HLS and progressive playback. The resolved
        // source carries the UA of the client that signed its URL, so the
        // factory leads with it instead of the legacy hardcoded one.
        val mediaSourceFactory =
            DefaultMediaSourceFactory(
                YoutubeChunkedDataSourceFactory(
                    userAgentHint = when (source) {
                        is PlayableSource.Muxed -> source.userAgent
                        is PlayableSource.Adaptive -> source.userAgent
                    }
                ),
                DefaultExtractorsFactory()
            )

        when (source) {
            is PlayableSource.Muxed -> {
                val mediaItem = MediaItem.Builder()
                    .setUri(source.url)
                    .apply {
                        if (source.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                            setMimeType(MimeTypes.APPLICATION_M3U8)
                        }
                    }
                    .build()
                exoPlayer.setMediaSource(
                    mediaSourceFactory.createMediaSource(mediaItem)
                )
            }

            is PlayableSource.Adaptive -> {
                // Adaptive streams are video-only: the audio URL MUST be
                // merged in or the trailer plays silently. Same pattern
                // the fullscreen player uses (NativePlayerActivity).
                // Both URLs are googlevideo, so the chunked factory
                // serves both.
                val videoSource = mediaSourceFactory
                    .createMediaSource(MediaItem.fromUri(source.videoUrl))
                val audioSource = mediaSourceFactory
                    .createMediaSource(MediaItem.fromUri(source.audioUrl))
                exoPlayer.setMediaSource(MergingMediaSource(videoSource, audioSource))
            }
        }

        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.volume = if (muted) 0f else 1f
        // Only autoplay while the owner is still started: mounting into a
        // paused/stopped app (the resolve finished behind the launcher) leaves
        // the pool silent until HomeHero's resume epoch re-resolves it.
        exoPlayer.playWhenReady =
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        exoPlayer.prepare()

        // Resume point for the post-failure retry. After prepare(), so the seek
        // applies to the media just set (a seek before prepare is dropped with
        // the timeline), and only when there is one: a first play never seeks.
        if (startPositionMs > 0L) {
            exoPlayer.seekTo(startPositionMs)
        }

        onDispose {
            // Intentionally empty: during a crossfade this instance can be
            // the OUTGOING content while the incoming one already prepared
            // new media on the shared pooled player, so a stop here would
            // kill the new trailer. HomeHero owns the stops instead (backdrop
            // transitions, ON_STOP, navigation teardown).
        }
    }

    // Volume tracks the mute toggle without re-prepping media (re-prepping
    // would restart the trailer from the beginning mid-viewing).
    DisposableEffect(exoPlayer, muted) {
        exoPlayer.volume = if (muted) 0f else 1f
        onDispose { }
    }

    // Backgrounding the app (TV Home press, input switch) does not dispose the
    // composition, so the DisposableEffect cleanups above never run and the
    // trailer audio keeps playing over other apps. PAUSE - not STOP - is what
    // is handled: Fire OS often leaves the app merely paused behind its
    // launcher (no stop at all), and ON_PAUSE precedes ON_STOP wherever the
    // activity really does stop, so one handler covers both. The resume epoch
    // in HomeHero re-resolves and re-preps it when the app returns.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        TrailerPlayerPool.pauseCurrent()
    }

    DisposableEffect(exoPlayer, source) {
        val handler = android.os.Handler(
            android.os.Looper.getMainLooper()
        )

        // Watchdog with two budgets: a short one before the first frame
        // (so the hero never hangs on a blank gray screen), and a much
        // longer one for a mid-play re-buffer (so a healthy trailer is not
        // killed at the first hiccup).
        val watchdog = object : Runnable {
            override fun run() {
                val state = exoPlayer.playbackState
                val playing = exoPlayer.isPlaying
                if (state != Player.STATE_READY || !playing) {
                    Log.w(
                        "HOME_HERO",
                        "Inline trailer stuck (state=$state playing=$playing); " +
                            "falling back to backdrop"
                    )
                    onEnded()
                }
            }
        }

        var startedPlaying = false

        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(
                    playbackState: Int
                ) {
                    if (playbackState == Player.STATE_ENDED) {
                        handler.removeCallbacks(watchdog)
                        onEnded()
                    } else if (playbackState == Player.STATE_READY && exoPlayer.isPlaying) {
                        startedPlaying = true
                        handler.removeCallbacks(watchdog)
                    } else if (playbackState == Player.STATE_BUFFERING && startedPlaying) {
                        // Re-buffer mid-playback: give it the long grace before
                        // falling back to the backdrop.
                        handler.removeCallbacks(watchdog)
                        handler.postDelayed(watchdog, HeroTrailerRebufferGraceMs)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        startedPlaying = true
                        handler.removeCallbacks(watchdog)
                    }
                }

                override fun onRenderedFirstFrame() {
                    // The surface has a real frame on it now, so the hero can
                    // drop the backdrop it was holding over the (until now
                    // black) player surface.
                    onFirstFrame()
                }

                override fun onPlayerError(error: PlaybackException) {
                    // A failed trailer must never leave a blank hero:
                    // fall back to the backdrop image immediately, then
                    // give the caller one shot at re-resolving a fresh
                    // signed URL (googlevideo URLs can go stale mid-stream).
                    //
                    // Read where it died FIRST. onEnded() drops the source,
                    // which hands the pooled player back for reuse, and a
                    // released player reports position 0 - so a position read
                    // after the fallback would resume every retry from the
                    // top, which is the whole bug this fixes.
                    val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val durationMs = exoPlayer.duration
                    Log.e(
                        "HOME_HERO",
                        "Inline trailer playback failed: ${error.errorCodeName} " +
                            "at ${positionMs}ms of " +
                            (if (durationMs > 0L) "${durationMs}ms" else "unknown length"),
                        error
                    )
                    // A player-visible 403 is not a mode mismatch: the chunked
                    // source already cycled every client UA / ratebypass /
                    // bounded-range mode and every one was refused, which means
                    // YouTube is refusing the client IDENTITY (the cached
                    // watch config's visitor data). Re-resolving with that same
                    // identity would only re-sign a URL it already said no to,
                    // so the config goes first and the next resolve - whenever
                    // the hero naturally makes one - fetches a fresh visitor.
                    // Deliberately 403-on-googlevideo only: a timeout, a 5xx or
                    // an addon host's own refusal keeps the cached identity and
                    // the ordinary retry.
                    val identityBlocked = error.isYouTubeIdentityBlocked()
                    var resumeRequested = false
                    if (identityBlocked) {
                        InnerTubeExtractor.invalidateConfig()
                        // The owner decides, because the one-attempt budget for
                        // a trailer is its state, not this composable's: one
                        // client-exclusion re-resolve is worth spending, a
                        // second one on the same clip is not.
                        resumeRequested = onIdentityBlocked(positionMs, durationMs)
                    }
                    handler.removeCallbacks(watchdog)
                    onEnded()
                    if (
                        shouldRetryHeroTrailerAfterError(
                            positionMs,
                            durationMs,
                            isIdentityBlocked = identityBlocked
                        )
                    ) {
                        onFailed(positionMs)
                    } else if (identityBlocked) {
                        // Ambient content: a refusal the app cannot fix has to
                        // be invisible. The backdrop is already up (onEnded
                        // above), there is no error card or spinner anywhere on
                        // this path, and not retrying is what removes the
                        // re-mount / re-buffer stutter the field log recorded.
                        //
                        // When the owner DID take its one client-exclusion
                        // resume, the re-mount is deliberate and the line says
                        // so - a capture must not read the recovery as the old
                        // "skipped" path.
                        Log.w(
                            "HOME_HERO",
                            if (resumeRequested) {
                                "Trailer refused by YouTube for this identity; " +
                                    "re-resolving once with another client"
                            } else {
                                "Trailer refused by YouTube for this identity; " +
                                    "skipping the retry and leaving the backdrop up"
                            }
                        )
                    } else {
                        Log.w(
                            "HOME_HERO",
                            "Trailer failed ${durationMs - positionMs}ms from the end; " +
                                "letting it end instead of replaying its tail"
                        )
                    }
                }
            }

        exoPlayer.addListener(listener)

        // Give the trailer the short start budget to render a first frame;
        // if it hasn't, fall back to the backdrop.
        handler.postDelayed(watchdog, HeroTrailerStartGraceMs)

        onDispose {
            handler.removeCallbacks(watchdog)
            exoPlayer.removeListener(listener)
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode =
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                setShutterBackgroundColor(
                    android.graphics.Color.TRANSPARENT
                )

                player = exoPlayer
                keepScreenOn = true
            }
        },
        modifier = modifier
    )
}

/**
 * The hero's backdrop art, as one composable.
 *
 * It is drawn in TWO places - as the whole hero when no trailer is resolved,
 * and as the layer held over the trailer player until its first frame renders -
 * so it lives here rather than being written out twice.
 *
 * 1280x720, not 1920x1080: the hero draws at roughly 1150x520, so the extra
 * pixels were never on screen - they only doubled the bitmap (8 MB of
 * ARGB_8888) and the decode/upload for every new backdrop a settled focus
 * brought in. Visually identical at the size it renders. The Home hero backdrop
 * is the one image the whole screen is built around, so it keeps the fade.
 */
@Composable
private fun HeroBackdropArt(url: String?) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(url) {
            ImageRequest.Builder(context)
                .data(url)
                .size(Size(1280, 720))
                .crossfade(true)
                .build()
        },
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        alignment = Alignment.Center
    )
}


@Composable
private fun HeroClearLogo(
    url: String,
    name: String,
    modifier: Modifier = Modifier
) {
    var logoIsDark by remember(url) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Has the art actually PAINTED? A logo URL that exists is not a logo on
    // screen: the fetch takes a moment, and it is a fetch again whenever Coil's
    // decoded-bitmap cache has been dropped (which is this device's normal
    // state - see com.kennyb1201.kbstream.data.memory.releaseImageMemoryCache),
    // or when the hero is the first thing in the session to want that art. The
    // blank box that left is the same one the 404 case below was fixed for, and
    // the same bug the pre-playback splash had: a title that is missing and
    // then there on the next load, because that one is a cache hit.
    var artPainted by remember(url) { mutableStateOf(false) }

    // The caller hands this a fixed box (see heroLogoWidth/heroLogoHeight), so
    // the name can stand in the space the logo will take and nothing under it
    // moves when the art lands. The name staying up IS the failure case, which
    // is what the removed loadFailed branch used to do by hand.
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (!artPainted) {
            Text(
                text = name,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                // The hero logo lands on the hero backdrop (which fades
                // too), so it fades in with it rather than popping onto
                // finished art. This is the hero, not a rail tile.
                .crossfade(true)
                .build(),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                artPainted = true
                // Downscale, sample and luminance-sum the bitmap OFF the main
                // thread: this runs on the hero's focus path (every logo
                // change), and the analysis is real bitmap work that hitched
                // the frame it landed on. The tint arrives a frame later at
                // most, which is the same visual result without the stall.
                val image = state.result.image
                scope.launch {
                    val dark = withContext(Dispatchers.Default) {
                        runCatching {
                            isDarkMonochromeArtwork(image.toBitmap())
                        }.getOrDefault(false)
                    }
                    logoIsDark = dark
                }
            },
            colorFilter = if (logoIsDark) ColorFilter.tint(KBTextHi) else null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun isDarkMonochromeArtwork(bitmap: android.graphics.Bitmap): Boolean {
    val sample = android.graphics.Bitmap.createScaledBitmap(bitmap, 48, 24, true)
    var luminanceSum = 0L
    var saturationSum = 0L
    var count = 0L

    for (y in 0 until sample.height) {
        for (x in 0 until sample.width) {
            val pixel = sample.getPixel(x, y)
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha > 20) {
                val red = (pixel ushr 16) and 0xFF
                val green = (pixel ushr 8) and 0xFF
                val blue = pixel and 0xFF
                luminanceSum +=
                    (0.2126f * red + 0.7152f * green + 0.0722f * blue).toLong()
                saturationSum += (maxOf(red, green, blue) - minOf(red, green, blue)).toLong()
                count++
            }
        }
    }

    if (sample !== bitmap) sample.recycle()
    if (count == 0L) return false

    return luminanceSum / count < 148L && saturationSum / count < 42L
}

/**
 * Public alias so KB FOLLOW_LAYOUT folder screens can render the exact
 * same hero (clearlogo, gradients, metadata, inline trailer) as Home —
 * keeping one implementation guarantees the two stay identical.
 */
@Composable
internal fun HomeHeroArtwork(
    preview: MetaPreview,
    meta: Meta?,
    tmdbDetail: TmdbDetail?,
    heroBackdropUrl: String?,
    heroLogoUrl: String?,
    trailerKey: String?,
    autoPlayTrailer: Boolean,
    muted: Boolean,
    heroHeight: Dp = HomeHeroHeight,
    heroLogoWidth: Dp = HeroLogoWidth,
    heroLogoHeight: Dp = HeroLogoHeight,
    heroLogoUrls: List<String> = emptyList()
) {
    HomeHero(
        preview = preview,
        meta = meta,
        tmdbDetail = tmdbDetail,
        heroBackdropUrl = heroBackdropUrl,
        heroLogoUrl = heroLogoUrl,
        trailerKey = trailerKey,
        autoPlayTrailer = autoPlayTrailer,
        muted = muted,
        heroHeight = heroHeight,
        heroLogoWidth = heroLogoWidth,
        heroLogoHeight = heroLogoHeight,
        heroLogoUrls = heroLogoUrls
    )
}

@Composable
private fun HomeHero(
    preview: MetaPreview,
    meta: Meta?,
    tmdbDetail: TmdbDetail?,
    heroBackdropUrl: String?,
    heroLogoUrl: String?,
    trailerKey: String?,
    autoPlayTrailer: Boolean,
    muted: Boolean,
    continueWatchingItem: UpNextItem? = null,
    heroHeight: Dp = HomeHeroHeight,
    heroLogoWidth: Dp = HeroLogoWidth,
    heroLogoHeight: Dp = HeroLogoHeight,
    // A focused Browse tile's service/studio mark as its WHOLE ranked
    // candidate list (see BrowseShortcutArt). Drawn through BrandMarkLogo so
    // the hero walks past a mark that cannot be drawn on the dark panel - the
    // same walk the tile does - instead of showing nothing, which is what a
    // single top-ranked plate used to do here. Empty for a catalog title, a KB
    // folder and the non-browse cases, which keep HeroClearLogo below.
    heroLogoUrls: List<String> = emptyList(),
    // Whether this item's art AND words have been published together (see
    // HomeViewModel.heroSettledKey). The copy fade/rise runs on that moment
    // rather than on the focus change alone: a hop that outruns the resolve
    // never gets a second act, and an item that settles transitions once.
    copySettled: Boolean = true,
    // A hero that is nothing but a mark or a name - the Browse chip a tile
    // hands up - centres that in the whole hero instead of anchoring it to the
    // bottom. A catalog title and a KB folder are letters + metadata + buttons
    // stacked up from the seam, so they keep the bottom anchor; a browse chip
    // brings no metadata at all, and left on the bottom anchor its mark (or the
    // genre/decade name standing in for one) floats in the lower third of an
    // otherwise empty panel.
    centerLogo: Boolean = false
) {
    val context = LocalContext.current
    val title = meta?.name ?: preview.name

    // The hero fills the screen, so its backdrop art is pulled at TMDB's full
    // resolution - HERE, at the display site, and not where the URL is
    // resolved: the same resolved w1280 URL is a landscape rail card's
    // backdrop too (see TmdbRepository.BACKDROP_BASE_FULL), and upgrading it in
    // HomeViewModel would rewrite that card's Coil cache key as well.
    //
    // Only the backdrop rungs are upgraded. The two poster rungs under them are
    // not backdrops: "original" on a poster would hand a portrait still to a
    // 16:9 slot at several times the bytes.
    // ...and a viewer who chose a different still for the hero wins over all of
    // it: this is the surface the Media row's "Set as Home hero backdrop" row
    // names (see ImageOverrides).
    val overridePaths = rememberImageOverridePaths()
    val backdropArt = imageOverrideBackdropUrl(
        paths = overridePaths,
        tmdbId = tmdbDetail?.id,
        screen = ImageOverrides.SCREEN_HOME_HERO,
        fallbackUrl = TmdbRepository.fullResBackdropUrl(
            heroBackdropUrl ?: meta?.background ?: preview.background
        )
    )
    val backdrop = backdropArt
        ?: meta?.poster
        ?: preview.poster

    // TMDB's English wordmark, and nothing else. The hero used to fall back to
    // the add-on's own `logo` (via the resolved meta and the raw preview) when
    // no English TMDB mark existed - and an add-on's logo is a bare URL with no
    // language on it, so a localized catalog put its locale's wordmark in the
    // hero. With no English mark the hero shows its plain title instead.
    val clearLogo = heroLogoUrl

    // Whether the browse brand mark was rejected in full: the hero then shows
    // the shortcut's name, mirroring the tile's own fallback.
    var brandMarkUnusable by remember(heroLogoUrls) { mutableStateOf(false) }

    val trailerPlaying =
        !trailerKey.isNullOrBlank() && autoPlayTrailer

    // Inline ExoPlayer is the only hero trailer path. The YouTube web
    // embed was tried but renders a gray screen with audio + subtitles on
    // some TVs and has no reliable end-of-video signal. ExoPlayer shares
    // the main player's proven stack (OkHttp + the youtube client UA +
    // bounded range requests via YoutubeChunkedDataSourceFactory), renders
    // no subtitles, and reports ENDED so the hero returns to the backdrop.
    // The resolved trailer AND the position its playback starts at, as one
    // value (see HeroTrailerPlayback): the resume point travels with the source
    // it belongs to, so a re-arm for any other reason cannot pick up a stale
    // position from a previous session.
    var resolvedTrailer by remember(trailerKey) {
        mutableStateOf<HeroTrailerPlayback?>(null)
    }

    // One-shot handoff from the failure below to the resolve that retries it:
    // written when a retry is actually going to happen, read (and cleared) by
    // that retry's resolve. Deliberately NOT kept as "where playback is": a
    // value like that would follow the viewer into the next play.
    var trailerResumeMs by remember(trailerKey) { mutableLongStateOf(0L) }

    // The client to skip when a refused trailer is re-resolved, one-shot for
    // the same reason as the resume above: the exclusion belongs to the source
    // that just died, and a foreground re-arm must not inherit it.
    var trailerExcludeClient by remember(trailerKey) { mutableStateOf<String?>(null) }

    // How long the trailer said it was when it died - the diagnostics death line
    // reports the death point against it, and it is only known at the failure,
    // not at the resolve that follows. Consumed with the same one-shot resume.
    var trailerDeathDurationMs by remember(trailerKey) { mutableLongStateOf(0L) }

    // One-shot playback-failure retry: googlevideo signed URLs can go stale
    // mid-stream (403 on a later chunk). Re-resolving fetches a fresh URL —
    // capped at a single retry per trailer so a genuinely dead video can't
    // loop resolve → mount → 403 forever.
    var trailerAttempt by remember(trailerKey) { mutableStateOf(0) }

    // How many times YouTube has refused THIS trailer in this session.
    //
    // A refusal never spends the retry above (a refused identity cannot be
    // recovered by re-signing - see shouldRetryHeroTrailerAfterError), so this
    // counter is what remembers that a refusal happened. The first one
    // invalidates the watch config, which means the resolve that follows it is
    // signed with a FRESH visitor: a second refusal is therefore real evidence
    // that the fresh identity is refused too, and is what marks the video
    // blocked for the session.
    var trailerIdentityRefusals by remember(trailerKey) { mutableStateOf(0) }

    // Re-arm the trailer when the app returns to the foreground: ON_PAUSE
    // silenced the player (audio-leak fix), so bump the epoch to drop the
    // stale source and re-resolve instead of leaving a paused/blank player.
    // ON_PAUSE, not ON_STOP: Fire OS often only PAUSES the app behind its
    // launcher, and a trailer paused in that window has to be re-armed too -
    // ON_STOP would leave it frozen on the first frame.
    var appInBackground by remember { mutableStateOf(false) }
    var resumeEpoch by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { appInBackground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (appInBackground) {
            appInBackground = false
            resumeEpoch += 1
        }
    }

    // Backdrop transitions (trailer ended, watchdog bail, resume re-arm)
    // must silence the pooled player immediately: the inline player's own
    // dispose can't do it -- during a crossfade the outgoing instance is
    // disposed AFTER the incoming one already prepared new media, and a stop
    // there would kill the new trailer.
    LaunchedEffect(resolvedTrailer) {
        if (resolvedTrailer == null) {
            TrailerPlayerPool.releaseForReuse()
        }
    }

    // Leaving Home entirely (Details, Settings, Search) disposes the hero
    // while the pooled player survives composition; without this its audio
    // would keep playing over the next screen.
    DisposableEffect(Unit) {
        onDispose { TrailerPlayerPool.releaseForReuse() }
    }

    LaunchedEffect(trailerPlaying, trailerKey, trailerAttempt, resumeEpoch) {
        resolvedTrailer = null
        // Consume the resume position WITH this resolve: a retry seeks to it,
        // and every other reason to re-run this effect (foreground re-arm,
        // autoplay toggle) starts from the top because the value is gone.
        val resumeFrom = trailerResumeMs
        trailerResumeMs = 0L
        // Consumed with it: the exclusion is part of the same one-shot retry.
        val excludeClient = trailerExcludeClient
        trailerExcludeClient = null
        val deathDurationMs = trailerDeathDurationMs
        trailerDeathDurationMs = 0L

        if (trailerPlaying && !trailerKey.isNullOrBlank()) {
            // Non-null from the guard above, and captured by the lambdas below:
            // naming it once keeps the death line and the resolve on the same
            // id without a smart cast crossing a lambda boundary.
            val key = trailerKey
            // A video YouTube already refused on a fresh identity is not
            // resolved again this session: the retry below would invalidate
            // (which lifts the block) and then pay a watch-page fetch + full
            // resolve on every foreground return, for a URL that will 403.
            // Checked BEFORE the retry's invalidate for exactly that reason.
            if (TrailerPlayerLauncher.isIdentityBlocked(trailerKey)) {
                Log.w(
                    "HOME_HERO",
                    "Hero trailer key=$trailerKey is blocked by YouTube this " +
                        "session; keeping the backdrop"
                )
                return@LaunchedEffect
            }
            if (trailerAttempt > 0) {
                // Bust the cached source so the retry gets a fresh signed URL.
                TrailerPlayerLauncher.invalidate(trailerKey)
                Log.w(
                    "HOME_HERO",
                    "Retrying hero trailer after playback failure " +
                        "(attempt=$trailerAttempt key=$trailerKey)"
                )
            }
            Log.w(
                "HOME_HERO",
                "Resolving hero trailer key=$trailerKey"
            )

            TrailerPlayerLauncher
                .resolvePlayableUrl(trailerKey, excludeClient = excludeClient)
                .onSuccess { source ->
                    resolvedTrailer = HeroTrailerPlayback(
                        source = source,
                        startPositionMs = resumeFrom
                    )
                    if (excludeClient != null) {
                        // The resume the refusal asked for: name the client that
                        // signed the replacement, so a capture shows the fix
                        // working rather than inferring it from a smooth play.
                        PoTokenTrace.recordTrailerDeath(
                            videoId = key,
                            client = excludeClient,
                            kind = PoTokenTrace.resolveKind(),
                            positionMs = resumeFrom,
                            durationMs = deathDurationMs,
                            resume = "client " +
                                (TrailerPlayerLauncher.signingClient(trailerKey)
                                    ?: "unknown") + " ok"
                        )
                    }
                    Log.w(
                        "HOME_HERO",
                        "Hero trailer resolved: " +
                            heroSourceOrigin(source) +
                            if (excludeClient != null) " (excluded $excludeClient)" else ""
                    )
                }
                .onFailure { error ->
                    if (excludeClient != null) {
                        PoTokenTrace.recordTrailerDeath(
                            videoId = key,
                            client = excludeClient,
                            kind = PoTokenTrace.resolveKind(),
                            positionMs = resumeFrom,
                            durationMs = deathDurationMs,
                            resume = "none: no other client"
                        )
                    }
                    Log.e(
                        "HOME_HERO",
                        "Failed to resolve hero trailer, " +
                            "keeping backdrop",
                        error
                    )
                }
        } else {
            Log.w(
                "HOME_HERO",
                "Hero trailer skipped (trailerPlaying=$trailerPlaying key=$trailerKey)"
            )
        }
    }

    val year = if (preview.type == "movie") {
        tmdbDetail?.releaseYear()
            ?: meta?.releaseInfo
                ?.let {
                    Regex("""\b(?:19|20)\d{2}\b""")
                        .find(it)
                        ?.value
                }
    } else {
        val startYear =
            tmdbDetail?.firstAirDate
                ?.take(4)
                ?.takeIf {
                    it.length == 4 &&
                        it.all(Char::isDigit)
                }
                ?: tmdbDetail?.releaseYear()

        val endYear =
            tmdbDetail?.lastEpisodeToAir
                ?.airDate
                ?.take(4)
                ?.takeIf {
                    it.length == 4 &&
                        it.all(Char::isDigit)
                }

        val isStillRunning =
            when (
                tmdbDetail?.status
                    ?.trim()
                    ?.lowercase()
            ) {
                "returning series",
                "in production",
                "planned" -> true

                else -> false
            }

        when {
            startYear == null -> null

            isStillRunning -> "$startYear–"

            !endYear.isNullOrBlank() &&
                endYear != startYear ->
                "$startYear–$endYear"

            tmdbDetail?.status
                ?.trim()
                ?.equals(
                    "ended",
                    ignoreCase = true
                ) == true ->
                startYear

            else -> startYear
        }
    }

    val rating =
        tmdbDetail
            ?.certification(
                preview.type == "movie"
            )
            ?.takeIf {
                it.isNotBlank()
            }
            ?: meta?.releaseInfo
                ?.let {
                    Regex(
                        """\b(?:PG-13|NC-17|TV-Y7|TV-Y|TV-G|TV-PG|TV-14|TV-MA|PG|G|R)\b"""
                    )
                        .find(
                            it.uppercase()
                        )
                        ?.value
                }

    // Status tag: series show their TMDB lifecycle (Ongoing / Ended /
    // Canceled / ...). TMDB's movie status is only the production
    // lifecycle, so movie tags are derived from release dates instead
    // (In Theaters / Streaming / Coming Soon / ...).
    val statusTag =
        if (preview.type.equals("movie", ignoreCase = true)) {
            tmdbDetail?.movieStatusTag()
        } else {
            when (
                tmdbDetail?.status
                    ?.trim()
                    ?.lowercase()
            ) {
                "returning series" -> "Ongoing"
                "ended" -> "Ended"
                "canceled",
                "cancelled" -> "Canceled"
                "in production" -> "In Production"
                "planned" -> "Planned"
                else -> null
            }
        }

    val imdb =
        if (continueWatchingItem != null) {
            continueWatchingItem.imdbRating
                ?.let {
                    "IMDb %.1f".format(it)
                }
                ?: meta?.imdbRating
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        "IMDb $it"
                    }
        } else {
            meta?.imdbRating
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    "IMDb $it"
                }
        }

    val runtime =
        meta?.runtime
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        val genre =
        meta?.genres
            ?.firstOrNull()
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

    val seasonEpisodeCount =
        if (preview.type != "movie") {
            tmdbDetail?.displaySeasonEpisodeCount()
        } else {
            null
        }

    // One ellipsized line. IMDb is the meta line's own rating - no MDBList
    // tokens are folded in, so the line stays short enough that its tail is
    // never ellipsized away on a TV. The detail page keeps the full rating
    // chip strip. Runtime sits here beside the year and rating.
    val heroInfoParts =
        listOfNotNull(
            imdb,
            year,
            rating,
            runtime,
            genre
        )

    val heroInfo =
        heroInfoParts.joinToString(" • ")

    // Regular hero description.
    // Prefer TMDB's overview, then addon metadata, then preview metadata.
    val heroDescription =
    tmdbDetail?.displayDescription()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: meta?.description
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        ?: preview.description
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        
    val continueEpisodeLabel =
        continueWatchingItem?.let { item ->
            val prefix =
                when {
                    item.isSeriesFinale ->
                        "Series Finale"

                    item.isSeasonFinale ->
                        "Season Finale"

                    // Upcoming-rail items carry their relative air-date
                    // label — the hero says "Airs Today" / "Airs In 5 days"
                    // instead of the generic "Next Up".
                    item.airDateLabel != null ->
                        "Airs ${item.airDateLabel}"

                    else ->
                        when (item.badge) {
                            UpNextBadge.CONTINUE_WATCHING ->
                                "Resume"

                            UpNextBadge.NEXT_UP ->
                                "Next Up"

                            UpNextBadge.NEW_EPISODE ->
                                "New Episode"

                            UpNextBadge.NEW_SEASON ->
                                "New Season"
                        }
                }

            // The pair is spelled by the same rule the card uses, so the
            // hero cannot print the E00 the card beside it already refuses.
            upNextHeroEpisodeLabel(
                prefix,
                item.season,
                item.episode
            )
        }

    val continueProgress =
        continueWatchingItem
            ?.progressPercent
            ?.coerceIn(0f, 1f)

    val continueTimeLeft =
        formatTimeLeft(continueWatchingItem?.remainingMinutes)

        val continueEpisodeCount =
    continueWatchingItem?.let { item ->                                                // episodesWatchedForDisplay rather than the raw field: a show
        // with nothing completed yet carries no watched count at all, and
        // reading it directly is what hid this line for the one title a
        // viewer has just started (see UpNextItem).
        val watched = item.episodesWatchedForDisplay
        val total = item.episodesTotal

        if (
            watched != null &&
            total != null &&
            total > 0
        ) {
            "$watched of $total aired episodes watched"
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.4f)
                    .background(Color.Black)
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.6f)
            ) {
            
                Crossfade(
                    targetState = resolvedTrailer,
                    label = "hero_backdrop_crossfade"
                ) { trailerPlayback ->
                    if (trailerPlayback != null) {
                        // The pooled player is an unpainted SurfaceView: until
                        // its first frame is drawn the surface is a black
                        // rectangle, so the crossfade used to reveal BLACK
                        // and the trailer popped in a moment later - and a
                        // trailer that never rendered kept that black up for
                        // the whole watchdog budget. Keep the backdrop mounted
                        // ON TOP of the player and fade it out only once
                        // Player.Listener.onRenderedFirstFrame reports a real
                        // frame underneath, so the crossfade reveals the
                        // trailer's first frame rather than an empty surface.
                        var firstFrameRendered by remember(trailerPlayback) {
                            mutableStateOf(false)
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            HeroInlineTrailerPlayer(
                                source = trailerPlayback.source,
                                muted = muted,
                                startPositionMs = trailerPlayback.startPositionMs,
                                modifier = Modifier.fillMaxSize(),
                                onFirstFrame = { firstFrameRendered = true },
                                onEnded = {
                                    // Video finished (or the watchdog gave up
                                    // on a stuck player): drop the source so
                                    // the hero crossfades back to the
                                    // backdrop.
                                    resolvedTrailer = null
                                },
                                onFailed = { positionMs ->
                                    // Playback error (e.g. the signed URL went
                                    // stale and a chunk 403'd): drop the source
                                    // and retry resolution once with a fresh
                                    // URL, resuming where it died. onEnded
                                    // still fires first, so the backdrop shows
                                    // immediately either way.
                                    if (trailerAttempt < 1) {
                                        // Record the resume point ONLY when the
                                        // retry is really going to happen: a
                                        // position written on the capped second
                                        // failure would sit there and be picked
                                        // up by the next re-arm (a foreground
                                        // return), seeking a fresh play to a
                                        // dead position.
                                        trailerResumeMs = positionMs
                                        trailerAttempt += 1
                                    } else {
                                        resolvedTrailer = null
                                    }
                                },
                                onIdentityBlocked = { positionMs, durationMs ->
                                    // The first refusal already invalidated the
                                    // watch config, so the resolve that follows
                                    // it is signed with a fresh visitor AND, now,
                                    // a different client: the 2026-10-22 log is
                                    // a URL class that refuses offset reads, not
                                    // an identity verdict. A second refusal of
                                    // the same video is therefore the fresh
                                    // identity and the replacement both being
                                    // refused, and that is when the video is
                                    // marked for the session - so a later re-arm
                                    // goes straight to the backdrop instead of
                                    // buying a watch-page fetch every time.
                                    val key = trailerKey
                                    trailerIdentityRefusals += 1
                                    if (trailerIdentityRefusals >= 2) {
                                        trailerKey?.let {
                                            TrailerPlayerLauncher.markIdentityBlocked(it)
                                        }
                                        val died = key?.let {
                                            TrailerPlayerLauncher.signingClient(it)
                                        }
                                        if (key != null) {
                                            PoTokenTrace.recordTrailerDeath(
                                                videoId = key,
                                                client = died,
                                                kind = PoTokenTrace.resolveKind(),
                                                positionMs = positionMs,
                                                durationMs = durationMs,
                                                resume = "client " +
                                                    (died ?: "unknown") + " died"
                                            )
                                        }
                                        false
                                    } else if (key != null && trailerAttempt < 1) {
                                        // One re-resolve, signed by a client
                                        // other than the one that just died,
                                        // resumed where it did.
                                        val deadClient =
                                            TrailerPlayerLauncher.signingClient(key)
                                        trailerExcludeClient = deadClient
                                        trailerResumeMs = positionMs
                                        trailerDeathDurationMs = durationMs
                                        PoTokenTrace.recordTrailerDeath(
                                            videoId = key,
                                            client = deadClient,
                                            kind = PoTokenTrace.resolveKind(),
                                            positionMs = positionMs,
                                            durationMs = durationMs,
                                            resume = "none"
                                        )
                                        trailerAttempt += 1
                                        true
                                    } else {
                                        false
                                    }
                                }
                            )
                            Crossfade(
                                targetState = firstFrameRendered,
                                label = "hero_first_frame_reveal",
                                modifier = Modifier.fillMaxSize()
                            ) { rendered ->
                                if (!rendered) {
                                    HeroBackdropArt(backdrop)
                                }
                            }
                        }
                    } else {
                        HeroBackdropArt(backdrop)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    // Anchor stays fully opaque so this
                                    // still blends seamlessly into the
                                    // solid black column beside it -- only
                                    // the falloff was shortened so less of
                                    // the backdrop gets darkened by it.
                                    0.00f to Color.Black.copy(alpha = 1.00f),
                                    0.06f to Color.Black.copy(alpha = 0.80f),
                                    0.14f to Color.Black.copy(alpha = 0.50f),
                                    0.22f to Color.Black.copy(alpha = 0.24f),
                                    0.32f to Color.Black.copy(alpha = 0.08f),
                                    0.42f to Color.Transparent,
                                    1.00f to Color.Transparent
                                )
                            )
                        )
                )
            }
        }

        // Bottom seam gradient: blends the backdrop into the rails below.
        // Skipped while an inline trailer is playing — otherwise its fully
        // opaque bottom edge paints a dark band across the video.
        if (resolvedTrailer == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                .45f to Color.Transparent,
                                .62f to Color.Black.copy(alpha = .04f),
                                .72f to Color.Black.copy(alpha = .12f),
                                .82f to Color.Black.copy(alpha = .26f),
                                .90f to Color.Black.copy(alpha = .45f),
                                // Ramps the rest of the way to fully opaque
                                // right at the bottom edge so it matches the
                                // solid black behind the rails below exactly --
                                // everything above stays light so most of the
                                // hero isn't darkened just to blend this seam.
                                .96f to Color.Black.copy(alpha = .78f),
                                1f to Color.Black.copy(alpha = 1.00f)
                            )
                        )
                    )
            )
        }

        //
        // The copy moves on the same clock as the art behind it.
        //
        // The backdrop and the clearlogo cross-fade through Coil, but every word
        // over them - the fallback title, the metadata line, the episode title,
        // the resume label and its bar - was a plain recomposition keyed on the
        // focused item, so the words cut instantly on top of a picture that was
        // still fading. That is the most-visible incongruity on the screen
        // every session opens on: one element on a 200ms clock, the rest on
        // none.
        //
        // A single progress value drives the incoming copy rather than an
        // AnimatedContent crossing two copies of this column: the column is
        // bottom-anchored and as tall as its longest description, so two of
        // them alive at once would double the block's height for the length of
        // the fade and shove the buttons beneath it. One copy, fading up where
        // it sits, is the same read with no layout consequence - and it cannot
        // duplicate a focus target.
        val heroCopyKey = "${tmdbDetail?.id ?: meta?.id ?: preview.id}:${preview.type}"
        val heroCopyReducedMotion = rememberReducedMotion()
        val heroCopyProgress = remember { Animatable(1f) }
        // The key whose words have already been on screen.
        //
        // A COLD item's copy shows while its art resolves (the alternative - a
        // blank hero for a third of a second on every hop - reads as a flash),
        // and it must not fade in a SECOND time when the art lands. So the fade
        // belongs to a copy that has not been seen yet: an item that arrives
        // already settled (cached art, a folder, a browse chip). Each of those
        // gets exactly one; anything the viewer saw while it resolved gets none.
        var heroCopyShownKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(heroCopyKey, copySettled) {
            val alreadyOnScreen = heroCopyShownKey == heroCopyKey
            heroCopyShownKey = heroCopyKey
            if (!copySettled || heroCopyReducedMotion || alreadyOnScreen) {
                heroCopyProgress.snapTo(1f)
                return@LaunchedEffect
            }
            heroCopyProgress.snapTo(0f)
            heroCopyProgress.animateTo(
                1f,
                tween(durationMillis = KB_PANEL_ENTER_MS, easing = KB_EASE)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .graphicsLayer {
                    alpha = heroCopyProgress.value
                    // A short rise: the words arrive from just below where
                    // they land, the way the art fades up behind them.
                    translationY = (1f - heroCopyProgress.value) * 10.dp.toPx()
                }
                // 0.46f (was 0.4f): gives the hero logo/wordmark box room —
                // the logo's Modifier.width() coerces to this column, so a
                // bigger logo box without a wider column would clamp back.
                .fillMaxWidth(0.46f)
                .padding(
                    start = 32.dp,
                    end = 20.dp,
                    // The 16dp seam inset belongs to the bottom-anchored
                    // layout; centred, it would pull the logo up off the
                    // hero's true middle by half of it.
                    bottom = if (centerLogo) 0.dp else 16.dp
                ),
            verticalArrangement =
                if (centerLogo) Arrangement.Center else Arrangement.Bottom
        ) {
            // The logo/text slot is ONE fixed box: a clearlogo arriving late -
            // or never arriving - changes nothing about the column's height or
            // the position of everything under it, so the art landing can no
            // longer re-lay-out the copy. The plain-title fallback renders
            // INSIDE that same box for exactly that reason.
            //
            // The 10dp rise is cancelled here instead of being taken off the
            // whole column: the text keeps the motion it earns, while the logo
            // (a wordmark) fades only. A wordmark sliding vertically is the
            // motion that read as a jump on a switch.
            Box(
                modifier = Modifier
                    .width(heroLogoWidth)
                    .height(heroLogoHeight)
                    .graphicsLayer {
                        translationY = -(1f - heroCopyProgress.value) * 10.dp.toPx()
                    },
                contentAlignment = Alignment.BottomStart
            ) {
                if (heroLogoUrls.isNotEmpty() && !brandMarkUnusable) {
                    // A focused Browse tile's brand mark, walked past the
                    // candidates that cannot be drawn (see heroLogoUrls).
                    BrandMarkLogo(
                        urls = heroLogoUrls,
                        contentDescription = title,
                        onUnusable = { brandMarkUnusable = true },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (heroLogoUrls.isEmpty() && !clearLogo.isNullOrBlank()) {
                    HeroClearLogo(
                        url = clearLogo,
                        name = title,
                        // Fixed size, coerced by the parent column's constraints
                        // on narrow screens; Collection manifests get the larger
                        // box so their wordmark logos carry the hero.
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = title,
                        color = KBTextHi,
                        style = MaterialTheme.typography.headlineLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

                        // Main metadata line.
            if (heroInfo.isNotBlank()) {
                Text(
                    text = heroInfo,
                    color = KBTextHi.copy(alpha = 0.94f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }

            // Second line. For catalog items the TMDB status comes FIRST,
            // then the season/episode totals. Continue Watching items show no
            // TMDB status (Ongoing / Ended / Canceled / etc.), so they carry
            // no second line at all - their runtime rides the metadata line
            // above.
            val heroSecondaryParts =
                if (continueWatchingItem == null) {
                    listOfNotNull(statusTag, seasonEpisodeCount)
                } else {
                    emptyList<String>()
                }

            if (heroSecondaryParts.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    heroSecondaryParts.forEachIndexed { index, part ->
                        if (index > 0) {
                            Text(
                                // One separator spelling app-wide: Detail's
                                // meta line and this hero line used to split
                                // the same run of facts with different
                                // spacing - two spaces around the bullet here,
                                // one there.
                                text = " • ",
                                color = KBTextHi.copy(alpha = 0.94f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val isStatus =
                            continueWatchingItem == null &&
                                index == 0 &&
                                !statusTag.isNullOrBlank()

                        if (isStatus) {
                            Text(
                                text = part,
                                color = KBAccent,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = part,
                                color = KBTextHi.copy(alpha = 0.94f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Episode title FIRST, above the "Resume  •  S02 · E05" line and
            // the progress bar: the name of what is about to play is the
            // headline, and the resume state is the detail under it.
            val hidesHeroEpisodeTitle = rememberHidesEpisodeTitle(
                inProgress = continueWatchingItem?.badge == UpNextBadge.CONTINUE_WATCHING
            )
            if (!hidesHeroEpisodeTitle) {
                continueWatchingItem
                    ?.episodeTitle
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { episodeTitle ->
                        Text(
                            text = episodeTitle,
                            color = KBTextHi,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
            }

            continueEpisodeLabel?.let { label ->
                Text(
                    text = label,
                    color = KBAccent,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp)
                )

                continueProgress?.let { progress ->
                    // The shared bar. This was a fourth copy of it: 4dp and the
                    // 2dp radius were right, but the track was KBTextHi at 28%
                    // where the same bar under a poster is KBTextLo at 45%, so
                    // the hero's resume bar read brighter than the card's.
                    KBProgressBar(
                        progress = progress,
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .width(260.dp)
                    )
                }

            continueTimeLeft?.let { label ->
                Text(
                    text = label,
                    color = KBTextHi.copy(alpha = 0.70f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }

            continueEpisodeCount?.let { label ->
                Text(
                    text = label,
                    color = KBTextHi.copy(alpha = 0.70f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }

            // Upcoming-rail items: the real calendar date under the
            // "Airs …" label ("Mon, Sep 15"), styled like the other
            // hero detail lines. Skipped when the label already IS the
            // date — ≥7 days out the relative label formats as the
            // absolute date, so "Airs Thu, Sep 25" + the date line showed
            // the same date twice (the hero double-date on far-out items,
            // e.g. new-season premieres). Relative labels ("In 5 days",
            // "Today") keep the date line — there it adds real info.
            continueWatchingItem?.airDateFull
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { fullDate ->
                    continueWatchingItem.airDateLabel != fullDate
                }
                ?.let { fullDate ->
                    Text(
                        text = fullDate,
                        color = KBTextHi.copy(alpha = 0.70f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }

            if (continueWatchingItem != null) {
    // Only show the episode description for non-resume items (a finale
    // that is being resumed still has a saved position, so it behaves
    // like a plain resume card here).
    //
    // And not under spoiler-free, which is the point of [hidesHeroEpisodeTitle]
    // above: the episode's NAME is hidden there while its SYNOPSIS - the actual
    // spoiler text - was still drawn, so the mode hid the smaller of the two
    // leaks. The title's own latch is reused rather than re-derived; for every
    // badge that reaches this block it means "spoiler-free is on", because the
    // one badge that reads as in-progress here is excluded just below.
    if (
        !hidesHeroEpisodeTitle &&
        continueWatchingItem.badge !=
            UpNextBadge.CONTINUE_WATCHING &&
        continueWatchingItem.startPositionMs <= 0L
    ) {
        continueWatchingItem.episodeDescription
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let { description ->
                Text(
                    text = description,
                    color = KBTextHi.copy(alpha = 0.80f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp)
                )
            }
    }

    // Continue-watching items never show the show's/movie's metadata
    // description: resume items (CONTINUE_WATCHING) show no description
    // at all, and every other continue-watching badge shows only the
    // episode description above.
} else {
    heroDescription
        ?.trim()
        ?.takeIf {
            it.isNotBlank()
        }
        ?.let { description ->
            Text(
                text = description,
                color = KBTextHi.copy(alpha = 0.80f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
}
    }
}
}

                    

/**
 * Builds the rail header: optional addon name and catalog type around the
 * formatted catalog name, e.g. "AIOMetadata · Trending · Series".
 */
private fun homeRailTitle(
    catalogName: String,
    addonName: String,
    type: String,
    showType: Boolean,
    showAddon: Boolean
): String {

    val parts = buildList {

        if (showAddon && addonName.isNotBlank()) {
            add(addonName)
        }

        add(catalogName)

        if (showType && type.isNotBlank()) {
            add(type.replaceFirstChar { it.uppercase() })
        }
    }

    return parts.joinToString(" · ")
}

/**
 * Whether Home must hide an episode's own title on a card, under spoiler-free
 * mode.
 *
 * A Home card knows exactly one thing about where the viewer is: its badge.
 * `CONTINUE_WATCHING` is the episode they left off INSIDE, so its title is the
 * thing they are resuming and hiding it would make the row harder to use for no
 * gain. Every other badge (`NEXT_UP`, `NEW_EPISODE`, `NEW_SEASON`) offers an
 * episode they have not started, which is precisely what the mode exists to
 * keep off the screen. The rule itself lives once, in
 * [com.kennyb1201.kbstream.data.spoiler.SpoilerFree].
 */
@Composable
private fun rememberHidesEpisodeTitle(inProgress: Boolean): Boolean {
    val context = LocalContext.current
    // Read through the synced revision, not once per card: the mode is a SYNCED
    // pref, so switching it on another device has to re-title (or un-title) the
    // cards already on screen instead of waiting for Home to be rebuilt
    // (see [com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier.revision]).
    val spoilerFreeRevision by com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
        .revision.collectAsStateWithLifecycle()
    val spoilerFree = remember(context, spoilerFreeRevision) {
        AppPreferences.getSpoilerFree(context)
    }
    return SpoilerFree.hidesIdentity(
        enabled = spoilerFree,
        // Every card this is asked about is offering an episode the viewer has
        // not watched - that is the whole point of the card - so the guard is
        // entirely the progress.
        watched = false,
        started = inProgress
    )
}

@Composable
private fun UpcomingEpisodeCard(
    upcoming: UpcomingEpisode,
    onClick: () -> Unit,
    // Long press raises the same menu the other poster cards offer. Upcoming
    // cards used to ignore it entirely, so the one row that shows titles a
    // viewer most often wants GONE (a show they stopped watching) had no
    // in-place way to hide one - every other card did.
    onLongClick: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    // True only for the card at position 0 of its rail: Left from there has no
    // target and must not fall through to another rail (see
    // [homeRailHeadLeftEdgeStop]).
    isHeadCard: Boolean = false,
    // Non-null only when this card's rail is the one Home draws first (see
    // [homeTopRailUpHook]).
    onUpPressed: ((FocusRequester) -> Unit)? = null
) {
    var focused by remember {
        mutableStateOf(false)
    }

    PosterCard(
        posterUrl = upcoming.backdrop ?: upcoming.poster,
        contentDescription = upcoming.title,
        isWatched = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .width(224.dp)
            .height(146.dp)
            .padding(end = HomeRailGap)
            .homeRailHeadLeftEdgeStop(isHeadCard)
            .then(
                if (focusRequester == null) {
                    Modifier
                } else {
                    Modifier
                        .focusRequester(focusRequester)
                        .homeTopRailUpHook(focusRequester, onUpPressed)
                }
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocus()
                }
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            // Same 4-stop treatment as the Continue Watching
                            // cards: the old 5-stop gradient ended in TWO
                            // 100%-opaque stops, painting a flat solid-black
                            // band across the bottom third instead of a
                            // smooth fade.
                            colors = listOf(
                                // One scrim vocabulary on KBVoid, like Detail's:
                                // a black ramp under a card ended on a KBVoid
                                // stop, so the AMOLED toggle changed the bottom
                                // of the fade but not the top of it.
                                KBVoid.copy(alpha = 0.08f),
                                KBVoid.copy(alpha = 0.34f),
                                KBVoid.copy(alpha = 0.80f),
                                KBVoid.copy(alpha = 0.97f)
                            )
                        )
                    )
            )

            // Top chip: the AIR DATE (or NEW SEASON flag) — the thing that
            // makes this card "upcoming". A season premiere airing TODAY
            // still shows the TODAY chip: "NEW SEASON" says what it is, but
            // on premiere day "TODAY" is the urgent part (the date below
            // already says which day) — MobLand S2 landing today read as
            // "NEW SEASON / Thu, Sep 18" instead of "TODAY".
            if (upcoming.isSeasonPremiere && upcoming.airDateLabel != "Today") {
                Text(
                    text = "NEW SEASON",
                    color = KBTextHi,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(
                            color = KBPlum,
                            shape = KBShapeChip
                        )
                        // 6x3, the inner padding every other badge on Home's
                        // rails uses (see CompactUpNextCard): at 5x2 these two
                        // read a touch tighter than the same-role badges on the
                        // row beside them.
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            } else {
                Text(
                    text = upcoming.airDateLabel.uppercase(),
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(
                            color = KBAccent,
                            shape = KBShapeChip
                        )
                        // Same 6x3 as the NEW SEASON badge above (and every
                        // downstream rail badge): the pair sits on adjacent
                        // rails, so a 1dp difference between them is visible.
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }

            // Bottom block: calendar date first ("When is this on?" is the
            // primary question for an unaired episode), then show title,
            // then season/episode + episode title.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // Calendar date ("Mon, Sep 15") — promoted ABOVE the show
                // title: on upcoming cards the date outranks the title as
                // the scanning hook, and it's the only timing info NEW
                // SEASON cards carry (their top chip is the flag, not a
                // date).
                upcoming.airDateFull
                    .takeIf { it.isNotBlank() }
                    ?.let { fullDate ->
                        Text(
                            text = fullDate,
                            color = if (focused) {
                                KBTextHi.copy(alpha = 0.80f)
                            } else {
                                KBTextLo
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }

                Text(
                    text = upcoming.title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp).kbFocusMarquee(focused)
                )

                // The season/episode label the Continue Watching rail prints,
                // from its own rule: `S2 E8`, unpadded and spelled once for
                // both rails and both form factors (see [upNextEpisodeLabel]).
                // An Upcoming row always names a real pair, so the rule cannot
                // answer null here.
                val seLabel = upNextEpisodeLabel(
                    upcoming.season,
                    upcoming.episode
                ).orEmpty()
                // Show title -> S·E line -> episode title line.
                Text(
                    text = seLabel,
                    color = if (focused) {
                        KBTextHi.copy(alpha = 0.78f)
                    } else {
                        KBTextLo
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )

                // A card in the Upcoming rail is by definition an episode that
                // has not aired, so it has not been reached: nothing to weigh.
                val hidesUpcomingTitle = rememberHidesEpisodeTitle(inProgress = false)
                if (!hidesUpcomingTitle) {
                    upcoming.episodeTitle
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { episodeTitle ->
                            Text(
                                text = episodeTitle,
                                color = if (focused) {
                                    KBTextHi.copy(alpha = 0.88f)
                                } else {
                                    KBTextLo
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                }
            }
        }
    }
}

/**
 * The Home hero, and the focus/hero-art state it reads.
 *
 * This exists as its own composable because a state read is charged to the
 * scope that performs it: with these reads in `HomeScreen`'s body, every D-pad
 * step recomposed the whole screen — the hero, the rails LazyColumn and its
 * content lambdas, the top bar and the context menus — to change one image.
 * Taking the [State] objects and reading them HERE keeps the work in this
 * scope, so scrolling a rail only recomposes the hero. The two effects live
 * here for the same reason: their `LaunchedEffect` keys are composition-time
 * reads of that same state.
 */
@Composable
private fun HomeHeroHost(
    focusedItem: State<MetaPreview?>,
    focusedFolder: State<KBFolder?>,
    focusedBrowseShortcut: State<BrowseHomeShortcut?>,
    browseShortcutArt: Map<String, BrowseShortcutArt>,
    continueWatchingItem: State<UpNextItem?>,
    heroMeta: State<Meta?>,
    heroTmdbDetail: State<TmdbDetail?>,
    heroBackdropUrl: State<String?>,
    heroLogoUrl: State<String?>,
    heroTrailerKey: State<String?>,
    heroSettledKey: State<String?>,
    onResolveHeroMeta: (MetaPreview) -> Unit
) {
    val context = LocalContext.current

    // Pre-warm the hero trailer resolve: on a cold start the source cache is
    // empty, so the first post-dwell resolve otherwise starts the full
    // InnerTube -> NewPipe chain only AFTER the 4s dwell elapses. Kicking the
    // resolve off in the background the moment the key arrives means the
    // post-dwell resolve is usually a cache hit (the resolve mutex coalesces
    // both callers into one network flight) and playback starts immediately.
    LaunchedEffect(heroTrailerKey.value) {
        val key = heroTrailerKey.value
        if (
            !key.isNullOrBlank() &&
            AppPreferences.getHeroTrailerAutoplay(context)
        ) {
            runCatchingCancellable {
                // Tokens first: the mint is the part that pays for a COLD WebView,
                // and a resolve that finds the page already loaded (plus a cached
                // player token) is the whole point of the pre-warm - see
                // InnerTubeExtractor.prewarmPoTokens and the 0.9.23 capture whose
                // GVS mint spent 6027ms of a 6s budget on the cold path.
                InnerTubeExtractor.prewarmPoTokens(key)
                TrailerPlayerLauncher.resolvePlayableUrl(
                    key,
                    recordFailure = false
                )
            }
        }
    }

    var heroTrailerReady by remember {
        mutableStateOf(false)
    }

    // Dwell before any network work: focus that survives the dwell is a
    // deliberate stop, and it is also what arms the trailer. How long that is
    // belongs to the viewer — Settings → Hero Trailer Delay.
    LaunchedEffect(
        focusedItem.value?.id,
        focusedItem.value?.type,
        continueWatchingItem.value?.id
    ) {
        heroTrailerReady = false

        val dwellMs = AppPreferences.getHeroTrailerDelayMs(context)
        focusedItem.value?.let {
            // Debounce the RESOLVE only. The hero's text is drawn from the
            // already-known MetaPreview, so it still tracks focus instantly;
            // what waits is the work this effect would otherwise do once per
            // card crossed (see HERO_RESOLVE_DEBOUNCE_MS).
            delay(HERO_RESOLVE_DEBOUNCE_MS)
            onResolveHeroMeta(it)
            // The trailer dwell still measures from focus: the debounce is
            // inside it, so the trailer arms at the same instant it always
            // did rather than one debounce later.
            delay((dwellMs - HERO_RESOLVE_DEBOUNCE_MS).coerceAtLeast(0L))
            heroTrailerReady = true
        }
    }

    // Hero owner: a focused KB folder tile (manifest artwork) or a focused
    // catalog/Continue-Watching item. Folders render even when no catalog item
    // has been focused yet.
    val heroFolder = focusedFolder.value
    val folderBackdrop = heroFolder?.let { f ->
        f.heroBackdropUrl?.takeIf { it.isNotBlank() }
            ?: f.coverImageUrl?.takeIf { it.isNotBlank() }
    }
    val folderLogo = heroFolder?.titleLogoUrl?.takeIf { it.isNotBlank() }
    // KB's ModernHomeModels blanks the hero title for hideTitle folders (the
    // clearlogo stands alone; with no logo the hero shows artwork only).
    val folderPreview = heroFolder?.let { f ->
        MetaPreview(
            id = "kb-folder:${f.id ?: f.title}",
            type = "movie",
            name = if (f.hideTitle) "" else f.title
        )
    }

    // A focused Browse tile owns the hero the same way a folder does, but its
    // artwork is the tile lookup's answer (see KBHomeViewModel) rather than a
    // manifest: it can still be null while that lookup is in flight, and the
    // hero then falls back to the shortcut's own name.
    val heroBrowse = focusedBrowseShortcut.value
    val browseArt =
        heroBrowse?.let { shortcut -> browseShortcutArt[shortcut.chipKey()] }
    val browseBackdrop =
        browseArt?.backdropUrl?.takeIf { it.isNotBlank() }
    val browseLogo =
        browseArt?.clearlogoUrl?.takeIf { it.isNotBlank() }
    val browsePreview = heroBrowse?.let { shortcut ->
        MetaPreview(
            id = "browse:${shortcut.chipKey()}",
            type = "movie",
            name = shortcut.name
        )
    }

    // A rail tile that is not a catalog item owns the hero whenever it is
    // focused; the catalog item's meta/artwork/trailer is then left out.
    val heroOverridden = heroFolder != null || heroBrowse != null

    (browsePreview ?: folderPreview ?: focusedItem.value)?.let {
        // KB-style proportional hero: give the rails a fixed fraction of the
        // real screen height, and the hero whatever remains. Scales to any TV
        // density, unlike the old fixed 300.dp which pushed the first rail's
        // posters off the bottom on shorter panels.
        val configuration = LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp.dp
        val railsFraction = 0.52f
        val heroComputedHeight =
            (screenHeight * (1f - railsFraction))
                .coerceAtMost(HomeHeroHeight)

        HomeHero(
            preview = it,
            heroHeight = heroComputedHeight,
            meta = if (heroOverridden) null else heroMeta.value,
            tmdbDetail = if (heroOverridden) null else heroTmdbDetail.value,
            heroBackdropUrl = when {
                heroFolder != null -> folderBackdrop
                heroBrowse != null -> browseBackdrop
                else -> heroBackdropUrl.value
            },
            heroLogoUrl = when {
                heroFolder != null -> folderLogo
                heroBrowse != null -> browseLogo
                else -> heroLogoUrl.value
            },
            trailerKey = if (heroOverridden) null else heroTrailerKey.value,
            autoPlayTrailer =
                !heroOverridden &&
                    heroTrailerReady &&
                    continueWatchingItem.value == null &&
                    // Settings > Playback: hero trailer autoplay toggle
                    AppPreferences.getHeroTrailerAutoplay(context),
            // Settings > Interface: mute hero trailers toggle
            muted = AppPreferences.getHeroTrailerMuted(context),
            continueWatchingItem = continueWatchingItem.value,
            // Collection manifests supply their own wordmark logo — render it
            // larger than the shared TMDB hero logo. A browse shortcut is NOT
            // in that group even though it also overrides the hero: the only
            // mark it can bring is a service's or studio's TMDB brand logo, so
            // it takes the standard TMDB box and reads at the same size as a
            // catalog title's logo rather than looming over the hero in the
            // collection wordmark's frame.
            heroLogoWidth = when {
                heroFolder != null -> CollectionHeroLogoWidth
                else -> HeroLogoWidth
            },
            heroLogoHeight = when {
                heroFolder != null -> CollectionHeroLogoHeight
                else -> HeroLogoHeight
            },
            // The focused Browse tile's brand-mark candidates, so the hero
            // draws its service/studio mark the same way the tile does (and
            // shows the shortcut's name if none can be drawn). Empty for every
            // other hero owner - and for a genre / tag / decade / collection,
            // which own no mark at all and let the hero say their name.
            heroLogoUrls = browseArt?.clearlogoUrls.orEmpty(),
            // A browse chip's hero is its mark or its name and nothing else, so
            // it sits in the middle of the panel rather than at the bottom.
            centerLogo = heroBrowse != null,
            // One animation per settled item. A folder or a browse chip brings
            // its own art with it (or has none to wait for), so those settle
            // immediately; a catalog title waits for the resolve that publishes
            // its art and words together.
            copySettled = heroOverridden ||
                heroSettledKey.value == "${it.id}:${it.type}"
        )
    }
}

/**
 * Continue Watching, as a rail whose POSITION the home manager owns.
 *
 * A composable of its own rather than an `item` in the rail column, because
 * where it sits is no longer the column's to decide: the merged arrangement is
 * (see KBHomeSlots.buildMergedEntries), which is what lets it be moved below a
 * catalog or hidden outright. The content is unchanged - the same cards, the
 * same hero hand-off, the same up-to-the-topbar hook - so nothing about how the
 * rail behaves on screen moved with it.
 *
 * Its callbacks are the enclosing screen's own local functions passed down:
 * they touch focus, the hero and the menus, which all live up there.
 */
// LocalBringIntoViewSpec is still experimental; the enclosing rail column opts
// in the same way.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BuiltinContinueWatchingRail(
    cards: List<UpNextItem>,
    title: String,
    bringIntoViewSpec: BringIntoViewSpec,
    onTitleHeight: (Int) -> Unit,
    onCardFocusRequester: (FocusRequester) -> Unit,
    /**
     * Every card's own [FocusRequester], keyed by the key its focus reports and
     * dropped again when the card leaves, filled as the cards COMPOSE (see the
     * registration in the items block below).
     *
     * Home's return-from-Detail restore looks the remembered card up in here
     * (the screen's `railFocusRequesters`), and it can only do that if the cards
     * registered themselves: the composition that owns a requester is the one
     * being built on the way back, so a map filled only when a card takes focus
     * is empty for the whole restore window.
     */
    cardFocusRequesters: MutableMap<String, FocusRequester>,
    /**
     * The card D-pad focus has just landed on: its key and its own requester,
     * reported on every focus change.
     *
     * The requester is what keeps Home's [focusedCardRequester] current, so a
     * hold-Up from anywhere in this rail comes back to where the viewer was; the
     * key rides along because it is what Home remembers for the return from
     * Detail (see [HomeViewModel.lastFocusedRailItemKey]), and only the card
     * knows its own name.
     */
    onFocusedCard: (key: String, requester: FocusRequester) -> Unit,
    onOpenUpNext: (UpNextItem) -> Unit,
    onLongPress: (UpNextItem) -> Unit,
    onSelectHero: (MetaPreview, UpNextItem) -> Unit,
    /**
     * Bumped by the screen when an action removed the card focus was on, so
     * this rail lands on its new head whether or not the viewer had walked it
     * by hand (see the landing below).
     */
    landOnHeadSignal: Int = 0,
    /**
     * Whether this composition of Home is a FRESH entry: cold start, or any
     * entry with no Detail-return focus-restore target (see
     * [com.kennyb1201.kbstream.ui.home.HomeViewModel.lastFocusedRailItemKey],
     * which is non-null exactly when there is one). Only then may the landing
     * below complete itself on this rail's head (see
     * [continueWatchingLandsOnFreshEntry]); a return from Detail has a restore
     * target and the ViewModel's own restore owns focus there.
     */
    isFreshEntry: Boolean = false,
    /**
     * Whether the viewer has moved focus themselves since this entry - the
     * fresh-entry landing's licence (see [continueWatchingLandsOnFreshEntry]).
     *
     * False means no D-pad press has happened yet, so whatever holds focus is
     * the framework's default rather than a choice, whichever rail it landed on,
     * and completing the landing takes nothing from the viewer. True means they
     * have started navigating, and this rail leaves focus where they put it.
     */
    viewerMovedFocusSinceEntry: Boolean = false,
    /**
     * Where the rails column sits in the window, recorded from its own layout by
     * Home.
     *
     * A fresh entry is not landed until the head card is VISIBLE (see the
     * verdict below), and "visible" is a question only the column can answer:
     * it is the viewport the rails scroll in, and the card's own bounds say
     * nothing without it. On a cold start the column seeds at the top, the
     * viewport under the hero is short, and the rail this landing picks can sit
     * below it - focus delivered, card unseen, first D-pad press doing the
     * scroll by hand (field, 2026-10-24).
     */
    railsViewportBounds: LandingBounds,
    // Non-null only when this rail is the one Home draws first (see
    // [homeTopRailUpHook]).
    onUpPressed: ((FocusRequester) -> Unit)? = null
) {
    // Where this rail's scroll position lives. The LazyRow used to keep its
    // state to itself; the landing below has to be able to put the rail back on
    // its head, so the rail owns it now (the default state was a remembered
    // one either way, so nothing else about the row moves).
    val railState = rememberLazyListState()

    // Where the head card sits in the window, recorded from the card's own
    // layout and read by the fresh-entry landing's visibility check (see
    // [landingCardOnScreen]). A plain holder rather than state: it moves on
    // every scroll frame, and nothing composes on it.
    val headCardBounds = remember { LandingBounds() }

    // The rail's own handle on the rails column. Asking it to bring this rail
    // into view produces the same scroll the D-pad's traversal would, through
    // the same [LocalBringIntoViewSpec] Home provides - which is the point: the
    // landing has to rest WHERE a press would have taken the viewer, not at a
    // second geometry invented here (see the landing below).
    val railAnchor = remember { BringIntoViewRequester() }

    // The card at the head of the rail is focusable through the RAIL's own
    // requester, so the landing below can reach exactly the card in position 0:
    // a card's requester is created inside the `items` block, where the rail
    // cannot see it. Every other card keeps one of its own.
    val headRequester = remember { FocusRequester() }

    // Whether focus is anywhere inside this rail. `hasFocus` and not
    // `isFocused`: the LazyRow itself is not focusable - its cards are - and a
    // parent's `hasFocus` is "this node or any child of it".
    var railHasFocus by remember { mutableStateOf(false) }

    // Set once and never cleared: the rail is the viewer's from the moment they
    // walk it (see [continueWatchingWalkedByFocus]).
    var viewerWalkedRail by remember { mutableStateOf(false) }

    // Identity of the card at the head - what the landing is keyed on. Keyed on
    // the head rather than on the card LIST so the landing runs on the update
    // that actually changes what is first: re-emitting the same list is not a
    // reason to move focus, and an intermediate load (the local snapshot) whose
    // head the enriched merge then replaces is corrected by the merge's own
    // pass, whose head is the final one.
    val headId = cards.firstOrNull()?.id

    // The value of [landOnHeadSignal] the landing last answered. A value that
    // has moved means an ACTION removed the card the viewer was on - their
    // place is gone, so the head is owed the focus even though they had walked
    // the rail themselves.
    var answeredLandingSignal by remember { mutableStateOf(landOnHeadSignal) }

    // One shot for the life of this composition. The fresh-entry landing below
    // is Home completing the landing it owed the viewer on entry, not a thing
    // to redo on every load that follows. Deliberately NOT reset by
    // [landOnHeadSignal]: a card deleted mid-session is that signal's own
    // landing, and it has to be able to run even after this one has fired.
    var initialLandingDone by remember { mutableStateOf(false) }

    // Continue Watching lands on its head (the reported bug: the rail finished
    // loading with focus on the card that WAS first, and the new first card off
    // to the left). Also keyed on [railHasFocus] so a load that arrives while
    // the viewer is elsewhere is corrected the moment they enter the rail,
    // instead of leaving them on a rail anchored away from its own first card.
    //
    // The fresh-entry branch reads the viewer's intent latch from the body
    // rather than the key list: the effect already re-runs on the update that
    // matters - the head appearing (headId) - and a key would make a D-pad press
    // run a landing that then has to decide it is not one. Where focus rests is
    // not consulted at all (see [continueWatchingLandsOnFreshEntry]).
    //
    // Whether the decline has been said on HOME_FOCUS yet. The effect re-runs on
    // focus and on later loads; one line is the report.
    var loggedFreshEntryDecline by remember { mutableStateOf(false) }

    LaunchedEffect(headId, railHasFocus, landOnHeadSignal) {
        // No cards left at all: there is nothing in this row to land on, and
        // asking a detached requester only burns the budget. The framework's
        // own relocation picks up the row below, which is the same place the
        // viewer would press Down to reach.
        if (headId == null) return@LaunchedEffect
        // Set when this pass is the fresh-entry landing, so the log at the end
        // can name the branch that ran and the head it chose.
        var freshEntryLanding = false
        if (landOnHeadSignal != answeredLandingSignal) {
            answeredLandingSignal = landOnHeadSignal
            // The walk latch is cleared with it: the card the viewer had chosen
            // has been deleted, so this landing is their new place rather than
            // a load moving focus out from under them.
            viewerWalkedRail = false
        } else if (
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = isFreshEntry,
                initialLandingDone = initialLandingDone,
                viewerMovedFocusSinceEntry = viewerMovedFocusSinceEntry
            )
        ) {
            // Fresh entry, data has arrived, and the viewer has pressed nothing:
            // whatever holds focus is the framework's default, not a choice, so
            // moving is finishing the landing rather than stealing focus from
            // the viewer. One shot - every later load takes the gate below.
            initialLandingDone = true
            freshEntryLanding = true
        } else {
            if (
                isFreshEntry &&
                !initialLandingDone &&
                viewerMovedFocusSinceEntry &&
                !loggedFreshEntryDecline
            ) {
                loggedFreshEntryDecline = true
                Log.i(
                    "HOME_FOCUS",
                    "Fresh-entry landing declined: viewer moved focus first; " +
                        "Continue Watching head " + headId + " left where it is"
                )
            }
            if (!continueWatchingLandsOnHead(railHasFocus, viewerWalkedRail)) {
                return@LaunchedEffect
            }
        }
        // Put the head card on screen first: until it is laid out there is no
        // focus node to ask for. This is also the scroll the viewer was making
        // by hand - after the head changed, the list kept the CARD that was
        // first at the leading edge, which is where "press Left to see the new
        // first item" came from.
        railState.scrollToItem(CONTINUE_WATCHING_HEAD_INDEX)
        // The focus ask needs the card attached, and one frame is not always
        // enough on a busy first composition - the retry the guide's focus
        // grabs use. requestFocus() reports a miss by its RETURN value (it
        // throws only for a requester with nothing attached), so success is
        // read from it rather than from runCatching alone.
        // Two consecutive acceptances, not one (see [FocusLandingVerdict]):
        // an answer that accepted and was disposed a frame later - the rail
        // reloading under the landing - used to be logged as a delivery here.

        // Whether the ask has ever been ACCEPTED. Kept apart from [landed]
        // because the fresh-entry landing now has two ways to fail - focus
        // never delivered, and focus delivered onto a card that is not on
        // screen - and the HOME_FOCUS line at the end has to say which one it
        // was (see the report below).
        var focusDelivered = false
        var landed = false
        var attempts = 0
        val verdict = FocusLandingVerdict()
        while (!landed && attempts < CONTINUE_WATCHING_LANDING_ATTEMPTS) {
            awaitFrame()
            if (freshEntryLanding) {
                // The rails column's own scroll, asked the way a D-pad step
                // asks for it - through the BringIntoViewSpec Home provides - so
                // the rail comes to rest exactly where traversal would have put
                // it. A fresh entry has no place yet, so bringing the rail the
                // landing just chose into view takes nothing from the viewer;
                // this is the scroll their first press used to perform.
                //
                // Asked per frame rather than once: the node may not be laid
                // out on the first try, and a bring-into-view is a no-op then
                // rather than a failure (the same reason the settings search's
                // jump asks per frame).
                runCatching { railAnchor.bringIntoView() }
            }
            landed = verdict.accepted(
                runCatching { headRequester.requestFocus() }.getOrDefault(false)
            )
            if (landed) focusDelivered = true
            if (
                landed &&
                freshEntryLanding &&
                !landingCardOnScreen(
                    cardTopPx = headCardBounds.topPx,
                    cardBottomPx = headCardBounds.bottomPx,
                    viewportTopPx = railsViewportBounds.topPx,
                    viewportBottomPx = railsViewportBounds.bottomPx
                )
            ) {
                // Focus answered, but the card it answered for is not where the
                // viewer can see it. Visibility is part of the landing now, not
                // a detail after it: an accepted focus on a card below the fold
                // is the field case, and calling that a landing is what kept it
                // invisible for three reports. Retry - the column scroll above
                // is still settling.
                landed = false
            }
            attempts++
        }
        if (freshEntryLanding) {
            // The field capture's question - which branch ran, and on what - in
            // one line. Info rather than debug, because Fire TV suppresses
            // debug output.
            val decided =
                "Fresh-entry landing on Continue Watching head " + headId
            when {
                landed ->
                    Log.i("HOME_FOCUS", decided + " after " + attempts + " frames")
                // The distinction the next field report needs: focus was
                // delivered, the SCROLL is what did not finish. Saying "gave up"
                // here would read as the focus failure this is not.
                focusDelivered -> Log.w(
                    "HOME_FOCUS",
                    decided + " landed off-screen after " + attempts +
                        " frames: the head card is outside the rails viewport"
                )
                else ->
                    Log.w("HOME_FOCUS", decided + " gave up after " + attempts + " frames")
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(
                start = TvSafeAreaHorizontal,
                top = 0.dp,
                bottom = 0.dp
            )
            // The rail's own little hop on the column: this is the node a
            // fresh-entry landing asks to be brought into view (see the landing
            // above), which is what makes the whole section - title and cards -
            // the thing the column lands, rather than the head card alone.
            .bringIntoViewRequester(railAnchor)
    ) {
        SectionTitle(title, onHeight = onTitleHeight)

        CompositionLocalProvider(
            LocalBringIntoViewSpec provides bringIntoViewSpec
        ) {
            LazyRow(
                state = railState,
                contentPadding = PaddingValues(
                    start = RailHorizontalStartPadding,
                    end = TvSafeAreaHorizontal,
                    top = 10.dp,
                    bottom = 12.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                // What the landing above is gated on: focus anywhere inside
                // this row (see [continueWatchingLandsOnHead]).
                modifier = Modifier.onFocusChanged {
                    railHasFocus = it.hasFocus
                }
            ) {
                // Indexed, so a card knows its own position: the head card is
                // the one the rail can focus, and a focus landing anywhere else
                // is the viewer walking the rail.
                itemsIndexed(
                    items = cards,
                    key = { _, item -> item.id },
                    // One reuse pool for this row's own card shape. The rails'
                    // cells are different composables, so sharing a pool across
                    // them would swap contents on every scroll that crossed a
                    // boundary.
                    contentType = { _, _ -> "continue-watching" }
                ) { index, item ->
                    val ownRequester = remember { FocusRequester() }
                    val requester = if (index == CONTINUE_WATCHING_HEAD_INDEX) {
                        headRequester
                    } else {
                        ownRequester
                    }

                    // This card's key, in this rail's own family: what Home
                    // remembers when focus lands here and what its return-from-
                    // Detail restore looks up. Registered as the card composes,
                    // and dropped as it leaves, so a disposed node is never
                    // asked for focus (see [cardFocusRequesters]).
                    val cardKey = CONTINUE_WATCHING_CARD_KEY_PREFIX + item.id
                    DisposableEffect(requester, cardKey) {
                        cardFocusRequesters[cardKey] = requester
                        onDispose { cardFocusRequesters.remove(cardKey) }
                    }

                    // Carry the row's own backdrop + clearLogo into the hero
                    // preview: while TMDB resolution is pending the hero renders
                    // THESE instead of falling back to the poster (which showed
                    // as an ugly zoomed backdrop with a plain-text title).
                    val hero = MetaPreview(
                        id = item.parentId ?: item.id,
                        type = item.parentType ?: "movie",
                        name = item.title,
                        poster = item.poster,
                        background = item.backdrop,
                        logo = item.clearLogo
                    )

                    CompactUpNextCard(
                        item = item,
                        // A card that leaves this rail (marked watched, or
                        // removed) fades where it was and the rest close the
                        // gap instead of the rail teleporting under the D-pad.
                        modifier = Modifier
                            .animateItem()
                            // Left from the rail's first card is nowhere, not a
                            // step down to the Upcoming header below it (see
                            // [homeRailHeadLeftEdgeStop]).
                            .homeRailHeadLeftEdgeStop(
                                index == CONTINUE_WATCHING_HEAD_INDEX
                            )
                            .then(
                                if (index == CONTINUE_WATCHING_HEAD_INDEX) {
                                    // Only the head is measured: it is the one the
                                    // landing promises is visible (see the verdict
                                    // above), and a write per card would be work
                                    // for nothing.
                                    Modifier.onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInWindow()
                                        headCardBounds.record(
                                            topPx = bounds.top,
                                            bottomPx = bounds.bottom
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            ),
                        onClick = { onOpenUpNext(item) },
                        onLongClick = {
                            // Remember this card's requester so dismissing the
                            // menu restores focus to the card that opened it.
                            onCardFocusRequester(requester)
                            onLongPress(item)
                        },
                        onFocus = {
                            // Where the viewer actually is: hold-Up from this
                            // card, and the bar's dismiss, come back HERE (see
                            // focusedCardRequester).
                            onFocusedCard(cardKey, requester)
                            if (continueWatchingWalkedByFocus(index)) {
                                // The viewer's own place in the rail, not the
                                // landing's (which asks for index 0): from here
                                // on no load moves focus again.
                                viewerWalkedRail = true
                            }
                            onSelectHero(hero, item)
                        },
                        onUpPressed = onUpPressed,
                        focusRequester = requester,
                        badgeColor = when {
                            item.isSeriesFinale -> KBDanger
                            item.isSeasonFinale -> KBRust
                            else -> when (item.badge) {
                                UpNextBadge.CONTINUE_WATCHING -> KBAccent
                                UpNextBadge.NEXT_UP -> KBSteel
                                UpNextBadge.NEW_EPISODE -> KBSuccess
                                UpNextBadge.NEW_SEASON -> KBPlum
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * The Upcoming Schedule rail, arranged the same way and for the same reason as
 * [BuiltinContinueWatchingRail]. A card here opens the title's details rather
 * than playing anything - an episode that has not aired has nothing to play.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BuiltinUpcomingRail(
    episodes: List<UpcomingEpisode>,
    title: String,
    bringIntoViewSpec: BringIntoViewSpec,
    onTitleHeight: (Int) -> Unit,
    onOpenDetails: (UpNextItem) -> Unit,
    onSelectHero: (MetaPreview, UpNextItem) -> Unit,
    /**
     * The card D-pad focus has just landed on, with its key - the same contract
     * [BuiltinContinueWatchingRail] has, and for the same reason (see
     * [BuiltinContinueWatchingRail.onFocusedCard]).
     */
    onFocusedCard: (key: String, requester: FocusRequester) -> Unit,
    /**
     * Every card's own [FocusRequester] under the key its focus reports, filled
     * as the cards compose - see
     * [BuiltinContinueWatchingRail.cardFocusRequesters].
     */
    cardFocusRequesters: MutableMap<String, FocusRequester>,
    /**
     * A long press on a card, with that card's own [FocusRequester]: Home
     * raises the same "Go to Details" + "Hide" menu every other poster card
     * offers, and the requester is what lets a dismiss hand focus back to the
     * card that opened it.
     */
    onLongPress: (UpcomingEpisode, FocusRequester) -> Unit,
    /**
     * Bumped by the screen when an action removed the card focus was on, so
     * this rail lands on its new head whether or not the viewer had walked it
     * by hand - the same landing [BuiltinContinueWatchingRail] makes.
     */
    landOnHeadSignal: Int = 0,
    // Non-null only when this rail is the one Home draws first (see
    // [homeTopRailUpHook]).
    onUpPressed: ((FocusRequester) -> Unit)? = null,
    /**
     * The header's press: open Library's Calendar pane (see [SectionTitle]).
     * Every show's whole year lives there; this rail is one episode per show.
     */
    onOpenCalendar: (() -> Unit)? = null
) {
    // Same landing machinery as BuiltinContinueWatchingRail, for the same
    // reason: a Hide on a card deletes the card (and can empty the whole row),
    // so the hand-back has to come from the rail's own new head rather than
    // from the deleted card's requester, which has nothing to attach to.
    val railState = rememberLazyListState()
    val headRequester = remember { FocusRequester() }
    var railHasFocus by remember { mutableStateOf(false) }
    var viewerWalkedRail by remember { mutableStateOf(false) }
    val headId = episodes.firstOrNull()?.id
    var answeredLandingSignal by remember { mutableStateOf(landOnHeadSignal) }

    LaunchedEffect(headId, railHasFocus, landOnHeadSignal) {
        if (headId == null) return@LaunchedEffect
        if (landOnHeadSignal != answeredLandingSignal) {
            answeredLandingSignal = landOnHeadSignal
            viewerWalkedRail = false
        } else if (!continueWatchingLandsOnHead(railHasFocus, viewerWalkedRail)) {
            return@LaunchedEffect
        }
        railState.scrollToItem(CONTINUE_WATCHING_HEAD_INDEX)
        // Verified, like the Continue Watching head above: one acceptance is
        // not a landing (see [FocusLandingVerdict]).
        var landed = false
        var attempts = 0
        val verdict = FocusLandingVerdict()
        while (!landed && attempts < CONTINUE_WATCHING_LANDING_ATTEMPTS) {
            awaitFrame()
            landed = verdict.accepted(
                runCatching { headRequester.requestFocus() }.getOrDefault(false)
            )
            attempts++
        }
    }

    Column(
        modifier = Modifier.padding(
            start = TvSafeAreaHorizontal,
            top = 0.dp,
            bottom = 0.dp
        )
    ) {
        SectionTitle(title, onHeight = onTitleHeight, onClick = onOpenCalendar)

        CompositionLocalProvider(
            LocalBringIntoViewSpec provides bringIntoViewSpec
        ) {
            LazyRow(
                state = railState,
                contentPadding = PaddingValues(
                    start = RailHorizontalStartPadding,
                    end = TvSafeAreaHorizontal,
                    top = 10.dp,
                    bottom = 12.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                // What the landing above is gated on: focus anywhere in this
                // row (see continueWatchingLandsOnHead).
                modifier = Modifier.onFocusChanged {
                    railHasFocus = it.hasFocus
                }
            ) {
                itemsIndexed(
                    items = episodes,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> "upcoming" }
                ) { index, upcoming ->
                    val ownRequester = remember { FocusRequester() }
                    val requester = if (index == CONTINUE_WATCHING_HEAD_INDEX) {
                        headRequester
                    } else {
                        ownRequester
                    }

                    // This card's key and its requester together, registered as
                    // the card composes (see
                    // [BuiltinContinueWatchingRail.cardFocusRequesters]).
                    val cardKey = UPCOMING_CARD_KEY_PREFIX + upcoming.id
                    DisposableEffect(requester, cardKey) {
                        cardFocusRequesters[cardKey] = requester
                        onDispose { cardFocusRequesters.remove(cardKey) }
                    }
                    val heroItem = UpNextItem(
                        id = upcoming.id,
                        title = upcoming.title,
                        poster = upcoming.poster,
                        badge = UpNextBadge.NEXT_UP,
                        backdrop = upcoming.backdrop,
                        parentId = upcoming.parentId,
                        parentType = upcoming.parentType,
                        // Hero renders "Airs <label>" + the calendar date.
                        airDateLabel = upcoming.airDateLabel,
                        airDateFull = upcoming.airDateFull,
                        season = upcoming.season,
                        episode = upcoming.episode,
                        episodeTitle = upcoming.episodeTitle
                    )

                    Box(
                        modifier = Modifier
                            // The schedule reorders as episodes air and as the
                            // watched flags land; a card that moves now takes
                            // its neighbours with it instead of the row
                            // jumping.
                            .animateItem()
                            .width(224.dp)
                            .height(146.dp + PosterFocusHeadroom)
                            .padding(end = HomeRailGap),
                        contentAlignment = Alignment.Center
                    ) {
                        UpcomingEpisodeCard(
                            upcoming = upcoming,
                            onClick = { onOpenDetails(heroItem) },
                            onLongClick = { onLongPress(upcoming, requester) },
                            focusRequester = requester,
                            // The head card's left edge is the rail's edge (see
                            // [homeRailHeadLeftEdgeStop]). It is also the rail
                            // whose focusable header sits directly below the
                            // Continue Watching rail's first card, which is the
                            // reported Left->Down jump this closes.
                            isHeadCard = index == CONTINUE_WATCHING_HEAD_INDEX,
                            onUpPressed = onUpPressed,
                            onFocus = {
                                // Where the viewer actually is (see
                                // focusedCardRequester).
                                onFocusedCard(cardKey, requester)
                                onSelectHero(
                                    MetaPreview(
                                        id = upcoming.parentId,
                                        type = upcoming.parentType,
                                        name = upcoming.title,
                                        poster = upcoming.poster,
                                        background = upcoming.backdrop
                                    ),
                                    heroItem
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    onHeight: ((Int) -> Unit)? = null,
    /**
     * When set, the title is a control rather than a caption.
     *
     * Only the Upcoming rail passes it: that rail is one next episode per show,
     * while the whole year of every tracked show lives in Library's Calendar
     * pane, and the heading is where a viewer who wants more than the next card
     * would press. Every other rail's title stays a plain heading, so nothing
     * else on Home gains a focus stop.
     */
    onClick: (() -> Unit)? = null
) {
    // Home's rail titles are the app's section heading (KBSectionHeader), not a
    // style of their own: they were the one place that drew titleMedium at 94%
    // white with its own padding, so a rail here and the same rail on Detail
    // named themselves differently. The measurement is Home's own need - the
    // band reserved under a focused rail for the NEXT rail's title is built
    // from the heading's real line box, so it stays right at any type scale
    // (see [RailLanding]) - and it wraps the whole thing, padding included.
    val measured = Modifier.then(
        if (onHeight == null) Modifier
        else Modifier.onSizeChanged { onHeight(it.height) }
    )

    if (onClick == null) {
        KBSectionHeader(
            title = text,
            modifier = measured
        )
    } else {
        // A Surface, not a hand-rolled clickable: it carries focus, activation
        // and the pressed state as one target, so a D-pad OK and a tap both
        // open the calendar and the title takes focus when the rail is walked
        // up to.
        //
        // EVERY state's colours are named. Only the resting pair used to be,
        // so the focused fill fell through to tv-material3's default - a
        // near-white wash under the same near-white heading, which read as an
        // unnamed control in the middle of the app's palette, and left the
        // word barely legible in it. Focus now raises the theme's own surface
        // and keeps the heading's own colour, exactly as the Library's filter
        // chips do; the border is the accent, so the heading still reads as
        // the one rail title in Home that is also a door.
        Surface(
            onClick = onClick,
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = KBTextHi,
                focusedContainerColor = KBSurfaceRaised,
                focusedContentColor = KBTextHi,
                pressedContainerColor = KBSurfaceRaised,
                pressedContentColor = KBTextHi
            ),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                border = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.Transparent
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, KBAccent),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                )
            ),
            modifier = measured
        ) {
            KBSectionHeader(
                title = text
            )
        }
    }
}

@Composable
private fun CompactUpNextCard(
    item: UpNextItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    // Non-null only for the card whose rail is the one Home draws first (see
    // [homeTopRailUpHook]).
    onUpPressed: ((FocusRequester) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    badgeColor: Color
) {
    var focused by remember {
        mutableStateOf(false)
    }

    // The rule lives in HomeUpNext.kt so a test can reach it; it refuses an
    // episode number the sources never named, which is what a card reading
    // "S02 · E00" over blank artwork was: a tracker's or an add-on's 0.
    val episodeLabel = upNextEpisodeLabel(
        item.season,
        item.episode
    )

    val displayBadge =
        when {
            item.isSeriesFinale ->
                "SERIES FINALE"

            item.isSeasonFinale ->
                "SEASON FINALE"

            else ->
                when (item.badge) {
                    UpNextBadge.CONTINUE_WATCHING ->
                        "RESUME"

                    UpNextBadge.NEXT_UP ->
                        "NEXT UP"

                    UpNextBadge.NEW_EPISODE ->
                        "NEW EPISODE"

                    UpNextBadge.NEW_SEASON ->
                        "NEW SEASON"
                }
        }

    val progress = item.progressPercent
        ?.coerceIn(0f, 1f)

    // Resume cards keep their progress bar and time-left. A finale being
    // resumed still has a saved position, so treat it like a resume card;
    // plain up-next finales have no position to show.
    val isResumeCard =
        item.badge == UpNextBadge.CONTINUE_WATCHING ||
            (item.startPositionMs > 0L &&
                (item.isSeasonFinale ||
                    item.isSeriesFinale))

    val timeLeft =
    if (isResumeCard) {
        formatTimeLeft(item.remainingMinutes)
    } else {
        null
    }

    // Spoiler-free, read once for the card: the same rule its title line uses
    // ([rememberHidesEpisodeTitle]). A card offering an episode the viewer has
    // not started must not show a frame FROM that episode either - the still was
    // the loudest thing on the tile, and the detail screen's episode list covers
    // the same frame. Covering it here means falling back to the show's own art
    // rather than a blur: a blur is a no-op below API 31, which is where these
    // boxes live (Fire OS 7), and the rail is a list of what to watch next, not
    // a browse surface that needs a "hidden" marker.
    val hidesEpisodeIdentity = rememberHidesEpisodeTitle(
        inProgress = item.badge == UpNextBadge.CONTINUE_WATCHING
    )

    PosterCard(
        posterUrl = if (hidesEpisodeIdentity) {
            item.backdrop ?: item.poster ?: ""
        } else {
            item.episodeThumbnail ?: item.backdrop ?: item.poster ?: ""
        },
        contentDescription = item.title,
        isWatched = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(224.dp)
            .height(146.dp)
            .padding(end = HomeRailGap)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                focused = it.isFocused

                if (it.isFocused) {
                    onFocus()
                }
            }
            .then(
                // D-pad Up on the card of the rail Home draws first opens the
                // top bar; every other card leaves the key to the focus system,
                // which moves focus to the rail above.
                if (focusRequester == null) {
                    Modifier
                } else {
                    Modifier.homeTopRailUpHook(focusRequester, onUpPressed)
                }
            )
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                // One scrim vocabulary on KBVoid, like Detail's:
                                // a black ramp under a card ended on a KBVoid
                                // stop, so the AMOLED toggle changed the bottom
                                // of the fade but not the top of it.
                                KBVoid.copy(alpha = 0.08f),
                                KBVoid.copy(alpha = 0.34f),
                                KBVoid.copy(alpha = 0.80f),
                                KBVoid.copy(alpha = 0.97f)
                            )
                        )
                    )
            )

            Text(
                text = displayBadge,
                color = KBTextHi,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        color = badgeColor,
                        shape = KBShapeChip
                    )
                    .padding(
                        horizontal = 6.dp,
                        vertical = 3.dp
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 10.dp,
                        end = 10.dp,
                        bottom = if (
                            isResumeCard &&
                            progress != null &&
                            progress > 0f
                        ) {
                            15.dp
                        } else {
                            9.dp
                        }
                    )
            ) {
                Text(
                    text = item.title,
                    color = KBTextHi,
                    // The on-card title slot (titleSmall), matching the
                    // Upcoming card above it and every other card title: this
                    // was the sibling that disagreed with its own rail.
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.kbFocusMarquee(focused)
                )

                episodeLabel?.let { label ->
                    Text(
                        text = label,
                        color = if (focused) {
                            KBTextHi.copy(alpha = 0.78f)
                        } else {
                            KBTextLo
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (!hidesEpisodeIdentity) {
                    item.episodeTitle
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let { episodeTitle ->
                            Text(
                                text = episodeTitle,
                                color = if (focused) {
                                    KBTextHi.copy(alpha = 0.88f)
                                } else {
                                    KBTextLo
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                }
            }

            item.episodesRemaining
    ?.takeIf { it > 0 }
    ?.let { remaining ->
        Text(text = if (remaining == 1) {
    "1 ep. left"
} else {
    "$remaining ep. left"
},
            color = KBTextHi,
            // The two corner chips on this card ("3 ep. left" and "42m
            // left") are the same chip: one label style, one fill, one
            // padding. They had drifted to 11sp on a 65% plate and 10sp on a
            // 55% one, so the same kind of fact read as two kinds.
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 10.dp,
                    bottom = if (
                        isResumeCard &&
                        progress != null &&
                        progress > 0f
                    ) {
                        15.dp
                    } else {
                        9.dp
                    }
                )
                .background(
                    color = KBVoid.copy(alpha = 0.65f),
                    shape = KBShapeChip
                )
                .padding(
                    horizontal = 6.dp,
                    vertical = 3.dp
                )
        )
    }
        
            timeLeft?.let { label ->
                Text(
                    text = label,
                    color = KBTextHi.copy(alpha = 0.90f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(
                            color = KBVoid.copy(alpha = 0.65f),
                            shape = KBShapeChip
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 3.dp
                        )
                )
            }

            if (
                isResumeCard &&
                progress != null &&
                progress > 0f
            ) {
                // The shared bar: this one had its own track tone
                // (KBTextHi at 28%) and square ends where the detail screen's
                // was KBTextLo at 45% under a 2dp radius.
                KBProgressBar(
                    progress = progress,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onItemClick: (MetaPreview) -> Unit,
    onOpenDetailTarget: (
        MetaPreview,
        StreamsTarget,
        String?
    ) -> Unit,
    onOpenStreams: (
        MetaPreview,
        StreamsTarget,
        String?
    ) -> Unit = { _, _, _ -> },
    onSearch: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    // The Upcoming rail's header: Library, on the Calendar pane. Separate from
    // [onOpenLibrary] because it also selects that pane and hands the pane the
    // D-pad (see [BuiltinUpcomingRail.onOpenCalendar]).
    //
    // Nullable so a host that cannot navigate leaves the heading a plain
    // caption instead of drawing a control that does nothing.
    onOpenCalendar: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onOpenKBFolder: (String) -> Unit = {},
    onOpenBrowseShortcut: (com.kennyb1201.kbstream.data.kb.BrowseHomeShortcut) -> Unit = {},
    onOpenCatalogGrid: (Rail) -> Unit = {},
    // Opens the home rail manager (the Add-ons screen's dialog). Home cannot
    // raise that dialog itself - it is wired to the Add-ons view model - so the
    // press navigates there with a one-shot request behind it.
    onManageRails: () -> Unit = {},
    viewModel: HomeViewModel =
        viewModel()
) {
    // The phone entry: same callbacks, same ViewModel, a touch-first layout.
    // This is the one branch the port adds to this file - everything below is
    // the TV screen, unchanged (see HomeScreenMobile for what a finger gets).
    if (!FormFactor.isTv) {
        HomeScreenMobile(
            onItemClick = onItemClick,
            onOpenDetailTarget = onOpenDetailTarget,
            // The Continue Watching menu's "Play Manually" is the one phone
            // route that needs the shell's streams screen: the card's tap and
            // its other rows stay on the callbacks above.
            onOpenStreams = onOpenStreams,
            // Search / Live TV / Library / Settings are NOT handed to the
            // phone screen any more: they are the shell's bottom bar items, and
            // the shell calls the very same callbacks this screen holds. TV
            // still uses all four below, which is why they stay parameters.
            //
            // Profile switching left the phone screen too: its door is the
            // bottom bar's avatar now (hosted by the shell, so it also exists
            // on the four screens Home does not own). The TV header keeps the
            // chip this callback draws there.
            onOpenCatalogGrid = onOpenCatalogGrid,
            onOpenKBFolder = onOpenKBFolder,
            onOpenBrowseShortcut = onOpenBrowseShortcut,
            onManageRails = onManageRails,
            // The Upcoming header is the same door on both form factors: the
            // phone has no room for the TV's rail-a-year argument, but the
            // whole schedule still lives in Library's Calendar pane.
            onOpenCalendar = onOpenCalendar,
            viewModel = viewModel
        )
        return
    }

    val context = LocalContext.current

    // The artwork the viewer chose for the titles the rails show (see
    // ImageOverrides). Read once for the whole screen and handed to each card,
    // so a choice made on a title's Media row reaches its rail card without the
    // rail being rebuilt - and without a query per card.
    val overridePaths = rememberImageOverridePaths()

    // KB collections (imported from a profile URL) interleaved with the
    // addon rails below; arrangement (pin/reorder/hide) from the manager.
    val kbViewModel: com.kennyb1201.kbstream.ui.kb.KBHomeViewModel =
        viewModel()

    // Keyed on the active profile: these are profile-scoped prefs and
    // remember{} would otherwise keep the previous profile's display
    // settings for the whole session after a switch.
    val activeProfileId = com.kennyb1201.kbstream.data.sync.ProfileManager
        .activeProfile.collectAsStateWithLifecycle().value?.id
    // ...and on the sync revision too, because every one of these is a SYNCED
    // pref: a value that arrives from the account (a sibling TV changed the
    // rail labels or the landscape switch) has to re-read here instead of
    // waiting for the next entry into Home.
    val syncRevision by com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
        .revision.collectAsStateWithLifecycle()
    val showRailType by remember(activeProfileId, syncRevision) {
        mutableStateOf(AppPreferences.getHomeRailShowCatalogType(context))
    }
    val showRailAddon by remember(activeProfileId, syncRevision) {
        mutableStateOf(AppPreferences.getHomeRailShowAddonName(context))
    }
    val landscapeCards by remember(activeProfileId, syncRevision) {
        mutableStateOf(AppPreferences.homeLandscapeActive(context))
    }
    // Profile-gated: rows render only while they belong to the ACTIVE
    // profile. The ViewModel clears them on a switch, but that clear is
    // dispatched, so without this gate the profile the user just left could
    // paint for a frame before the empty list landed.
    val railsBuiltForProfile by viewModel.railsProfileId.collectAsStateWithLifecycle()
    val railsRaw by viewModel.rails.collectAsStateWithLifecycle()
    // Hidden titles are dropped as Home reads its own data, so a long-press
    // Hide on a rail takes the title off the whole screen at once - the
    // rails, Continue Watching, Up Next and the hero - and a rail the user
    // emptied out is dropped with it instead of leaving a gap. The key set
    // re-reads whenever the store changes (see rememberHiddenTitleKeys).
    val hiddenTitleKeys = rememberHiddenTitleKeys()
    val rails = remember(
        railsRaw,
        railsBuiltForProfile,
        activeProfileId,
        hiddenTitleKeys
    ) {
        val built =
            if (railsBuiltForProfile == activeProfileId) railsRaw else emptyList()
        built.mapNotNull { rail ->
            val visible = rail.items.filterNot { meta ->
                HiddenTitles.hides(
                    hiddenTitleKeys,
                    meta.type,
                    meta.name,
                    meta.yearOrNull,
                    meta.id
                )
            }
            rail.takeIf { visible.isNotEmpty() }?.copy(items = visible)
        }
    }
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val partialWatchedKeys by viewModel.partialWatchedKeys.collectAsStateWithLifecycle()
    val upNextRaw by viewModel.upNext.collectAsStateWithLifecycle()
    // A hidden show's Continue Watching / Up Next cards come from history and
    // from Simkl rather than from the rails above, so they are filtered here
    // too - including the Upcoming rail's announced episodes.
    val upNext = remember(upNextRaw, hiddenTitleKeys) {
        upNextRaw.filterNot { item ->
            HiddenTitles.hides(
                hiddenTitleKeys,
                item.parentType
                    ?: if (item.season != null) "series" else "movie",
                item.showTitle ?: item.title,
                null,
                item.parentId,
                item.id
            )
        }
    }
    val upcomingSchedule by
        viewModel.upcomingSchedule.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    // Held as State objects rather than values: HomeHeroHost below performs the
    // reads, so a hero art/trailer update recomposes the hero instead of this
    // whole screen (rails LazyColumn and overlays included).
    val heroMetaState = viewModel.heroMeta.collectAsStateWithLifecycle()
    val heroTmdbDetailState = viewModel.heroTmdbDetail.collectAsStateWithLifecycle()
    val heroBackdropUrlState = viewModel.heroBackdropUrl.collectAsStateWithLifecycle()
    val heroLogoUrlState = viewModel.heroLogoUrl.collectAsStateWithLifecycle()
    val heroTrailerKeyState = viewModel.heroTrailerKey.collectAsStateWithLifecycle()
    // Which hero's art AND words are on screen together (see
    // HomeViewModel.heroSettledKey). The copy transition waits for it, so a
    // switch is one settled change rather than words-now-art-later.
    val heroSettledKeyState = viewModel.heroSettledKey.collectAsStateWithLifecycle()

    // KB collections interleaved with addon rails (merged order from the
    // Collections manager: pin / reorder / hide). Computed in composable
    // context — the LazyColumn builder lambda below is LazyListScope, not
    // composable, so it must receive only finished values.
    val kbState by kbViewModel.state.collectAsStateWithLifecycle()
    // Which built-in rails have something to show right now. A built-in entry
    // carries no content of its own - the composables below render it - so the
    // merge is told, exactly as the inline version tested `upNext.isNotEmpty()`
    // and `upcomingSchedule.isNotEmpty()` before drawing each one.
    val builtinWithContent = remember(upNext, upcomingSchedule) {
        buildSet {
            if (upNext.isNotEmpty()) {
                add(
                    com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                        .BUILTIN_CONTINUE_WATCHING
                )
            }
            if (upcomingSchedule.isNotEmpty()) {
                add(
                    com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                        .BUILTIN_UPCOMING_SCHEDULE
                )
            }
        }
    }
    val mergedEntries = remember(rails, kbState, builtinWithContent) {
        com.kennyb1201.kbstream.ui.kb.KBHomeSlots
            .buildMergedEntries(context, rails, kbState, builtinWithContent)
    }

    // A rail's section title honors a rename from the home manager; an
    // unrenamed rail keeps the title it has always drawn.
    val homeRailRenames = kbState.arrangement.renames
    fun builtinRailTitle(key: String, default: String): String =
        homeRailRenames[key]?.takeIf { it.isNotBlank() } ?: default

    // The rail drawn FIRST on Home owns the D-pad Up -> top bar hook: the hero
    // spacer above the rails is inert, so that one hook is the only way the top
    // bar can be reached - and it has to be exactly one rail, or Up from a rail
    // with another rail above it jumps over that rail instead of moving focus to
    // it (see [homeTopRailUpHook]).
    //
    // Ownership follows the MERGED ORDER below, not rail identity. It used to
    // follow identity - Continue Watching took the hook whenever it had cards,
    // and the first catalog rail took it only when Continue Watching was empty -
    // which meant the same thing as "the topmost rail" only while CW was
    // hardcoded above the catalogs. The home manager can now hide it, move it,
    // or put the Upcoming rail or a browse row first, so the rail that draws
    // first is the rail that owns the hook, whatever kind it is.

    var showTopBar by remember {
        mutableStateOf(false)
    }

    // Bumped by EVERY summon of the top bar - a short-press Up on the top rail
    // ([openTopBar]) and the hold-Up shortcut ([openTopBarFromHold]) alike.
    //
    // The hand-off effect below keys on this as well as on [showTopBar], so an
    // Up press while the bar is already showing but did not take focus re-drives
    // the hand-off. Keyed on the Boolean alone, that press set it to the value
    // it already had, the effect never re-ran, and there was no path from the
    // remote into the bar for the rest of that showing.
    var topBarSummons by remember {
        mutableStateOf(0)
    }

    // True while D-pad focus actually rests inside the top bar, reported by the
    // bar itself (see `onBarFocusChanged` on [TopActionBar]). This - not
    // requestFocus()'s return value - is what the hand-off treats as success: a
    // request can answer true for a node that is attached and still lose focus a
    // frame later to the rail that asked for it, which is how the bar ended up
    // shown and unfocused.
    //
    // Written from the bar's focus callback and read inside the hand-off
    // coroutine, never in composition, so a D-pad step inside the bar does not
    // recompose this screen. Cleared when the bar is dismissed (in the hand-off
    // effect's `!showTopBar` branch, because a disposed focus node does not have
    // to report its own exit).
    var topBarHasFocus by remember {
        mutableStateOf(false)
    }

    val topBarFocusRequester = remember {
        FocusRequester()
    }

    var lastPosterFocusRequester by remember {
        mutableStateOf<FocusRequester?>(null)
    }

    // One FocusRequester per mirrored browse chip, keyed by the chip's own
    // identity. Owned HERE rather than made inside the browse rail, because the
    // "Remove from Home" landing has to name a chip the menu is not pointing at
    // - the successor that takes the removed chip's place - and the rail's own
    // requester is not visible from this scope (see browseChipSuccessor).
    val browseChipFocusRequesters = remember {
        mutableMapOf<String, FocusRequester>()
    }

    // The card D-pad focus is ON, kept current by every rail's own focus
    // callback. Its own var, and deliberately not [lastPosterFocusRequester]:
    //
    // That one is written only where a target is CAPTURED - a long press, or a
    // short-press Up on the top rail - so plain traversal left it naming
    // whatever card was last long-pressed, possibly a rail away. Holding Up
    // from a poster five rows down and dismissing the bar therefore put focus
    // back on that older card; and on a session that had not long-pressed
    // anything yet the var was still null, so the dismiss was a silent no-op
    // while the bar's own chip was disposed by AnimatedVisibility - focus fell
    // to nothing and the D-pad was dead until something else grabbed it.
    //
    // [lastPosterFocusRequester] keeps the menu restores it was written for; the
    // hold-Up shortcut and [dismissTopBar] read THIS, and fall back to it.
    var focusedCardRequester by remember {
        mutableStateOf<FocusRequester?>(null)
    }

    // Bumped when a Continue Watching action removed the card the viewer was
    // on. The rail's own landing effect watches it and puts focus on the row's
    // NEW head, with a verified request - the deleted card's requester has
    // nothing to attach to, so asking it (which is all this used to do) was
    // guaranteed to fail and left the D-pad stranded (see
    // BuiltinContinueWatchingRail).
    var continueWatchingLandOnHeadSignal by remember {
        mutableStateOf(0)
    }

    // Seed hero = first focusable card: the first Continue Watching item
    // when that rail exists, else the first catalog poster. Keeps the hero
    // in sync with where D-pad focus lands on entry (the topmost card);
    // previously this only looked at catalog rails, so returning home with
    // focus restored onto Continue Watching showed the first rail's poster
    // in the hero instead.
    val firstHomeItem = remember(upNext, rails) {
        upNext.firstOrNull()?.let { cw ->
            MetaPreview(
                id = cw.parentId ?: cw.id,
                type = cw.parentType ?: "movie",
                name = cw.title,
                poster = cw.poster
            )
        } ?: rails.asSequence()
            .flatMap {
                it.items.asSequence()
            }
            .firstOrNull()
    }

    // False until the user moves focus themselves; gates the auto-seed
    // effect below so it stops overriding the hero after first interaction.
    var userAdjustedFocus by remember {
        mutableStateOf(false)
    }

    // The state object is kept alongside the delegate so HomeHeroHost can read
    // focus directly: the read is then charged to the hero's recompose scope
    // rather than this function's, so a D-pad step no longer recomposes the
    // whole screen (rails LazyColumn included).
    val focusedItemState = remember {
        mutableStateOf<MetaPreview?>(firstHomeItem)
    }
    var focusedItem by focusedItemState

    // KB folder tile currently under focus (null = a normal catalog item
    // owns the hero). The manifest supplies the folder's heroBackdropUrl /
    // titleLogoUrl directly, so the hero swaps to the collection's own
    // artwork without probing addons — no meta/trailer resolution happens
    // for folders.
    val focusedFolderState = remember {
        mutableStateOf<KBFolder?>(null)
    }
    var focusedFolder by focusedFolderState

    val focusedContinueWatchingItemState = remember {
        mutableStateOf<UpNextItem?>(null)
    }
    var focusedContinueWatchingItem by focusedContinueWatchingItemState

    // Browse tile currently under focus (null = the hero belongs to a catalog
    // item, a KB folder or Continue Watching). Its resolved artwork drives the
    // hero, so a Browse row gets the same "focus swaps the hero" treatment the
    // collection folders do.
    val focusedBrowseShortcutState = remember {
        mutableStateOf<BrowseHomeShortcut?>(null)
    }
    var focusedBrowseShortcut by focusedBrowseShortcutState

    // Scroll position of the rails LazyColumn; read/written by selectHero so
    // the Continue Watching row can be cleared out of the viewport on rail
    // focus (see below).
    //
    // Seeded from where the viewer left Home (see [HomeViewModel.railScrollIndex]):
    // this composable is disposed when Detail opens - it is a route in the same
    // activity - so a plain rememberLazyListState came back at the top of the
    // rails on every return, on the most common loop in the app.
    val railListState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.railScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.railScrollOffset
    )

    // ...and the position is put back into the ViewModel as the viewer scrolls,
    // so the seed above is current. A snapshot flow rather than a state read in
    // the body: reading `firstVisibleItemIndex` during composition would charge
    // every scroll tick to this whole function's scope.
    LaunchedEffect(railListState) {
        snapshotFlow {
            railListState.firstVisibleItemIndex to railListState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            viewModel.railScrollIndex = index
            viewModel.railScrollOffset = offset
        }
    }

    // Where the rails column sits in the window, recorded from the column's own
    // layout. The Continue Watching landing's verdict reads it to tell a card
    // that is on screen from one that is merely focused (see
    // [landingCardOnScreen]): the field case was a head card focused below the
    // fold, and nothing in the app could name that difference before.
    //
    // A plain holder, not state: the column's bounds do not change while its
    // contents scroll, and the only reader is the landing coroutine.
    val railsViewportBounds = remember { LandingBounds() }

    // Every rail card's requester, keyed the same way the remembered card is
    // (see HomeViewModel.lastFocusedRailItemKey), so the card the viewer left
    // Home on can be asked for focus when they come back.
    // Registered by the cards themselves (they own their requesters).
    val railFocusRequesters = remember {
        mutableMapOf<String, FocusRequester>()
    }

    // Whether this composition of Home is a fresh ENTRY rather than a return to
    // it - the screen's own return signal, asked of the ViewModel (see
    // [HomeViewModel.recordHomeEntry]).
    //
    // It is NOT "no card remembered". Those were the same thing while only the
    // catalog rails reported a key, and the two readings diverge exactly where
    // it matters: a viewer coming back from a Detail the Continue Watching, the
    // Upcoming or the Browse cards opened has a place to return to and a
    // remembered key, and a viewer whose remembered place is gone has neither.
    // Read from a null key, a return could look like an arrival and move focus
    // off the place the viewer came back to; read the other way round, a fresh
    // entry could be dragged to a card left over from an earlier visit. Each
    // side of the screen owns one case: a return is the restore below, a fresh
    // entry is the Continue Watching landing (see
    // [continueWatchingLandsOnFreshEntry]).
    //
    // Held for the life of the composition, so a recomposition cannot change
    // the answer mid-visit.
    val isFreshEntry = remember { viewModel.recordHomeEntry() }

    // Whether the viewer has taken focus into their own hands since this entry,
    // set by the first deliberate D-pad press. This - not where focus happens to
    // rest - is what the Continue Watching landing is gated on (see
    // [continueWatchingLandsOnFreshEntry]).
    //
    // The distinction is the whole fix. Entry is a race between Continue
    // Watching's data and the framework's default focus placement; when the rail
    // composes empty (cold caches, a profile switch, a slow merge) that default
    // lands on the Upcoming rail's head or a catalog poster, and reading the
    // location as "the viewer chose this" made the landing decline itself for
    // the rest of the session, silently (field, 2026-10-22).
    //
    // A new composition is a new entry - Home is a route, so leaving it disposes
    // this - which is the reset the latch needs. Written from the root preview
    // handler below and read inside the rail's landing coroutine, never composed
    // on, so a D-pad step does not recompose the screen.
    var viewerMovedFocusSinceEntry by remember {
        mutableStateOf(false)
    }

    // The restore window: 20 frames at the shared hand-back cadence, the same
    // ~1s the rails get to compose the card the viewer left Home on.
    val HOME_RETURN_FOCUS_STEPS = 20

    // The other half of the restore: the scroll seed above puts the remembered
    // rail back in the viewport, and this asks that card for focus. The rails
    // compose lazily, so the target may not exist for a frame or two after the
    // restored scroll lands - hence the bounded retry. Giving up quietly is
    // correct: the rails are back where they were, which is already the
    // difference the viewer notices, and a longer wait would fight whatever
    // they do next.
    //
    // Two things the first version of this loop got wrong, and both made the
    // restore a no-op rather than a best effort.
    //
    // The LOOKUP is retried with the request. Cards are registered when they
    // COMPOSE (see the card's own [DisposableEffect]), the rails build lazily,
    // so on a return this map is filled a frame or more AFTER this effect runs -
    // reading it once and giving up looked up nothing at all.
    //
    // And the request's own ANSWER is read: requestFocus() returns FALSE for a
    // node that is not attached yet rather than throwing, so
    // `runCatching { requestFocus() }` reported success for a landing that never
    // happened and returned on the first frame. The app's one rule for a
    // hand-back reads the boolean (see
    // [com.kennyb1201.kbstream.ui.browse.landFocusOn]); a false here means "not
    // composed yet", which is a retry, not a landing.
    LaunchedEffect(Unit) {
        // A fresh entry has nothing to come back to: whatever key an earlier
        // visit left in the ViewModel is not a place this viewer was, and the
        // Continue Watching landing owns focus on an arrival.
        if (isFreshEntry) return@LaunchedEffect
        val wanted = viewModel.lastFocusedRailItemKey ?: return@LaunchedEffect
        repeat(HOME_RETURN_FOCUS_STEPS) {
            val requester = railFocusRequesters[wanted]
            if (requester != null) {
                val landed = runCatching { requester.requestFocus() }
                    .getOrDefault(false)
                if (landed) return@LaunchedEffect
            }
            delay(BROWSE_RETURN_FOCUS_DELAY_MS)
        }
    }

    // Height of a section title, in pixels, reported by the rails themselves.
    // The band left below a focused rail is built from this rather than from a
    // number guessed at the default type scale — see [RailLanding].
    var railTitleHeightPx by remember { mutableStateOf(0f) }

    // The top bar's own motion length: the shell's transition, or a hard cut
    // for a viewer who asked the platform to stop animating.
    val topBarMotionMs = rememberMotionMs(KB_SCREEN_TRANSITION_MS)

    fun openTopBar(
        requester: FocusRequester
    ) {
        lastPosterFocusRequester = requester
        showTopBar = true
        // Re-arm the hand-off even when the bar is already up (see
        // [topBarSummons]): this is the press that recovers a stranded bar
        // without Back.
        topBarSummons++
    }

    /**
     * Open the top bar from a rail that is NOT the one drawn first - the
     * hold-Up shortcut (see [rememberHomeTopBarHoldUpHook]).
     *
     * [restoreTarget] is the card the viewer was on when the hold began, so the
     * bar's dismiss puts them back exactly there. A null target keeps whatever
     * focus target is already remembered (a rail kind that reports none for its
     * cards), rather than clearing it and leaving the bar's dismiss with nowhere
     * to send focus.
     *
     * That is the one difference from [openTopBar], which is handed the
     * requester of the card it was pressed on and can therefore always store it.
     */
    fun openTopBarFromHold(restoreTarget: FocusRequester?) {
        if (restoreTarget != null) {
            lastPosterFocusRequester = restoreTarget
        }
        showTopBar = true
        // The same re-arm as [openTopBar]: the hold-Up shortcut is a summon too.
        topBarSummons++
    }

    /**
     * Hands focus back to [requester] and makes sure it really landed.
     *
     * The shared menu hand-back, not a loop of Home's own: its FIRST ask is made
     * synchronously, inside the callback that raised it, while the menu is still
     * composed and can hand the focus over. Home used to start every hand-back on
     * the composition scope with an `awaitFrame` in front of it, which put a
     * frame with NO focused node between the menu's dismissal and the card's
     * re-focus; the framework fills that gap with the page's own default target,
     * and arriving there scrolls the rails column to it (bringIntoView). The
     * verified retry then landed on the card a frame later but could not undo the
     * scroll - the reported "I marked a movie as watched on Home and it lost
     * focus of the card and the screen scrolled up". Asking first closes the gap,
     * so the page never moves; the retries still cover a card that is not
     * attached yet (see [rememberFocusHandBack], which documents the same trap
     * for the browse pages' menus).
     */
    val handFocusBack = rememberFocusHandBack()

    fun restoreFocus(requester: FocusRequester?) = handFocusBack(requester)

    fun dismissTopBar() {
        showTopBar = false
        // Where focus IS, not where the last long press happened to leave the
        // stored target (see [focusedCardRequester]). The stored target is the
        // fallback for a rail kind that reports no focused card at all.
        restoreFocus(focusedCardRequester ?: lastPosterFocusRequester)
    }

    // Long-press menu on Continue Watching cards.
    var continueWatchingMenu by remember {
        mutableStateOf<UpNextItem?>(null)
    }

    fun dismissContinueWatchingMenu() {
        continueWatchingMenu = null
        restoreFocus(lastPosterFocusRequester)
    }

    fun openContinueWatchingMenu(item: UpNextItem) {
        continueWatchingMenu = item
    }

    // Long-press menu on Upcoming Schedule cards (Go to Details + Hide).
    var upcomingMenu by remember {
        mutableStateOf<UpcomingEpisode?>(null)
    }

    // Bumped when an Upcoming Hide removed the card the viewer was on; the rail
    // lands on its new head (see BuiltinUpcomingRail), the same signal class the
    // Continue Watching rail uses for its own deletes.
    var upcomingLandOnHeadSignal by remember {
        mutableStateOf(0)
    }

    fun dismissUpcomingMenu() {
        upcomingMenu = null
        restoreFocus(lastPosterFocusRequester)
    }

    // Long-press menu on regular poster rails (movies/series catalogs).
    var posterMenu by remember {
        mutableStateOf<PosterMenuTarget?>(null)
    }

    fun dismissPosterMenu() {
        posterMenu = null
        restoreFocus(lastPosterFocusRequester)
    }

    // Long-press menu on a tile of a Browse rail - a chip mirrored to Home
    // from the search browser's Browse strip.
    var browseShortcutMenu by remember {
        mutableStateOf<BrowseHomeShortcut?>(null)
    }

    fun dismissBrowseShortcutMenu() {
        browseShortcutMenu = null
        restoreFocus(lastPosterFocusRequester)
    }

    // KB-style focus landing (ported from KBTV's ModernHomeContent /
    // ModernHomeRowsList). No snap scrolls anywhere: the rows LazyColumn is
    // wrapped in a BringIntoViewSpec so that when a catalog rail takes
    // focus, Compose's native focus bring-into-view scroll lands the row
    // at a fixed inset below the viewport top. Deterministic landing means:
    //  - no Continue Watching / Upcoming sliver peeking under the hero
    //    (landing on a catalog rail always pushes those rows fully above),
    //  - no bounce (the spec returns the same distance for every child of
    //    the focused row, so the scroll target never changes mid-flight),
    //  - the hero stays visible above the first rail exactly like KB.
    // KB uses MODERN_ROW_HEADER_FOCUS_INSET = 40.dp for the same job;
    // RailHeaderFocusInset mirrors that (defined with the Home constants).
    val density = LocalDensity.current
    val railRowsBringIntoViewSpec = remember(density, railTitleHeightPx) {
        val topInsetPx = with(density) { RailHeaderFocusInset.toPx() }
        val headerFloorPx = with(density) { RailHeaderFloor.toPx() }
        // Rebuilt whenever a rail reports a different title height, so a
        // font-scale change re-reserves the band instead of leaving the next
        // rail's title short of the panel edge.
        val revealBandPx = railRevealBandPx(
            sectionGapPx = with(density) { RailSectionGap.toPx() },
            titleHeightPx = railTitleHeightPx,
            fallbackTitlePx = with(density) { RailLanding.FallbackTitleHeight.toPx() },
            sliverPx = with(density) { RailLanding.NextTitleSliver.toPx() }
        )
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                // What arrives here is the focused CARD, not its rail: `offset`
                // is the card's leading edge and `size` the card's own height
                // (Compose's TV guide calls these "Item's initial position" /
                // "Item's size" - the item that asked to be brought into view).
                // A card sits some way below its rail's top, by a different
                // amount in every rail, so a landing built from the card lands
                // a different line in each row - and on the Upcoming rail, whose
                // card is inset furthest (~52dp of title + row padding + the box
                // that reserves the glow), the 40dp inset put the rail's OWN top
                // 12dp above the viewport and ate the top of its title. Every
                // number below is about the RAIL: map the card back to the
                // LazyColumn item containing it (see [railItemContaining]).
                val rail = railItemContaining(
                    childLeadingEdgePx = offset,
                    visibleItems = railListState.layoutInfo.visibleItemsInfo
                )
                val currentLeadingEdge = rail?.offset?.toFloat() ?: offset
                val railSize = rail?.size?.toFloat() ?: abs(size)
                // Landing line, pulled up when the focused rail is too tall to
                // leave the reveal band below it — a poster rail always is, so
                // it lands on the floor and the next rail's title is fitted
                // into what remains. Depends only on the RAIL's size, so every
                // child of a rail still returns the same distance (no bounce
                // mid-flight). See [RailLanding] for the packing this closes.
                val revealTarget = containerSize - railSize - revealBandPx
                val targetLeadingEdge = railLandingTarget(
                    topInsetPx = topInsetPx,
                    floorPx = headerFloorPx,
                    revealTargetPx = revealTarget
                )
                // Already resting on the landing line: done. This also keeps
                // the spring quiet during horizontal focus moves (no bounce).
                if (abs(currentLeadingEdge - targetLeadingEdge) < 1f) return 0f
                val distance = currentLeadingEdge - targetLeadingEdge
                // Never force the list above its start (mirrors KB's
                // canScrollBackward guard).
                if (distance < 0f && !railListState.canScrollBackward) return 0f
                return distance
            }
        }
    }

    // Horizontal counterpart (KB's ModernHomeRows horizontalBringIntoViewSpec):
    // a focused card lands with its leading edge at the rail's start padding,
    // so the row tracks focus like KB's rails instead of the minimal
    // default scroll (cards never disappear past the left edge). Wrapping
    // each rail's LazyRow with this also SHADOWS the vertical spec above,
    // which would otherwise leak into the rows via CompositionLocalProvider.
    val railCardsBringIntoViewSpec = remember(density) {
        val startPaddingPx = with(density) { RailHorizontalStartPadding.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val childSize = abs(size)
                val initialTarget = startPaddingPx
                val spaceAvailable = containerSize - initialTarget
                val targetForLeadingEdge =
                    if (childSize <= containerSize && spaceAvailable < childSize) {
                        containerSize - childSize
                    } else {
                        initialTarget
                    }
                return offset - targetForLeadingEdge
            }
        }
    }

    fun selectHero(
        item: MetaPreview
    ) {
        userAdjustedFocus = true
        focusedItem = item
        focusedFolder = null
        focusedContinueWatchingItem = null
        focusedBrowseShortcut = null
    }

    /**
     * Focus landed on a card inside the Continue Watching OR Upcoming rows:
     * both drive the hero's episode/progress view. No snap scrolls here
     * anymore — the rows LazyColumn's BringIntoViewSpec handles landing
     * deterministically, and the user's own row is never scrolled away.
     */
    fun selectContinueWatchingHero(
        item: MetaPreview,
        upNextItem: UpNextItem
    ) {
        userAdjustedFocus = true
        focusedItem = item
        focusedFolder = null
        focusedContinueWatchingItem = upNextItem
        focusedBrowseShortcut = null
    }

    // Re-apply rail display settings changed in Settings while we were away
    // (catalog-type / addon-name are read per-composition; the digital
    // release filter needs a rail rebuild from the warm cache).
    LaunchedEffect(Unit) {
        viewModel.onHomeResumed()
        // Pick up Collections-manager edits (import / pin / reorder / hide)
        // made while we were away.
        kbViewModel.load()
    }

    // The bar comes up inside an AnimatedVisibility, so the ask races the frame
    // that attaches its first chip - and on a loaded Onn it can also race the
    // rail beneath it still decoding art and asking for focus back. Two things
    // follow from that, and both are load-bearing:
    //
    // 1. Success is focus OBSERVED inside the bar, not requestFocus()'s answer.
    //    A request that answers true for an attached node can still lose focus a
    //    frame later, so an answer-based exit ended the drive exactly when the
    //    bar was about to be stranded. While focus is not in the bar the request
    //    keeps being issued, at one frame per attempt; the safety cap is only a
    //    runaway guard (see [HOME_TOP_BAR_DRIVE_MAX_FRAMES]).
    // 2. The drive is re-armed by every summon (see [topBarSummons]), so the Up
    //    press the viewer makes on a stranded bar arrives here instead of being
    //    swallowed by an unchanged Boolean.
    LaunchedEffect(showTopBar, topBarSummons) {
        if (!showTopBar) {
            // The bar is gone, so whatever it last reported is stale. Reset here
            // rather than in dismissTopBar: a focus node being disposed by the
            // AnimatedVisibility is not guaranteed to report its own exit, and
            // the next summon must start from "not landed".
            topBarHasFocus = false
            return@LaunchedEffect
        }
        var frames = 0
        while (!topBarHasFocus && frames < HOME_TOP_BAR_DRIVE_MAX_FRAMES) {
            awaitFrame()
            if (!showTopBar) return@LaunchedEffect
            if (topBarHasFocus) break
            runCatching { topBarFocusRequester.requestFocus() }
            frames++
        }
        if (topBarHasFocus) {
            // Info rather than debug: Fire TV suppresses debug logs, and this
            // line is the field report the next symptom should cite.
            Log.i(
                "HOME_FOCUS",
                "Top bar hand-off landed after " + frames + " frames"
            )
        } else {
            Log.w(
                "HOME_FOCUS",
                "Top bar hand-off gave up after " + frames + " frames with the " +
                    "bar still unfocused; Up re-drives it"
            )
        }
    }

    LaunchedEffect(
        firstHomeItem?.id,
        firstHomeItem?.type
    ) {
        if (
            !userAdjustedFocus &&
            firstHomeItem != null
        ) {
            focusedItem = firstHomeItem
            // When the seed came from the Continue Watching rail, also
            // attach its UpNextItem so the hero renders the episode /
            // progress view exactly like focusing the card does.
            if (focusedContinueWatchingItem == null) {
                focusedContinueWatchingItem = upNext.firstOrNull()?.takeIf { cw ->
                    (cw.parentId ?: cw.id) == firstHomeItem.id
                }
            }
        }
    }

    LifecycleEventEffect(
        Lifecycle.Event.ON_RESUME
    ) {
        viewModel.refreshUpNext()
        viewModel.refreshWatchedStatusForCurrentRails()
    }

    // The scope a resume's root re-verification runs in. Its own scope because
    // it is not a focus hand-back: it survives the click and hands the
    // navigation back when the lookup answers (or gives up), while the
    // hand-backs close over [rememberFocusHandBack]'s own scope.
    val resumeVerifyScope = rememberCoroutineScope()

    fun openUpNext(
        item: UpNextItem,
        openInStreamsScreen: Boolean = false,
        startAtBeginning: Boolean = false,
        openDetailsOnly: Boolean = false
    ) {
        val parentId = item.parentId
        val parentType = item.parentType

        if (
            parentId.isNullOrBlank() ||
            parentType.isNullOrBlank()
        ) {
            return
        }

        // A source that wrote 0 where it meant "no episode" must not reach the
        // streams picker or the detail page: an episode the sources never named
        // is exactly what opened a season over blank chips. The item could still
        // carry one from a persisted row, so the guard lives at the use as well
        // as at the builder.
        val namedEpisode =
            namedEpisodeNumber(item.episode)

        val detail = MetaPreview(
            id = parentId,
            type = parentType,
            name = item.title,
            poster = item.poster,
            background = item.backdrop,
            description = item.episodeDescription,
            logo = item.clearLogo
        )

        // Series-episode cards carry the show name in [item.title] with the
        // episode details in separate fields (season/episode/episodeTitle).
        // The streams picker (and everything downstream that re-parses the
        // title) needs the canonical "Sx Ey • Name" shape, so compose it when
        // the card title doesn't already contain the marker.
        val targetTitle = run {
            val hasMarker =
                item.season != null &&
                    namedEpisode != null &&
                    Regex(
                        "S\\s*0*${item.season}\\s*E\\s*0*$namedEpisode\\b",
                        RegexOption.IGNORE_CASE
                    ).containsMatchIn(item.title)
            if (
                parentType == "series" &&
                item.season != null &&
                namedEpisode != null &&
                !hasMarker
            ) {
                buildString {
                    append(item.title)
                    append(" S${item.season} E$namedEpisode")
                    item.episodeTitle
                        ?.takeIf { it.isNotBlank() }
                        ?.let { append(" • $it") }
                }
            } else {
                item.title
            }
        }

        val target = StreamsTarget(
            contentType = parentType,
            streamId = item.episodeStreamId
                ?: item.parentId
                ?: item.id,
            title = targetTitle,
            // The show name that rides itemName into the player and on into the
            // Up Next panel. The card's title is usually the show name, but when
            // the source's name was missing and enrichment failed it is the
            // internal id - and the player used to print that id as the show
            // title, then carry it through every autoplay after it (see
            // upNextPlayerDisplayName).
            displayName = upNextPlayerDisplayName(
                cardTitle = item.title,
                showTitle = item.showTitle,
                composedTitle = targetTitle,
                hasArtwork = !item.poster.isNullOrBlank()
            ),
            season = item.season,
            episode = namedEpisode,
            resumePositionMs =
                if (startAtBeginning) {
                    0L
                } else {
                    item.startPositionMs
                },
            runtimeMinutes =
                item.runtimeMinutes
                    ?.takeIf { it > 0 },
            // "Play from Beginning" is the one path that wants position 0
            // applied as-is: a launch carrying no position of its own resumes
            // the saved watch-history progress instead.
            startFromBeginning = startAtBeginning
        )

        /**
         * Opens [open] with a stream id whose root has been re-verified.
         *
         * A history row hands this screen an id it did not build - written by
         * an older build, or synced from another device before this device had
         * the fix - and a row filed against a catalog id TMDB only knows under
         * the other type names another title's streams. Re-verifying here is
         * the never-again guarantee that does not depend on the migration pass
         * having run, and it costs a map lookup once the title is cached, which
         * Home's own enrichment has already made it.
         *
         * Bounded like every other root wait in this app (the Detail screen's
         * five-second budget): a cold, wrong id gets its one lookup, and
         * anything slower keeps the stored id - today's behavior, no hang.
         */
        fun openWithVerifiedRoot(open: (StreamsTarget) -> Unit) {
            val targetStreamId = target.streamId
            val showName = item.showTitle ?: item.title
            resumeVerifyScope.launch {
                val verified = withTimeoutOrNull(CW_ROOT_VERIFY_TIMEOUT_MS) {
                    runCatching {
                        viewModel.verifiedResumeStreamId(
                            streamId = targetStreamId,
                            showName = showName,
                            type = parentType
                        )
                    }.getOrNull()
                } ?: targetStreamId
                open(target.copy(streamId = verified))
            }
        }

        if (openDetailsOnly) {
            selectHero(detail)
            onItemClick(detail)
        } else if (openInStreamsScreen) {

            // "Play Manually" goes straight to the streams picker and
            // intentionally bypasses Auto-select.
            openWithVerifiedRoot { verified ->
                onOpenStreams(
                    detail,
                    verified,
                    item.poster
                )
            }
        } else {

            selectHero(detail)

            openWithVerifiedRoot { verified ->
                onOpenDetailTarget(
                    detail,
                    verified,
                    item.poster
                )
            }
        }
    }

    // One built-in row - Continue Watching or the Upcoming Schedule - wired to
    // this screen's own callbacks.
    //
    // The built-ins are drawn from two places: the merged rail column, and the
    // no-catalog fallback in the column below it. They read local history and
    // the local schedule, so they have something to show even when no catalog
    // rail built at all - base drew them there for exactly that reason, at
    // column scope above the loading and retry cards, and the arrangement only
    // moved WHERE they are drawn. One lambda so the two paths cannot drift.
    val drawBuiltinRail: @Composable (key: String, isTopRail: Boolean) -> Unit =
        { key, isTopRail ->
            val title = builtinRailTitle(
                key,
                com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                    .builtinDefaultTitle(key)
                    ?: "Upcoming"
            )
            val upHook = if (isTopRail) {
                { requester: FocusRequester -> openTopBar(requester) }
            } else {
                null
            }
            if (
                key ==
                com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                    .BUILTIN_CONTINUE_WATCHING
            ) {
                BuiltinContinueWatchingRail(
                    cards = upNext,
                    title = title,
                    bringIntoViewSpec = railCardsBringIntoViewSpec,
                    onTitleHeight = { railTitleHeightPx = it.toFloat() },
                    onCardFocusRequester = { requester ->
                        lastPosterFocusRequester = requester
                    },
                    cardFocusRequesters = railFocusRequesters,
                    onFocusedCard = { cardKey, requester ->
                        focusedCardRequester = requester
                        // Remembered past this composable's life: the card the
                        // viewer left Home on is what the restore above asks
                        // for when they come back from Detail.
                        viewModel.lastFocusedRailItemKey = cardKey
                    },
                    onOpenUpNext = { item -> openUpNext(item) },
                    onLongPress = { item -> openContinueWatchingMenu(item) },
                    onSelectHero = { hero, item ->
                        selectContinueWatchingHero(hero, item)
                    },
                    landOnHeadSignal = continueWatchingLandOnHeadSignal,
                    // A return to Home is the remembered-place restore's, which
                    // owns focus there (see [HomeViewModel.recordHomeEntry] and
                    // the effect above); only a fresh entry may complete the
                    // landing on this rail's head.
                    isFreshEntry = isFreshEntry,
                    viewerMovedFocusSinceEntry = viewerMovedFocusSinceEntry,
                    railsViewportBounds = railsViewportBounds,
                    onUpPressed = upHook
                )
            } else if (
                key ==
                com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                    .BUILTIN_UPCOMING_SCHEDULE
            ) {
                BuiltinUpcomingRail(
                    episodes = upcomingSchedule,
                    title = title,
                    bringIntoViewSpec = railCardsBringIntoViewSpec,
                    onTitleHeight = { railTitleHeightPx = it.toFloat() },
                    onOpenDetails = { item ->
                        openUpNext(item, openDetailsOnly = true)
                    },
                    onSelectHero = { meta, item ->
                        selectContinueWatchingHero(meta, item)
                    },
                    cardFocusRequesters = railFocusRequesters,
                    onFocusedCard = { cardKey, requester ->
                        focusedCardRequester = requester
                        viewModel.lastFocusedRailItemKey = cardKey
                    },
                    onLongPress = { episode, requester ->
                        // Same contract the Continue Watching cards have:
                        // remember the tile that opened the menu, so dismissing
                        // it hands focus back to that exact tile.
                        lastPosterFocusRequester = requester
                        upcomingMenu = episode
                    },
                    landOnHeadSignal = upcomingLandOnHeadSignal,
                    onUpPressed = upHook,
                    onOpenCalendar = onOpenCalendar
                )
            }
            // No catch-all: a built-in key this build does not know draws
            // NOTHING rather than being silently mislabelled as the Upcoming
            // rail. A String `when` gets no compiler help, so the branch is
            // written out (HD-P2-2).
        }

    // The top-bar hook for the rail drawn at [entryIndex] - null for every rail
    // but the first one in the merged order (see the note above).
    fun topRailUpHook(entryIndex: Int): ((FocusRequester) -> Unit)? =
        if (com.kennyb1201.kbstream.ui.kb.isTopRailEntry(entryIndex)) {
            { requester: FocusRequester -> openTopBar(requester) }
        } else {
            null
        }

    // The built-in rows, in the arrangement's order, for the two branches of the
    // rail column that draw no catalog rails at all (a failed build, and none to
    // build): there is nothing to interleave them with, and they are still worth
    // drawing, because they read local history and the local schedule. Base drew
    // them at column scope above both of those cards.
    fun androidx.compose.foundation.lazy.LazyListScope.builtinRailItems() {
        val builtins =
            mergedEntries.mapNotNull {
                it as? com.kennyb1201.kbstream.ui.kb.HomeEntry.BuiltinRail
            }
        builtins.forEachIndexed { index, entry ->
            item(key = "builtin|" + entry.key.removePrefix("builtin:")) {
                drawBuiltinRail(
                    entry.key,
                    com.kennyb1201.kbstream.ui.kb.isTopRailEntry(index)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // The viewer-intent latch the Continue Watching landing is gated on
            // (see [viewerMovedFocusSinceEntry]). A preview handler at the root
            // sees every D-pad press before the focused node does, whatever is
            // focused - a rail card, a chip, the top bar - and it never consumes
            // one: the press still has to reach the focus system.
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    isHomeFocusMoveKey(event.key)
                ) {
                    viewerMovedFocusSinceEntry = true
                }
                false
            }
            .padding(
                vertical = TvSafeAreaVertical
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            HomeHeroHost(
                focusedItem = focusedItemState,
                focusedFolder = focusedFolderState,
                focusedBrowseShortcut = focusedBrowseShortcutState,
                browseShortcutArt = kbState.browseShortcutArt,
                continueWatchingItem = focusedContinueWatchingItemState,
                heroMeta = heroMetaState,
                heroTmdbDetail = heroTmdbDetailState,
                heroBackdropUrl = heroBackdropUrlState,
                heroLogoUrl = heroLogoUrlState,
                heroTrailerKey = heroTrailerKeyState,
                heroSettledKey = heroSettledKeyState,
                onResolveHeroMeta = { viewModel.resolveHeroMeta(it) }
            )

            // KB parity: the rows list gets the custom vertical
            // BringIntoViewSpec (fixed header landing inset). The opaque
            // hero spacer below is what lets KB's fixed-viewport trick
            // work with a flowing hero: focus scrolling the catalog rails
            // slides the spacer under the hero instead of leaving the
            // CW/Upcoming sliver peeking out from behind it.
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides railRowsBringIntoViewSpec
            ) {
                // KB parity: bottom contentPadding equals the rows
                // viewport so the LAST rail can also land at the focus
                // inset — without it the tail rows stop short and leave a
                // sliver of the rows above them peeking under the hero.
                val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                LazyColumn(
                    state = railListState,
                    // Hold-Up from ANY rail reveals the top bar (see
                    // rememberHomeTopBarHoldUpHook): on the list rather than on
                    // a card, so every rail kind is covered by construction and
                    // the shortcut cannot be lost when a rail is added. A short
                    // press is passed through untouched, so Up still walks focus
                    // rail by rail - and on the first rail the card's own hook
                    // still opens the bar on the press itself.
                    modifier = Modifier
                        .weight(1f)
                        // The viewport the landing's visibility check measures
                        // against (see [railsViewportBounds]); recorded from the
                        // list itself, so it stays right on any panel size and
                        // through any header change.
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            railsViewportBounds.record(
                                topPx = bounds.top,
                                bottomPx = bounds.bottom
                            )
                        }
                        .then(
                            rememberHomeTopBarHoldUpHook(
                                // The card the hold began on - the one focus is
                                // actually on - rather than the last card that
                                // was long-pressed (see [focusedCardRequester]).
                                restoreTargetAtPress = {
                                    focusedCardRequester ?: lastPosterFocusRequester
                                },
                                onOpenTopBar = { target ->
                                    openTopBarFromHold(target)
                                }
                            )
                        ),
                    contentPadding = PaddingValues(
                        top = 0.dp,
                        bottom = screenHeight * 0.52f
                    ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            RailSectionGap
                        )
                ) {
                    item(key = "hero_spacer", contentType = "spacer") {
                        // Opaque spacer that prevents CW progress bar
                        // from peeking below the hero gradient.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(HeroToFirstRailGap)
                                .background(Color.Black)
                        )
                    }



                    when {
                        // Full-screen spinner only when there is NOTHING to show
                        // yet: a rebuild with existing rails keeps them visible
                        // (rails stream in progressively on top of the old list
                        // instead of flashing a loader on every refresh), and a
                        // built-in row or a local collection counts as something
                        // - those are drawn by the branches below, where this
                        // card would otherwise have pushed them off the screen.
                        isLoading && mergedEntries.isEmpty() -> {
                            item(key = "loading", contentType = "skeleton") {
                                // Poster-shaped placeholders, not a spinner and
                                // not the status card: loading is the one state
                                // whose shape is known in advance (see
                                // KBSkeleton), so the rails arrive at their REAL
                                // size instead of the page appearing as a shell
                                // and then shifting down when the posters land.
                                //
                                // Sized from the Poster Size setting and the
                                // viewer's poster edge, so the placeholders are
                                // the tiles about to replace them; the 12dp Home
                                // edge is the documented exception, and the rails
                                // below use the same gutter.
                                val skeletonSize = rememberPosterSize()
                                KBSkeletonRailStack(
                                    posterWidth = skeletonSize.width,
                                    posterHeight = skeletonSize.height,
                                    horizontalPadding = TvSafeAreaHorizontal,
                                    railCount = 3,
                                    shape = posterEdgeShape()
                                )
                            }
                        }

                        // Only when there are rails for it to sit above. With
                        // the rail list empty this branch won over the retry
                        // card below, so a failed cold start showed a dead gray
                        // line instead of the card whose entire point is that
                        // pressing OK retries -- see the empty branch.
                        error != null && rails.isNotEmpty() -> {
                            // The built-in rows still draw: this line is status
                            // about the CATALOG list, and they are not catalog
                            // rows (see builtinRailItems). Base drew them here
                            // too, above this line.
                            builtinRailItems()
                            item(key = "error", contentType = "status") {
                                // The shared status pill rather than a bare
                                // Text line: this is the same "something went
                                // wrong" state every browse screen shows, and
                                // it was the last one still hand-rolled. No
                                // onRetry here - the rails below are what the
                                // viewer is here for, and the pill's retry
                                // card would take focus off them.
                                KBStatusMessage(
                                    message = "Error: $error",
                                    modifier = Modifier.padding(24.dp)
                                )
                            }
                        }

                        // No catalog rails, and no load in flight: the retry
                        // card, with the rows that do not come from a catalog
                        // drawn first (see drawBuiltinRail) - which is where they
                        // used to be drawn, above this card, back when they sat at
                        // column scope. Without that, a profile with history but
                        // no add-ons saw only this card: the rail column below is
                        // reached only when a catalog rail exists, so the
                        // built-ins were dropped from the one state they matter
                        // most in.
                        rails.isEmpty() && !isLoading -> {
                            builtinRailItems()
                            item(key = "empty", contentType = "status") {
                                // The shared status card, with the retry as its
                                // own focusable action rather than the whole
                                // plate being one big clickable - OK on the
                                // remote retries the rail build immediately,
                                // with no need to leave Home or poke a setting
                                // when a cold-start load failed.
                                // A network failure and an empty add-on list are
                                // NOT the same state, and this card used to
                                // assert the wrong one: the ViewModel crafts
                                // "Couldn't reach your add-ons…" for exactly this
                                // branch and the message was dropped on the
                                // floor, so a TV that lost the network on cold
                                // start told the viewer they had no add-ons. Its
                                // text is preferred whenever there IS an error;
                                // "add an addon" is for the case with nothing to
                                // report.
                                val emptyMessage = error?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        "Couldn't reach your add-ons. Check the " +
                                            "network connection, then press OK to retry."
                                    }
                                    ?: "No catalogs available. Add an addon to get started."
                                KBStatusMessage(
                                    message = emptyMessage,
                                    icon = KB_STATUS_ICON_EMPTY,
                                    onRetry = { viewModel.refreshRailsOnly() },
                                    modifier = Modifier.fillParentMaxSize()
                                )
                            }
                        }

                        // Rails exist and nothing is loading, but the merged
                        // order came back empty: every rail the loader produced
                        // has been hidden by the viewer (HD-P2-5). The empty-
                        // catalog card above cannot catch this - it keys on the
                        // CATALOG list, and the rails are right there, just not
                        // visible - so without this branch Home was the hero and
                        // nothing else: no hint, and no way back to the manager
                        // that did the hiding.
                        mergedEntries.isEmpty() && !isLoading -> {
                            item(key = "all-hidden", contentType = "status") {
                                KBStatusMessage(
                                    message = "All rails are hidden.",
                                    icon = KB_STATUS_ICON_EMPTY,
                                    onRetry = onManageRails,
                                    actionLabel = "MANAGE RAILS",
                                    modifier = Modifier.fillParentMaxSize()
                                )
                            }
                        }

                        else -> {
                            // KB collections interleave with addon rails:
                            // the merged order was computed above in composable
                            // context (pin / reorder / hide from the manager;
                            // unarranged collections sit after the addon rails).
                            itemsIndexed(
                                items = mergedEntries,
                                key = { _, entry ->
                                    when (entry) {
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.AddonRail ->
                                            "rail|" + entry.sourceIndex + "|" +
                                                entry.rail.addonName + ":" +
                                                entry.rail.catalogName + ":" + entry.rail.type
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.Collection ->
                                            "kb|" + (entry.collection.id ?: entry.collection.title)
                                        // One key per rail: the chips inside a
                                        // rail are reordered in place rather
                                        // than re-keying the LazyColumn item,
                                        // and a rail is its own arrangement
                                        // key, so it can move on its own.
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.BrowseRail ->
                                            "browse|" + entry.key
                                        // One key per built-in rail, matching the
                                        // "<family>|<id>" shape the rest use.
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.BuiltinRail ->
                                            "builtin|" + entry.key.removePrefix(
                                                "builtin:"
                                            )
                                    }
                                },
                                // One content type per rail KIND: the column
                                // mixes these with spacers and status cards, and
                                // Compose can only reuse a composition slot
                                // between items of the same type. Without this,
                                // inserting, removing or reordering a rail
                                // rebuilt every rail below it from scratch.
                                contentType = { _, entry ->
                                    when (entry) {
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.AddonRail ->
                                            "addon-rail"
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.Collection ->
                                            "kb-collection"
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.BrowseRail ->
                                            "browse-rail"
                                        is com.kennyb1201.kbstream.ui.kb.HomeEntry.BuiltinRail ->
                                            "builtin-rail"
                                    }
                                }
                            ) { entryIndex, entry ->
                                when (val e = entry) {
                                    // A built-in rail: this app's own rows, drawn
                                    // where the arrangement put them rather than
                                    // in a hardcoded slot above the catalogs - and
                                    // the one drawn first owns the top-bar hook
                                    // (see the merged-order note above).
                                    is com.kennyb1201.kbstream.ui.kb.HomeEntry.BuiltinRail ->
                                        drawBuiltinRail(
                                            e.key,
                                            com.kennyb1201.kbstream.ui.kb
                                                .isTopRailEntry(entryIndex)
                                        )

                                    is com.kennyb1201.kbstream.ui.kb.HomeEntry.BrowseRail ->
                                        com.kennyb1201.kbstream.ui.kb.KBHomeBrowseRail(
                                            shortcuts = e.rail.shortcuts,
                                            artByKey = kbState.browseShortcutArt,
                                            title = e.rail.title,
                                            onOpenShortcut = onOpenBrowseShortcut,
                                            onShortcutLongPress = { shortcut, requester ->
                                                // Same contract the catalog
                                                // cards' long-press has: remember
                                                // the tile that opened the menu,
                                                // so dismissing it hands focus
                                                // back to that exact tile.
                                                lastPosterFocusRequester =
                                                    requester
                                                browseShortcutMenu = shortcut
                                            },
                                            chipFocusRequesters =
                                                browseChipFocusRequesters,
                                            onShortcutFocused = { shortcut ->
                                                userAdjustedFocus = true
                                                focusedFolder = null
                                                focusedContinueWatchingItem = null
                                                focusedBrowseShortcut = shortcut
                                            },
                                            cardFocusRequesters =
                                                railFocusRequesters,
                                            onFocusedCard = { cardKey, requester ->
                                                focusedCardRequester = requester
                                                viewModel.lastFocusedRailItemKey =
                                                    cardKey
                                            },
                                            onUpPressed = topRailUpHook(entryIndex)
                                        )
                                    is com.kennyb1201.kbstream.ui.kb.HomeEntry.Collection ->
                                        KBHomeCollectionRail(
                                            collection = e.collection,
                                            onOpenFolder = onOpenKBFolder,
                                            onFolderFocused = { folder: KBFolder ->
                                                userAdjustedFocus = true
                                                focusedFolder = folder
                                                focusedContinueWatchingItem = null
                                                focusedBrowseShortcut = null
                                            },
                                            cardFocusRequesters =
                                                railFocusRequesters,
                                            onFocusedCard = { cardKey, requester ->
                                                focusedCardRequester = requester
                                                viewModel.lastFocusedRailItemKey =
                                                    cardKey
                                            },
                                            onUpPressed = topRailUpHook(entryIndex)
                                        )
                                    is com.kennyb1201.kbstream.ui.kb.HomeEntry.AddonRail -> {
                                        val rail = e.rail

                                        Column(
                                            modifier = Modifier.padding(
                                                start = TvSafeAreaHorizontal,
                                                top = 0.dp,
                                                bottom = 0.dp
                                            )
                                        ) {
                                            SectionTitle(
                                                homeRailTitle(
                                                    // A rename from the home
                                                    // manager (available on a
                                                    // built-in row - the Top
                                                    // Today rows - and on a
                                                    // built catalog) beats the
                                                    // rail's own name.
                                                    catalogName = e.titleOverride
                                                        ?: rail.catalogName,
                                                    addonName = rail.addonName,
                                                    type = rail.type,
                                                    showType = showRailType,
                                                    showAddon = showRailAddon
                                                ),
                                                onHeight = {
                                                    railTitleHeightPx = it.toFloat()
                                                }
                                            )

                                    val railRowState = rememberLazyListState()

                                    InfiniteRailPageHandler(
                                        listState = railRowState,
                                        itemCount = rail.items.size,
                                        railKey = rail.addonName + "::" +
                                            rail.catalogId + "::" + rail.type,
                                        onLoadMore = viewModel::loadMoreForRail
                                    )

                                    CompositionLocalProvider(
                                        LocalBringIntoViewSpec provides railCardsBringIntoViewSpec
                                    ) {
                                        LazyRow(
                                            state = railRowState,
                                            contentPadding = PaddingValues(
                                                start = RailHorizontalStartPadding,
                                                end = TvSafeAreaHorizontal,
                                                top = RailTopContentPadding,
                                                bottom = RailBottomContentPadding
                                            ),
                                            horizontalArrangement =
                                                Arrangement.spacedBy(0.dp)
                                        ) {
                                            itemsIndexed(
                                                items = rail.items,
                                                key = { _, meta ->
                                                    "${meta.type}:${meta.id}"
                                                },
                                                // One reuse pool per media type:
                                                // a movie cell and a series cell
                                                // draw different art and caption
                                                // shapes, so sharing a pool made
                                                // the composables swap contents
                                                // on every scroll that crossed a
                                                // boundary.
                                                contentType = { _, meta -> meta.type }
                                            ) { index, meta ->

                                                val requester = remember {
                                                    FocusRequester()
                                                }

                                                val watched =
                                                    viewModel.watchedKey(
                                                        meta.id,
                                                        meta.type
                                                    ) in watchedKeys

                                                // Eye badge: started but not
                                                // finished. Never shown when the
                                                // completed checkmark resolves.
                                                val watchedPartially =
                                                    !watched &&
                                                        viewModel.watchedKey(
                                                            meta.id,
                                                            meta.type
                                                        ) in partialWatchedKeys

                                                // The rail drawn first owns the
                                                // top-bar hook (see the merged-
                                                // order note above).
                                                val railUpHook =
                                                    topRailUpHook(entryIndex)

                                                val cardWidth =
                                                    if (landscapeCards) {
                                                        HomeLandscapeWidth
                                                    } else {
                                                        HomePosterWidth
                                                    }

                                                // Registered at COMPOSITION, not when a
                                                // card takes focus. Home is disposed while
                                                // its Detail page is open, so on the way
                                                // back this map has to be filled by the
                                                // cards that compose - keyed off the one
                                                // card that happened to take focus first,
                                                // it stayed empty for the whole restore
                                                // window and the remembered place was
                                                // never asked for at all. (The key is
                                                // still remembered on focus, below.)
                                                DisposableEffect(requester) {
                                                    val cardKey =
                                                        "${meta.type}:${meta.id}"
                                                    railFocusRequesters[cardKey] = requester
                                                    onDispose {
                                                        railFocusRequesters.remove(cardKey)
                                                    }
                                                }

                                                val cardHeight =
                                                    if (landscapeCards) {
                                                        HomeLandscapeHeight
                                                    } else {
                                                        HomePosterHeight
                                                    }

                                                val posterModifier = Modifier
                                                    // Outermost, so the
                                                    // shared bounds are
                                                    // what the flight
                                                    // animates.
                                                    .heroSourceElement(
                                                        meta.type,
                                                        meta.id
                                                    )
                                                    .offset(y = (-3).dp)
                                                    // Left from the rail's first
                                                    // card is nowhere (see
                                                    // [homeRailHeadLeftEdgeStop]).
                                                    .homeRailHeadLeftEdgeStop(
                                                        index == 0
                                                    )
                                                    .focusRequester(requester)
                                                    .width(cardWidth)
                                                    .height(cardHeight)
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            val cardKey = "${meta.type}:${meta.id}"
                                                            // Remembered past this composable's
                                                            // life too: the card the viewer left
                                                            // Home on is what focus has to land on
                                                            // when they come back from Detail (see
                                                            // HomeViewModel.lastFocusedRailItemKey).
                                                            viewModel.lastFocusedRailItemKey =
                                                                cardKey
                                                            // The card focus is
                                                            // on (see
                                                            // focusedCardRequester).
                                                            focusedCardRequester = requester
                                                            selectHero(meta)
                                                        }
                                                    }
                                                    .then(
                                                        // Only the rail drawn
                                                        // first consumes D-pad Up
                                                        // (see the merged-order
                                                        // note above).
                                                        Modifier.homeTopRailUpHook(
                                                            requester,
                                                            railUpHook
                                                        )
                                                    )

                                                Box(
                                                    modifier = Modifier
                                                        .width(cardWidth)
                                                        .height(
                                                            cardHeight +
                                                                PosterFocusHeadroom
                                                        )
                                                        .padding(
                                                            end = HomeRailGap
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    val art =
                                                        rail.landscapeArt[
                                                            meta.landscapeArtKey()
                                                        ]

                                                    if (landscapeCards) {
                                                        LandscapeCard(
                                                            // Resolved TMDB art first, then the
                                                            // add-on's own background - and,
                                                            // last, the add-on's POSTER. Rails
                                                            // whose items carry no background at
                                                            // all (catalog-only add-ons, the
                                                            // resume rows when a title has only a
                                                            // poster) had nothing to draw until
                                                            // their landscape art resolved, so a
                                                            // fast scroll showed holes that
                                                            // filled in on the next frame. A
                                                            // cropped poster is the same
                                                            // degradation the global landscape
                                                            // setting already uses; see
                                                            // GlobalPosterCard. Note this is not
                                                            // a way around `tmdbOnly`: a pinned
                                                            // rail's resolved art is a blank
                                                            // MARKER, not null, so it still
                                                            // stops the chain here and keeps its
                                                            // title-only treatment.
                                                            // The viewer's chosen still wins over
                                                            // the rail's own art, including a pinned
                                                            // rail's blank marker: they asked for that
                                                            // image on landscape cards, and it is TMDB
                                                            // art they picked rather than the add-on's
                                                            // promo background the marker exists to
                                                            // keep out.
                                                            backdropUrl = imageOverrideCardBackdropUrl(
                                                                paths = overridePaths,
                                                                rawId = meta.id,
                                                                fallbackUrl = art?.first
                                                                    ?: meta.background
                                                                    ?: meta.poster
                                                            ),
                                                            // The card's logo is TMDB's English
                                                            // wordmark or nothing: the add-on's
                                                            // own `logo` is a bare URL with no
                                                            // language on it, so it is never
                                                            // drawn (see LandscapeArt). The
                                                            // background above keeps its add-on
                                                            // fallback, which is artwork, not a
                                                            // wordmark.
                                                            logoUrl = art?.second,
                                                            fallbackTitle = meta.name,
                                                            contentDescription = meta.name,
                                                            isWatched = watched,
                                                            isPartiallyWatched = watchedPartially,
                                                            // A pinned "Top ... Today" row
                                                            // numbers its cards; no other
                                                            // rail has a standing to show.
                                                            rank = landscapeRank(
                                                                rail.ranked,
                                                                index
                                                            ),
                                                            onClick = {
                                                                selectHero(meta)
                                                                onItemClick(meta)
                                                            },
                                                            onLongClick = {
                                                                lastPosterFocusRequester =
                                                                    requester
                                                                posterMenu =
                                                                    PosterMenuTarget(
                                                                        meta,
                                                                        rail
                                                                    )
                                                            },
                                                            modifier = posterModifier
                                                        )
                                                    } else {
                                                        PosterCard(
                                                            // One choice covers every surface this
                                                            // title's poster appears on.
                                                            posterUrl = imageOverridePosterUrl(
                                                                paths = overridePaths,
                                                                rawId = meta.id,
                                                                fallbackUrl = meta.poster
                                                            ),
                                                            contentDescription = meta.name,
                                                            isWatched = watched,
                                                            isPartiallyWatched = watchedPartially,
                                                            onClick = {
                                                                selectHero(meta)
                                                                onItemClick(meta)
                                                            },
                                                            onLongClick = {
                                                                lastPosterFocusRequester =
                                                                    requester
                                                                posterMenu =
                                                                    PosterMenuTarget(
                                                                        meta,
                                                                        rail
                                                                    )
                                                            },
                                                            modifier = posterModifier,
                                                            onPosterError = { throwable ->
                                                                Log.e(
                                                                    "HOME_UI",
                                                                    "Catalog poster load failed, " +
                                                                        "title=${meta.name}, " +
                                                                        "poster=${meta.poster}",
                                                                    throwable
                                                                )
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(
                            modifier = Modifier.height(
                                0.dp
                            )
                        )
                    }
                }
            }
        }

        // Back dismisses the top bar. Every other overlay on Home (the poster
        // menus) closes on Back because they are their own focus group; the top
        // bar is a plain AnimatedVisibility with no such handling, so a press
        // that opened it deliberately was the one Back press this screen
        // ignored. Enabled only while it is showing, so Back still leaves Home
        // normally the rest of the time.
        BackHandler(enabled = showTopBar) {
            dismissTopBar()
        }

        // The bar drops from the top edge, so the motion should say so - and
        // it takes the shell's transition length, gated on the same reduced-
        // motion flag. It was the one ungated animation on this screen: a
        // default-duration fade with no `rememberReducedMotion()` anywhere near
        // it, on the flagship page, for a viewer who had asked the platform to
        // stop animating.
        AnimatedVisibility(
            visible = showTopBar,
            enter = fadeIn(tween(topBarMotionMs)) + slideInVertically(tween(topBarMotionMs)) { -it / 2 },
            exit = fadeOut(tween(topBarMotionMs)) + slideOutVertically(tween(topBarMotionMs)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                KBVoid.copy(alpha = .96f),
                                KBVoid.copy(alpha = .72f),
                                Color.Transparent
                            )
                        )
                    )
            ) {
                TopActionBar(
                    onSearch = onSearch,
                    onOpenGuide = onOpenGuide,
                    onOpenLibrary = onOpenLibrary,
                    onOpenSettings = onOpenSettings,
                    onSwitchProfile = onSwitchProfile,
                    firstActionFocusRequester =
                        topBarFocusRequester,
                    onBarFocusChanged = { topBarHasFocus = it },
                    onDismiss = {
                        dismissTopBar()
                    }
                )
            }
        }

        // Long-press menu for Continue Watching cards.
        continueWatchingMenu?.let { menuItem ->
            // The card's own rule, so the menu cannot disagree with the title the
            // viewer long-pressed: an episode they have not started is listed
            // without its name, and this menu's subtitle was appending it
            // anyway ("S02 · E05 · The Funeral").
            val hidesMenuEpisodeTitle = rememberHidesEpisodeTitle(
                inProgress = menuItem.badge == UpNextBadge.CONTINUE_WATCHING
            )
            PosterContextMenu(
                title = menuItem.title,
                // The card is a SHOW, not the episode: hiding it takes the
                // show off every screen, so the show's own id and the row's
                // stream key both go in (a card with no parent id still
                // hides itself from Continue Watching).
                hideTarget = hideTarget(
                    menuItem.showTitle ?: menuItem.title,
                    menuItem.parentType
                        ?: if (menuItem.season != null) "series" else "movie",
                    menuItem.poster,
                    listOf(menuItem.parentId, menuItem.id)
                ),
                // No "Add to Library" / "Add to list" here: these cards are
                // an in-progress episode, and the library actions belong to
                // the SHOW - they live on the show's own poster menu (the
                // rails) and on its detail page. Repeating them on a card
                // that only offers one episode's resume just cluttered the
                // menu the viewer opens to keep watching.
                subtitle = buildString {
                    // The rail's own rule, so the menu over a card numbers the
                    // episode the way the card under it does - "S2 E5", not
                    // "S02 · E05" - and the phone's menu cannot spell it
                    // differently either (it reads the same rule).
                    val seasonEpisode = upNextEpisodeLabel(
                        menuItem.season,
                        menuItem.episode
                    ).orEmpty()
                    if (seasonEpisode.isNotBlank()) {
                        append(seasonEpisode)
                    }
                    if (!hidesMenuEpisodeTitle) {
                        menuItem.episodeTitle
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { episodeName ->
                                if (isNotEmpty()) append(" · ")
                                append(episodeName)
                            }
                    }
                }.ifBlank { null },
                actions = listOf(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        openUpNext(
                            selectedItem,
                            openDetailsOnly = true
                        )
                    },
                    PosterContextAction(
                        label = "Play Manually",
                        description = if (menuItem.startPositionMs > 0L) {
                            "Open the streams picker - still resumes at your progress"
                        } else {
                            "Open the streams picker"
                        }
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        openUpNext(
                            selectedItem,
                            openInStreamsScreen = true,
                            startAtBeginning = false
                        )
                    },
                    PosterContextAction(
                        label = "Play from Beginning",
                        description = "Start over from the beginning"
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        openUpNext(
                            selectedItem,
                            startAtBeginning = true
                        )
                    },
                    PosterContextAction(
                        label = "Mark as Watched",
                        description = if (
                            upNextWatchedTarget(menuItem) is
                                UpNextWatchedTarget.Episode
                        ) {
                            "Mark this episode watched and move past it"
                        } else {
                            "Mark this title watched"
                        }
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        viewModel.markContinueWatchingAsWatched(
                            selectedItem
                        )
                        // NOT the card's requester: this action deletes the row
                        // it points at, and a request against a detached node
                        // returns false (discarded) and leaves the D-pad with
                        // nothing. The rail lands on its new head instead, with
                        // a verified request of its own.
                        continueWatchingLandOnHeadSignal++
                    },
                    PosterContextAction(
                        // Names the ROW it takes the card off, so it cannot be
                        // confused with the shared menu's "Hide everywhere"
                        // directly below it: the two verbs used to be swapped
                        // between label and description, and a viewer aiming
                        // for "off this rail" could not tell which row did it.
                        label = "Remove from Continue Watching",
                        description = "Hide this from Continue Watching",
                        isDestructive = true
                    ) {
                        val selectedItem = menuItem
                        continueWatchingMenu = null
                        viewModel.removeFromContinueWatching(
                            selectedItem
                        )
                        // Same as "Mark as Watched" above: the row this card
                        // named is going, so the rail's new head is the landing
                        // - a request against the deleted card lands nowhere.
                        continueWatchingLandOnHeadSignal++
                    }
                ),
                onDismiss = {
                    dismissContinueWatchingMenu()
                }
            )
        }

        // Long-press menu for Upcoming Schedule cards. Two rows - open the
        // title, or take it off every screen - because an upcoming episode has
        // nothing to play, so the Continue Watching menu's in-progress rows do
        // not apply.
        upcomingMenu?.let { episode ->
            val upcomingHero = UpNextItem(
                id = episode.id,
                title = episode.title,
                poster = episode.poster,
                badge = UpNextBadge.NEXT_UP,
                backdrop = episode.backdrop,
                parentId = episode.parentId,
                parentType = episode.parentType,
                airDateLabel = episode.airDateLabel,
                airDateFull = episode.airDateFull,
                season = episode.season,
                episode = episode.episode,
                episodeTitle = episode.episodeTitle
            )
            PosterContextMenu(
                title = episode.title,
                // The card is a SHOW: the show's own id and the row's stream
                // key both go in, so Hide takes it off Continue Watching and
                // the Upcoming row together (a card with no parent id still
                // hides itself).
                hideTarget = hideTarget(
                    episode.title,
                    episode.parentType ?: "series",
                    episode.poster,
                    listOf(episode.parentId, episode.id)
                ),
                actions = listOf(
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        upcomingMenu = null
                        openUpNext(upcomingHero, openDetailsOnly = true)
                    }
                ),
                onBeforeHide = {
                    // The card is about to leave the screen. Bump the rail's
                    // landing signal so it puts focus on its new head after
                    // the recomposition, rather than on the deleted card's
                    // requester - which has nothing to attach to.
                    upcomingLandOnHeadSignal++
                },
                onDismiss = {
                    dismissUpcomingMenu()
                }
            )
        }

        // Long-press menu for regular poster rails (movies/series catalogs).
        posterMenu?.let { target ->
            // Same key the rail badge uses, so the toggle always matches what
            // the poster currently shows: "Mark as Unwatched" when the badge
            // is visible, "Mark as Watched" otherwise.
            val isWatched =
                viewModel.watchedKey(
                    target.meta.id,
                    target.meta.type
                ) in watchedKeys

            // The id the rail card actually carries: TMDB rails use
            // "tmdb:123", add-on catalogs use "tt12345". Splitting it keys
            // the badge and the add off the same id, so a title saved from
            // an add-on rail still shows "In Library ✓" here.
            val railIds = LibraryIds.split(target.meta.id)

            PosterContextMenu(
                title = target.meta.name,
                // The same id pair the poster's own "In Library ✓" badge is
                // read from, now handed to the shared rows: the add and the
                // badge can no longer disagree about which title this is.
                libraryTarget = LibraryAddTarget(
                    mediaType = target.meta.type,
                    imdbId = railIds.imdbId,
                    tmdbId = railIds.tmdbId,
                    title = target.meta.name,
                    year = target.meta.yearOrNull,
                    posterUrl = target.meta.poster
                ),
                hideTarget = hideTarget(
                    target.meta.name,
                    target.meta.type,
                    target.meta.poster,
                    listOf(
                        target.meta.id,
                        railIds.imdbId,
                        railIds.tmdbId?.toString()
                    ),
                    year = target.meta.yearOrNull
                ),
                actions = listOf(
                    PosterContextAction(
                        label = "Open in Grid",
                        description = "Browse this whole catalog as a poster grid"
                    ) {
                        posterMenu = null
                        target.rail?.let(onOpenCatalogGrid)
                    },
                    PosterContextAction(
                        label = "Go to Details",
                        description = "Open this title's detail page"
                    ) {
                        posterMenu = null
                        selectHero(target.meta)
                        onItemClick(target.meta)
                    },
                    PosterContextAction(
                        label = watchedMenuLabel(
                            isWatched = isWatched,
                            mediaType = target.meta.type
                        ),
                        description = watchedMenuDescription(
                            isWatched = isWatched,
                            mediaType = target.meta.type
                        )
                    ) {
                        posterMenu = null
                        if (isWatched) {
                            viewModel.markUnwatched(target.meta)
                        } else {
                            viewModel.markAsWatched(target.meta)
                        }
                        restoreFocus(lastPosterFocusRequester)
                    }
                ),
                onDismiss = {
                    dismissPosterMenu()
                }
            )
        }

        // Long-press menu for a tile on a Browse rail: how a chip mirrored to
        // Home is taken off it again. The other half of this lives on the chip
        // itself in Search ("Add to Home"), and both go through the same pref
        // blob, so the two can never disagree about which chips are on Home.
        browseShortcutMenu?.let { shortcut ->
            // The rail the chip sits in, so the action says WHERE it leaves
            // from: chips are grouped by kind (genres and tags together,
            // services and networks together, ...).
            val railTitle =
                com.kennyb1201.kbstream.data.kb.browseShortcutRail(shortcut.categoryKey)
                    ?.title
                    ?: "Browse"
            PosterContextMenu(
                title = shortcut.name,
                subtitle = "Browse chip",
                actions = listOf(
                    PosterContextAction(
                        label = "Remove from Home",
                        description = "Take this chip off the $railTitle rail",
                        isDestructive = true
                    ) {
                        // Decide the successor BEFORE the removal: once the
                        // chip is gone, the list it has to be found in is the
                        // new one.
                        val successor = browseChipSuccessor(
                            shortcut,
                            kbState.browseShortcuts
                        )
                        browseShortcutMenu = null
                        kbViewModel.removeBrowseShortcut(shortcut)
                        // Land on whatever takes the removed chip's place: the
                        // next chip in the rail, the previous one, or - when
                        // this was the rail's last chip and the whole rail is
                        // dropped - the first chip of the next rail. Asking the
                        // deleted chip's own requester (which is what the old
                        // `lastPosterFocusRequester?.requestFocus()` did)
                        // succeeded pre-recomposition and was then cleared with
                        // the node, stranding the D-pad.
                        restoreFocus(
                            successor
                                ?.let { browseChipFocusRequesters[it.chipKey()] }
                                ?: lastPosterFocusRequester
                        )
                    }
                ),
                onDismiss = {
                    dismissBrowseShortcutMenu()
                }
            )
        }

    }
}

/**
 * Target for the long-press menu on a regular poster rail card. Carries the
 * owning rail so "Open in Grid" can browse the whole catalog.
 */
private data class PosterMenuTarget(
    val meta: MetaPreview,
    val rail: Rail? = null
)

/**
 * Invisible card-sized sentinel that lives at the end of a rail's LazyRow.
 * When the user scrolls far enough that this slot becomes the last visible
 * item in the rail column, it requests the rail's next catalog page from
 * the ViewModel (infinite scroll). Purely invisible — no visual footprint.
 */
/**
 * Infinite scroll for Home rails — same pattern as the genres screen
 * (InfiniteTagRailHandler): when the user scrolls within [threshold] items
 * of the rail's end, ask the ViewModel for the catalog's next page. Keys
 * off the rail's live item count so each append re-arms the check.
 */
@Composable
private fun InfiniteRailPageHandler(
    listState: LazyListState,
    itemCount: Int,
    railKey: String?,
    onLoadMore: (String) -> Unit
) {
    InfiniteScrollEffect(
        listState = listState,
        itemCount = itemCount,
        onLoadMore = { railKey?.let(onLoadMore) }
    )
}
