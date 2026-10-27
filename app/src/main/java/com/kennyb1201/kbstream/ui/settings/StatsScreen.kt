package com.kennyb1201.kbstream.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.history.TopShow
import com.kennyb1201.kbstream.data.history.ViewingStats
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurface
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
 * consecutive days with a completion (the viewer's own days, see
 * [ViewingStats.streakDays]). All read-only: nothing here writes.
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = viewModel()
) {
    // The phone entry: the same view model, the same numbers, laid out for a
    // finger. One branch; the TV page below is unchanged (see
    // StatsScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        com.kennyb1201.kbstream.ui.mobile.StatsScreenMobile(
            onBack = onBack,
            viewModel = viewModel
        )
        return
    }

    BackHandler(onBack = onBack)

    val state by viewModel.state.collectAsStateWithLifecycle()

    // A fresh read on entry, and again whenever the active profile changes.
    // StatsViewModel is activity-scoped (a plain viewModel()), so its numbers
    // used to be whatever its single process-lifetime load produced: a screen
    // reopened after another episode was finished never showed it, and after a
    // profile switch it showed the PREVIOUS profile's numbers under the "this
    // profile's history" subtitle (stats C1).
    val activeProfileId = ProfileManager.activeProfile
        .collectAsStateWithLifecycle().value?.id
    LaunchedEffect(activeProfileId) { viewModel.load() }

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

        // A read that threw used to leave the screen here for good, with
        // nothing to press but BACK - the coroutine died with the loading flag
        // still set (stats C6). Name the failure and offer the one press that
        // can clear it.
        state.error?.let { message ->
            Text(
                text = message,
                color = KBDanger,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(10.dp))
            KBButton(label = "RETRY", onClick = { viewModel.load() })
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
        // Drawn only when there IS one: "0 day streak" on a fresh profile read
        // as a figure about nothing watched, under a tile row already saying 0
        // (stats I1).
        if (state.streakDays > 0) {
            StreakBadge(state.streakDays)
            Spacer(modifier = Modifier.height(20.dp))
        }

        // ── Top titles ───────────────────────────────────────────────────────
        // "Titles", not "shows": the ranking is every finished title, movies
        // included - the tiles count movies separately, and the empty message
        // below promises "the titles you complete will rank here", so a header
        // calling them all shows contradicted both (stats C2).
        Text(
            text = "TOP TITLES",
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
    // A plain plate, not a card: a headline number is read, never pressed. A
    // KBCard with an empty onClick gave each tile a focus ring, a glow and a
    // full D-pad stop that answered OK with nothing - the same dead stop as the
    // VERSION row in Settings.
    Surface(
        shape = KBShapeCard,
        colors = SurfaceDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi
        ),
        modifier = modifier
    ) {
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

/**
 * One ranked line: poster, name, finished runtime and what was finished.
 *
 * Unlike the plates above it, this row IS focusable. A readout that never takes
 * focus is right for four tiles, but this list is a LazyColumn: with no
 * focusable node below BACK the D-pad could not reach a row past the fold, so
 * the list never scrolled and the bottom of a ten-row ranking was both
 * invisible and unreachable (stats C4). Focus scrolls the list to the row; OK
 * does nothing, because there is nothing here to open.
 */
@Composable
private fun TopShowRow(show: TopShow) {
    var focused by remember { mutableStateOf(false) }
    // A movie is a finished title like any other and ranks here, so it has to
    // be named as one: every row used to be worded as episodes, which filed a
    // film under "1 episode finished" (stats C2).
    val isMovie = show.type.equals("movie", ignoreCase = true)
    Surface(
        shape = KBShapeCard,
        colors = SurfaceDefaults.colors(
            containerColor = if (focused) KBSurfaceRaised else KBSurface,
            contentColor = KBTextHi
        ),
        // Always 2dp wide, transparent until focused: swapping the border in and
        // out with focus would resize every row as the D-pad moved down the
        // list.
        border = Border(
            border = BorderStroke(
                width = 2.dp,
                color = KBAccent.copy(alpha = if (focused) 1f else 0f)
            ),
            shape = KBShapeCard
        ),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
    ) {
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
                            when {
                                show.done == 0 -> "in progress"
                                isMovie -> "movie finished"
                                show.done == 1 -> "1 episode finished"
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
