package com.kennyb1201.kbstream.ui.mobile

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
import com.kennyb1201.kbstream.ui.iptv.GridProgram
import com.kennyb1201.kbstream.ui.iptv.GridWindow
import com.kennyb1201.kbstream.ui.iptv.GuideGrid
import com.kennyb1201.kbstream.ui.iptv.cellWidthDp
import com.kennyb1201.kbstream.ui.iptv.gridCellHeadline
import com.kennyb1201.kbstream.ui.iptv.hourMarks
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
 * The same data, the same 6-hour window, the same cell widths and the same
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
    reminderKeys: Set<String>,
    nowMs: Long,
    onPlay: (IptvChannelWithEpg) -> Unit,
    onToggleReminder: (IptvChannelWithEpg, GridCell) -> Unit,
    onCatchup: ((IptvChannelWithEpg, GridCell) -> Unit)?,
    onRequestWindow: (List<String>, Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = remember(nowMs / 3_600_000L) { GridWindow.opening(nowMs) }
    val timeScroll = rememberScrollState()
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<Pair<IptvChannelWithEpg, GridCell>?>(null) }

    val rows = remember(items, programsByChannel, window) {
        items.map { item ->
            item to rowCells(
                channelKey = item.channel.id,
                programs = programsByChannel[item.channel.id].orEmpty(),
                window = window,
            )
        }
    }

    LaunchedEffect(rows, window) {
        val ids = items.take(GuideGrid.LOOKAHEAD_ROWS * 2).map { it.channel.id }
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
                                (GuideGrid.MINUTES_PER_HOUR * GuideGrid.PX_PER_MIN).dp
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
                        guidePending = item.channel.id !in resolvedGuideIds &&
                            programsByChannel[item.channel.id].isNullOrEmpty(),
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(timeScroll)
                                .height(MOBILE_ROW_HEIGHT)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(CONTENT_WIDTH)
                                    .height(MOBILE_ROW_HEIGHT)
                            ) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    cells.forEach { cell ->
                                        MobileProgramCell(
                                            cell = cell,
                                            isLive = !cell.isGap &&
                                                nowMs >= cell.startUtcMillis &&
                                                nowMs < cell.endUtcMillis,
                                            nowMs = nowMs,
                                            reminderSet = !cell.isGap && reminderKeys.contains(
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
                                                    GuideGrid.PX_PER_MIN).toInt().dp
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

        MobileGridDetailStrip(selected = selected, nowMs = nowMs)
    }
}

private val CHANNEL_CELL_WIDTH = 128.dp
private val MOBILE_ROW_HEIGHT = 58.dp
private val CONTENT_WIDTH =
    (GuideGrid.WINDOW_HOURS * GuideGrid.MINUTES_PER_HOUR * GuideGrid.PX_PER_MIN).dp

@Composable
private fun MobileChannelCell(
    item: IptvChannelWithEpg,
    guidePending: Boolean,
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
            val number = item.channel.tvgChno?.trim()?.takeIf { it.isNotBlank() }
            Text(
                text = when {
                    number != null -> number
                    guidePending -> "Loading guide…"
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
    isLive: Boolean,
    nowMs: Long,
    reminderSet: Boolean,
    onTap: () -> Unit,
) {
    val fill = if (isLive) liveFillFraction(cell, nowMs) else 0f
    Box(
        modifier = Modifier
            .width(cellWidthDp(cell.startUtcMillis, cell.endUtcMillis).dp)
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

@Composable
private fun MobileGridDetailStrip(
    selected: Pair<IptvChannelWithEpg, GridCell>?,
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
        val item = selected?.first
        val cell = selected?.second
        Column {
            Text(
                text = when {
                    item == null -> "Tap a program to watch, remind or catch up."
                    cell == null -> ""
                    cell.isGap -> "No program data"
                    else -> cell.title.orEmpty()
                },
                color = if (item == null || cell?.isGap != false) KBTextLo else KBTextHi,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item != null && cell != null && !cell.isGap) {
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
