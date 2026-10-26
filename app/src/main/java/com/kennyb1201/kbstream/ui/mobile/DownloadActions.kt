package com.kennyb1201.kbstream.ui.mobile

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.download.DownloadEnqueueResult
import com.kennyb1201.kbstream.data.download.DownloadRecord
import com.kennyb1201.kbstream.data.download.DownloadState
import com.kennyb1201.kbstream.data.download.DownloadTarget
import com.kennyb1201.kbstream.data.download.DownloadsManager
import com.kennyb1201.kbstream.data.download.WAITING_FOR_WIFI
import com.kennyb1201.kbstream.ui.components.touch.MobileFilterChip
import com.kennyb1201.kbstream.ui.player.NativePlayerActivity
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.launch

/**
 * The download half of the phone's browsing surfaces: the one place the Streams
 * row, the Detail button and the Downloads screen agree on what a download's
 * state looks like and on what pressing it does.
 *
 * It exists because those three surfaces are three different layouts over one
 * behaviour. Everything that could drift - the label a state shows, whether the
 * action is offered at all, and what launching a downloaded play actually hands
 * the player - is expressed once, here, and read from all three.
 */

/**
 * The active profile's downloads, as a Compose state.
 *
 * [DownloadsManager.records] is the in-memory view the manager keeps up to date
 * from the downloader's own listener, and this refreshes it once as the screen
 * appears: a process that was killed mid-download comes back with the downloader
 * already knowing the transfer, and the first read is what turns that into rows.
 */
@Composable
internal fun rememberDownloadRecords(): List<DownloadRecord> {
    val context = LocalContext.current
    val records by DownloadsManager.records.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { DownloadsManager.refresh(context, persist = false) }
    return records
}

/** The record for one identity, when this phone has one. */
internal fun downloadFor(
    records: List<DownloadRecord>,
    mediaKey: String,
    season: Int?,
    episode: Int?
): DownloadRecord? = records.firstOrNull { record ->
    record.mediaKey == mediaKey && record.season == season && record.episode == episode
}

/** The chip's word for a download's state. */
internal fun downloadActionLabel(record: DownloadRecord): String = when (record.state) {
    DownloadState.DOWNLOADED -> "DOWNLOADED"
    DownloadState.DOWNLOADING -> record.percent?.let { "${it.toInt()}%" } ?: "DOWNLOADING"
    DownloadState.QUEUED -> if (record.waitingForNetwork) "WAITING FOR WI-FI" else "QUEUED"
    DownloadState.PAUSED -> "PAUSED"
    DownloadState.FAILED -> "RETRY"
    DownloadState.DELETED -> "DOWNLOAD"
}

/**
 * Whether any COMPLETED download belongs to one of these title keys.
 *
 * The Library's question, which is coarser than the Detail page's on purpose: a
 * library row is the SHOW, and it should badge when one of its episodes is on
 * the phone - so this matches on the title key and ignores the episode, while
 * the Detail page matches the exact season and episode it is showing.
 */
internal fun downloadsHaveAny(
    records: List<DownloadRecord>,
    mediaKeys: Collection<String>
): Boolean {
    val keys = mediaKeys.filter { it.isNotBlank() }.toSet()
    if (keys.isEmpty()) return false
    return records.any { it.state.isComplete && it.mediaKey in keys }
}

/** The gate's decision for one row, as the screens ask it. */
internal fun downloadGate(
    context: Context,
    stream: Stream?,
    isLiveChannel: Boolean
) = com.kennyb1201.kbstream.data.download.DownloadGates.evaluate(
    stream = stream,
    isLiveChannel = isLiveChannel,
    online = DownloadsManager.isOnline(context)
)

/**
 * Builds the target from the route's own title identity.
 *
 * [parentId] is what the player carries as `parent_id` for this title, which is
 * what makes a downloaded play land on the same watch-history row a streamed one
 * does. The screens pass the id they would hand `Screen.Player`.
 */
internal fun downloadTargetFor(
    parentId: String,
    parentType: String,
    title: String,
    qualityLabel: String,
    episodeLabel: String? = null,
    season: Int? = null,
    episode: Int? = null,
    posterUrl: String? = null,
    episodeStreamId: String? = null
): DownloadTarget = DownloadTarget(
    parentType = parentType,
    parentId = parentId,
    title = title,
    episodeLabel = episodeLabel,
    season = season,
    episode = episode,
    posterUrl = posterUrl,
    qualityLabel = qualityLabel,
    episodeStreamId = episodeStreamId
)

