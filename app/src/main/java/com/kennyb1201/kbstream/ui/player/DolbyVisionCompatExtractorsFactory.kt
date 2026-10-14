package com.kennyb1201.kbstream.ui.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/**
 * Wraps the stock Media3 [ExtractorsFactory] and, for video tracks, intercepts
 * the sample stream to strip Dolby Vision Profile 7 RPU / enhancement-layer NAL
 * units on the fly (see [DolbyVisionCompat]). Non-DV content is a strict
 * pass-through that never touches the buffering path.
 *
 * Framing per container:
 *  - Media3's MP4 / fragmented MP4 extractors convert length-prefixed HEVC NAL
 *    units to Annex-B start codes before calling TrackOutput, so their samples
 *    are handled as Annex-B here too.
 *  - TS and Matroska reach TrackOutput as Annex-B as well. This single framing
 *    path covers MP4, fMP4, TS and the common single-track MKV remuxes where
 *    the DV RPU rides in-band.
 *
 * Detection happens in two stages. Tracks whose codec string declares Dolby
 * Vision (dvhe/dvh1 via a dvcc box) strip immediately and have their codec
 * rewritten so Media3 never queries a Dolby Vision decoder — but only when
 * [dvRewriteEnabled] is set (DV mode "P7 → 8.1" or "Strip All"). In the
 * "P7 → 8.1" mode only Profile 7 qualifies (every other DV profile is passed
 * through so DV displays get the real thing); with [convertAllProfiles] set
 * ("Strip All") every profile
 * — 4/5/7/8 — is converted for displays without Dolby Vision. On devices with
 * a native Dolby Vision decoder ([nativeDvSupported]) the HDR10-base profiles
 * (4/8) pass through as real Dolby Vision — which those devices decode
 * natively (mutating them is what makes MTK-class HEVC decoders stall with
 * zero output frames) — EXCEPT in the explicit "Strip All" mode, where the
 * user has told us the display has no Dolby Vision at all and P4/P8 are
 * always stripped to HDR10 even on DV-capable devices (a DV decoder on the
 * box does not mean the TV can show DV; on such combos the platform DV
 * pipeline black-screens the non-DV display, so Strip All must win). Profiles 4/8
 * already carry a standard HDR10 base layer, so on non-DV devices they are
 * re-advertised as hvc1,
 * their VPS is rewritten to a clean single-layer parameter set (a DV VPS left
 * behind after the strip makes some decoders stall waiting for RPUs), and only
 * the DV RPU / EL and (per toggle) HDR10+ SEI NALs are dropped — every other
 * byte stays bit-exact; P5 has no HDR10 base, so it stays a best-effort
 * plain-HEVC fallback that the GLES shader / FFmpeg path color-corrects. Tracks reported as
 * plain HEVC (hvc1/hev1 — muxers that omitted the dvcc marker) are sniffed
 * over the first samples: in-band RPU NALs engage the same strip (DV remuxes,
 * when DV conversion is enabled), and with [stripHdr10Plus] set, ST 2094-40
 * SEIs are removed from plain-HDR10+ releases so HDR10+-intolerant TVs don't
 * black-screen. With DV conversion disabled but the HDR10+ strip on (DV mode
 * Off + toggle), only the HDR10+ SEIs are removed and DV streams are never
 * touched. DV→HDR10 conversions themselves (Strip All) always drop
 * HDR10+ SEI NALs from the samples regardless of the toggle — the converted
 * stream is static HDR10, and HDR10+ dynamic metadata black-screens the same
 * HDR10+-intolerant TVs these modes exist for (common on P8 WEB-DL encodes
 * that carry DV + HDR10+ in one track). Media3 1.9 has no Format-level HDR10+
 * passthrough, so the sample-level strip is the whole story. Verified-clean
 * samples are forwarded untouched, so a false negative only ever costs a few
 * buffered samples, never picture data.
 *
 * MKV variants where the RPU only exists as BlockAdditional side data are not
 * reachable here (stock Media3 discards that data before TrackOutput); on those
 * files the base layer already reaches the decoder clean and the codec rewrite
 * above is what fixes playback.
 */
/**
 * How a declared single-layer Dolby Vision profile with an HDR10 base layer
 * (P4 / P8) is handled once the mode has decided to rewrite it.
 *
 * Extracted as a pure value so the whole matrix is pinned by a unit test
 * without a player: the three paths are materially different, and the wrong
 * one black-screens a non-DV TV (native passthrough), stalls an MTK-class
 * decoder (strip), or fixes playback (relabel-only).
 */
internal enum class Hdr10BaseHandling { PASSTHROUGH, RELABEL_ONLY, STRIP }

/**
 * The P4/P8 handling for one stream.
 *
 *  - [Hdr10BaseHandling.PASSTHROUGH]: a DV-capable box on a DV-capable display
 *    in Auto (or any mode that is not the explicit "Strip All") — the platform
 *    DV pipeline shows the real thing.
 *  - [Hdr10BaseHandling.RELABEL_ONLY]: a DV-capable box on a NON-DV display in
 *    Auto. The platform pipeline black-screens the sink, and rewriting the
 *    VPS/SPS is what stalls MTK-class decoders, so the base layer is
 *    re-advertised as plain HEVC, the codec config's VPS is rewritten to a
 *    clean single-layer set, and the DV RPU/EL and the HDR10+ SEI are removed
 *    in place — no SPS/PPS and no in-sample parameter set is touched (see
 *    `Mode.STRIP_NALS_ONLY`).
 *  - [Hdr10BaseHandling.STRIP]: no DV decoder on the box, or the explicit
 *    "Strip All" override — strip the RPU/EL/HDR10+ NALs to HDR10 as before.
 */
internal fun selectHdr10BaseHandling(
    nativeDvSupported: Boolean,
    nonDvDisplayOnDvDevice: Boolean,
    convertAllProfiles: Boolean,
): Hdr10BaseHandling = when {
    nativeDvSupported && !convertAllProfiles -> Hdr10BaseHandling.PASSTHROUGH
    nonDvDisplayOnDvDevice && !convertAllProfiles -> Hdr10BaseHandling.RELABEL_ONLY
    else -> Hdr10BaseHandling.STRIP
}

internal class DolbyVisionCompatExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val stripHdr10Plus: Boolean = false,
    private val convertAllProfiles: Boolean = false,
    private val dvRewriteEnabled: Boolean = true,
    private val convertP7To81: Boolean = false,
    private val convertP5To81: Boolean = false,
    private val nativeDvSupported: Boolean = false,
    /**
     * A DV-capable box driving a NON-DV display in Auto. Native passthrough
     * black-screens such a sink, and a bitstream-mutating strip is what stalls
     * MTK-class decoders, so P4/P8 take the relabel-only path instead (see
     * [selectHdr10BaseHandling]).
     */
    private val nonDvDisplayOnDvDevice: Boolean = false
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> {
        Log.i("PLAYER_DV", "Compat extractor factory invoked (default)")
        return delegate.createExtractors().map { wrap(it) }.toTypedArray()
    }

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> {
        Log.i(
            "PLAYER_DV",
            "Compat extractor factory invoked uri=${uri.lastPathSegment ?: uri} " +
                "headers=${responseHeaders.keys.joinToString(",")}"
        )
        return delegate.createExtractors(uri, responseHeaders).map { wrap(it) }.toTypedArray()
    }

    private fun wrap(extractor: Extractor): Extractor {
        val name = extractor.javaClass.name
        val simpleName = name.substringAfterLast('.')
        // Keep the wrapper in front of every candidate returned by
        // DefaultExtractorsFactory. Media3 may return a container-specific
        // extractor whose implementation is decorated/proxied, so relying
        // only on the concrete class name can silently bypass DV rewriting.
        // VideoCompatExtractor only intercepts video tracks; audio and other
        // track types remain direct pass-throughs. Media3 emits the HEVC
        // access units seen here as Annex-B for the progressive containers
        // handled by this factory.
        val framing = NalFraming.ANNEX_B
        Log.i(
            "PLAYER_DV",
            "Wrapping extractor=$simpleName framing=$framing " +
                "allProfiles=$convertAllProfiles rewriteEnabled=$dvRewriteEnabled " +
                "stripHdr10Plus=$stripHdr10Plus convertP7To81=$convertP7To81 convertP5To81=$convertP5To81 " +
                "nativeDv=$nativeDvSupported nonDvDisplay=$nonDvDisplayOnDvDevice"
        )
        return VideoCompatExtractor(
            extractor, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled,
            convertP7To81, convertP5To81, nativeDvSupported, nonDvDisplayOnDvDevice
        )
    }
}

