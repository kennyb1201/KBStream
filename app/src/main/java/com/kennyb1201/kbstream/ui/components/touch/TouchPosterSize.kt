package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.posterTileRevision

/**
 * Settings → Display's "Poster Size", on a phone.
 *
 * The TV's own [com.kennyb1201.kbstream.ui.components.PosterSize] is a
 * ten-foot measurement (110/124/140dp wide) and every phone grid used to pin
 * its own literal instead: 112dp in the rails, 104dp as a grid column, 120dp on
 * Home - so the switch changed nothing at all on a phone, which is the reported
 * "poster size toggle isn't respected in mobile".
 *
 * The three steps keep the SAME pref (one choice covers every device, exactly
 * as the poster-caption and landscape switches do) and the same ORDER, scaled
 * to a thumb: the middle step is the 112dp the phone pages already agreed on,
 * and the outer two move ±16dp, which is a visible step on a 360dp-wide screen
 * without turning a grid into a single column.
 */
enum class TouchPosterSize(val width: Dp, val height: Dp) {
    SMALL(96.dp, 144.dp),
    MEDIUM(112.dp, 168.dp),
    LARGE(128.dp, 192.dp);

    companion object {
        /** 0 = small, 1 = medium (default), 2 = large - the Settings order. */
        fun fromPref(value: Long): TouchPosterSize =
            when (value.toInt()) {
                0 -> SMALL
                2 -> LARGE
                else -> MEDIUM
            }
    }
}

/**
 * The poster size the viewer picked, as a phone tile.
 *
 * Keyed on the sync revision for two reasons: the pref is synced (a choice made
 * on the television has to reshape a phone already on screen), and a local edit
 * ticks the same revision ([PrefsPayloadApplier.notifyLocalPrefChange]), so
 * flipping the switch and coming back here does not need a restart.
 */
@Composable
fun rememberTouchPosterSize(): TouchPosterSize {
    val context = LocalContext.current
    val syncedRevision = posterTileRevision()
    return remember(syncedRevision) {
        TouchPosterSize.fromPref(AppPreferences.getPosterSize(context))
    }
}

/**
 * Whether the phone draws landscape tiles: the "Landscape Posters Everywhere"
 * switch. The touch twin of `rememberGlobalLandscape`, on the same synced pref
 * and the same revision, so a phone screen can size a cell and its card from
 * ONE answer.
 */
@Composable
fun rememberTouchLandscape(): Boolean {
    val context = LocalContext.current
    val syncedRevision = posterTileRevision()
    return remember(syncedRevision) {
        AppPreferences.landscapePostersActive(context)
    }
}

/**
 * The width a tile occupies right now: the landscape width when the global
 * landscape switch is on, the poster width otherwise.
 *
 * Same rule (and same ratio) as the TV's `rememberPosterTileWidth`, so a phone
 * rail's cell, its card and its caption cannot disagree about the shape being
 * drawn.
 */
@Composable
fun rememberTouchTileWidth(size: TouchPosterSize = rememberTouchPosterSize()): Dp =
    if (rememberTouchLandscape()) landscapeTileWidth(size.width) else size.width
