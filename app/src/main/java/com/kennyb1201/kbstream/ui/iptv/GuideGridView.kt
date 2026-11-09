package com.kennyb1201.kbstream.ui.iptv

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.notifications.ReminderRules
import com.kennyb1201.kbstream.data.reporting.GuideTrace
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

// F12a: 48dp, TiviMate's own row, down from 62. The program cell is 20 + 16 +
// 8 = 44dp of content with its 4dp padding, so the optional second line
// (reminder, score) still fits and no row needs a height exception (F5: the
// score line is why the row does not shrink further).
private val GRID_ROW_HEIGHT: Dp = 48.dp

// F13a/F14b: the top band's two heights. With the preview on it is exactly the
// preview's own 16:9 box, so the band adds no padding around it; the text
// height is the block's fixed height when the preview is off, which is what
// gives that space back to the rows without the band resizing as focus moves.
//
// Density spec F1: the box is 240x135, not 320x180, and the band follows it to
// 135dp - 45dp, about a row, back to the channels. The block keeps its own
// 128dp of text height (F1b): it is not cut to make the band fit, the band has
// 7dp of room over it.
private val GRID_BAND_WITH_PREVIEW: Dp = 135.dp
private val GRID_BAND_TEXT_HEIGHT: Dp = 128.dp
private val GRID_PREVIEW_WIDTH: Dp = 240.dp
private val GRID_PREVIEW_HEIGHT: Dp = 135.dp

// Density spec F2: 24dp, down from 30. One labelSmall day line and one
// labelMedium hour label still clear it - the marks themselves are unchanged.
private val GRID_TIME_HEADER_HEIGHT: Dp = 24.dp

// HEADER-TRAP F2: how many frames the return-from-header handoff retries the
// remembered home's requester before it hands the direction to the platform's
// focus search - the same eight frames the grid's own pending moves get.
private const val HEADER_RETURN_ATTEMPTS = 8

// ENTRY-FOCUS F1c: the entry grab's retry, the same eight frames the screen's
// channel-list entry loop runs - the target row may still be composing when the
// rows first arrive.
private const val ENTRY_FOCUS_ATTEMPTS = 8

/**
 * The TiviMate-style timeline grid (spec §2, §2k, §3, §4).
 *
 * Channels down the left, time across the top, every program cell at its real
 * width, one vertical now-line, and a groups sidebar. It is a VIEW of the
 * existing Guide screen: same ViewModel, same callbacks, same group state - the
 * screen owns the toggle and the header, this file draws the grid.
 *
 * The two things that make or break it, and where they live:
 *
 *  - **Focus** is computed from the loaded program lists, never by geometric
 *    default search ([verticalTarget], [horizontalTarget] in GuideGridRules).
 *    Every arrow has a computed target or is a defined no-op; a row's first cell
 *    hands Left to the channel column, the column hands Left to the sidebar, and
 *    the first row hands Up to the header.
 *  - **Geometry** is one shared horizontal [ScrollState] for the header and every
 *    row (F2a), so the axis cannot drift from the cells under it, and the channel
 *    column and sidebar never scroll sideways.
 *
 * It deliberately does NOT fabricate programs (D3), load a whole group at once
 * (F1a), or grow a FocusRequester per cell it has ever drawn (F3e).
 */
