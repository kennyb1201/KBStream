package com.kennyb1201.kbstream.ui.iptv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import coil.compose.AsyncImage
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

private val GRID_ROW_HEIGHT: Dp = 62.dp

/** The full window's width in dp - the scrolled content of every row. */
private val GRID_CONTENT_WIDTH: Dp =
    (GuideGrid.WINDOW_HOURS * GuideGrid.MINUTES_PER_HOUR * GuideGrid.PX_PER_MIN).dp

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

    var window by remember { mutableStateOf(GridWindow.opening(nowMs)) }
    var focusedRow by remember { mutableStateOf(0) }
    var focusedCellId by remember { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableStateOf<GridPendingFocus?>(null) }
    var focusLiveOnNextWindow by remember { mutableStateOf(false) }

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

    // Jump to now (F2d): reset the axis to the current hour and re-anchor focus on
    // the live program of the focused channel.
    LaunchedEffect(jumpToNowSignal) {
        if (jumpToNowSignal <= 0) return@LaunchedEffect
        window = GridWindow.opening(System.currentTimeMillis())
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
        if (window.startUtcMillis >= end) window = GridWindow.opening(System.currentTimeMillis())
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

    fun focusCell(rowIndex: Int, cell: GridCell) {
        pendingFocus = GridPendingFocus(rowIndex, cell.id, channelFocus = false)
    }

    fun focusChannel(rowIndex: Int) {
        pendingFocus = GridPendingFocus(rowIndex, cellId = null, channelFocus = true)
    }

    fun activateCell(item: IptvChannelWithEpg, cell: GridCell) {
        // A gap has nothing to play, remind or catch up (F2g); the strip has
        // already said so.
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
                    floorStartUtcMillis = GridWindow.opening(nowMs).startUtcMillis,
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
            val index = groups.indexOf(selectedGroup).coerceAtLeast(0)
            sidebarRequesters[groups.getOrNull(index)]?.let { requester ->
                runCatching { requester.requestFocus() }
            }
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
            // Back into the grid on the row the viewer was on (F3i).
            if (rows.isNotEmpty()) focusChannel(focusedRow.coerceIn(0, rows.lastIndex))
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

    Row(modifier = modifier.fillMaxSize()) {
        GroupsSidebar(
            groups = groups,
            selectedGroup = selectedGroup,
            groupCount = groupCount,
            state = sidebarState,
            requesters = sidebarRequesters,
            onKey = ::onSidebarKey,
        )

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // The sticky time header: the same shared scroll state as every row,
            // so its labels cannot drift from the cells under them (F2e).
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
                                FocusRequester().also { channelRequesters[row.channelKey] = it }
                            },
                            onKey = { key -> onChannelKey(index, key) },
                            onFocus = {
                                focusedRow = index
                                focusedCellId = null
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
                            },
                            onKey = { cell, key -> onCellKey(index, cell, key) },
                        )
                    }
                }
            }

            GridDetailStrip(
                item = focusedItem,
                cell = focusedCell,
                nowMs = nowMs,
                hasCatchup = focusedItem?.let {
                    it.channel.catchupSource != null || it.channel.catchup != null
                } == true,
            )
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

/** The grid keys rows and cells by the playlist channel key the guide uses. */
private fun gridChannelKey(item: IptvChannelWithEpg): String =
    item.channel.id.ifBlank { item.channel.streamUrl }

/**
 * The groups sidebar (F2k): always visible in grid view, 220dp, name + visible
 * count, selected row highlighted, chain program -> channel -> sidebar -> channel.
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
                        .onFocusChanged { focused = it.isFocused }
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
                modifier = Modifier
                    .size(34.dp)
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
                    style = MaterialTheme.typography.labelLarge,
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
            Box(modifier = Modifier.width(GRID_CONTENT_WIDTH).height(GRID_ROW_HEIGHT)) {
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
            .padding(horizontal = 8.dp, vertical = 6.dp)
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

/** The focused program's title, channel, time and description (F2i). */
@Composable
private fun GridDetailStrip(
    item: IptvChannelWithEpg?,
    cell: GridCell?,
    nowMs: Long,
    hasCatchup: Boolean,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .height(112.dp),
        shape = KBShapePanel,
        colors = SurfaceDefaults.colors(
            containerColor = KBSurface.copy(alpha = 0.92f),
            contentColor = KBTextHi
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when {
                item == null -> Text(
                    text = "Choose a channel to see its programs.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
                cell == null -> Text(
                    text = "${item.channel.displayName} · move right into the grid to pick a program.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> {
                    Text(
                        text = cell.title ?: "No program data",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val channelName = item.channel.displayName.ifBlank { "Live Channel" }
                    Text(
                        text = "$channelName · ${gridCellHeadline(cell, nowMs)}",
                        color = if (cell.isGap) KBTextLo else KBAccent,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    val hint = when {
                        cell.isGap -> "Nothing scheduled in this span."
                        nowMs < cell.startUtcMillis -> "Press OK to set a reminder."
                        nowMs >= cell.endUtcMillis && hasCatchup -> "Press OK for catch-up."
                        nowMs >= cell.endUtcMillis -> "Ended · this channel has no catch-up."
                        else -> "Press OK to watch."
                    }
                    Text(
                        text = hint,
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    cell.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
