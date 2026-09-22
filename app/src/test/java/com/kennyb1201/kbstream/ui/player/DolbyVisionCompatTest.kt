package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Dolby Vision profile routing that the player and the compat
 * extractor depend on.
 *
 * The failure this guards is specific and was real: Profile 5 is single-layer
 * ICtCp, so *relabeling* it as Profile 8.1 hands the display ICtCp samples
 * interpreted as Rec.2020 PQ — the green/purple picture. P5 must therefore
 * never take the 8.1 rewrite; it is stripped and color-corrected on the GPU
 * (or played natively as Dolby Vision when the device has a DV decoder).
 * These tests pin every branch of that decision so a future edit cannot
 * silently route P5 back through the 8.1 relabel.
 */
class DolbyVisionCompatTest {

    // ── P5 detection (drives the GPU ICtCp color path) ────────────────────

    @Test
    fun `profile 5 codecs are detected in every spelling`() {
        assertTrue(DolbyVisionCompat.isP5Profile("dvhe.05.06"))
        assertTrue(DolbyVisionCompat.isP5Profile("dvh1.05.06"))
        assertTrue(DolbyVisionCompat.isP5Profile("dvhe.5.06"))
        assertTrue(DolbyVisionCompat.isP5Profile("DVHE.05.06"))
        assertTrue(DolbyVisionCompat.isP5Profile("  dvh1.05.09  "))
    }

    @Test
    fun `other profiles and plain HEVC are not P5`() {
        assertFalse(DolbyVisionCompat.isP5Profile("dvhe.07.06"))
        assertFalse(DolbyVisionCompat.isP5Profile("dvhe.08.06"))
        assertFalse(DolbyVisionCompat.isP5Profile("dvhe.04.06"))
        assertFalse(DolbyVisionCompat.isP5Profile("hvc1.2.4.L153.B0"))
        assertFalse(DolbyVisionCompat.isP5Profile(null))
        assertFalse(DolbyVisionCompat.isP5Profile(""))
        assertFalse(DolbyVisionCompat.isP5Profile("   "))
    }

    // ── HDR10-base-layer profiles (pass through / strip, never 8.1) ───────

    @Test
    fun `profiles 4 and 8 have an HDR10 base layer`() {
        assertTrue(DolbyVisionCompat.isHdr10BaseLayerProfile("dvhe.04.06"))
        assertTrue(DolbyVisionCompat.isHdr10BaseLayerProfile("dvh1.08.06"))
        assertTrue(DolbyVisionCompat.isHdr10BaseLayerProfile("dvhe.8.06"))
    }

    @Test
    fun `profiles 5 and 7 do not have an HDR10 base layer`() {
        assertFalse(DolbyVisionCompat.isHdr10BaseLayerProfile("dvhe.05.06"))
        assertFalse(DolbyVisionCompat.isHdr10BaseLayerProfile("dvhe.07.06"))
        assertFalse(DolbyVisionCompat.isHdr10BaseLayerProfile("hvc1.2.4"))
        assertFalse(DolbyVisionCompat.isHdr10BaseLayerProfile(null))
    }

    // ── The 8.1 relabel: P7 only, and P5 must be excluded ─────────────────

    @Test
    fun `P7 relabels to Profile 8_1 only when its conversion is on`() {
        assertEquals("dvhe.08.06", DolbyVisionCompat.to81Codec("dvhe.07.06", true, false))
        assertEquals("dvhe.08.06", DolbyVisionCompat.to81Codec("dvhe.7.06", true, false))
        assertNull(DolbyVisionCompat.to81Codec("dvhe.07.06", false, false))
    }

    @Test
    fun `P5 relabels to Profile 8_1 only via its own flag, never via the P7 flag`() {
        assertEquals("dvh1.08.06", DolbyVisionCompat.to81Codec("dvh1.05.06", false, true))
        // The P7 toggle must not drag P5 into the 8.1 relabel (the green/
        // purple regression).
        assertNull(DolbyVisionCompat.to81Codec("dvhe.05.06", true, false))
        // And with both toggles off nothing happens.
        assertNull(DolbyVisionCompat.to81Codec("dvh1.05.06", false, false))
    }

    @Test
    fun `profiles 4 and 8 and plain HEVC never relabel`() {
        assertNull(DolbyVisionCompat.to81Codec("dvhe.04.06", true, true))
        assertNull(DolbyVisionCompat.to81Codec("dvhe.08.06", true, true))
        assertNull(DolbyVisionCompat.to81Codec("hvc1.2.4.L153.B0", true, true))
        assertNull(DolbyVisionCompat.to81Codec(null, true, true))
    }

    // ── The strip-to-HDR10 target codec ───────────────────────────────────

    @Test
    fun `strip targets P7 alone unless Strip All is on`() {
        assertEquals(DolbyVisionCompat.HDR10_CODEC, DolbyVisionCompat.hdr10Codec("dvhe.07.06"))
        assertNull(DolbyVisionCompat.hdr10Codec("dvhe.08.06"))
        assertNull(DolbyVisionCompat.hdr10Codec("dvhe.05.06"))
        assertNull(DolbyVisionCompat.hdr10Codec("hvc1.2.4"))
    }

