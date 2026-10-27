package com.kennyb1201.kbstream.ui.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.sync.ProfileManager

/**
 * The active profile id, as the composition key for a Settings row that
 * mirrors a device-local preference.
 *
 * Settings rows split in two. A SYNCED row is seeded through
 * `PrefsPayloadApplier.revision`, so a blob pulled from another box re-seeds it
 * where it stands. A DEVICE-LOCAL row is deliberately bare - decoder, buffer
 * mode, engine, aspect ratio, the API keys, tunnelling, PiP - because another
 * box's answer must never move a control that describes this one (see
 * `PrefsPayloadBuilder.EXCLUDED_PREF_KEYS`).
 *
 * That reasoning covers a push from another DEVICE. It does not cover a profile
 * switch on this one: the store behind every one of those rows is per profile
 * (`ProfileStorage.prefsName`), so picking a kids profile while Settings was
 * open left the audio decoder, buffer mode, aspect ratio and the four key fields
 * showing the profile that had just been left, until Settings was closed and
 * reopened. The screen's own comment already records one instance of this class
 * fixed per-row - "switching to a kids profile left this row showing the previous
 * profile's lock state" - which is what this key generalises.
 *
 * Keying a row on this re-seeds it exactly once per real switch, and nothing is
 * lost by that, because every handler writes its flip straight through to prefs
 * (the in-memory state is a mirror of the store, not a buffer for it). The one
 * exception is an API-key field with text typed but not yet saved: that text
 * belonged to the profile being left, so dropping it is the correct answer.
 *
 * Returns null before profiles exist at all; a row keyed on null is seeded from
 * the profile-scoped store exactly as before, and re-seeds when the id arrives.
 */
@Composable
internal fun rememberProfileRowKey(): String? =
    ProfileManager.activeProfile.collectAsStateWithLifecycle().value?.id
