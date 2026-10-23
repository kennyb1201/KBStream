package com.kennyb1201.kbstream.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * list and Up comes back to the selected cell) and what a phone adds is a tap.
 * Everything is built on the TV `Surface`, which carries focus and activation as
 * one target and answers a touch press as readily as a D-pad OK - so the phone
 * gets the same controls without a second layout to keep in step.
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
    val listFocusRequester = remember { FocusRequester() }

    // The day the D-pad should be on, once the month it belongs to has landed.
    var landDay by remember { mutableStateOf(LocalDate.now()) }
    var landRequest by remember { mutableStateOf(if (takeInitialFocus) 1 else 0) }

    LaunchedEffect(Unit) { viewModel.open() }

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

    Column(modifier = modifier.fillMaxSize()) {
        CalendarHeader(
            month = state.month,
            onPrev = { page(-1) },
            onNext = { page(1) },
            onToday = {
                landDay = LocalDate.now()
                landRequest++
                viewModel.goToToday()
            }
        )

        CalendarWeekdayRow()

        CalendarGrid(
            month = state.month,
            days = state.days,
            selected = state.selected,
            today = LocalDate.now(),
            touch = touch,
            dayRequesters = dayRequesters,
            onSelect = viewModel::select,
            onPrevMonth = { page(-1) },
            onNextMonth = { page(1) },
            onExitBottom = {
                if (!touch) {
                    runCatching { listFocusRequester.requestFocus() }
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        CalendarDayList(
            state = state,
            touch = touch,
            listFocusRequester = listFocusRequester,
            onUpToGrid = {
                if (!touch) {
                    runCatching {
                        dayRequesters[state.selected]?.requestFocus() ?: false
                    }
                }
            },
            onOpenDetails = onOpenDetails
        )
    }
}

/** The month header: prev, the month, next, and the way back to today. */
@Composable
private fun CalendarHeader(
    month: YearMonth,
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
        CalendarChip(label = "‹", description = "Previous month", onClick = onPrev)
        Text(
            text = calendarMonthLabel(month),
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(180.dp)
        )
        CalendarChip(label = "›", description = "Next month", onClick = onNext)
        Spacer(modifier = Modifier.weight(1f))
        CalendarChip(label = "TODAY", onClick = onToday)
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
 */
@Composable
private fun CalendarChip(
    label: String,
    description: String? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (focused) KBSurfaceRaised else KBSurface,
            contentColor = if (focused) KBTextHi else KBTextLo
        ),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (description == null) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = description }
                }
            )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
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
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onExitBottom: () -> Unit
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
                                requester = if (touch) null else dayRequesters[day],
                                onSelect = onSelect,
                                onPrevMonth = onPrevMonth,
                                onNextMonth = onNextMonth,
                                onExitBottom = onExitBottom
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
    requester: FocusRequester?,
    onSelect: (LocalDate) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onExitBottom: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = { onSelect(day) },
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                selected -> KBAccent.copy(alpha = 0.28f)
                focused -> KBSurfaceRaised
                else -> KBSurface
            },
            contentColor = when {
                selected || isToday -> KBAccent
                else -> KBTextHi
            }
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
                if (touch || event.type != KeyEventType.KeyDown) {
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

                        event.key == Key.DirectionDown && isLastRow -> {
                            onExitBottom()
                            true
                        }

                        else -> false
                    }
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Text(
                text = day.dayOfMonth.toString(),
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
}

/**
 * The selected day's episodes, under the grid.
 *
 * The three empty states are deliberately different sentences: no tracked shows
 * at all (naming the sources that would fill it), a month with nothing in it,
 * and a day with nothing in it. A day with many episodes scrolls HERE and only
 * here - the grid above never moves.
 */
@Composable
private fun CalendarDayList(
    state: LibraryCalendarUiState,
    touch: Boolean,
    listFocusRequester: FocusRequester,
    onUpToGrid: () -> Unit,
    onOpenDetails: (String) -> Unit
) {
    if (!state.hasShows && !state.loading) {
        CalendarNotice(
            text = "Nothing to schedule yet. Watch something, or connect " +
                "Simkl, MDBList or Trakt, and its episodes appear here."
        )
        return
    }

    if (state.loading) {
        CalendarNotice("Loading this month…")
        return
    }

    if (state.monthIsEmpty) {
        CalendarNotice("Nothing scheduled this month.")
        return
    }

    val episodes = state.episodesOn(state.selected)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(listFocusRequester)
            .onPreviewKeyEvent { event ->
                if (!touch && event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionUp
                ) {
                    onUpToGrid()
                    true
                } else {
                    false
                }
            }
    ) {
        Text(
            text = calendarDayLabel(state.selected),
            color = KBTextLo,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 6.dp)
        )

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
            contentPadding = PaddingValues(bottom = 20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = episodes,
                key = { episode ->
                    "${episode.showId}|s${episode.season}|e${episode.episode}"
                }
            ) { episode ->
                CalendarEpisodeRow(
                    episode = episode,
                    watched = state.isWatched(episode),
                    onClick = { onOpenDetails(episode.openId) }
                )
            }
        }
    }
}

/** A plain line of copy where a list would be. */
@Composable
private fun CalendarNotice(text: String) {
    Text(
        text = text,
        color = KBTextLo,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp)
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
 */
@Composable
private fun CalendarEpisodeRow(
    episode: CalendarEpisode,
    watched: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (focused) KBSurfaceRaised else KBSurface,
            contentColor = if (focused) KBTextHi else KBTextHi
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
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
    }
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

/** A day cell's height: "31" plus a marker line, at TV density. */
private val CALENDAR_CELL_HEIGHT = 42.dp
