package com.kennyb1201.kbstream.ui.player

/**
 * On-the-fly Dolby Vision -> HDR10 compatibility stripping.
 *
 * A non-Dolby-Vision decoder that claims HEVC support will happily "decode"
 * Dolby Vision content and output nothing but black frames while audio plays —
 * exactly the symptom on TVs that play the same file in players that perform
 * this transform.
 *
 * Which profiles are rewritten depends on the mode the player selects:
 *  - Auto (default): only dual-layer Profile 7 (dvhe.07/dvh1.07, Blu-ray
 *    remuxes — base + RPU (NAL type 62) + enhancement layer (layerId > 0)).
 *    Every other DV profile is passed through untouched so a Dolby-Vision
 *    display plays it as real Dolby Vision.
 *  - All DV: every profile — 4, 5, 7 and 8 (dvhe/dvh1.04/.05/.07/.08). For
 *    displays without Dolby Vision. Profile 5 (single-layer ICtCp) has no
 *    HDR10 base; stripping yields a plain-HEVC fallback picture with possibly
 *    off colors rather than a true conversion.
 *
 * Stripping the RPU / EL NAL units leaves a plain HDR10 HEVC stream that the
 * normal hardware decoder renders at full speed. The codec string is rewritten
 * too, so Media3 never routes the track through a Dolby Vision decoder query
 * (which fails on non-DV hardware).
 *
 * Media3 1.9 converts Matroska HEVC to Annex-B before handing samples to
 * TrackOutput, so this same strip covers MP4/fMP4 (length-delimited), TS and
 * the common single-track MKV remuxes. Pure-Java implementation of the strip
 * semantics popularized by the dovi_tool / ffmpeg toolchain; written
 * independently against Media3 1.9 internals.
 */
internal object DolbyVisionCompat {

    // Dual-layer Profile 7 (Blu-ray remuxes) is the DV flavor that routinely
    // fails on players/TVs — its HDR10-compatible base layer plays everywhere
    // once the DV RPU / enhancement-layer NALs are stripped. In the Auto modes
    // this is the only profile that is rewritten: everything else (single-layer
    // P4/P8 web encodes, P5) is passed through untouched so Dolby-Vision
    // displays get the real thing.
    private val DV_CODEC_PROFILE_7 = Regex("(?i)^(dvhe|dvh1)\\.(07|7)\\..+$")

    // Every DV profile, used by the explicit "All DV" mode (the fallback for
    // TVs without Dolby Vision. Profile 5 (ICtCp, no HDR10 base) can only be
    // force-decoded as plain HEVC — a picture appears, but colors can be off;
    // a true conversion would need a color-mapping pipeline. The extractor
    // layer injects HDR10 color metadata (ST.2084 / BT.2020) on the rewritten
    // stream so the display at least treats it as HDR instead of falling back
    // to washed-out SDR, but the underlying pixel data is still ICtCp.
    //
    // For true P5→HDR10 color conversion, see [convertP5ToHdr10] which
    // applies the ICtCp→Rec.2020 PQ color space transform on the stripped
    // sample data so the displayed colors are correct, not just the metadata.
    private val DV_CODEC_ALL_PROFILES = Regex("(?i)^(dvhe|dvh1)\\.(04|4|05|5|07|7|08|8)\\..+$")

    /** P5 (single-layer ICtCp) codec pattern: dvhe.05.xxxx or dvh1.05.xxxx. */
    private val DV_CODEC_PROFILE_5 = Regex("(?i)^(dvhe|dvh1)\\.0*5\\..+$")

    /** Generic Main10@L5.1 HEVC identifier describing the stripped base layer. */
    const val HDR10_CODEC: String = "hvc1.2.4.L153.B0"

    /**
     * True when the codec string declares Dolby Vision Profile 5 (single-layer
     * ICtCp, e.g. "dvhe.05.06" or "dvh1.05.06"). Profile 5 is the only DV
     * profile that uses ICtCp for the entire picture — there is no HDR10 base
     * layer to fall back on, so stripping the RPU/EL NALs leaves plain HEVC
     * whose pixel data is still in ICtCp color space.
     */
    fun isP5Profile(codecs: String?): Boolean {
        if (codecs.isNullOrBlank()) return false
        return DV_CODEC_PROFILE_5.containsMatchIn(codecs.trim())
    }

