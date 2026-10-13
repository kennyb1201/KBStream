package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge's Kotlin half: what it answers with no library in the build.
 *
 * libdovi itself cannot be exercised here — there is no Android device or
 * emulator in CI, and no `.so` in a JVM unit test — so what is pinned is the
 * part that has to be right regardless: the profile detection the settings path
 * uses (pure string work, no JNI), the constants the C side is written against,
 * and the fallback contract every caller depends on. `available` is false in
 * this process by construction, which is exactly the state a local build and a
 * failed native CI step are in, and the point of these tests is that answering
 * "no libdovi" neither throws nor invents RPU bytes.
 */
class DoviBridgeTest {

    @Test
    fun `the profile comes out of the codec string without touching JNI`() {
        assertEquals(7, DoviBridge.detectDvProfile("dvhe.07.06"))
        assertEquals(8, DoviBridge.detectDvProfile("dvhe.08.06"))
        assertEquals(5, DoviBridge.detectDvProfile("dvhe.05.06"))
        assertEquals(5, DoviBridge.detectDvProfile("dvh1.05.06"))
        assertEquals(7, DoviBridge.detectDvProfile("  dvhe.07.06  "))
    }

    @Test
    fun `a codec string that declares no Dolby Vision profile is not one`() {
        assertEquals(DoviBridge.NOT_DOLBY_VISION, DoviBridge.detectDvProfile(null))
        assertEquals(DoviBridge.NOT_DOLBY_VISION, DoviBridge.detectDvProfile("hvc1.2.4.L150.B0"))
        assertEquals(DoviBridge.NOT_DOLBY_VISION, DoviBridge.detectDvProfile("hev1.1.6.L93.B0"))
        // The pattern is the four Dolby Vision fourccs plus a profile number and
        // a dot: a fourcc on its own declares nothing this app can act on.
        assertEquals(DoviBridge.NOT_DOLBY_VISION, DoviBridge.detectDvProfile("dvhe.07"))
        assertEquals(DoviBridge.NOT_DOLBY_VISION, DoviBridge.detectDvProfile("dvhe.xx.06"))
        assertEquals(DoviBridge.NOT_DOLBY_VISION, DoviBridge.detectDvProfile("avc1.640028"))
    }

    @Test
    fun `the constants the native side is written against are pinned`() {
        // The C bridge mirrors these numbers; a change here without the same
        // change in dovi_bridge.c is a silent mismatch, not a compile error.
        assertEquals(0, DoviBridge.NAL_FORMAT_ANNEX_B)
        assertEquals(1, DoviBridge.NAL_FORMAT_LENGTH_DELIMITED)
        assertEquals(2, DoviBridge.NAL_FORMAT_SINGLE_NAL)
        assertEquals(-1, DoviBridge.NOT_DOLBY_VISION)
        assertEquals(-2, DoviBridge.PARSE_ERROR)
        assertEquals(-3, DoviBridge.BUFFER_TOO_SMALL)
        assertEquals("dovi_bridge", DoviBridge.LIBRARY)
        // And the conversion mode, which is what the 8.1 path asks for: mode 2 is
        // the universal converter. Mode 4 is 8.4 as the pinned crate reads it,
        // NOT the "8.1 preserving the mapping" its own C API comments claim.
        assertEquals(2, DoviBridge.MODE_TO_81)
        assertEquals(4, DoviBridge.MODE_TO_84)
    }

    @Test
    fun `with no library in the build the bridge says so rather than throwing`() {
        // loadLibrary fails in a JVM test exactly as it does in an APK built
        // without scripts/build_dovi.sh, and that has to be a boolean.
        assertFalse("no .so in a unit-test process", DoviBridge.available)
    }

    @Test
    fun `the libdovi profile probe declines when there is no library`() {
        // Annex-B sample whose second NAL is an RPU (header 0x7C 0x01 -> type 62).
        val sample = byteArrayOf(0, 0, 1, 0x40, 0x01, 0x11, 0, 0, 1, 0x7C, 0x01, 0x22, 0x33)
        assertEquals(
            "with no .so the probe must answer NOT_DOLBY_VISION, not throw",
            DoviBridge.NOT_DOLBY_VISION,
            DolbyVisionCompat.sampleDvProfileFromLibdovi(sample, sample.size)
        )
        // A sample with no RPU NAL at all answers the same way (type 33 = SPS).
        val plain = byteArrayOf(0, 0, 1, 0x42, 0x01, 0x11)
        assertEquals(
            DoviBridge.NOT_DOLBY_VISION,
            DolbyVisionCompat.sampleDvProfileFromLibdovi(plain, plain.size)
        )
        // Degenerate input is refused before any scan.
        assertEquals(DoviBridge.NOT_DOLBY_VISION, DolbyVisionCompat.sampleDvProfileFromLibdovi(byteArrayOf(), 0))
    }

    @Test
    fun `with no library the sample strip declines instead of returning bytes`() {
        // The two strip call sites in DolbyVisionCompat read null as "run the
        // Kotlin parser", so a build without the .so must answer exactly that —
        // not an empty array (which would erase the sample, since the caller
        // copies what it is given) and not the input (which would look to the
        // sniff path like a strip that ran).
        val sample = byteArrayOf(0, 0, 0, 1, 0x7C, 0x01, 0x11, 0x22)
        val before = sample.copyOf()
        assertNull(
            "no .so in this process, so the caller falls back to the Kotlin strip",
            DoviBridge.stripSample(
                sample,
                sample.size,
                DoviBridge.NAL_FORMAT_ANNEX_B,
                0,
                stripDv = true,
                stripHdr10Plus = true
            )
        )
        assertArrayEquals("and the caller's buffer is left alone", before, sample)
        // Degenerate lengths are refused before anything else, like the
        // conversion path's short ranges.
        assertNull(DoviBridge.stripSample(sample, 0, DoviBridge.NAL_FORMAT_ANNEX_B, 0, true, true))
    }

    @Test
    fun `with no library the RPU conversion declines instead of inventing bytes`() {
        val sample = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x7C, 0x01, 0x11, 0x22)
        assertNull(
            "the caller has to fall back to the Kotlin rewrite, so this must be null",
            DoviBridge.convertRpuTo81(sample, 4, sample.size)
        )
        // A degenerate range is refused the same way (before any JNI call).
        assertNull(DoviBridge.convertRpuTo81(sample, 4, 5))
        assertTrue("and nothing is written back into the caller's sample", sample[4] == 0x7C.toByte())
    }
}