/** How HEVC NAL units are framed in the sample stream for a given container. */
internal enum class NalFraming { ANNEX_B, LENGTH_DELIMITED }

/** Forwards to a delegate extractor but routes video tracks through a stripping TrackOutput. */
private class VideoCompatExtractor(
    private val delegate: Extractor,
    private val framing: NalFraming,
    private val stripHdr10Plus: Boolean,
    private val convertAllProfiles: Boolean,
    private val dvRewriteEnabled: Boolean,
    private val convertP7To81: Boolean,
    private val convertP5To81: Boolean,
    private val nativeDvSupported: Boolean,
    private val nonDvDisplayOnDvDevice: Boolean
) : Extractor {

    override fun init(output: ExtractorOutput) {
        Log.i("PLAYER_DV", "Compat extractor initialized=${delegate.javaClass.simpleName}")
        delegate.init(
            VideoCompatExtractorOutput(
                output, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled,
                convertP7To81, convertP5To81, nativeDvSupported, nonDvDisplayOnDvDevice
            )
        )
    }

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

private class VideoCompatExtractorOutput(
    private val delegate: ExtractorOutput,
    private val framing: NalFraming,
    private val stripHdr10Plus: Boolean,
    private val convertAllProfiles: Boolean,
    private val dvRewriteEnabled: Boolean,
    private val convertP7To81: Boolean,
    private val convertP5To81: Boolean,
    private val nativeDvSupported: Boolean,
    private val nonDvDisplayOnDvDevice: Boolean
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) {
            VideoCompatTrackOutput(
                track, framing, stripHdr10Plus, convertAllProfiles, dvRewriteEnabled,
                convertP7To81, convertP5To81, nativeDvSupported, nonDvDisplayOnDvDevice
            )
        } else {
            track
        }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

/**
 * TrackOutput that buffers each video sample while active and either passes it
 * through untouched, sniffs it for in-band Dolby Vision RPU NALs, or strips
 * them (rewriting the codec string to plain HEVC for declared DV tracks).
 */
private class VideoCompatTrackOutput(
    private val delegate: TrackOutput,
    private val framing: NalFraming,
    private val stripHdr10Plus: Boolean,
    private val convertAllProfiles: Boolean,
    private val dvRewriteEnabled: Boolean,
    private val convertP7To81: Boolean,
    private val convertP5To81: Boolean,
    private val nativeDvSupported: Boolean,
    private val nonDvDisplayOnDvDevice: Boolean
) : TrackOutput {

    /** True when either per-profile 8.1 conversion (P5/P7) is active. */
    private val convertTo81 = convertP7To81 || convertP5To81

    // The HDR10 color metadata every DV->HDR10 rewrite path below attaches —
    // see [DV_HDR10_COLOR_INFO] for why it is not optional.
    private val hdr10ColorInfo = DV_HDR10_COLOR_INFO

    private enum class Mode { NORMAL, SNIFFING, STRIPPING, STRIP_NALS_ONLY }

    private var mode = Mode.NORMAL
    private var sniffRemaining = 0
    private var currentCodecs: String? = null
    // Original format (untouched init data) of the current track, kept so the
    // sniff path can re-emit the format with rewritten VPS/SPS once in-band
    // Dolby Vision is confirmed on a track whose codec config was forwarded
    // verbatim (plain hvc1/hev1 remuxes).
    private var lastFormat: Format? = null
    private var isP5Content = false
    /**
     * True while the current track's samples are routed through the Profile 8.1
     * transform (RPUs rewritten, EL dropped). Per track, not per session: in
     * "P7 → 8.1" mode a P7 track takes the 8.1 route while a P5 track is
     * *stripped* to HDR10 for the GPU color path, and both the transform choice
     * and the HDR10+ handling must follow the track, not the mode.
     */
    private var strippingTo81 = false
    private var nalLengthFieldLength = 4
    private var pendingBuf = ByteArray(0)
    private var pendingLen = 0
    private var stripReported = false
    // True once this track's codec config (hvcC record / Annex-B parameter
    // sets) was rewritten to single-layer HDR10 at format time. MP4/fMP4
    // samples carry no VPS/SPS, so the per-sample stats below never see them;
    // this flag makes the first-sample log report the config rewrite that
    // actually happened.
    private var initDataRewritten = false
    private val scratch = ParsableByteArray()

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        currentCodecs = format.codecs
        lastFormat = format
        isP5Content = DolbyVisionCompat.isP5Profile(format.codecs)
        Log.i(
            "PLAYER_DV",
            "Compat video format mime=${format.sampleMimeType} codecs=${format.codecs ?: "?"} P5=${isP5Content}"
        )
        // A (re)emitted format starts a fresh sample window (seek / re-init).
        pendingLen = 0
        // 8.1 conversion is per-profile: only declared P5/P7 streams whose
        // toggle is on are rewritten — the codec stays in the dvhe/dvh1 family
        // (so the Dolby Vision pipeline still engages) with the profile digits
        // changed to 08, the VPS is forced to a single layer, the enhancement
        // layer NALs are dropped, and every RPU is rewritten to 8.1 metadata
        // with a fresh CRC. P4 / P8 declared streams have no matching to81Codec
        // and follow the mode's default handling below.
        // The 8.1 rewrite is a true conversion only for Profile 7: its base
        // layer IS HDR10, so relabeling the profile digits ("dvhe.07" →
        // "dvhe.08"), dropping the enhancement layer and re-emitting the RPUs as
        // 8.1 metadata (the dovi_tool mode-2 transform) leaves pixels the
        // display can render exactly as 8.1 declares them. Profile 5 is not
        // that: its pixels are ICtCp, and no bitstream-level rewrite changes
        // that, so relabeling P5 as 8.1 hands the display ICtCp interpreted as
        // Rec.2020 PQ — the green and purple picture. P5 therefore never takes
        // this route; the P5 conversion below strips it for the GPU color path
        // instead.
        val to81Rewrite =
            if (convertTo81) DolbyVisionCompat.to81Codec(format.codecs, convertP7To81, false)
            else null
        strippingTo81 = to81Rewrite != null
        if (to81Rewrite != null) {
            Log.i(
                "PLAYER_DV",
                "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — converting to " +
                    "Profile 8.1 ($to81Rewrite): single-layer VPS, EL dropped, RPUs rewritten"
            )
            var builder = format.buildUpon().setCodecs(to81Rewrite)
            // Keep the original declared DV codec (e.g. "dvhe.07.06") on the
            // label so the player UI / P5 detection can badge the source profile.
            if (!format.codecs.isNullOrBlank()) {
                builder = builder.setLabel(format.codecs)
            }
            builder = builder.setInitializationData(
                rewriteInitData(format.initializationData)
            )
            delegate.format(builder.build())
            mode = Mode.STRIPPING
            return
        }
        // When DV conversion is disabled (the DV setting is Off and only the
        // HDR10+ strip / 8.1 toggles are on), declared DV tracks whose profile
        // is not 8.1-converted must pass through untouched — no codec rewrite,
        // no RPU strip.
        // P5 conversion (the "P5 → HDR10" switch in Settings): Profile 5 has no
        // HDR10 base layer, so the only handling that can produce correct colors
        // is to strip the DV metadata and let the player's GPU color path
        // convert the ICtCp planes to Rec.2020 PQ — the same shape "Strip All"
        // produces for P5, and the color path engages alongside this toggle (see
        // p5GlesPathWanted in the player). The declared codec stays on the
        // format label so the player still recognizes the source as P5.
        val p5Conversion =
            convertP5To81 && !convertAllProfiles && DolbyVisionCompat.isP5Profile(format.codecs)
        val dvRewrite = when {
            p5Conversion -> DolbyVisionCompat.HDR10_CODEC
            dvRewriteEnabled -> DolbyVisionCompat.hdr10Codec(format.codecs, convertAllProfiles)
            else -> null
        }
        // Profiles 4/8 are single-layer streams whose base layer is already
        // standard HDR10 HEVC. On a device with a native Dolby Vision decoder
        // they play untouched through the platform DV pipeline — the DV decoder
        // consumes the RPU NALs and the platform downconverts for non-DV sinks —
        // so no mutation is needed. Mutating them is exactly what MTK-class DV
        // hardware chokes on: re-advertising as hvc1 and stripping the RPUs
        // leaves the HEVC decoder configured OK but stalling with zero output
        // frames. The strip path below is only for devices without a DV decoder
        // (where the base layer must be played as plain HDR10) — except in the
        // explicit "Strip All" mode, which is the user telling us the display
        // has NO Dolby Vision at all and every profile must become HDR10.
        // A DV-capable device (e.g. a Fire TV Stick advertising video/dolby-
        // vision) does NOT imply a DV-capable display: on such combos the
        // platform DV pipeline does not reliably downconvert for the non-DV TV
        // (black screen with audio), so Strip All must override the passthrough
        // and always strip P4/P8 to HDR10. On those devices without a DV
        // decoder, forwarding the in-band DV RPU / HDR10+ metadata NALs
        // untouched makes some decoders re-emit their output format on every
        // frame ("Resolution change XxX to XxX" at video fps) and the compositor
        // drops the frames — black screen with audio. But stripping the
        // metadata while leaving the Dolby Vision VPS in place is worse: the
        // decoder sits in DV mode waiting for RPUs that never arrive and stalls
        // completely (input frames in, zero output). So for these profiles:
        // re-advertise as plain HEVC, rewrite the VPS to a clean single-layer
        // parameter set, and strip the metadata NALs (62/63 + layerId>0 +
        // HDR10+ SEI per toggle) — every other byte bit-exact.
        // Passthrough suppression is the player telling us this box's Dolby
        // Vision decoder has already hard-failed (OMX_ErrorInsufficientResources,
        // 0x80001000), so handing it Dolby Vision again cannot work — it has to
        // become HDR10, exactly as Strip All forces. dvRewrite is null for
        // declared P4/P8 (they are already HDR10-base, so Auto's "P7 → 8.1"
        // rewrite does not target them), which is why suppression used to change
        // nothing at all for those profiles: the stream went to the vendor DV
        // decoder anyway and failed the same way on every title. P8 is the
        // profile these 4K web-DL remuxes carry, so this was the whole
        // suppression not working.
        val hdr10BaseLayerProfile = DolbyVisionCompat.isHdr10BaseLayerProfile(format.codecs)
        val rewriteCodec =
            dvRewrite ?: if (
                dvRewriteEnabled && !nativeDvSupported && !convertAllProfiles &&
                hdr10BaseLayerProfile
            ) {
                DolbyVisionCompat.hdr10Codec(format.codecs, true)
            } else null
        if (rewriteCodec != null && hdr10BaseLayerProfile) {
            // The relabel advertises a plain-HEVC codec string to the decoder.
            // HDR10_CODEC is a generic Main10@L5.1 guess; MTK-class decoders
            // check the advertised tier/level against the SPS and emit zero
            // output frames when they disagree (field: dvhe.08.06 = High tier
            // Level 5.0, advertised as L153). Derive the true string from the
            // track's own SPS and only guess when it cannot be parsed.
            val spsCodec = DolbyVisionCompat.hevcCodecFromInitData(format.initializationData)
            val relabelCodec = spsCodec ?: rewriteCodec
            val codecSrc = if (spsCodec != null) "sps" else "fallback"
            val handling = selectHdr10BaseHandling(
                nativeDvSupported = nativeDvSupported,
                nonDvDisplayOnDvDevice = nonDvDisplayOnDvDevice,
                convertAllProfiles = convertAllProfiles
            )
            if (handling == Hdr10BaseHandling.PASSTHROUGH) {
                Log.i(
                    "PLAYER_DV",
                    "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — device has a " +
                        "native Dolby Vision decoder: passing through as Dolby Vision " +
                        "(no strip — HDR10-base profiles play natively)"
                )
                mode = Mode.NORMAL
                delegate.format(format)
                return
            }
            if (handling == Hdr10BaseHandling.RELABEL_ONLY) {
                // DV-decoder box, non-DV display (Auto): re-advertise the HDR10
                // base layer as plain HEVC, rewrite the codec config's VPS to a
                // clean single-layer parameter set — the decoder configured from
                // a DV-flagged VPS and then waited for the RPUs this drops —
                // and strip the DV RPU/EL and the HDR10+ SEI in place. The
                // SPS/PPS are not rewritten and neither is any in-sample
                // parameter set. Mutating the in-sample sets is
                // what stalls MTK-class decoders (field: Strip All = configure
                // OK, zero frames); leaving the in-band DV RPU / HDR10+ SEI in
                // the stream is what makes the decoder re-emit its output
                // format on every frame and the compositor drop the frames
                // (black screen with audio). The RPU used to be KEPT here on
                // the theory that dropping it left a DV-flagged track with no
                // DV metadata and the decoder stalled — but that arm was only
                // ever field-run with the WRONG codec string (the SPS-derived
                // one landed after it), and the reference player's "Always
                // Strip DV" plays this chipset RPU-dropped with a correct codec string. The
                // relabel drops it too: RPU-dropped + correct codec string is
                // the combination that has picture — and as of the 2026-10-11
                // capture the rewritten color info also carries the container's
                // HDR10 static metadata (see [dvHdr10ColorInfoFor]): every other
                // visible parameter already matched the reference config, and
                // hdrStaticInfo was the remaining difference. The base layer is
                // standard HDR10; Mode.STRIP_NALS_ONLY isolates this experiment from
                // transformAnnexB, which rewrites the VPS in-sample.
                // The construction is stepwise: the full relabel (declared codec
                // on the label + HDR10 color info) first, then a minimal
                // codecs+mime-only relabel if that throws. A single construction
                // failure is not hypothetical — a 2026-10-10 field capture
                // (dvhe.08.10, AFTKRT) had it throw and fall back to native DV,
                // which black-screened with per-frame "Resolution change" spam.
                // Shipping the minimal relabel both recovers the working path and
                // identifies which step threw. The success log is emitted only
                // AFTER the build, so it can never announce a relabel that did
                // not happen.
                // The config's VPS is rewritten only while the app's own
                // stripper is the one removing the samples. libdovi is the
                // stripper whenever it is in the build (see
                // Mode.STRIP_NALS_ONLY), and the 2026-10-13 field run on a
                // Fire TV Stick 4K Max + non-DV TV is what this arm exists for:
                // on Auto the relabel plus libdovi's strip but with the
                // rewritten VPS in the codec config came up as a solid green
                // picture at 62 kbps, where Nuvio — which never touches the
                // codec config — renders. The Kotlin fallback keeps the
                // rewrite, because the rewrite is what closes its stall.
                val skipVpsRewrite = DoviBridge.available
                // The last difference from Nuvio's stripRpuOnly, on trial
                // (2026-10-14): it never re-advertises the track, so the
                // declared codec string stays on the format while libdovi strips
                // the samples. Same gate as the VPS skip above.
                val skipRelabel = DoviBridge.available
                val relabel = relabelTargetFor(format, skipVpsRewrite = skipVpsRewrite, skipRelabel = skipRelabel)
                val relabeled =
                    if (relabel.relabelSkipped) null else relabelDvFormat(relabel.format, relabelCodec)
                if (relabeled != null || relabel.relabelSkipped) {
                    // Reported from the format that is actually handed to the
                    // decoder, not from the source: the field test reads this to
                    // tell whether the container even carried HDR10 static
                    // metadata.
                    val presented = relabeled?.format ?: relabel.format
                    val hdrStatic =
                        if (presented.colorInfo?.hdrStaticInfo != null) "preserved"
                        else "absent"
                    val advertised = if (relabeled != null) {
                        "re-advertising as hvc1 $relabelCodec " +
                            "(${relabeled.attempt.name.lowercase()} relabel)"
                    } else {
                        "keeping the declared codec ${format.codecs ?: "?"} (no relabel)"
                    }
                    Log.i(
                        "PLAYER_DV",
                        "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — DV box, non-DV " +
                            "display: $advertised, samples: HDR10+ SEI " +
                            "stripped, RPU dropped, " +
                            "relabel=${relabelOutcome(relabel.relabelSkipped)} " +
                            "vpsRewrite=${vpsRewriteOutcome(skipVpsRewrite, relabel.vpsRewritten)} " +
                            "vpsRewritten=${relabel.vpsRewritten} " +
                            "(config VPS only, SPS/PPS untouched) " +
                            "(nativeDv=$nativeDvSupported stripAll=$convertAllProfiles)" +
                            " codecSrc=$codecSrc hdrStaticInfo=$hdrStatic"
                    )
                    delegate.format(presented)
                    // The relabel is bit-exact and the removal is in-place:
                    // the only parameter set touched is the codec config's VPS,
                    // rewritten by relabelTargetFor above (see
                    // Mode.STRIP_NALS_ONLY for the sample side) — or, while the
                    // 2026-10-14 no-relabel test is on, none at all: the track
                    // and its codec config reach the decoder exactly as the
                    // container declared them and only the samples changed.
                    mode = Mode.STRIP_NALS_ONLY
                } else {
                    mode = Mode.NORMAL
                    delegate.format(format)
                }
                return
            }
            if (hdr10BaseLayerProfile && nativeDvSupported) {
                // DV-decoder hardware: the mutating strip (VPS rewrite + RPU
                // removal) is field-dead here — zero-frame stall, proven twice.
                // P4/P8 take the working path instead: relabel to hvc1/HDR10,
                // strip the HDR10+ SEI and the RPU/EL, and rewrite the codec
                // config's VPS to a clean single-layer set (the SPS/PPS stay as
                // they are). Same relabel the Auto non-DV-display branch above
                // uses; this is where "Strip All" — and the 0x80001000 rescue,
                // which forces Strip All for the session — lands on such a box.
                // Boxes WITHOUT a DV decoder keep the mutating strip below: it
                // is the tested working path there, so it is left alone.
                // Same libdovi gate as the Auto branch above: this is where
                // "Strip All" (and the 0x80001000 rescue that forces it) lands
                // on a DV-capable box, the strip itself is libdovi's, and the
                // rewritten config VPS is what green-screened that same field
                // run at 62 kbps. The mutating strip below keeps its rewrite —
                // that path's samples are stripped by the Kotlin parser, which
                // is the stall the rewrite closes.
                val skipVpsRewrite = DoviBridge.available
                // The same trial as the Auto branch above, on this box: Nuvio
                // leaves the track declared, so the declared codec string stays
                // here too while libdovi strips the samples.
                val skipRelabel = DoviBridge.available
                val relabel = relabelTargetFor(format, skipVpsRewrite = skipVpsRewrite, skipRelabel = skipRelabel)
                val relabeled =
                    if (relabel.relabelSkipped) null else relabelDvFormat(relabel.format, relabelCodec)
                if (relabeled != null || relabel.relabelSkipped) {
                    val presented = relabeled?.format ?: relabel.format
                    val hdrStatic =
                        if (presented.colorInfo?.hdrStaticInfo != null) "preserved"
                        else "absent"
                    val advertised = if (relabeled != null) {
                        "re-advertising as hvc1 $relabelCodec " +
                            "(${relabeled.attempt.name.lowercase()} relabel)"
                    } else {
                        "keeping the declared codec ${format.codecs ?: "?"} (no relabel)"
                    }
                    Log.i(
                        "PLAYER_DV",
                        "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — DV-decoder " +
                            "hardware: $advertised, samples: HDR10+ SEI " +
                            "stripped, RPU dropped, " +
                            "relabel=${relabelOutcome(relabel.relabelSkipped)} " +
                            "vpsRewrite=${vpsRewriteOutcome(skipVpsRewrite, relabel.vpsRewritten)} " +
                            "vpsRewritten=${relabel.vpsRewritten} " +
                            "(config VPS only, SPS/PPS untouched) " +
                            "(the mutating strip is field-dead here)" +
                            " codecSrc=$codecSrc hdrStaticInfo=$hdrStatic"
                    )
                    delegate.format(presented)
                    mode = Mode.STRIP_NALS_ONLY
                } else {
                    mode = Mode.NORMAL
                    delegate.format(format)
                }
                return
            }
            Log.i(
                "PLAYER_DV",
                "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — HDR10 base layer: " +
                    "re-advertising as hvc1, rewriting VPS to single-layer HDR10, " +
                    "stripping DV RPU/EL + HDR10+ metadata NALs " +
                    "(nativeDv=$nativeDvSupported stripAll=$convertAllProfiles)"
            )
            // Building the stripped Format must never be able to fail the load.
            // This branch runs on the RESCUE path: the player has already seen
            // the hardware Dolby Vision decoder refuse this stream
            // (0x80001000), forced the strip on, and is relying on the retry
            // ladder to find something that works. Throwing out of format()
            // destroyed that: the rewritten Format construction raised
            // IllegalStateException inside VideoCompatTrackOutput, the load
            // task died as a SOURCE error (`Unexpected IllegalStateException`)
            // that the ladder has no case for, and the player rebuilt itself
            // forever with the identical failure — a DV file that could not be
            // played at all, on a box whose DV decoder was never going to
            // accept it. Degrade to "no rewrite" instead: pass the original
            // format through and let the ladder move on (another renderer,
            // the FFmpeg software path, the MPV engine).
            val stripped = runCatching {
                var builder = format.buildUpon().setCodecs(rewriteCodec)
                // Keep the original declared DV codec (e.g. "dvhe.08.06") on the
                // label so the player UI can badge the profile playing as HDR10.
                if (!format.codecs.isNullOrBlank()) {
                    builder = builder.setLabel(format.codecs)
                }
                if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                    builder = builder.setSampleMimeType(MimeTypes.VIDEO_H265)
                }
                builder = builder.setInitializationData(
                    rewriteInitData(format.initializationData)
                )
                // The rewritten format must carry explicit HDR10 color metadata,
                // same as the general strip path below — declared DV tracks don't
                // reliably have Format.colorInfo populated coming out of the
                // wrapping extractor chain, and buildUpon() only carries over
                // whatever was already there. Without KEY_COLOR_TRANSFER /
                // STANDARD / RANGE on the negotiated MediaFormat, MTK-class OMX
                // decoders can accept 10-bit input samples and never emit an
                // output frame — this is what was actually happening on P4/P8
                // sources, not a VPS problem (P8/P4 usually carry no VPS
                // extension to begin with, so vpsRewritten=false here is normal).
                builder.build().buildUpon().setColorInfo(hdr10ColorInfo).build()
            }
            val strippedFormat = stripped.getOrNull()
            if (strippedFormat != null) {
                delegate.format(strippedFormat)
                mode = Mode.STRIPPING
            } else {
                // Logged loudly and with the cause: the next capture has to show
                // that the rewrite was abandoned rather than a load failure.
                Log.w(
                    "PLAYER_DV",
                    "DV strip rewrite failed (${stripped.exceptionOrNull()?.javaClass?.simpleName}: " +
                        "${stripped.exceptionOrNull()?.message}) — playing the stream unchanged " +
                        "instead of failing the load",
                    stripped.exceptionOrNull()
                )
                mode = Mode.NORMAL
                delegate.format(format)
            }
            return
        }
        mode = when {
            // Declared Dolby Vision (dvcc present) with DV conversion enabled:
            // the "P7 → 8.1" mode rewrites only Profile 7 (via to81Codec above),
            // "Strip All" rewrites every profile (4/5/7/8). Strip from the
            // first sample and rewrite the codec string so Media3 never queries
            // a DV decoder. P5 (ICtCp) keeps this path: its pixels are not
            // HDR10, so the downstream GLES shader / FFmpeg conversion needs
            // the rewritten stream.
            dvRewrite != null -> Mode.STRIPPING
            // No DV marker in the codec string — plain HEVC might still carry
            // in-band RPU when the muxer omitted the dvcc box (remuxes). Sniff
            // the first samples for NAL type 62/63 — and for HDR10+ SEIs when
            // that toggle is on.
            isPlainHevc(format) -> {
                sniffRemaining = SNIFF_BUDGET_SAMPLES
                Mode.SNIFFING
            }
            else -> Mode.NORMAL
        }
        if (framing == NalFraming.LENGTH_DELIMITED) {
            nalLengthFieldLength = nalLengthFieldLength(format)
        }
        if (dvRewrite != null) {
            Log.i(
                "PLAYER_DV",
                "Declared Dolby Vision (codecs=${format.codecs ?: "?"}) — rewriting to HEVC HDR10"
            )
            // Profile 5 (dvhe.05/dvh1.05) is single-layer ICtCp with no HDR10
            // base layer, so the codec rewrite yields plain HEVC whose pixel
            // data is still ICtCp, not Rec.2020 PQ. The injected HDR10 color
            // metadata will at least make the display treat it as HDR rather
            // than falling back to washed-out SDR.
            //
            // For true P5→HDR10 color conversion, the ICtCp pixel data must be
            // converted to Rec.2020 PQ. This requires software decoding (FFmpeg)
            // with pixel-level color space conversion, since the hardware decoder
            // outputs ICtCp pixel values that the display interprets as Rec.2020
            // PQ (giving wrong colors). The FFmpeg path handles this via its
            // internal color space conversion when fed the correct input/output
            // colorspace parameters.
            //
            // The pixel-level conversion is applied by the FFmpeg renderer when
            // P5 content is detected — see NativePlayerActivity for the decoder
            // selection logic that forces FFmpeg for P5 streams.
            var builder = format.buildUpon().setCodecs(dvRewrite)
            // Keep the original declared DV codec (e.g. "dvhe.07.06") on the
            // rewritten format so the player UI can badge the exact profile
            // that was converted ("DV P7 → HDR10"). The decoder selection
            // only reads codecs / mime, so the label is safe metadata here.
            if (!format.codecs.isNullOrBlank()) {
                builder = builder.setLabel(format.codecs)
            }
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                builder = builder.setSampleMimeType(MimeTypes.VIDEO_H265)
            }
            builder = builder.setInitializationData(
                rewriteInitData(format.initializationData)
            )
            val rewritten = builder.build()
            // When DV is stripped, the resulting stream is plain HDR10 HEVC.
            // Inject correct HDR10 color metadata (ST.2084 PQ / BT.2020) so
            // the display treats it as HDR. For Profile 7 this matches the
            // original base layer; for Profile 5 (single-layer ICtCp) there is
            // no true HDR10 base, so this is best-effort — colors may be off
            // because the pixel data is still ICtCp, not Rec.2020 PQ, but
            // forcing HDR10 metadata at least avoids an SDR fallback with
            // fully washed-out colors.
            delegate.format(rewritten.buildUpon().setColorInfo(hdr10ColorInfo).build())
        } else {
            delegate.format(format)
        }
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean
    ): Int = sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (mode == Mode.NORMAL || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        ensurePendingCapacity(pendingLen + length)
        val read = input.read(pendingBuf, pendingLen, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read > 0) pendingLen += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int): Unit =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (mode == Mode.NORMAL || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        ensurePendingCapacity(pendingLen + length)
        data.readBytes(pendingBuf, pendingLen, length)
        pendingLen += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (mode == Mode.NORMAL || pendingLen == 0) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val carrySize = offset.coerceIn(0, pendingLen)
        val sampleEnd = pendingLen - carrySize

        fun emit(dataLen: Int) {
            scratch.reset(pendingBuf, dataLen)
            delegate.sampleData(scratch, dataLen)
            delegate.sampleMetadata(timeUs, flags, dataLen, 0, cryptoData)
        }

        if (mode == Mode.SNIFFING) {
            val dvFound = when (framing) {
                NalFraming.ANNEX_B ->
                    DolbyVisionCompat.sampleHasDvNalsAnnexB(pendingBuf, sampleEnd)
                NalFraming.LENGTH_DELIMITED ->
                    DolbyVisionCompat.sampleHasDvNalsLengthDelimited(
                        pendingBuf, sampleEnd, nalLengthFieldLength
                    )
            }
            if (dvFound && (dvRewriteEnabled || convertTo81)) {
                // Note: this branch is only reachable for a track whose
                // container declared no Dolby Vision at all (codecs hvc1/hev1,
                // mime video/hevc), so the platform's Dolby Vision pipeline is
                // never engaged for it — passing the in-band RPU/EL NALs
                // through instead is what makes some decoders re-emit their
                // output format on every frame (black screen with audio). The
                // rewrite below (single-layer VPS/SPS + metadata dropped) is
                // what keeps such a track decodable.
                mode = Mode.STRIPPING
                strippingTo81 = convertTo81
                val action = if (convertTo81) "converting to Profile 8.1" else "stripping to HDR10"
                // What the RPU actually is, according to the library that will
                // convert it. The sniff above only reads the NAL type byte, so a
                // report of "P7 -> 8.1 didn't render" needs this line to say
                // whether the file really is profile 7 (and so whether the
                // conversion, rather than the detection, is what to look at).
                // One JNI call, on the first sample of a track that is already
                // being rewritten; every later sample reuses the locked mode.
                val libdoviProfile = DolbyVisionCompat.sampleDvProfileFromLibdovi(pendingBuf, sampleEnd)
                val libdoviNote = when {
                    !DoviBridge.available -> "libdovi not in this build"
                    libdoviProfile > 0 -> "libdovi reads P$libdoviProfile"
                    else -> "libdovi parsed no RPU"
                }
                Log.i(
                    "PLAYER_DV",
                    "In-band Dolby Vision RPU detected (no dvcc marker, codecs=${currentCodecs ?: "?"}, $libdoviNote) — $action"
                )
                // This track's codec config was emitted untouched (it looked
                // like plain HEVC), so its VPS/SPS still declare the original
                // Dolby Vision structure. Rewrite them to single-layer HDR10
                // and re-emit the format now that in-band DV is confirmed — a
                // decoder that configured from a DV VPS but never receives RPUs
                // is exactly the stall that black-screens with audio.
                val fmt = lastFormat
                if (fmt != null && fmt.initializationData.isNotEmpty()) {
                    val rewrittenInit = rewriteInitData(fmt.initializationData)
                    if (rewrittenInit !== fmt.initializationData) {
                        Log.i(
                            "PLAYER_DV",
                            "Re-emitting format with rewritten single-layer HDR10 codec config " +
                                "(codecs=${fmt.codecs ?: "?"})"
                        )
                        delegate.format(fmt.buildUpon().setInitializationData(rewrittenInit).build())
                    }
                }
                // Fall through and strip this very sample: samples that contain
                // DV NALs are never forwarded untouched to the decoder.
            } else if (dvFound) {
                // DV conversion is off for this session (DV = Off) — the sniff
                // only exists to find HDR10+ SEIs. A DV remux has nothing to
                // do here: forward it untouched and stop sniffing.
                mode = Mode.NORMAL
                sniffRemaining = 0
                emit(sampleEnd)
                if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
                pendingLen = carrySize
                return
            } else {
                // No DV NALs in this sample. When HDR10+ stripping is enabled,
                // remove ST 2094-40 SEIs here too: plain-HDR10+ HEVC releases
                // (codecs hvc1/hev1, no DV at all) black-screen on HDR10+-
                // intolerant TVs the same way DV7 does on non-DV sets. Once an
                // HDR10+ SEI is seen, lock into stripping for the stream's life.
                if (stripHdr10Plus) {
                    val hdr10Cleaned = when (framing) {
                        NalFraming.ANNEX_B ->
                            DolbyVisionCompat.stripAnnexB(
                                pendingBuf, sampleEnd, stripDv = false, stripHdr10Plus = true
                            )
                        NalFraming.LENGTH_DELIMITED ->
                            DolbyVisionCompat.stripLengthDelimited(
                                pendingBuf, sampleEnd, nalLengthFieldLength,
                                stripDv = false, stripHdr10Plus = true
                            )
                    }
                    if (hdr10Cleaned >= 0) {
                        mode = Mode.STRIPPING
                        Log.i(
                            "PLAYER_DV",
                            "HDR10+ SEI detected on plain HEVC track (codecs=${currentCodecs ?: "?"}) — stripping to static HDR10"
                        )
                        emit(hdr10Cleaned)
                        if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
                        pendingLen = carrySize
                        return
                    }
                }
                // Verified clean (or budget exhausted) — forward untouched.
                sniffRemaining--
                if (sniffRemaining <= 0) mode = Mode.NORMAL
                emit(sampleEnd)
                if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
                pendingLen = carrySize
                return
            }
        }

        if (mode == Mode.STRIP_NALS_ONLY) {
            val firstSample = !stripReported
            val inventory =
                if (firstSample) DolbyVisionCompat.describeNals(pendingBuf, sampleEnd) else ""
            // Per-SEI accounting, first sample only - all later samples pass
            // null, so the steady state pays nothing for this.
            val accounting =
                if (firstSample) DolbyVisionCompat.SeiStripAccounting() else null
            // Pure removal — stripAnnexB/stripLengthDelimited never rewrite
            // parameter sets. The RPU/EL (stripDv) and the HDR10+ SEI
            // (stripHdr10Plus) are BOTH dropped, which is the combination the
            // field has: the old keep-the-RPU theory here — "a DV-flagged track
            // with no DV metadata stalls the decoder" — was only ever run
            // against the wrong codec string (arm A predates the SPS-derived
            // one), while the reference player's Always Strip DV plays this
            // chipset RPU-dropped with a correct codec string. The `dropRpuNal` arm
            // (RPU alone, HDR10+ SEI kept) is a different caller's config and
            // is not passed here.
            //
            // Two callers engage this mode: the Auto non-DV-display relabel
            // and the DV-hardware reroute in the strip branch, both of which
            // re-advertise the codec before their samples arrive here. The
            // stripping is identical for the two, so the log below names no
            // codec source — the format line above it says which ran. The
            // removal itself is libdovi's now (see
            // [DolbyVisionCompat.stripAnnexB]), which the log's "(via libdovi)"
            // marker below records.
            val stripped = when (framing) {
                NalFraming.ANNEX_B ->
                    DolbyVisionCompat.stripAnnexB(
                        pendingBuf, sampleEnd, stripDv = true, // drop RPU/EL, like the reference
                        stripHdr10Plus = true,
                        accounting = accounting
                    )
                NalFraming.LENGTH_DELIMITED ->
                    DolbyVisionCompat.stripLengthDelimited(
                        pendingBuf, sampleEnd, nalLengthFieldLength,
                        stripDv = true, // drop RPU/EL, like the reference
                        stripHdr10Plus = true,
                        accounting = accounting
                    )
            }
            if (firstSample) {
                stripReported = true
                // The old line asserted "HDR10+ SEI dropped" whether or not
                // anything was removed. This one reports what actually
                // happened: whether the sample was modified at all, and the
                // fate of each SEI NAL - dropped whole, surgically shrunk, or
                // forwarded bit-exact. That distinction is what says whether a
                // stall is the removal or the relabeled stream itself.
                Log.i(
                    "PLAYER_DV",
                    "NALS-strip: first sample (codecs=${currentCodecs ?: "?"}) " +
                        "modified=${stripped >= 0} nals=$inventory " +
                        "sei=${accounting?.summary()?.ifEmpty { "none" } ?: "none"}" +
                        // Which stripper produced these bytes: libdovi reports
                        // no per-NAL detail, so its entries are categories and
                        // the marker is what says so.
                        if (accounting?.viaLibdovi == true) " (via libdovi)" else ""
                )
            }
            // No parameter set is rewritten here: this mode is the sample-side
            // half of the relabel, whose codec config is what carries the
            // rewritten VPS (see relabelTargetFor). The samples themselves get
            // nothing but the removal above.
            val outLen = if (stripped >= 0) stripped else sampleEnd
            emit(outLen)
            if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
            pendingLen = carrySize
            return
        }

        val stats = DolbyVisionCompat.StripStats()
        // Inventory BEFORE the in-place transform so the log shows what the
        // decoder would have seen untouched.
        val inventory =
            if (!stripReported) DolbyVisionCompat.describeNals(pendingBuf, sampleEnd) else ""
        // Every rewrite this file performs — the plain strip to HDR10 AND the
        // Profile 8.1 conversion — outputs a single layer of static metadata,
        // so HDR10+ (ST 2094-40) SEI NALs are dropped unconditionally: keeping
        // them next to a DV RPU means two competing dynamic-metadata sets on a
        // stream that is now static HDR10, and HDR10+-intolerant TVs
        // black-screen on the SEIs just like on DV. The separate "Strip HDR10+"
        // toggle still governs plain-HDR10+ (non-DV) streams via the sniff path,
        // which is the only place the toggle is consulted now.
        val effectiveStripHdr10Plus = true
        if (!stripReported) {
            Log.i(
                "PLAYER_DV",
                "DV conversion — stripping HDR10+ SEI from samples (a rewritten stream " +
                    "carries static HDR10 either way, so the toggle is not consulted here)"
            )
        }
        val stripped = if (strippingTo81) {
            when (framing) {
                NalFraming.ANNEX_B ->
                    DolbyVisionCompat.transformAnnexBTo81(
                        pendingBuf, sampleEnd, stripHdr10Plus = effectiveStripHdr10Plus, stats = stats
                    )
                NalFraming.LENGTH_DELIMITED ->
                    DolbyVisionCompat.transformLengthDelimitedTo81(
                        pendingBuf, sampleEnd, nalLengthFieldLength,
                        stripHdr10Plus = effectiveStripHdr10Plus, stats = stats
                    )
            }
        } else {
            when (framing) {
                NalFraming.ANNEX_B ->
                    DolbyVisionCompat.transformAnnexB(
                        pendingBuf, sampleEnd, stripHdr10Plus = effectiveStripHdr10Plus, stats = stats
                    )
                NalFraming.LENGTH_DELIMITED ->
                    DolbyVisionCompat.transformLengthDelimited(
                        pendingBuf, sampleEnd, nalLengthFieldLength,
                        stripHdr10Plus = effectiveStripHdr10Plus, stats = stats
                    )
            }
        }
        if (stripped >= 0 && !stripReported) {
            stripReported = true
            val vpsFail = stats.vpsRewriteFailedReason?.let { " vpsFail=$it" } ?: ""
            val spsFail = stats.spsRewriteFailedReason?.let { " spsFail=$it" } ?: ""
            Log.i(
                "PLAYER_DV",
                if (strippingTo81) {
                    "First 8.1-converted sample (codecs=${currentCodecs ?: "?"}) — " +
                        "RPU rewritten=${stats.rpuRewritten} EL=${stats.elBytes}B " +
                        "HDR10+SEI=${stats.hdr10PlusBytes}B vpsRewritten=${stats.vpsRewritten}$vpsFail " +
                        "initDataRewritten=$initDataRewritten spsRewritten=${stats.spsRewritten}$spsFail " +
                        "nals=$inventory"
                } else {
                    "First stripped sample (codecs=${currentCodecs ?: "?"}) — " +
                        "dropped RPU=${stats.rpuBytes}B EL=${stats.elBytes}B HDR10+SEI=${stats.hdr10PlusBytes}B " +
                        "vpsRewritten=${stats.vpsRewritten}$vpsFail initDataRewritten=$initDataRewritten " +
                        "spsRewritten=${stats.spsRewritten}$spsFail nals=$inventory"
                }
            )
        }
        emit(if (stripped >= 0) stripped else sampleEnd)

        if (carrySize > 0) System.arraycopy(pendingBuf, sampleEnd, pendingBuf, 0, carrySize)
        pendingLen = carrySize
    }

    /** Rewrites codec-private VPS/SPS to single-layer HDR10, tracking whether
     * the config actually changed so the first-sample log reports it even for
     * containers whose samples carry no parameter sets (MP4/fMP4). */
    private fun rewriteInitData(initData: List<ByteArray>): List<ByteArray> {
        val rewritten = DolbyVisionCompat.rewriteInitDataVps(initData)
        if (rewritten !== initData) initDataRewritten = true
        return rewritten
    }

    private fun isPlainHevc(format: Format): Boolean {
        if (format.sampleMimeType == MimeTypes.VIDEO_H265) return true
        val codecs = format.codecs
        return !codecs.isNullOrBlank() && PLAIN_HEVC_CODEC.containsMatchIn(codecs.trim())
    }

    private fun ensurePendingCapacity(need: Int) {
        if (pendingBuf.size >= need) return
        var newSize = if (pendingBuf.isEmpty()) 16 * 1024 else pendingBuf.size
        while (newSize < need) newSize = newSize shl 1
        pendingBuf = pendingBuf.copyOf(newSize)
    }

    private fun nalLengthFieldLength(format: Format): Int {
        val csd = format.initializationData.firstOrNull() ?: return 4
        if (csd.size <= 21) return 4
        if (csd[0].toInt() != 1) return 4
        return (csd[21].toInt() and 0x03) + 1
    }

    private companion object {
        private val PLAIN_HEVC_CODEC = Regex("(?i)^(hvc1|hev1)\\.")
        private const val SNIFF_BUDGET_SAMPLES = 24
    }
}

