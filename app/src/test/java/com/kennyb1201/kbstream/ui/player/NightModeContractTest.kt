package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Night Mode (spec 2026-11-13): one global toggle, applied by both engines.
 *
 * Night Mode is dynamic-range compression - not a dialogue boost (that lifts a
 * channel) and not a volume boost (that is a flat gain with a limiter). It rides
 * the gain against the signal level, so quiet passages come up and loud peaks go
 * down at the same TV volume.
 *
 * Two things a JVM test cannot press are pinned from the source, the same way
 * the other player contracts are: the ORDER of the stages (compress → gain →
 * limit on Exo, dialogue → Night Mode → gain → limiter in MPV's one `af` string)
 * and the decode rule (a bitstream never reaches either chain, so Night Mode has
 * to force PCM in every output mode and hand passthrough back when it is off).
 * The parameters themselves are behavioural: [AudioDownmix.NightModeCompressor]
 * is tested directly, and the shipped filter's presence was verified from the
 * AAR's own libavfilter before this was built on it.
 */
class NightModeContractTest {

    // ── MPV: the chain, its order, and its absence when off ─────────────────

    @Test
    fun `the af chain runs dialogue, then Night Mode, then the boost and its limiter`() {
        val spec = MpvAudioChains.audioFilterSpec(
            dialogueLevel = 2,
            channels = 6,
            volumeBoostDb = 6,
            nightMode = true
        )
        assertTrue("a spec must be produced", spec != null)

        val dialogue = spec!!.indexOf("pan=5.1")
        val night = spec.indexOf(MpvAudioChains.NIGHT_MODE_FILTER)
        val volume = spec.indexOf("volume=6dB")
        val limiter = spec.indexOf("alimiter")

        assertTrue("dialogue lift first", dialogue >= 0 && dialogue < night)
        assertTrue("Night Mode after the lift: $spec", night in 0 until volume)
        assertTrue("the boost's gain after it: $spec", volume in 0 until limiter)
        assertTrue("and the limiter LAST, so it sees the compressed+boosted samples", limiter > volume)
    }

    @Test
    fun `Night Mode alone is the whole chain, and everything off clears it`() {
        assertEquals(
            MpvAudioChains.NIGHT_MODE_FILTER,
            MpvAudioChains.audioFilterSpec(
                dialogueLevel = 0,
                channels = 2,
                volumeBoostDb = 0,
                nightMode = true
            )
        )
        assertNull(
            "with every knob off the af chain must be cleared, exactly as before",
            MpvAudioChains.audioFilterSpec(
                dialogueLevel = 0,
                channels = 2,
                volumeBoostDb = 0,
                nightMode = false
            )
        )
    }

    @Test
    fun `the filter string is the verified one for the shipped FFmpeg`() {
        // dynaudnorm (Dynamic Audio Normalizer) is the real-time gain rider in
        // FFmpeg n8.1.1, the version inside the shipped mpv AAR - verified from
        // the AAR's libavfilter before it was relied on, and re-checked by the
        // test below. These are starting values; the ear test is the arbiter.
        val spec = MpvAudioChains.NIGHT_MODE_FILTER
        assertEquals("dynaudnorm=framelen=150:maxgain=2:peak=0.95", spec)
        // maxgain is a LINEAR factor in FFmpeg (default 10), not dB: 2 is ~+6 dB,
        // the spec's makeup figure. A "maxgain=6" would be six TIMES.
        assertTrue("short frame: a scene, not an act", spec.contains("framelen=150"))
        assertTrue("modest max amplification", spec.contains("maxgain=2"))
    }

    // ── Decode vs passthrough (F4) ──────────────────────────────────────────

    @Test
    fun `Night Mode needs PCM in every output mode and hands passthrough back when off`() {
        val saved = Snapshot()
        try {
            PlayerAudioTuning.apply(
                PlayerAudioTuning.DOWNMIX_AUTO,
                dialogueBoost = 0,
                volumeBoostDb = 0,
                nightMode = false
            )
            assertTrue("all off is neutral", PlayerAudioTuning.isNeutral)
            assertFalse(
                "explicit passthrough is honoured while nothing is on",
                PlayerAudioTuning.requiresDecode(PlayerAudioTuning.AUDIO_OUTPUT_PASSTHROUGH)
            )
            assertEquals(
                MpvAudioChains.SPDIF_CODECS,
                MpvAudioChains.audioSpdifSpec(
                    PlayerAudioTuning.AUDIO_OUTPUT_PASSTHROUGH,
                    tuningNeutral = true,
                    nightMode = false
                )
            )

            PlayerAudioTuning.apply(
                PlayerAudioTuning.DOWNMIX_AUTO,
                dialogueBoost = 0,
                volumeBoostDb = 0,
                nightMode = true
            )
            assertFalse("Night Mode is never neutral", PlayerAudioTuning.isNeutral)
            assertTrue(
                "Auto decodes while it is on",
                PlayerAudioTuning.requiresDecode(PlayerAudioTuning.AUDIO_OUTPUT_AUTO)
            )
            assertTrue(
                "and so does an explicit Passthrough: the compressor cannot ride a bitstream",
                PlayerAudioTuning.requiresDecode(PlayerAudioTuning.AUDIO_OUTPUT_PASSTHROUGH)
            )
            assertEquals(
                "so mpv is told to keep everything decoded",
                "",
                MpvAudioChains.audioSpdifSpec(
                    PlayerAudioTuning.AUDIO_OUTPUT_PASSTHROUGH,
                    tuningNeutral = true,
                    nightMode = true
                )
            )
        } finally {
            saved.restore()
        }
    }

    // ── Exo: the chain order in the processor ───────────────────────────────

    @Test
    fun `the Exo compressor sits after fold-balance and before the gain and the limiter`() {
        val processor = source(PROCESSOR)
        val compress = processor.indexOf("nightCompressor.nextGain(")
        val gain = processor.indexOf("val scaled = mixed[out] * gain")
        val limit = processor.indexOf("limiter.nextGain(peak)")

        assertTrue("the compressor is in the per-frame loop", compress > 0)
        assertTrue("compress before the volume gain", compress < gain)
        assertTrue("and the limiter last", gain < limit)

        assertTrue(
            "off is a SKIP, not a multiply by one: the stage is guarded by the " +
                "per-buffer flag",
            processor.contains("val nightMode = PlayerAudioTuning.nightMode") &&
                processor.contains("if (nightMode) {")
        )
        assertTrue(
            "hot path: the state is preallocated on the processor, never per buffer",
            processor.contains(
                "private var nightCompressor = AudioDownmix.NightModeCompressor("
            )
        )
    }

    // ── The pref: default off, synced, one setter ───────────────────────────

    @Test
    fun `the toggle defaults off, syncs, and the tuning keys beside it do not`() {
        val prefs = source(PREFS)
        assertTrue(
            "default OFF: an install that never touches it is today's build",
            prefs.contains("getBoolean(KEY_AUDIO_NIGHT_MODE, false)")
        )
        assertTrue(
            "the setter publishes the display blob, like every synced pref",
            prefs.contains("fun setAudioNightMode(") &&
                prefs.substringAfter("fun setAudioNightMode(")
                    .substringBefore("\n    }\n")
                    .contains("syncDisplayPrefsBlob(context)")
        )

        val payload = source(SYNC_PAYLOAD)
        val synced = payload
            .substringAfter("internal val SYNCED_PREF_KEYS = setOf(")
            .substringBefore("internal val EXCLUDED_PREF_KEYS")
        assertTrue("\"audio_night_mode\" must ride the sync blob", synced.contains("\"audio_night_mode\""))
        listOf(
            "audio_downmix_target",
            "audio_dialogue_boost",
            "audio_volume_boost_db"
        ).forEach { key ->
            assertFalse(
                "$key answers this room's speakers, so it stays device-local",
                synced.contains("\"$key\"")
            )
        }
    }

    // ── Both surfaces, both engines ─────────────────────────────────────────

    @Test
    fun `both settings surfaces carry the row and name the passthrough cost`() {
        val tv = source(SETTINGS_TV)
        val phone = source(SETTINGS_MOBILE)
        listOf("tv" to tv, "phone" to phone).forEach { (who, src) ->
            assertTrue("$who: the row", src.contains("label = \"Night Mode\""))
            assertTrue(
                "$who: it writes the shared pref",
                src.contains("AppPreferences.setAudioNightMode(context, it)")
            )
            assertTrue(
                "$who: the description states the decode consequence (F4)",
                src.contains("passthrough is off while it's on")
            )
        }
        assertTrue(
            "tv: the read is keyed on the sync revision, so the phone's flip lands",
            tv.contains("var audioNightMode by remember(syncedRevision) {")
        )
        assertTrue(
            "phone: same",
            phone.contains("var audioNightMode by remember(syncedRevision) {")
        )
    }

    @Test
    fun `the player panel toggles it on both engines`() {
        assertTrue(
            "Exo panel -> the activity that republishes the tuning",
            source(EXO_PANEL).contains("activity.setNightMode(enabled)")
        )
        assertTrue(
            "MPV panel -> the activity that re-issues af",
            source(MPV_PANEL).contains("activity.chooseNightMode(enabled)")
        )

        val native = source(NATIVE)
        assertTrue(native.contains("internal fun setNightMode(enabled: Boolean)"))
        assertTrue(
            "the Exo toggle writes the one pref and republishes the per-buffer tuning",
            native.contains("AppPreferences.setAudioNightMode(this, enabled)") &&
                native.contains("PlayerTrackBridge.publishAudioTuning(this)")
        )
        assertTrue(
            "and a bitstream session is rebuilt onto the PCM path, not left silent",
            native.contains("fun restateAudioChainForNightMode(") &&
                native.contains("MimeTypes.AUDIO_AC3")
        )

        val mpv = source(MPV_ACTIVITY)
        assertTrue(mpv.contains("internal fun chooseNightMode(enabled: Boolean)"))
        assertTrue(mpv.contains("AppPreferences.setAudioNightMode(this, enabled)"))
        assertTrue(
            "MPV re-issues the chain live, and its open-time neutral answer counts it",
            mpv.contains("surface?.setNightMode(enabled)") &&
                mpv.contains("view.setNightMode(AppPreferences.getAudioNightMode(this))") &&
                mpv.contains("!AppPreferences.getAudioNightMode(this)")
        )
    }

    @Test
    fun `MPV hands the toggle to both the filter chain and the passthrough decision`() {
        val view = source(MPV_VIEW)
        assertTrue(
            "the filter chain sees it",
            view.contains("volumeBoostDb,\n            nightMode\n        )")
        )
        assertTrue(
            "and so does audio-spdif",
            view.contains(
                "MpvAudioChains.audioSpdifSpec(audioOutputMode, audioTuningNeutral, nightMode)"
            )
        )
        assertTrue(view.contains("fun setNightMode(enabled: Boolean)"))
    }

    // ── The compressor itself ───────────────────────────────────────────────

    @Test
    fun `a loud frame is pulled down and a quiet one is lifted, within the makeup cap`() {
        val loud = AudioDownmix.NightModeCompressor()
        repeat(3_000) { loud.nextGain(meanSquare = 0.5f, peak = 0.9f) }
        assertTrue(
            "a loud program is compressed: gain=${loud.currentGain}",
            loud.currentGain < 0.8f
        )

        val quiet = AudioDownmix.NightModeCompressor()
        repeat(30_000) { quiet.nextGain(meanSquare = 0.0001f, peak = 0.02f) }
        assertTrue(
            "a quiet program is lifted: gain=${quiet.currentGain}",
            quiet.currentGain > 1.2f
        )
        assertTrue(
            "but never past the +6 dB makeup: gain=${quiet.currentGain}",
            quiet.currentGain <= 2.0f + 1e-3f
        )
    }

    @Test
    fun `the peak guard is immediate, whatever the envelope says`() {
        val compressor = AudioDownmix.NightModeCompressor()
        repeat(30_000) { compressor.nextGain(meanSquare = 1e-6f, peak = 0.001f) }
        assertTrue("lifted before the transient", compressor.currentGain > 1.2f)

        val guarded = compressor.nextGain(meanSquare = 1e-6f, peak = 1f)
        assertTrue(
            "a full-scale transient cannot be pushed past the ceiling: $guarded",
            guarded <= AudioDownmix.NightModeCompressor.PEAK_CEILING + 1e-6f
        )
    }

    @Test
    fun `reset starts clean for a new stream`() {
        val compressor = AudioDownmix.NightModeCompressor()
        repeat(3_000) { compressor.nextGain(meanSquare = 0.5f, peak = 0.9f) }
        assertTrue(compressor.currentGain < 1f)
        compressor.reset()
        assertEquals(1f, compressor.currentGain, 0f)
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    /** The live tuning is process-wide; put back whatever the suite found. */
    private inner class Snapshot {
        private val downmix = PlayerAudioTuning.downmixTarget
        private val dialogue = PlayerAudioTuning.dialogueBoost
        private val volume = PlayerAudioTuning.volumeBoostDb
        private val night = PlayerAudioTuning.nightMode

        fun restore() {
            PlayerAudioTuning.apply(downmix, dialogue, volume, night)
        }
    }

    private fun source(relative: String): String {
        val file = File(mainRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun mainRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream"
        const val PROCESSOR = "$PACKAGE/ui/player/AudioDownmixProcessor.kt"
        const val MPV_VIEW = "$PACKAGE/ui/player/MpvPlayerView.kt"
        const val MPV_ACTIVITY = "$PACKAGE/ui/player/MpvPlayerActivity.kt"
        const val NATIVE = "$PACKAGE/ui/player/NativePlayerActivity.kt"
        const val EXO_PANEL = "$PACKAGE/ui/player/PlayerPanelSection.kt"
        const val MPV_PANEL = "$PACKAGE/ui/player/MpvSettingsSection.kt"
        const val SETTINGS_TV = "$PACKAGE/ui/settings/SettingsScreen.kt"
        const val SETTINGS_MOBILE = "$PACKAGE/ui/mobile/SettingsScreenMobile.kt"
        const val SYNC_PAYLOAD = "$PACKAGE/data/sync/SyncPrefsPayload.kt"
        const val PREFS = "$PACKAGE/data/settings/AppPreferences.kt"
    }
}
