package com.kennyb1201.kbstream.ui.iptv

import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * The pure half of the timeline guide: how a grid cell is sized, which cell an
 * arrow press lands on, how the time window pages, and how saved group order is
 * applied.
 *
 * The list guide's rules were pulled out of the composable because they are
 * decisions with edge cases a viewer meets as bugs (see `GuideRules.kt`). The
 * grid multiplies that: focus over two axes across cells whose widths change
 * with every program is the one thing this screen can get badly wrong, and it
 * cannot be pinned at all from inside a composable. Everything here is
 * `internal`, Compose-free, and takes primitives or [GridCell]s, so
 * `GuideGridRulesTest` runs it on the JVM.
 *
 * The geometry constants are the spec's, not tunables invented here: 4dp per
 * minute (a 30-minute program is 120dp), a 56dp floor so a 5-minute short is
 * still a target, a 6-hour window paged 3 hours at a time, 24 programs per
 * channel per window read, and the 80-row page the ViewModel already caps a
 * guide request at.
 */

// The three pinned group labels. The phone guide has its own spellings
// (GUIDE_GROUP_* in MobileGuideGroups.kt); these are the same three values, and
// both derivations read them through [applyGroupOrder] so the two screens cannot
// disagree about which labels are pinned.
internal const val GRID_GROUP_ALL = "All"
internal const val GRID_GROUP_RECENT = "Recent"
internal const val GRID_GROUP_FAVORITES = "Favorites"

/** The three labels a saved order may never move. */
internal val GRID_PINNED_GROUPS = setOf(GRID_GROUP_ALL, GRID_GROUP_RECENT, GRID_GROUP_FAVORITES)

/** `Sat Nov 7` - the time header's day label, where the grid crosses midnight. */
private val GRID_DAY_LABEL: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

/**
 * How the guide is drawn: the list it has always been, or the timeline grid.
 *
 * The stored value is the pref's own spelling, so a build that ships the grid
 * and one that predates it read the same store without a migration: anything
 * unrecognised (including a missing key) is the list, which is what D1 requires -
 * the grid is opt-in and the default must not move under a daily viewer.
 */
internal enum class GuideViewMode(val prefValue: String) {
    LIST("list"),
    GRID("grid");

    companion object {
        fun fromPref(value: String?): GuideViewMode =
            entries.firstOrNull { it.prefValue == value } ?: LIST

        fun toggled(current: GuideViewMode): GuideViewMode =
            if (current == LIST) GRID else LIST
    }
}

internal object GuideGrid {

    /** The visible time window, in hours. */
    const val WINDOW_HOURS = 6

    /** How far one page of the time axis moves, in hours. */
    const val PAGE_HOURS = 3

    /** Width of one minute of air time. */
    const val PX_PER_MIN = 4

    /**
     * The narrowest a cell may render. A 5-minute program is 20dp at
     * [PX_PER_MIN] - too small to read, focus or hit - so it renders here and
     * clips its title; its real start/end still drive focus and the strip.
     */
    const val MIN_CELL_WIDTH_DP = 56

    /** The fixed groups sidebar, left of the channel column. */
    const val SIDEBAR_WIDTH_DP = 220

    /** The fixed channel column (logo + name + number). */
    const val CHANNEL_COLUMN_WIDTH_DP = 200

    /**
     * Programs per channel per window read.
     *
     * A 6-hour window of 30-minute programs is 12; 24 leaves room for the
     * short-form channels (news, music) whose schedules run 10-15 minutes
     * without letting the read grow.
     */
    const val PER_CHANNEL_LIMIT = 24

    /**
     * Rows loaded ahead of the viewport. The list's own cap
     * (`IptvViewModel.MAX_GUIDE_CHANNELS_PER_REQUEST`) sizes a request; this is
     * how many rows of lookahead the grid keeps loaded past the last visible
     * one, so a Down press does not land on a row that is still loading.
     */
    const val LOOKAHEAD_ROWS = 12

    const val MINUTES_PER_HOUR = 60

    /** One hour in milliseconds - the grid's own unit, used by paging. */
    const val HOUR_MS = 3_600_000L

    val WINDOW_MS: Long = WINDOW_HOURS * HOUR_MS

    val PAGE_MS: Long = PAGE_HOURS * HOUR_MS
}

