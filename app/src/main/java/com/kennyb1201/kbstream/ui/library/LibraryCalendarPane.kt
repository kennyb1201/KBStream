package com.kennyb1201.kbstream.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.FormFactor
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_DELAY_MS
import com.kennyb1201.kbstream.ui.browse.BROWSE_RETURN_FOCUS_STEPS
import com.kennyb1201.kbstream.ui.home.formatAirDateLabel
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusRowInset
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.delay

/**
 * The Library calendar pane: one month at a time, of the whole year, of every
 * tracked show.
 *
 * One composition serves both form factors. The grid, the header controls and
 * the day list are drawn once; what a television adds is focus (a day is
 * selected by focus, the grid's left/right edges page the month, Down enters the
 * list and Up comes back to the selected cell) and what a phone adds is a tap
 * on the same plate.
 *
 * Two things this pane had to learn about the TV `Surface` the field reported on
 * 2026-10-24:
 *
 *  - it is a FOCUS surface. Its activation is a D-pad OK, and a finger cannot
 *    press it at all - the same trap the phone's update popup hit. So every
 *    pressable here is branched: a television draws the `Surface` (focus,
 *    ring, glow), a phone draws the same plate with a plain foundation
 *    `clickable`. Without that branch the whole calendar was dead to touch.
 *  - its focused fill is NOT this app's. Passing only the resting pair leaves
 *    tv-material3's near-white focused container in place, with the bright
 *    resting label on top of it - "focus is bright white and hard to read
 *    what you're focused on". Every pressable below therefore names its
 *    focused and pressed containers as well, exactly as `KBCard` and the
 *    Add-ons list card do.
 *
 * The day list is a COLUMN OF ITS OWN on a television, beside the grid. It used
 * to sit under the grid in the same column, where the six grid rows left it
 * roughly one row of height - so a day with three episodes looked like a day
 * with one, and the other two were unreachable because the list was cropped
 * before it began. Beside the grid it has the pane's whole height, which is
 * what a list of a day's episodes needs.
 *
 * The pane never asks for the D-pad unless it was handed it ([takeInitialFocus]
 * is true, which is only the Home rail's shortcut): arriving from the CALENDAR
 * chip keeps focus on the chip, exactly as every other Library pane does.
 */
@Composable
fun LibraryCalendarPane(
    onOpenDetails: (navigationId: String) -> Unit,
    modifier: Modifier = Modifier,
    takeInitialFocus: Boolean = false,
    viewModel: LibraryCalendarViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val touch = !FormFactor.isTv

    /*
     * One requester per day of the visible month, built in a remember rather
     * than lazily by the grid's cell loop: FocusRequester() is
     * @RememberInComposition, so constructing one while composing a cell is a
     * composition-time allocation lint refuses (and the map only ever holds the
     * month on screen, so keying it on the month is the whole lifetime it
     * needs).
     */
    val dayRequesters = remember(state.month) {
        calendarCells(state.month)
            .filterNotNull()
            .associateWith { FocusRequester() }
    }

    /*
     * The day list's first row. Down on the grid's bottom row hands the D-pad
     * here; it is the list's own requester rather than a requester on the list
     * container, because a Column is not a focus target - asking one for focus
     * answered false and the list could not be entered at all.
     */
    val firstEpisodeRequester = remember { FocusRequester() }

    // The day the D-pad should be on, once the month it belongs to has landed.
    var landDay by remember { mutableStateOf(LocalDate.now()) }
    var landRequest by remember { mutableStateOf(if (takeInitialFocus) 1 else 0) }

    /*
     * A press on a day is a request to SEE that day, and on a television "see"
     * means the D-pad has to get there: selecting the day only retitles the side
     * list, which the viewer could not otherwise reach (Down enters it from the
     * grid's BOTTOM row, so every earlier row's episodes were readable but not
     * pressable). The request is retried rather than asked once, because the
     * list re-composes for the new day in the same breath as the press - the
     * same rule the month landing follows.
     */
    var listLandRequest by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { viewModel.open() }

    LaunchedEffect(listLandRequest) {
        if (listLandRequest == 0) return@LaunchedEffect
        var landed = false
        var attempts = 0
        while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
            delay(BROWSE_RETURN_FOCUS_DELAY_MS)
            landed = runCatching {
                firstEpisodeRequester.requestFocus()
            }.getOrDefault(false)
            attempts++
        }
    }

    /*
     * One landing effect for both cases it has to serve: the initial focus the
     * Home shortcut asks for, and the cell a month page must hand the D-pad to.
     * Both are delivered AFTER composition and retried, because a requester
     * asked in the same frame as its node's attach answers false rather than
     * throwing - the same rule the Detail hand-backs and the Library's return
     * landing follow.
     */
    LaunchedEffect(landRequest, state.loading, state.month) {
        if (landRequest == 0 || state.loading) return@LaunchedEffect
        var landed = false
        var attempts = 0
        while (!landed && attempts < BROWSE_RETURN_FOCUS_STEPS) {
            delay(BROWSE_RETURN_FOCUS_DELAY_MS)
            val day = landDay.takeIf { YearMonth.from(it) == state.month }
                ?: state.selected
            landed = runCatching {
                dayRequesters[day]?.requestFocus() ?: false
            }.getOrDefault(false)
            attempts++
        }
    }

    fun page(delta: Int) {
        // The landing day belongs to the month being LEFT, so the incoming one
        // decides its own (today, or its first episode day).
        landDay = LocalDate.now()
        landRequest++
        viewModel.pageMonth(delta)
    }

    val today = LocalDate.now()

    CalendarPaneLayout(
        state = state,
        today = today,
        touch = touch,
        dayRequesters = dayRequesters,
        firstEpisodeRequester = firstEpisodeRequester,
        onSelect = viewModel::select,
        onPrevMonth = { page(-1) },
        onNextMonth = { page(1) },
        onToday = {
            landDay = LocalDate.now()
            landRequest++
            viewModel.goToToday()
        },
        onEnterDayList = { listLandRequest++ },
        onOpenDetails = onOpenDetails,
        modifier = modifier
    )
}