/**
 * HDR10 color metadata (ST.2084 PQ / BT.2020, 10-bit) for every DV→HDR10 and
 * relabel path in this file.
 *
 * Declared DV tracks don't reliably carry a populated Format.colorInfo through
 * the wrapping extractor chain, and without KEY_COLOR_TRANSFER / STANDARD /
 * RANGE on the negotiated MediaFormat, some hardware decoders (MTK-class OMX
 * components in particular) accept 10-bit input samples but never emit an
 * output frame — black screen with audio still playing, indistinguishable from
 * the VPS-stall failure this file already guards against. Every rewrite branch
 * attaches it; the minimal relabel fallback knowingly ships without it, because
 * a degraded relabel still beats a native-DV black screen.
 *
 * The relabel path does NOT attach this value bare — it goes through
 * [dvHdr10ColorInfoFor], which layers the container's own [ColorInfo.hdrStaticInfo]
 * (ST 2086 mastering display colour volume / MaxCLL-MaxFALL) onto it.
 */
internal val DV_HDR10_COLOR_INFO: ColorInfo = ColorInfo.Builder()
    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
    .setColorSpace(C.COLOR_SPACE_BT2020)
    .setColorRange(C.COLOR_RANGE_LIMITED)
    .setLumaBitdepth(10)
    .setChromaBitdepth(10)
    .build()

