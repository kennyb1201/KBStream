package com.kennyb1201.kbstream.ui.addons

import com.kennyb1201.kbstream.data.addon.InstalledAddon

/*
 * The one rule the Add-ons screen's list renders by: what counts as one row,
 * and what each row is keyed by.
 *
 * It lives here, pure, because getting it wrong is a crash rather than a
 * cosmetic bug - a duplicate LazyColumn key throws
 * `IllegalArgumentException: Key "..." was already used` and takes the whole
 * screen down with it (Sentry ANDROID-T/V), which is exactly what happened
 * while this rule was `key = { addon -> addon.id }` inline in the composable.
 */

/**
 * One row per installed add-on, in the order they were installed.
 *
 * An add-on's [InstalledAddon.id] comes from its own MANIFEST, and nothing stops
 * two installs from reporting the same one: the manager itself matches entries
 * by manifest URL *or* id (see `AddonManager.updateAddonFromManifest`), which is
 * only necessary because the two can disagree. Keying the list by the id alone
 * therefore crashed on a profile holding two such installs.
 *
 * [InstalledAddon.manifestUrl] is the identity every other part of the add-on
 * layer already agrees on - the install path, `importAddons`, the manifest
 * refresh's match - so it is what this list is keyed by. Deduplicating by it
 * first means a list that somehow holds the same URL twice (hand-edited prefs, a
 * profile merge) still renders instead of throwing: the second entry IS the same
 * add-on.
 *
 * Pure, so the collision rule is pinned without a Compose test harness.
 */
internal fun distinctAddonRows(addons: List<InstalledAddon>): List<InstalledAddon> =
    addons.distinctBy { it.manifestUrl }