    @Test
    fun `Strip All strips every DV profile`() {
        for (codec in listOf("dvhe.04.06", "dvhe.05.06", "dvhe.07.06", "dvhe.08.06", "dvh1.08.06")) {
            assertEquals(
                "expected $codec to strip under Strip All",
                DolbyVisionCompat.HDR10_CODEC,
                DolbyVisionCompat.hdr10Codec(codec, convertAllProfiles = true)
            )
        }
        assertNull(DolbyVisionCompat.hdr10Codec("hvc1.2.4.L153.B0", convertAllProfiles = true))
    }

    // ── The label the player reads back to detect the source profile ───────

    /**
     * The player detects P5 from the extractor's `label` (the original declared
     * codec, preserved through the rewrite), because the rewritten codecs
     * string is plain HEVC. If this label extraction ever stops recognising
     * P5, the GPU color path never engages and P5 plays green/purple again.
     */
    @Test
    fun `declared P5 survives the rewrite as a DV P5 label`() {
        assertEquals("DV P5", dvLabelFromCodec("dvhe.05.06"))
        assertEquals("DV P5", dvLabelFromCodec("dvh1.05.06"))
        assertEquals("DV P7", dvLabelFromCodec("dvhe.07.06"))
        assertEquals("DV P8", dvLabelFromCodec("dvh1.08.06"))
        assertEquals("DV P4", dvLabelFromCodec("dvhe.04.06"))
        assertNull(dvLabelFromCodec("hvc1.2.4.L153.B0"))
        assertNull(dvLabelFromCodec(null))
    }

    @Test
    fun `badges describe a stripped P5 as HDR10, not as 8_1`() {
        assertEquals("DV P5 → HDR10", normalizeCodec("hvc1.2.4.L153.B0", "dvhe.05.06"))
        assertEquals("DV P7 → 8.1", normalizeCodec("hvc1.2.4.L153.B0", "dvhe.07.06", convertedTo81 = true))
        assertEquals("H.265", normalizeCodec("hvc1.2.4.L153.B0"))
    }

    // Learned device capability: a box whose DV decoder hard-fails.

    @Test
    fun `a recorded DV decoder failure suppresses passthrough inside its TTL`() {
        val failedAt = 1_000_000L
        assertTrue(dvPassthroughSuppressed(failedAt, failedAt))
        assertTrue(
            dvPassthroughSuppressed(
                failedAt,
                failedAt + DV_PASSTHROUGH_FAILURE_TTL_MS - 1L
            )
        )
    }

    @Test
    fun `the suppression expires so Dolby Vision can come back on its own`() {
        val failedAt = 1_000_000L
        assertFalse(dvPassthroughSuppressed(failedAt, failedAt + DV_PASSTHROUGH_FAILURE_TTL_MS))
        assertFalse(
            dvPassthroughSuppressed(failedAt, failedAt + DV_PASSTHROUGH_FAILURE_TTL_MS * 10L)
        )
    }

    @Test
    fun `nothing recorded means passthrough is never suppressed`() {
        assertFalse(dvPassthroughSuppressed(0L, System.currentTimeMillis()))
    }

    // Vendor DV decoder refusal vs. genuine resource exhaustion.
    //
    // The TCL/Realtek DV decoder reports its refusal with the platform's own
    // out-of-resources code (0x80001000), so the player must tell the two
    // apart by the session. Getting this wrong sent a DV-capable TV to the
    // "out of video decoder resources" banner instead of the HDR10 strip.

    @Test
    fun `a DV passthrough session whose decoder failed is the vendor refusal`() {
        assertTrue(
            dvPassthroughDecoderRefused(
                isDecoderFailure = true,
                dvPassthroughActive = true,
                declaredDvCodec = "dvhe.08.06",
                alreadyStripped = false
            )
        )
    }

    @Test
    fun `a plain HEVC failure with passthrough on is not the DV refusal`() {
        // Passthrough is on, but the failing track is not Dolby Vision: this
        // is genuine exhaustion and must keep the next-source recovery.
        assertFalse(
            dvPassthroughDecoderRefused(
                isDecoderFailure = true,
                dvPassthroughActive = true,
                declaredDvCodec = "hvc1.2.4.L153.B0",
                alreadyStripped = false
            )
        )
    }

    @Test
    fun `a DV failure without passthrough active is not the refusal`() {
        // Passthrough was already suppressed/stripped: re-stripping proves
        // nothing, so this must fall through to the resource recovery.
        assertFalse(
            dvPassthroughDecoderRefused(
                isDecoderFailure = true,
                dvPassthroughActive = false,
                declaredDvCodec = "dvhe.07.06",
                alreadyStripped = false
            )
        )
    }

    @Test
    fun `an already-stripped session never re-takes the DV path`() {
        assertFalse(
            dvPassthroughDecoderRefused(
                isDecoderFailure = true,
                dvPassthroughActive = true,
                declaredDvCodec = "dvhe.07.06",
                alreadyStripped = true
            )
        )
    }

    @Test
    fun `a non-decoder failure is never the DV refusal`() {
        assertFalse(
            dvPassthroughDecoderRefused(
                isDecoderFailure = false,
                dvPassthroughActive = true,
                declaredDvCodec = "dvhe.08.06",
                alreadyStripped = false
            )
        )
    }
}