/**
 * The relabel's color info: [DV_HDR10_COLOR_INFO] with the source format's
 * HDR10 static metadata carried over when the container declared it, and the
 * bare forced value when it did not.
 *
 * The transfer / space / range / bitdepth are ours to force — they are the
 * values proven correct for a track re-advertised as plain HDR10 HEVC. The
 * static metadata is not: it is the stream's own ST 2086 mastering display
 * colour volume and content light level, and the reference config on this
 * chipset preserves the container's full colorInfo including it. Since the
 * 2026-10-11 field capture had every other visible parameter (RPU dropped,
 * SPS-derived hvc1.2.4.H150.B0, HDR10+ SEI stripped) matching it and still
 * stalled with zero decoder output, dropping the static metadata was the
 * remaining difference — a decoder that wants it can accept input and emit no
 * frame. `Format.buildUpon()` carries the field, but the relabel REPLACES the
 * whole color info, so it has to be re-attached explicitly here.
 */
internal fun dvHdr10ColorInfoFor(format: Format): ColorInfo {
    val static = format.colorInfo?.hdrStaticInfo
    if (static == null) return DV_HDR10_COLOR_INFO
    val base = DV_HDR10_COLOR_INFO
    return ColorInfo.Builder()
        .setColorTransfer(base.colorTransfer)
        .setColorSpace(base.colorSpace)
        .setColorRange(base.colorRange)
        .setLumaBitdepth(base.lumaBitdepth)
        .setChromaBitdepth(base.chromaBitdepth)
        .setHdrStaticInfo(static)
        .build()
}

