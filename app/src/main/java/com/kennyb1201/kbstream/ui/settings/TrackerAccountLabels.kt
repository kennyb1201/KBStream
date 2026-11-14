package com.kennyb1201.kbstream.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.trakt.TraktRepository

/**
 * The connected account's name for the tracker rows that resolve one lazily
 * (spec 2026-11-14, F1).
 *
 * The anime trackers already carry theirs (AniList and MAL in their auth
 * objects, Kitsu in [com.kennyb1201.kbstream.data.kitsu.KitsuAuth.viewerName]),
 * and BingeCat's mapping already stores the profile's name - but Simkl and Trakt
 * only ever read the account for the connect screen's "signed in as" line, so
 * their Settings rows said nothing about WHICH account this device holds.
 *
 * Both helpers follow the same discipline the anime rows do, because it is the
 * rule the spec sets: cached first (the token store's own copy, so a force-stop
 * or an offline Settings entry still names the account), and ONE network read
 * only when no cache exists. A failure returns null, and the row falls back to
 * its plain service name - never a blank, never a token, never a hang on a
 * network that is not there. The effect is keyed on the connection state and the
 * caller's seed (profile + sync revision), so entering Settings after a
 * reconnect or a profile switch re-asks.
 */
@Composable
internal fun rememberSimklAccountName(connected: Boolean, seed: Any?): String? {
    val context = LocalContext.current
    val repository = remember(context) { SimklRepository.getInstance(context) }
    var name by remember { mutableStateOf(repository.accountName()) }
    LaunchedEffect(connected, seed) {
        if (!connected) {
            name = null
            return@LaunchedEffect
        }
        name = repository.accountName()
            ?: runCatching { repository.accountDisplayName() }.getOrNull()
    }
    return name
}

@Composable
internal fun rememberTraktAccountName(connected: Boolean, seed: Any?): String? {
    val context = LocalContext.current
    val repository = remember(context) { TraktRepository.getInstance(context) }
    var name by remember { mutableStateOf(repository.accountSlug()) }
    LaunchedEffect(connected, seed) {
        if (!connected) {
            name = null
            return@LaunchedEffect
        }
        // loadAccountSlug is the connect screen's own read, and it caches: the
        // cached value above already answered in the common case, so this only
        // runs while the name has never been resolved.
        name = repository.accountSlug()
            ?: runCatching { repository.loadAccountSlug() }.getOrNull()
    }
    return name
}

/**
 * The mapped BingeCat profile's name (spec 2026-11-14, F4).
 *
 * No request at all: the mapping prefs already cache the name beside the id, and
 * the cloud applier writes both, so this answers from disk on every surface and
 * follows a re-map on the next entry to the pane. Null when the profile is not
 * mapped (the row already has a state for that) or the session is gone.
 */
@Composable
internal fun rememberBingeCatProfileName(seed: Any?): String? {
    val context = LocalContext.current
    val name = remember(seed) {
        val repository = com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
            .getInstance(context)
        if (repository.hasToken() && repository.mappedProfileId() != null) {
            repository.mappedProfileName()
        } else {
            null
        }
    }
    return name
}
