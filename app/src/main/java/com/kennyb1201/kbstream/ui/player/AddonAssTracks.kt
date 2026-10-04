package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes

/**
 * The naming that lets the player recognise an addon ASS/SSA track from the
 * format media3 exposes.
 *
 * An addon ASS track is a sidecar, so it renders as flattened cues on the
 * ExoPlayer engine — unless it is routed to the libass overlay (see the ASS
 * section of NativePlayerActivity). To route it, the activity has to name the
 * track it selected back to the addon URL it came from, and the only handle a
 * selected track gives is its [androidx.media3.common.Format]: the config `id`
 * (when media3 carries it through) or, failing that, the language + label pair.
 * Both spellings are built here so the tag and the lookup cannot drift.
 *
 * Pure on purpose: the tagging decision and the id round-trip are the parts
 * that fail silently (an untagged ASS offer just renders flattened again), so
 * they are unit tested without a player.
 */
internal object AddonAssTracks {

    /** Marks a subtitle configuration id as an addon ASS track. */
    const val CONFIG_ID_PREFIX = "kbstream-ass:"

    /** The config id to set for [url] when [mime] is ASS/SSA, else null. */
    fun configIdFor(mime: String, url: String): String? =
        if (mime == MimeTypes.TEXT_SSA && url.isNotBlank()) CONFIG_ID_PREFIX + url else null

    /** The addon URL a config id names, or null when it is not one of ours. */
    fun urlFromConfigId(id: String?): String? =
        id?.takeIf { it.startsWith(CONFIG_ID_PREFIX) }
            ?.removePrefix(CONFIG_ID_PREFIX)
            ?.takeIf { it.isNotBlank() }

    /**
     * The fallback key: language + label, lowercased, for the case where media3
     * does not carry the config id through to the track's [androidx.media3.common.Format].
     */
    fun languageKey(language: String?, label: String?): String =
        language.orEmpty().lowercase() + "|" + label.orEmpty().lowercase()
}
