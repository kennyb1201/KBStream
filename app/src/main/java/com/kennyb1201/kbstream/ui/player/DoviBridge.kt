package com.kennyb1201.kbstream.ui.player

import android.util.Log

/**
 * Raw JNI surface of `libdovi.so` + `libdovi_bridge.so` (built by
 * `scripts/build_dovi.sh`).
 *
 * Why this exists: [DolbyVisionCompat] identifies a Dolby Vision RPU by its NAL
 * header byte (type 62) and rewrites it by hand — the mapping/reshaping rewrite
 * that a Player 7 → 8.1 conversion needs. On this chipset the decoder stalls on
 * every variant of that rewrite (relabel, no-relabel, VPS rewrite), which is the
 * black screen with audio the DV settings exist to work around: the reference
 * player, handing the same file to libdovi and letting IT produce the 8.1 RPU,
 * renders. libdovi parses the RPU rather than pattern-matching it, so it is the
 * right author for those bytes and this is the way to it.
 *
 * Name-mangling is load-bearing, exactly as in [AssNative]: the C side is named
 * `Java_com_kennyb1201_kbstream_ui_player_DoviBridge_native*`, so the package,
 * the object name and each method name must not change without changing
 * `app/src/main/cpp/dovi_bridge.c` in the same commit — and the members stay at
 * Kotlin's default visibility, because an `internal` *function* is name-mangled
 * in the bytecode and would break that lookup.
 *
 * Nothing here may be called unless [available] is true: the JVM resolves an
 * `external fun` lazily, so an absent library raises UnsatisfiedLinkError at the
 * call site rather than at class load. Every caller in this app is written as
 * "use the bridge when [available], else the Kotlin path", which is the
 * fallback the DV settings already depend on for local builds and for any CI run
 * where the native step failed. (The `init { System.loadLibrary(...) }` a
 * conventional JNI object opens with would throw out of class initialization
 * instead, and there would be no fallback to take.)
 */
internal object DoviBridge {

    private const val TAG = "PLAYER_DV"

    /** Library name passed to [System.loadLibrary]. */
    const val LIBRARY = "dovi_bridge"

    /**
     * Whether this build actually carries libdovi. False on any build made
     * without running `scripts/build_dovi.sh` (including every local build and
     * any CI run where the native step failed), and what the RPU path checks
     * before it hands a sample to JNI.
     */
    val available: Boolean = runCatching { System.loadLibrary(LIBRARY) }
        .onFailure {
            Log.i(
                TAG,
                "dovi_bridge.so is not in this build: libdovi RPU conversion is unavailable and " +
                    "the Kotlin RPU rewrite handles the 8.1 path",
                it
            )
        }
        .isSuccess

    /**
     * Framing of the buffer handed to [nativeProcessVideoSample].
     *
     * `ANNEX_B` walks start codes, `LENGTH_DELIMITED` walks the big-endian NAL
     * lengths an MP4/fMP4 sample carries, and `SINGLE_NAL` is one NAL's own
     * bytes with neither — which is what the RPU conversion path has in hand
     * (see [convertRpuTo81]): the caller has already found the NAL.
     */
    const val NAL_FORMAT_ANNEX_B = 0
    const val NAL_FORMAT_LENGTH_DELIMITED = 1
    const val NAL_FORMAT_SINGLE_NAL = 2

    /** [nativeDetectProfile]: this NAL is not a valid Dolby Vision RPU. */
    const val NOT_DOLBY_VISION = -1

    /** [nativeDetectProfile] / [nativeProcessVideoSample]: the RPU would not parse. */
    const val PARSE_ERROR = -2

    /** [nativeProcessVideoSample]: the converted NAL does not fit in `outBuffer`. */
    const val BUFFER_TOO_SMALL = -3

    /**
     * libdovi's conversion modes, as the crate the build script pins reads them.
     *
     * Mode 2 is the universal converter the 8.1 path uses: the mapping curves
     * become no-ops and profiles 5, 7 and 8 are all handled, which is exactly
     * "8.1 for whatever this file claims to be". The mode numbers come from
     * libdovi's `ConversionMode`, and the two constants below it are pinned to
     * what 3.4.0 DOES rather than what its C API comments say: its `From<u8>`
     * maps 2 and 3 both to `To81` and 4 to `To84`, so the documented "3 = 8.4"
     * is not reachable and "4 = 8.1 preserving the mapping" is not either
     * (mode 4 is 8.4). Nothing in this app converts to 8.4 — a caller that ever
     * wants it must re-read that mapping against the pinned version first.
     */
    const val MODE_TO_81 = 2

    /** Kept for callers that deliberately ask for 8.4 (see the note above). */
    const val MODE_TO_84 = 4

    /**
     * The Dolby Vision profile of the RPU NAL at `payload[offset, offset+length)`,
     * or [NOT_DOLBY_VISION] / [PARSE_ERROR].
     *
     * The payload is the NAL as it sits in the stream from its two-byte NAL
     * header on (libdovi strips that header itself, and it recognizes it), with
     * no start code and no length field. `offset`/`length` select it inside
     * `payload` so a whole sample buffer can be handed over without a copy.
     */
    external fun nativeDetectProfile(payload: ByteArray, offset: Int, length: Int): Int

