package com.kennyb1201.kbstream.data.download

/**
 * How a stored download record and the downloader's own view of it are turned
 * into the one row the app shows.
 *
 * Split out of the manager and kept pure - no Context, no media3 - because this
 * is where §7's state rules actually live, and they are the rules a device pass
 * cannot reproduce on demand:
 *
 *  - a partial never reads Downloaded, whatever a stored record happens to say;
 *  - a partial the downloader no longer knows about is Failed, not Downloaded
 *    and not silently gone;
 *  - a completed download the downloader no longer has is Failed with the
 *    "no longer on this phone" reason, which is also what the player says when
 *    it cannot find the bytes;
 *  - a download waiting on its network requirement keeps its own state and
 *    gains the waiting qualifier, so a Wi-Fi-only start reads "Queued" with the
 *    reason under it rather than looking stalled.
 */
internal enum class LiveDownloadState {
    QUEUED,
    DOWNLOADING,
    /** Stopped short of the end, by the viewer or by the platform. Resumable. */
    STOPPED,
    COMPLETED,
    FAILED,
    /** Removed from the downloader's index: its bytes are gone. */
    REMOVED
}

/** The downloader's view of one download, in this module's own vocabulary. */
internal data class LiveDownload(
    val state: LiveDownloadState,
    val percent: Float? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = DownloadSpace.UNKNOWN_LENGTH,
    val waitingForNetwork: Boolean = false
)

/**
 * The reasons a row can fail with, in the words the viewer reads. Kept together
 * so the player and the list cannot say different things about one failure.
 */
internal object DownloadFailureReasons {

    /** The bytes are gone: cleared storage, an uninstall-and-reinstall, a partial delete. */
    const val MISSING = "This download is no longer on this phone"

    /** Cut off and not resumable - the downloader's index no longer holds it. */
    const val INTERRUPTED = "The download was interrupted"

    /** The downloader gave up and this device is not out of room. */
    const val FAILED = "The download failed"

    /** Enqueue-time refusal when the row could not be prepared. */
    const val RESOLVE_FAILED = "TorBox could not prepare this source"

    /** Enqueue-time refusal when there is nothing resolvable in the row. */
    const val NO_SOURCE = "This source has no playable link"
}

/**
 * The merged row, or null when the record should be dropped entirely.
 *
 * @param indexReady whether the downloader's index was read successfully. A
 *   failed read must not be mistaken for "the downloader forgot this download",
 *   which would fail every row on a device that hiccuped - so with a false value
 *   the stored record is left exactly as it is.
 * @param lowOnSpace whether this device is currently short of space; it is what
 *   turns an unexplained failure into the "Out of space" the spec asks for, told
 *   from the one thing that can be observed after the fact.
 */
internal fun reconcile(
    stored: DownloadRecord,
    live: LiveDownload?,
    indexReady: Boolean,
    lowOnSpace: Boolean,
    now: Long
): DownloadRecord? {
    if (live == null) {
        if (!indexReady) return stored
        // The downloader does not know this download at all.
        val reason = if (stored.state.isComplete) {
            DownloadFailureReasons.MISSING
        } else {
            DownloadFailureReasons.INTERRUPTED
        }
        return stored.copy(
            state = DownloadState.FAILED,
            reason = reason,
            percent = null,
            waitingForNetwork = false,
            updatedAt = now
        )
    }

    return when (live.state) {
        LiveDownloadState.REMOVED -> null

        LiveDownloadState.COMPLETED -> stored.copy(
            state = DownloadState.DOWNLOADED,
            percent = 100f,
            // A completed download's size is the size of its bytes: prefer the
            // downloader's figure, fall back to what was already known.
            totalBytes = live.totalBytes.takeIf { it > 0L }
                ?: stored.totalBytes.takeIf { it > 0L }
                ?: DownloadSpace.UNKNOWN_LENGTH,
            bytesDownloaded = maxOf(live.bytesDownloaded, live.totalBytes, 0L),
            reason = null,
            waitingForNetwork = false,
            updatedAt = now
        )

        LiveDownloadState.FAILED -> stored.copy(
            state = DownloadState.FAILED,
            reason = if (lowOnSpace) {
                DownloadSpace.OUT_OF_SPACE_REASON
            } else {
                DownloadFailureReasons.FAILED
            },
            percent = null,
            waitingForNetwork = false,
            updatedAt = now
        )

        LiveDownloadState.DOWNLOADING -> stored.copy(
            state = DownloadState.DOWNLOADING,
            percent = live.percent,
            bytesDownloaded = live.bytesDownloaded,
            totalBytes = live.totalBytes.takeIf { it > 0L } ?: stored.totalBytes,
            reason = null,
            waitingForNetwork = false,
            updatedAt = now
        )

        LiveDownloadState.QUEUED -> stored.copy(
            state = DownloadState.QUEUED,
            percent = null,
            bytesDownloaded = live.bytesDownloaded,
            totalBytes = live.totalBytes.takeIf { it > 0L } ?: stored.totalBytes,
            reason = null,
            waitingForNetwork = live.waitingForNetwork,
            updatedAt = now
        )

        LiveDownloadState.STOPPED -> stored.copy(
            state = DownloadState.PAUSED,
            percent = null,
            bytesDownloaded = live.bytesDownloaded,
            totalBytes = live.totalBytes.takeIf { it > 0L } ?: stored.totalBytes,
            reason = null,
            waitingForNetwork = live.waitingForNetwork,
            updatedAt = now
        )
    }
}

/**
 * Whether a record is whole enough to play.
 *
 * The download must say Downloaded AND the downloader must still be holding it
 * as completed - the second half is what keeps a record whose bytes were removed
 * behind the app's back from opening a player that then fails (spec §6).
 */
internal fun isPlayable(stored: DownloadRecord, live: LiveDownload?): Boolean =
    stored.state.isComplete &&
        live != null &&
        live.state == LiveDownloadState.COMPLETED

/** The waiting line a queued-for-Wi-Fi row shows under its state. */
internal const val WAITING_FOR_WIFI = "Waiting for Wi-Fi"
