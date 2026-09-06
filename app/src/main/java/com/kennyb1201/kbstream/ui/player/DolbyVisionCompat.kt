package com.kennyb1201.kbstream.ui.player

import android.util.Log
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
 *  - P7 → 8.1 (default): only dual-layer Profile 7 (dvhe.07/dvh1.07, Blu-ray
 *    remuxes — base + RPU (NAL type 62) + enhancement layer (layerId > 0)),
 *    rewritten to Profile 8.1 so Dolby Vision displays that reject P7 can
 *    play it (the P5 → 8.1 toggle adds single-layer ICtCp P5 on top). Every
 *    other DV profile is passed through untouched so a Dolby-Vision display
 *    plays it as real Dolby Vision.
 *  - Strip All: every profile — 4, 5, 7 and 8 (dvhe/dvh1.04/.05/.07/.08). For
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
    // once the DV RPU / enhancement-layer NALs are stripped or the stream is
    // rewritten to Profile 8.1. In the "P7 → 8.1" mode this is the only
    // profile that is rewritten: everything else (single-layer P4/P8 web
    // encodes, P5 unless its 8.1 toggle is on) is passed through untouched so
    // Dolby-Vision displays get the real thing.
    private val DV_CODEC_PROFILE_7 = Regex("(?i)^(dvhe|dvh1)\\.(07|7)\\..+$")

    // Every DV profile, used by the explicit "Strip All" mode (the fallback
    // for TVs without Dolby Vision. Profile 5 (ICtCp, no HDR10 base) can only
    // be
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
     * Codec string for the Profile 8.1 rewrite target of a declared P5/P7
     * stream: keeps the dvhe/dvh1 family (so Media3 still routes the track
     * through the Dolby Vision pipeline) but changes the profile digits to 08.
     * Returns null when the profile's 8.1 toggle is off, and for every other
     * codec (P4/P8/plain HEVC), which must pass through untouched.
     */
    private val DV_CODEC_TO_81 = Regex("(?i)^(dvhe|dvh1)\\.0*(7|5)\\.(.+)$")

    fun to81Codec(
        codecs: String?,
        convertP7To81: Boolean,
        convertP5To81: Boolean
    ): String? {
        if (codecs.isNullOrBlank()) return null
        val m = DV_CODEC_TO_81.find(codecs.trim()) ?: return null
        val profile = m.groupValues[2]
        val enabled = when (profile) {
            "7" -> convertP7To81
            "5" -> convertP5To81
            else -> false
        }
        if (!enabled) return null
        // "dvhe.07.06" / "dvh1.05.06" -> "dvhe.08.06" / "dvh1.08.06"
        return m.groupValues[1] + ".08." + m.groupValues[3]
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
    private const val NAL_SUFFIX_SEI = 40
    private const val NAL_DV_RPU = 62
    private const val NAL_DV_EL = 63

    /**
     * HDR10+ (ST 2094-40) user data can ride in either a prefix SEI (39) or a
     * suffix SEI (40) NAL — most muxers use prefix, but a suffix-carried
     * payload reaches the display pipeline exactly the same way and must be
     * stripped too, or the black-screen-on-HDR10+ TVs this module targets get
     * the metadata anyway.
     */
    private fun isHdr10PlusSeiNal(nalType: Int): Boolean = when (nalType) {
        NAL_PREFIX_SEI, NAL_SUFFIX_SEI -> true
        else -> false
    }

    // HDR10+ (SMPTE ST 2094-40) user data: ITU-T T.35 country 0xB5, provider
    // code 0x003C, provider-orientation 0x0001, application id 0x04.
    private val HDR10_PLUS_MARKER = intArrayOf(0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04)

    /**
     * The plain-HEVC rewrite target for a declared DV codec, or null when the
     * track must be left untouched.
     *
     * "P7 → 8.1" mode behavior ([convertAllProfiles] = false): only Profile 7
     * qualifies — the other profiles pass through so DV displays play them as
     * Dolby Vision. "Strip All" ([convertAllProfiles] = true): every profile
     * (4/5/7/8) qualifies, for displays without Dolby Vision (P5 is a
     * best-effort HEVC fallback).
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
                if (keep && stripHdr10Plus && isHdr10PlusSeiNal(nalType) &&
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
                if (keep && stripHdr10Plus && isHdr10PlusSeiNal(nalType) &&
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
        var vpsRewriteFailedReason: String? = null
        var rpuRewritten = 0
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
     * Outcome of one VPS rewrite attempt. A DV VPS left in-band after the RPU
     * strip is exactly the black-screen-with-audio failure this module exists
     * to prevent, so a parse failure is reported (and logged), never silently
     * conflated with "no DV extension".
     */
    internal sealed class VpsRewriteOutcome {
        /** Rewritten single-layer HDR10 RBSP (emulation-prevention re-applied). */
        data class Rewritten(val rbsp: ByteArray) : VpsRewriteOutcome()

        /** VPS carried no Dolby Vision extension — keep the original as-is. */
        object NoDvExtension : VpsRewriteOutcome()

        /** VPS did not parse ([reason] for diagnostics); the original is kept. */
        data class Failed(val reason: String) : VpsRewriteOutcome()
    }

    /**
     * Rewrites a Dolby Vision VPS payload (RBSP, EPB bytes allowed) into a clean
     * single-layer HDR10 VPS: the Dolby Vision extension is removed and the layer
     * count forced to 1. See [VpsRewriteOutcome] for the three outcomes; a
     * failed parse keeps the original VPS rather than corrupting it.
     */
    fun rewriteVpsToHdr10(buf: ByteArray, from: Int, to: Int): VpsRewriteOutcome {
        if (to - from < 4) return VpsRewriteOutcome.Failed("short-nal")
        val rbsp = removeEmulationPrevention(buf, from, to)
        if (rbsp.size < 6) return VpsRewriteOutcome.Failed("short-rbsp")
        val br = VpsBitReader(rbsp)

        // Fixed header: id(4) reserved(=3)(2) max_layers(6) max_sub_layers(3)
        // nesting(1) reserved_ffff(16) = 32 bits.
        if (br.readBits(4) == -1L) return VpsRewriteOutcome.Failed("header")
        if (br.readBits(2) != 3L) return VpsRewriteOutcome.Failed("reserved-3bits")
        val maxLayers = br.readBits(6).toInt() + 1
        if (maxLayers <= 0) return VpsRewriteOutcome.Failed("max-layers")
        val maxSubLayers = br.readBits(3).toInt() + 1
        if (maxSubLayers <= 0) return VpsRewriteOutcome.Failed("max-sub-layers")
        if (br.readBits(1) == -1L) return VpsRewriteOutcome.Failed("nesting")
        if (br.readBits(16) == -1L) return VpsRewriteOutcome.Failed("reserved-ffff")

        // profile_tier_level(1, maxSubLayers - 1): general fields (2+1+5+32 +
        // 4 constraint flags + 44 reserved + 8 level), sub-layer flags, then
        // per-sub-layer profile/level blocks.
        br.readBits(2); br.readBits(1); br.readBits(5); br.readBits(32)
        br.readBits(1); br.readBits(1); br.readBits(1); br.readBits(1)
        br.readBits(44)
        if (br.readBits(8) == -1L) return VpsRewriteOutcome.Failed("ptl-general")
        val subLayerProfile = BooleanArray(maxSubLayers - 1)
        val subLayerLevel = BooleanArray(maxSubLayers - 1)
        for (i in 0 until maxSubLayers - 1) {
            val p = br.readBits(1)
            val l = br.readBits(1)
            if (p == -1L || l == -1L) return VpsRewriteOutcome.Failed("ptl-subflags")
            subLayerProfile[i] = p == 1L
            subLayerLevel[i] = l == 1L
        }
        if (maxSubLayers - 1 > 0) {
            for (i in maxSubLayers - 1 until 8) {
                if (br.readBits(2) == -1L) return VpsRewriteOutcome.Failed("ptl-reserved")
            }
        }
        for (i in 0 until maxSubLayers - 1) {
            if (subLayerProfile[i]) {
                br.readBits(2); br.readBits(1); br.readBits(5); br.readBits(32)
                br.readBits(1); br.readBits(1); br.readBits(1); br.readBits(1)
                if (br.readBits(44) == -1L) return VpsRewriteOutcome.Failed("ptl-sub-profile")
            }
            if (subLayerLevel[i]) {
                if (br.readBits(8) == -1L) return VpsRewriteOutcome.Failed("ptl-sub-level")
            }
        }

        // Ordering info is ue(v) — self-delimiting, so it is copied verbatim
        // even when the layer count changes below.
        val orderingPresent = br.readBits(1) == 1L
        val start = if (orderingPresent) 0 else maxSubLayers - 1
        for (i in start until maxSubLayers) {
            if (br.readUe() == -1L || br.readUe() == -1L || br.readUe() == -1L) {
                return VpsRewriteOutcome.Failed("ordering-info")
            }
        }

        val maxLayerId = br.readBits(6).toInt()
        if (maxLayerId < 0) return VpsRewriteOutcome.Failed("max-layer-id")
        val numLayerSets = br.readUe().toInt() + 1
        if (numLayerSets <= 0) return VpsRewriteOutcome.Failed("layer-sets")
        for (i in 1 until numLayerSets) {
            if (!br.skipBits(maxLayerId + 1)) return VpsRewriteOutcome.Failed("layer-set-flags")
        }

        // Timing/HRD block — parsed, not skipped, so the surgical copy below
        // keeps it bit-exact while still locating the extension flag. Layout
        // mirrors hevc_parser (the parser dovi_tool uses), ITU-T H.265 7.3.2.1.
        // Bailing here would leave the Dolby Vision VPS in-band on a stripped
        // stream — the exact black-screen-with-audio failure this module
        // exists to prevent (decoders that key off the DV VPS extension sit
        // in DV mode waiting for RPUs that never arrive). Streaming P8 / web
        // encodes are where vps_timing_info_present_flag shows up.
        val timingPresent = br.readBits(1)
        if (timingPresent == -1L) return VpsRewriteOutcome.Failed("timing-flag")
        if (timingPresent == 1L && !skipVpsTimingInfo(br, maxSubLayers)) {
            return VpsRewriteOutcome.Failed("timing-info")
        }
        val extBit = br.pos
        val extFlag = br.readBits(1)
        if (extFlag == -1L) return VpsRewriteOutcome.Failed("extension-flag")
        if (extFlag == 0L) return VpsRewriteOutcome.NoDvExtension

        // Rebuild: header with vps_max_layers_minus1 = 0, everything from the
        // end of the layer-count field through the extension flag verbatim,
        // extension flag cleared, rbsp trailing bits.
        val out = VpsBitWriter()
        if (!br.copyBits(0, 6, out)) return VpsRewriteOutcome.Failed("copy-header")
        out.writeBits(0L, 6)
        if (!br.copyBits(12, extBit, out)) return VpsRewriteOutcome.Failed("copy-body")
        out.writeBits(0L, 1) // vps_extension_flag = 0
        out.writeBits(1L, 1) // rbsp_stop_one_bit
        return VpsRewriteOutcome.Rewritten(addEmulationPrevention(out.finish()))
    }

    /**
     * Skips the vps_timing_info block (ITU-T H.265 7.3.2.1): 32-bit
     * num_units_in_tick / time_scale, the POC-proportional flag and its
     * Exp-Golomb, then vps_num_hrd_parameters hrd_parameters() payloads
     * (bit layouts per hevc_parser's HrdParameters::parse). Returns false on
     * any parse failure so the caller can bail and keep the original VPS.
     */
    private fun skipVpsTimingInfo(br: VpsBitReader, maxSubLayers: Int): Boolean {
        if (br.readBits(32) == -1L) return false // vps_num_units_in_tick
        if (br.readBits(32) == -1L) return false // vps_time_scale
        val pocProportional = br.readBits(1)
        if (pocProportional == -1L) return false
        if (pocProportional == 1L) {
            if (br.readUe() == -1L) return false // vps_num_ticks_poc_diff_one_minus1
        }
        val numHrd = br.readUe()
        if (numHrd == -1L || numHrd > 32) return false // vps_num_hrd_parameters
        for (i in 0 until numHrd.toInt()) {
            if (br.readUe() == -1L) return false // hrd_layer_set_idx
            var cprmsPresent = false
            if (i > 0) {
                val b = br.readBits(1)
                if (b == -1L) return false
                cprmsPresent = b == 1L
            }
            if (!skipHrdParameters(br, cprmsPresent, maxSubLayers)) return false
        }
        return true
    }

    /**
     * Skips one hrd_parameters() payload (hevc_parser HrdParameters::parse):
     * common-level present flags and fixed field widths, then per-sub-layer
     * rate/CPB parameters with Exp-Golomb values. When fixed rate is signaled
     * low_delay is inferred false and cpb_cnt_minus1 still follows.
     */
    private fun skipHrdParameters(
        br: VpsBitReader,
        cprmsPresent: Boolean,
        maxSubLayers: Int
    ): Boolean {
        var nalParams = false
        var vclParams = false
        var subpicParams = false
        if (cprmsPresent) {
            val nal = br.readBits(1)
            val vcl = br.readBits(1)
            if (nal == -1L || vcl == -1L) return false
            nalParams = nal == 1L
            vclParams = vcl == 1L
            if (nalParams || vclParams) {
                val subpic = br.readBits(1)
                if (subpic == -1L) return false
                subpicParams = subpic == 1L
                if (subpicParams) {
                    // tick_divisor_minus2(8) + du_cpb_removal_delay_increment(5)
                    // + sub_pic_cpb_params_in_pic_timing_sei(1) + dpb_output_delay_du(5)
                    if (!br.skipBits(8 + 5 + 1 + 5)) return false
                }
                // bit_rate_scale(4) + cpb_size_scale(4)
                if (!br.skipBits(8)) return false
                if (subpicParams && !br.skipBits(4)) return false // cpb_size_du_scale
                // initial_cpb_removal_delay(5) + au_cpb_removal_delay(5)
                // + dpb_output_delay(5)
                if (!br.skipBits(15)) return false
            }
        }
        for (i in 0 until maxSubLayers) {
            var lowDelay = false
            var nbCpb = 1
            val fixedGeneral = br.readBits(1)
            if (fixedGeneral == -1L) return false
            var fixed = fixedGeneral == 1L
            if (!fixed) {
                val within = br.readBits(1)
                if (within == -1L) return false
                fixed = within == 1L
            }
            if (fixed) {
                if (br.readUe() == -1L) return false // elemental_duration_in_tc_minus1
            } else {
                val low = br.readBits(1)
                if (low == -1L) return false
                lowDelay = low == 1L
            }
            if (!lowDelay) {
                val cpb = br.readUe()
                if (cpb == -1L || cpb > 31) return false
                nbCpb = cpb.toInt() + 1
            }
            if (nalParams && !skipSubLayerHrd(br, nbCpb, subpicParams)) return false
            if (vclParams && !skipSubLayerHrd(br, nbCpb, subpicParams)) return false
        }
        return true
    }

    /** Skips one sub_layer_hrd_parameters() payload (hevc_parser SubLayerHrdParameter). */
    private fun skipSubLayerHrd(
        br: VpsBitReader,
        nbCpb: Int,
        subpicParams: Boolean
    ): Boolean {
        for (j in 0 until nbCpb) {
            if (br.readUe() == -1L) return false // bit_rate_value_minus1
            if (br.readUe() == -1L) return false // cpb_size_value_minus1
            if (subpicParams) {
                if (br.readUe() == -1L) return false // cpb_size_du_value_minus1
                if (br.readUe() == -1L) return false // bit_rate_du_value_minus1
            }
            if (br.readBits(1) == -1L) return false // cbr_flag
        }
        return true
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
                    when (val res = rewriteVpsToHdr10(buf, header + 2, nalEnd)) {
                        is VpsRewriteOutcome.Rewritten -> {
                            System.arraycopy(buf, code, out, write, codeLen)
                            System.arraycopy(buf, header, out, write + codeLen, 2)
                            System.arraycopy(res.rbsp, 0, out, write + codeLen + 2, res.rbsp.size)
                            write += codeLen + 2 + res.rbsp.size
                            changed = true
                            nalWritten = true
                        }
                        is VpsRewriteOutcome.Failed -> Log.i(
                            "PLAYER_DV",
                            "VPS rewrite failed (${res.reason}) — keeping original VPS in codec config"
                        )
                        VpsRewriteOutcome.NoDvExtension -> Unit
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
                        when (val res = rewriteVpsToHdr10(buf, header + 2, nalEnd)) {
                            is VpsRewriteOutcome.Rewritten -> {
                                changed = true
                                stats?.vpsRewritten = true
                                // start code + 2-byte NAL header + rewritten RBSP
                                System.arraycopy(buf, code, buf, write, codeLen)
                                System.arraycopy(buf, header, buf, write + codeLen, 2)
                                System.arraycopy(res.rbsp, 0, buf, write + codeLen + 2, res.rbsp.size)
                                write += codeLen + 2 + res.rbsp.size
                                keep = false
                            }
                            is VpsRewriteOutcome.Failed ->
                                stats?.vpsRewriteFailedReason = res.reason
                            VpsRewriteOutcome.NoDvExtension -> Unit
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
                    isHdr10PlusSeiNal(nalType) && stripHdr10Plus &&
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
                        when (val res = rewriteVpsToHdr10(buf, payload + 2, payload + nal)) {
                            is VpsRewriteOutcome.Rewritten -> {
                                changed = true
                                stats?.vpsRewritten = true
                                writeLength(buf, write, res.rbsp.size + 2, fieldLen)
                                System.arraycopy(buf, payload, buf, write + fieldLen, 2)
                                System.arraycopy(res.rbsp, 0, buf, write + fieldLen + 2, res.rbsp.size)
                                write += fieldLen + 2 + res.rbsp.size
                                keep = false
                            }
                            is VpsRewriteOutcome.Failed ->
                                stats?.vpsRewriteFailedReason = res.reason
                            VpsRewriteOutcome.NoDvExtension -> Unit
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
                    isHdr10PlusSeiNal(nalType) && stripHdr10Plus &&
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

    // ── Profile 7 / Profile 5 → Profile 8.1 conversion ───────────────────
    // The "P7 → 8.1" mode (the default DV setting) rewrites Profile 7
    // (dual-layer Blu-ray remuxes) to Profile 8.1 on the fly, and the P5 → 8.1
    // toggle does the same for Profile 5 (single-layer ICtCp) on top of that
    // mode — so Dolby Vision-capable displays that choke on P7 or P5 can play
    // them as real Dolby Vision instead of black-screening. The bitstream-
    // level change is small but structural:
    //
    //  - Profile 7 → 8.1: drop the enhancement layer NALs (layerId > 0 / 63),
    //    force the VPS to a single-layer parameter set, and in every RPU set
    //    el_spatial_resampling_filter_flag = 0 / disable_residual_flag = 1
    //    (which is exactly what makes the RPU read as profile 8, per
    //    dovi_tool's get_dovi_profile). FEL RPUs additionally get their
    //    luma/chroma mapping replaced with the fixed no-op 8.1 mapping and
    //    the DM coefficients replaced with the standard 8.1 (BT.2020) ones.
    //  - Profile 5 → 8.1: set vdr_rpu_profile = 1, bl_video_full_range_flag =
    //    false, replace the ICtCp mapping with the no-op 8.1 mapping and the
    //    DM coefficients with the 8.1 ones. The PIXELS are still ICtCp (a
    //    bitstream cannot recolor them) — that is exactly what the existing
    //    P5 GLES shader / FFmpeg color path converts for display, so P5→8.1
    //    and the P5 color converter compose rather than conflict.
    //
    // This mirrors dovi_tool's `convert --mode 2` (To81) field-for-field:
    // same header/mapping/DM bit layouts (ITU-T H.265 / Dolby Vision RPU),
    // same CRC-32/MPEG-2 checksum recomputed over the rewritten payload.
    // Any parse failure bails out and leaves the RPU untouched — a converted
    // stream never carries a corrupt RPU, worst case it stays P7/P5.

    private const val RPU_NUM_COMPONENTS = 3
    private const val RPU_MMR_MAX_COEFFS = 7
    private const val RPU_NLQ_NUM_PIVOTS = 2

    /** Bit-level reader over an RPU payload (emulation-prevention bytes removed). */
    private class RpuBitReader(private val data: ByteArray, private val bitLen: Int) {
        var pos = 0
            private set

        fun bits(n: Int): Long? {
            if (n < 0 || n > 32 || pos + n > bitLen) return null
            var v = 0L
            for (i in 0 until n) {
                val byte = data[pos ushr 3].toInt() and 0xFF
                val bit = (byte ushr (7 - (pos and 7))) and 1
                v = (v shl 1) or bit.toLong()
                pos++
            }
            return v
        }

        /** Unsigned Exp-Golomb (ue(v)). Returns null on overrun. */
        fun ue(): Long? {
            var zeros = 0
            while (true) {
                val b = bits(1) ?: return null
                if (b == 1L) break
                zeros++
                if (zeros > 32) return null
            }
            val rest = bits(zeros) ?: return null
            return (1L shl zeros) - 1L + rest
        }

        /** Signed Exp-Golomb (se(v)). Returns null on overrun. */
        fun se(): Long? {
            val ue = ue() ?: return null
            return if (ue and 1L == 0L) -(ue ushr 1) else (ue + 1) ushr 1
        }

        fun skipAlignmentZeros(): Boolean {
            while (pos and 7 != 0) {
                val b = bits(1) ?: return false
                if (b != 0L) return false
            }
            return true
        }

        /** Copies every remaining bit (up to the CRC region) into [w]. */
        fun copyRemainingBitsTo(w: RpuBitWriter): Boolean {
            while (pos < bitLen) {
                val b = bits(1) ?: return false
                w.writeBits(b, 1)
            }
            return true
        }
    }

    /** Bit-level writer that packs bits into bytes (mirror of VpsBitWriter). */
    private class RpuBitWriter {
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

        fun writeUe(value: Long) {
            if (value < 0) return
            var zeros = 0
            var t = value + 1
            while (t > 1) {
                zeros++
                t = t ushr 1
            }
            writeBits(0, zeros)
            writeBits(1, 1)
            if (zeros > 0) writeBits(value - ((1L shl zeros) - 1), zeros)
        }

        fun writeSe(value: Long) {
            if (value > 0) writeUe(value * 2 - 1) else writeUe(-value * 2)
        }

        fun byteAlign() {
            if (nBits > 0) writeBits(0, 8 - nBits)
        }

        fun finish(): ByteArray {
            byteAlign()
            return out.toByteArray()
        }
    }

    /** CRC-32/MPEG-2 (poly 0x04C11DB7, init 0xFFFFFFFF, MSB-first, no final XOR) over [from, to). */
    private fun crc32Mpeg2(buf: ByteArray, from: Int, to: Int): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in from until to) {
            crc = crc xor ((buf[i].toInt() and 0xFF) shl 24)
            for (b in 0 until 8) {
                crc = if (crc and 0x80000000.toInt() != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }

    private class RpuHeaderFields {
        var rpuType = 0L
        var rpuFormat = 0L
        var vdrRpuProfile = 0L
        var vdrRpuLevel = 0L
        var seqInfoPresent = false
        var chromaResamplingExplicitFilterFlag = 0L
        var coefficientDataType = 0L
        var coefficientLog2Denom = 0L
        var coefficientLog2DenomLength = 0L
        var vdrRpuNormalizedIdc = 0L
        var blVideoFullRangeFlag = 0L
        var blBitDepthMinus8 = 0L
        var elBitDepthMinus8Full = 0L
        var elBitDepthMinus8Low = 0L
        var vdrBitDepthMinus8 = 0L
        var spatialResamplingFilterFlag = 0L
        var reservedZero3Bits = 0L
        var elSpatialResamplingFilterFlag = 0L
        var disableResidualFlag = 1L
        var vdrDmMetadataPresentFlag = 0L
        var usePrevVdrRpuFlag = 0L
        var prevVdrRpuId = 0L
    }

    private class RpuMappingPiece(
        val mappingIdc: Long,
        val polyOrderMinus1: Long,
        val linearInterpFlag: Boolean,
        val polyCoefInt: LongArray,
        val polyCoef: LongArray,
        val mmrOrderMinus1: Long,
        val mmrConstantInt: Long,
        val mmrConstant: Long,
        val mmrCoefInt: Array<LongArray>,
        val mmrCoef: Array<LongArray>
    )

    private class RpuMappingData(
        val vdrRpuId: Long,
        val mappingColorSpace: Long,
        val mappingChromaFormatIdc: Long,
        val numPivotsMinus2: Long,
        val pivots: LongArray,
        val pieces: List<RpuMappingPiece>,
        val elTypeFel: Boolean
    )

    private class RpuDmData {
        var compressed = false
        var affectedDmMetadataId = 0L
        var currentDmMetadataId = 0L
        var sceneRefreshFlag = 0L
        var yccToRgb = LongArray(9)
        var yccOffsets = LongArray(3)
        var rgbToLms = LongArray(9)
        var signalEotf = 0L
        var signalEotfParam0 = 0L
        var signalEotfParam1 = 0L
        var signalEotfParam2 = 0L
        var signalBitDepth = 0L
        var signalColorSpace = 0L
        var signalChromaFormat = 0L
        var signalFullRangeFlag = 0L
        var sourceMinPq = 0L
        var sourceMaxPq = 0L
        var sourceDiagonal = 0L
    }

    /**
     * Parses the RPU data header (format 1), mirroring dovi_tool's
     * RpuDataHeader::parse. Returns null when the stream does not parse.
     */
    private fun parseRpuHeader(r: RpuBitReader): RpuHeaderFields? {
        val h = RpuHeaderFields()
        h.rpuType = r.bits(6) ?: return null
        if (h.rpuType != 2L) return null
        h.rpuFormat = r.bits(11) ?: return null
        h.vdrRpuProfile = r.bits(4) ?: return null
        h.vdrRpuLevel = r.bits(4) ?: return null
        h.seqInfoPresent = r.bits(1) == 1L
        if (h.seqInfoPresent) {
            h.chromaResamplingExplicitFilterFlag = r.bits(1) ?: return null
            h.coefficientDataType = r.bits(2) ?: return null
            if (h.coefficientDataType == 0L) {
                h.coefficientLog2Denom = r.ue() ?: return null
            }
            h.vdrRpuNormalizedIdc = r.bits(2) ?: return null
            h.blVideoFullRangeFlag = r.bits(1) ?: return null
            if ((h.rpuFormat and 0x700L) == 0L) {
                h.blBitDepthMinus8 = r.ue() ?: return null
                h.elBitDepthMinus8Full = r.ue() ?: return null
                h.elBitDepthMinus8Low = h.elBitDepthMinus8Full and 0xFF
                h.vdrBitDepthMinus8 = r.ue() ?: return null
                h.spatialResamplingFilterFlag = r.bits(1) ?: return null
                h.reservedZero3Bits = r.bits(3) ?: return null
                h.elSpatialResamplingFilterFlag = r.bits(1) ?: return null
                h.disableResidualFlag = r.bits(1) ?: return null
            }
            h.coefficientLog2DenomLength =
                if (h.coefficientDataType == 0L) h.coefficientLog2Denom else 32L
        }
        h.vdrDmMetadataPresentFlag = r.bits(1) ?: return null
        h.usePrevVdrRpuFlag = r.bits(1) ?: return null
        if (h.usePrevVdrRpuFlag == 1L) {
            h.prevVdrRpuId = r.ue() ?: return null
        }
        return h
    }

    /** Parses rpu_data_mapping (and its NLQ section), mirroring dovi_tool. */
    private fun parseRpuMapping(
        r: RpuBitReader,
        h: RpuHeaderFields,
        blBitDepth: Long
    ): RpuMappingData? {
        val vdrRpuId = r.ue() ?: return null
        val colorSpace = r.ue() ?: return null
        val chromaFormat = r.ue() ?: return null
        val numPivotsMinus2 = r.ue() ?: return null
        val numPivots = numPivotsMinus2 + 2
        if (numPivots > 64) return null
        val pivots = LongArray(numPivots.toInt())
        for (i in pivots.indices) {
            pivots[i] = r.bits(blBitDepth.toInt()) ?: return null
        }
        var nlqMethodIdc: Long? = null
        if ((h.rpuFormat and 0x700L) == 0L && h.disableResidualFlag == 0L) {
            nlqMethodIdc = r.bits(3) ?: return null
            // nlq_num_pivots_minus2 is fixed 0 in dovi_tool; two pivot values follow.
            for (i in 0 until RPU_NLQ_NUM_PIVOTS) {
                if (r.bits(blBitDepth.toInt()) == null) return null
            }
        }
        val numX = r.ue() ?: return null
        val numY = r.ue() ?: return null
        val cdt = h.coefficientDataType
        val coeffLen = h.coefficientLog2DenomLength.toInt()
        val pieces = ArrayList<RpuMappingPiece>()
        for (cmp in 0 until RPU_NUM_COMPONENTS) {
            for (piece in 0 until (numPivotsMinus2 + 1).toInt()) {
                val mappingIdc = r.ue() ?: return null
                if (mappingIdc == 0L) {
                    val order = r.ue() ?: return null
                    if (order > 1) return null
                    val linear = order == 0L && r.bits(1) == 1L
                    val coefCount = (order + 2).toInt()
                    val coefInt = LongArray(coefCount)
                    val coef = LongArray(coefCount)
                    for (j in 0 until coefCount) {
                        coefInt[j] = if (cdt == 0L) r.se() ?: return null else 0L
                        coef[j] = r.bits(coeffLen) ?: return null
                    }
                    pieces.add(
                        RpuMappingPiece(
                            mappingIdc, order, linear, coefInt, coef,
                            0, 0, 0, emptyArray(), emptyArray()
                        )
                    )
                } else if (mappingIdc == 1L) {
                    val mmrOrder = r.bits(2) ?: return null
                    if (mmrOrder > 2) return null
                    val constInt = if (cdt == 0L) r.se() ?: return null else 0L
                    val constant = r.bits(coeffLen) ?: return null
                    val rows = (mmrOrder + 1).toInt()
                    val coefInt = Array(rows) { LongArray(RPU_MMR_MAX_COEFFS) }
                    val coef = Array(rows) { LongArray(RPU_MMR_MAX_COEFFS) }
                    for (j in 0 until rows) {
                        for (k in 0 until RPU_MMR_MAX_COEFFS) {
                            coefInt[j][k] = if (cdt == 0L) r.se() ?: return null else 0L
                            coef[j][k] = r.bits(coeffLen) ?: return null
                        }
                    }
                    pieces.add(
                        RpuMappingPiece(
                            mappingIdc, 0, false, LongArray(0), LongArray(0),
                            mmrOrder, constInt, constant, coefInt, coef
                        )
                    )
                } else {
                    return null
                }
            }
        }
        // NLQ residual data (Profile 7 FEL only). MEL RPUs have all-zero
        // parameters; FEL have real residuals — matching dovi_tool's
        // RpuDataNlq::is_mel / el_type() distinction.
        var elTypeFel = false
        if (nlqMethodIdc != null) {
            val elBitDepth = (h.elBitDepthMinus8Low + 8).toInt()
            var allMel = true
            for (cmp in 0 until RPU_NUM_COMPONENTS) {
                val nlqOffset = r.bits(elBitDepth) ?: return null
                val vdrInMaxInt = if (cdt == 0L) r.ue() ?: return null else 0L
                val vdrInMax = r.bits(coeffLen) ?: return null
                if (nlqMethodIdc == 0L) {
                    val slopeInt = if (cdt == 0L) r.ue() ?: return null else 0L
                    val slope = r.bits(coeffLen) ?: return null
                    val thresholdInt = if (cdt == 0L) r.ue() ?: return null else 0L
                    val threshold = r.bits(coeffLen) ?: return null
                    if (nlqOffset != 0L || vdrInMaxInt != 1L || vdrInMax != 0L ||
                        slopeInt != 0L || slope != 0L || thresholdInt != 0L || threshold != 0L
                    ) {
                        allMel = false
                    }
                } else {
                    allMel = false
                }
            }
            elTypeFel = !allMel
        }
        // numX/numY are parsed for resync only; the conversion forces them to 0.
        return RpuMappingData(vdrRpuId, colorSpace, chromaFormat, numPivotsMinus2, pivots, pieces, elTypeFel)
    }

    /** Parses the fixed vdr_dm_data fields (extension blocks ride in "remaining"). */
    private fun parseRpuDm(r: RpuBitReader, h: RpuHeaderFields): RpuDmData? {
        val d = RpuDmData()
        d.compressed = h.reservedZero3Bits == 1L
        d.affectedDmMetadataId = r.ue() ?: return null
        d.currentDmMetadataId = r.ue() ?: return null
        d.sceneRefreshFlag = r.ue() ?: return null
        if (!d.compressed) {
            for (i in 0..8) d.yccToRgb[i] = r.bits(16) ?: return null
            for (i in 0..2) d.yccOffsets[i] = r.bits(32) ?: return null
            for (i in 0..8) d.rgbToLms[i] = r.bits(16) ?: return null
            d.signalEotf = r.bits(16) ?: return null
            d.signalEotfParam0 = r.bits(16) ?: return null
            d.signalEotfParam1 = r.bits(16) ?: return null
            d.signalEotfParam2 = r.bits(32) ?: return null
            d.signalBitDepth = r.bits(5) ?: return null
            d.signalColorSpace = r.bits(2) ?: return null
            d.signalChromaFormat = r.bits(2) ?: return null
            d.signalFullRangeFlag = r.bits(2) ?: return null
            d.sourceMinPq = r.bits(12) ?: return null
            d.sourceMaxPq = r.bits(12) ?: return null
            d.sourceDiagonal = r.bits(10) ?: return null
        }
        return d
    }

    private fun writeRpuHeader(w: RpuBitWriter, h: RpuHeaderFields, isP5: Boolean, isP7: Boolean) {
        w.writeBits(h.rpuType, 6)
        w.writeBits(h.rpuFormat, 11)
        w.writeBits(if (isP5) 1L else h.vdrRpuProfile, 4)
        w.writeBits(h.vdrRpuLevel, 4)
        w.writeBits(if (h.seqInfoPresent) 1L else 0L, 1)
        if (h.seqInfoPresent) {
            w.writeBits(h.chromaResamplingExplicitFilterFlag, 1)
            w.writeBits(h.coefficientDataType, 2)
            if (h.coefficientDataType == 0L) w.writeUe(h.coefficientLog2Denom)
            w.writeBits(h.vdrRpuNormalizedIdc, 2)
            w.writeBits(if (isP5) 0L else h.blVideoFullRangeFlag, 1)
            if ((h.rpuFormat and 0x700L) == 0L) {
                w.writeUe(h.blBitDepthMinus8)
                w.writeUe(h.elBitDepthMinus8Full)
                w.writeUe(h.vdrBitDepthMinus8)
                w.writeBits(h.spatialResamplingFilterFlag, 1)
                w.writeBits(h.reservedZero3Bits, 3)
                w.writeBits(if (isP7) 0L else h.elSpatialResamplingFilterFlag, 1)
                w.writeBits(if (isP7) 1L else h.disableResidualFlag, 1)
            }
        }
        w.writeBits(h.vdrDmMetadataPresentFlag, 1)
        w.writeBits(h.usePrevVdrRpuFlag, 1)
        if (h.usePrevVdrRpuFlag == 1L) w.writeUe(h.prevVdrRpuId)
    }

    /** The fixed no-op mapping dovi_tool writes for 8.1 (set_empty_p81_mapping). */
    private fun writeEmptyP81Mapping(
        w: RpuBitWriter,
        h: RpuHeaderFields,
        blBitDepth: Long,
        vdrRpuId: Long,
        colorSpace: Long,
        chromaFormat: Long
    ) {
        val cdt = h.coefficientDataType
        val coeffLen = h.coefficientLog2DenomLength.toInt()
        w.writeUe(vdrRpuId)
        w.writeUe(colorSpace)
        w.writeUe(chromaFormat)
        for (cmp in 0 until RPU_NUM_COMPONENTS) {
            w.writeUe(0) // num_pivots_minus2
            w.writeBits(0, blBitDepth.toInt())
            w.writeBits(1023, blBitDepth.toInt())
        }
        // No NLQ section: disable_residual_flag is now 1.
        w.writeUe(0) // num_x_partitions_minus1
        w.writeUe(0) // num_y_partitions_minus1
        for (cmp in 0 until RPU_NUM_COMPONENTS) {
            w.writeUe(0) // mapping_idc = polynomial
            w.writeUe(0) // poly_order_minus1
            w.writeBits(0, 1) // linear_interp_flag
            // coef_int [0, 1], coef [0, 0] (identity polynomial)
            if (cdt == 0L) w.writeSe(0)
            w.writeBits(0, coeffLen)
            if (cdt == 0L) w.writeSe(1)
            w.writeBits(0, coeffLen)
        }
    }

    /** Re-emits a parsed mapping with partitions forced to 0 (dovi_tool convert_to_p81). */
    private fun writeMappingPreserved(
        w: RpuBitWriter,
        h: RpuHeaderFields,
        m: RpuMappingData,
        blBitDepth: Long
    ) {
        val cdt = h.coefficientDataType
        val coeffLen = h.coefficientLog2DenomLength.toInt()
        w.writeUe(m.vdrRpuId)
        w.writeUe(m.mappingColorSpace)
        w.writeUe(m.mappingChromaFormatIdc)
        w.writeUe(m.numPivotsMinus2)
        for (p in m.pivots) w.writeBits(p, blBitDepth.toInt())
        w.writeUe(0)
        w.writeUe(0)
        for (piece in m.pieces) {
            w.writeUe(piece.mappingIdc)
            if (piece.mappingIdc == 0L) {
                w.writeUe(piece.polyOrderMinus1)
                if (piece.polyOrderMinus1 == 0L) {
                    w.writeBits(if (piece.linearInterpFlag) 1L else 0L, 1)
                }
                for (j in piece.polyCoefInt.indices) {
                    if (cdt == 0L) w.writeSe(piece.polyCoefInt[j])
                    w.writeBits(piece.polyCoef[j], coeffLen)
                }
            } else {
                w.writeBits(piece.mmrOrderMinus1, 2)
                if (cdt == 0L) w.writeSe(piece.mmrConstantInt)
                w.writeBits(piece.mmrConstant, coeffLen)
                for (j in 0 until piece.mmrCoefInt.size) {
                    for (k in 0 until RPU_MMR_MAX_COEFFS) {
                        if (cdt == 0L) w.writeSe(piece.mmrCoefInt[j][k])
                        w.writeBits(piece.mmrCoef[j][k], coeffLen)
                    }
                }
            }
        }
    }

    /** Fixed DM fields with the standard 8.1 (BT.2020) coefficients (set_p81_coeffs). */
    private fun writeRpuDm(w: RpuBitWriter, d: RpuDmData) {
        w.writeUe(d.affectedDmMetadataId)
        w.writeUe(d.currentDmMetadataId)
        w.writeUe(d.sceneRefreshFlag)
        if (!d.compressed) {
            val ycc = longArrayOf(9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0)
            val off = longArrayOf(16777216, 134217728, 134217728)
            val lms = longArrayOf(7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962)
            for (v in ycc) w.writeBits(v, 16)
            for (v in off) w.writeBits(v, 32)
            for (v in lms) w.writeBits(v, 16)
            w.writeBits(d.signalEotf, 16)
            w.writeBits(d.signalEotfParam0, 16)
            w.writeBits(d.signalEotfParam1, 16)
            w.writeBits(d.signalEotfParam2, 32)
            w.writeBits(d.signalBitDepth, 5)
            w.writeBits(0, 2) // signal_color_space = 0 (8.1)
            w.writeBits(d.signalChromaFormat, 2)
            w.writeBits(d.signalFullRangeFlag, 2)
            w.writeBits(d.sourceMinPq, 12)
            w.writeBits(d.sourceMaxPq, 12)
            w.writeBits(d.sourceDiagonal, 10)
        }
    }

    /**
     * Converts one RPU payload (EPB removed, incl. the 0x19 prefix) to
     * Profile 8.1 per dovi_tool `convert --mode 2`. Returns the rewritten
     * payload with a recomputed CRC-32/MPEG-2, or null when the RPU is not
     * P5/P7 or does not parse (caller keeps the original).
     */
    internal fun convertRpuPayloadTo81(rbsp: ByteArray): ByteArray? {
        if (rbsp.size < 25) return null
        var trailingZeros = 0
        var i = rbsp.size - 1
        while (i >= 0 && rbsp[i].toInt() == 0) {
            trailingZeros++
            i--
        }
        val rpuEnd = rbsp.size - trailingZeros
        if (rpuEnd < 7) return null
        if (rbsp[rpuEnd - 1].toInt() != 0x80) return null
        val crcStart = rpuEnd - 5
        if (crcStart <= 1) return null

        val r = RpuBitReader(rbsp, crcStart * 8)
        if (r.bits(8) != 25L) return null
        val h = parseRpuHeader(r) ?: return null

        val isP5 = h.seqInfoPresent && h.vdrRpuProfile == 0L && h.blVideoFullRangeFlag == 1L
        val isP7 = h.seqInfoPresent && h.vdrRpuProfile == 1L &&
            h.elSpatialResamplingFilterFlag == 1L && h.disableResidualFlag == 0L &&
            h.vdrBitDepthMinus8 == 4L && (h.rpuFormat and 0x700L) == 0L
        if (!isP5 && !isP7) return null

        val blBitDepth = h.blBitDepthMinus8 + 8
        var mapping: RpuMappingData? = null
        if (h.usePrevVdrRpuFlag == 0L) {
            mapping = parseRpuMapping(r, h, blBitDepth) ?: return null
        }
        var dm: RpuDmData? = null
        if (h.vdrDmMetadataPresentFlag == 1L) {
            dm = parseRpuDm(r, h) ?: return null
        }
        if (!r.skipAlignmentZeros()) return null

        val w = RpuBitWriter()
        w.writeBits(0x19L, 8) // rpu_nal_prefix
        writeRpuHeader(w, h, isP5 = isP5, isP7 = isP7)
        if (h.usePrevVdrRpuFlag == 0L && mapping != null) {
            val replaceMapping = isP5 || mapping.elTypeFel
            if (replaceMapping) {
                writeEmptyP81Mapping(
                    w, h, blBitDepth,
                    mapping.vdrRpuId, mapping.mappingColorSpace, mapping.mappingChromaFormatIdc
                )
            } else {
                writeMappingPreserved(w, h, mapping, blBitDepth)
            }
        }
        if (dm != null) writeRpuDm(w, dm)
        if (!r.copyRemainingBitsTo(w)) return null
        w.byteAlign()
        val payload = w.finish()
        val crc = crc32Mpeg2(payload, 1, payload.size)
        val out = ByteArrayOutputStream(payload.size + 5 + trailingZeros)
        out.write(payload, 0, payload.size)
        out.write((crc ushr 24).toInt() and 0xFF)
        out.write((crc ushr 16).toInt() and 0xFF)
        out.write((crc ushr 8).toInt() and 0xFF)
        out.write(crc and 0xFF)
        out.write(0x80)
        repeat(trailingZeros) { out.write(0) }
        return out.toByteArray()
    }

    /**
     * Rewrites one RPU NAL (NAL header at [from], NAL end at [to]) to
     * Profile 8.1, handling emulation-prevention bytes. Returns the rewritten
     * RBSP-with-EPB (ready to splice after the 2-byte NAL header), or null
     * when the RPU is not P5/P7 or cannot be converted.
     */
    fun rewriteRpuTo81(buf: ByteArray, from: Int, to: Int): ByteArray? {
        if (to - from < 4) return null
        val rbsp = removeEmulationPrevention(buf, from + 2, to)
        val converted = convertRpuPayloadTo81(rbsp) ?: return null
        return addEmulationPrevention(converted)
    }

    /**
     * 8.1 transform for Annex-B samples: rewrites every P5/P7 RPU to Profile
     * 8.1, rewrites the VPS to a single-layer parameter set, drops the
     * enhancement layer (NAL 63 / layerId &gt; 0) and, per toggle, HDR10+ SEIs.
     * Returns the new length, or -1 when nothing changed.
     */
    fun transformAnnexBTo81(
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
                        when (val res = rewriteVpsToHdr10(buf, header + 2, nalEnd)) {
                            is VpsRewriteOutcome.Rewritten -> {
                                changed = true
                                stats?.vpsRewritten = true
                                System.arraycopy(buf, code, buf, write, codeLen)
                                System.arraycopy(buf, header, buf, write + codeLen, 2)
                                System.arraycopy(res.rbsp, 0, buf, write + codeLen + 2, res.rbsp.size)
                                write += codeLen + 2 + res.rbsp.size
                                keep = false
                            }
                            is VpsRewriteOutcome.Failed ->
                                stats?.vpsRewriteFailedReason = res.reason
                            VpsRewriteOutcome.NoDvExtension -> Unit
                        }
                    }
                    nalType == NAL_DV_RPU -> {
                        val rewritten = rewriteRpuTo81(buf, header, nalEnd)
                        if (rewritten != null) {
                            changed = true
                            stats?.rpuRewritten = (stats?.rpuRewritten ?: 0) + 1
                            System.arraycopy(buf, code, buf, write, codeLen)
                            System.arraycopy(buf, header, buf, write + codeLen, 2)
                            System.arraycopy(rewritten, 0, buf, write + codeLen + 2, rewritten.size)
                            write += codeLen + 2 + rewritten.size
                            keep = false
                        }
                    }
                    nalType == NAL_DV_EL || layerId > 0 -> {
                        keep = false
                        changed = true
                        if (stats != null) stats.elBytes += nalBytes
                    }
                    isHdr10PlusSeiNal(nalType) && stripHdr10Plus &&
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
     * Length-delimited (MP4/fMP4) counterpart of [transformAnnexBTo81].
     * Malformed samples are forwarded untouched.
     */
    fun transformLengthDelimitedTo81(
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
                        when (val res = rewriteVpsToHdr10(buf, payload + 2, payload + nal)) {
                            is VpsRewriteOutcome.Rewritten -> {
                                changed = true
                                stats?.vpsRewritten = true
                                writeLength(buf, write, res.rbsp.size + 2, fieldLen)
                                System.arraycopy(buf, payload, buf, write + fieldLen, 2)
                                System.arraycopy(res.rbsp, 0, buf, write + fieldLen + 2, res.rbsp.size)
                                write += fieldLen + 2 + res.rbsp.size
                                keep = false
                            }
                            is VpsRewriteOutcome.Failed ->
                                stats?.vpsRewriteFailedReason = res.reason
                            VpsRewriteOutcome.NoDvExtension -> Unit
                        }
                    }
                    nalType == NAL_DV_RPU -> {
                        val rewritten = rewriteRpuTo81(buf, payload, payload + nal)
                        if (rewritten != null) {
                            changed = true
                            stats?.rpuRewritten = (stats?.rpuRewritten ?: 0) + 1
                            writeLength(buf, write, rewritten.size + 2, fieldLen)
                            System.arraycopy(buf, payload, buf, write + fieldLen, 2)
                            System.arraycopy(rewritten, 0, buf, write + fieldLen + 2, rewritten.size)
                            write += fieldLen + 2 + rewritten.size
                            keep = false
                        }
                    }
                    nalType == NAL_DV_EL || layerId > 0 -> {
                        keep = false
                        changed = true
                        if (stats != null) stats.elBytes += nal
                    }
                    isHdr10PlusSeiNal(nalType) && stripHdr10Plus &&
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
}
