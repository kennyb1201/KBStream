package com.kennyb1201.kbstream.data.download

/**
 * The free-space rule, as arithmetic that can be tested without a device.
 *
 * The rule exists because the alternative is a download that silently fills the
 * phone: a 4K remux is tens of gigabytes, and the failure mode of "just start
 * it and see" is a device whose storage is gone with no explanation. So a start
 * whose length is KNOWN is refused before a single byte is written, and a start
 * whose length is unknown (an HLS playlist, a chunked response with no
 * Content-Length) is allowed to begin and then has the downloader's own
 * out-of-space failure translated into the same plain words - see
 * [outOfSpaceReason], which both paths print.
 */
object DownloadSpace {

    /**
     * Headroom kept clear on top of the file itself.
     *
     * 256 MB, not zero: the phone has to keep running while the download writes
     * (the player's own buffers, the watch-history database, Coil's image
     * cache), and a download that consumes the last byte of storage takes those
     * with it. Deliberately not a percentage - a percentage of a nearly-full
     * device is nearly nothing, which is the state the margin exists for.
     */
    const val MARGIN_BYTES: Long = 256L * 1024L * 1024L

    /** Refused before enqueue: the file's known length does not fit with [MARGIN_BYTES] spare. */
    const val NOT_ENOUGH_SPACE = "Not enough space for this download"

    /** The downloader ran the device out of room mid-transfer, or Media3 reports the same. */
    const val OUT_OF_SPACE_REASON = "Out of space"

    /**
     * Whether a download of [lengthBytes] may start with [freeBytes] free.
     *
     * A null (or unknown, [UNKNOWN_LENGTH]) length answers true: "length
     * unknown" is not "length too big", and the spec's rule is to enqueue and
     * let the failure speak. Anything else is the length plus the margin.
     */
    fun hasRoomFor(freeBytes: Long, lengthBytes: Long?): Boolean {
        if (lengthBytes == null || lengthBytes == UNKNOWN_LENGTH) return true
        if (lengthBytes <= 0L) return true
        // Overflow-safe: a bogus Content-Length must not wrap below the margin
        // and read as "room enough" on a full device.
        if (lengthBytes > Long.MAX_VALUE - MARGIN_BYTES) return false
        return freeBytes >= lengthBytes + MARGIN_BYTES
    }

    /** What the row says when the downloader reports it could not write any more. */
    fun outOfSpaceReason(): String = OUT_OF_SPACE_REASON

    /**
     * Media3's own sentinel for "no length reported" (`C.LENGTH_UNSET`), spelled
     * out here so this object stays pure arithmetic with no media3 import: it is
     * the value [DownloadRecord.totalBytes] carries when the length was never
     * known, and the value a caller hands [hasRoomFor] in that case.
     */
    const val UNKNOWN_LENGTH: Long = -1L
}
