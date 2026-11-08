package com.kennyb1201.kbstream.ui.mobile

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.notifications.ReminderRules
import com.kennyb1201.kbstream.ui.iptv.GridCell
import com.kennyb1201.kbstream.ui.iptv.GridCellScore
import com.kennyb1201.kbstream.ui.iptv.GridProgram
import com.kennyb1201.kbstream.ui.iptv.GridWindow
import com.kennyb1201.kbstream.ui.iptv.GuideGrid
import com.kennyb1201.kbstream.ui.iptv.GuideRowGuideState
import com.kennyb1201.kbstream.ui.iptv.GuideScore
import com.kennyb1201.kbstream.ui.iptv.GRID_SCALE_DP_PER_MIN
import com.kennyb1201.kbstream.ui.iptv.GRID_SPAN_HOURS
import com.kennyb1201.kbstream.ui.iptv.autoFollowWindow
import com.kennyb1201.kbstream.ui.iptv.guideRowGuideState
import com.kennyb1201.kbstream.ui.iptv.cellWidthDp
import com.kennyb1201.kbstream.ui.iptv.gridCellHeadline
import com.kennyb1201.kbstream.ui.iptv.gridContentWidthDp
import com.kennyb1201.kbstream.ui.iptv.hourMarks
import com.kennyb1201.kbstream.ui.iptv.scoreFor
import com.kennyb1201.kbstream.ui.iptv.liveFillFraction
import com.kennyb1201.kbstream.ui.iptv.rowCells
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * The phone's timeline grid (spec F5a).
 *
 * The same data, the same six-hour window, the same cell widths and the same
 * detail strip as the TV grid - and none of the TV focus machinery: on a phone
 * the axis is dragged with a finger and a cell is tapped, so every D-pad rule in
 * §3 is replaced by "the row scrolls sideways". Keeping it in its own tv-free
 * file is the Phase-2 split's rule, and it is what stops `FocusRequester` maps
 * leaking into the touch path.
 */
