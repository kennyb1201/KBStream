package com.kennyb1201.kbstream.ui.player

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * What the two engine handoffs do to the intent they launch.
 *
 * Both players replay their OWN launch intent at the other engine, so the base
 * still names the activity it came from. The handoff used to correct that
 * component in a `startActivityForResult` override, keyed off an extra on the
 * intent; the rewrite now happens in an explicit builder at the one call site
 * ([NativePlayerActivity.mpvHandoffIntent] / [MpvPlayerActivity.exoSwitchIntent]).
 * That is the behaviour pinned here, because getting it wrong is silent from
 * the couch: the SWITCH press opens a second session of the SAME engine and
 * reads as a dead button.
 *
 * Robolectric instantiates the activities without running a real session -
 * neither ExoPlayer nor libmpv is built until `onCreate` does it, and these
 * builders are pure intent edits. The no-op [android.app.Application] mirrors
 * `PlayerActivityContractTest`: the real one starts background work nothing
 * here needs.
 */
@RunWith(AndroidJUnit4::class)
class PlayerHandoffIntentTest {

    private fun nativePlayer(): NativePlayerActivity =
        Robolectric.buildActivity(NativePlayerActivity::class.java).get()

    private fun mpvPlayer(): MpvPlayerActivity =
        Robolectric.buildActivity(MpvPlayerActivity::class.java).get()

    @Test
    fun `the MPV handoff points at the backup engine`() {
        val player = nativePlayer()
        val base = Intent(player, NativePlayerActivity::class.java)
            .putExtra("stream_url", "https://example.test/movie.mkv")
            .putExtra(MpvPlayerActivity.EXTRA_MPV_FALLBACK, true)

        val handoff = player.mpvHandoffIntent(base)

        assertEquals(
            "the MPV handoff still names NativePlayerActivity: it opens a " +
                "second ExoPlayer session and the SWITCH press looks dead",
            MpvPlayerActivity::class.java.name,
            handoff.component?.className
        )
        // The extra stays: MPV reads it to know it is the backup engine.
        assertTrue(
            "the handoff extra was dropped, so MPV no longer knows it is the " +
                "backup engine",
            handoff.getBooleanExtra(MpvPlayerActivity.EXTRA_MPV_FALLBACK, false)
        )
        // The session extras ride along untouched.
        assertEquals(
            "https://example.test/movie.mkv",
            handoff.getStringExtra("stream_url")
        )
    }

    @Test
    fun `the MPV handoff leaves the base intent alone`() {
        val player = nativePlayer()
        val base = Intent(player, NativePlayerActivity::class.java)

        player.mpvHandoffIntent(base)

        assertEquals(
            "the handoff mutated the caller's intent instead of a copy",
            NativePlayerActivity::class.java.name,
            base.component?.className
        )
    }

    @Test
    fun `the switch-back points at the main engine`() {
        val player = mpvPlayer()
        val base = Intent(player, MpvPlayerActivity::class.java)
            .putExtra("stream_url", "https://example.test/movie.mkv")

        val handoff = player.exoSwitchIntent(base)

        assertEquals(
            "the switch-back still names MpvPlayerActivity: it opens a second " +
                "MPV session and the SWITCH press looks dead",
            NativePlayerActivity::class.java.name,
            handoff.component?.className
        )
        assertEquals(
            "https://example.test/movie.mkv",
            handoff.getStringExtra("stream_url")
        )
    }

    @Test
    fun `the switch-back leaves the base intent alone`() {
        val player = mpvPlayer()
        val base = Intent(player, MpvPlayerActivity::class.java)

        player.exoSwitchIntent(base)

        assertEquals(
            "the switch-back mutated the caller's intent instead of a copy",
            MpvPlayerActivity::class.java.name,
            base.component?.className
        )
    }
}
