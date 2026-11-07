package com.kennyb1201.kbstream.data.player

import kotlinx.serialization.Serializable

/**
 * One embedded subtitle track that a device has SEEN draw as bitmaps (PGS
 * images) in a title, as remembered by [SubtitleBitmapProofs].
 *
 * A plain value with no Android dependency, because the RANKING rule that reads
 * it ([com.kennyb1201.kbstream.ui.player.SubtitleTrackRules]) is pure and
 * JVM-tested - the store that persists it is the only part that needs a
 * `Context`.
 *
 * The record is matched on position AND on the language and kind it was made
 * with: a track labelled "English SDH" at position 3 only proves the track at
 * position 3 that still claims English and SDH, so a different rip of the same
 * show with reordered tracks cannot make an unrelated track look proven.
 */
@Serializable
internal data class BitmapTrackProof(
    /** The track's flat position among the file's text tracks (the picker's numbering). */
    val position: Int,
    /** The container's language tag as it was when the proof was made. */
    val language: String? = null,
    /** [KIND_SDH] / [KIND_REGULAR] / [KIND_FORCED]. */
    val kind: String = KIND_REGULAR,
    /** When it was recorded, for the store's oldest-first eviction. */
    val at: Long = 0L
) {
    companion object {
        const val KIND_SDH = "sdh"
        const val KIND_REGULAR = "regular"
        const val KIND_FORCED = "forced"
    }
}
