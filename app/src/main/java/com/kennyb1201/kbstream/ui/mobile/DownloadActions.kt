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
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.domain.streamengine.AutoplayPick
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
 * picker's long-press menu (the phone's only download action) and the Downloads
 * screen agree on what a download's state looks like and on what pressing it
 * does. The Detail page reads nothing from here but [DownloadedBadge]'s record
 * lookup - it shows that a title is here, never how to start one.
 *
 * It exists because those surfaces are different layouts over one behaviour.
 * Everything that could drift - the label a state shows, whether the action is
 * offered at all, and what launching a downloaded play actually hands the
 * player - is expressed once, here, and read from all of them.
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
 * What a press on a Download action means, from wherever it was pressed - the
 * phone's streams menu today.
 *
 * A FAILED row's press is a RETRY, and a retry re-resolves the row through the
 * normal resolve path instead of retrying a URL that may be dead (spec §7);
 * every other press is a fresh enqueue.
 *
 * The press is acknowledged HERE, before the suspend work starts. Enqueueing is
 * not instant even for a URL row - a hash row resolves through TorBox first, and
 * every row probes its length - so a menu that only spoke when the manager
 * returned could sit silent for seconds and read as a press that did nothing.
 * The manager's own Toast still lands on completion (or on its refusal), so the
 * immediate notice is the acknowledgement and the second one is the outcome -
 * [retryDownload] is what speaks it on the retry side, for this menu and for the
 * Downloads screen alike.
 */
internal fun downloadPress(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    stream: Stream,
    record: DownloadRecord?,
    target: DownloadTarget
) {
    val retrying = record?.state == DownloadState.FAILED
    Toast.makeText(
        context.applicationContext,
        if (retrying) "Retrying download…" else "Preparing download…",
        Toast.LENGTH_SHORT
    ).show()
    scope.launch {
        if (record?.state == DownloadState.FAILED) {
            retryDownload(context, record)
        } else {
            startDownload(context, stream, target)
        }
    }
}

/**
 * Retries one download and says why when the retry is refused.
 *
 * The mirror of [startDownload] for the other press a download row offers, and
 * it exists because the two surfaces that offer a Retry have to answer the same
 * way: a retry re-resolves the row, so it can be refused for reasons the store
 * cannot show - offline ("Connect to download"), an unresolvable hash row, no
 * playable link, a record that is no longer listed - and a refusal that says
 * nothing is exactly the silence this feature can least afford. Only a REFUSAL
 * is announced: a retry that starts turns the row back into Queued, which the
 * list already shows.
 */