/** Which relabel attempt produced the format a session will play. */
internal enum class RelabelAttempt { FULL, MINIMAL }

/** A relabeled format plus the attempt that built it, for the success log. */
internal class RelabelOutcome(val format: Format, val attempt: RelabelAttempt)

/** What the relabel is handed: the source format (or its VPS-rewritten form)
 * plus whether that rewrite happened, for the success log — and whether the
 * relabel itself is being skipped, which is the 2026-10-14 test (see
 * [relabelTargetFor]). */
internal class RelabelTarget(
    val format: Format,
    val vpsRewritten: Boolean,
    /**
     * True when the whole relabel is skipped: [format] is the source format
     * exactly as it arrived, declared codec string included, and the caller
     * must not hand it to [relabelDvFormat].
     */
    val relabelSkipped: Boolean = false
)

/**
 * The relabel log's verdict on the codec config's VPS, for the field log.
 *
 * Three outcomes, not two, because `vpsRewritten=false` on its own cannot be
 * read as "the rewrite was skipped": a P8 track usually carries no VPS
 * extension at all, and a rewrite that threw is contained to the same value
 * (see [relabelTargetFor]). A log that called either of those "skipped(libdovi)"
 * would send a field run hunting for libdovi in a build that has it, or the
 * other way round — the 2026-10-13 capture is read off exactly this field.
 */