/**
 * The pane's two arrangements.
 *
 * A television has the width for the grid and the day list side by side, and
 * that is the only arrangement in which a day's episodes are all reachable. A
 * phone does not, so it keeps them stacked and gives the list the remaining
 * height (`weight`) rather than letting the grid take its size first.
 */
@Composable
private fun CalendarPaneLayout(
    state: LibraryCalendarUiState,
    today: LocalDate,
    touch: Boolean,
    dayRequesters: Map<LocalDate, FocusRequester>,
    firstEpisodeRequester: FocusRequester,
    onSelect: (LocalDate) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    /** Take the D-pad into the selected day's list. */
    onEnterDayList: () -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier
) {
    val backToGrid = {
        if (!touch) {
            runCatching { dayRequesters[state.selected]?.requestFocus() ?: false }
        }
    }

    if (touch) {
        Column(modifier = modifier.fillMaxSize()) {
            CalendarMonthSection(
                state = state,
                today = today,
                touch = touch,
                dayRequesters = dayRequesters,
                onSelect = onSelect,
                onOpenDay = onEnterDayList,
                onPrevMonth = onPrevMonth,
                onNextMonth = onNextMonth,
                onToday = onToday,
                onEnterDayList = onEnterDayList,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            CalendarDayList(
                state = state,
                touch = touch,
                firstEpisodeRequester = firstEpisodeRequester,
                onUpToGrid = backToGrid,
                onOpenDetails = onOpenDetails,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = modifier.fillMaxSize()
        ) {
            CalendarMonthSection(
                state = state,
                today = today,
                touch = touch,
                dayRequesters = dayRequesters,
                onSelect = onSelect,
                onOpenDay = onEnterDayList,
                onPrevMonth = onPrevMonth,
                onNextMonth = onNextMonth,
                onToday = onToday,
                onEnterDayList = onEnterDayList,
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            )

            CalendarDayList(
                state = state,
                touch = touch,
                firstEpisodeRequester = firstEpisodeRequester,
                onUpToGrid = backToGrid,
                onOpenDetails = onOpenDetails,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

/** The header, the weekday initials and the month grid, as one column. */
@Composable
private fun CalendarMonthSection(
    state: LibraryCalendarUiState,
    today: LocalDate,
    touch: Boolean,
    dayRequesters: Map<LocalDate, FocusRequester>,
    onSelect: (LocalDate) -> Unit,
    /** A day was activated: select it AND move the D-pad into its list. */
    onOpenDay: () -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    /** Down on the grid's bottom row: enter the day list. */
    onEnterDayList: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        CalendarHeader(
            month = state.month,
            touch = touch,
            onPrev = onPrevMonth,
            onNext = onNextMonth,
            onToday = onToday
        )

        CalendarWeekdayRow()

        CalendarGrid(
            month = state.month,
            days = state.days,
            selected = state.selected,
            today = today,
            touch = touch,
            dayRequesters = dayRequesters,
            onSelect = onSelect,
            onOpenDay = onOpenDay,
            onPrevMonth = onPrevMonth,
            onNextMonth = onNextMonth,
            onEnterDayList = onEnterDayList
        )
    }
}

/** The month header: prev, the month, next, and the way back to today. */
@Composable
private fun CalendarHeader(
    month: YearMonth,
    touch: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        CalendarChip(
            label = "‹",
            touch = touch,
            description = "Previous month",
            onClick = onPrev
        )
        Text(
            text = calendarMonthLabel(month),
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(180.dp)
        )
        CalendarChip(
            label = "›",
            touch = touch,
            description = "Next month",
            onClick = onNext
        )
        Spacer(modifier = Modifier.weight(1f))
        CalendarChip(label = "TODAY", touch = touch, onClick = onToday)
    }
}

/** The seven weekday initials above the grid. */
@Composable
private fun CalendarWeekdayRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        calendarWeekdayLabels().forEach { label ->
            Text(
                text = label,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * One header control: a chip that both a D-pad and a finger can press.
 *
 * [description] names the action for accessibility - "‹" is not a sentence on
 * its own - and is never drawn.
 *
 * Passing only the resting colours left tv-material3's near-white focused fill
 * under the focused label, so the pair is named here as well - and a phone,
 * which cannot press a focus surface at all, gets the foundation clickable on
 * the same plate (see the pane's KDoc).
 */
@Composable
private fun CalendarChip(
    label: String,
    touch: Boolean,
    description: String? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accessibility = if (description == null) {
        Modifier
    } else {
        Modifier.semantics { contentDescription = description }
    }

    if (touch) {
        Box(
            modifier = Modifier
                .clip(KBShapeCard)
                .background(KBSurface)
                .clickable(onClick = onClick)
                .then(accessibility)
        ) {
            CalendarChipLabel(label = label, color = KBTextLo, focused = false)
        }
        return
    }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (focused) KBSurfaceRaised else KBSurface,
            contentColor = if (focused) KBTextHi else KBTextLo,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBTextHi
        ),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .then(accessibility)
    ) {
        CalendarChipLabel(
            label = label,
            color = if (focused) KBTextHi else KBTextLo,
            focused = focused
        )
    }
}

/** A chip's label, at the weight focus calls for. */
@Composable
private fun CalendarChipLabel(label: String, color: Color, focused: Boolean) {
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/**
 * The month grid: seven columns, one cell per day, empty cells padded so the
 * 1st sits under its own weekday.
 *
 * Not a lazy grid on purpose. A month is at most six rows of seven, every cell
 * has to exist for focus search to walk it, and the D-pad's month edges are a
 * property of the CELL (is this column 0 or 6?) rather than of a scrolling
 * viewport.
 */
@Composable
private fun CalendarGrid(
    month: YearMonth,
    days: Map<LocalDate, List<CalendarEpisode>>,
    selected: LocalDate,
    today: LocalDate,
    touch: Boolean,
    dayRequesters: Map<LocalDate, FocusRequester>,
    onSelect: (LocalDate) -> Unit,
    onOpenDay: () -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onEnterDayList: () -> Unit
) {
    val rows = remember(month) { calendarCells(month).chunked(7) }

    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { rowIndex, week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEachIndexed { column, day ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                    ) {
                        if (day == null) {
                            Box(modifier = Modifier.fillMaxWidth().height(CALENDAR_CELL_HEIGHT))
                        } else {
                            CalendarDayCell(
                                day = day,
                                episodeCount = days[day].orEmpty().size,
                                selected = day == selected,
                                isToday = day == today,
                                touch = touch,
                                isFirstColumn = column == 0,
                                isLastColumn = column == 6,
                                isLastRow = rowIndex == rows.lastIndex,
                                // Down only leaves the grid when there is a
                                // list below to land in; a day with nothing on
                                // it lets the press fall through instead of
                                // swallowing it.
                                hasEpisodesBelow = days[day].orEmpty().isNotEmpty(),
                                requester = if (touch) null else dayRequesters[day],
                                onSelect = onSelect,
                                onOpenDay = onOpenDay,
                                onPrevMonth = onPrevMonth,
                                onNextMonth = onNextMonth,
                                onEnterDayList = onEnterDayList
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One day: its number, a marker when episodes land on it, a ring on today.
 *
 * Selection follows FOCUS on a television (the Home hero's rule), which is what
 * makes the day list under the grid follow the D-pad without a press; a phone
 * selects on tap. The grid's left/right edges page the month rather than
 * walking off into nothing, and Down on the bottom row enters the day list.
 *
 * The cell's height is a fixed [CALENDAR_CELL_HEIGHT] and its two lines are
 * sized to fit inside it. It used to be 42dp, which is less than one label's
 * line box plus the smallest label's - so the episode COUNT under the day
 * number was drawn about half outside the cell, clipped by the Surface. The
 * count is how a viewer knows a day has more than one episode, so it has to be
 * legible, not decorative.
 *
 * Focus and touch are two different plates, for the two reasons in the pane's
 * KDoc: a television names its focused fill (tv-material3's own is near-white
 * on bright text) and keeps the accent ring that says where focus is; a phone
 * gets a foundation clickable on the same plate, because it cannot press a
 * focus surface at all.
 */
@Composable
private fun CalendarDayCell(
    day: LocalDate,
    episodeCount: Int,
    selected: Boolean,
    isToday: Boolean,
    touch: Boolean,
    isFirstColumn: Boolean,
    isLastColumn: Boolean,
    isLastRow: Boolean,
    hasEpisodesBelow: Boolean,
    requester: FocusRequester?,
    onSelect: (LocalDate) -> Unit,
    onOpenDay: () -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onEnterDayList: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val selectedFill = KBAccent.copy(alpha = 0.28f)
    val labelColor = if (selected || isToday) KBAccent else KBTextHi

    val plate = if (selected) selectedFill else KBSurface

    if (touch) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(CALENDAR_CELL_HEIGHT)
                .clip(KBShapeCard)
                .background(plate)
                .border(
                    width = 1.dp,
                    color = if (isToday) KBAccent else Color.Transparent,
                    shape = KBShapeCard
                )
                .clickable { onSelect(day) }
        ) {
            CalendarDayCellContent(
                day = day,
                episodeCount = episodeCount,
                selected = selected,
                labelColor = labelColor
            )
        }
        return
    }

    Surface(
        onClick = {
            onSelect(day)
            // On a television the press is a request to see the day: take the
            // D-pad into its list so the episodes can be opened, not just read
            // in the pane beside the grid. A day with nothing on it keeps the
            // press as a plain selection.
            if (hasEpisodesBelow) onOpenDay()
        },
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                selected -> selectedFill
                focused -> KBSurfaceRaised
                else -> KBSurface
            },
            contentColor = labelColor,
            // NAMED, not left to tv-material3: its focused fill is a near-white
            // that this palette never uses, and the label keeps its bright
            // resting colour on it - the "bright white, hard to read" report.
            focusedContainerColor = if (selected) selectedFill else KBSurfaceRaised,
            focusedContentColor = labelColor,
            pressedContainerColor = if (selected) selectedFill else KBSurfaceRaised,
            pressedContentColor = labelColor
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (isToday) KBAccent else Color.Transparent
                ),
                shape = KBShapeCard
            ),
            focusedBorder = Border(
                border = BorderStroke(1.dp, KBAccent),
                shape = KBShapeCard
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(CALENDAR_CELL_HEIGHT)
            .then(
                if (requester == null) Modifier else Modifier.focusRequester(requester)
            )
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (focusState.isFocused) onSelect(day)
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else if (event.nativeKeyEvent.repeatCount > 0) {
                    // A KEY HELD DOWN IS ONE PAGE, NOT ONE PER REPEAT. Both the
                    // paging arms and the Down-into-the-list arm ask the page to
                    // do real work (a month page cancels the build in flight and
                    // fans out over every tracked show again), and a remote's
                    // auto-repeat arrives as a stream of KeyDowns with a rising
                    // repeatCount - so holding Right on a month's last column
                    // used to page months as fast as the remote could repeat,
                    // starting a full TMDB/TVmaze fan-out per repeat. On the
                    // field report ("scrolled through the calendar quickly and
                    // it kicked me out to launcher, nothing in Sentry") that is
                    // the app asking the box for far more than it can hold at
                    // once. The first press still does the thing; the repeats of
                    // that same press do nothing at all.
                    false
                } else {
                    when {
                        event.key == Key.DirectionLeft && isFirstColumn -> {
                            onPrevMonth()
                            true
                        }

                        event.key == Key.DirectionRight && isLastColumn -> {
                            onNextMonth()
                            true
                        }

                        event.key == Key.DirectionDown && isLastRow && hasEpisodesBelow -> {
                            onEnterDayList()
                            true
                        }

                        else -> false
                    }
                }
            }
    ) {
        CalendarDayCellContent(
            day = day,
            episodeCount = episodeCount,
            selected = selected,
            labelColor = labelColor
        )
    }
}

/** A day cell's two lines: the number, and how many episodes land on it. */
@Composable
private fun CalendarDayCellContent(
    day: LocalDate,
    episodeCount: Int,
    selected: Boolean,
    labelColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = day.dayOfMonth.toString(),
            color = labelColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
        if (episodeCount > 0) {
            Text(
                text = episodeCount.toString(),
                color = KBAccent,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/**
 * The selected day's episodes, in their own column.
 *
 * The three empty states are deliberately different sentences: no tracked shows
 * at all (naming the sources that would fill it), a month with nothing in it,
 * and a day with nothing in it. A day with many episodes scrolls HERE and only
 * here - the grid never moves.
 *
 * The FIRST row is the list's entry point: Down on the grid's bottom row asks
 * for it, and Up on it hands the D-pad back to the selected day. Before this
 * the requester sat on the (non-focusable) container Column, so the press
 * answered false and a day's episodes could not be reached with a remote at
 * all - the only way to an episode was to open the show from somewhere else.
 */
@Composable
private fun CalendarDayList(
    state: LibraryCalendarUiState,
    touch: Boolean,
    firstEpisodeRequester: FocusRequester,
    onUpToGrid: () -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.hasShows && !state.loading) {
        CalendarNotice(
            text = "Nothing to schedule yet. Watch something, or connect " +
                "Simkl, MDBList or Trakt, and its episodes appear here.",
            modifier = modifier
        )
        return
    }

    if (state.loading) {
        CalendarNotice("Loading this month…", modifier = modifier)
        return
    }

    if (state.monthIsEmpty) {
        CalendarNotice("Nothing scheduled this month.", modifier = modifier)
        return
    }

    val episodes = state.episodesOn(state.selected)

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = calendarDayLabel(state.selected),
                color = KBTextLo,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (episodes.isNotEmpty()) {
                // How many there are, in words: the grid's count is a glyph,
                // and this is the line that says what it counted.
                Text(
                    text = if (episodes.size == 1) {
                        "1 episode"
                    } else {
                        "${episodes.size} episodes"
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (episodes.isEmpty()) {
            Text(
                text = "Nothing airing",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            // The vertical inset is the room a focused row needs INSIDE the
            // list's own clips-to-bounds viewport (see KBFocusRowInset). With
            // only bottom padding, the FIRST row's ring was drawn at the very
            // top edge of the scroll and sliced off flat - the reported "first
            // row is clipped up top" once a day was focused.
            contentPadding = PaddingValues(
                top = KBFocusRowInset,
                bottom = KBFocusRowInset
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = episodes,
                key = { _, episode ->
                    "${episode.showId}|s${episode.season}|e${episode.episode}"
                }
            ) { index, episode ->
                CalendarEpisodeRow(
                    episode = episode,
                    watched = state.isWatched(episode),
                    touch = touch,
                    onClick = { onOpenDetails(episode.openId) },
                    // Only the first row carries the entry request; the rest
                    // are ordinary focus stops between it and the bottom.
                    requester = if (index == 0) firstEpisodeRequester else null,
                    onUpPressed = if (index == 0 && !touch) onUpToGrid else null
                )
            }
        }
    }
}

/** A plain line of copy where a list would be. */
@Composable
private fun CalendarNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = KBTextLo,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(top = 8.dp)
    )
}

/**
 * One episode on the selected day.
 *
 * Show name, S/E with the episode's title, the air-date label (the same
 * formatter the Upcoming rail uses, so "Today" means the same thing on both),
 * a PREMIERE/FINALE badge where the season payload says so, and a watched mark
 * for a past episode from the merged watched union. The press opens the SHOW's
 * detail page, which is what a rail card does - an episode is not a route.
 *
 * Focus is painted by the plate and the ring, never by a brighter label, and the
 * focused fill is named: left to tv-material3 it was an almost solid white under
 * the row's bright copy, which is the "hard to read what you're focused on"
 * report. A phone presses the row through a foundation clickable - the TV
 * `Surface` is a focus surface, and a finger cannot press it.
 */
@Composable
private fun CalendarEpisodeRow(
    episode: CalendarEpisode,
    watched: Boolean,
    touch: Boolean,
    onClick: () -> Unit,
    requester: FocusRequester?,
    onUpPressed: (() -> Unit)?
) {
    var focused by remember { mutableStateOf(false) }

    if (touch) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(KBShapeCard)
                .background(KBSurface)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            CalendarEpisodeContent(episode = episode, watched = watched)
        }
        return
    }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (focused) KBSurfaceRaised else KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBTextHi,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBTextHi
        ),
        // A ring, not a scale or a glow. The row is full width inside the day
        // list's LazyColumn, which clips to its own bounds - a row that grew
        // would have its ring sheared off flat along both edges (the reason the
        // theme keeps KBFocusChipInset and KBFocusRowInset for lazy rows).
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.Transparent),
                shape = KBShapeCard
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = KBShapeCard
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (requester == null) Modifier else Modifier.focusRequester(requester)
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (
                    onUpPressed != null &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionUp
                ) {
                    onUpPressed()
                    true
                } else {
                    false
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            CalendarEpisodeContent(episode = episode, watched = watched)
        }
    }
}

/**
 * One episode row's content, shared by the two form factors' plates: artwork,
 * the two lines, the state tags and the date.
 *
 * Extracted rather than drawn twice so the string set can never drift between a
 * television and a phone - only the plate underneath them differs.
 */
@Composable
private fun RowScope.CalendarEpisodeContent(episode: CalendarEpisode, watched: Boolean) {
    AsyncImage(
        model = episode.poster ?: episode.backdrop,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(46.dp)
            .height(64.dp)
            .clip(KBShapeCard)
            .background(KBSurfaceRaised)
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.weight(1f)
    ) {
        Text(
            text = episode.showTitle,
            color = if (watched) KBAccent else KBTextHi,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOfNotNull(
                "S%02d · E%02d".format(episode.season, episode.episode),
                episode.episodeTitle
            ).joinToString("  "),
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (episode.isPremiere) CalendarBadge("PREMIERE")
    if (episode.isFinale) CalendarBadge("FINALE")
    if (watched) {
        Text(
            text = "WATCHED",
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
    Text(
        text = formatAirDateLabel(episode.airDate.toString()),
        color = KBTextLo,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier.width(96.dp),
        textAlign = TextAlign.End
    )
}

/** A small all-caps tag: PREMIERE / FINALE. */
@Composable
private fun CalendarBadge(label: String) {
    Text(
        text = label,
        color = KBAccent,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .background(KBAccent.copy(alpha = 0.16f), KBShapeCard)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** "Friday, 24 October" - the heading over the day's list. */
private fun calendarDayLabel(day: LocalDate): String =
    day.format(
        java.time.format.DateTimeFormatter.ofPattern(
            "EEEE, d MMMM",
            java.util.Locale.getDefault()
        )
    )

/** A day cell's height: the day number plus the episode count, both legible. */
private val CALENDAR_CELL_HEIGHT = 48.dp
