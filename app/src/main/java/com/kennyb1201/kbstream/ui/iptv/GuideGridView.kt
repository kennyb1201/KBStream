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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

// F12a: 48dp, TiviMate's own row, down from 62. The program cell is 20 + 16 +
// 8 = 44dp of content with its 4dp padding, so the optional second line
// (reminder, score) still fits and no row needs a height exception.
private val GRID_ROW_HEIGHT: Dp = 48.dp

// F13a/F14b: the top band's two heights. 180dp is exactly the preview's own
// 16:9 box, so the band adds no padding around it; the text height is the
// block's fixed height when the preview is off, which is what gives that space
// back to the rows without the band resizing as focus moves.
private val GRID_BAND_WITH_PREVIEW: Dp = 180.dp
private val GRID_BAND_TEXT_HEIGHT: Dp = 128.dp
private val GRID_PREVIEW_WIDTH: Dp = 320.dp
private val GRID_PREVIEW_HEIGHT: Dp = 180.dp

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
    /** Bumped by the header's Jump to now; 0 on first composition. */
    jumpToNowSignal: Int,
    /**
     * Bumped by the header's Down, so focus comes back to where it left (F1e).
     * The screen owns the header, this grid owns the focus homes.
     */
    returnFocusSignal: Int,
    /**
     * Whether the groups sidebar is showing. Owned by the screen because the
     * header names the current group while it is hidden (F1f).
     */
    sidebarVisible: Boolean,
    onSidebarVisibleChange: (Boolean) -> Unit,
    /**
     * The zoom level: how many hours of air time the window draws (F12e). The
     * screen owns it because the header's zoom button sets it and persists it.
     */
    windowHours: Int,
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

    var window by remember { mutableStateOf(GridWindow.opening(nowMs, windowHours)) }
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

    // F12e: a zoom change keeps the axis where the viewer left it and only
    // changes how much of it is drawn.
    LaunchedEffect(windowHours) {
        val next = window.withHours(windowHours)
        if (next != window) window = next
    }

    // Jump to now (F2d): reset the axis to the current hour and re-anchor focus on
    // the live program of the focused channel.
    LaunchedEffect(jumpToNowSignal) {
        if (jumpToNowSignal <= 0) return@LaunchedEffect
        window = GridWindow.opening(System.currentTimeMillis(), windowHours)
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
            window = GridWindow.opening(System.currentTimeMillis(), windowHours)
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
        if (cell != null) {
            val offsetDp = row.cells.takeWhile { it.id != cell.id }
                .sumOf { cellWidthDp(it.startUtcMillis, it.endUtcMillis) }
            val offsetPx = with(density) { offsetDp.dp.toPx() }.toInt()
            if (!reducedMotion) {
                timeScroll.animateScrollTo(offsetPx.coerceIn(0, timeScroll.maxValue))
            } else {
                timeScroll.scrollTo(offsetPx.coerceIn(0, timeScroll.maxValue))
            }
        }

        var attempts = 0
        while (attempts < 8) {
            val requester = when {
                target.channelFocus -> channelRequesters[row.channelKey]
                cell != null -> cellRequesters[cell.id]
                else -> null
            }
            if (requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)) {
                focusedRow = target.rowIndex
                focusedCellId = cell?.id
                return@LaunchedEffect
            }
            withFrameNanos { }
            attempts++
        }
    }

    // F1b: the sidebar has to be composed before its requester can take focus,
    // so LEFT from a channel posts a target and this effect lands it - the same
    // retry shape as the grid's own pendingFocus, and it says so when it gives
    // up (F2c: a handoff that dies silently is the whole bug).
    LaunchedEffect(pendingSidebarFocus, sidebarVisible, groups, selectedGroup) {
        if (!pendingSidebarFocus || !sidebarVisible) return@LaunchedEffect
        val label = groups.getOrNull(groups.indexOf(selectedGroup).coerceAtLeast(0))
        var landed = false
        var attempts = 0
        while (attempts < 8) {
            val requester = label?.let { sidebarRequesters[it] }
            if (requester != null && runCatching { requester.requestFocus() }.getOrDefault(false)) {
                landed = true
                break
            }
            withFrameNanos { }
            attempts++
        }
        pendingSidebarFocus = false
        if (landed) focusHome = GridFocusHome.SIDEBAR
        Log.i(
            "GUIDE",
            "grid handoff channel->sidebar group=$label landed=$landed attempts=$attempts"
        )
    }

    // F1e: Down out of the header returns to the last focus home - the sidebar
    // row while it is showing, otherwise the channel or cell the viewer was on
    // (a hidden sidebar stays hidden). The handoff remembers which of the two
    // grid homes it was, instead of always assuming the sidebar.
    LaunchedEffect(returnFocusSignal, sidebarVisible) {
        if (returnFocusSignal <= 0) return@LaunchedEffect
        if (sidebarVisible) {
            pendingSidebarFocus = true
            return@LaunchedEffect
        }
        val rowIndex = focusedRow.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
        val cell = if (focusHome == GridFocusHome.CELL) {
            rows.getOrNull(rowIndex)?.cells?.firstOrNull { it.id == focusedCellId }
        } else {
            null
        }
        pendingFocus = GridPendingFocus(rowIndex, cell?.id, channelFocus = cell == null)
    }

    fun focusCell(rowIndex: Int, cell: GridCell) {
        pendingFocus = GridPendingFocus(rowIndex, cell.id, channelFocus = false)
    }

    fun focusChannel(rowIndex: Int) {
        pendingFocus = GridPendingFocus(rowIndex, cellId = null, channelFocus = true)
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
        if (!direct) focusChannel(rowIndex)
        Log.i(
            "GUIDE",
            "grid handoff sidebar->channel row=$rowIndex channel=${row.channelKey} " +
                "requester=${requester != null} direct=$direct fallback=${!direct}"
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

    /** One arrow press, answered from the loaded lists (F3a, F3b, F3d, F3g). */
    fun onCellKey(rowIndex: Int, cell: GridCell, key: Key): Boolean {
        val row = rows.getOrNull(rowIndex) ?: return false
        when (key) {
            Key.DirectionLeft -> {
                val target = horizontalTarget(row.cells, cell.id, -1)
                if (target != null) focusCell(rowIndex, target) else focusChannel(rowIndex)
                return true
            }
            Key.DirectionRight -> {
                val target = horizontalTarget(row.cells, cell.id, 1)
                if (target != null) {
                    focusCell(rowIndex, target)
                    return true
                }
                // Past the last cell: page the window and land on the first
                // program of the new one in this row (F3d).
                val next = window.paged(
                    direction = 1,
                    floorStartUtcMillis = GridWindow.opening(nowMs, windowHours).startUtcMillis,
                    maxEndUtcMillis = scheduleEndMs,
                )
                if (next == window) return true
                window = next
                val first = rows[rowIndex].cells.firstOrNull { !it.isGap }
                if (first != null) focusCell(rowIndex, first)
                return true
            }
            Key.DirectionUp, Key.DirectionDown -> {
                val direction = if (key == Key.DirectionUp) -1 else 1
                val target = verticalTarget(rows.map { it.cells }, rowIndex, cell, direction)
                if (target != null) {
                    focusCell(target.rowIndex, target.cell)
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
            // Direct first (it is composed the moment the state flips), pending
            // as the fallback for the frames before it is.
            onSidebarVisibleChange(true)
            val index = groups.indexOf(selectedGroup).coerceAtLeast(0)
            val requester = sidebarRequesters[groups.getOrNull(index)]
            val direct = requester != null &&
                runCatching { requester.requestFocus() }.getOrDefault(false)
            if (direct) {
                focusHome = GridFocusHome.SIDEBAR
            } else {
                pendingSidebarFocus = true
            }
            Log.i(
                "GUIDE",
                "grid handoff channel->sidebar row=$rowIndex group=$selectedGroup " +
                    "requester=${requester != null} direct=$direct fallback=${!direct}"
            )
            true
        }
        Key.DirectionRight -> {
            val row = rows.getOrNull(rowIndex) ?: return true
            val first = row.cells.firstOrNull { !it.isGap } ?: return true
            focusCell(rowIndex, first)
            true
        }
        Key.DirectionUp -> {
            if (rowIndex == 0) {
                runCatching { headerFocusRequester.requestFocus() }
            } else {
                focusChannel(rowIndex - 1)
            }
            true
        }
        Key.DirectionDown -> {
            if (rowIndex < rows.lastIndex) focusChannel(rowIndex + 1)
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
    Column(modifier = modifier.fillMaxSize().padding(bottom = 12.dp)) {
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
                Row(modifier = Modifier.fillMaxWidth().height(30.dp)) {
                    Spacer(modifier = Modifier.width(GuideGrid.CHANNEL_COLUMN_WIDTH_DP.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Row(
                            modifier = Modifier.horizontalScroll(timeScroll).height(30.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            hourMarks(window).forEach { mark ->
                                Box(
                                    modifier = Modifier.width(
                                        (GuideGrid.MINUTES_PER_HOUR * GuideGrid.PX_PER_MIN).dp
                                    )
                                ) {
                                    Column {
                                        mark.dayLabel?.let { day ->
                                            Text(
                                                text = day,
                                                color = KBAccent,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            text = mark.label,
                                            color = KBTextLo,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    state = rowListState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(items = rows, key = { _, row -> row.channelKey }) { index, row ->
                        Row(modifier = Modifier.fillMaxWidth().height(GRID_ROW_HEIGHT)) {
                            ChannelCell(
                                row = row,
                                isFocusedRow = index == focusedRow,
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
                // F12b: 28dp, not 34 - the logo and the two text lines have to
                // fit 44dp of a 48dp row.
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
                    // F12b: labelMedium, so the name and the number still fit
                    // the 48dp row without the second line being clipped.
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isFocusedRow) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val number = item.channel.tvgChno?.trim()?.takeIf { it.isNotBlank() }
                Text(
                    text = when {
                        number != null -> number
                        guidePending -> "Loading guide…"
                        else -> item.channel.groupTitle?.trim()?.takeIf { it.isNotBlank() } ?: " "
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
    channelId: String,
    requesters: MutableMap<String, FocusRequester>,
    timeScroll: ScrollState,
    onCellFocus: (GridCell) -> Unit,
    onKey: (GridCell, Key) -> Boolean,
) {
    val nowOffsetDp = ((nowMs - window.startUtcMillis) / 60_000L * GuideGrid.PX_PER_MIN).toInt()
    val nowInWindow = nowMs in window.startUtcMillis until window.endUtcMillis

    Box(modifier = modifier.clipToBounds()) {
        Row(modifier = Modifier.horizontalScroll(timeScroll).height(GRID_ROW_HEIGHT)) {
            // The scrolled content is the WINDOW's width (F12e): a 3-hour
            // window is 720dp, a 12-hour one 2880dp, and the cells are laid
            // out at their real widths inside it either way.
            Box(
                modifier = Modifier
                    .width(gridContentWidthDp(window).dp)
                    .height(GRID_ROW_HEIGHT)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    row.cells.forEach { cell ->
                        key(cell.id) {
                            ProgramCell(
                                cell = cell,
                                isFocused = cell.id == focusedCellId,
                                isLive = !cell.isGap &&
                                    nowMs >= cell.startUtcMillis && nowMs < cell.endUtcMillis,
                                nowMs = nowMs,
                                reminderSet = !cell.isGap && reminderKeys.contains(
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
            if (reminderSet) {
                Text(
                    text = "REMINDER SET",
                    color = KBAccent,
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
 * content's: 180dp - exactly the preview's 16:9 box - while the preview is
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
