package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The SLEEP TIMER section of both players' settings panels.
 *
 * One class rather than a copy in each panel because the two engines' panels
 * are built the same way — both append their rows to their own column with
 * [PillPanelUi] — so the section lands in the same style, at the same place, in
 * both, and there is exactly one list of choices to keep honest. What differs
 * per engine is only who enforces the timer (see [SleepTimer]); this section
 * draws it and reports presses.
 *
 * The rows come from [optionsProvider] rather than from a list captured once,
 * for two reasons that both matter on a live channel: "End of program" can
 * only be offered once the guide has a block that ends in the future, and its
 * end changes when the block rolls over. The provider is re-read once a second
 * while the panel is on screen, and the grid is rebuilt only when the rows
 * actually changed.
 *
 * The caption is likewise re-read from [SleepTimer] on that tick, so the
 * countdown a viewer is looking at moves while a backgrounded player pays
 * nothing for it. The tick stops with the activity ([release]), and an
 * unchanged caption is not written back into the TextView — a text set every
 * second would relayout a panel the D-pad is focused in.
 */
internal class SleepTimerSection(
    private val activity: Activity,
    private val optionsProvider: () -> List<SleepTimerOption>,
    private val onSelect: (SleepTimerOption) -> Unit
) {

    private val ui = PillPanelUi(activity)

    private val pills = mutableListOf<Pair<TextView, SleepTimerOption>>()
    private var grid: LinearLayout? = null
    private var caption: TextView? = null
    private var column: LinearLayout? = null

    /** The rows currently built, so the grid is only rebuilt when they change. */
    private var builtOptions: List<SleepTimerOption> = emptyList()

    private var lastCaption: String? = null

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (column?.isShown == true) refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Builds the section once, at the end of the panel's own column. */
    fun attach(panel: View) {
        if (column != null) return
        val column = (panel as? ViewGroup)?.getChildAt(0) as? LinearLayout ?: return
        this.column = column

        column.addView(ui.header("SLEEP TIMER", topMarginDp = 16))
        column.addView(
            ui.label(
                "Playback stops on its own, so a binge cannot play on after you fall asleep.",
                topMarginDp = 0
            )
        )
        grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }.also { column.addView(it) }
        caption = ui.label("", topMarginDp = 6).also { column.addView(it) }

        rebuildRowsIfChanged()
        refresh()
        handler.postDelayed(tick, TICK_MS)
    }

    /** Marks the armed row and rewrites the countdown. */
    fun refresh() {
        rebuildRowsIfChanged()
        val state = SleepTimer.state.value
        pills.forEach { (view, option) -> ui.stylePill(view, state.marks(option)) }
        val text = sleepTimerStatusText(state, System.currentTimeMillis())
        if (text != lastCaption) {
            lastCaption = text
            caption?.text = text
        }
    }

    /**
     * Rewrites the pills when this session's choices changed — "End of
     * program" appearing once the guide answers, or its end moving with the
     * block. The focused option is carried across so the rebuild cannot drop a
     * viewer's place mid-press; a focused row that no longer exists (the live
     * ending replacing the movie one) leaves focus on the column, which the
     * D-pad's own search then resolves.
     */
    private fun rebuildRowsIfChanged() {
        val options = optionsProvider()
        if (options == builtOptions) return
        val grid = grid ?: return
        val focused = pills.firstOrNull { it.first.isFocused }?.second
        builtOptions = options

        grid.removeAllViews()
        pills.clear()
        ui.addPillGrid(grid, 6, options.map { it.label to it }, pills) { option ->
            onSelect(option)
            refresh()
        }
        focused?.let { option ->
            pills.firstOrNull { it.second == option }?.first?.requestFocus()
        }
    }

    /** Stops the tick. Called from the activity's teardown. */
    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        /**
         * One second: the resolution the caption counts at, and the same cadence
         * the players' own position ticks already run at.
         */
        const val TICK_MS = 1_000L
    }
}