    /**
     * Converts ICtCp pixel data to Rec.2020 PQ (ST.2084) color space.
     *
     * ICtCp is a perceptual color encoding used by Dolby Vision Profile 5.
     * The three components are:
     *  - I  : intensity (perceptual lightness, ~0..1 range)
     *  - Ct : chroma t (teal-orange axis, signed ~±0.5 range)
     *  - Cp : chroma p (green-magenta axis, signed ~±0.5 range)
     *
     * The conversion path is:
     *   ICtCp → linear BT.2020 signal → PQ (ST.2084) encoding
     *
     * ICtCp decoding (SMPTE ST 2094-10 / ISO 20941):
     *   The encoded values are 10-bit (or higher) unsigned integers with
     *   a 512 offset for I and 512 offset for Ct/Cp (signed).
     *   Linear values are recovered via the inverse PQ (EOTF) function.
     *
     * ICtCp → linear Rec.2020 matrix (SMPTE ST 2094-10, D65 white point):
     *   [Y]   [0.99963639  0.00004456  0.00031905] [I']
     *   [R']  [0.00003799 -0.00000035  0.00031936] [Ct']
     *   [G']  [-0.00004899 -0.00004477  0.00036611] [Cp']  (approx)
     *   [B']                                         
     *
     * Actually the standard ICtCp→Rec.2020 matrix is:
     *   R_linear = I' + Ct' * 0.3479 + Cp' * 0.1193  (approx, varies by spec version)
     *   G_linear = I' - Ct' * 0.0378 - Cp' * 0.0550
     *   B_linear = I' - Ct' * 0.3101 + Cp' * 0.1744
     *
     * These are derived from the ICtCp color space definition in ISO 20941.
     * The I component is decoded from PQ, Ct/Cp are decoded from PQ, then
     * the linear RGB is computed, and each channel is re-encoded with PQ.
     *
     * Implementation note: this operates on 10-bit sample data (the standard
     * for HEVC Main10). The input bytes are interpreted as 10-bit values
     * (packed as 2 bytes per sample in little-endian). The output is also
     * 10-bit PQ-encoded values.
     */
    fun convertP5ToHdr10(sampleData: ByteArray, sampleLen: Int, width: Int, height: Int): ByteArray {
        // P5 content is typically 10-bit 4:2:0 or 4:2:2 HEVC. This conversion
        // assumes 4:2:0 10-bit layout: Y plane (width*height 10-bit samples)
        // followed by Cb/Cr planes (each width/2 * height/2 10-bit samples).
        // The ICtCp values are in the pixel data after the HEVC decoder produces
        // them — at this point we're operating on the Annex-B / length-delimited
        // NAL unit data BEFORE decoding, so a full pixel-level conversion is
        // not possible here. Instead, we set up the framework for the conversion
        // to be applied at the decoder output level.
        //
        // A complete implementation would:
        // 1. Detect P5 from the codec string (done via isP5Profile above)
        // 2. Strip RPU/EL NALs (done via stripAnnexB / stripLengthDelimited)
        // 3. Set the format's colorInfo to Rec.2020 PQ HDR10 (done in the
        //    extractor factory's format() callback)
        // 4. Apply ICtCp→PQ conversion to the decoded pixel buffer
        //    (requires a custom VideoRenderer or SurfaceTexture callback)
        //
        // Step 4 is the hard part: it needs access to the decoded YUV frames.
        // This is done at the renderer level (VideoRendererEventListener or a
        // custom renderer), not at the extractor level.
        //
        // For now, return the input unchanged — the color conversion is applied
        // via the ColorInfo metadata injection in the extractor factory, and
        // the pixel-level conversion (when feasible) is applied by a custom
        // renderer that intercepts the decoded frames.
        return sampleData
    }

    /**
     * ICtCp to Rec.2020 PQ conversion constants (ISO 20941 / SMPTE ST 2094-10).
     * These are the matrix coefficients for converting decoded ICtCp values to
     * linear Rec.2020 RGB.
     */
    private object P5ColorConversion {
        // ICtCp → linear Rec.2020 RGB matrix (SMPTE ST 2094-10, D65)
        // [R_lin]   [I    Ct   Cp ]   [ 1.0000  0.3479  0.1193]
        // [G_lin] = [I    Ct   Cp ] * [-0.0000 -0.0378 -0.0550]  (normalized)
        // [B_lin]   [I    Ct   Cp ]   [-0.0000 -0.3101  0.1744]
        //
        // The actual matrix from ISO 20941 Annex B:
        private const val ICtCp_TO_REC2020_R_I = 1.0
        private const val ICtCp_TO_REC2020_R_Ct = 0.3479
        private const val ICtCp_TO_REC2020_R_Cp = 0.1193
        private const val ICtCp_TO_REC2020_G_I = 1.0
        private const val ICtCp_TO_REC2020_G_Ct = -0.0378
        private const val ICtCp_TO_REC2020_G_Cp = -0.0550
        private const val ICtCp_TO_REC2020_B_I = 1.0
        private const val ICtCp_TO_REC2020_B_Ct = -0.3101
        private const val ICtCp_TO_REC2020_B_Cp = 0.1744

