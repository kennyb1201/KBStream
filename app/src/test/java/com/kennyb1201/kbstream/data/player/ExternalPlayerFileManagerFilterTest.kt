package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file-manager filter on the external-player picker.
 *
 * Field 2026-10-13 (Settings -> Playback engine -> External player): a file
 * manager was offered as the app to hand a title to. They all register
 * `ACTION_VIEW` for every file type there is, so the video MIME probe matches
 * them by construction - the probe cannot tell a manager from a player, and the
 * viewer ends up handing a film to a file browser.
 *
 * [ExternalPlayer.isFileManager] is the rule, pinned here rather than on a box
 * with four file managers installed: the blocklist by package, the label rule
 * for the managers that name themselves, and the known-player escape hatch that
 * keeps a real player off the floor when its label trips the rule.
 */
class ExternalPlayerFileManagerFilterTest {

    @Test
    fun `a blocklisted manager is filtered whatever it is called`() {
        listOf(
            "com.marc.files",                 // File Manager +
            "com.alphainventor.filemanager",  // File Manager
            "com.cx.fileexplorer",            // CX File Explorer
            "com.estrongs.android.pop",       // ES File Explorer
            "com.google.android.apps.nbu.files"
        ).forEach { pkg ->
            assertTrue(
                "$pkg answers the video probe because it answers every file type",
                ExternalPlayer.isFileManager(pkg, "Files")
            )
        }
        // The blocklist is not escapable by label: a package known to be a
        // manager is one even if it calls itself something else.
        assertTrue(
            "the blocklist is the first rule, not a hint",
            ExternalPlayer.isFileManager("com.cx.fileexplorer", "VLC")
        )
    }

    @Test
    fun `a manager whose own name says what it is needs no blocklist entry`() {
        assertTrue(
            "an unreported manager is still filtered by its label",
            ExternalPlayer.isFileManager("com.some.new.manager", "File Manager")
        )
        assertTrue(ExternalPlayer.isFileManager("com.other.app", "File Explorer Pro"))
        assertTrue(ExternalPlayer.isFileManager("com.google.android.apps.nbu.files", "Files"))
        assertTrue(
            "case must not matter - a launcher is free to capitalize it",
            ExternalPlayer.isFileManager("com.other.app", "FILES")
        )
    }

    @Test
    fun `a real player is kept`() {
        assertFalse(ExternalPlayer.isFileManager("org.videolan.vlc", "VLC"))
        assertFalse(ExternalPlayer.isFileManager("org.xbmc.kodi", "Kodi"))
        assertFalse(ExternalPlayer.isFileManager("com.brouken.player", "Just Player"))
        assertFalse(ExternalPlayer.isFileManager("com.example.player", "Movies"))
    }

    @Test
    fun `a known player keeps its place even when its label trips the keyword rule`() {
        // The escape hatch, and the reason it exists: dropping a player the
        // viewer can actually play with is worse than listing an entry too
        // many, and the probe found this one through a video MIME filter - which
        // is the evidence that it is a player, whatever its store name says.
        assertFalse(
            "MX Player's own badge names the files it plays",
            ExternalPlayer.isFileManager("com.mxtech.videoplayer.ad", "MX Player - Play Files")
        )
        assertFalse(
            ExternalPlayer.isFileManager("com.vimu.player", "Vimu - Files & Media")
        )
    }

    @Test
    fun `the scheme-only player is never filtered`() {
        // VidHub is found by the scheme probe, not the MIME probe, so the
        // filter is not applied to it at all - this pins that its label would
        // not trip the rule even if it were.
        assertFalse(ExternalPlayer.isFileManager("com.oyac.vidhub", "VidHub"))
    }
}
