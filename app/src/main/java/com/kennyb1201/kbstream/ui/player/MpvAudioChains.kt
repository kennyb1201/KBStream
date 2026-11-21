package com.kennyb1201.kbstream.ui.player

import java.util.Locale

/**
 * The audio strings this engine hands mpv: its `af` filter chain, the output
 * layout, and the formats it may bitstream.
 *
 * Pure and extracted for the same reason [SoftwareAudioRules] is: a filter spec
 * that mpv rejects is silent (the chain simply does not apply, and the viewer
 * hears an unchanged mix), so the only cheap way to be sure the strings are
 * right is to pin them without a TV. The parity audit of 2026-10-18 is what put
 * the boost's limiter and this object's single-owner rule (`af` has exactly one
 * writer) together.
 */
internal object MpvAudioChains {

    /** FL, FR, FC, ... - the center is channel 3 in every layout that has one. */
    const val CENTER_CHANNEL_INDEX = 2

    /**
     * Encoded formats mpv may bitstream when the viewer asks for passthrough:
     * the same set the app's own FFmpeg extension exists for, plus AC-3/E-AC-3
     * that any receiver takes. Anything not named here is decoded to PCM, which
     * is what the downmix / dialogue / volume chain needs in order to be heard.
     */
    const val SPDIF_CODECS = "ac3,eac3,dts,dts-hd,truehd"

    /**
     * Night Mode's filter: FFmpeg's Dynamic Audio Normalizer, which is built for
     * exactly this job - frame-based gain riding, so quiet passages come up and
     * loud ones are held down.
     *
     * The short frame (150 ms, the starting point in mpv night-mode practice)
     * keeps it responsive to a scene rather than an act; `peak=0.95` is FFmpeg's
     * own default ceiling; `maxgain=2` caps the amplification at ~+6 dB, the
     * spec's makeup figure - `maxgain` is a LINEAR factor (default 10), not dB.
     * Both names/units were verified against the FFmpeg 8.1 filter reference, and
     * `dynaudnorm` itself was verified present in the shipped AAR's libavfilter
     * (FFmpeg n8.1.1) before being relied on - see the NightMode contract test.
     *
     * These are STARTING values: the ear test is the arbiter and the string is
     * the one place to change them.
     */
    const val NIGHT_MODE_FILTER = "dynaudnorm=framelen=150:maxgain=2:peak=0.95"