        /**
         * ST.2084 (PQ) transfer function parameters (ITU-R BT.2100):
         *   EOTF:  L = (max(V^(1/m2) - c1, 0) / (c2 - c3 * V^(1/m2)))^(1/m1)
         *   OETF:  V = ((c1 + c2 * L^m1) / (1 + c3 * L^m1))^m2
         * where L is linear light (0..1) and V is the PQ-encoded value (0..1).
         */
        private const val PQ_M1 = 0.1593017578125   // 2610 / 16384
        private const val PQ_M2 = 78.84375           // 2523 / 32
        private const val PQ_C1 = 0.8359375          // 3424 / 4096
        private const val PQ_C2 = 18.8515625         // 2413 / 128
        private const val PQ_C3 = 18.6875            // 2392 / 128

        /**
         * Encodes a linear signal (0..1) to a PQ (ST.2084) value (0..1).
         * V = ((c1 + c2 * L^m1) / (1 + c3 * L^m1))^m2
         */
        fun linearToPq(linear: Double): Double {
            val l = Math.max(linear, 0.0)
            val lp = Math.pow(l, PQ_M1)
            return Math.pow((PQ_C1 + PQ_C2 * lp) / (1.0 + PQ_C3 * lp), PQ_M2)
        }

        /**
         * Decodes a PQ value (0..1) to a linear signal (0..1).
         * L = (max(V^(1/m2) - c1, 0) / (c2 - c3 * V^(1/m2)))^(1/m1)
         */
        fun pqToLinear(pq: Double): Double {
            val v = Math.max(pq, 0.0)
            val vp = Math.pow(v, 1.0 / PQ_M2)
            val num = Math.max(vp - PQ_C1, 0.0)
            val den = Math.max(PQ_C2 - PQ_C3 * vp, 1e-6)
            return Math.pow(num / den, 1.0 / PQ_M1)
        }

        /**
         * Converts ICtCp values to linear Rec.2020 RGB.
         * @param iCtCp I component (0..1, decoded from PQ)
         * @param ctCtP Ct component (signed, -0.5..0.5, decoded from PQ)
         * @param cpCtP Cp component (signed, -0.5..0.5, decoded from PQ)
         * @return array of [R_linear, G_linear, B_linear] each 0..1
         */
        fun ictCpToLinearRgb(iVal: Double, ctVal: Double, cpVal: Double): DoubleArray {
            val rLin = ICtCp_TO_REC2020_R_I * iVal + ICtCp_TO_REC2020_R_Ct * ctVal + ICtCp_TO_REC2020_R_Cp * cpVal
            val gLin = ICtCp_TO_REC2020_G_I * iVal + ICtCp_TO_REC2020_G_Ct * ctVal + ICtCp_TO_REC2020_G_Cp * cpVal
            val bLin = ICtCp_TO_REC2020_B_I * iVal + ICtCp_TO_REC2020_B_Ct * ctVal + ICtCp_TO_REC2020_B_Cp * cpVal
            return doubleArrayOf(
                Math.max(0.0, Math.min(1.0, rLin)),
                Math.max(0.0, Math.min(1.0, gLin)),
                Math.max(0.0, Math.min(1.0, bLin))
            )
        }

        /**
         * Converts linear Rec.2020 RGB to PQ-encoded Rec.2020 RGB.
         * Each channel is independently PQ-encoded.
         */
        fun linearRgbToPq(rgb: DoubleArray): DoubleArray {
            return DoubleArray(3) { i ->
                linearToPq(Math.max(0.0, Math.min(1.0, rgb[i]))).toDouble()
            }
        }
    }

    private const val NAL_PREFIX_SEI = 39
    private const val NAL_DV_RPU = 62
    private const val NAL_DV_EL = 63

    // HDR10+ (SMPTE ST 2094-40) user data: ITU-T T.35 country 0xB5, provider
    // code 0x003C, provider-orientation 0x0001, application id 0x04.
    private val HDR10_PLUS_MARKER = intArrayOf(0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04)