internal fun vpsRewriteOutcome(skippedForLibdovi: Boolean, rewritten: Boolean): String = when {
    skippedForLibdovi -> "skipped(libdovi)"
    rewritten -> "applied"
    else -> "not-needed"
}

/**
 * The relabel log's verdict on the codec-string relabel, for the field log.
 *
 * The 2026-10-14 field run reads this field to tell which path ran: the
 * declared `dvhe.08.06` left on the format because libdovi did the strip and
 * nothing re-advertised the track, or the `hvc1` relabel this app has always
 * shipped. It is a separate field from `vpsRewrite` because the two are
 * independent decisions — the VPS skip already ran on its own (0.6.71) and the
 * picture was still solid green, which is what leaves the relabel itself as
 * the candidate this test is bisecting.
 */
internal fun relabelOutcome(skippedForLibdovi: Boolean): String =
    if (skippedForLibdovi) "skipped(libdovi-test)" else "applied"

/**
 * The format the relabel is built from: [format] with the Dolby Vision VPS
 * rewritten to a clean single-layer parameter set in its codec private data.
 *
 * The relabel itself only re-advertises the track (codec string, mime, color
 * info) and leaves the codec config bit-exact, so a DV-flagged VPS rides on to
 * the decoder that way - and a decoder which configured from it then waits for
 * the RPUs the relabel drops, which is the zero-output stall this exists to
 * close. [DolbyVisionCompat.rewriteVpsInInitData] rewrites the VPS alone: the
 * SPS/PPS stay byte-identical (the advertised codec string is derived from
 * that SPS) and so does every sample.
 *
 * Every step is contained: a format that cannot be rebuilt is handed to the
 * relabel unchanged with `vpsRewritten=false`, exactly as before this existed,
 * rather than failing a load that must never fail.
 *
 * [skipVpsRewrite] is the Nuvio-parity arm: it is what every caller whose
 * samples are stripped by libdovi passes (see the two relabel call sites and
 * [Mode.STRIP_NALS_ONLY]). Nuvio's `stripRpuOnly` strips the samples and leaves
 * the codec config alone, and the reference transformer it is modelled on does
 * the same. The 2026-10-13 field run is why that matters — libdovi stripped the
 * RPU/EL/HDR10+ and the decoder rendered its first frame, but the config's
 * rewritten VPS on top of that strip produced a solid green picture at 62 kbps.
 * The rewrite is not wrong for every case: it is what closes the zero-output
 * stall of the app's OWN sample stripper, so it is the default behaviour here
 * and every caller on the Kotlin fallback keeps it. Skipping it hands the
 * caller the source format itself, which is what makes the config
 * byte-identical rather than "rebuilt with the same bytes".
 *
 * [skipRelabel] is the 2026-10-14 test, and the LAST difference from Nuvio's
 * `stripRpuOnly`: that reference never re-advertises the track, so the declared
 * `dvhe.08.06` stays on the format while the library strips the samples. The
 * VPS skip landed first (0.6.71, `vpsRewrite=skipped(libdovi)` in the field
 * log) and the picture still came up solid green, which leaves the `hvc1`
 * re-advertisement itself as the remaining candidate — the decoder is told
 * clean HEVC while the stripped stream still carries DV-shaped structure the
 * relabel claims is not there. It subsumes [skipVpsRewrite] rather than
 * stacking with it, because a track the app leaves declared is a track whose
 * codec config it has to leave alone too: this returns the source format
 * itself and reports [RelabelTarget.relabelSkipped], and the caller keeps the
 * library's strip. The call sites gate it on [DoviBridge.available] exactly as
 * they gate the VPS skip, and the relabel log's `relabel=` field says which of
 * the two ran.
 */
