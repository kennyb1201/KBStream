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
        val synced = PrefsPayloadBuilder.SYNCED_PREF_KEYS
        // A Fire TV Stick and a projector need different decoders: these must
        // never ride the sync payload.
        listOf(
            "force_software_decoder",
            "enable_tunneling",
            "dv_compat_mode",
            "dv_convert_p5_to_81",
            "default_aspect_ratio",
            "default_buffer_mode"
        ).forEach { key ->
            assertFalse("$key must stay local", key in synced)
        }
    }
}