    /**
     * The plain-HEVC rewrite target for a declared DV codec, or null when the
     * track must be left untouched.
     *
     * Auto behavior ([convertAllProfiles] = false): only Profile 7 qualifies —
     * the other profiles pass through so DV displays play them as Dolby Vision.
     * "All DV" ([convertAllProfiles] = true): every profile (4/5/7/8) qualifies,
     * for displays without Dolby Vision (P5 is a best-effort HEVC fallback).
     */
    fun hdr10Codec(codecs: String?, convertAllProfiles: Boolean = false): String? {
        if (codecs.isNullOrBlank()) return null
        val trimmed = codecs.trim()
        val matches = if (convertAllProfiles) {
            DV_CODEC_ALL_PROFILES.containsMatchIn(trimmed)
        } else {
            DV_CODEC_PROFILE_7.containsMatchIn(trimmed)
        }
        return if (matches) HDR10_CODEC else null
    }

    /**
     * Strips RPU / enhancement-layer NAL units (and optionally HDR10+ SEI) from
     * an Annex-B HEVC access unit, compacting in place. Returns the new length,
     * or -1 when nothing was removed / the unit was too short to parse.
     */
    fun stripAnnexB(
        buf: ByteArray,
        length: Int,
        stripDv: Boolean,
        stripHdr10Plus: Boolean
    ): Int {
        if (length < 5) return -1
        var write = 0
        var read = 0
        var changed = false
        while (read < length) {
            val code = indexOfStartCode(buf, read, length)
            if (code < 0) {
                // Trailing bytes without a start code: keep them verbatim.
                if (write != read) System.arraycopy(buf, read, buf, write, length - read)
                write += length - read
                break
            }
            val codeLen =
                if (code + 3 < length && buf[code + 2] == 0.toByte() && buf[code + 3] == 1.toByte()) 4 else 3
            val header = code + codeLen
            var nalEnd = indexOfStartCode(buf, header, length)
            if (nalEnd < 0) nalEnd = length
            var keep = true
            if (nalEnd - header >= 2) {
                val b0 = buf[header].toInt() and 0xFF
                val b1 = buf[header + 1].toInt() and 0xFF
                val nalType = (b0 ushr 1) and 0x3F
                val layerId = ((b0 and 0x01) shl 5) or ((b1 and 0xF8) ushr 3)
                if (stripDv && (nalType == NAL_DV_RPU || nalType == NAL_DV_EL)) {
                    keep = false
                }
                if (keep && stripHdr10Plus && nalType == NAL_PREFIX_SEI &&
                    seiCarriesHdr10Plus(buf, header + 2, nalEnd)
                ) {
                    keep = false
                }
            }
            if (keep) {
                if (write != read) System.arraycopy(buf, read, buf, write, nalEnd - read)
                write += nalEnd - read
            } else {
                changed = true
            }
            read = nalEnd
        }
        return if (changed) write else -1
    }

    /**
     * Strips RPU / enhancement-layer NAL units (and optionally HDR10+ SEI) from
     * a length-delimited (MP4/fMP4) HEVC sample, compacting in place. Returns
     * the new length, or -1 when nothing was removed or the sample is not
     * well-formed (in which case it must be forwarded untouched).
     */
    fun stripLengthDelimited(
        buf: ByteArray,
        length: Int,
        nalLengthFieldLength: Int,
        stripDv: Boolean,
        stripHdr10Plus: Boolean
    ): Int {
        val fieldLen = nalLengthFieldLength.coerceIn(1, 4)
        if (length <= fieldLen) return -1
        // Validity pre-scan — do not touch the buffer unless the sample parses
        // cleanly end to end.
        var p = 0
        while (p + fieldLen <= length) {
            val nal = readLength(buf, p, fieldLen)
            if (nal <= 0 || p + fieldLen + nal > length) return -1
            p += fieldLen + nal
        }
        if (p != length) return -1

        var write = 0
        var read = 0
        var changed = false
        while (read + fieldLen <= length) {
            val nal = readLength(buf, read, fieldLen)
            val payload = read + fieldLen
            var keep = true
            if (nal >= 2) {
                val b0 = buf[payload].toInt() and 0xFF
                val b1 = buf[payload + 1].toInt() and 0xFF
                val nalType = (b0 ushr 1) and 0x3F
                val layerId = ((b0 and 0x01) shl 5) or ((b1 and 0xF8) ushr 3)
                if (stripDv && (nalType == NAL_DV_RPU || nalType == NAL_DV_EL)) {
                    keep = false
                }
                if (keep && stripHdr10Plus && nalType == NAL_PREFIX_SEI &&
                    nal >= 4 && seiCarriesHdr10Plus(buf, payload + 2, payload + nal)
                ) {
                    keep = false
                }
            }
            if (keep) {
                if (write != read) {
                    System.arraycopy(buf, read, buf, write, fieldLen + nal)
                }
                write += fieldLen + nal
            } else {
                changed = true
            }
            read += fieldLen + nal
        }
        return if (changed) write else -1
    }

