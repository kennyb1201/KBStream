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
 * still a target, a window paged 3 hours at a time (6 hours on the phone,
 * 3/6/12 on the TV grid's zoom), 24 programs per channel per window read, and
 * the 80-row page the ViewModel already caps a guide request at.
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

    /**
     * The window the PHONE grid draws, in hours.
     *
     * The TV grid's zoom opens on [DEFAULT_WINDOW_HOURS] instead and walks
     * [ZOOM_WINDOW_HOURS] (F12e); its window is a parameter from then on, and
     * this constant is only the phone view's own.
     */
    const val WINDOW_HOURS = 6

    /**
     * The window the TV grid opens on, in hours (F12e, amending the earlier
     * six-hour default).
     *
     * Three hours is the TiviMate-like view: a two-hour film is 960dp wide, so
     * its title reads instead of truncating the way it did at six. It is the
     * default only - the zoom button still reaches every level.
     */
    const val DEFAULT_WINDOW_HOURS = 3

    /**
     * The zoom's levels, in hours, in the order the header button walks them:
     * 3h -> 6h -> 12h -> 3h. Six and twelve are unchanged; three is new, and is
     * where a fresh install opens.
     */
    val ZOOM_WINDOW_HOURS: List<Int> = listOf(3, 6, 12)

    /** How far one page of the time axis moves, in hours. */
    const val PAGE_HOURS = 3

    /** Width of one minute of air time. */
    const val PX_PER_MIN = 4

    /**
     * The narrowest a cell may render. A 5-minute program is 20dp at
     * [PX_PER_MIN] - too small to read, focus or hit - so it renders here and
     * clips its title; its real start/end still drive focus and the top block.
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

    val PAGE_MS: Long = PAGE_HOURS * HOUR_MS

    /**
     * The window the zoom reaches after [currentHours]: 3 -> 6 -> 12 -> 3.
     *
     * An unknown level (a pref written by a build that had another one) opens
     * the cycle at its first level rather than throwing or sticking.
     */
    fun nextZoomHours(currentHours: Int): Int {
        val index = ZOOM_WINDOW_HOURS.indexOf(currentHours)
        return if (index < 0) {
            ZOOM_WINDOW_HOURS.first()
        } else {
            ZOOM_WINDOW_HOURS[(index + 1) % ZOOM_WINDOW_HOURS.size]
        }
    }
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
        // The page keeps the window's OWN span, so a zoomed window pages at
        // the zoom's width instead of snapping back to six hours (F12e).
        val span = durationMs
        val nextStart = startUtcMillis + direction * GuideGrid.PAGE_MS
        if (direction < 0 && nextStart < floorStartUtcMillis) {
            return if (startUtcMillis > floorStartUtcMillis) {
                GridWindow(floorStartUtcMillis, floorStartUtcMillis + span)
            } else {
                this
            }
        }
        if (direction > 0 && maxEndUtcMillis != null && nextStart >= maxEndUtcMillis) return this
        return GridWindow(nextStart, nextStart + span)
    }

    /**
     * The same start, [windowHours] wide - what a zoom change does to the
     * window (F12e): the axis stays where the viewer left it, only more or less
     * of it is drawn.
     */
    fun withHours(windowHours: Int): GridWindow {
        val span = windowHours.coerceAtLeast(1) * GuideGrid.HOUR_MS
        return GridWindow(startUtcMillis, startUtcMillis + span)
    }

    companion object {
        /**
         * The window the grid opens on: the current hour, [windowHours] wide.
         *
         * The TV grid passes its zoom level; the phone view leaves the default,
         * which is its own six-hour window.
         */
        fun opening(
            nowUtcMillis: Long,
            windowHours: Int = GuideGrid.WINDOW_HOURS,
        ): GridWindow {
            val start = floorToHour(nowUtcMillis)
            val span = windowHours.coerceAtLeast(1) * GuideGrid.HOUR_MS
            return GridWindow(start, start + span)
        }
    }
}

/** The width of [window]'s scrolled content in dp - one dp width per minute. */
internal fun gridContentWidthDp(window: GridWindow): Int =
    ((window.durationMs / 60_000L) * GuideGrid.PX_PER_MIN).toInt()

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
    /** The guide's own category, when the source carries one (F14a). */
    val category: String? = null,
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
            category = it.category,
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
    /** The program's guide category, when it has one (F14a). */
    val category: String? = null,
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
 * actually on screen, and keeps its real start for the detail block and for
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
            category = program.category,
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
 * program's real span is carried by the cell and shown in the top block.
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
    val marks = ArrayList<GridHourMark>((window.durationMs / GuideGrid.HOUR_MS).toInt() + 1)
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
 * The detail block's one-line meta: the channel, when the program is on, and the
 * guide's category when it carries one (F14a).
 *
 * A future program says `Starts in 25 min` - the block draws no progress bar for
 * it, because a bar would be an empty rail that never moves - a live one says
 * `On now · 12 min left`, and an ended one says `Ended`. There is no score and
 * no rating: the guide carries neither, and a placeholder says more than the
 * data does.
 */
