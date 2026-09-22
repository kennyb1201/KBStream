package com.kennyb1201.kbstream.ui.player

import kotlin.math.pow

/**
 * Live audio tuning shared by the settings screen, the in-player panel and the
 * processor that runs inside the audio sink.
 *
 * The values are plain `@Volatile` fields, read per sample by
 * [AudioDownmixProcessor] on the audio thread: changing a knob takes effect on
 * the next buffer — no player rebuild, no gap in playback. That is the whole
 * reason the state lives here instead of being baked into the processor when the
 * sink is built (a processor that is constructed with fixed settings cannot be
 * re-tuned without tearing the player down, which a TV app must never do while
 * a viewer is dragging a pill).
 *
 * Two sources write here:
 *  - the global settings (**Settings → Video & Audio**), and
 *  - the per-file override in the player's own panel, which the activity resolves
 *    against the global value when a session starts (see [PlayerTrackBridge]).
 */
internal object PlayerAudioTuning {

    /** 0 = leave the layout alone, 2 = stereo, 6 = 5.1. */
    const val DOWNMIX_AUTO = 0
    const val DOWNMIX_STEREO = 2
    const val DOWNMIX_SURROUND = 6

    /**
     * Every option list the UI shows lives here, so the settings screen and the
     * player panel cannot drift apart.
     *
     * "Auto" is the behaviour the app had before this existed: the decoded
     * layout goes to the sink untouched and the device (its HAL, or Android
     * AudioTrack) downmixes if it has to — a plain matrix with no centre lift,
     * which is exactly why dialogue sits under the music on a TV's own speakers.
     */
    val DOWNMIX_OPTIONS: List<Pair<String, Int>> =
        listOf(
            "Auto" to DOWNMIX_AUTO,
            "Stereo" to DOWNMIX_STEREO,
            "5.1" to DOWNMIX_SURROUND
        )

    /** 0 = off, 1 = low, 2 = high. */
    val DIALOGUE_OPTIONS: List<Pair<String, Int>> =
        listOf(
            "Off" to 0,
            "Low" to 1,
            "High" to 2
        )

    /** Extra output gain in dB, applied ahead of the limiter. */
    val VOLUME_OPTIONS: List<Pair<String, Int>> =
        listOf(
            "Off" to 0,
            "+3" to 3,
            "+6" to 6,
            "+9" to 9,
            "+12" to 12,
            "+15" to 15
        )

    /** Output layout the sink should carry. */
    @Volatile
    var downmixTarget: Int = DOWNMIX_AUTO

    /** Dialogue / centre-channel lift. */
    @Volatile
    var dialogueBoost: Int = 0

    /** Overall gain in dB (0-15) for content that is simply mixed too quiet. */
    @Volatile
    var volumeBoostDb: Int = 0

    /**
     * Center-channel gain for a 5.1/7.1 downmix. 0.7071 is the standard ITU
     * coefficient (centre at the same perceived level as a front channel);
     * the boost multiplies on top of it, and the centre channel is where
     * dialogue lives in every film/TV mix.
     */
    val centerGain: Float
        get() = CENTER_BASE * (1f + 0.5f * dialogueBoost)

    /**
     * Mid-channel lift for STEREO sources. A 2.0 track has no centre channel:
     * dialogue sits in the phantom centre, which is exactly the "mid" component
     * (L+R)/2 while music beds and wide effects sit in the "side" (L-R)/2. So
     * lifting mid raises voices without dragging up the whole mix.
     */
    val midGain: Float
        get() = 1f + 0.35f * dialogueBoost

    /** Linear gain for [volumeBoostDb]. */
    val linearGain: Float
        get() =
            if (volumeBoostDb <= 0) {
                1f
            } else {
                10f.pow(volumeBoostDb / 20f)
            }

    /** True when every knob is at its default: the processor becomes a pass-through. */
    val isNeutral: Boolean
        get() = downmixTarget == DOWNMIX_AUTO && dialogueBoost == 0 && volumeBoostDb == 0

    /** Applies values resolved from the global defaults plus any per-file override. */
    fun apply(
        downmixTarget: Int,
        dialogueBoost: Int,
        volumeBoostDb: Int
    ) {
        this.downmixTarget = downmixTarget
        this.dialogueBoost = dialogueBoost
        this.volumeBoostDb = volumeBoostDb
    }

    /** Standard ITU-R BS.775 centre coefficient (1/sqrt(2)). */
    const val CENTER_BASE = 0.7071f

    /** Surround coefficient, from the same standard. */
    const val SURROUND_BASE = 0.7071f

    /** LFE coefficient — matches the platform's own downmix so bass is unchanged. */
    const val LFE_BASE = 0.5f
}
