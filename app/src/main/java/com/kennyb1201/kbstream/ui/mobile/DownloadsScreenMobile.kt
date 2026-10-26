package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.data.download.DownloadRecord
import com.kennyb1201.kbstream.data.download.DownloadState
import com.kennyb1201.kbstream.data.download.DownloadsManager
import com.kennyb1201.kbstream.data.download.formatDownloadBytes
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.MobileFilterChip
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import com.kennyb1201.kbstream.ui.theme.OswaldFamily
import kotlinx.coroutines.launch

/**
 * Downloads, for a finger: one row per download on this phone for the profile on
 * screen, and the header's storage figure (spec §4.3).
 *
 * Reached from Settings' Playback pane and rendered over it, the way the panes
 * themselves are - one screen pushed at a time, Back returns to what opened it.
 * Everything it shows comes from [DownloadsManager]: records for the active
 * profile, merged with what the downloader itself is doing, so a transfer that
 * is running right now shows its own percentage without this screen holding a
 * copy of anything.
 *
 * The empty state is the spec's own sentence, plus the storage consequence it
 * asks to be stated here rather than discovered later: downloads are app-private
 * files, so no gallery or Files app will ever list them, and uninstalling the app
 * takes them with it.
 */
@Composable
fun DownloadsScreenMobile(onBack: () -> Unit) {
    MobileTheme {
        val context = LocalContext.current
        val records = rememberDownloadRecords()
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            DownloadsHeader(
                totalBytes = DownloadsManager.totalBytes(context),
                count = records.count { it.state.isComplete },
                onBack = onBack
            )

            if (records.isEmpty()) {
                DownloadsEmptyState()
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = records, key = { it.id }) { record ->
                    DownloadRow(
                        record = record,
                        onPlay = { playDownloaded(context, record) },
                        onPause = { DownloadsManager.pause(context, record.id) },
                        onResume = { DownloadsManager.resume(context, record.id) },
                        onDelete = { DownloadsManager.delete(context, record.id) },
                        onRetry = {
                            // Retry re-resolves the row rather than replaying a
                            // dead URL (spec §7), so it is a suspend call and
                            // the message is the same one a fresh press gets.
                            scope.launch {
                                DownloadsManager.retry(context, record.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

/** The screen's own head: back, the title, and the storage this feature is using. */
@Composable
private fun DownloadsHeader(totalBytes: Long, count: Int, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MobileBackButton(onBack = onBack)
                Text(
                    text = "DOWNLOADS",
                    color = KBAccent,
                    fontFamily = OswaldFamily,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                // Completed downloads only: a partial's bytes are on the disk
                // for now, but counting them would make this figure disagree
                // with what deleting a row gives back (spec §7).
                text = if (count == 0) {
                    "Nothing downloaded yet"
                } else {
                    "$count on this phone · ${formatDownloadBytes(totalBytes)}"
                },
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DownloadsEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .background(KBSurface, KBShapePanel)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Downloads live on this phone only. They don't sync, " +
                    "and uninstalling removes them.",
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "They're kept inside the app, so they never show up in " +
                    "your gallery or Files, and they're stored against this " +
                    "profile.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * One download.
 *
 * The row is a title, the quality the viewer picked for it, its state and its
 * size, with the actions that state allows. A FAILED row offers Retry and not
 * Play, which is the whole difference between a row whose bytes are here and one
 * whose are not.
 */
@Composable
private fun DownloadRow(
    record: DownloadRecord,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit
) {
    KBCard(
        onClick = {
            // The card's own tap is the row's primary action, so it is whatever
            // the state makes primary: play what is here, or resume what is not.
            when (record.state) {
                DownloadState.DOWNLOADED -> onPlay()
                DownloadState.PAUSED -> onResume()
                DownloadState.FAILED -> onRetry()
                else -> Unit
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurfaceRaised.copy(alpha = 0.72f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = record.title,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    record.episodeLabel,
                    record.qualityLabel.takeIf { it.isNotBlank() },
                    record.sizeLabel()
                ).joinToString(" · "),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (record.state.isPartial) {
                DownloadProgressBar(record.progressFraction())
            }
            Text(
                text = downloadStatusLine(record),
                color = downloadStateColor(record),
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (record.state) {
                    DownloadState.DOWNLOADED -> {
                        MobileFilterChip(label = "PLAY", selected = false, onClick = onPlay)
                        MobileFilterChip(label = "DELETE", selected = false, onClick = onDelete)
                    }

                    DownloadState.DOWNLOADING, DownloadState.QUEUED -> {
                        if (record.state == DownloadState.DOWNLOADING) {
                            MobileFilterChip(label = "PAUSE", selected = false, onClick = onPause)
                        }
                        MobileFilterChip(label = "CANCEL", selected = false, onClick = onDelete)
                    }

                    DownloadState.PAUSED -> {
                        MobileFilterChip(label = "RESUME", selected = false, onClick = onResume)
                        MobileFilterChip(label = "CANCEL", selected = false, onClick = onDelete)
                    }

                    DownloadState.FAILED -> {
                        // Retry, never a Play: the bytes for a failed row are
                        // not here, and re-resolving is what a retry means.
                        MobileFilterChip(label = "RETRY", selected = false, onClick = onRetry)
                        MobileFilterChip(label = "DELETE", selected = false, onClick = onDelete)
                    }

                    DownloadState.DELETED -> MobileFilterChip(
                        label = "REMOVE",
                        selected = false,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

/**
 * A transfer's progress, drawn by hand.
 *
 * Two plates rather than a Material progress indicator, deliberately: this app's
 * phone surface is hand-drawn throughout (see the player's own chrome), and a
 * bar whose only job is "how much of this file is here" does not need a
 * component with a theme of its own. A null fraction draws the empty track, which
 * is the honest picture for a download whose length the downloader has not
 * established yet.
 */
@Composable
private fun DownloadProgressBar(fraction: Float?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(KBSurface, KBShapePanel)
    ) {
        if (fraction != null && fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(KBAccent, KBShapePanel)
            )
        }
    }
}
