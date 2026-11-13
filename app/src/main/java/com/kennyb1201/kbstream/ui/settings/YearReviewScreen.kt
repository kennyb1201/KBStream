package com.kennyb1201.kbstream.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.history.YearReview
import com.kennyb1201.kbstream.data.history.YearTitle
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import java.time.format.DateTimeFormatter

/**
 * The Year in review screen (spec 2026-11-13, F1), one layout for TV and phone.
 *
 * One layout rather than the two-file pattern the Library and Stats pages use:
 * everything here is a short, read-only column of figures, and a second copy
 * would be a second place for the approximation wording to drift from the number
 * it qualifies. The only form-factor difference is padding - a D-pad scrolls the
 * same LazyColumn a finger does.
 *
 * The year comes from the route when one was picked (Library / Settings), and
 * the screen's own picker then owns it. Both entry points exist by design: the
 * Home card is seasonal, and a review must stay reachable in June.
 */
@Composable
fun YearReviewScreen(
    onBack: () -> Unit,
    year: Int? = null,
    viewModel: YearReviewViewModel = viewModel()
) {
    BackHandler(onBack = onBack)

    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeProfileId = ProfileManager.activeProfile
        .collectAsStateWithLifecycle().value?.id
    val context = LocalContext.current

    // A fresh read on entry, on a new year and whenever the active profile
    // changes: a kids profile must never inherit the previous profile's year.
    LaunchedEffect(activeProfileId, year) { viewModel.load(year) }

    val edge = if (com.kennyb1201.kbstream.FormFactor.isTv) 28.dp else 16.dp
    val review = state.review

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = edge, vertical = if (com.kennyb1201.kbstream.FormFactor.isTv) 20.dp else 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KBButton(label = "BACK", onClick = onBack)
            KBPageTitle(text = "YOUR ${state.year} IN REVIEW")
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "This profile's own history, on this device.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )

        // ── The year picker ──────────────────────────────────────────────
        // Only the years this profile has something in, plus this year - a
        // picker of every year since the app existed would be a list of empty
        // states.
        if (state.years.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.years, key = { it }) { candidate ->
                    YearChip(
                        year = candidate,
                        selected = candidate == state.year,
                        onClick = { viewModel.load(candidate) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when {
            state.loading -> {
                Text(
                    text = "Reading history…",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            state.error != null -> {
                Text(
                    text = state.error.orEmpty(),
                    color = KBDanger,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                KBButton(label = "RETRY", onClick = { viewModel.load(state.year) })
            }

            review == null -> {
                // Nothing to draw and nothing failed: only possible between a
                // profile switch and its read landing.
                Text(
                    text = "Reading history…",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            review.empty -> {
                EmptyYearReview(state.year)
            }

            else -> {
                ReviewBody(
                    review = review,
                    onShare = {
                        YearReviewShareCard.share(
                            context = context,
                            review = review,
                            hoursLabel = hoursLabel(review.approxRuntimeMs),
                            accentArgb = KBAccent.toArgb()
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ReviewBody(
    review: YearReview,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item(key = "hours") {
            Surface(
                shape = KBShapeCard,
                colors = SurfaceDefaults.colors(containerColor = KBSurface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        // The mark and the word "approximate" are not decoration:
                        // one history row per title means a rewatch or a scrub
                        // overwrites the previous value, so this is the runtime of
                        // what was finished (plus what was left part-way), never a
                        // stopwatch.
                        text = "≈ ${hoursLabel(review.approxRuntimeMs)} watched",
                        color = KBTextHi,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Approximate: history keeps one row per title, so this is the " +
                            "runtime of what you finished plus where you stopped - not a stopwatch.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item(key = "counts") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ReviewTile("Episodes", review.episodesFinished.toString(), Modifier.weight(1f))
                ReviewTile("Movies", review.moviesFinished.toString(), Modifier.weight(1f))
                ReviewTile("Shows", review.showsFinished.toString(), Modifier.weight(1f))
            }
        }

        item(key = "days") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ReviewTile(
                    "Busiest day",
                    review.busiestDay?.let { day ->
                        day.date.format(DateTimeFormatter.ofPattern("MMM d")) +
                            " · " + hoursLabel(day.runtimeMs)
                    } ?: "—",
                    Modifier.weight(1f)
                )
                ReviewTile(
                    "Longest streak",
                    if (review.longestStreakDays > 0) {
                        "${review.longestStreakDays} days"
                    } else {
                        "—"
                    },
                    Modifier.weight(1f)
                )
            }
        }

        if (review.topShows.isNotEmpty()) {
            item(key = "topshows-header") { SectionHeader("TOP SHOWS · BY EPISODES FINISHED") }
            items(review.topShows, key = { "show:${it.parentId}" }) { show ->
                TitleRow(show = show, suffix = plural(show.episodes, "episode"))
            }
        }

        if (review.recentMovies.isNotEmpty()) {
            // "Most recent", never "most watched": a movie completes once, so
            // there is no count to rank by and the table has no play counter.
            item(key = "movies-header") { SectionHeader("MOVIES · MOST RECENT") }
            items(review.recentMovies, key = { "movie:${it.parentId}" }) { movie ->
                TitleRow(show = movie, suffix = "")
            }
        }

        if (review.firstFinished != null || review.lastFinished != null) {
            item(key = "endpoints") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    review.firstFinished?.let { first ->
                        EndpointLine("First finished", first)
                    }
                    review.lastFinished?.let { last ->
                        EndpointLine("Last finished", last)
                    }
                }
            }
        }

        item(key = "share") {
            KBButton(label = "SHARE THIS YEAR") { onShare() }
        }

        item(key = "note") {
            Text(
                text = "Counted from completed watches only. Titles you marked watched " +
                    "in one tap are left out: a mark is a verdict, not a measured watch.",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun EmptyYearReview(year: Int) {
    // An honest empty state: a year with nothing in it is not an error, and it
    // must not be dressed up with the current year's numbers.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Nothing watched in $year.",
            color = KBTextHi,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Finish something and it will appear here - the review is built from " +
                "this profile's own history.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = KBAccent,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ReviewTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = KBShapeCard,
        colors = SurfaceDefaults.colors(containerColor = KBSurface),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = label.uppercase(),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = KBTextHi,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TitleRow(show: YearTitle, suffix: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 66.dp)
                .clip(KBShapeSmall)
                .background(KBSurface)
        ) {
            AsyncImage(
                model = show.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = show.name,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (suffix.isNotEmpty()) {
            Text(
                text = suffix,
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun EndpointLine(label: String, title: YearTitle) {
    val whenText = title.lastAtMs.takeIf { it > 0L }?.let { stamp ->
        runCatching {
            java.time.Instant.ofEpochMilli(stamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ofPattern("MMM d"))
        }.getOrNull()
    }
    Text(
        text = "$label · ${title.name}" + (whenText?.let { " · $it" } ?: ""),
        color = KBTextLo,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun YearChip(year: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) KBAccent.copy(alpha = 0.2f) else KBSurface
        )
    ) {
        Text(
            text = year.toString(),
            color = if (selected) KBAccent else KBTextHi,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/** "≈ 214 hours" / "≈ 45 minutes" - the unit the review speaks in. */
internal fun hoursLabel(ms: Long): String {
    val hours = ms / 3_600_000L
    if (hours > 0) return if (hours == 1L) "1 hour" else "$hours hours"
    val minutes = ms / 60_000L
    return if (minutes == 1L) "1 minute" else "$minutes minutes"
}

private fun plural(count: Int, noun: String): String =
    "$count $noun" + if (count == 1) "" else "s"
