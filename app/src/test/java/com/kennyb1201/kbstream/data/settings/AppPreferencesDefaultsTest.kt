package com.kennyb1201.kbstream.data.settings

import com.kennyb1201.kbstream.ui.components.PosterBorder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The data layer stores a couple of values whose meaning is owned by a UI
 * type: the poster border strength is a `PosterBorder` ordinal, and the audio
 * knobs are the constants in `PlayerAudioTuning`. Moving the store into
 * `data.settings` (so the data layer no longer imports `ui`) broke the direct
 * references those defaults used to have.
 *
 * The constants are spelled out in the data layer instead, and these tests are
 * what keeps the two sides from drifting: if someone adds a border level or
 * reorders the enum, the ordinal check fails rather than silently changing
 * what every existing install draws.
 */
class AppPreferencesDefaultsTest {

    @Test
    fun `the poster border default matches the UI enum`() {
        assertEquals(
            PosterBorder.DEFAULT.ordinal,
            AppPreferences.DEFAULT_POSTER_BORDER_STRENGTH
        )
    }

    @Test
    fun `the audio encodings match the player tuning constants`() {
        assertEquals(AudioDefaults.DOWNMIX_AUTO, 0)
        assertEquals(AudioDefaults.DOWNMIX_STEREO, 2)
        assertEquals(AudioDefaults.DOWNMIX_SURROUND, 6)
        assertEquals(AudioDefaults.AUDIO_OUTPUT_AUTO, 0)
        assertEquals(AudioDefaults.AUDIO_OUTPUT_PASSTHROUGH, 1)
        assertEquals(AudioDefaults.AUDIO_OUTPUT_DECODE, 2)
        assertEquals(AudioDefaults.DIALOGUE_MAX, 4)
    }
}