    /**
     * The whole `af` chain for this session, or null when nothing is on.
     *
     * Order matters and is the point of composing it here: the dialogue lift
     * first, then Night Mode's gain riding, then the volume boost's gain and its
     * limiter - the limiter AFTER the gain is the only arrangement in which it
     * can see the compressed and boosted samples, and it is the counterpart of
     * the main player's own limiter sitting after its own compression and boost.
     * `af` is a single property with a single owner, so every filter is written
     * together or one would erase the others.
     */
    fun audioFilterSpec(
        dialogueLevel: Int,
        channels: Int,
        volumeBoostDb: Int,
        nightMode: Boolean
    ): String? {
        val spec = listOfNotNull(
            dialogueFilterSpec(dialogueLevel, channels),
            if (nightMode) NIGHT_MODE_FILTER else null,
            volumeBoostSpec(volumeBoostDb)
        )
        return spec.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    /**
     * The `pan` spec for [level] at [channels], or null for "no filter".
     *
     * Only the center channel is lifted on a multichannel mix, and the mid
     * component on a stereo one: `mid = (L+R)/2` is where a 2.0 track keeps its
     * voices while music beds sit in `(L-R)/2`, so lifting mid raises dialogue
     * without dragging up the whole mix - exactly what [PlayerAudioTuning.midGain]
     * and [PlayerAudioTuning.inPlaceCenterGain] express on the other engine.
     *
     * Channels are named by index, and the center is `c2` in every layout that
     * HAS one (FFmpeg's 5.0, 5.1 and 7.1 all put FC third), which keeps this
     * independent of whether the file declares 5.1 or 5.1(side). The layouts
     * with no center are deliberately left alone rather than guessed at: mono
     * (nothing to lift) and 4.0 (quad is FL/FR/BL/BR - lifting "the center"
     * there would have to be invented, and inventing one is how a surround
     * field gets mangled).
     */
    fun dialogueFilterSpec(level: Int, channels: Int): String? {
        if (level <= 0) return null
        val gain = 1f + 0.35f * level
        return when (channels) {
            1 -> null
            2 -> {
                val same = gainText((gain + 1f) / 2f)
                val cross = gainText((gain - 1f) / 2f)
                "pan=stereo|c0=$same*c0+$cross*c1|c1=$cross*c0+$same*c1"
            }
            5 -> passThroughWithCenterLift("5.0", 5, gainText(gain))
            6 -> passThroughWithCenterLift("5.1", 6, gainText(gain))
            8 -> passThroughWithCenterLift("7.1", 8, gainText(gain))
            else -> null
        }
    }

    /**
     * Gain then limiter for [db], or null when the boost is off.
     *
     * `alimiter` with `level=disabled` is a pure peak limiter: with mpv's own
     * auto-leveling it would re-level the whole track, a different (and audible)
     * edit than the one the viewer asked for. The limit sits just under full
     * scale so the sample-format conversion after the chain still has headroom.
     */
    fun volumeBoostSpec(db: Int): String? =
        if (db <= 0) null else "volume=${db}dB,alimiter=level=disabled:limit=0.97"

    /**
     * The `audio-channels` value for a downmix [target], given what this device's
     * output can carry.
     *
     * Auto is the DEVICE's own answer, not mpv's `auto-safe`: the main player
     * folds a film down to what the output can actually carry (see
     * [resolveDeviceMaxChannels]) because keeping 5.1 on a stereo TV leaves
     * dialogue under the score - and `auto-safe` is free to do exactly that, as
     * it prefers the source layout whenever the output might fold it itself.
     * Both engines therefore fold to the same layout on the same box.
     */
    fun downmixLayout(target: Int, deviceMaxChannels: Int): String = when (target) {
        PlayerAudioTuning.DOWNMIX_STEREO -> "stereo"
        PlayerAudioTuning.DOWNMIX_SURROUND -> "5.1"
        else -> if (deviceMaxChannels >= 6) "5.1" else "stereo"
    }

    /**
     * The `audio-spdif` value for this session: what mpv may hand to the output
     * untouched, or an empty string to keep everything decoded.
     *
     * [tuningNeutral] is the caller's answer to "is the downmix / dialogue /
     * volume / Night Mode chain doing anything for this session": Auto passes
     * the bitstream through only while it is NOT, because passthrough never
     * reaches the app's PCM chain and would silently discard a change the viewer
     * asked for - the same rule [PlayerAudioTuning.requiresDecode] applies on the
     * other engine.
     *
     * [nightMode] outranks even an explicit Passthrough, exactly as
     * [PlayerAudioTuning.requiresDecode] does on the Exo side and as Night Mode's
     * Settings description promises: the gain rider cannot touch a bitstream, so
     * a mode that cannot be honoured would change a number and nothing audible.
     * Turning Night Mode off gives the bitstream back.
     */
    fun audioSpdifSpec(
        outputMode: Int,
        tuningNeutral: Boolean,
        nightMode: Boolean
    ): String = when {
        nightMode -> ""
        outputMode == PlayerAudioTuning.AUDIO_OUTPUT_DECODE -> ""
        outputMode == PlayerAudioTuning.AUDIO_OUTPUT_PASSTHROUGH -> SPDIF_CODECS
        tuningNeutral -> SPDIF_CODECS
        else -> ""
    }

    /** [count] channels of the [layout], all passed through bar the center. */
    private fun passThroughWithCenterLift(layout: String, count: Int, gain: String): String =
        buildString {
            append("pan=").append(layout)
            for (index in 0 until count) {
                append("|c").append(index).append('=')
                if (index == CENTER_CHANNEL_INDEX) append(gain).append('*')
                append('c').append(index)
            }
        }

    /** Four decimals, locale-proof: mpv's filter parser wants a plain number. */
    private fun gainText(value: Float): String = String.format(Locale.US, "%.4f", value)
}
