package com.kennyb1201.kbstream.data.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The profile the viewer has CHOSEN in this run of the app.
 *
 * [ProfileStorage.activeProfileId] answers a different question: it is the
 * profile the app is currently bound to, and it falls back to the last one used
 * at startup - including a cold launch that is still sitting on Who's Watching,
 * and a headless worker that has no UI at all. Neither is a choice, and code
 * that writes a viewer's data (the TorBox cloud→Library sync) must not treat
 * either as one: the field report was a household TorBox account mirrored into
 * the profile that happened to be ambient when a launch round ran.
 *
 * So the choice is recorded here, by the UI layers that actually make it:
 *
 *  - [MainActivity] enters the active profile when the launch skips the picker,
 *    and clears it while the picker is on screen;
 *  - the picker's select handler enters the profile it just activated.
 *
 * Process-lifetime on purpose: a fresh process (a scheduled worker after the app
 * was killed, a cold start) begins with no live profile, which is exactly the
 * "defer the adds" state. Nothing persists - the last-active id is already
 * persisted elsewhere and must not be read as this.
 */
internal object ProfileSession {

    private val _liveProfileId = MutableStateFlow<String?>(null)

    /** The chosen profile, or null while nobody has chosen one. */
    val liveProfileIdFlow: StateFlow<String?> = _liveProfileId.asStateFlow()

    /** Records the viewer's choice; null means "no choice right now". */
    fun enter(profileId: String?) {
        _liveProfileId.value = profileId?.takeIf { it.isNotBlank() }
    }

    /**
     * The live profile, but only while it is STILL the profile this device is
     * bound to: a profile deleted or switched underneath the session must not
     * keep receiving writes under its old id.
     */
    fun liveProfileId(context: Context): String? {
        val live = _liveProfileId.value?.takeIf { it.isNotBlank() } ?: return null
        return live.takeIf { it == ProfileStorage.activeProfileId(context) }
    }
}
