package com.kennyb1201.kbstream.data.image

import android.content.Context
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.kennyb1201.kbstream.data.settings.AppPreferences

/**
 * Hardware bitmaps for the full-bleed BACKDROPS, and nowhere else.
 *
 * The A/B on the field TV (0.9.422, two pastes six minutes apart) is why this is
 * not a global switch any more, even though hardware decoding was measurably
 * faster: worst decode 2647ms -> 1258ms, the over-33ms share 94% -> 82%, card
 * disk decodes 104 -> 57. What it also did was take memory the app could no
 * longer get back - `native 15MB -> 83MB`, and
 *
 *     image cache: 7MB of 48MB hero=5MB/3 other=55MB/342 live=60MB
 *
 * with RUNNING_CRITICAL and RUNNING_LOW trims in the same short session.
 *
 * The cap was NOT the thing that broke: `size` counts the strong cache and
 * `live` adds the evicted values the GC has not collected yet (see
 * [com.kennyb1201.kbstream.data.memory.ImageCacheComposition]), and the graded
 * trim did its job - 48MB of strong entries down to 7MB. What it cannot do is
 * free an evicted HARDWARE bitmap: its pixels are native, so evicting it creates
 * no Java-heap pressure and nothing collects it. 53MB of dead-but-alive art sat
 * outside the budget until the system trimmed the app, which on this TV means
 * re-decoding the rail it just took away. In software mode the identical churn
 * was reclaimed immediately - the other paste reports `live` equal to the total
 * and no trims at all.
 *
 * So hardware is scoped to the surfaces a screen holds ONE of: the Home hero and
 * the Detail hero (TV and phone). Those are where the decode cost actually
 * lands - full-bleed, on the frame the viewer is waiting for - and a few bitmaps
 * of churn per screen cannot accumulate into the tens of MB that hundreds of
 * rail tiles did.
 *
 * Tiles, posters, logos, brand marks and the artwork-tint samples stay software
 * unconditionally; the tint path needs readable pixels anyway (see
 * [com.kennyb1201.kbstream.ui.theme.ArtworkTint]).
 *
 * Read per request, but the setting only takes effect on the next launch: the
 * loader is built once, from [AppPreferences.getSoftwareImageBitmaps].
 */
fun ImageRequest.Builder.allowBackdropHardware(context: Context): ImageRequest.Builder =
    allowHardware(!AppPreferences.getSoftwareImageBitmaps(context))
