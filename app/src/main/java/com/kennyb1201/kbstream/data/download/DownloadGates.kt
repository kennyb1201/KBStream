package com.kennyb1201.kbstream.data.download

import com.kennyb1201.kbstream.data.addon.Stream

/**
 * Whether the Download action may be offered for one source row, and if not,
 * why - the whole of spec §5 as a pure decision, so the gate matrix is a unit
 * test rather than a device pass.
 *
 * The distinction the result draws is deliberate and is the spec's own: a LIVE
 * row is [Hidden] outright (there is no download of a stream with no end, and
 * offering a greyed-out action would only invite the question), while a
 * protected row is [Disabled] with its reason printed, so a viewer who wonders
 * why their title has no download can read the answer instead of guessing.
 */
sealed interface DownloadGate {

    /**
     * The action is offered. [needsResolve] says the row carries a torrent hash
     * rather than a URL, so the download must go through the same resolve path a
     * stream press uses (TorBox) before anything can be enqueued.
     */
    data class Allowed(val needsResolve: Boolean) : DownloadGate

    /** No action anywhere: a live channel has no end, so it has no download. */
    object Hidden : DownloadGate

    /** Shown, greyed, with [reason] - the DRM case, and only it. */
    data class Disabled(val reason: String) : DownloadGate

    /** Shown as "Connect to download" and inert: enqueueing needs the network. */
    object Offline : DownloadGate
}

object DownloadGates {

    /** The DRM copy, kept here so the row and the Detail button cannot drift. */
    const val DRM_REASON = "Downloads aren't available for protected titles"

    /** What an offline press says. Playback of what is already downloaded is the offline path. */
    const val CONNECT_TO_DOWNLOAD = "Connect to download"

    /**
     * The decision for one row.
     *
     * @param stream the picked row, or null when the caller has no row at all
     *   (a live channel's Detail page, which must show nothing).
     * @param isLiveChannel the player's own live signal - the `parentType ==
     *   "channel"` test the launch carries, so "live" means here exactly what it
     *   means to playback.
     * @param online whether the device has usable connectivity right now. A
     *   download needs it both to resolve and to fetch, so an offline press is
     *   refused rather than queued: the offline path is playing what is already
     *   here (spec §5).
     */
    fun evaluate(
        stream: Stream?,
        isLiveChannel: Boolean,
        online: Boolean
    ): DownloadGate {
        if (isLiveChannel || stream == null) return DownloadGate.Hidden
        if (!stream.drm?.licenseUrl.isNullOrBlank()) {
            return DownloadGate.Disabled(DRM_REASON)
        }
        if (!online) return DownloadGate.Offline
        return DownloadGate.Allowed(needsResolve = needsResolve(stream))
    }

    /**
     * Whether this row is a hash-only row that must be resolved first.
     *
     * Deliberately the same two-field test
     * [com.kennyb1201.kbstream.data.debrid.TorBoxResolve.isUnresolved] applies,
     * written out here rather than called, because this object is pure and the
     * resolver's is a suspend-capable class that reaches the network and the
     * prefs. A row with a URL is playable; a row with only an infohash is not.
     * The two are pinned together by a test so they cannot drift apart.
     */
    fun needsResolve(stream: Stream): Boolean =
        stream.url.isNullOrBlank() && !stream.infoHash.isNullOrBlank()

    /**
     * Whether the row may be enqueued at all, hash rows included - the resolve
     * itself happens in the manager, and its failure is the row's failure.
     */
    fun isAllowed(gate: DownloadGate): Boolean = gate is DownloadGate.Allowed
}
