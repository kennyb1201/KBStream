package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The RPU Level 6 -> HDR10 static metadata conversion.
 *
 * This is the half of the 2026-10-14 `hdrStaticInfo=absent` fix that can be
 * tested off-device: the record's byte layout and unit conversion are pure
 * arithmetic over four integers, and the bytes this produces are forwarded to
 * the decoder verbatim — so getting a unit or an endianness wrong is a wrong
 * picture, not a crash. The reading of the block out of a real RPU is libdovi's
 * and is exercised on-device (see [DoviBridge.readHdr10StaticMetadata]).
 *
 * The expected layout is Media3's own writer
 * (`MatroskaExtractor.getHdrStaticInfo`, read at the pinned media3 version),
 * because these bytes land in the same `ColorInfo.hdrStaticInfo` field and are
 * passed to the codec unchanged: type byte, eight chromaticity shorts in
 * 0.00002 units (R, G, B, white point), then max mastering luminance, min
 * mastering luminance, MaxCLL and MaxFALL as little-endian shorts. Max, MaxCLL
 * and MaxFALL are whole cd/m²; the minimum is in 0.0001 cd/m², which is the
 * unit the RPU stores it in — the field Media3's writer gets wrong by feeding
 * Matroska's cd/m² float straight into it.
 */
class DvHdr10StaticInfoTest {

    private val bt2020D65 = intArrayOf(
        35_400, // R x (0.708)
        14_600, // R y (0.292)
        8_500, // G x (0.170)
        39_850, // G y (0.797)
        6_550, // B x (0.131)
        2_300, // B y (0.046)
        15_635, // W x (0.3127)
        16_450 // W y (0.3290)
    )

    /** The eight chromaticity shorts, decoded the way the decoder reads them. */
    private fun chromaticities(bytes: ByteArray): IntArray =
        IntArray(8) { i -> shortAt(bytes, 1 + i * 2) }

    private fun shortAt(bytes: ByteArray, at: Int): Int =
        ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at].toInt() and 0xFF)

    @Test
    fun `the record is the 25-byte CTA-861_3 shape with BT_2020 and D65`() {
        val bytes = hdr10StaticInfoBytes(
            DvLevel6(
                maxDisplayMasteringLuminance = 1000,
                minDisplayMasteringLuminance = 1,
                maxContentLightLevel = 1000,
                maxFrameAverageLightLevel = 400
            )
        )
        assertNotNull("a block with a mastering luminance has to produce a record", bytes)
        val record = bytes!!
        assertEquals("CTA-861.3 static metadata is 25 bytes", 25, record.size)
        assertEquals("type 0 is the HDR10 static metadata descriptor", 0, record[0].toInt())
        assertArrayEquals(
            "a Profile 8 base layer is BT.2020 at D65, and Level 6 carries no primaries to " +
                "override that with",
            bt2020D65,
            chromaticities(record)
        )
    }

    @Test
    fun `the luminance and light-level fields are cd per square metre, little-endian`() {
        val record = hdr10StaticInfoBytes(
            DvLevel6(
                maxDisplayMasteringLuminance = 1000,
                // The RPU's minimum is in 0.0001 cd/m², and so is this record's
                // field — a stored 1 means 0.0001 cd/m².
                minDisplayMasteringLuminance = 1,
                maxContentLightLevel = 800,
                maxFrameAverageLightLevel = 300
            )
        )!!
        assertEquals(
            "max mastering luminance is whole cd/m² in the RPU and in this record",
            1000,
            shortAt(record, 17)
        )
        assertEquals(
            "the CTA-861.3 minimum is in 0.0001 cd/m² — the RPU's own unit — so the " +
                "value passes through, and 1 stays 1 (0.0001 cd/m²)",
            1,
            shortAt(record, 19)
        )
        assertEquals("MaxCLL", 800, shortAt(record, 21))
        assertEquals("MaxFALL", 300, shortAt(record, 23))
    }

    @Test
    fun `a block with no mastering luminance produces nothing`() {
        assertNull(
            "a record stating no mastering display says less than the forced HDR10 color info " +
                "already does, so it must not replace anything",
            hdr10StaticInfoBytes(
                DvLevel6(
                    maxDisplayMasteringLuminance = 0,
                    minDisplayMasteringLuminance = 0,
                    maxContentLightLevel = 1000,
                    maxFrameAverageLightLevel = 400
                )
            )
        )
    }

    @Test
    fun `the bridge's four values become the block, and a short answer is no block`() {
        val block = dvLevel6From(intArrayOf(1000, 10000, 800, 300))
        assertEquals(1000, block?.maxDisplayMasteringLuminance)
        assertEquals(10_000, block?.minDisplayMasteringLuminance)
        assertEquals(800, block?.maxContentLightLevel)
        assertEquals(300, block?.maxFrameAverageLightLevel)

        assertNull("no libdovi / no RPU / no Level 6 all arrive as null", dvLevel6From(null))
        assertNull(
            "a partial array is not a block",
            dvLevel6From(intArrayOf(1000, 1, 800))
        )
    }

    @Test
    fun `values outside a short are clamped rather than wrapped`() {
        val record = hdr10StaticInfoBytes(
            DvLevel6(
                maxDisplayMasteringLuminance = 700_000,
                minDisplayMasteringLuminance = 0,
                maxContentLightLevel = 70_000,
                maxFrameAverageLightLevel = 65_536
            )
        )!!
        assertEquals(
            "a saturated field must not wrap to a small luminance",
            65_535,
            shortAt(record, 17)
        )
        assertEquals(65_535, shortAt(record, 21))
        assertEquals(
            "and neither must a MaxFALL one over the field's range",
            65_535,
            shortAt(record, 23)
        )
    }
}