internal fun gridDetailMeta(
    channelName: String,
    cell: GridCell,
    nowUtcMillis: Long,
): String {
    val whenLabel = if (!cell.isGap && nowUtcMillis < cell.startUtcMillis) {
        val range = formatTimeRange(cell.startUtcMillis, cell.endUtcMillis)
        val starts = formatStartsInLabel(cell.startUtcMillis - nowUtcMillis)
        if (starts == "starting") "$range · Starting now" else "$range · Starts $starts"
    } else {
        gridCellHeadline(cell, nowUtcMillis)
    }
    return listOfNotNull(
        channelName.takeIf { it.isNotBlank() },
        whenLabel,
        cell.category?.trim()?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/**
 * The label the grid's own cells fall back to for [cell] - the time range and
 * whether that press can do anything at all (`Ended` is the one that cannot,
 * where the channel has no catch-up).
 */
internal fun gridCellHeadline(cell: GridCell, nowUtcMillis: Long): String = when {
    cell.isGap -> "No program data"
    nowUtcMillis < cell.startUtcMillis -> formatTimeRange(cell.startUtcMillis, cell.endUtcMillis)
    nowUtcMillis >= cell.endUtcMillis -> "Ended · ${formatTimeRange(cell.startUtcMillis, cell.endUtcMillis)}"
    else -> "On now · ${formatRemainingLabel(cell.endUtcMillis - nowUtcMillis) ?: "ending"}"
}

/**
 * What a grid row says about its guide data (F3a-c).
 *
 * Three states, not two, because "no answer yet" and "answered, and there is
 * nothing" are different facts and only the second one may be reported as a
 * result. The per-game channels are the case that forces it: the guide has no
 * match for them, their read finishes empty, and nothing will ever change - so
 * a row that only knows "no programs on screen" calls them loading forever.
 */
internal enum class GuideRowGuideState {
    /** Nothing has answered yet for this channel: the read is out or still to come. */
    LOADING,

    /** The read finished and this channel has no guide data at all. */
    NO_GUIDE_DATA,

    /** An answer is in: the channel is matched, or its read found programs. */
    ANSWERED,
}

/**
 * Which of [GuideRowGuideState] a row is in (F3a, F3c).
 *
 * A matched channel is always answered - its guide rows exist, so the grid has
 * said everything it can about it, and an empty window on it is a feed gap, not
 * a missing guide (F3d). A row that already holds programs is answered too: the
 * next window is a slide, and flipping it back to "Loading guide…" over the data
 * it is still drawing would be a lie (F1c, F2b).
 *
 * What is left is a row with nothing to show. Its entry decides: absent means
 * nothing has answered, so it is still loading; while it is in [loadingIds] an
 * empty entry is stale for the window being read and it is loading too - never a
 * result the read is about to replace (F3c). Only an empty entry with no read out
 * is a finished answer.
 */
internal fun guideRowGuideState(
    channelId: String,
    resolvedGuideIds: Set<String>,
    programsByChannel: Map<String, List<GridProgram>>,
    loadingIds: Set<String>,
): GuideRowGuideState {
    if (channelId in resolvedGuideIds) return GuideRowGuideState.ANSWERED
    val programs = programsByChannel[channelId]
    return when {
        programs == null -> GuideRowGuideState.LOADING
        programs.isNotEmpty() -> GuideRowGuideState.ANSWERED
        channelId in loadingIds -> GuideRowGuideState.LOADING
        else -> GuideRowGuideState.NO_GUIDE_DATA
    }
}

/**
 * One live or finished game as the grid draws it on a cell (F3b, F3m-c).
 *
 * [channelIds] is the answer of the HUB's game-to-channel resolution, not a
 * second match: the grid only asks which of its cells show the game, never
 * which channel carries it. [line] is [com.kennyb1201.kbstream.data.sports]
 * gameScoreLine's own sentence - `TB 3 - 2 MIN · 2nd 14:32` while live,
 * `Final 3 - 2` after - so the hub's card and the grid's cell cannot disagree
 * about what a game's score reads like.
 */
internal data class GuideScore(
    val channelIds: Set<String>,
    /** The game's scheduled kickoff, epoch millis. */
    val startUtcMillis: Long,
    val line: String,
    /** True while the game is being played, false once it is final. */
    val live: Boolean,
)

/** What a single cell's second line says about a game, when it has one. */
internal data class GridCellScore(
    val line: String,
    val live: Boolean,
)

/**
 * The score a cell draws, or null when its program is not carrying a game
 * (F3b, F3m-c).
 *
 * The ONE lookup both surfaces use - the TV cell and the phone cell render what
 * this returns, so there is no second score pipeline and no second match.
 *
 * "Its program carries the game" is asked of the cell, not of the clock alone:
 * a cell is CLIPPED to the window (a program that started before the window
 * opens has the window's start), so a kickoff is not always inside it even
 * though the program is the game's own broadcast slot. The two cases are
 * therefore:
 *
 *  - **live**: the cell is the one on air right now on that channel. That is
 *    the program carrying the game at this moment, whether it began ten minutes
 *    or two hours ago.
 *  - **final**: the cell whose span contains the kickoff - the program the game
 *    was scheduled in. It keeps reading `Final 3 - 2` until its own end, which
 *    is what F3b asks for, and the next program does not inherit it.
 *
 * A gap cell never shows a score: nothing is airing there.
 */
internal fun scoreFor(
    channelId: String,
    cell: GridCell,
    nowUtcMillis: Long,
    scores: List<GuideScore>,
): GridCellScore? {
    if (cell.isGap) return null
    val score = scores.firstOrNull { game ->
        channelId in game.channelIds && scoreOverlapsCell(game, cell, nowUtcMillis)
    } ?: return null
    return GridCellScore(line = score.line, live = score.live)
}

/** Whether [score]'s game is the one [cell] is airing (see [scoreFor]). */
internal fun scoreOverlapsCell(
    score: GuideScore,
    cell: GridCell,
    nowUtcMillis: Long,
): Boolean = if (score.live) {
    nowUtcMillis >= cell.startUtcMillis && nowUtcMillis < cell.endUtcMillis
} else {
    cell.startUtcMillis <= score.startUtcMillis && score.startUtcMillis < cell.endUtcMillis
}

/**
 * How far past a window's right edge the auto-follow still counts as "just
 * crossed" (F2a).
 *
 * The grid's clock ticks every 30 seconds, so the tick that sees the crossing is
 * at most one tick late. Two minutes leaves room for a device that slept a beat
 * without letting a viewer who has been away for an hour be yanked forward: that
 * is a window that no longer contains now, not one the clock just left.
 */
internal const val GRID_AUTO_FOLLOW_SLACK_MS = 120_000L

/**
 * The window auto-follow moves to, or null when it must not move (F2a, T3).
 *
 * The rule, in one place so it can be checked without a grid on screen:
 *
 *  - only a window the clock has JUST left follows. A viewer paged forward is
 *    never yanked back to now, and one who has been away for an hour is not
 *    dragged forward - [nowUtcMillis] may sit past the edge by at most
 *    [GRID_AUTO_FOLLOW_SLACK_MS];
 *  - the move is one page, at the window's own zoom, through [GridWindow.paged]
 *    - so the floor (never before the current hour) and the guide's schedule
 *    end still apply, and a window pinned at the end answers itself;
 *  - a schedule that has ended before the next page answers null, i.e. no move.
 */
internal fun autoFollowWindow(
    window: GridWindow,
    nowUtcMillis: Long,
    windowHours: Int,
    scheduleEndMs: Long?,
): GridWindow? {
    if (nowUtcMillis < window.endUtcMillis) return null
    if (nowUtcMillis - window.endUtcMillis > GRID_AUTO_FOLLOW_SLACK_MS) return null
    val next = window.paged(
        direction = 1,
        floorStartUtcMillis = GridWindow.opening(nowUtcMillis, windowHours).startUtcMillis,
        maxEndUtcMillis = scheduleEndMs,
    )
    return next.takeIf { it != window }
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

/**
 * The grid's programs after a window read answered [loaded] for [requestedIds].
 *
 * Every requested channel gets an entry, even when the read held nothing for it
 * (F2a): an explicit empty list means "this window was read and it is empty for
 * this channel", so absence can mean exactly one thing - never read. Without
 * that, a channel whose read finishes empty is indistinguishable from one that
 * was never asked for, and a row that can never be answered waits forever.
 *
 * The entries are merged like any other ([mergeWindowPrograms]), so a channel
 * read for one window keeps what it already has when the next window is read.
 */
internal fun mergeWindowAnswer(
    existing: Map<String, List<GridProgram>>,
    requestedIds: Set<String>,
    loaded: Map<String, List<EpgProgramRow>>,
): Map<String, List<GridProgram>> {
    val ids = requestedIds + loaded.keys
    if (ids.isEmpty()) return existing
    val merged = HashMap<String, List<GridProgram>>(existing)
    ids.forEach { channelId ->
        merged[channelId] = mergeWindowPrograms(
            existing = merged[channelId].orEmpty(),
            incoming = gridProgramsOf(channelId, loaded[channelId].orEmpty()),
        )
    }
    return merged
}

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