@Composable
internal fun GuideGridView(
    items: List<IptvChannelWithEpg>,
    programsByChannel: Map<String, List<GridProgram>>,
    resolvedGuideIds: Set<String>,
    groups: List<String>,
    groupCount: (String) -> Int,
    selectedGroup: String,
    onSelectGroup: (String) -> Unit,
    reminderKeys: Set<String>,
    nowMs: Long,
    scheduleEndMs: Long?,
    /**
     * The live/finished games the cells may show a score for (F3b): the shared
     * match, resolved once in the ViewModel. Empty is the ordinary case and
     * draws the reminder line exactly as before.
     */
    scores: List<GuideScore> = emptyList(),
    /**
     * Bumped by the header's Down, so focus comes back to where it left (F1e).
     * The screen owns the header, this grid owns the focus homes.
     */
    returnFocusSignal: Int,
    /**
     * ENTRY-FOCUS F1a: the guide has just been opened on this grid, so the grid
     * owes one entry focus grab. The list has always had one; the grid had none,
     * so a guide that opened straight into the persisted grid view started with
     * focus nowhere and every D-pad press found no handler at all.
     *
     * Armed by the SCREEN, not here: the grid is composed and disposed by the
     * view toggle, so an entry effect in this file would also fire on a
     * list->grid toggle (F2a) and fight the header path that already works.
     */
    entryFocusArmed: Boolean = false,
    /** Consumes [entryFocusArmed] once the grab lands or gives up (F1a). */
    onEntryFocusDone: () -> Unit = {},
    /**
     * Whether the groups sidebar is showing. Owned by the screen because the
     * header names the current group while it is hidden (F1f).
     */
    sidebarVisible: Boolean,
    onSidebarVisibleChange: (Boolean) -> Unit,
    /**
     * The corner preview's on/off (F13d). The screen owns it because the header
     * toggle sets it and persists it; the grid only draws it.
     */
    previewEnabled: Boolean,
    /**
     * The channel the preview plays: the last one played, if any (F13b/F13c).
     * Null means the viewer has never played a live channel, and then there is
     * no preview to draw at all.
     */
    previewChannel: IptvChannel?,
    /** The header control Up out of the first row lands on. */
    headerFocusRequester: FocusRequester,
    onPlay: (IptvChannelWithEpg) -> Unit,
    onToggleReminder: (IptvChannelWithEpg, GridCell) -> Unit,
    onCatchup: ((IptvChannelWithEpg, GridCell) -> Unit)?,
    onRequestWindow: (List<String>, Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val reducedMotion = rememberReducedMotion()
    // The direct handoffs scroll the time axis from a key handler, which cannot
    // suspend: the scroll is a frame's work posted ahead of the focus request.
    val scope = rememberCoroutineScope()

    var window by remember { mutableStateOf(GridWindow.opening(nowMs)) }
    var focusedRow by remember { mutableStateOf(0) }
    var focusedCellId by remember { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableStateOf<GridPendingFocus?>(null) }
    var pendingSidebarFocus by remember { mutableStateOf(false) }
    var focusLiveOnNextWindow by remember { mutableStateOf(false) }
    var focusHome by remember { mutableStateOf(GridFocusHome.CHANNEL) }

    val rowListState = rememberLazyListState()
    val timeScroll = rememberScrollState()
    val sidebarState = rememberLazyListState()
    val cellRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val channelRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val sidebarRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val rows = remember(items, programsByChannel, window) {
        items.map { item ->
            val channelKey = gridChannelKey(item)
            GridRowModel(
                item = item,
                channelKey = channelKey,
                cells = rowCells(
                    channelKey = channelKey,
                    programs = programsByChannel[item.channel.id].orEmpty(),
                    window = window,
                ),
            )
        }
    }

    /**
     * Puts [cell]'s left edge at the viewport's left edge - the axis move every
     * cell handoff makes, direct or pending, so the two paths look the same.
     */
    suspend fun scrollTimeTo(row: GridRowModel, cell: GridCell, animate: Boolean) {
        val offsetDp = row.cells.takeWhile { it.id != cell.id }
            .sumOf { cellWidthDp(it.startUtcMillis, it.endUtcMillis) }
        val offsetPx = with(density) { offsetDp.dp.toPx() }.toInt()
        val target = offsetPx.coerceIn(0, timeScroll.maxValue)
        if (animate) timeScroll.animateScrollTo(target) else timeScroll.scrollTo(target)
    }

    // F3e: the per-cell requester map is pruned to the live set, exactly as the
    // list's row map is. A cell that leaves the window leaves; one that returns
    // is recreated.
    LaunchedEffect(rows) {
        val liveCells = rows.asSequence().flatMap { it.cells.asSequence() }.map { it.id }.toHashSet()
        cellRequesters.keys.retainAll(liveCells)
        channelRequesters.keys.retainAll(rows.mapTo(HashSet()) { it.channelKey })
        sidebarRequesters.keys.retainAll(groups.toHashSet())
    }

    // The rows the grid keeps loaded: what is on screen plus a page of lookahead
    // in the scroll direction, capped by the same 80-row request size the list
    // uses (F1a). One call per page, not per row.
    LaunchedEffect(rows, window, rowListState.firstVisibleItemIndex) {
        if (rows.isEmpty()) return@LaunchedEffect
        val first = rowListState.firstVisibleItemIndex
        val visible = rowListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val start = (first - 1).coerceAtLeast(0)
        val end = (first + visible + GuideGrid.LOOKAHEAD_ROWS).coerceAtMost(rows.size)
        val ids = rows.subList(start, end).map { it.item.channel.id }
        if (ids.isNotEmpty()) onRequestWindow(ids, window.startUtcMillis, window.endUtcMillis)
    }

    // F2a: the grid follows now on its own - the NOW button is gone (F2b) and
    // this is what replaced it. On the tick that carries the clock past a
    // window's right edge the axis slides one page and focus re-anchors on the
    // focused row's live program, through the same path the button used to
    // drive. The rule itself is [autoFollowWindow]: only a window the clock has
    // just left follows, so a viewer paged away is never yanked, and the
    // guide's schedule end still stops the axis.
    var autoFollowedEdgeMs by remember { mutableStateOf(0L) }
    LaunchedEffect(nowMs, window.endUtcMillis, scheduleEndMs) {
        if (autoFollowedEdgeMs == window.endUtcMillis) return@LaunchedEffect
        val next = autoFollowWindow(
            window = window,
            nowUtcMillis = nowMs,
            windowHours = GRID_SPAN_HOURS,
            scheduleEndMs = scheduleEndMs,
        ) ?: return@LaunchedEffect
        autoFollowedEdgeMs = window.endUtcMillis
        window = next
        focusLiveOnNextWindow = true
    }

    LaunchedEffect(focusLiveOnNextWindow, rows) {
        if (!focusLiveOnNextWindow) return@LaunchedEffect
        focusLiveOnNextWindow = false
        val row = rows.getOrNull(focusedRow) ?: return@LaunchedEffect
        val live = row.cells.firstOrNull {
            !it.isGap && nowMs >= it.startUtcMillis && nowMs < it.endUtcMillis
        } ?: row.cells.firstOrNull { !it.isGap } ?: return@LaunchedEffect
        pendingFocus = GridPendingFocus(focusedRow, live.id, channelFocus = false)
    }

    // A guide whose schedule now ends before the window opens must not leave the
    // grid pointing at time that no longer exists.
    LaunchedEffect(scheduleEndMs, window.startUtcMillis) {
        val end = scheduleEndMs ?: return@LaunchedEffect
        if (window.startUtcMillis >= end) {
            window = GridWindow.opening(System.currentTimeMillis())
        }
    }

    // Focus drives scroll on both axes, and only focus does (F3c). The pending
    // target is requested after a frame so the row and the cell exist.
    LaunchedEffect(pendingFocus, rows, window) {
        val target = pendingFocus ?: return@LaunchedEffect
        pendingFocus = null
        if (target.rowIndex !in rows.indices) return@LaunchedEffect

        if (!reducedMotion) rowListState.animateScrollToItem(target.rowIndex)
        else rowListState.scrollToItem(target.rowIndex)

        val row = rows[target.rowIndex]
        val cell = target.cellId?.let { id -> row.cells.firstOrNull { it.id == id } }
        if (cell != null) scrollTimeTo(row, cell, animate = !reducedMotion)

        var attempts = 0
        var requesterSeen = false
        while (attempts < 8) {
            val requester = when {
                target.channelFocus -> channelRequesters[row.channelKey]
                cell != null -> cellRequesters[cell.id]
                else -> null
            }
            if (requester != null) requesterSeen = true
            if (requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)) {
                focusedRow = target.rowIndex
                focusedCellId = cell?.id
                return@LaunchedEffect
            }
            withFrameNanos { }
            attempts++
        }
        // F1c: the retry is the fallback, not a silent end. A handoff that dies
        // without a line is the bug this spec is about - the press was consumed
        // and nothing moved, and the log said nothing either.
        val guideLine = "grid handoff pending-giveup row=${target.rowIndex} cell=${target.cellId} " +
            "channel=${target.channelFocus} requester=$requesterSeen attempts=$attempts"
        Log.i(
            "GUIDE",
            guideLine
        )
        GuideTrace.note(guideLine)
    }

    // F1b: the sidebar has to be composed before its requester can take focus,
    // so LEFT from a channel posts a target and this effect lands it - the same
    // retry shape as the grid's own pendingFocus, and it says so when it gives
    // up (F2c: a handoff that dies silently is the whole bug).
    LaunchedEffect(pendingSidebarFocus, sidebarVisible, groups, selectedGroup) {
        if (!pendingSidebarFocus || !sidebarVisible) return@LaunchedEffect
        val groupIndex = groups.indexOf(selectedGroup).coerceAtLeast(0)
        val label = groups.getOrNull(groupIndex)
        var scrolled = false
        var landed = false
        var attempts = 0
        while (attempts < 8) {
            val requester = label?.let { sidebarRequesters[it] }
            if (requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)) {
                landed = true
                break
            }
            // GUIDE-LEFT F1: the map can hold an entry for a row that cannot
            // answer - filed before the sidebar was hidden, or left behind when
            // the LazyColumn disposed it as it scrolled away - and the field
            // paste shows exactly that: requester=true, direct=false, then eight
            // retries against the same detached node. No number of asks revives
            // it, because the retry has no compose step. This is the entry
            // grab's step, copied: the first failed ask is the signal that the
            // row is not there, so it is scrolled into place, and the ask after
            // the next frame sees the fresh requester it files.
            //
            // Guarded on attempt 0 and on the ask having failed, not on
            // [LazyListState.layoutInfo]: a sidebar that has just been hidden
            // can still report its last layout, and a selection that is on
            // screen is not scrolled at all.
            if (attempts == 0) {
                sidebarState.scrollToItem(groupIndex)
                scrolled = true
            }
            withFrameNanos { }
            attempts++
        }
        pendingSidebarFocus = false
        if (landed) focusHome = GridFocusHome.SIDEBAR
        // GUIDE-LEFT F4: `scrolled` says whether the compose step ran, so a paste
        // can tell a handoff that landed on its first ask from one that only
        // landed after the row was brought back - the two cases this line used
        // to conflate, and the reason eight failed attempts looked identical to
        // eight attempts against a row that was never there.
        val guideLine = "grid handoff channel->sidebar group=$label landed=$landed " +
            "attempts=$attempts scrolled=$scrolled"
        Log.i(
            "GUIDE",
            guideLine
        )
        GuideTrace.note(guideLine)
    }

    // F1e: Down out of the header returns to the last focus home - the sidebar
    // row while it is showing, otherwise the channel or cell the viewer was on
    // (a hidden sidebar stays hidden). The handoff remembers which of the two
    // grid homes it was, instead of always assuming the sidebar.
    //
    // HEADER-TRAP spec F2: the return now lands ITSELF, and says so. The field
    // report was a DOWN that stayed on the same header button: the press was
    // claimed and the old shape only ever posted an async target, so a home
    // whose requester was not composed yet ended in silence with focus still
    // on the header. Three paths, in order:
    //
    //  direct    - ask the remembered home's requester right now (it is
    //              composed in the reported case),
    //  fallback  - the pending shape: scroll the row into place and retry the
    //              requester for a few frames,
    //  geometric - neither landed, so hand the direction to the platform's
    //              focus search; the press is already ours, and this is the
    //              one path that cannot leave focus on the header.
    //
    // One GUIDE line per return names the home, the path and the target.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(returnFocusSignal, sidebarVisible) {
        if (returnFocusSignal <= 0) return@LaunchedEffect
        val rowIndex = focusedRow.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
        val onCell = focusHome == GridFocusHome.CELL && focusedCellId != null
        val cell = if (onCell) {
            rows.getOrNull(rowIndex)?.cells?.firstOrNull { it.id == focusedCellId }
        } else {
            null
        }
        val home = when {
            sidebarVisible -> "sidebar"
            cell != null -> "cell"
            else -> "channel"
        }
        val label = groups.getOrNull(groups.indexOf(selectedGroup).coerceAtLeast(0))

        // The target's requester, or null while that target is not composed.
        fun requesterFor(kind: String): FocusRequester? = when (kind) {
            "sidebar" -> label?.let { sidebarRequesters[it] }
            "cell" -> cell?.let { cellRequesters[it.id] }
            else -> rows.getOrNull(rowIndex)?.let { channelRequesters[it.channelKey] }
        }

        val target = when (home) {
            "sidebar" -> "sidebar group=$label"
            "cell" -> "cell row=$rowIndex cell=${cell?.id}"
            else -> "channel row=$rowIndex"
        }

        fun landHome() {
            // Focus is where it was asked to go, so the pending effects have
            // nothing left to do and must not also fire.
            pendingSidebarFocus = false
            pendingFocus = null
            focusHome = when (home) {
                "sidebar" -> GridFocusHome.SIDEBAR
                "cell" -> GridFocusHome.CELL
                else -> GridFocusHome.CHANNEL
            }
        }

        val wanted = requesterFor(home)
        if (wanted != null && runCatching { wanted.requestFocus() }.getOrDefault(false)) {
            landHome()
            val guideLine = "grid handoff header->grid signal=$returnFocusSignal $target " +
                "direct=true landed=$home"
            Log.i(
                "GUIDE",
                guideLine
            )
            GuideTrace.note(guideLine)
            return@LaunchedEffect
        }

        var attempts = 0
        var landed = false
        while (attempts < HEADER_RETURN_ATTEMPTS) {
            val requester = requesterFor(home)
            if (requester != null &&
                runCatching { requester.requestFocus() }.getOrDefault(false)
            ) {
                landed = true
                break
            }
            // A row that has to scroll into place has no requesters until it
            // composes, so the fallback scrolls it first, once.
            if (attempts == 0 && home != "sidebar" && rowIndex in rows.indices) {
                if (!reducedMotion) rowListState.animateScrollToItem(rowIndex)
                else rowListState.scrollToItem(rowIndex)
            }
            withFrameNanos { }
            attempts++
        }
        if (landed) {
            landHome()
            val guideLine = "grid handoff header->grid signal=$returnFocusSignal $target " +
                "direct=false fallback=true landed=$home attempts=$attempts"
            Log.i(
                "GUIDE",
                guideLine
            )
            GuideTrace.note(guideLine)
            return@LaunchedEffect
        }

        // F2b: never end with focus on the header. Geometry is the last
        // resort; the line records that it ran, landed or not.
        pendingSidebarFocus = false
        pendingFocus = null
        val moved = runCatching {
            focusManager.moveFocus(FocusDirection.Down)
        }.getOrDefault(false)
        val guideLine = "grid handoff header->grid signal=$returnFocusSignal $target " +
            "direct=false fallback=false geometric=$moved attempts=$attempts"
        Log.i(
            "GUIDE",
            guideLine
        )
        GuideTrace.note(guideLine)
    }

    // ENTRY-FOCUS F1b/F1c: the grab itself - the list's entry loop, for the
    // grid. Keyed on (armed, rows) because the rows do not exist when the grid
    // first composes on a straight entry: the window request fills them a
    // moment later, so a one-shot grab at composition time would ask for a
    // requester that is not attached and fail exactly like the in-grid moves
    // that used to die silently. The armed flag is consumed either way, so the
    // grab is for guide entry and cannot re-fire mid-session (F1a).
    LaunchedEffect(entryFocusArmed, rows) {
        if (!entryFocusArmed || rows.isEmpty()) return@LaunchedEffect

        // F2b: a header return or an in-grid handoff in flight owns focus in
        // those moments; the grab stands down for good rather than racing it.
        if (pendingFocus != null || pendingSidebarFocus || returnFocusSignal > 0) {
            // F3b: which guard fired used to be invisible - a grab that stood
            // down for a handoff in flight looked exactly like a grab that
            // never armed at all, and the first-enter repro cannot be read
            // without that distinction.
            val guard = when {
                pendingFocus != null -> "pendingFocus"
                pendingSidebarFocus -> "pendingSidebarFocus"
                else -> "returnFocusSignal=$returnFocusSignal"
            }
            GuideTrace.note("grid entry-focus stood-down $guard")
            onEntryFocusDone()
            return@LaunchedEffect
        }

        // F1b: the return effect's own home rule - the selected group's sidebar
        // row while the sidebar is showing, else the first row's channel cell.
        val groupIndex = groups.indexOf(selectedGroup).coerceAtLeast(0)
        val label = groups.getOrNull(groupIndex)
        val home = if (sidebarVisible) "sidebar" else "channel"
        val firstRow = rows.first()
        val target = if (home == "sidebar") {
            "sidebar group=$label"
        } else {
            "channel row=0 key=${firstRow.channelKey}"
        }

        fun requesterFor(): FocusRequester? = if (home == "sidebar") {
            label?.let { sidebarRequesters[it] }
        } else {
            channelRequesters[firstRow.channelKey]
        }

        var attempts = 0
        var landed = false
        while (attempts < ENTRY_FOCUS_ATTEMPTS) {
            val requester = requesterFor()
            if (requester != null &&
                runCatching { requester.requestFocus() }.getOrDefault(false)
            ) {
                landed = true
                break
            }
            // A selected group the sidebar has not composed yet (it opens at the
            // top, and the selection can be far down) has no requester until it
            // is scrolled into place - the same reason the in-grid handoffs
            // scroll before they retry.
            if (attempts == 0 && home == "sidebar" && requester == null) {
                sidebarState.scrollToItem(groupIndex)
            }
            awaitFrame()
            attempts++
        }

        if (landed) {
            focusedRow = 0
            focusedCellId = null
            focusHome = if (home == "sidebar") GridFocusHome.SIDEBAR else GridFocusHome.CHANNEL
            pendingSidebarFocus = false
            val guideLine = "grid entry-focus landed=$target attempts=$attempts"
            Log.i("GUIDE", guideLine)
            GuideTrace.note(guideLine)
        } else {
            // F1c: a still-dead entry has to be readable in COPY DIAGNOSTICS,
            // not silent - which is what the field report was.
            val guideLine = "grid entry-focus exhausted home=$home attempts=$attempts"
            Log.i("GUIDE", guideLine)
            GuideTrace.note(guideLine)
        }
        onEntryFocusDone()
    }

    /**
     * F1a/F1b: delivers one in-grid move the way [enterGrid] already delivers a
     * sidebar one - the target's own requester FIRST, the pending path as the
     * fallback, one GUIDE line either way.
     *
     * The field report is that every move inside the grid failed while the
     * sidebar moves worked, and the code said why: the sidebar moves asked the
     * target directly, every in-grid move only posted a target that an effect
     * had eight frames to land. That effect cannot land a row the viewer is
     * about to scroll into - there is no requester until it composes - and when
     * it gives up, the press has already been consumed.
     *
     * [cell] null means the channel column of [rowIndex]. The direct path also
     * moves the time axis: a horizontally scrolled row does not bring a focused
     * child into view the way a LazyRow does, so without it a RIGHT onto a cell
     * past the right edge would focus something off screen.
     */
    fun handoffFocus(rowIndex: Int, cell: GridCell?, direction: String) {
        val row = rows.getOrNull(rowIndex)
        if (row == null) {
            val guideLine = "grid handoff $direction row=$rowIndex dropped=no-row"
            Log.i("GUIDE", guideLine)
            GuideTrace.note(guideLine)
            return
        }
        val requester = if (cell == null) {
            channelRequesters[row.channelKey]
        } else {
            cellRequesters[cell.id]
        }
        // Only a row already on screen is asked directly: a move to a row the
        // viewer is about to scroll to still needs the effect's scroll, and its
        // cells have no requesters yet anyway.
        val rowOnScreen = rowListState.layoutInfo.visibleItemsInfo.any { it.index == rowIndex }
        val direct = rowOnScreen && requester != null &&
            runCatching { requester.requestFocus() }.getOrDefault(false)
        if (direct) {
            focusedRow = rowIndex
            focusedCellId = cell?.id
            focusHome = if (cell == null) GridFocusHome.CHANNEL else GridFocusHome.CELL
            if (cell != null) scope.launch { scrollTimeTo(row, cell, animate = !reducedMotion) }
        } else {
            pendingFocus = GridPendingFocus(rowIndex, cell?.id, channelFocus = cell == null)
        }
        val guideLine = "grid handoff $direction row=$rowIndex cell=${cell?.id} onScreen=$rowOnScreen " +
            "requester=${requester != null} direct=$direct fallback=${!direct}"
        Log.i(
            "GUIDE",
            guideLine
        )
        GuideTrace.note(guideLine)
    }

    /**
     * F1a, F2a-F2c: RIGHT out of a group enters the grid on the row the viewer
     * was on, and the sidebar goes away with it (F-E: TiviMate hides it while
     * focus is in the grid).
     *
     * The DIRECT request is the one that has to work - the target row is on
     * screen in the reported case, so a handoff that only ever posts an async
     * target is the silent stay that was reported. The pending path stays as
     * the fallback for rows that still need scrolling into place, and one GUIDE
     * line records which path ran and what it returned.
     */
    fun enterGrid(rowIndex: Int) {
        val row = rows.getOrNull(rowIndex) ?: return
        val requester = channelRequesters[row.channelKey]
        val direct = requester != null &&
            runCatching { requester.requestFocus() }.getOrDefault(false)
        if (direct) {
            focusedRow = rowIndex
            focusedCellId = null
            focusHome = GridFocusHome.CHANNEL
        }
        // F2b: the hide rides with the request, never after it - the channel
        // column lives outside the sidebar, so focus has a home either way.
        onSidebarVisibleChange(false)
        if (!direct) {
            pendingFocus = GridPendingFocus(rowIndex, cellId = null, channelFocus = true)
        }
        val guideLine = "grid handoff sidebar->channel row=$rowIndex channel=${row.channelKey} " +
            "requester=${requester != null} direct=$direct fallback=${!direct}"
        Log.i(
            "GUIDE",
            guideLine
        )
        GuideTrace.note(guideLine)
        // F3c: the collapse itself, on one line - the request's own result and
        // the hide that rode with it. The intermittent stuck-sidebar report had
        // no evidence at all: the focus request and the hide happen together, so
        // a sidebar that stayed could not be told from a request that never
        // landed. The hide is unconditional here (the call above), which is what
        // `sidebarHidden=true` records.
        GuideTrace.note(
            "grid collapse sidebar->channel row=$rowIndex landed=$direct sidebarHidden=true"
        )
    }

    fun activateCell(item: IptvChannelWithEpg, cell: GridCell) {
        // A gap has nothing to play, remind or catch up (F2g); the block above
        // has already said so.
        if (cell.isGap) return
        when {
            cell.startUtcMillis > nowMs -> onToggleReminder(item, cell)
            nowMs < cell.endUtcMillis -> onPlay(item)
            else -> onCatchup?.invoke(item, cell)
        }
    }

    /**
     * One arrow press, answered from the loaded lists (F3a, F3b, F3d, F3g).
     *
     * Every branch resolves the target with the same rules it always used and
     * then DELIVERS it through [handoffFocus] - direct first, pending as the
     * fallback, one GUIDE line. The destination is unchanged (T2); only the
     * delivery is (F1).
     */
    fun onCellKey(rowIndex: Int, cell: GridCell, key: Key): Boolean {
        val row = rows.getOrNull(rowIndex) ?: return false
        when (key) {
            Key.DirectionLeft -> {
                val target = horizontalTarget(row.cells, cell.id, -1)
                if (target != null) {
                    handoffFocus(rowIndex, target, "cell-left")
                } else {
                    handoffFocus(rowIndex, null, "cell-left-to-channel")
                }
                return true
            }
            Key.DirectionRight -> {
                val target = horizontalTarget(row.cells, cell.id, 1)
                if (target != null) {
                    handoffFocus(rowIndex, target, "cell-right")
                    return true
                }
                // Past the last cell: page the window and land on the first
                // program of the new one in this row (F3d).
                val next = window.paged(
                    direction = 1,
                    floorStartUtcMillis = GridWindow.opening(nowMs).startUtcMillis,
                    maxEndUtcMillis = scheduleEndMs,
                )
                if (next == window) return true
                window = next
                val first = rows[rowIndex].cells.firstOrNull { !it.isGap }
                if (first != null) handoffFocus(rowIndex, first, "cell-right-page")
                return true
            }
            Key.DirectionUp, Key.DirectionDown -> {
                val direction = if (key == Key.DirectionUp) -1 else 1
                val target = verticalTarget(rows.map { it.cells }, rowIndex, cell, direction)
                if (target != null) {
                    handoffFocus(
                        target.rowIndex,
                        target.cell,
                        if (direction < 0) "cell-up" else "cell-down",
                    )
                } else if (direction < 0) {
                    // Up out of the first row reaches the header (F3g); Down at
                    // the bottom is a defined no-op.
                    runCatching { headerFocusRequester.requestFocus() }
                }
                return true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                activateCell(row.item, cell)
                return true
            }
            else -> return false
        }
    }

    fun onChannelKey(rowIndex: Int, key: Key): Boolean = when (key) {
        Key.DirectionLeft -> {
            // F1b: the sidebar comes back and takes focus, on the current group.
            //
            // GUIDE-LEFT F2: direct only when the row is ALREADY composed. A row
            // the sidebar is not composing - hidden a moment ago, or scrolled out
            // of its window - has a map entry but no attached node, so asking it
            // cannot land: that ask was the field's `direct=false`, one wasted
            // frame followed by a retry loop with no way to succeed. The effect
            // owns the first ask after the frame and the scroll instead.
            //
            // `sidebarVisible` is the composable's own parameter, so it still
            // holds the value this frame was composed with - false while focus
            // was in the grid, which is the state every reported LEFT press
            // arrived in.
            onSidebarVisibleChange(true)
            val index = groups.indexOf(selectedGroup).coerceAtLeast(0)
            val requester = sidebarRequesters[groups.getOrNull(index)]
            val rowOnScreen = sidebarVisible &&
                sidebarState.layoutInfo.visibleItemsInfo.any { it.index == index }
            val direct = rowOnScreen && requester != null &&
                runCatching { requester.requestFocus() }.getOrDefault(false)
            if (direct) {
                focusHome = GridFocusHome.SIDEBAR
            } else {
                pendingSidebarFocus = true
            }
            val guideLine = "grid handoff channel->sidebar row=$rowIndex group=$selectedGroup " +
                "requester=${requester != null} direct=$direct fallback=${!direct}"
            Log.i(
                "GUIDE",
                guideLine
            )
            GuideTrace.note(guideLine)
            true
        }
        Key.DirectionRight -> {
            val row = rows.getOrNull(rowIndex) ?: return true
            val first = row.cells.firstOrNull { !it.isGap } ?: return true
            handoffFocus(rowIndex, first, "channel-right")
            true
        }
        Key.DirectionUp -> {
            if (rowIndex == 0) {
                runCatching { headerFocusRequester.requestFocus() }
            } else {
                handoffFocus(rowIndex - 1, null, "channel-up")
            }
            true
        }
        Key.DirectionDown -> {
            if (rowIndex < rows.lastIndex) handoffFocus(rowIndex + 1, null, "channel-down")
            true
        }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            rows.getOrNull(rowIndex)?.let { onPlay(it.item) }
            true
        }
        else -> false
    }

    fun onSidebarKey(index: Int, key: Key): Boolean = when (key) {
        Key.DirectionUp -> {
            if (index == 0) {
                runCatching { headerFocusRequester.requestFocus() }
            } else {
                val previous = groups.getOrNull(index - 1)
                if (previous != null) {
                    sidebarRequesters[previous]?.let { runCatching { it.requestFocus() } }
                }
            }
            true
        }
        Key.DirectionDown -> {
            val next = groups.getOrNull(index + 1)
            if (next != null) sidebarRequesters[next]?.let { runCatching { it.requestFocus() } }
            true
        }
        Key.DirectionRight -> {
            // Into the grid on the row the viewer was on, and the sidebar
            // leaves the way (F1a, F-E).
            if (rows.isNotEmpty()) enterGrid(focusedRow.coerceIn(0, rows.lastIndex))
            true
        }
        Key.DirectionLeft -> true
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            groups.getOrNull(index)?.let(onSelectGroup)
            true
        }
        else -> false
    }

    val focusedCell: GridCell? = remember(rows, focusedRow, focusedCellId) {
        rows.getOrNull(focusedRow)?.cells?.firstOrNull { it.id == focusedCellId }
    }
    val focusedItem = rows.getOrNull(focusedRow)?.item
    // F14c: the block's cell is the focused one, or - for a gap, or before the
    // grid has focus at all - the focused row's live program, the same rule the
    // removed strip used.
    val focusedRowCells = rows.getOrNull(focusedRow)?.cells.orEmpty()
    val detailCell = focusedCell?.takeUnless { it.isGap }
        ?: focusedRowCells.firstOrNull {
            !it.isGap && nowMs >= it.startUtcMillis && nowMs < it.endUtcMillis
        }

    // F13/F14: the top band - the preview in the left corner, the focused
    // program's block beside it. It is chrome, above both columns: no grid cell
    // sits under it (F13a) and it is not a focus stop (F13f/F14d).
    // Density spec F4: 4dp, down from the strip era's 12dp safe area. The strip
    // is gone, so this only keeps the last row off the screen edge.
    Column(modifier = modifier.fillMaxSize().padding(bottom = 4.dp)) {
        GridTopBand(
            previewEnabled = previewEnabled,
            previewChannel = previewChannel,
            item = focusedItem,
            cell = detailCell,
            nowMs = nowMs,
            hasCatchup = focusedItem?.let {
                it.channel.catchupSource != null || it.channel.catchup != null
            } == true,
        )

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // F1d: the sidebar slides away when focus enters the grid and slides
            // back on LEFT. Reduced motion is the grid's existing hard cut.
            AnimatedVisibility(
                visible = sidebarVisible,
                enter = if (reducedMotion) {
                    EnterTransition.None
                } else {
                    slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
                },
                exit = if (reducedMotion) {
                    ExitTransition.None
                } else {
                    slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                },
            ) {
                GroupsSidebar(
                    groups = groups,
                    selectedGroup = selectedGroup,
                    groupCount = groupCount,
                    state = sidebarState,
                    requesters = sidebarRequesters,
                    onKey = ::onSidebarKey,
                    onRowFocus = { focusHome = GridFocusHome.SIDEBAR },
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // The sticky time header: the same shared scroll state as every
                // row, so its labels cannot drift from the cells under them
                // (F2e).
                Row(modifier = Modifier.fillMaxWidth().height(GRID_TIME_HEADER_HEIGHT)) {
                    Spacer(modifier = Modifier.width(GuideGrid.CHANNEL_COLUMN_WIDTH_DP.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Row(
                            modifier = Modifier.horizontalScroll(timeScroll)
                                .height(GRID_TIME_HEADER_HEIGHT),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            hourMarks(window).forEach { mark ->
                                Box(
                                    modifier = Modifier.width(
                                        (GuideGrid.MINUTES_PER_HOUR * GRID_SCALE_DP_PER_MIN).dp
                                    )
                                ) {
                                    // Header-clip F1a: the day rides on the hour's
                                    // OWN line - `Sun Nov 8 · 3 PM`, accent for the
                                    // day, plain for the hour. Stacking them needed
                                    // ~28-31dp of Inter in a 24dp strip, so the
                                    // boundary mark's hour was clipped at the bottom
                                    // edge; every other mark was one line and fit,
                                    // which is why only the first one was cut. The
                                    // phone has drawn this line all along.
                                    val line = hourMarkLine(mark)
                                    val day = mark.dayLabel
                                    Text(
                                        text = buildAnnotatedString {
                                            if (day != null) {
                                                withStyle(SpanStyle(color = KBAccent)) {
                                                    append(day)
                                                }
                                                append(line.substring(day.length))
                                            } else {
                                                append(line)
                                            }
                                        },
                                        color = KBTextLo,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    state = rowListState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 6.dp)
                    // Density spec F3: rows sit adjacent, no 2dp gap. The 48dp
                    // row edge is the separator and the focus ring is unchanged.
                ) {
                    itemsIndexed(items = rows, key = { _, row -> row.channelKey }) { index, row ->
                        Row(modifier = Modifier.fillMaxWidth().height(GRID_ROW_HEIGHT)) {
                            ChannelCell(
                                row = row,
                                isFocusedRow = index == focusedRow,
                                // An empty entry now means "read, and empty" on the
                                // shared map (F2a). TV reads it exactly as it read an
                                // absent one, so its rendering does not change (S5).
                                guidePending = row.item.channel.id !in resolvedGuideIds &&
                                    programsByChannel[row.item.channel.id].isNullOrEmpty(),
                                requester = remember(row.channelKey) {
                                    FocusRequester().also {
                                        channelRequesters[row.channelKey] = it
                                    }
                                },
                                onKey = { key -> onChannelKey(index, key) },
                                onFocus = {
                                    focusedRow = index
                                    focusedCellId = null
                                    focusHome = GridFocusHome.CHANNEL
                                },
                                onPlay = { onPlay(row.item) },
                            )

                            ProgramRow(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                row = row,
                                window = window,
                                nowMs = nowMs,
                                focusedCellId = if (index == focusedRow) focusedCellId else null,
                                reminderKeys = reminderKeys,
                                scores = scores,
                                channelId = row.item.channel.id,
                                requesters = cellRequesters,
                                timeScroll = timeScroll,
                                onCellFocus = { cell ->
                                    focusedRow = index
                                    focusedCellId = cell.id
                                    focusHome = GridFocusHome.CELL
                                },
                                onKey = { cell, key -> onCellKey(index, cell, key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One grid row: the channel and its cells for the current window. */
private data class GridRowModel(
    val item: IptvChannelWithEpg,
    val channelKey: String,
    val cells: List<GridCell>,
)

private data class GridPendingFocus(
    val rowIndex: Int,
    val cellId: String?,
    val channelFocus: Boolean,
)

/**
 * Where focus was last: what the header's Down returns to (F1e). The sidebar
 * and the grid are different homes because a hidden sidebar must stay hidden
 * while the viewer is in the grid.
 */
private enum class GridFocusHome { SIDEBAR, CHANNEL, CELL }

/** The grid keys rows and cells by the playlist channel key the guide uses. */
private fun gridChannelKey(item: IptvChannelWithEpg): String =
    item.channel.id.ifBlank { item.channel.streamUrl }

/**
 * The groups sidebar (F2k): 220dp, name + visible count, selected row
 * highlighted, chain program -> channel -> sidebar -> channel.
 *
 * It shows while the viewer is choosing a group and slides away once focus
 * enters the grid (F1), the way TiviMate does it, so the channels get the full
 * width. LEFT from a channel column brings it back.
 *
 * It only DISPLAYS the order; reordering lives in the guide's Manage hidden
 * dialog (F2k-g), the one place that already lists every group.
 */
@Composable
private fun GroupsSidebar(
    groups: List<String>,
    selectedGroup: String,
    groupCount: (String) -> Int,
    state: LazyListState,
    requesters: MutableMap<String, FocusRequester>,
    onKey: (Int, Key) -> Boolean,
    onRowFocus: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(GuideGrid.SIDEBAR_WIDTH_DP.dp)
            .fillMaxHeight()
            .padding(end = 12.dp)
    ) {
        Text(
            text = "GROUPS",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 6.dp)
        )
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(items = groups, key = { _, label -> "sidebar-$label" }) { index, label ->
                val selected = label == selectedGroup
                val requester = remember(label) {
                    FocusRequester().also { requesters[label] = it }
                }
                var focused by remember(label) { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requester)
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) onRowFocus()
                        }
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && onKey(index, event.key)
                        }
                        .focusable()
                        .clip(KBShapeChip)
                        .background(
                            when {
                                // The focused row is near-white on the focus
                                // treatment, never accent-on-accent (F2k-b).
                                focused -> KBAccent.copy(alpha = 0.22f)
                                selected -> KBSurfaceRaised
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = when {
                                focused -> KBAccent
                                selected -> KBAccent.copy(alpha = 0.45f)
                                else -> Color.Transparent
                            },
                            shape = KBShapeChip
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            color = when {
                                focused -> Color.White
                                selected -> KBAccent
                                else -> KBTextHi
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = groupCount(label).toString(),
                            color = if (focused) Color.White.copy(alpha = 0.9f) else KBTextLo,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The fixed channel column's cell: logo, name, number, and the live press. */
@Composable
private fun ChannelCell(
    row: GridRowModel,
    isFocusedRow: Boolean,
    guidePending: Boolean,
    requester: FocusRequester,
    onKey: (Key) -> Boolean,
    onFocus: () -> Unit,
    onPlay: () -> Unit,
) {
    val item = row.item
    var focused by remember(row.channelKey) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(GuideGrid.CHANNEL_COLUMN_WIDTH_DP.dp)
            .fillMaxHeight()
            .padding(end = 4.dp)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && onKey(event.key)
            }
            .focusable()
            .clip(KBShapeChip)
            .background(if (focused) KBAccent.copy(alpha = 0.20f) else KBSurface)
            .border(
                width = 1.dp,
                color = if (focused) KBAccent else Color.Transparent,
                shape = KBShapeChip
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val logoUrl = item.channel.logoUrl ?: item.epgChannel?.iconUrl
            Box(
                // F12b: 28dp, not 34 - the logo and the name line have to fit
                // 44dp of a 48dp row (F4: the group line that used to sit under
                // the name is gone, so the second line is only the number or the
                // loading state).
                modifier = Modifier
                    .size(28.dp)
                    .clip(KBShapeChip)
                    .background(KBVoid.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = item.channel.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                } else {
                    // No logo means the NAME only - never a broken-image box
                    // (F2f). Tile initials are the list card's own fallback.
                    Text(
                        text = item.channel.displayName
                            .split(" ")
                            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                            .take(2)
                            .joinToString(""),
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = item.channel.displayName.ifBlank { "Live Channel" },
                    color = if (focused || isFocusedRow) KBTextHi else KBTextLo,
                    // F12b: labelMedium, so the name and its number still fit
                    // the 48dp row without the second line being clipped (F4).
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isFocusedRow) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // F4: the number, the loading state, or nothing. The group
                // title used to sit here too, under every channel in the group
                // - the same word repeated down the whole column, and space the
                // cell's one real line needed. The group is not lost: it is the
                // header's title while the sidebar is away (F1f) and a row of
                // the sidebar itself. Nothing replaces it here.
                val number = item.channel.tvgChno?.trim()?.takeIf { it.isNotBlank() }
                Text(
                    text = when {
                        number != null -> number
                        guidePending -> "Loading guide…"
                        else -> " "
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One row of program cells, scrolled by the shared horizontal state.
 *
 * Cells are drawn at their real width and clipped to the window; the now-line
 * lives inside the scrolled content so it tracks the time axis, not the screen.
 */
@Composable
private fun ProgramRow(
    modifier: Modifier = Modifier,
    row: GridRowModel,
    window: GridWindow,
    nowMs: Long,
    focusedCellId: String?,
    reminderKeys: Set<String>,
    /** The pass's resolved games; each cell asks [scoreFor] what it draws (F3b). */
    scores: List<GuideScore>,
    channelId: String,
    requesters: MutableMap<String, FocusRequester>,
    timeScroll: ScrollState,
    onCellFocus: (GridCell) -> Unit,
    onKey: (GridCell, Key) -> Boolean,
) {
    val nowOffsetDp = ((nowMs - window.startUtcMillis) / 60_000L * GRID_SCALE_DP_PER_MIN).toInt()
    val nowInWindow = nowMs in window.startUtcMillis until window.endUtcMillis

    Box(modifier = modifier.clipToBounds()) {
        Row(modifier = Modifier.horizontalScroll(timeScroll).height(GRID_ROW_HEIGHT)) {
            // The scrolled content is the WINDOW's width (F1b): 2880dp for the
            // six-hour span, and the cells are laid out at their real widths
            // inside it.
            Box(
                modifier = Modifier
                    .width(gridContentWidthDp(window).dp)
                    .height(GRID_ROW_HEIGHT)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    row.cells.forEach { cell ->
                        key(cell.id) {
                            // F3b: ONE lookup for both surfaces - the phone cell
                            // calls the same function.
                            val score = scoreFor(channelId, cell, nowMs, scores)
                            ProgramCell(
                                cell = cell,
                                isFocused = cell.id == focusedCellId,
                                isLive = !cell.isGap &&
                                    nowMs >= cell.startUtcMillis && nowMs < cell.endUtcMillis,
                                nowMs = nowMs,
                                score = score,
                                // The two lines share one slot: a live score wins
                                // it while the game is on and the reminder marker
                                // comes back after it goes final. The reminder
                                // stays SET - only its indicator is suppressed.
                                reminderSet = score == null && !cell.isGap && reminderKeys.contains(
                                    ReminderRules.reminderKey(channelId, cell.startUtcMillis)
                                ),
                                requester = remember(cell.id) {
                                    FocusRequester().also { requesters[cell.id] = it }
                                },
                                onKey = { key -> onKey(cell, key) },
                                onFocus = { onCellFocus(cell) },
                            )
                        }
                    }
                }
                if (nowInWindow) {
                    Box(
                        modifier = Modifier
                            .offset(x = nowOffsetDp.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(KBAccent.copy(alpha = 0.9f))
                    )
                }
            }
        }
    }
}

/** One program or gap cell. */
@Composable
private fun ProgramCell(
    cell: GridCell,
    isFocused: Boolean,
    isLive: Boolean,
    nowMs: Long,
    /** The game this cell is carrying, if any (F3b); null draws the reminder line. */
    score: GridCellScore? = null,
    reminderSet: Boolean,
    requester: FocusRequester,
    onKey: (Key) -> Boolean,
    onFocus: () -> Unit,
) {
    var focused by remember(cell.id) { mutableStateOf(false) }
    val width = cellWidthDp(cell.startUtcMillis, cell.endUtcMillis).dp
    val fillTarget = if (isLive) liveFillFraction(cell, nowMs) else 0f
    val animatedFill by animateFloatAsState(
        targetValue = fillTarget,
        animationSpec = tween(durationMillis = 240),
        label = "gridCellFill"
    )

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && onKey(event.key)
            }
            .focusable()
            .clip(KBShapeCard)
            .background(if (cell.isGap) KBVoid.copy(alpha = 0.35f) else KBSurface)
            .then(
                if (isLive && !cell.isGap) {
                    Modifier.background(
                        Brush.horizontalGradient(
                            0f to KBAccent.copy(alpha = 0.30f),
                            animatedFill to KBAccent.copy(alpha = 0.30f),
                            animatedFill to Color.Transparent,
                            1f to Color.Transparent
                        )
                    )
                } else {
                    Modifier
                }
            )
            .border(
                width = if (focused || isFocused) 2.dp else 1.dp,
                color = when {
                    focused || isFocused -> KBAccent
                    isLive -> KBAccent.copy(alpha = 0.35f)
                    else -> KBSurfaceRaised
                },
                shape = KBShapeCard
            )
            // F12a: 4dp, not 6 - 20 + 16 of text plus 8 of padding is the 44dp
            // this cell has inside a 48dp row.
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column {
            Text(
                text = cell.title.orEmpty(),
                color = if (cell.isGap) KBTextLo else KBTextHi,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isLive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // F3b: the second line is the score while a game is on (or reading
            // Final after it), and the reminder marker otherwise - the two
            // never share it.
            val secondLine = score?.line ?: "REMINDER SET".takeIf { reminderSet }
            if (secondLine != null) {
                Text(
                    text = secondLine,
                    color = if (score != null && !score.live) KBTextLo else KBAccent,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * The top band (F13/F14, round 3): the preview in the left corner, the focused
 * program's block beside it.
 *
 * The band is chrome above both columns, so nothing is drawn under it and no
 * cell moves when it changes (F13a). Its height is the preview's state, not the
 * content's: 135dp - exactly the preview's 16:9 box - while the preview is
 * drawn, and the block's own fixed text height when it is not (F14b/T8).
 */
@Composable
private fun GridTopBand(
    previewEnabled: Boolean,
    previewChannel: IptvChannel?,
    item: IptvChannelWithEpg?,
    cell: GridCell?,
    nowMs: Long,
    hasCatchup: Boolean,
) {
    // F13c: no last-played channel means no preview at all - it never autoplays
    // a channel nobody chose.
    val channel = previewChannel?.takeIf { previewEnabled }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (channel != null) GRID_BAND_WITH_PREVIEW else GRID_BAND_TEXT_HEIGHT),
        shape = KBShapePanel,
        colors = SurfaceDefaults.colors(
            containerColor = KBSurface.copy(alpha = 0.92f),
            contentColor = KBTextHi
        )
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (channel != null) {
                GridPreview(
                    channel = channel,
                    modifier = Modifier
                        .width(GRID_PREVIEW_WIDTH)
                        .height(GRID_PREVIEW_HEIGHT)
                )
            }
            GridDetailBlock(
                item = item,
                cell = cell,
                nowMs = nowMs,
                hasCatchup = hasCatchup,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

/**
 * F13: the last channel watched, playing in the band's left corner - with
 * audio, because hearing it while browsing is the point - and never following
 * focus (F13b: re-tuning on every arrow would rebuffer constantly).
 *
 * It is a PREVIEW, not a player (F13e): the channel's own stream URL and
 * headers, subtitles off the way live TV starts them, no controls, no seek. It
 * is display-only: nothing here is focusable, so the D-pad chain is unchanged
 * (F13f).
 */
@Composable
private fun GridPreview(channel: IptvChannel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember(channel.streamUrl) { mutableStateOf(channel.streamUrl.isBlank()) }

    // F13g: created when the grid opens, released when it closes, when the
    // toggle turns it off and when full-screen playback starts - the guide
    // leaves the composition for all three, which is the onDispose below.
    val previewPlayer = remember(channel.streamUrl) {
        if (channel.streamUrl.isBlank()) {
            null
        } else {
            runCatching {
                val dataSource = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .apply {
                        if (channel.headers.isNotEmpty()) {
                            setDefaultRequestProperties(channel.headers)
                        }
                    }
                ExoPlayer.Builder(context.applicationContext)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
                    .build()
            }.getOrNull()
        }
    }

    // F13h: one attempt per grid open. A dead feed leaves the still in place -
    // no toast, no retry loop - and the player is stopped so it is not
    // reconnecting behind the still.
    DisposableEffect(previewPlayer, channel.streamUrl) {
        val player = previewPlayer ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                runCatching { player.stop() }
            }
        }
        player.addListener(listener)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.setMediaItem(MediaItem.fromUri(channel.streamUrl))
        player.prepare()
        player.playWhenReady = true
        player.volume = 1f
        onDispose {
            runCatching {
                player.removeListener(listener)
                player.release()
            }
        }
    }

    Box(
        modifier = modifier
            .clip(KBShapeCard)
            .background(KBVoid),
        contentAlignment = Alignment.Center
    ) {
        if (!failed && previewPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        player = previewPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // The channel's own still - logo over name - so a dead feed reads
            // as "this channel", not as a black box.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                val logoUrl = channel.logoUrl
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(KBShapeChip)
                        .background(KBVoid.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!logoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = logoUrl,
                            contentDescription = channel.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(6.dp)
                        )
                    } else {
                        Text(
                            text = channel.displayName
                                .split(" ")
                                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                                .take(2)
                                .joinToString(""),
                            color = KBTextLo,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = channel.displayName.ifBlank { "Live Channel" },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * F14: the focused program, in a block at the top - title, meta line,
 * description and, while the program is live, its elapsed progress.
 *
 * Every element has a fixed number of lines (two, two, two), so the block is
 * the same height for a one-line description as for a three-line one and the
 * grid under it never jumps (F14b). The rules are the removed strip's own
 * (`focusedItem` + `focusedCell`, so a gap cell shows its row's live program,
 * F14c) and there is no rating badge: our guide data carries no rating and a
 * placeholder would say more than the data does.
 */
@Composable
private fun GridDetailBlock(
    item: IptvChannelWithEpg?,
    cell: GridCell?,
    nowMs: Long,
    hasCatchup: Boolean,
    modifier: Modifier = Modifier,
) {
    val channelName = item?.channel?.displayName?.ifBlank { "Live Channel" } ?: "Live Channel"
    val isLive = cell != null && !cell.isGap &&
        nowMs >= cell.startUtcMillis && nowMs < cell.endUtcMillis

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = when {
                item == null -> "Choose a channel to see its programs."
                cell == null -> channelName
                else -> cell.title ?: "No program data"
            },
            color = if (item == null || cell == null) KBTextLo else KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // F14b: two lines of room, always.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = when {
                cell == null -> "Move right into the grid to pick a program."
                else -> gridDetailMeta(channelName, cell, nowMs)
            },
            color = if (cell?.isGap == true || cell == null) KBTextLo else KBAccent,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            text = when {
                item == null -> "Its programs and times are drawn across the grid."
                cell == null -> "Then its title, time and description land here."
                cell.isGap -> "Nothing scheduled in this span."
                !cell.description.isNullOrBlank() -> cell.description
                nowMs < cell.startUtcMillis -> "Press OK to set a reminder."
                nowMs >= cell.endUtcMillis && hasCatchup -> "Press OK for catch-up."
                nowMs >= cell.endUtcMillis -> "Ended · this channel has no catch-up."
                else -> "Press OK to watch."
            },
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (isLive && cell != null) {
            // F14a: the live program's elapsed fraction. A future program says
            // "Starts in ..." on the meta line instead - a bar that never
            // moves would be a lie - and a past one gets no bar at all.
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(KBShapeChip)
                    .background(KBSurfaceRaised)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(liveFillFraction(cell, nowMs))
                        .fillMaxHeight()
                        .background(KBAccent)
                )
            }
        }
    }
}
