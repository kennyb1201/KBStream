package com.kennyb1201.kbstream.data.settings

/**
 * The stored values behind the audio prefs.
 *
 * These are the numbers a pref file actually contains, so they belong on the
 * data side of the app: the settings store reads and writes them, the sync
 * payload builder carries them, and the player's live tuning
 * (`ui.player.PlayerAudioTuning`) reads them back. They live here rather than
 * in the player because a plain settings read must not have to reach into the
 * UI layer to know what an Int means - and the player aliases them, so there
 * is still exactly one definition of each value.
 *
 * Only the encoding lives here. The player keeps the live `@Volatile` fields
 * and the gain math, which are runtime state and not prefs.
 */
object AudioDefaults {

    /** Downmix target: 0 = leave the layout alone, 2 = stereo, 6 = 5.1. */
    const val DOWNMIX_AUTO = 0
    const val DOWNMIX_STEREO = 2
    const val DOWNMIX_SURROUND = 6

    /**
     * Decode-vs-passthrough: whose decoder runs the original audio.
     * Auto leaves the choice to the output device, Passthrough always
     * bitstreams, Decode always hands PCM to the app's own chain.
     */
    const val AUDIO_OUTPUT_AUTO = 0
    const val AUDIO_OUTPUT_PASSTHROUGH = 1
    const val AUDIO_OUTPUT_DECODE = 2

    /**
     * The top of the dialogue-boost scale. 4 is where it stops on purpose: the
     * lift is not a limiter, and the player trims the surrounds by 0.2 per
     * level to pay for it, which would reach zero (surrounds gone rather than
     * lowered) at 5.
     */
    const val DIALOGUE_MAX = 4
}
