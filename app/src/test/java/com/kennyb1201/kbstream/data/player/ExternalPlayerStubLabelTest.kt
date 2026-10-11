package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stub filter on the external-player picker.
 *
 * Field 2026-10-11 (Settings -> Player engine): Amazon's dummy gallery activity
 * answers the video VIEW probe, and with no human label `labelFor` falls back to
 * the component's class name - so the picker listed
 * "amazon.dummy.gallery.DummyGalleryActivity", an entry no viewer can pick a
 * player out of. The probe cannot tell a stub from a player; the label can, and
 * [ExternalPlayer.isStubLabel] is that rule.
 */
class ExternalPlayerStubLabelTest {

    @Test
    fun `a label that is the component's own class name is a stub`() {
        assertTrue(
            ExternalPlayer.isStubLabel(
                "amazon.dummy.gallery",
                "amazon.dummy.gallery.DummyGalleryActivity"
            )
        )
    }

    @Test
    fun `a real player's human label is kept`() {
        assertFalse(ExternalPlayer.isStubLabel("com.vimu.player", "Vimu"))
        assertFalse(ExternalPlayer.isStubLabel("org.xbmc.kodi", "Kodi"))
    }

    @Test
    fun `a bare package name is the fallback label, not a stub class name`() {
        // labelFor falls back to the package name when an app has no label at
        // all, and "com.example".startsWith("com.example.") is false - so that
        // entry stays rather than being filtered by the same rule.
        assertFalse(ExternalPlayer.isStubLabel("com.example", "com.example"))
    }
}
