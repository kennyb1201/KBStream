package com.kennyb1201.kbstream.data.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-title store both engines share (spec 2026-11-14, parity row 7).
 *
 * A track pick is a property of the SHOW, not of the engine that made it: Exo
 * writes it through `PlayerTrackBridge`, MPV through `MpvPlayerActivity`, and
 * both go through [PlayerTitlePrefs] under a key from
 * [PlayerTitlePrefs.titleKeyFor]. Until this test, "a pick made on Exo is
 * honoured on MPV" was an assumption about two other files agreeing.
 *
 * This drives the store itself rather than either shell: the shells' call sites
 * are pinned in `AudioMemoryParityContractTest`, and instantiating a player to
 * reach the same two functions would test the player, not the memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioMemoryStoreParityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a pick written to the shared store comes back unchanged`() {
        val key = PlayerTitlePrefs.titleKeyFor(parentId = "show-42", mediaId = "episode-7")
        assertEquals("a show keys on the show, so every episode inherits it", "show-42", key)
        val signature = "en|eac3|6|1|2"
        PlayerTitlePrefs.remember(
            context,
            key,
            PlayerTitlePrefs.Prefs(audioLang = "en", audioTrackSignature = signature)
        )
        val read = PlayerTitlePrefs.get(context, key)
        assertEquals(
            "the exact pick is what the other engine re-applies - not a language",
            signature,
            read?.audioTrackSignature
        )
        assertEquals("en", read?.audioLang)
    }

    @Test
    fun `a movie keys on its own id and round trips the same way`() {
        val key = PlayerTitlePrefs.titleKeyFor(parentId = null, mediaId = "movie-9")
        assertEquals("movie-9", key)
        PlayerTitlePrefs.remember(
            context,
            key,
            PlayerTitlePrefs.Prefs(audioLang = "fr", audioTrackSignature = "fr|ac3|2|0|2")
        )
        assertEquals("fr|ac3|2|0|2", PlayerTitlePrefs.get(context, key)?.audioTrackSignature)
    }

    @Test
    fun `one title's pick never leaks into another`() {
        val first = PlayerTitlePrefs.titleKeyFor("show-1", "ep-1")
        val second = PlayerTitlePrefs.titleKeyFor("show-2", "ep-1")
        PlayerTitlePrefs.remember(context, first, PlayerTitlePrefs.Prefs(audioTrackSignature = "en|eac3|6|0|1"))
        PlayerTitlePrefs.remember(context, second, PlayerTitlePrefs.Prefs(audioTrackSignature = "fr|ac3|2|0|1"))
        assertEquals("en|eac3|6|0|1", PlayerTitlePrefs.get(context, first)?.audioTrackSignature)
        assertEquals("fr|ac3|2|0|1", PlayerTitlePrefs.get(context, second)?.audioTrackSignature)
    }

    @Test
    fun `the key derivation is the parent id, else the media id, and nothing when neither exists`() {
        assertEquals("show-1", PlayerTitlePrefs.titleKeyFor("show-1", "ep-2"))
        assertEquals("ep-2", PlayerTitlePrefs.titleKeyFor(null, "ep-2"))
        assertEquals("ep-2", PlayerTitlePrefs.titleKeyFor("   ", "ep-2"))
        assertNull("a blank identity remembers nothing rather than a stray row", PlayerTitlePrefs.titleKeyFor(null, null))
        assertNull(PlayerTitlePrefs.titleKeyFor("", ""))
        assertNull(PlayerTitlePrefs.titleKeyFor("  ", null))
    }

    @Test
    fun `an empty choice drops the row instead of resurrecting an old pick`() {
        val key = PlayerTitlePrefs.titleKeyFor("show-3", "ep-1")
        PlayerTitlePrefs.remember(context, key, PlayerTitlePrefs.Prefs(audioTrackSignature = "en|eac3|6|0|1"))
        PlayerTitlePrefs.remember(context, key, PlayerTitlePrefs.Prefs())
        assertNull(
            "back to Auto must read as nothing remembered",
            PlayerTitlePrefs.get(context, key)?.audioTrackSignature
        )
    }
}
