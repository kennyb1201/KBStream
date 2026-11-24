package com.kennyb1201.kbstream.data.player

import com.kennyb1201.kbstream.data.settings.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which engine a launch opens, once the "Play Anime in MPV" and "Play 24 fps
 * titles in MPV" settings are folded into the stored choice. The decision is
 * split out of the Context-reading wrapper so the matrices can be pinned here.
 */
class PlayerEngineTest {

    private fun launch(
        chosen: Int = AppPreferences.PLAYER_ENGINE_EXO,
        mpvAvailable: Boolean = true,
        mpvForAnime: Boolean = false,
        isAnime: Boolean = false
    ): Boolean = PlayerEngine.launchPrefersMpv(
        chosenEngine = chosen,
        mpvAvailable = mpvAvailable,
        mpvForAnime = mpvForAnime,
        isAnime = isAnime
    )

    @Test
    fun `the stored MPV choice opens every title in MPV`() {
        assertTrue(launch(chosen = AppPreferences.PLAYER_ENGINE_MPV))
        assertTrue(launch(chosen = AppPreferences.PLAYER_ENGINE_MPV, isAnime = true))
    }

    @Test
    fun `a device without libmpv never opens MPV`() {
        assertFalse(
            launch(
                chosen = AppPreferences.PLAYER_ENGINE_MPV,
                mpvAvailable = false
            )
        )
        assertFalse(
            launch(
                chosen = AppPreferences.PLAYER_ENGINE_EXO,
                mpvAvailable = false,
                mpvForAnime = true,
                isAnime = true
            )
        )
    }

    @Test
    fun `the anime setting opens anime in MPV on the default engine`() {
        assertTrue(launch(mpvForAnime = true, isAnime = true))

        // Everything that is not anime keeps following the stored choice.
        assertFalse(launch(mpvForAnime = true, isAnime = false))
        // And the setting off means anime plays where every other title does.
        assertFalse(launch(mpvForAnime = false, isAnime = true))
    }

    /**
     * "ExoPlayer only" is about not changing engine MID-TITLE; the anime setting
     * is an explicit statement about which engine should OPEN anime, so it wins.
     * This is pinned here because it is the one combination that looks like a
     * contradiction on the settings screen, and the row's copy says so.
     */
    @Test
    fun `the anime setting outranks ExoPlayer only`() {
        assertTrue(
            launch(
                chosen = AppPreferences.PLAYER_ENGINE_EXO_ONLY,
                mpvForAnime = true,
                isAnime = true
            )
        )

        assertFalse(
            launch(
                chosen = AppPreferences.PLAYER_ENGINE_EXO_ONLY,
                mpvForAnime = true,
                isAnime = false
            )
        )
    }

    /** The 24 fps arm: the toggle on, and a reading the probe could have made. */
    private fun film(
        chosen: Int = AppPreferences.PLAYER_ENGINE_EXO,
        mpvAvailable: Boolean = true,
        mpvFor24Fps1080: Boolean = true,
        fps: Double? = 23.976,
        height: Int? = 1_080,
        isDolbyVision: Boolean = false
    ): Boolean = PlayerEngine.launchPrefersMpv(
        chosenEngine = chosen,
        mpvAvailable = mpvAvailable,
        mpvForAnime = false,
        isAnime = false,
        mpvFor24Fps1080 = mpvFor24Fps1080,
        fps = fps,
        height = height,
        isDolbyVision = isDolbyVision
    )

    @Test
    fun `the toggle off leaves a 24 fps 1080p file on the stored engine`() {
        assertFalse(film(mpvFor24Fps1080 = false))
    }

    @Test
    fun `a measured 24 fps 1080p file opens in MPV`() {
        assertTrue("23.976 is the rate a 24p WEB-DL declares", film(fps = 23.976))
        assertTrue("and 24.000 is the same cadence", film(fps = 24.0))
        assertTrue("a 720p release is below the ceiling, not above it", film(height = 720))
        // The probe normalises, so extractor noise has to land on the same arm.
        assertTrue(film(fps = 23.98))
        assertTrue(film(fps = 23.976023))
    }

    @Test
    fun `4K stays on the stored engine`() {
        assertFalse("mpv on this GPU cannot spare a 4K frame", film(height = 2_160))
        assertFalse(film(height = 1_440))
    }

    @Test
    fun `Dolby Vision stays on the stored engine`() {
        assertFalse(
            "mpv has no DV path; the passthrough and conversion are ExoPlayer's",
            film(isDolbyVision = true)
        )
    }

    @Test
    fun `an unmeasured file fails closed`() {
        assertFalse("no rate reading", film(fps = null))
        assertFalse("no height declared", film(height = null))
        assertFalse("an absurd rate is not content", film(fps = 0.0))
    }

    @Test
    fun `only film cadence is in band`() {
        assertFalse("25 fps is a different standard rate, not a sloppier 24", film(fps = 25.0))
        assertFalse(film(fps = 29.97))
        assertFalse(film(fps = 30.0))
        assertFalse(film(fps = 50.0))
        assertFalse(film(fps = 60.0))
    }

    @Test
    fun `the 24 fps setting outranks ExoPlayer only`() {
        assertTrue(
            film(chosen = AppPreferences.PLAYER_ENGINE_EXO_ONLY)
        )
    }

    @Test
    fun `an external choice and a device without libmpv are never redirected`() {
        assertFalse(
            "a profile that asked for an external player is not asking for libmpv",
            film(chosen = AppPreferences.PLAYER_ENGINE_EXTERNAL)
        )
        assertFalse(film(mpvAvailable = false))
    }

    @Test
    fun `the refusal names the reason the launch acted on`() {
        assertNull(PlayerEngine.filmCadenceRefusal(23.976, 1_080, false))
        assertEquals(
            "a 4K file is refused by its height",
            "2160p",
            PlayerEngine.filmCadenceRefusal(23.976, 2_160, false)
        )
        assertEquals(
            "Dolby Vision is refused before anything else is weighed",
            "Dolby Vision",
            PlayerEngine.filmCadenceRefusal(23.976, 1_080, true)
        )
        assertEquals(
            "25 fps is not film cadence",
            "25.000 fps",
            PlayerEngine.filmCadenceRefusal(25.0, 1_080, false)
        )
        assertEquals(
            "no height declared",
            "no height",
            PlayerEngine.filmCadenceRefusal(23.976, null, false)
        )
        assertEquals(
            "no rate reading",
            "no rate",
            PlayerEngine.filmCadenceRefusal(null, 1_080, false)
        )
    }
}
