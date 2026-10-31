package com.kennyb1201.kbstream.data.memory

import android.app.ActivityManager
import android.content.Context

/**
 * How much of the box a playing session's sample buffer is allowed to take.
 *
 * The player already caps media3's byte target, because media3 buffers into
 * PLAIN JAVA-HEAP `byte[]` blocks and a duration-only target on a 4K release is
 * a memory bomb (see the long note in `NativePlayerActivity.createPlayer`). That
 * cap was derived from the heap alone - a third of `maxMemory()` - and the heap
 * is the wrong denominator on the devices this ships to. `android:largeHeap`
 * makes `maxMemory()` report a slice of RAM the system would rather hand to a
 * foreground app than keep free, so a 1.7 GB TV box that reports a 512 MB heap
 * ends up with a 160 MB sample-buffer target: a tenth of everything the box
 * has, held live at the exact moment the player is also opening a decoder, a
 * surface and two splash images. That is a low-memory kill with no Java
 * exception to report - the app is simply gone, back to the launcher.
 *
 * So the target is the SMALLER of two answers, the heap's third and a fraction
 * of the RAM the device actually has (reported by [ActivityManager]). The heap
 * answer is kept, not replaced: it is the number that was measured to stop the
 * high-bitrate stall on the boxes already in the field, and on a heap-poor box
 * it is still the binding one.
 *
 * The table (heap / total RAM → target):
 *
 *   192 MB / 1.5 GB → 64 MB   (unchanged: heap-bound)
 *   256 MB / 1.5 GB →  85 MB  (unchanged: heap-bound, the measured stall fix)
 *   384 MB / 1.7 GB → 106 MB  (was 128 MB: RAM-bound)
 *   512 MB / 1.7 GB → 106 MB  (was 160 MB: RAM-bound)
 *   512 MB / 4 GB   → 160 MB  (unchanged: heap-bound, the cap)
 *
 * A device that cannot report its RAM (0) keeps the old heap-only rule, which
 * is what every pre-existing test and every shipped build effectively does.
 */
internal object PlayerMemoryBudget {

    /** Floors and caps for the sample-buffer target, in bytes. */
    const val MIN_SAMPLE_BYTES = 48L * 1024 * 1024
    const val MAX_SAMPLE_BYTES = 160L * 1024 * 1024

    private const val HEAP_DIVISOR = 3L

    /**
     * One sixteenth of total RAM.
     *
     * Calibrated against the measurements the heap rule was built on: at 1.5 GB
     * this is 96 MB, so it never undercuts the ~85 MB that fixed the 40 Mbps
     * stall on a 256 MB-heap box; at 2 GB it is 128 MB, the old value; and it
     * only binds on boxes whose heap is richer than their RAM, which is exactly
     * the largeHeap case. Four percent of RAM is also well under the share a
     * foreground app is normally allowed to hold on a 1.7 GB set.
     */
    private const val RAM_DIVISOR = 16L

    /** What the box reports about itself. Either value may be 0 if unavailable. */
    data class Device(val maxHeapBytes: Long, val totalRamBytes: Long)

    /** Reads the current device. Never throws: a null service reads as unknown. */
    fun of(context: Context): Device {
        val totalRam = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching 0L
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.totalMem
        }.getOrDefault(0L)
        return Device(
            maxHeapBytes = Runtime.getRuntime().maxMemory(),
            totalRamBytes = totalRam
        )
    }

    /** The media3 `setTargetBufferBytes` argument for [device]. */
    fun sampleBufferBytes(device: Device): Int =
        sampleBufferBytes(device.maxHeapBytes, device.totalRamBytes)

    /**
     * Pure form, so the table above is a test and not a claim.
     *
     * [totalRamBytes] of 0 or less means "not reported": the heap rule alone
     * decides, and a box whose RAM cannot be read never gets a budget cut on the
     * strength of a number we do not have.
     */
    internal fun sampleBufferBytes(maxHeapBytes: Long, totalRamBytes: Long): Int {
        val fromHeap = maxHeapBytes / HEAP_DIVISOR
        val fromRam = if (totalRamBytes > 0L) totalRamBytes / RAM_DIVISOR else Long.MAX_VALUE
        return minOf(fromHeap, fromRam)
            .coerceIn(MIN_SAMPLE_BYTES, MAX_SAMPLE_BYTES)
            .toInt()
    }
}
