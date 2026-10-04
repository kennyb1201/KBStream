package com.kennyb1201.kbstream.ui.player

import java.io.File

/**
 * The native operations [LibassSubtitleRenderer] drives, behind an interface so
 * the renderer's sample-handling can be unit tested with a fake and without a
 * device or libassjni.so.
 *
 * Kept deliberately tiny: create the instance, feed the header once, append one
 * event per sample, and drop events on a seek. The renderer owns none of the
 * native state — the shared [AssSubtitleRenderer] on the activity does, so the
 * overlay tick and the streaming feed drive the same libass instance.
 */
internal interface LibassStreamingSink {

    /** Whether this build carries libassjni.so at all. */
    val available: Boolean

    /** Allocates the native instance. False leaves the track to media3's plain renderer. */
    fun create(): Boolean

    /** Feeds the ASS header (the codec-private block). */
    fun processCodecPrivate(data: ByteArray): Boolean

    /** Appends one event block at [timeMs]. [durationMs] is 0 when unknown. */
    fun processChunk(data: ByteArray, timeMs: Long, durationMs: Long)

    /** Drops accumulated events, for a seek. */
    fun flushEvents()
}

/**
 * The production sink: delegates to the activity's shared [AssSubtitleRenderer],
 * resolving fonts / fontconfig / cache paths lazily (they are read at most once,
 * on the first track).
 */
internal class AssStreamingSink(
    private val renderer: AssSubtitleRenderer,
    private val fonts: () -> List<File>,
    private val configPath: () -> String?,
    private val cacheDir: () -> String?
) : LibassStreamingSink {

    override val available: Boolean get() = AssSubtitleRenderer.available

    override fun create(): Boolean = renderer.create(fonts(), configPath(), cacheDir())

    override fun processCodecPrivate(data: ByteArray): Boolean =
        renderer.processCodecPrivate(data)

    override fun processChunk(data: ByteArray, timeMs: Long, durationMs: Long) =
        renderer.processChunk(data, timeMs, durationMs)

    override fun flushEvents() = renderer.flushEvents()
}
