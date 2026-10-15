package com.kennyb1201.kbstream.ui.player

/**
 * The HDR10 static metadata a Dolby Vision RPU carries with it, and the
 * conversion into the 25-byte CTA-861.3 record Media3 hands the decoder.
 *
 * Why this exists
 * ---------------
 * A Profile 8 RPU carries Dolby's own ST 2086/HDR10 metadata fallback — the
 * "Level 6" block, in libdovi's words *"ST2086/HDR10 metadata fallback"*:
 * max/min display mastering luminance and MaxCLL/MaxFALL. On a Profile 8 stream
 * that metadata is frequently ONLY there: the base layer is a plain HEVC
 * HDR10 base with no mastering-display / content-light SEI of its own.
 *
 * So stripping the RPU (which this app must do on boxes whose decoder stalls
 * with it in the stream) takes the stream's only HDR10 static metadata with it.
 * What is left is PQ/BT.2020 with no mastering display, and a TV that has no
 * idea how to map the picture — the 2026-10-14 field report: HDR10 declared,
 * `hdrStaticInfo=absent`, black screen.
 *
 * Preserving the base layer's SEI is the other half of the answer and is
 * already the strip path's job (see [DolbyVisionCompat.stripAnnexB]'s
 * `kotlinOnly` surgery, which shrinks a shared SEI NAL instead of dropping it).
 * This file is the half that PROVIDES the metadata for the files that have
 * none: the values come from the RPU, read through libdovi just before it is
 * dropped, and are attached to the relabeled format's [ColorInfo] — which is
 * exactly where the reference implementation keeps its copy, and where
 * `MediaFormatUtil` picks it up for `MediaFormat.KEY_HDR_STATIC_INFO`.
 *
 * Units and layout
 * ----------------
 * [hdr10StaticInfoBytes] reproduces Media3's own writer — the 25-byte record
 * `MatroskaExtractor.getHdrStaticInfo()` builds from a file's container
 * metadata — byte for byte where the two agree, because these bytes end up in
 * the same field and are forwarded to the codec verbatim: the leading
 * descriptor id, the eight chromaticities as CTA-861.3's 0.00002 units, and
 * max mastering luminance, MaxCLL and MaxFALL as whole cd/m².
 *
 * The minimum is the one field where this deliberately differs. CTA-861.3's
 * minimum is in 0.0001 cd/m² units — the same unit the RPU's Level 6 block
 * stores it in — so it is written through unchanged. Media3's writer feeds
 * Matroska's *cd/m²* float into the same short, which truncates the usual
 * 0.0001 cd/m² minimum to 0; matching that would mean writing a value 10⁴ times
 * too small for the field it lands in.
 *
 * The display primaries are the fixed ones, not a guess: a Profile 8 base layer
 * is BT.2020 with a D65 white point by definition, and the RPU's Level 6 block
 * carries no primaries to read (its sibling levels carry *indices*, not
 * chromaticities). The luminance and light-level values are the RPU's own,
 * scaled out of the units that block stores them in — see [DvLevel6].
 */

/**
 * libdovi's `DoviExtMetadataBlockLevel6`, in the units Dolby stores it:
 * [maxDisplayMasteringLuminance] and both light levels are cd/m², and
 * [minDisplayMasteringLuminance] is in 1e-4 cd/m² (the unit that lets a value
 * like 0.0001 be representable in 16 bits at all — dovi_tool's own C example
 * divides exactly that field by 10000).
 */
internal data class DvLevel6(
    val maxDisplayMasteringLuminance: Int,
    val minDisplayMasteringLuminance: Int,
    val maxContentLightLevel: Int,
    val maxFrameAverageLightLevel: Int
)

/** The Level 6 block [DoviBridge.readHdr10StaticMetadata] returned, or null. */
internal fun dvLevel6From(values: IntArray?): DvLevel6? {
    if (values == null || values.size < 4) return null
    return DvLevel6(
        maxDisplayMasteringLuminance = values[0],
        minDisplayMasteringLuminance = values[1],
        maxContentLightLevel = values[2],
        maxFrameAverageLightLevel = values[3]
    )
}

/**
 * The 25-byte CTA-861.3 static metadata record for [level6], or null when the
 * block carries nothing worth attaching (no mastering luminance at all: the
 * decoder would be handed a record that says less than the forced HDR10
 * ColorInfo already does).
 *
 * Layout, matching Media3's writer byte for byte: one type byte, the eight
 * chromaticity shorts (R, G, B, white point) in 0.00002 units, then max
 * mastering luminance, min mastering luminance, MaxCLL and MaxFALL, each a
 * little-endian short in cd/m².
 */
internal fun hdr10StaticInfoBytes(level6: DvLevel6): ByteArray? {
    if (level6.maxDisplayMasteringLuminance <= 0) return null

    val out = ByteArray(25)
    out[0] = 0 // CTA-861.3 static metadata type 0.
    var at = 1
    fun putShort(value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
        at += 2
    }
    // BT.2020 primaries and the D65 white point, in 0.00002 units.
    putShort(35_400) // R x  (0.708)
    putShort(14_600) // R y  (0.292)
    putShort(8_500) //  G x  (0.170)
    putShort(39_850) // G y  (0.797)
    putShort(6_550) //  B x  (0.131)
    putShort(2_300) //  B y  (0.046)
    putShort(15_635) // W x  (0.3127)
    putShort(16_450) // W y  (0.3290)
    putShort(level6.maxDisplayMasteringLuminance.coerceIn(0, 65_535))
    // Same unit in and out: CTA-861.3 defines this field in 0.0001 cd/m², and
    // so does the RPU's Level 6 block (a stored 1 is 0.0001 cd/m², the usual
    // value for a stream whose minimum is "black").
    putShort(level6.minDisplayMasteringLuminance.coerceIn(0, 65_535))
    putShort(level6.maxContentLightLevel.coerceIn(0, 65_535))
    putShort(level6.maxFrameAverageLightLevel.coerceIn(0, 65_535))
    return out
}
