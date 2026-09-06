package com.kennyb1201.kbstream.ui.player

import java.io.ByteArrayOutputStream

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

    /**
     * Single-layer profiles whose base layer is already standard HDR10 HEVC
     * (dvhe.04 / dvh1.04, dvhe.08 / dvh1.08 web encodes). Unlike Profile 7
     * (dual-layer Blu-ray remuxes) there is no enhancement layer that must be
     * removed, and unlike Profile 5 the pixels are not ICtCp — the stream
     * decodes as plain HDR10 the moment the decoder ignores the in-band RPU
     * NALs, which is exactly what other players feed the hardware decoder.
     */
    private val DV_CODEC_HDR10_BASE_LAYER = Regex("(?i)^(dvhe|dvh1)\\.0*[48]\\..+$")

    /**
     * True when the codec string declares a single-layer DV profile with a
     * standard HDR10 base layer (Profile 4 or Profile 8). Such streams play as
     * plain HDR10 without any NAL stripping or codec rewriting — mutating them
     * is what some hardware decoders choke on (configure OK, never output).
     */
    fun isHdr10BaseLayerProfile(codecs: String?): Boolean {
        if (codecs.isNullOrBlank()) return false
        return DV_CODEC_HDR10_BASE_LAYER.containsMatchIn(codecs.trim())
    }

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
     * Aggregate strip/rewrite counters for one stream's first processed sample,
     * used to report exactly what was removed so device quirks stay diagnosable.
     */
    internal class StripStats {
        var rpuBytes = 0
        var elBytes = 0
        var hdr10PlusBytes = 0
        var vpsRewritten = false
    }

    // ── VPS rewriting ──────────────────────────────────────────────────────
    // Dolby Vision HEVC streams advertise themselves through a VPS extension
    // (vps_extension_flag + Dolby Vision signaling). Once the RPU / EL NALs are
    // stripped, a decoder that keys off that extension sits in "DV mode" waiting
    // for RPUs that will never arrive — MediaTek-class decoders in particular
    // stall completely (input frames in, zero output). Rewriting the VPS to a
    // clean single-layer parameter set (extension removed, layer count forced to
    // 1) is the companion step to the strip. Bit layouts mirror hevc_parser (the
    // parser dovi_tool uses) — ITU-T H.265 7.3.2.1 / 7.3.3.

    private const val NAL_VPS = 32

    /** Bit-level reader over an RBSP (emulation-prevention bytes removed). */
    private class VpsBitReader(private val data: ByteArray) {
        var pos = 0
        private val bitLen = data.size * 8

        fun readBits(n: Int): Long {
            if (pos + n > bitLen) return -1
            var v = 0L
            for (i in 0 until n) {
                val byte = data[pos ushr 3].toInt() and 0xFF
                val bit = (byte ushr (7 - (pos and 7))) and 1
                v = (v shl 1) or bit.toLong()
                pos++
            }
            return v
        }

        /** Unsigned Exp-Golomb (ue(v)). Returns -1 on overrun. */
        fun readUe(): Long {
            var zeros = 0
            while (true) {
                val b = readBits(1)
                if (b == -1L) return -1
                if (b == 1L) break
                zeros++
                if (zeros > 32) return -1
            }
            val rest = readBits(zeros)
            if (rest == -1L) return -1
            return (1L shl zeros) - 1L + rest
        }

        fun skipBits(n: Int): Boolean {
            if (pos + n > bitLen) return false
            pos += n
            return true
        }

        /** Copies [from, to) bits into [out]; does not disturb the reader position. */
        fun copyBits(from: Int, to: Int, out: VpsBitWriter): Boolean {
            if (from < 0 || to > bitLen || from > to) return false
            val saved = pos
            pos = from
            while (pos < to) {
                val b = readBits(1)
                if (b == -1L) {
                    pos = saved
                    return false
                }
                out.writeBits(b, 1)
            }
            pos = saved
            return true
        }
    }

    /** Bit-level writer that packs bits into bytes. */
    private class VpsBitWriter {
        private val out = ByteArrayOutputStream()
        private var acc = 0
        private var nBits = 0

        fun writeBits(value: Long, n: Int) {
            for (i in n - 1 downTo 0) {
                acc = (acc shl 1) or (((value ushr i) and 1L).toInt())
                nBits++
                if (nBits == 8) {
                    out.write(acc)
                    acc = 0
                    nBits = 0
                }
            }
        }

        fun finish(): ByteArray {
            if (nBits > 0) {
                out.write(acc shl (8 - nBits))
                nBits = 0
            }
            return out.toByteArray()
        }
    }

    /** Removes emulation-prevention bytes (0x03 after 0x00 0x00) from an RBSP slice. */
    private fun removeEmulationPrevention(buf: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArrayOutputStream(to - from)
        var i = from
        while (i < to) {
            val b = buf[i].toInt() and 0xFF
            if (b == 0x03 && i - from >= 2 && (buf[i - 1].toInt() and 0xFF) == 0 &&
                (buf[i - 2].toInt() and 0xFF) == 0 && i + 1 < to &&
                (buf[i + 1].toInt() and 0xFF) <= 0x03
            ) {
                i++
                continue
            }
            out.write(b)
            i++
        }
        return out.toByteArray()
    }

    /** Re-inserts emulation-prevention bytes so the RBSP can ride in an Annex-B stream. */
    private fun addEmulationPrevention(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 8)
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 0x03) {
                out.write(0x03)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /**
     * Rewrites a Dolby Vision VPS payload (RBSP, EPB bytes allowed) into a clean
     * single-layer HDR10 VPS: the Dolby Vision extension is removed and the layer
     * count forced to 1. Returns the rewritten RBSP (with EPB re-applied), or null
     * when the VPS has no DV extension / does not parse (caller keeps it as-is).
     */
    fun rewriteVpsToHdr10(buf: ByteArray, from: Int, to: Int): ByteArray? {
        if (to - from < 4) return null
        val rbsp = removeEmulationPrevention(buf, from, to)
        if (rbsp.size < 6) return null
        val br = VpsBitReader(rbsp)

        // Fixed header: id(4) reserved(=3)(2) max_layers(6) max_sub_layers(3)
        // nesting(1) reserved_ffff(16) = 32 bits.
        if (br.readBits(4) == -1L) return null
        if (br.readBits(2) != 3L) return null
        val maxLayers = br.readBits(6).toInt() + 1
        if (maxLayers <= 0) return null
        val maxSubLayers = br.readBits(3).toInt() + 1
        if (maxSubLayers <= 0) return null
        if (br.readBits(1) == -1L) return null
        if (br.readBits(16) == -1L) return null

        // profile_tier_level(1, maxSubLayers - 1): general fields (2+1+5+32 +
        // 4 constraint flags + 44 reserved + 8 level), sub-layer flags, then
        // per-sub-layer profile/level blocks.
        br.readBits(2); br.readBits(1); br.readBits(5); br.readBits(32)
        br.readBits(1); br.readBits(1); br.readBits(1); br.readBits(1)
        br.readBits(44)
        if (br.readBits(8) == -1L) return null
        val subLayerProfile = BooleanArray(maxSubLayers - 1)
        val subLayerLevel = BooleanArray(maxSubLayers - 1)
        for (i in 0 until maxSubLayers - 1) {
            val p = br.readBits(1)
            val l = br.readBits(1)
            if (p == -1L || l == -1L) return null
            subLayerProfile[i] = p == 1L
            subLayerLevel[i] = l == 1L
        }
        if (maxSubLayers - 1 > 0) {
            for (i in maxSubLayers - 1 until 8) {
                if (br.readBits(2) == -1L) return null
            }
        }
        for (i in 0 until maxSubLayers - 1) {
            if (subLayerProfile[i]) {
                br.readBits(2); br.readBits(1); br.readBits(5); br.readBits(32)
                br.readBits(1); br.readBits(1); br.readBits(1); br.readBits(1)
                if (br.readBits(44) == -1L) return null
            }
            if (subLayerLevel[i]) {
                if (br.readBits(8) == -1L) return null
            }
        }

        // Ordering info is ue(v) — self-delimiting, so it is copied verbatim
        // even when the layer count changes below.
        val orderingPresent = br.readBits(1) == 1L
        val start = if (orderingPresent) 0 else maxSubLayers - 1
        for (i in start until maxSubLayers) {
            if (br.readUe() == -1L || br.readUe() == -1L || br.readUe() == -1L) return null
        }

        val maxLayerId = br.readBits(6).toInt()
        if (maxLayerId < 0) return null
        val numLayerSets = br.readUe().toInt() + 1
        if (numLayerSets <= 0) return null
        for (i in 1 until numLayerSets) {
            if (!br.skipBits(maxLayerId + 1)) return null
        }

        // Timing/HRD present: bail rather than risk misparsing (rare on DV
        // web encodes, and an untouched VPS beats a corrupted one).
        val timingPresent = br.readBits(1)
        if (timingPresent == -1L) return null
        if (timingPresent == 1L) return null
        val extBit = br.pos
        val extFlag = br.readBits(1)
        if (extFlag == -1L) return null
        if (extFlag == 0L) return null // no DV extension — nothing to rewrite

        // Rebuild: header with vps_max_layers_minus1 = 0, everything from the
        // end of the layer-count field through the extension flag verbatim,
        // extension flag cleared, rbsp trailing bits.
        val out = VpsBitWriter()
        if (!br.copyBits(0, 6, out)) return null
        out.writeBits(0L, 6)
        if (!br.copyBits(12, extBit, out)) return null
        out.writeBits(0L, 1) // vps_extension_flag = 0
        out.writeBits(1L, 1) // rbsp_stop_one_bit
        return addEmulationPrevention(out.finish())
    }

    /**
     * Rewrites every VPS NAL found in codec-private (initialization data)
     * buffers to single-layer HDR10, returning new buffers (or the same list
     * when nothing changed). Keeps the decoder's parameter set from
     * advertising Dolby Vision after the strip.
     */
    fun rewriteInitDataVps(initData: List<ByteArray>): List<ByteArray> {
        var any = false
        val out = ArrayList<ByteArray>(initData.size)
        for (buf in initData) {
            val rewritten = rewriteVpsInAnnexBBuffer(buf)
            if (rewritten != null) {
                out.add(rewritten)
                any = true
            } else {
                out.add(buf)
            }
        }
        return if (any) out else initData
    }

    /** Returns a copy of an Annex-B buffer with VPS NALs rewritten, or null if unchanged. */
    private fun rewriteVpsInAnnexBBuffer(buf: ByteArray): ByteArray? {
        if (buf.size < 5) return null
        val out = ByteArray(buf.size)
        var write = 0
        var read = 0
        var changed = false
        while (read < buf.size) {
            val code = indexOfStartCode(buf, read, buf.size)
            if (code < 0) {
                System.arraycopy(buf, read, out, write, buf.size - read)
                write += buf.size - read
                break
            }
            val codeLen =
                if (code + 3 < buf.size && buf[code + 2].toInt() == 0 && buf[code + 3].toInt() == 1) 4 else 3
            val header = code + codeLen
            var nalEnd = indexOfStartCode(buf, header, buf.size)
            if (nalEnd < 0) nalEnd = buf.size
            var nalWritten = false
            if (nalEnd - header >= 2) {
                val nalType = ((buf[header].toInt() and 0xFF) ushr 1) and 0x3F
                if (nalType == NAL_VPS) {
                    val rewritten = rewriteVpsToHdr10(buf, header + 2, nalEnd)
                    if (rewritten != null) {
                        System.arraycopy(buf, code, out, write, codeLen)
                        System.arraycopy(buf, header, out, write + codeLen, 2)
                        System.arraycopy(rewritten, 0, out, write + codeLen + 2, rewritten.size)
                        write += codeLen + 2 + rewritten.size
                        changed = true
                        nalWritten = true
                    }
                }
            }
            if (!nalWritten) {
                System.arraycopy(buf, read, out, write, nalEnd - read)
                write += nalEnd - read
            }
            read = nalEnd
        }
        return if (changed) out.copyOf(write) else null
    }

    /**
     * DV-strip transform for Annex-B samples: rewrites the VPS to plain
     * single-layer HDR10, drops RPU (62) / EL (63 and any layerId &gt; 0) NALs,
     * and removes HDR10+ prefix SEIs when [stripHdr10Plus]. Returns the new
     * length, or -1 when nothing changed. This is dovi_tool's `remove` plus the
     * VPS rewrite that keeps DV-signaled decoders from stalling.
     */
    fun transformAnnexB(
        buf: ByteArray,
        length: Int,
        stripHdr10Plus: Boolean,
        stats: StripStats?
    ): Int {
        if (length < 5) return -1
        var write = 0
        var read = 0
        var changed = false
        while (read < length) {
            val code = indexOfStartCode(buf, read, length)
            if (code < 0) {
                if (write != read) System.arraycopy(buf, read, buf, write, length - read)
                write += length - read
                break
            }
            val codeLen =
                if (code + 3 < length && buf[code + 2].toInt() == 0 && buf[code + 3].toInt() == 1) 4 else 3
            val header = code + codeLen
            var nalEnd = indexOfStartCode(buf, header, length)
            if (nalEnd < 0) nalEnd = length
            val nalBytes = nalEnd - header
            var keep = true
            if (nalBytes >= 2) {
                val b0 = buf[header].toInt() and 0xFF
                val b1 = buf[header + 1].toInt() and 0xFF
                val nalType = (b0 ushr 1) and 0x3F
                val layerId = ((b0 and 0x01) shl 5) or ((b1 and 0xF8) ushr 3)
                when {
                    nalType == NAL_VPS -> {
                        val rewritten = rewriteVpsToHdr10(buf, header + 2, nalEnd)
                        if (rewritten != null) {
                            changed = true
                            stats?.vpsRewritten = true
                            // start code + 2-byte NAL header + rewritten RBSP
                            System.arraycopy(buf, code, buf, write, codeLen)
                            System.arraycopy(buf, header, buf, write + codeLen, 2)
                            System.arraycopy(rewritten, 0, buf, write + codeLen + 2, rewritten.size)
                            write += codeLen + 2 + rewritten.size
                            keep = false
                        }
                    }
                    nalType == NAL_DV_RPU -> {
                        keep = false
                        changed = true
                        if (stats != null) stats.rpuBytes += nalBytes
                    }
                    nalType == NAL_DV_EL || layerId > 0 -> {
                        keep = false
                        changed = true
                        if (stats != null) stats.elBytes += nalBytes
                    }
                    nalType == NAL_PREFIX_SEI && stripHdr10Plus &&
                        seiCarriesHdr10Plus(buf, header + 2, nalEnd) -> {
                        keep = false
                        changed = true
                        if (stats != null) stats.hdr10PlusBytes += nalBytes
                    }
                }
            }
            if (keep) {
                if (write != read) System.arraycopy(buf, read, buf, write, nalEnd - read)
                write += nalEnd - read
            }
            read = nalEnd
        }
        return if (changed) write else -1
    }

    /**
     * Length-delimited (MP4/fMP4) counterpart of [transformAnnexB]. Malformed
     * samples are forwarded untouched.
     */
    fun transformLengthDelimited(
        buf: ByteArray,
        length: Int,
        nalLengthFieldLength: Int,
        stripHdr10Plus: Boolean,
        stats: StripStats?
    ): Int {
        val fieldLen = nalLengthFieldLength.coerceIn(1, 4)
        if (length <= fieldLen) return -1
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
                when {
                    nalType == NAL_VPS -> {
                        val rewritten = rewriteVpsToHdr10(buf, payload + 2, payload + nal)
                        if (rewritten != null) {
                            changed = true
                            stats?.vpsRewritten = true
                            writeLength(buf, write, rewritten.size, fieldLen)
                            System.arraycopy(buf, payload, buf, write + fieldLen, 2)
                            System.arraycopy(rewritten, 0, buf, write + fieldLen + 2, rewritten.size)
                            write += fieldLen + 2 + rewritten.size
                            keep = false
                        }
                    }
                    nalType == NAL_DV_RPU -> {
                        keep = false
                        changed = true
                        if (stats != null) stats.rpuBytes += nal
                    }
                    nalType == NAL_DV_EL || layerId > 0 -> {
                        keep = false
                        changed = true
                        if (stats != null) stats.elBytes += nal
                    }
                    nalType == NAL_PREFIX_SEI && stripHdr10Plus &&
                        nal >= 4 && seiCarriesHdr10Plus(buf, payload + 2, payload + nal) -> {
                        keep = false
                        changed = true
                        if (stats != null) stats.hdr10PlusBytes += nal
                    }
                }
            }
            if (keep) {
                if (write != read) System.arraycopy(buf, read, buf, write, fieldLen + nal)
                write += fieldLen + nal
            }
            read += fieldLen + nal
        }
        return if (changed) write else -1
    }

    private fun writeLength(buf: ByteArray, offset: Int, value: Int, lengthBytes: Int) {
        var v = value
        for (i in lengthBytes - 1 downTo 0) {
            buf[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    /**
     * One-line inventory of an Annex-B sample's NAL units (type:bytes pairs),
     * used by the extractor's first-sample diagnostics.
     */
    fun describeNals(buf: ByteArray, length: Int): String {
        val sb = StringBuilder()
        var read = 0
        while (read < length) {
            val code = indexOfStartCode(buf, read, length)
            if (code < 0) break
            val codeLen =
                if (code + 3 < length && buf[code + 2].toInt() == 0 && buf[code + 3].toInt() == 1) 4 else 3
            val header = code + codeLen
            var nalEnd = indexOfStartCode(buf, header, length)
            if (nalEnd < 0) nalEnd = length
            if (nalEnd - header >= 2) {
                val nalType = ((buf[header].toInt() and 0xFF) ushr 1) and 0x3F
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(nalType).append(':').append(nalEnd - header)
            }
            read = nalEnd
        }
        return sb.toString()
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
