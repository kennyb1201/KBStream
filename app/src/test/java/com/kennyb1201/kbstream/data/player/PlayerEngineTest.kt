package com.kennyb1201.kbstream.data.player

import com.kennyb1201.kbstream.ui.settings.AppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which engine a launch opens, once the "Play Anime in MPV" setting is folded
 * into the stored choice. The decision is split out of the Context-reading
 * wrapper so the matrix can be pinned here.
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
}
