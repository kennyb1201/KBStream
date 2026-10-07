package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.SeekBar
import com.kennyb1201.kbstream.R

/** One chapter marker: where it starts, and its name when the file has one. */
data class ChapterMark(val timeMs: Long, val title: String?)

/**
 * The chapter tick geometry, kept in a top-level object with no Android types
 * so the position rule can be unit tested directly on the JVM (a View subclass
 * cannot be instantiated there).
 */
internal object ChapterTicks {

    /**
     * The x a mark is drawn at, or null when it should not be drawn.
     *
     * A mark before the start is clamped to the track's left; a mark past the
     * duration is dropped (null), as is every mark when the duration is
     * unknown.
     */
    fun tickX(
        timeMs: Long,
        durationMs: Long,
        trackLeft: Float,
        trackRight: Float
    ): Float? {
        if (durationMs <= 0L) return null
        val clamped = timeMs.coerceAtLeast(0L)
        if (clamped > durationMs) return null
        val fraction = clamped.toDouble() / durationMs.toDouble()
        return trackLeft + (trackRight - trackLeft) * fraction.toFloat()
    }
}

/**
 * A [SeekBar] that paints a tick for each chapter marker on top of the normal
 * track.
 *
 * Paint only, by design: it never changes the bar's value or its seek
 * behaviour, so a bar with no chapters is pixel-identical to a stock SeekBar
 * and nothing downstream has to know chapters exist. The ticks are drawn after
 * `super.onDraw`, at the mark's fraction of the track, in the theme's secondary
 * colour at 40% alpha.
 *
 * The one piece of geometry worth pinning is [tickX], kept as a pure function
 * so a JVM test can assert "a mark at 50% of the duration draws at 50% of the
 * track width" without an Android harness.
 */
class ChapterSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {

    private var marks: List<ChapterMark> = emptyList()
    private var durationMs: Long = 0L

    private val density = resources.displayMetrics.density

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // The theme's secondary colour, at 40% so a tick reads as a marker on
        // the track rather than as a second progress bar.
        val secondary = context.getColor(R.color.kb_text_lo)
        color = (secondary and 0x00FFFFFF) or (TICK_ALPHA shl 24)
        strokeWidth = TICK_WIDTH_DP * density
    }

    /**
     * Replaces the drawn markers. An empty list (the default) draws exactly
     * like a stock SeekBar; [durationMs] must be positive for any tick to show.
     */
    fun setChapters(marks: List<ChapterMark>, durationMs: Long) {
        this.marks = marks
        this.durationMs = durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0L || marks.isEmpty()) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        marks.forEach { mark ->
            val x = ChapterTicks.tickX(mark.timeMs, durationMs, left, right)
                ?: return@forEach
            canvas.drawLine(x, 0f, x, height.toFloat(), tickPaint)
        }
    }

    private companion object {
        /** 2dp wide, full track height. */
        const val TICK_WIDTH_DP = 2f

        /** 40% alpha. */
        const val TICK_ALPHA = 0x66
    }
}