    /**
     * Processes one video sample (or, with [NAL_FORMAT_SINGLE_NAL], one NAL) and
     * returns the bytes written to `outBuffer`, or a negative error code.
     *
     * Handles the NAL scan for every framing, and per NAL:
     *
     *  - a valid UNSPEC62 Dolby Vision RPU: dropped when `stripDoviRpu`, else
     *    converted with `doviMode` when `convertDovi`, else forwarded untouched
     *    (a failed conversion forwards the original bytes rather than corrupting
     *    the stream);
     *  - an UNSPEC63 enhancement-layer NAL (or any NAL with a non-zero layer id):
     *    dropped when `stripDoviRpu` — a profile 7 single-layer output cannot
     *    carry the EL, and it is what stalls the decoder;
     *  - an HDR10+ (ST 2094-40) SEI NAL: dropped when `stripHdr10Plus`. This is
     *    the COARSE form — the whole SEI NAL goes, including any other message
     *    sharing it — where [DolbyVisionCompat] strips only the HDR10+ message
     *    and keeps the rest of the NAL. The extractor keeps its surgical version
     *    for that reason and passes false here; the flag exists so the sample
     *    path can strip a stream down in one call.
     *
     * `doviProfile` is the caller's expectation: 0 means "whatever the RPU
     * parses as", and a profile number means convert only when the parsed RPU is
     * that profile.
     */
    external fun nativeProcessVideoSample(
        sample: ByteArray,
        sampleLen: Int,
        nalFormat: Int,
        nalLengthFieldLength: Int,
        outBuffer: ByteArray,
        convertDovi: Boolean,
        doviMode: Int,
        doviProfile: Int,
        stripDoviRpu: Boolean,
        stripHdr10Plus: Boolean
    ): Int

    /**
     * The Profile 8.1 RPU payload for the UNSPEC62 NAL at `buf[header, nalEnd)`,
     * or null when this build has no libdovi, the NAL is not a convertible Dolby
     * Vision RPU, or the conversion failed.
     *
     * Payload only, and without the two-byte NAL header: that is the shape
     * [DolbyVisionCompat]'s in-place transforms write (`codeLen`, then the
     * original NAL header, then these bytes), and the shape its own
     * `rewriteRpuTo81` returns, so the two are drop-in alternatives for each
     * other at the call site.
     */
    fun convertRpuTo81(buf: ByteArray, header: Int, nalEnd: Int): ByteArray? {
        if (!available) return null
        val payloadLen = nalEnd - header
        if (payloadLen < 3) return null
        // The RPU NAL including its 2-byte header: libdovi's parser expects it
        // there and validates it.
        val input = buf.copyOfRange(header, nalEnd)
        // A conversion can grow the payload (mode 2 drops the mapping but
        // re-escapes the RBSP); the slack is generous because this is a few
        // KB per frame and the alternative is a sample silently left alone.
        val out = ByteArray(input.size * 2 + 128)
        val written = runCatching {
            nativeProcessVideoSample(
                sample = input,
                sampleLen = input.size,
                nalFormat = NAL_FORMAT_SINGLE_NAL,
                nalLengthFieldLength = 0,
                outBuffer = out,
                convertDovi = true,
                doviMode = MODE_TO_81,
                // Whatever the RPU says it is: mode 2 is the universal 8.1
                // converter, and a file that turned out to be 5 or 8 takes the
                // same path the spec's table gives it.
                doviProfile = 0,
                stripDoviRpu = false,
                stripHdr10Plus = false
            )
        }.getOrElse {
            Log.w(TAG, "libdovi could not convert an RPU; keeping the Kotlin rewrite", it)
            return null
        }
        // 0 means "nothing here to convert" and <0 is an error code, so both
        // leave the caller on its own rewrite. Fewer than 3 bytes cannot be a
        // NAL with a header and payload either.
        if (written < 3) return null
        // Drop the NAL header libdovi wrote back: the caller writes its own.
        return out.copyOfRange(2, written)
    }

    private val DV_PROFILE_REGEX = Regex("^(dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.")

    /**
     * The Dolby Vision profile a track's codec string declares — 5, 7 or 8 — or
     * [NOT_DOLBY_VISION] when it declares none.
     *
     * Pure string work on purpose: it is asked on the format path (every format
     * change of a DV track) and answers the only question the JNI side would
     * otherwise be needed for there, `dvhe.07` being profile 7. An unknown
     * profile number reads as [NOT_DOLBY_VISION] rather than a guess.
     */
    fun detectDvProfile(codecString: String?): Int {
        val match = codecString?.let { DV_PROFILE_REGEX.find(it.trim()) } ?: return NOT_DOLBY_VISION
        return match.groupValues[2].toIntOrNull() ?: NOT_DOLBY_VISION
    }
}