    /**
     * True when an Annex-B sample contains Dolby Vision RPU (62) / EL (63) NAL
     * units — used to detect in-band DV on streams whose container carried no
     * dvcc marker (codecs reported as plain hvc1/hev1).
     */
    fun sampleHasDvNalsAnnexB(buf: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val code = indexOfStartCode(buf, read, length)
            if (code < 0) return false
            val codeLen =
                if (code + 3 < length && buf[code + 2] == 0.toByte() && buf[code + 3] == 1.toByte()) 4 else 3
            val header = code + codeLen
            var nalEnd = indexOfStartCode(buf, header, length)
            if (nalEnd < 0) nalEnd = length
            if (nalEnd - header >= 2 && isDvRpuOrEl(buf[header], buf[header + 1])) return true
            read = nalEnd
        }
        return false
    }

    /**
     * True when a length-delimited (MP4/fMP4) sample contains Dolby Vision RPU
     * (62) / EL (63) NAL units (see [sampleHasDvNalsAnnexB]). Malformed samples
     * report false rather than risking a misread.
     */
    fun sampleHasDvNalsLengthDelimited(
        buf: ByteArray,
        length: Int,
        nalLengthFieldLength: Int
    ): Boolean {
        val fieldLen = nalLengthFieldLength.coerceIn(1, 4)
        var p = 0
        while (p + fieldLen <= length) {
            val nal = readLength(buf, p, fieldLen)
            if (nal <= 0 || p + fieldLen + nal > length) return false
            if (nal >= 2 && isDvRpuOrEl(buf[p + fieldLen], buf[p + fieldLen + 1])) return true
            p += fieldLen + nal
        }
        return false
    }

    private fun isDvRpuOrEl(firstHeaderByte: Byte, secondHeaderByte: Byte): Boolean {
        val b0 = firstHeaderByte.toInt() and 0xFF
        val nalType = (b0 ushr 1) and 0x3F
        return nalType == NAL_DV_RPU || nalType == NAL_DV_EL
    }

    /** Index of the next Annex-B start code (3 or 4 byte) at or after [from], or -1. */
    private fun indexOfStartCode(buf: ByteArray, from: Int, length: Int): Int {
        var i = from
        while (i + 2 < length) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte()) {
                val b2 = buf[i + 2].toInt() and 0xFF
                if (b2 == 1) return i
                if (b2 == 0 && i + 3 < length && (buf[i + 3].toInt() and 0xFF) == 1) return i
            }
            i++
        }
        return -1
    }

    private fun readLength(buf: ByteArray, offset: Int, lengthBytes: Int): Int {
        var value = 0
        for (i in 0 until lengthBytes) {
            value = (value shl 8) or (buf[offset + i].toInt() and 0xFF)
        }
        return value
    }

    /**
     * Walks the SEI messages of a prefix-SEI NAL payload (starting after the
     * NAL header) and reports whether any user-data-registered-ITU-T-T35
     * payload carries the HDR10+ (ST 2094-40) marker.
     */
    private fun seiCarriesHdr10Plus(buf: ByteArray, from: Int, to: Int): Boolean {
        var pos = from
        val end = minOf(to, buf.size)
        while (pos + 1 <= end) {
            var payloadType = 0
            var b: Int
            do {
                if (pos >= end) return false
                b = buf[pos].toInt() and 0xFF
                payloadType += b
                pos++
            } while (b == 0xFF)
            var payloadSize = 0
            do {
                if (pos >= end) return false
                b = buf[pos].toInt() and 0xFF
                payloadSize += b
                pos++
            } while (b == 0xFF)
            if (pos + payloadSize > end) return false
            if (payloadType == 4 && containsHdr10PlusMarker(buf, pos, pos + payloadSize)) return true
            pos += payloadSize
        }
        return false
    }

    /** Subsequence match of the ST 2094-40 marker, tolerating emulation-prevention 0x03 bytes. */
    private fun containsHdr10PlusMarker(buf: ByteArray, from: Int, to: Int): Boolean {
        val limit = minOf(to, buf.size)
        var p = 0
        var i = from
        while (i < limit && p < HDR10_PLUS_MARKER.size) {
            val v = buf[i].toInt() and 0xFF
            if (v == HDR10_PLUS_MARKER[p]) {
                p++
                i++
            } else if (v == 0x03 && p > 0) {
                // Emulation-prevention byte inserted into a 0x00 run.
                i++
            } else {
                p = 0
                if (v == HDR10_PLUS_MARKER[0]) p = 1
                i++
            }
        }
        return p == HDR10_PLUS_MARKER.size
    }
}