/**
 * What a press on the Download control means, from wherever it was pressed.
 *
 * A FAILED row's press is a RETRY, and a retry re-resolves the row through the
 * normal resolve path instead of retrying a URL that may be dead (spec §7);
 * every other press is a fresh enqueue. Shared by the picker's rows and the
 * Detail button so the two cannot mean different things.
 */
internal fun downloadPress(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    stream: Stream,
    record: DownloadRecord?,
    target: DownloadTarget
) {
    scope.launch {
        if (record?.state == DownloadState.FAILED) {
            val result = DownloadsManager.retry(context.applicationContext, record.id)
            if (result is DownloadEnqueueResult.Refused) {
                Toast.makeText(context.applicationContext, result.reason, Toast.LENGTH_SHORT).show()
            }
        } else {
            startDownload(context, stream, target)
        }
    }
}

/**
 * Asks for a download and reports what happened, in the app's own voice.
 *
 * The suspend call is the manager's; this is only the message - a refusal is
 * always shown, because a press that silently does nothing is the failure this
 * feature can least afford (the viewer is standing in a spot with no signal and
 * needs to know why).
 */
internal suspend fun startDownload(
    context: Context,
    stream: Stream,
    target: DownloadTarget
) {
    val result = DownloadsManager.enqueue(context.applicationContext, stream, target)
    val message = when (result) {
        is DownloadEnqueueResult.Started -> "Downloading ${target.qualityLabel}"
        is DownloadEnqueueResult.Refused -> result.reason
    }
    Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
}

/**
 * Opens a downloaded title in the ExoPlayer engine.
 *
 * The launch is the ordinary playback launch with two additions:
 * [DownloadsManager.EXTRA_DOWNLOAD_ID], which tells the player to read the bytes
 * out of the download cache and to refuse the network entirely, and the title
 * metadata the player would have been given anyway - so resume rows, watch
 * history, scrobbling and the end-of-episode panels behave identically to a
 * streamed play (spec §6).
 *
 * `start_position_ms` is deliberately NOT set: the player's own
 * `PlaybackResume` reads the saved position from the same history row a streamed
 * play would have written, which is what makes pausing a download and reopening
 * it resume where it stopped, network or not.
 */