@Composable
internal fun GuideGridMobileView(
    items: List<IptvChannelWithEpg>,
    programsByChannel: Map<String, List<GridProgram>>,
    resolvedGuideIds: Set<String>,
    loadingGuideIds: Set<String>,
    reminderKeys: Set<String>,
    /**
     * The live/finished games the cells may show a score for (F3m-b): the same
     * list the TV grid is handed, resolved once in the ViewModel by the hub's
     * matcher. Empty is the ordinary case and draws the reminder line as before.
     */
    scores: List<GuideScore> = emptyList(),
    nowMs: Long,
    /**
     * The guide's hard right edge (F1a): the end of the newest program it has
     * imported, from the same `guideScheduleEndUtcMillis` read the TV grid is
     * handed. Null when the guide holds none - no pin to apply, which is the TV's
     * answer too.
     */
    scheduleEndMs: Long?,
    onPlay: (IptvChannelWithEpg) -> Unit,
    onToggleReminder: (IptvChannelWithEpg, GridCell) -> Unit,
    onCatchup: ((IptvChannelWithEpg, GridCell) -> Unit)?,
    onRequestWindow: (List<String>, Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // F1a: one fixed scale, the same 8dp a minute the three-hour window drew at
    // - the span grew, the cells did not shrink. Every width below reads THIS
    // value, so the hour marks, the cells and the now-line cannot drift apart.
    val pxPerMin = GRID_SCALE_DP_PER_MIN

    // The window opens at now and then stays put until the clock leaves it
    // (F1b). It used to re-open on the hour, which moved the axis whether the
    // viewer had browsed away or not - a viewer scrolled right to tonight's games
    // was yanked forward at the top of the hour. Nothing moves the axis now
    // except the auto-follow below.
    var window by remember { mutableStateOf(GridWindow.opening(nowMs)) }

    val timeScroll = rememberScrollState()
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<Pair<IptvChannelWithEpg, GridCell>?>(null) }

    // F1a/F2a: the TV grid's follow, on this screen's own 30s tick - the SAME
    // [autoFollowWindow] rule, not a mobile fork of it. Only a window the clock
    // has just left follows, so a viewer paged forward is never yanked back and
    // one who has been away for an hour is not dragged forward; the move is one
    // page at the fixed span, and the guide's schedule end still stops the
    // axis (F1c/F1d).
    //
    // F2a: the scroll reset is this file's half of the TV's focus re-anchor
    // (`focusLiveOnNextWindow`). The window and the viewport move together, so
    // the now-line is on screen after a follow instead of the axis sliding
    // underneath a viewport that kept its pixel offset. It happens ONLY here - an
    // ordinary drag is never undone (F2b).
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
        Log.d(TAG, "follow window ${window.startUtcMillis} -> ${next.startUtcMillis}")
        window = next
        timeScroll.scrollTo(0)
    }

    val rows = remember(items, programsByChannel, window) {
        items.map { item ->
            item to rowCells(
                channelKey = item.channel.id,
                programs = programsByChannel[item.channel.id].orEmpty(),
                window = window,
            )
        }
    }

    // F3d: a window change re-clips every cell, so the strip's copy of the tapped program
    // is re-resolved in the new window by the air time it covered - kept while
    // that time is still on the axis, dropped when it is not.
    LaunchedEffect(rows) {
        val picked = selected ?: return@LaunchedEffect
        val mid = (picked.second.startUtcMillis + picked.second.endUtcMillis) / 2
        selected = rows.firstOrNull { it.first.channel.id == picked.first.channel.id }
            ?.second
            ?.firstOrNull { mid in it.startUtcMillis until it.endUtcMillis }
            ?.let { cell -> picked.first to cell }
    }

    // The rows this view keeps loaded: what is on screen plus a page of lookahead,
    // re-asked every time a new row reaches the top (F1a). A phone viewport is a
    // handful of rows tall, so a fixed slice of the group meant the rows past it
    // were never requested at all - the group scrolled into empty cells and stayed
    // there. Keying on the first visible index is the TV grid's shape; the
    // ViewModel's already-read set makes a scroll-back free (F1b).
    LaunchedEffect(rows, window, listState.firstVisibleItemIndex) {
        if (rows.isEmpty()) return@LaunchedEffect
        val first = listState.firstVisibleItemIndex
        val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val end = (first + visible + GuideGrid.LOOKAHEAD_ROWS).coerceAtMost(rows.size)
        val ids = rows.subList(first, end).map { it.first.channel.id }
        if (ids.isNotEmpty()) onRequestWindow(ids, window.startUtcMillis, window.endUtcMillis)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().height(26.dp)) {
            Spacer(modifier = Modifier.width(CHANNEL_CELL_WIDTH))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                Row(modifier = Modifier.horizontalScroll(timeScroll).height(26.dp)) {
                    hourMarks(window).forEach { mark ->
                        Box(
                            modifier = Modifier.width(
                                (GuideGrid.MINUTES_PER_HOUR * pxPerMin).dp
                            )
                        ) {
                            Text(
                                text = mark.dayLabel?.let { "$it · ${mark.label}" } ?: mark.label,
                                color = if (mark.dayLabel != null) KBAccent else KBTextLo,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(items = rows, key = { row -> row.first.channel.id }) { row ->
                val item = row.first
                val cells = row.second
                Row(modifier = Modifier.fillMaxWidth().height(MOBILE_ROW_HEIGHT)) {
                    MobileChannelCell(
                        item = item,
                        guideState = guideRowGuideState(
                            channelId = item.channel.id,
                            resolvedGuideIds = resolvedGuideIds,
                            programsByChannel = programsByChannel,
                            loadingIds = loadingGuideIds,
                        ),
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(timeScroll)
                                .height(MOBILE_ROW_HEIGHT)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(gridContentWidthDp(window).dp)
                                    .height(MOBILE_ROW_HEIGHT)
                            ) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    cells.forEach { cell ->
                                        // F3m-a: the SAME single lookup the TV
                                        // cell uses - no second score pipeline,
                                        // no second match in this file.
                                        val score = scoreFor(
                                            channelId = item.channel.id,
                                            cell = cell,
                                            nowUtcMillis = nowMs,
                                            scores = scores,
                                        )
                                        MobileProgramCell(
                                            cell = cell,
                                            pxPerMin = pxPerMin,
                                            isLive = !cell.isGap &&
                                                nowMs >= cell.startUtcMillis &&
                                                nowMs < cell.endUtcMillis,
                                            nowMs = nowMs,
                                            score = score,
                                            // One slot, two occupants (F3m-c): the
                                            // score wins it while the game is on
                                            // and the reminder marker returns
                                            // after it goes final. The reminder
                                            // itself stays set either way.
                                            reminderSet = score == null && !cell.isGap &&
                                                reminderKeys.contains(
                                                    ReminderRules.reminderKey(
                                                        item.channel.id,
                                                        cell.startUtcMillis
                                                    )
                                                ),
                                            onTap = {
                                                if (cell.isGap) {
                                                    selected = item to cell
                                                } else {
                                                    selected = item to cell
                                                    when {
                                                        cell.startUtcMillis > nowMs ->
                                                            onToggleReminder(item, cell)
                                                        nowMs < cell.endUtcMillis -> onPlay(item)
                                                        else -> onCatchup?.invoke(item, cell)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                                if (nowMs in window.startUtcMillis until window.endUtcMillis) {
                                    Box(
                                        modifier = Modifier
                                            .offset(
                                                x = ((nowMs - window.startUtcMillis) / 60_000L *
                                                    pxPerMin).toInt().dp
                                            )
                                            .width(2.dp)
                                            .fillMaxHeight()
                                            .background(KBAccent.copy(alpha = 0.9f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // F2a: the strip is the TAPPED program's detail, so nothing selected
        // means no strip at all - no hint text, no placeholder, and the row
        // list keeps the height. The TV removed its bottom strip entirely in
        // round-2 F10; the phone kept one because it is the only place a tapped
        // program's description shows, which is still true when one IS tapped.
        selected?.let { picked ->
            MobileGridDetailStrip(selected = picked, nowMs = nowMs)
        }
    }
}

private const val TAG = "GUIDE_GRID"

private val CHANNEL_CELL_WIDTH = 128.dp
private val MOBILE_ROW_HEIGHT = 58.dp

@Composable
private fun MobileChannelCell(
    item: IptvChannelWithEpg,
    guideState: GuideRowGuideState,
) {
    Row(
        modifier = Modifier
            .width(CHANNEL_CELL_WIDTH)
            .fillMaxHeight()
            .padding(end = 4.dp)
            .clip(KBShapeChip)
            .background(KBSurface)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val logoUrl = item.channel.logoUrl ?: item.epgChannel?.iconUrl
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(KBShapeChip)
                .background(KBVoid.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            if (!logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = item.channel.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(3.dp)
                )
            } else {
                // Name only, never a broken-image box (F2f).
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
        Column(modifier = Modifier.padding(start = 6.dp)) {
            Text(
                text = item.channel.displayName.ifBlank { "Live Channel" },
                color = KBTextHi,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The number is the channel's own, so it wins; the guide line under it
            // is only ever about the guide (F3b). "No guide data" is a result, and
            // only a finished read may print it (F3c) - that is
            // [GuideRowGuideState]'s job, not the cell's.
            val number = item.channel.tvgChno?.trim()?.takeIf { it.isNotBlank() }
            Text(
                text = when {
                    number != null -> number
                    guideState == GuideRowGuideState.LOADING -> "Loading guide…"
                    guideState == GuideRowGuideState.NO_GUIDE_DATA -> "No guide data"
                    else -> " "
                },
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MobileProgramCell(
    cell: GridCell,
    /** The grid's fixed scale (F1a); the cell is as wide as its air time at it. */
    pxPerMin: Int,
    isLive: Boolean,
    nowMs: Long,
    /** The game this cell is carrying, if any (F3m-c); null draws the reminder. */
    score: GridCellScore? = null,
    reminderSet: Boolean,
    onTap: () -> Unit,
) {
    val fill = if (isLive) liveFillFraction(cell, nowMs) else 0f
    Box(
        modifier = Modifier
            .width(cellWidthDp(cell.startUtcMillis, cell.endUtcMillis, pxPerMin = pxPerMin).dp)
            .fillMaxHeight()
            .padding(end = 1.dp)
            .clip(KBShapeCard)
            .background(
                if (cell.isGap) {
                    KBVoid.copy(alpha = 0.35f)
                } else if (isLive) {
                    KBAccent.copy(alpha = 0.18f + 0.22f * fill)
                } else {
                    KBSurface
                }
            )
            .border(
                width = 1.dp,
                color = if (isLive) KBAccent.copy(alpha = 0.4f) else KBSurfaceRaised,
                shape = KBShapeCard
            )
            .clickable { onTap() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Column {
            Text(
                text = cell.title.orEmpty(),
                color = if (cell.isGap) KBTextLo else KBTextHi,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isLive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // F3m-c: the second line is the score while a game is on (or reading
            // Final after it), and the reminder marker otherwise. The title's two
            // lines plus this one fit the 58dp row (F3m-d).
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

@Composable
private fun MobileGridDetailStrip(
    /** The tapped program; non-null by construction - see the call site (F2a). */
    selected: Pair<IptvChannelWithEpg, GridCell>,
    nowMs: Long,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(KBShapeCard)
            .background(KBSurface)
            .border(1.dp, KBSurfaceRaised, KBShapeCard)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val item = selected.first
        val cell = selected.second
        Column {
            Text(
                text = if (cell.isGap) "No program data" else cell.title.orEmpty(),
                color = if (cell.isGap) KBTextLo else KBTextHi,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!cell.isGap) {
                Text(
                    text = "${item.channel.displayName} · ${gridCellHeadline(cell, nowMs)}",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
                cell.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
