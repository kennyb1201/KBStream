package com.kennyb1201.kbstream.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.settings.AppPreferences

/**
 * App-wide poster tile sizing, chosen in Settings → Display ("Poster Size").
 * Every poster grid and rail shares one width so screens feel consistent;
 * heights keep the 2:3 poster aspect. Medium is the default.
 */
enum class PosterSize(val width: Dp, val height: Dp) {
    SMALL(110.dp, 165.dp),
    MEDIUM(124.dp, 186.dp),
    LARGE(140.dp, 210.dp);

    companion object {
        /** 0 = small, 1 = medium (default), 2 = large (matches Settings). */
        fun fromPref(value: Long): PosterSize =
            when (value.toInt()) {
                0 -> SMALL
                2 -> LARGE
                else -> MEDIUM
            }
    }
}

/** Current poster size from prefs, remembered for the composition. */
@Composable
fun rememberPosterSize(): PosterSize {
    val context = LocalContext.current
    return remember { PosterSize.fromPref(AppPreferences.getPosterSize(context)) }
}