internal fun playDownloaded(context: Context, record: DownloadRecord) {
    if (!DownloadsManager.isPlayable(context, record.id)) {
        // The bytes are gone - cleared storage, a partial delete. Say so and
        // offer the row a Re-download; never open a player that would fall back
        // to streaming, which is the one thing that would defeat the feature.
        DownloadsManager.markMissing(context, record.id)
        Toast.makeText(context, DownloadMissingMessage, Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(context, NativePlayerActivity::class.java).apply {
        putExtra("stream_url", record.sourceUrl)
        putExtra(DownloadsManager.EXTRA_DOWNLOAD_ID, record.id)
        putExtra("parent_id", record.parentId)
        putExtra("parent_type", record.parentType)
        record.season?.let { putExtra("season", it) }
        record.episode?.let { putExtra("episode", it) }
        record.episodeStreamId?.let { putExtra("episode_stream_id", it) }
        record.episodeLabel?.let { putExtra("episode_title", it) }
        putExtra("item_name", record.title)
        record.posterUrl?.let { putExtra("item_poster", it) }
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "Could not open this download", Toast.LENGTH_SHORT).show() }
}

/** The one wording for a download whose bytes are gone, shared with the list. */
internal const val DownloadMissingMessage = "This download is no longer on this phone"

/**
 * The badge a downloaded title wears.
 *
 * Drawn as the accent plate rather than as another chip: a download is a
 * property of the title (it is here, offline), not a piece of its metadata, and
 * it has to read as such at a glance from across a phone.
 */
@Composable
internal fun DownloadedBadge(modifier: Modifier = Modifier) {
    Text(
        text = "DOWNLOADED",
        color = KBVoid,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(KBAccent, KBShapePanel)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * One source row's Download action.
 *
 * A chip, not a full-width button: it sits on the source card beside the row's
 * own text, and the card's whole surface is already the tap target for playing
 * it. [label] is the state's own word (see [downloadActionLabel]), so the same
 * control carries "DOWNLOAD", the percentage, and "DOWNLOADED".
 */
@Composable
internal fun MobileDownloadAction(
    label: String,
    disabledReason: String?,
    onClick: () -> Unit
) {
    if (disabledReason != null) {
        // Disabled WITH its reason, the DRM case: the row still says Download is
        // a thing, and the reason is what the viewer needs to read. Not
        // offering the chip at all here would make a protected title look like
        // a bug (spec §5 - this is the one gate that must be learnable rather
        // than invisible).
        Text(
            text = "$label · $disabledReason",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        return
    }
    MobileFilterChip(label = label, selected = false, onClick = onClick)
}

/**
 * The row auto-play would start, by auto-play's own rules.
 *
 * The picker and the Detail page's Download button both ask this, deliberately
 * through the SAME call: the spec's rule is that a download is the row auto-play
 * would have played (spec §2.3, §4.2), and two implementations of "what would
 * auto-play pick" is exactly how the downloaded row and the played row stop
 * being the same row.
 */
internal fun autoplayPickFor(
    context: Context,
    sources: List<Stream>,
    season: Int?,
    episode: Int?,
    runtimeMinutes: Int?
): Stream? = com.kennyb1201.kbstream.domain.streamengine.AutoPlayQuality.cappedPick(
    pick = com.kennyb1201.kbstream.domain.streamengine.EpisodeMatch.autoplayPick(
        sources,
        season,
        episode,
        runtimeMinutes
    ),
    candidates = sources,
    season = season,
    episode = episode,
    runtimeMinutes = runtimeMinutes,
    cap = com.kennyb1201.kbstream.data.settings.AppPreferences
        .getMaxAutoPlayQuality(context)
)

/** The line under a download's title on the Downloads screen. */
internal fun downloadStatusLine(record: DownloadRecord): String =
    if (record.waitingForNetwork) {
        "${record.state.label(record.percent)} · $WAITING_FOR_WIFI"
    } else {
        record.stateLabel()
    }

/** The colour a state's word is drawn in, so the list scans. */
internal fun downloadStateColor(record: DownloadRecord) = when (record.state) {
    DownloadState.FAILED -> KBAccent
    DownloadState.DOWNLOADED -> KBAccent
    else -> KBTextHi
}

/** The muted colour every secondary line on this feature's surfaces uses. */
internal val DownloadSecondaryColor = KBTextLo

/** The chip text for a source row whose Download action is offered but waiting on a resolve. */
internal const val DownloadLabel = "DOWNLOAD"

/**
 * The Download control itself, in every state it has.
 *
 * One composable for all four surfaces that offer it, because the states are a
 * policy and not a layout: a row that is downloading offers Pause and Cancel, a
 * finished one offers Play downloaded and Delete, and the DRM gate is the only
 * case that shows a disabled control with a reason. Two copies of that (the
 * Streams row's and the Detail button's) would be two chances to offer the
 * wrong press on a file that is not here.
 *
 * @param record this title's download, when there is one - and it is the whole
 *   state machine: null means nothing has been downloaded here yet.
 * @param idleLabel what to say when there is no record yet. "DOWNLOAD" on a
 *   source row, and "Connect to download" where the device is offline.
 * @param disabledReason non-null for the one gate that shows a disabled
 *   control with its reason (DRM).
 * @param inert true when the press must do nothing at all rather than start
 *   anything - the offline case, where the label has already said why.
 */
@Composable
internal fun MobileDownloadControl(
    record: DownloadRecord?,
    idleLabel: String,
    disabledReason: String?,
    inert: Boolean,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    if (disabledReason != null) {
        MobileDownloadAction(
            label = idleLabel,
            disabledReason = disabledReason,
            onClick = {}
        )
        return
    }
    if (record == null) {
        MobileDownloadAction(label = idleLabel, disabledReason = null, onClick = onDownload)
        return
    }
    when (record.state) {
        DownloadState.DOWNLOADED -> {
            // The bytes are here: the primary press plays them, and the second
            // chip is the way out of the download.
            MobileFilterChip(label = "PLAY DOWNLOADED", selected = false, onClick = onPlay)
            MobileFilterChip(label = "DELETE", selected = false, onClick = onDelete)
        }

        DownloadState.PAUSED -> {
            MobileFilterChip(label = "RESUME", selected = false, onClick = onResume)
            MobileFilterChip(label = "CANCEL", selected = false, onClick = onDelete)
        }

        DownloadState.FAILED -> {
            // Retry, never a Play - a failed download has no bytes to play. The
            // press re-resolves through the caller (spec §7).
            MobileFilterChip(label = "RETRY", selected = false, onClick = onDownload)
            MobileFilterChip(label = "DELETE", selected = false, onClick = onDelete)
        }

        DownloadState.QUEUED, DownloadState.DOWNLOADING -> {
            if (inert) {
                // Offline: said, not offered. The state words still show, so a
                // queued download is visible from the picker.
                Text(
                    text = downloadActionLabel(record),
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                return
            }
            MobileFilterChip(
                label = downloadActionLabel(record),
                selected = false,
                onClick = {}
            )
            MobileFilterChip(label = "CANCEL", selected = false, onClick = onDelete)
        }

        DownloadState.DELETED -> MobileDownloadAction(
            label = idleLabel,
            disabledReason = null,
            onClick = onDownload
        )
    }
}
