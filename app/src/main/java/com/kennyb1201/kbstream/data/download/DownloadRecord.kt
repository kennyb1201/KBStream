package com.kennyb1201.kbstream.data.download

import kotlinx.serialization.Serializable

/**
 * One download, as it is stored and shown.
 *
 * A record is written when the row is enqueued and updated from the downloader's
 * own progress; it is never derived from the network and never re-created from a
 * re-resolved link, because §2.2's whole point is that after the bytes are here
 * the source link stops mattering.
 *
 * ## What [sourceUrl] is, and is not
 *
 * [sourceUrl] is the row's RESOLVED url at enqueue time. It is NOT used to fetch
 * anything at playback: the bytes are read out of the download cache, and the
 * only thing the url does at playback time is address that cache entry (Media3
 * keys a progressive download's cache on the url, or on [customCacheKey] when
 * one is set - see the manager's playback helpers). Deleting the download
 * deletes the cached bytes, so the record's url can never become a silent
 * fallback to streaming: there is nothing left for it to address.
 *
 * It is also the value a Retry does NOT use. Retry re-resolves the row
 * ([sourceName] and friends below) and builds a new record, so a dead link is
 * never retried - see [retrySource].
 */
@Serializable
data class DownloadRecord(
    /** [DownloadIdentity.downloadId] of [key]; the Media3 index key and the intent extra. */
    val id: String,
    /** [DownloadIdentity.key] - profile + media key + episode + row key. */
    val key: String,
    val profileId: String? = null,
    /** The title key this download belongs to (the player's own title identity). */
    val mediaKey: String,
    val season: Int? = null,
    val episode: Int? = null,
    /** What the row is called: the title, or the episode's own line for a series. */
    val title: String,
    /** "S02 · E04", for the row's secondary line. Null for a feature. */
    val episodeLabel: String? = null,
    /**
     * The picked row's own quality label - the spec's "the row IS the quality
     * choice", shown on every surface that names this download.
     */
    val qualityLabel: String,
    val posterUrl: String? = null,
    val parentType: String = "",
    val parentId: String = "",
    /**
     * The episode identity playback files its history row under.
     *
     * Carried so a downloaded play writes the SAME row a streamed play writes
     * (spec §6): the player takes it as `episode_stream_id`, and a row written
     * without it would be a second, differently-keyed resume point for one
     * episode.
     */
    val episodeStreamId: String? = null,
    /** The resolved URL at enqueue time. A cache address from here on, see the class comment. */
    val sourceUrl: String,
    /**
     * The cache key the bytes were written under, when one was set.
     *
     * Progressive downloads get one (the download id) so two rows for one title
     * cannot share a cache entry; adaptive ones must not (Media3 requires a null
     * custom cache key for HLS/DASH/SmoothStreaming), and playback then addresses
     * the cache by url exactly as the downloader wrote it.
     */
    val customCacheKey: String? = null,
    /** The mime type handed to the download request, when the row knew one. */
    val mimeType: String? = null,
    /** The addon's own request headers, for the download's own fetches. */
    val sourceHeaders: Map<String, String> = emptyMap(),
    /**
     * The original picked row, kept ONLY so a Retry can re-resolve it. A hash
     * row is stored with its infohash and file index so the retry is the same
     * request a stream press would make.
     */
    val sourceName: String? = null,
    val sourceTitle: String? = null,
    val sourceDescription: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val state: DownloadState = DownloadState.QUEUED,
    /** 0..100 while DOWNLOADING, when the downloader reports one; null otherwise. */
    val percent: Float? = null,
    val bytesDownloaded: Long = 0L,
    /** The whole file's length, or [DownloadSpace.UNKNOWN_LENGTH] when it was never known. */
    val totalBytes: Long = DownloadSpace.UNKNOWN_LENGTH,
    /** Why a FAILED row failed, in plain words. */
    val reason: String? = null,
    /**
     * True while the downloader is holding this download for a network it is
     * allowed to use (the Wi-Fi-only rule). It is a qualifier on the state
     * rather than a state of its own: the row still reads "Queued", with
     * "Waiting for Wi-Fi" under it.
     */
    val waitingForNetwork: Boolean = false,
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L
) {

    /** The row's state word, with the percentage when there is one. */
    fun stateLabel(): String = when (state) {
        DownloadState.FAILED -> reason?.takeIf { it.isNotBlank() }
            ?.let { "Failed ($it)" }
            ?: DownloadState.FAILED.label()

        else -> state.label(percent)
    }

    /** The line under the title: what is happening, and how big it is. */
    fun detailLine(): String = listOfNotNull(
        stateLabel(),
        qualityLabel.takeIf { it.isNotBlank() },
        sizeLabel()
    ).joinToString(" · ")

    /** This download's bytes, or null when there is no size to state yet. */
    fun sizeLabel(): String? {
        val bytes = if (totalBytes > 0L) totalBytes else bytesDownloaded
        return bytes.takeIf { it > 0L }?.let(::formatDownloadBytes)
    }

    /**
     * 0f..1f of the file transferred, for a progress row. Null when nothing is
     * known, which the row draws as an indeterminate state rather than an empty
     * bar that reads as "stuck at zero".
     */
    fun progressFraction(): Float? = when {
        state.isComplete -> 1f
        totalBytes > 0L && bytesDownloaded >= 0L -> {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }

        percent != null && percent >= 0f -> (percent / 100f).coerceIn(0f, 1f)
        else -> null
    }

    /**
     * The row to re-resolve for a Retry - the picked row as it was, with its URL
     * deliberately dropped, so the retry cannot reuse a link that may be dead.
     */
    fun retrySource(): com.kennyb1201.kbstream.data.addon.Stream =
        com.kennyb1201.kbstream.data.addon.Stream(
            name = sourceName,
            title = sourceTitle,
            description = sourceDescription,
            infoHash = infoHash,
            fileIdx = fileIdx,
            headers = sourceHeaders.takeIf { it.isNotEmpty() }
        )

    /** [state] with the per-profile identity stripped: the part a log line may carry. */
    fun traceLabel(): String = "$id ${state.name.lowercase()}"
}

/**
 * Bytes as a person reads them.
 *
 * Decimal units (1000), not binary (1024): this number is only ever compared
 * against what the phone's storage screen says, and that screen counts decimal.
 * A GB here disagreeing with the system's own GB is a bug report of its own.
 */
fun formatDownloadBytes(bytes: Long): String {
    if (bytes < 1000L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1000.0 && unit < units.lastIndex) {
        value /= 1000.0
        unit++
    }
    val rounded = if (value < 10.0) {
        // One decimal below 10, so a 1.4 GB film does not render as "1 GB".
        (Math.round(value * 10.0) / 10.0)
    } else {
        Math.round(value).toDouble()
    }
    val text = if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
    return "$text ${units[unit]}"
}
