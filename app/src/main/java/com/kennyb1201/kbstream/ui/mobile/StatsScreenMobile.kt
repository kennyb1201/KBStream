package com.kennyb1201.kbstream.ui.mobile

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.history.TopShow
import com.kennyb1201.kbstream.data.history.ViewingStats
import com.kennyb1201.kbstream.ui.settings.StatsViewModel
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/*
 * Viewing stats, for a finger.
 *
 * Same [StatsViewModel], same numbers, same wording: the phone only changes the
 * layout. The TV screen puts four headline plates in one row because a
 * television is wide; a 411dp phone turns that row into two rows of two, so
 * "Finished runtime" is never squeezed to two characters. Nothing here is
 * pressable except BACK - a headline number is read, and the TV page's
 * non-focusable plates are matched by plain plates (no tap target that answers
 * with nothing).
 *
 * The table still says "finished", never "time watched", for the reason the TV
 * screen documents: one history row per episode is overwritten in place, so a
 * rewatch is not recoverable.
 */

@Composable
fun StatsScreenMobile(
    onBack: () -> Unit,
    viewModel: StatsViewModel = viewModel()
) {
    BackHandler(onBack = onBack)

    val state by viewModel.state.collectAsStateWithLifecycle()

    MobileTheme {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            item(key = "header") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MobileBackButton(onBack = onBack)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "VIEWING STATS",
                            color = KBAccent,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = "Finished watches, counted from this profile's history " +
                            "on this device.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            if (state.loading) {
                item(key = "loading") {
                    Text(
                        text = "Reading history…",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                // ── Headline numbers ─────────────────────────────────────
                // Two rows of two rather than one row of four: the TV's four
                // equal plates would leave each value about 80dp wide on a
                // phone, which fits a count but not the runtime string.
                item(key = "tiles") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            StatTileMobile(
                                label = "Titles finished",
                                value = state.finishedTitles.toString(),
                                modifier = Modifier.weight(1f)
                            )
                            StatTileMobile(
                                label = "Episodes finished",
                                value = state.finishedEpisodes.toString(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            StatTileMobile(
                                label = "Movies finished",
                                value = state.finishedMovies.toString(),
                                modifier = Modifier.weight(1f)
                            )
                            StatTileMobile(
                                label = "Finished runtime",
                                value = ViewingStats.formatFinishedRuntime(state.finishedRuntimeMs),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                item(key = "streak") {
                    StreakBadgeMobile(state.streakDays)
                }

                item(key = "top_shows_header") {
                    Text(
                        text = "TOP SHOWS",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }

                if (state.topShows.isEmpty()) {
                    item(key = "top_shows_empty") {
                        Text(
                            text = "Nothing finished yet — the titles you complete " +
                                "will rank here.",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    items(state.topShows, key = { it.parentId }) { show ->
                        TopShowRowMobile(show)
                    }
                }
            }
        }
    }
}

/** One headline number: a plain plate, because a number is read, not pressed. */
@Composable
private fun StatTileMobile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(KBShapeCard)
            .background(KBSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = value,
            color = KBTextHi,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1
        )
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * The streak, said in words rather than an emoji, on the accent-tinted plate
 * the TV badge uses.
 */
@Composable
private fun StreakBadgeMobile(days: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(KBShapeChip)
            .background(KBAccent.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = if (days == 1) "1 day streak" else "$days day streak",
            color = KBAccent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** One top-show line: poster, name, finished runtime and episodes done. */
@Composable
private fun TopShowRowMobile(show: TopShow) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(KBShapeCard)
            .background(KBSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .size(width = 54.dp, height = 81.dp)
                .clip(KBShapeCard)
                .background(KBSurfaceRaised)
        ) {
            val poster = show.poster
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(poster)
                        .crossfade(true)
                        .build(),
                    contentDescription = show.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(KBShapeCard)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = show.name,
                color = KBTextHi,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}
