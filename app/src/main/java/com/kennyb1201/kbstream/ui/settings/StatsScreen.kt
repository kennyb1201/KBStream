package com.kennyb1201.kbstream.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.history.TopShow
import com.kennyb1201.kbstream.data.history.ViewingStats
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * Settings → Viewing stats: what this profile has FINISHED, counted from its
 * own watch history.
 *
 * It deliberately says "finished", never "time watched": one history row is
 * updated in place per episode, so a rewatch or a scrub overwrites the
 * previous value and exact watch time is not recoverable. What the table does
 * support is honest - the duration of every completed row, the saved position
 * of the rows still open, a count of completions by type, and the streak of
 * consecutive UTC days with a completion. All read-only: nothing here writes.
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = viewModel()
) {
    BackHandler(onBack = onBack)

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KBButton(label = "BACK", onClick = onBack)
            KBPageTitle(text = "VIEWING STATS")
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Finished watches, counted from this profile's history on this device.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (state.loading) {
            Text(
                text = "Reading history…",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }

        // ── Headline numbers ─────────────────────────────────────────────────
        // Four equal tiles, not four fixed-width plates in a Row: the plates
        // sized themselves to their own text, and the row then ran off the
        // right edge of the screen - "Finished runtime" was half off the TV,
        // the one headline a stats screen exists to show. Sharing the width
        // keeps all four on screen at any text size.
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatTile(
                "Titles finished",
                state.finishedTitles.toString(),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                "Episodes finished",
                state.finishedEpisodes.toString(),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                "Movies finished",
                state.finishedMovies.toString(),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                "Finished runtime",
                ViewingStats.formatFinishedRuntime(state.finishedRuntimeMs),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Streak ───────────────────────────────────────────────────────────
        StreakBadge(state.streakDays)

        Spacer(modifier = Modifier.height(20.dp))

        // ── Top shows ────────────────────────────────────────────────────────
        Text(
            text = "TOP SHOWS",
            color = KBAccent,
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (state.topShows.isEmpty()) {
            Text(
                text = "Nothing finished yet — the titles you complete will rank here.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            // The list takes the space that is LEFT under the headline and
            // scrolls inside it. Without the weight it was measured against
            // the whole column, so its last rows sat below the bottom of the
            // screen and the bottom card was cut off; the vertical
            // contentPadding gives the focused card's ring and glow somewhere
            // to grow at the two ends, so the first and last cards are not
            // clipped by the list's own bounds either.
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(state.topShows, key = { it.parentId }) { show ->
                    TopShowRow(show)
                }
            }
        }
    }
}

/** One headline number, drawn in the theme's card plate. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    KBCard(onClick = {}, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                text = value,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * The streak, in the theme's badge shape: the accent-tinted plate the app uses
 * for a state worth calling out. Said in words ("3 day streak"), not an emoji,
 * so it reads at 10 feet on a TV.
 */
@Composable
private fun StreakBadge(days: Int) {
    Surface(
        shape = KBShapeSmall,
        colors = SurfaceDefaults.colors(
            containerColor = KBAccent.copy(alpha = 0.18f),
            contentColor = KBAccent
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (days == 1) "1 day streak" else "$days day streak",
                color = KBAccent,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** One top-show line: poster, name, finished runtime and episodes done. */
@Composable
private fun TopShowRow(show: TopShow) {
    KBCard(onClick = {}) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Surface(
                shape = KBShapeCard,
                colors = SurfaceDefaults.colors(
                    containerColor = KBSurfaceRaised,
                    contentColor = KBTextHi
                )
            ) {
                AsyncImage(
                    model = show.poster
                        ?.takeIf { it.isNotBlank() }
                        ?.let { url ->
                            ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(url)
                                .crossfade(true)
                                .build()
                        },
                    contentDescription = show.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 54.dp, height = 81.dp)
                        .clip(KBShapeCard)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = show.name,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(ViewingStats.formatFinishedRuntime(show.ms))
                        append("  \u00b7  ")
                        append(
                            when (show.done) {
                                0 -> "in progress"
                                1 -> "1 episode finished"
                                else -> "${show.done} episodes finished"
                            }
                        )
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
