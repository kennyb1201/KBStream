package com.kennyb1201.kbstream.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier

/**
 * The viewer's clock convention: true when settings say 24-hour.
 *
 * Read ONCE per surface and passed down as a value, so the rule functions stay
 * pure and a clock format is an input to the layout rather than a global they
 * reach for. Every clock the app draws - the guide's cells, its header, the
 * catch-up caption, the sports hub, the player's zap banner - takes the same
 * flag from the same setting, which is what keeps a screen from disagreeing
 * with the clock in its own header.
 *
 * Keyed on the sync revision as well as the composition: this preference syncs
 * between devices (see `SyncPrefsPayload`'s `use_24h_clock`), so a value that
 * arrives from the account has to repaint a guide that is already open in
 * Settings' place.
 */
@Composable
fun rememberUse24HourClock(): Boolean {
    val context = LocalContext.current
    val revision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    return remember(context, revision) { AppPreferences.getUse24HourClock(context) }
}
