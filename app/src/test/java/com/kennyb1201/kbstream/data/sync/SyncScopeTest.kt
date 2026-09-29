package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the display-prefs blob covers. A key missing from
 * [PrefsPayloadBuilder.SYNCED_PREF_KEYS] never reaches the builder, so the
 * setter's push is a silent no-op — which is exactly how the subtitle and
 * language settings ended up never syncing while the setters looked wired up.
 */
class SyncScopeTest {

    @Test
    fun `subtitle appearance and language prefs sync`() {
        val synced = PrefsPayloadBuilder.SYNCED_PREF_KEYS
        // Subtitle rendering: the user's style choice follows them.
        assertTrue("default_subtitle_size must sync", "default_subtitle_size" in synced)
        assertTrue("default_subtitle_bg must sync", "default_subtitle_bg" in synced)
        // Preferred languages: same expectation on every device.
        assertTrue("preferred_audio_language must sync", "preferred_audio_language" in synced)
        assertTrue("preferred_subtitle_language must sync", "preferred_subtitle_language" in synced)
    }

    @Test
    fun `decoder and playback prefs stay per-device`() {
        // A Fire TV Stick and a projector need different decoders: not one of
        // these may ride the sync payload. The list lives beside the allow-list
        // it guards, so a decoder pref added to the payload is caught here
        // rather than on a device that cannot decode what the other one chose.
        val synced = PrefsPayloadBuilder.SYNCED_PREF_KEYS
        val excluded = PrefsPayloadBuilder.EXCLUDED_PREF_KEYS
        assertTrue("the exclusion list must not be empty", excluded.isNotEmpty())
        excluded.forEach { key ->
            assertFalse("$key must stay local", key in synced)
        }
        // The sample the old test pinned by hand, kept as a canary.
        assertTrue(
            "force_software_decoder must be excluded",
            "force_software_decoder" in excluded
        )
    }

    @Test
    fun `frame-rate matching stays per-device`() {
        // A TCL panel and a Fire TV Stick do not report the same modes, so a
        // synced choice would have one device asking for a mode the other does
        // not have. The pref is device-local, next to the decoder choices it
        // sits beside rather than with the display preferences.
        assertTrue(
            "match_frame_rate must be excluded",
            "match_frame_rate" in PrefsPayloadBuilder.EXCLUDED_PREF_KEYS
        )
        assertFalse(
            "match_frame_rate must not sync",
            "match_frame_rate" in PrefsPayloadBuilder.SYNCED_PREF_KEYS
        )
    }
}
