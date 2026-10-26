package com.kennyb1201.kbstream.data.download

/**
 * The vocabulary one download is shown in, and nothing besides it.
 *
 * The set is closed deliberately: the Streams row, the Detail button and the
 * Downloads screen all print the same word for the same fact, so a viewer who
 * reads "Paused" in two places is reading one state rather than two opinions
 * about it. [label] is therefore the single place any of those surfaces get
 * their wording from.
 *
 * [DELETED] is the one member a *stored* record never carries: deleting drops
 * the record outright, because the Downloads header's total and the empty state
 * must both be written from rows that still exist, and a tombstone that counted
 * zero bytes would be a row nobody could act on. The name is kept anyway - the
 * spec names it as a state, and a surface that has to render the last known
 * state of a row it was holding should not have to invent the word for it.
 */
enum class DownloadState {

    /** Enqueued, with no bytes on disk yet - or waiting for Wi-Fi (see [waitingForWifi]). */
    QUEUED,

    /** Transferring. [percent] is what the row shows, when it is known. */
    DOWNLOADING,

    /** Stopped on purpose, either by the viewer or by the platform. Resumable. */
    PAUSED,

    /** Whole and playable with the network off. The only state that counts toward the total. */
    DOWNLOADED,

    /** Gave up. [DownloadRecord.reason] carries the plain-words why. */
    FAILED,

    /** Gone. A stored record is removed rather than flagged, see the class comment. */
    DELETED;

    /**
     * True only for the whole-file state.
     *
     * This is the one predicate the "never show Downloaded for a partial file"
     * rule is written against (spec §7), so it is expressed once, here, instead
     * of as a `state == Downloaded` comparison at each surface that draws a
     * badge or writes a total.
     */
    val isComplete: Boolean
        get() = this == DOWNLOADED

    /**
     * True while bytes for this download are on disk but the file is not whole.
     *
     * A partial is never downloadable-badge material and never counts toward the
     * storage total; it is only ever something the viewer can resume, retry or
     * delete.
     */
    val isPartial: Boolean
        get() = this == QUEUED || this == DOWNLOADING || this == PAUSED

    /**
     * The row's wording. [percent] is the progress a DOWNLOADING row has, when
     * the downloader reports one - the spec's "Downloading (%)" is a percentage
     * only while there is one to show, so an unknown percentage prints the bare
     * word rather than a made-up number.
     */
    fun label(percent: Float? = null): String = when (this) {
        QUEUED -> "Queued"
        DOWNLOADING -> {
            val whole = percent?.takeIf { it >= 0f }?.toInt()
            if (whole == null) "Downloading" else "Downloading ($whole%)"
        }

        PAUSED -> "Paused"
        DOWNLOADED -> "Downloaded"
        FAILED -> "Failed"
        DELETED -> "Deleted"
    }
}
