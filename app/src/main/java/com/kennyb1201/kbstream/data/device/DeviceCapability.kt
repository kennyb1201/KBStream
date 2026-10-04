package com.kennyb1201.kbstream.data.device

import android.app.ActivityManager
import android.content.Context

/**
 * What this device can be asked to decode, for the stream ranker.
 *
 * [com.kennyb1201.kbstream.domain.streamengine.StreamRanker] orders the source
 * list that auto-play takes the head of, and a 4K remux that a 1.7 GB TV-box
 * cannot decode is not a better stream than the 1080p it outranked - it is the
 * one that stalls. The ranker is a pure object with no Android dependency, so
 * the device fact is worked out here and passed in.
 */
internal object DeviceCapability {

    /**
     * Total RAM under which a high-bitrate 4K stream is likelier to stall the
     * decoder and the player's buffers than to look better. The app's field TV
     * reports ~1.7 GB, and most Android TV boxes sit between 1.5 and 2 GB, so
     * they all read as constrained; a phone or tablet with 4–8 GB does not.
     */
    private const val CONSTRAINED_TOTAL_RAM_BYTES = 2_500L * 1024L * 1024L

    /**
     * True when this device is memory-constrained enough that the ranker should
     * prefer a stream it can decode over a bigger one it cannot.
     *
     * Reads the real device RAM (`totalMem`) rather than the reported memory
     * class: `android:largeHeap="true"` inflates the memory class the system
     * hands back (512 MB on the field TV), which would hide exactly the low-RAM
     * boxes this exists for. Fails open to "capable" when the service is
     * missing, so an unknown device keeps the previous ordering rather than
     * being demoted on a guess.
     */
    fun constrainedStreamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(info) }.getOrElse { return false }
        return info.totalMem in 1 until CONSTRAINED_TOTAL_RAM_BYTES
    }
}
