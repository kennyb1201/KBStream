package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.ui.unit.Density

/**
 * The lazy lists' idle pre-composition window, in one place (spec 2026-11-16,
 * scrolling round three F5; extended to the rest of the app by dig four, which
 * found no beyond-bounds window anywhere but Home).
 *
 * A press or a flick into a new row used to pay that row's FIRST composition on
 * the frame it landed - on top of its decode and its metadata lookup. Compose's
 * lazy layouts keep an out-of-viewport window of items prepared in advance; by
 * default that window keeps nothing behind, only a narrow slice ahead, and it
 * does not refill while the list is idle.
 *
 * Half a viewport each way is about one row / one screenful, which is what a walk
 * or a flick actually steps into. `isNonScrollCachingEnabled` is the half that
 * matters most for a D-pad: it fills the window while the viewer sits still
 * rather than only once a scroll has started.
 *
 * Deliberately NOT applied to the Guide grid: one of its rows composes the whole
 * six-hour span (see `ProgramRow`), so pre-composing a row there is the opposite
 * of cheap.
 */
object IdleListCacheWindow : LazyLayoutCacheWindow {
    override fun Density.calculateAheadWindow(viewport: Int): Int = viewport / 2
    override fun Density.calculateBehindWindow(viewport: Int): Int = viewport / 2
    override val isNonScrollCachingEnabled: Boolean = true
}
