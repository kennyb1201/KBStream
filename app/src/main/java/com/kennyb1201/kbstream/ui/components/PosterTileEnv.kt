package com.kennyb1201.kbstream.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier

/**
 * The two process-wide values a poster tile reads, collected ONCE for the shell.
 *
 * A tile needs the synced-preference revision (to re-read device display prefs
 * when a Settings change - or a pulled blob - lands) and the image-override
 * mirror (to draw the art the viewer chose). Both used to be collected by every
 * tile: a 24-card rail opened 24 subscriptions on the same `StateFlow`s and
 * recomposed 24 cards for one change, and the Detail episode rail's watched
 * flags did the same through a second per-card flow.
 *
 * [PosterTileHost] collects each exactly once, at the top of the composition,
 * and publishes them here. The helpers below fall back to collecting directly
 * when no host is above them, so a card can still be composed on its own (a
 * preview, a test, or a screen mounted outside the shell).
 */

/** The synced-pref revision, hoisted by [PosterTileHost]. Null when absent. */
internal val LocalPosterTileRevision = compositionLocalOf<Long?> { null }

/** The image-override mirror, hoisted by [PosterTileHost]. Null when absent. */
internal val LocalPosterTileImagePaths = compositionLocalOf<Map<String, String>?> { null }

/**
 * The revision a tile keys its preference reads on.
 *
 * Inside the shell this is a plain read of the host's value; outside it, the
 * per-composition collect it always used to be.
 */
@Composable
internal fun posterTileRevision(): Long {
    LocalPosterTileRevision.current?.let { return it }
    val revision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    return revision
}

/**
 * One collect of the pref revision and one of the image-override mirror, for
 * every poster surface below.
 *
 * Placed once, around the app's screen host (see MainActivity). It also starts
 * the override mirror's load for the active profile, which used to be started
 * by the first tile that composed.
 */
@Composable
fun PosterTileHost(content: @Composable () -> Unit) {
    val revision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        ImageOverrides.ensureLoaded(context)
    }
    val paths by ImageOverrides.paths.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalPosterTileRevision provides revision,
        LocalPosterTileImagePaths provides paths,
        content = content
    )
}