internal fun relabelTargetFor(
    format: Format,
    rewriteVps: (List<ByteArray>) -> List<ByteArray> = {
        DolbyVisionCompat.rewriteVpsInInitData(it)
    },
    skipVpsRewrite: Boolean = false,
    skipRelabel: Boolean = false
): RelabelTarget {
    // One decision, not two flags that could ever disagree: a declared track
    // keeps its declared codec config.
    if (skipRelabel) return RelabelTarget(format, false, relabelSkipped = true)
    if (format.initializationData.isEmpty()) return RelabelTarget(format, false)
    if (skipVpsRewrite) {
        // Nuvio parity: the VPS/SPS/PPS stay byte-identical and only the samples
        // are stripped. The relabel still re-advertises the track as hvc1.
        return RelabelTarget(format, false)
    }
    val rewritten = runCatching { rewriteVps(format.initializationData) }.getOrNull()
        ?: return RelabelTarget(format, false)
    if (rewritten === format.initializationData) return RelabelTarget(format, false)
    val patched = runCatching {
        format.buildUpon().setInitializationData(rewritten).build()
    }.getOrNull() ?: return RelabelTarget(format, false)
    return RelabelTarget(patched, true)
}

/**
 * The minimal relabel: codec string and mime only — no badging label, no color
 * metadata. It is the fallback when the full relabel's construction throws, so
 * a degraded relabel still ships instead of falling back to native Dolby Vision
 * (which black-screens a non-DV sink).
 */