/**
 * The span of time the grid is showing.
 *
 * Always hour-aligned: the grid opens at the top of the hour so the program in
 * progress is on screen with its start cell visible, and paging moves whole
 * hours. [opening] is the only constructor the screen uses; [paged] is the only
 * way the window moves.
 */
internal data class GridWindow(
    val startUtcMillis: Long,
    val endUtcMillis: Long,
) {

    val durationMs: Long get() = endUtcMillis - startUtcMillis

    /** Where [millis] sits in the window, in minutes from its left edge. */
    fun offsetMinutesOf(millis: Long): Int =
        ((millis - startUtcMillis) / 60_000L).toInt()

    fun contains(millis: Long): Boolean = millis in startUtcMillis until endUtcMillis

    /**
     * One page in [direction] (+1 forward, -1 back).
     *
     * [floorStartUtcMillis] is the window of `now` the axis may never go behind:
     * paging left at the now-window is a no-op, and a page that would straddle
     * it clamps to it instead of exposing time before it. [maxEndUtcMillis] is
     * the hard right edge (`maxProgramEndForSource`): a page that would start at
     * or past it is also a no-op, so the axis can never scroll into time the
     * guide has no programs for.
     */
    fun paged(
        direction: Int,
        floorStartUtcMillis: Long,
        maxEndUtcMillis: Long?,
    ): GridWindow {
        if (direction == 0) return this
        val nextStart = startUtcMillis + direction * GuideGrid.PAGE_MS
        if (direction < 0 && nextStart < floorStartUtcMillis) {
            return if (startUtcMillis > floorStartUtcMillis) {
                GridWindow(floorStartUtcMillis, floorStartUtcMillis + GuideGrid.WINDOW_MS)
            } else {
                this
            }
        }
        if (direction > 0 && maxEndUtcMillis != null && nextStart >= maxEndUtcMillis) return this
        return GridWindow(nextStart, nextStart + GuideGrid.WINDOW_MS)
    }

    companion object {
        /** The window the grid opens on: the current hour, six hours wide. */
        fun opening(nowUtcMillis: Long): GridWindow {
            val start = floorToHour(nowUtcMillis)
            return GridWindow(start, start + GuideGrid.WINDOW_MS)
        }
    }
}

