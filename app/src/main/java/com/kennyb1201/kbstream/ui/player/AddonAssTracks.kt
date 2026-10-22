package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * The naming that lets the player recognise an addon subtitle track from the
 * format media3 exposes.
 *
 * An addon subtitle is a sidecar, so it renders as flattened cues on the
 * ExoPlayer engine - unless an ASS/SSA one is routed to the libass overlay (see
 * the ASS section of NativePlayerActivity). To route it, the activity has to
 * name the track it selected back to the addon URL it came from, and the only
 * handle a selected track gives is its [Format] `id`: the configuration id the
 * controller attached when it built the track.
 *
 * EVERY addon track is tagged, not just the ASS ones, so a format that is not
 * tagged is positively "not one of ours" instead of merely unexplained. That is
 * the point of the whole object. The claim used to fall back to language +
 * label when the id was absent, which read an embedded container track as the
 * addon's ASS sidecar whenever the two were called the same thing - "English
 * (SDH)" is what both sides call the same subtitle - and the overlay then
 * loaded the offer and swallowed the real track's cues for the whole episode.
 * This player does carry a tag through to selection time (the user's own
 * sidecar is recognised by its constant id), so the fallback was never needed.
 * With it gone, a track nobody tagged is never claimed, and a lost id costs
 * typesetting (the track renders flattened) rather than the track itself: a
 * recoverable, visible failure rather than an invisible one.
 *
 * Pure on purpose: the tagging decision and the id round-trip are the parts
 * that fail silently, so they are unit tested without a player.
 */
internal object AddonAssTracks {

    /** Marks a subtitle configuration id as an addon ASS/SSA track. */
    const val CONFIG_ID_PREFIX = "kbstream-ass:"

    /**
     * Marks a subtitle configuration id as an addon sidecar that is NOT
     * ASS/SSA, so every track the addons published is identifiable and no
     * language + label guess is ever needed.
     */
    const val SUBTITLE_CONFIG_ID_PREFIX = "kbstream-sub:"

    /** The config id to set for [url] when [mime] is ASS/SSA, else null. */
    fun configIdFor(mime: String, url: String): String? =
        if (mime == MimeTypes.TEXT_SSA && url.isNotBlank()) CONFIG_ID_PREFIX + url else null

    /**
     * The config id to set for ANY addon offer: [configIdFor] for an ASS/SSA
     * file, the sibling prefix for everything else. Null only for an offer with
     * no URL to name.
     */
    fun trackConfigIdFor(mime: String, url: String): String? =
        configIdFor(mime, url) ?: if (url.isNotBlank()) SUBTITLE_CONFIG_ID_PREFIX + url else null

    /**
     * The addon ASS URL a config id names, or null when it is not one of ours.
     *
     * Deliberately ASS-only: a non-ASS addon track is identifiable but is never
     * claimed by the overlay.
     */
    fun urlFromConfigId(id: String?): String? =
        id?.takeIf { it.startsWith(CONFIG_ID_PREFIX) }
            ?.removePrefix(CONFIG_ID_PREFIX)
            ?.takeIf { it.isNotBlank() }

    /**
     * The addon ASS source URL [format] names, or null when it is not an addon
     * ASS track.
     *
     * The configuration id is the ONLY basis. No language, label, MIME type or
     * resemblance to a published offer may stand in for it: those are what
     * mis-claimed an embedded track as an addon sidecar.
     */
    fun assSourceFor(format: Format): String? = urlFromConfigId(format.id)
}