internal fun relabelDvMinimal(format: Format, rewriteCodec: String): Format {
    val builder = format.buildUpon().setCodecs(rewriteCodec)
    if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
        builder.setSampleMimeType(MimeTypes.VIDEO_H265)
    }
    return builder.build()
}

/**
 * The full relabel: the same codecs/mime as [relabelDvMinimal], plus the
 * declared DV codec kept on the label (so the UI can still badge the source
 * profile) and the forced HDR10 color info attached — with the source format's
 * hdrStaticInfo carried over when the container declared it (see
 * [dvHdr10ColorInfoFor]). The construction never touches samples, and the codec
 * config it is handed is whatever the caller prepared — see [relabelTargetFor],
 * which is where the relabel path's VPS rewrite happens.
 *
 * [applyColorInfo] is the color-info step, injectable so a unit test can drive
 * the throwing path this hardening exists for.
 */
internal fun relabelDvFull(
    format: Format,
    rewriteCodec: String,
    applyColorInfo: (Format.Builder) -> Format.Builder = {
        it.setColorInfo(dvHdr10ColorInfoFor(format))
    }
): Format {
    var builder = format.buildUpon().setCodecs(rewriteCodec)
    // Keep the declared DV codec on the label for badging.
    if (!format.codecs.isNullOrBlank()) {
        builder = builder.setLabel(format.codecs)
    }
    if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
        builder = builder.setSampleMimeType(MimeTypes.VIDEO_H265)
    }
    return applyColorInfo(builder.build().buildUpon()).build()
}

/**
 * Relabels a declared P4/P8 track as plain HEVC, stepwise.
 *
 * [relabelDvFull] first; if its construction throws, [relabelDvMinimal] is
 * retried, which both recovers the working relabel and identifies the throwing
 * step by which attempt succeeds. If both throw, nothing is relabeled and the
 * caller plays the stream unchanged — a relabel failure must never fail the
 * load.
 *
 * @return the relabeled format and the attempt that produced it, or null when
 * both attempts threw.
 */
internal fun relabelDvFormat(
    format: Format,
    rewriteCodec: String,
    applyColorInfo: (Format.Builder) -> Format.Builder = {
        it.setColorInfo(dvHdr10ColorInfoFor(format))
    },
    minimal: (Format, String) -> Format = { f, codec -> relabelDvMinimal(f, codec) },
    warn: (String, Throwable) -> Unit = { message, cause -> Log.w("PLAYER_DV", message, cause) }
): RelabelOutcome? {
    val full = runCatching { relabelDvFull(format, rewriteCodec, applyColorInfo) }
    if (full.isSuccess) return RelabelOutcome(full.getOrThrow(), RelabelAttempt.FULL)
    // Defensive: a failed Result's exceptionOrNull() is non-null by contract, so
    // this branch is unreachable in practice - but an early-return refactor above
    // must never be able to turn it into an NPE crash mid-playback. Failing safe
    // here means "play the stream unchanged", the same answer as both attempts
    // throwing.
    val fullFailure = full.exceptionOrNull() ?: run {
        warn(
            "DV relabel full failed with null exception (unexpected)",
            IllegalStateException("Result was failure but exceptionOrNull() was null")
        )
        return null
    }
    warn(
        "DV relabel full construction failed " +
            "(${fullFailure.javaClass.simpleName}: ${fullFailure.message}), " +
            "retrying minimal relabel",
        fullFailure
    )
    val minimalAttempt = runCatching { minimal(format, rewriteCodec) }
    if (minimalAttempt.isSuccess) {
        return RelabelOutcome(minimalAttempt.getOrThrow(), RelabelAttempt.MINIMAL)
    }
    // Same defensive guard as the full attempt above; see that comment.
    val minimalFailure = minimalAttempt.exceptionOrNull() ?: run {
        warn(
            "DV relabel minimal failed with null exception (unexpected)",
            IllegalStateException("Result was failure but exceptionOrNull() was null")
        )
        return null
    }
    warn(
        "DV relabel minimal construction failed " +
            "(${minimalFailure.javaClass.simpleName}: ${minimalFailure.message}) — " +
            "playing the stream unchanged instead of failing the load",
        minimalFailure
    )
    return null
}