internal suspend fun retryDownload(context: Context, record: DownloadRecord) {
    val result = DownloadsManager.retry(context.applicationContext, record.id)
    if (result is DownloadEnqueueResult.Refused) {
        Toast.makeText(context.applicationContext, result.reason, Toast.LENGTH_SHORT).show()
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
 * One source row's Download action, as a chip.
 *
 * A chip, not a full-width button: the card's whole surface is already the tap
 * target for playing it, so the action needs its own small target. [label] is
 * the state's own word (see [downloadActionLabel]). The phone offers this in a
 * long-press menu now (see [downloadPlan]); this is the inline shape, kept for a
 * surface that wants the actions beside the row's text.
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
 * The picker asks this for its own auto-play start, deliberately through the
 * SAME call its downloadable rows are drawn from: the spec's rule is that a
 * download is the row auto-play would have played (spec §2.3, §4.2), and two
 * implementations of "what would auto-play pick" is exactly how the downloaded
 * row and the played row stop being the same row.
 */
internal fun autoplayPickFor(
    context: Context,
    sources: List<Stream>,
    season: Int?,
    episode: Int?,
    runtimeMinutes: Int?,
    /** The binge identity to continue, when the caller has one (spec C19). */
    binge: AutoplayPick.Binge = AutoplayPick.Binge(),
    /** The add-on behind each row, by identity; null when unknown. */
    addonOf: (Stream) -> String? = { null }
): Stream? = AutoplayPick.pick(
    context = context,
    remaining = sources,
    season = season,
    episode = episode,
    runtimeMinutes = runtimeMinutes,
    binge = binge,
    addonOf = addonOf
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

/**
 * The word for a row that has no download yet.
 *
 * Sentence case because a menu row reads as a sentence (the chips that draw
 * this upper-case it themselves - see [MobileDownloadControl]).
 */
internal const val DownloadLabel = "Download"

/** Which press a [DownloadPlanRow] stands for. */
internal enum class DownloadActionKind { DOWNLOAD, PLAY, RESUME, DELETE, STATE }

/**
 * One row of the download state machine: what it says, whether it can be
 * pressed, and which action a press means.
 *
 * [reason] is only ever set on a disabled row, and is the words the viewer
 * needs to read (the DRM gate's, spec §5).
 */
internal data class DownloadPlanRow(
    val kind: DownloadActionKind,
    val label: String,
    val enabled: Boolean = true,
    val reason: String? = null
)

/**
 * The download state machine for one identity, as rows.
 *
 * Expressed as data rather than as a layout, because the phone now offers a
 * download from one surface and draws it two ways: the streams picker's
 * long-press menu reads these rows ([StreamsScreenMobile]), and
 * [MobileDownloadControl] keeps the chip rendering for a card that wants the
 * actions inline. Two copies of this `when` would be two chances to offer the
 * wrong press on a file that is not here, which is the failure this feature can
 * least afford.
 *
 * The states are closed, and mean exactly what [DownloadState] says:
 *
 *  - no record (or a DELETED one): **Download**;
 *  - QUEUED / DOWNLOADING: the state word (with its percentage when known)
 *    plus **Cancel download** - or the word alone on an [inert] surface
 *    (offline), where a cancel would offer to stop something that is waiting
 *    on the network the viewer does not have;
 *  - PAUSED: **Resume** plus **Cancel download**;
 *  - FAILED: **Retry** plus **Delete** (a retry, never a play: there are no
 *    bytes to play, and the retry re-resolves, spec §7);
 *  - DOWNLOADED: **Play downloaded** plus **Delete**.
 *
 * @param record this title's download, when there is one - and it is the whole
 *   state machine: null means nothing has been downloaded here yet.
 * @param idleLabel what to say when there is no record yet. [DownloadLabel] on
 *   a source row, and "Connect to download" where the device is offline.
 * @param disabledReason non-null for the one gate that shows a disabled row
 *   with its reason (DRM), so the gate stays learnable rather than hidden.
 * @param inert true on an offline surface, where a queued transfer is shown but
 *   its cancel is not offered.
 */
internal fun downloadPlan(
    record: DownloadRecord?,
    idleLabel: String,
    disabledReason: String?,
    inert: Boolean
): List<DownloadPlanRow> {
    if (disabledReason != null) {
        return listOf(
            DownloadPlanRow(
                kind = DownloadActionKind.DOWNLOAD,
                label = idleLabel,
                enabled = false,
                reason = disabledReason
            )
        )
    }
    if (record == null) {
        return listOf(DownloadPlanRow(DownloadActionKind.DOWNLOAD, idleLabel))
    }
    return when (record.state) {
        DownloadState.DOWNLOADED -> listOf(
            // The bytes are here: the primary press plays them, and the second
            // row is the way out of the download.
            DownloadPlanRow(DownloadActionKind.PLAY, "Play downloaded"),
            DownloadPlanRow(DownloadActionKind.DELETE, "Delete")
        )

        DownloadState.PAUSED -> listOf(
            DownloadPlanRow(DownloadActionKind.RESUME, "Resume"),
            DownloadPlanRow(DownloadActionKind.DELETE, "Cancel download")
        )

        DownloadState.FAILED -> listOf(
            DownloadPlanRow(DownloadActionKind.DOWNLOAD, "Retry"),
            DownloadPlanRow(DownloadActionKind.DELETE, "Delete")
        )

        DownloadState.QUEUED, DownloadState.DOWNLOADING -> buildList<DownloadPlanRow> {
            add(
                DownloadPlanRow(
                    kind = DownloadActionKind.STATE,
                    label = downloadStatusLine(record),
                    enabled = false
                )
            )
            if (!inert) {
                add(DownloadPlanRow(DownloadActionKind.DELETE, "Cancel download"))
            }
        }

        DownloadState.DELETED -> listOf(DownloadPlanRow(DownloadActionKind.DOWNLOAD, idleLabel))
    }
}

/**
 * [downloadPlan] drawn as chips.
 *
 * Kept because the states are a policy and not a layout: a surface that draws
 * them beside a card (the streams row of an earlier build) gets the same rows
 * the long-press menu shows, in the chip shape the rest of the app uses. The
 * plan's labels arrive in sentence case and are upper-cased here, because that
 * is how a chip reads.
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
    downloadPlan(record, idleLabel, disabledReason, inert).forEach { row ->
        when {
            row.kind == DownloadActionKind.STATE -> Text(
                text = row.label,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            row.reason != null -> MobileDownloadAction(
                label = row.label,
                disabledReason = row.reason,
                onClick = {}
            )

            else -> MobileFilterChip(
                label = row.label.uppercase(),
                selected = false,
                onClick = when (row.kind) {
                    DownloadActionKind.DOWNLOAD -> onDownload
                    DownloadActionKind.PLAY -> onPlay
                    DownloadActionKind.RESUME -> onResume
                    DownloadActionKind.DELETE -> onDelete
                    DownloadActionKind.STATE -> ({})
                }
            )
        }
    }
}
