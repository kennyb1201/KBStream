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
    // TVs without Dolby Vision). Profile 5 (ICtCp, no HDR10 base) can only be
    // force-decoded as plain HEVC — a picture appears, but colors can be off;
    // a true conversion would need a color-mapping pipeline.
    private val DV_CODEC_ALL_PROFILES = Regex("(?i)^(dvhe|dvh1)\\.(04|4|05|5|07|7|08|8)\\..+$")

    /** Generic Main10@L5.1 HEVC identifier describing the stripped base layer. */
    const val HDR10_CODEC: String = "hvc1.2.4.L153.B0"

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
                if (stripDv && (nalType == NAL_DV_RPU || nalType == NAL_DV_EL || layerId > 0)) {
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
                if (stripDv && (nalType == NAL_DV_RPU || nalType == NAL_DV_EL || layerId > 0)) {
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