/** The top of the hour [millis] falls in, in the device's zone. */
internal fun floorToHour(millis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * One program as the grid holds it: the row it belongs to and what a cell says.
 *
 * Deliberately not [EpgProgramRow] - the grid is keyed by the PLAYLIST channel
 * key (what the guide's rows and the zap lineup use), while the DAO row is keyed
 * by the guide's program channel key. Keeping the mapping out of the cell model
 * is what lets the focus rules be tested without a database.
 */
internal data class GridProgram(
    val channelKey: String,
    val title: String,
    val description: String?,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
)

/** [programs] as grid cells for [channelKey], in start order. */
internal fun gridProgramsOf(
    channelKey: String,
    programs: List<EpgProgramRow>,
): List<GridProgram> = programs
    .filter { it.endUtcMillis > it.startUtcMillis }
    .sortedBy { it.startUtcMillis }
    .map {
        GridProgram(
            channelKey = channelKey,
            title = it.title,
            description = it.description,
            startUtcMillis = it.startUtcMillis,
            endUtcMillis = it.endUtcMillis,
        )
    }

/**
 * One cell on screen: a program, or the empty span between two of them.
 *
 * Gaps are cells too (F2g): they occupy their real width, they are focusable,
 * and OK on one does nothing. That is what stops the row from silently
 * compressing a 20-minute hole into a butt joint - a viewer reading the grid's
 * time labels would otherwise see a program start 20 minutes before its stated
 * time.
 */
internal data class GridCell(
    val id: String,
    val channelKey: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val title: String? = null,
    val description: String? = null,
) {
    val isGap: Boolean get() = title == null

    val durationMs: Long get() = endUtcMillis - startUtcMillis

    /** The stable per-cell key the FocusRequester map is keyed by (F3e). */
    companion object {
        fun cellId(channelKey: String, startUtcMillis: Long, endUtcMillis: Long, gap: Boolean): String =
            if (gap) {
                "gap|$channelKey|$startUtcMillis|$endUtcMillis"
            } else {
                "cell|$channelKey|$startUtcMillis|$endUtcMillis"
            }
    }
}

/**
 * [programs] laid across [window] as cells, with the holes between them and
 * either side of them filled by gap cells.
 *
 * Programs are clipped to the window: a two-hour film that started before the
 * window opens renders from the left edge, at the width of the part that is
 * actually on screen, and keeps its real start for the detail strip and for
 * focus. A program entirely outside the window is dropped.
 */
internal fun rowCells(
    channelKey: String,
    programs: List<GridProgram>,
    window: GridWindow,
): List<GridCell> {
    val out = ArrayList<GridCell>(programs.size + 2)
    var cursor = window.startUtcMillis
    programs.forEach { program ->
        val start = program.startUtcMillis.coerceAtLeast(window.startUtcMillis)
        val end = program.endUtcMillis.coerceAtMost(window.endUtcMillis)
        if (end <= start) return@forEach
        if (start > cursor) {
            out += GridCell(
                id = GridCell.cellId(channelKey, cursor, start, gap = true),
                channelKey = channelKey,
                startUtcMillis = cursor,
                endUtcMillis = start,
            )
        }
        out += GridCell(
            id = GridCell.cellId(channelKey, program.startUtcMillis, program.endUtcMillis, gap = false),
            channelKey = channelKey,
            startUtcMillis = start,
            endUtcMillis = end,
            title = program.title,
            description = program.description,
        )
        cursor = maxOf(cursor, end)
    }
    if (cursor < window.endUtcMillis) {
        out += GridCell(
            id = GridCell.cellId(channelKey, cursor, window.endUtcMillis, gap = true),
            channelKey = channelKey,
            startUtcMillis = cursor,
            endUtcMillis = window.endUtcMillis,
        )
    }
    return out
}

/**
 * Width of a cell in dp: 4dp a minute, floored at [GuideGrid.MIN_CELL_WIDTH_DP].
 *
 * [startUtcMillis] and [endUtcMillis] are the cell's CLIPPED span, i.e. the part
 * on screen, because the width has to match the geometry of the axis; the
 * program's real span is carried by the cell and shown in the strip.
 */
internal fun cellWidthDp(
    startUtcMillis: Long,
    endUtcMillis: Long,
    pxPerMin: Int = GuideGrid.PX_PER_MIN,
    minWidthDp: Int = GuideGrid.MIN_CELL_WIDTH_DP,
): Int {
    val minutes = ((endUtcMillis - startUtcMillis) / 60_000L).toInt()
    return maxOf(minWidthDp, minutes * pxPerMin)
}

/** One label in the sticky time header. */
internal data class GridHourMark(
    /** Minutes from the window's left edge where this label sits. */
    val offsetMinutes: Int,
    /** `6 PM`, `7 PM`… */
    val label: String,
    /** `Sat Nov 7` when this mark opens a new day, else null. */
    val dayLabel: String?,
)

/** The hour labels across [window], with a day label where the day changes. */
internal fun hourMarks(window: GridWindow): List<GridHourMark> {
    val marks = ArrayList<GridHourMark>(GuideGrid.WINDOW_HOURS + 1)
    val cal = Calendar.getInstance()
    cal.timeInMillis = window.startUtcMillis
    var lastDay: Int? = null
    while (cal.timeInMillis < window.endUtcMillis) {
        val millis = cal.timeInMillis
        val day = cal.get(Calendar.DAY_OF_YEAR) + cal.get(Calendar.YEAR) * 1_000
        val opensDay = lastDay == null || day != lastDay
        marks += GridHourMark(
            offsetMinutes = window.offsetMinutesOf(millis),
            label = hourLabel(cal.get(Calendar.HOUR_OF_DAY)),
            // The first label always carries the date: the grid can be paged
            // into tomorrow, and a bare "12 AM" does not say which day.
            dayLabel = if (opensDay) DateFormats.time(millis, GRID_DAY_LABEL) else null,
        )
        lastDay = day
        cal.add(Calendar.HOUR_OF_DAY, 1)
    }
    return marks
}

/** `12 AM`, `6 PM` - the header's own 12-hour spellings. */
internal fun hourLabel(hourOfDay: Int): String {
    val h = ((hourOfDay % 24) + 24) % 24
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12 ${if (h < 12) "AM" else "PM"}"
}

/** Where a vertical press lands: a row index, and the cell inside it. */
internal data class GridFocusTarget(val rowIndex: Int, val cell: GridCell)

/**
 * The cell an UP/DOWN press lands on (F3a), or null at the edge of the grid.
 *
 * Walks outward from [fromRow] one row at a time and answers with the first row
 * that HAS a program in the window. Inside that row the target is the program
 * whose interval contains [fromCell]'s start time; failing that - the arrows
 * were on a gap, or the row's schedule is staggered - it is the program with the
 * greatest overlap with the focused cell, ties broken toward the earlier start.
 *
 * The target is computed from the loaded program lists, never from the positions
 * of composed nodes: that is the difference between this and the geometric
 * default search that made Detail's focus wander, and it is why a row that has
 * not been scrolled to yet is still a valid target.
 */
internal fun verticalTarget(
    rows: List<List<GridCell>>,
    fromRow: Int,
    fromCell: GridCell,
    direction: Int,
): GridFocusTarget? {
    if (direction == 0) return null
    var row = fromRow + direction
    while (row in rows.indices) {
        val programs = rows[row].filterNot { it.isGap }
        if (programs.isNotEmpty()) {
            return GridFocusTarget(rowIndex = row, cell = verticalPick(programs, fromCell))
        }
        row += direction
    }
    return null
}

private fun verticalPick(programs: List<GridCell>, fromCell: GridCell): GridCell {
    programs.firstOrNull {
        fromCell.startUtcMillis >= it.startUtcMillis && fromCell.startUtcMillis < it.endUtcMillis
    }?.let { return it }

    var best = programs.first()
    var bestOverlap = -1L
    programs.forEach { candidate ->
        val overlap = minOf(candidate.endUtcMillis, fromCell.endUtcMillis) -
            maxOf(candidate.startUtcMillis, fromCell.startUtcMillis)
        // `>` not `>=`: the list is in start order, so a tie keeps the earlier
        // start, which is what F3a asks for.
        if (overlap > bestOverlap) {
            bestOverlap = overlap
            best = candidate
        }
    }
    return best
}

/**
 * The cell a LEFT/RIGHT press lands on in the same row, or null at the row's
 * edge (F3b's defined edge no-op).
 *
 * Adjacency is by SLOT, not by program: a gap between two programs is a
 * legitimate stop, because the row's geometry puts it under the viewer's thumb.
 */
internal fun horizontalTarget(
    cells: List<GridCell>,
    fromCellId: String,
    direction: Int,
): GridCell? {
    if (direction == 0) return null
    val index = cells.indexOfFirst { it.id == fromCellId }
    if (index < 0) return null
    return cells.getOrNull(index + direction)
}

/**
 * The group labels with a saved order applied (F2k-g / T7).
 *
 * All, Recent and Favorites are pinned: they lead in that order, and only when
 * they exist. The playlist groups follow in the saved order, and a group the
 * saved order has never heard of (a new playlist group, or one that was never
 * moved) goes after the ordered ones, in playlist order. A saved label that is
 * no longer a group is dropped rather than accumulated.
 */
internal fun applyGroupOrder(
    labels: List<String>,
    savedOrder: List<String>,
): List<String> {
    if (labels.isEmpty()) return emptyList()
    if (savedOrder.isEmpty()) return labels

    val pinnedNames = listOf(GRID_GROUP_ALL, GRID_GROUP_RECENT, GRID_GROUP_FAVORITES)
    val pinned = pinnedNames.filter { it in labels }
    val playlistLabels = labels.filterNot { it in GRID_PINNED_GROUPS }

    val ordered = ArrayList<String>(playlistLabels.size)
    val seen = HashSet<String>(playlistLabels.size)
    savedOrder.forEach { label ->
        if (label in playlistLabels && seen.add(label)) ordered += label
    }
    ordered += playlistLabels.filterNot { it in seen }
    return pinned + ordered
}

/** The saved order with anything that is no longer a playlist group removed. */
internal fun pruneGroupOrder(savedOrder: List<String>, labels: List<String>): List<String> {
    val live = labels.filterNot { it in GRID_PINNED_GROUPS }.toSet()
    val seen = HashSet<String>(savedOrder.size)
    return savedOrder.filter { it in live && seen.add(it) }
}

/**
 * The saved order as it is stored: a JSON array of group labels.
 *
 * Hand-written rather than `JSONArray` because this value is read in unit tests
 * and on a device, and the android.json stub throws in the first. Only what a
 * group title can actually contain is escaped: quotes, backslashes and the
 * whitespace escapes.
 */
internal fun encodeGroupOrder(labels: List<String>): String {
    val body = labels.joinToString(",") { label ->
        val escaped = buildString(label.length + 2) {
            append('"')
            label.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
        escaped
    }
    return "[$body]"
}

/** [encodeGroupOrder]'s reader: blank or malformed input is no saved order. */
internal fun decodeGroupOrder(raw: String?): List<String> {
    val text = raw?.trim().orEmpty()
    if (text.length < 2 || !text.startsWith("[") || !text.endsWith("]")) return emptyList()

    val out = ArrayList<String>()
    val current = StringBuilder()
    var inString = false
    var escaped = false
    var index = 1
    val last = text.length - 1
    while (index < last) {
        val ch = text[index]
        if (escaped) {
            current.append(
                when (ch) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> ch
                }
            )
            escaped = false
        } else {
            when {
                ch == '\\' -> escaped = true
                ch == '"' -> {
                    if (inString) {
                        out += current.toString()
                        current.setLength(0)
                    } else {
                        current.setLength(0)
                    }
                    inString = !inString
                }
                inString -> current.append(ch)
            }
        }
        index++
    }
    return out
}

/**
 * The label the detail strip shows under the grid for [cell].
 *
 * A gap says what it is; a program says when it is on and whether that press can
 * do anything at all (`Ended` is the one that cannot, where the channel has no
 * catch-up).
 */
internal fun gridCellHeadline(cell: GridCell, nowUtcMillis: Long): String = when {
    cell.isGap -> "No program data"
    nowUtcMillis < cell.startUtcMillis -> formatTimeRange(cell.startUtcMillis, cell.endUtcMillis)
    nowUtcMillis >= cell.endUtcMillis -> "Ended · ${formatTimeRange(cell.startUtcMillis, cell.endUtcMillis)}"
    else -> "On now · ${formatRemainingLabel(cell.endUtcMillis - nowUtcMillis) ?: "ending"}"
}

/** Fraction of the cell already aired, 0..1 - the live cell's fill (F2h). */
internal fun liveFillFraction(cell: GridCell, nowUtcMillis: Long): Float {
    if (cell.isGap || cell.durationMs <= 0L) return 0f
    if (nowUtcMillis <= cell.startUtcMillis) return 0f
    if (nowUtcMillis >= cell.endUtcMillis) return 1f
    return ((nowUtcMillis - cell.startUtcMillis).toDouble() / cell.durationMs.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

/**
 * Whether a cell in the window read should still be held for the new window.
 *
 * The sliding window keeps what it already has (F1c): a program whose span
 * overlaps [window] is kept even though it was read for the previous window, and
 * only the newly exposed span is fetched. Keeping this a pure predicate is what
 * makes "no flash back to loading" checkable.
 */
internal fun keepForWindow(program: GridProgram, window: GridWindow): Boolean =
    program.endUtcMillis > window.startUtcMillis && program.startUtcMillis < window.endUtcMillis

/** Deduplicates rows read for overlapping windows: same channel, same start. */
internal fun mergeWindowPrograms(
    existing: List<GridProgram>,
    incoming: List<GridProgram>,
): List<GridProgram> {
    if (incoming.isEmpty()) return existing
    val seen = HashSet<Long>(existing.size + incoming.size)
    val out = ArrayList<GridProgram>(existing.size + incoming.size)
    (existing + incoming).sortedBy { it.startUtcMillis }.forEach { program ->
        if (seen.add(program.startUtcMillis)) out += program
    }
    return out
}

/** Group labels the sidebar/dialog may reorder: the playlist's own, never pinned. */
internal fun reorderableGroupLabels(labels: List<String>): List<String> =
    labels.filterNot { it in GRID_PINNED_GROUPS }

/**
 * [labels] with the group at [index] moved one slot in [direction].
 *
 * Returns the list unchanged at either end - the dialog's ▲ on the first row and
 * ▼ on the last are defined no-ops, not a wrap (a wrap would silently send a
 * group from the top of the list to the bottom on a stray press).
 */
internal fun moveGroupOrder(labels: List<String>, index: Int, direction: Int): List<String> {
    if (direction == 0) return labels
    if (index !in labels.indices) return labels
    val target = index + direction
    if (target !in labels.indices) return labels
    val out = labels.toMutableList()
    val moved = out.removeAt(index)
    out.add(target, moved)
    return out
}
