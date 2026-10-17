package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relabel-only experiment (DV-decoder box, non-DV display, Auto) must strip
 * the DV RPU/EL and the HDR10+ SEI in place and leave every other parameter set
 * alone. The VPS rewrite is the step after the RPU drop: a decoder that
 * configured from a DV-flagged VPS then waits for metadata that never arrives,
 * which is the zero-output stall, and that rewrite used to be unreachable for
 * MKV, whose codec config is not framed the way the MP4 path assumed. Keeping
 * `transformAnnexB` (which rewrites the VPS in-sample as well) out of the arm
 * is what keeps the field result attributable to the config VPS alone.
 *
 * As of the 2026-10-13 field run (Fire TV Stick 4K Max, non-DV TV, Auto AND
 * Strip All: first frame rendered, then a solid green picture at 62 kbps) the
 * config rewrite became conditional instead of unconditional — it is exactly
 * the difference from Nuvio's stripRpuOnly, which never touches the codec
 * config. That gate is gone now: both relabel sites force the rewrite
 * (`skipVpsRewrite = false`), which is the same decision the mutating strip
 * below has always made. The 2026-10-15 field report is why — a declared
 * `dvhe.08.06` track came back with `vpsRewrite=skipped(libdovi)` and no
 * picture, the RPUs removed while the VPS still described Dolby Vision, which is
 * the zero-output stall the rewrite exists to close — and the green-screen run
 * the skip was measured against stripped with libdovi, which neither relabel
 * site does any more (both run the Kotlin surgery, `kotlinOnly`). So the rule is
 * unconditional on both arms: once the RPU is gone the track is HEVC/HDR10 in
 * the label and in the parameter set, whichever mode got us there. Both sites'
 * decision and both relabel log fields are pinned here.
 *
 * The 2026-10-14 no-relabel run is where that stopped. It left the track
 * declared as `video/dolby-vision` while the RPU NALs were stripped, and it
 * field-failed the same night (Fire TV Stick 4K Plus, P8 MKV, mode=3 Strip
 * All): the MediaTek DV decoder stayed in DV mode waiting for RPUs that never
 * arrive and produced no frame at all (`bitrateInKbps = 0`), with the surface
 * reset + TextureView retry stalling identically. Strip-RPU + keep-DV-label is
 * the poisoned combination the relabel exists to leave, so both sites now force
 * the relabel (`skipRelabel = false`) and still run Mode.STRIP_NALS_ONLY — the
 * sample strip stays, now the Kotlin surgery (`kotlinOnly = true`) rather than
 * the library's coarse one, since the 2026-10-14 run also pinned libdovi's
 * whole-SEI drop as the green-screen cause. The VPS skip, both log fields and
 * the "strip anyway" half are pinned here, because a pin on the log alone would
 * not notice the strip being dropped along with the relabel.
 * Dropping the RPU as well as the SEI is
 * what the reference player's "Always Strip DV" plays this chipset with, and the earlier arm
 * that kept the RPU only ever ran with the wrong codec string — see the source
 * comment on `Mode.STRIP_NALS_ONLY`.
 *
 * The branch lives inside a private `TrackOutput`, so this reads the source and
 * pins the wiring — the same pattern the other source-contract tests use.
 */
class DolbyVisionRelabelNalsStripContractTest {

    private val source: String by lazy {
        val file = File(findSourceRoot(), EXTRACTOR)
        assertTrue("source missing: $file", file.isFile)
        file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    /**
     * The shared relabel construction: the call plus its two fixed arguments,
     * whitespace-tolerant.
     *
     * The codec string handed to the relabel is the SPS-derived one when the
     * track's parameter sets parse (see hevcCodecFromInitData) and rewriteCodec
     * only as the fallback, so the variable at both sites is `relabelCodec`;
     * the format is the VPS-rewritten one from relabelTargetFor.
     * The pin is on the call and the arguments that must be identical at both
     * sites, not on one line's spelling. A site that stopped going through
     * `relabelDvFormat` still fails, and both sites are counted independently.
     */
    private val relabelCall =
        Regex("relabelDvFormat\\(\\s*relabel\\.format,\\s*relabelCodec\\b")

    /** The `RELABEL_ONLY` branch body, up to the strip path's log line. */
    private fun relabelBranch(): String {
        val start = source.indexOf("if (handling == Hdr10BaseHandling.RELABEL_ONLY) {")
        assertTrue("the RELABEL_ONLY branch not found", start >= 0)
        val end = source.indexOf("HDR10 base layer: ", start)
        assertTrue("the RELABEL_ONLY branch end not found", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `the declared-DV suffix SEI rule is armed at every strip call that declared Dolby Vision`() {
        assertEquals(
            "one per framing, and only where the container declared Dolby Vision",
            2,
            count(source, "declaredDv = true")
        )
        assertEquals(
            "and the MUTATING strip takes the same rule, one call per framing, gated on the " +
                "track the container flagged - Fix F8, for the 2026-10-15 K83 / NORViNE file, " +
                "which is the declared-DV arrival at that mode and never reached the relabel",
            2,
            count(source, "declaredDv = mutatingStripDeclaredDv")
        )
        assertFalse(
            "the parameter has to default to off: the in-band path shares this stripper, " +
                "and the audit's rule is explicitly not to touch its NAL 40 handling",
            source.contains("declaredDv: Boolean = true")
        )
    }

    @Test
    fun `each first-sample audit line is emitted with its fix named`() {
        assertEquals(
            "two call sites (the relabel modes' strip, the in-band transform) plus the " +
                "definition",
            3,
            count(source, "logFirstSampleAudits(")
        )
        assertEquals(
            "BOTH modes now rewrite the in-sample parameter sets: the mutating strip always " +
                "did, and the relabel modes' NALS-strip does as of Fix 1 (2026-10-16) - the " +
                "declared-DV P8 field file whose config VPS was rewritten while its first " +
                "sample still carried the original DV-flagged set",
            2,
            count(source, "rewritesInSampleParamSets = true")
        )
        assertFalse(
            "so no call site may report that it leaves the samples' parameter sets alone: " +
                "that is what made PARAM-AUDIT print the set as a survivor on the failing file",
            source.contains("rewritesInSampleParamSets = false")
        )
        assertEquals(
            "and Fix 1's agreement itself is armed on both framings of that strip",
            2,
            count(source, "rewriteParamSets = true")
        )
        assertEquals(
            "the relabel modes' strip call arms the suffix-SEI rule unconditionally: that " +
                "container declared Dolby Vision by definition",
            1,
            count(source, "dvSuffixSeiRuleActive = true")
        )
        assertEquals(
            "and the mutating path arms it on the same fact (Fix F8), so the in-band sniff " +
                "still reads false through the same flag",
            1,
            count(source, "dvSuffixSeiRuleActive = mutatingStripDeclaredDv")
        )
        assertFalse(
            "no call site may hard-code the rule off any more: that is exactly what left a " +
                "declared-DV mutating strip without it",
            source.contains("dvSuffixSeiRuleActive = false")
        )
        assertEquals(
            "and the strip's own first-sample line reports the F8 count, so a field log says " +
                "whether the rule ran and matched anything",
            1,
            count(source, "dvSei=")
        )
        listOf(
            "NALS-bounds (Fix C)",
            "SEI-INVENTORY (Fix A/B",
            "PARAM-AUDIT (Fix D"
        ).forEach { tag ->
            assertTrue(
                "each fix has to be attributable on its own line: $tag",
                source.contains(tag)
            )
        }
    }

    @Test
    fun `both relabel arms force the relabel and the VPS rewrite`() {
        // H11 / Fix E, on both arms rather than the Auto one alone: the skip
        // parameters exist but no site takes them, and in particular nothing
        // gates on the native library being present - that rationale is stale,
        // because these arms' samples are the Kotlin stripper's now.
        assertEquals("one per arm", 2, count(source, "val skipRelabel = false"))
        assertEquals("one per arm", 2, count(source, "val skipVpsRewrite = false"))
        assertFalse("no arm may skip the relabel any more", source.contains("skipRelabel = true"))
        assertFalse("nor the VPS rewrite", source.contains("skipVpsRewrite = true"))
        assertFalse(
            "and the native library's presence may not decide it",
            source.contains("skipRelabel = DoviBridge.available")
        )
    }

    @Test
    fun `the new mode exists and the relabel branch engages it`() {
        assertTrue(
            "Mode.STRIP_NALS_ONLY must exist so the experiment cannot be silently " +
                "rerouted through transformAnnexB",
            source.contains("STRIP_NALS_ONLY")
        )
        assertTrue(
            "the RELABEL_ONLY branch must engage Mode.STRIP_NALS_ONLY",
            relabelBranch().contains("mode = Mode.STRIP_NALS_ONLY")
        )
    }

    @Test
    fun `the relabel branch forces the codec config VPS rewrite`() {
        val branch = relabelBranch()
        assertTrue(
            "the relabel must be built through relabelTargetFor — that is where the config " +
                "VPS rewrite happens, and it has to reach the relabel from somewhere",
            branch.contains(RELABEL_CALL)
        )
        assertTrue(
            "and the 2026-10-14 no-relabel test is off: the relabel runs unconditionally, " +
                "because strip-RPU + keep-DV-label is the zero-output stall",
            branch.contains("val skipRelabel = false")
        )
        assertTrue(
            "and the skip must be off: this arm's samples are the Kotlin stripper's, and a " +
                "DV VPS left on a track whose RPUs were removed is the decoder stall the " +
                "rewrite closes (2026-10-15)",
            branch.contains("val skipVpsRewrite = false")
        )
        assertTrue(
            "and the relabel itself must be built from that format",
            relabelCall.containsMatchIn(branch)
        )
        assertFalse(
            "the mutating strip's config rewrite takes the SPS with it, so the relabel " +
                "may not be routed through it",
            branch.contains("rewriteInitData(")
        )
        assertTrue(
            "the field run reads vpsRewritten off the line to tell a rewritten config " +
                "from one that was never touched",
            branch.contains("vpsRewritten=\${relabel.vpsRewritten}")
        )
    }

    @Test
    fun `the new mode strips metadata NALs without transforming the stream`() {
        val start = source.indexOf("if (mode == Mode.STRIP_NALS_ONLY) {")
        assertTrue("the STRIP_NALS_ONLY handler not found", start >= 0)
        val end = source.indexOf("val stats = DolbyVisionCompat.StripStats()", start)
        assertTrue("the STRIP_NALS_ONLY handler end not found", end > start)
        val handler = source.substring(start, end)
        assertFalse(
            "the handler must not keep the DV RPU: RPU-dropped is the combination " +
                "that has picture",
            handler.contains("stripDv = false")
        )
        // Both framing branches, not whichever one a grep happened to hit first.
        assertEquals(
            "BOTH the Annex-B and the length-delimited call must drop the RPU/EL",
            2,
            Regex("stripDv = true,").findAll(handler).count()
        )
        assertTrue(
            "the handler must drop the HDR10+ SEI (the stream is presented as static " +
                "HDR10), dropping the RPU/EL",
            handler.contains("stripHdr10Plus = true")
        )
        assertEquals(
            "and it must run the Kotlin surgery (Nuvio parity), not libdovi's coarse " +
                "whole-SEI drop",
            2,
            Regex("kotlinOnly = true,").findAll(handler).count()
        )
        assertFalse(
            "the handler must be pure removal, never the in-sample transform",
            handler.contains("transformAnnexB") || handler.contains("transformLengthDelimited")
        )
        assertFalse(
            "the handler must never rewrite the VPS/SPS",
            handler.contains("rewriteInitData")
        )
    }

    @Test
    fun `the first-sample relabel log reports what happened, not what was expected`() {
        // The old line said "first sample HDR10+ SEI dropped" whether or not
        // anything was removed, so the 2026-10-10 stall could not be read off
        // it: the 39:69 prefix-SEI NAL may have gone whole, been shrunk, or
        // ridden through untouched, and all three logged identically. It now
        // reports whether the sample was modified at all, plus the fate of each
        // SEI NAL the strip examined (see SeiStripAccounting).
        assertFalse(
            "the unconditional drop claim must be gone - it asserted a removal that may not " +
                "have happened",
            source.contains("first sample HDR10+ SEI dropped")
        )
        assertTrue(
            "the line reports whether the sample changed at all",
            source.contains("modified=\${stripped >= 0}")
        )
        assertTrue(
            "and the per-SEI accounting, with the nothing-to-report case spelled out",
            source.contains("sei=\${accounting?.summary()?.ifEmpty { \"none\" } ?: \"none\"}")
        )
        assertTrue(
            "the accounting exists for the first sample only, so later samples pay nothing",
            source.contains("if (firstSample) DolbyVisionCompat.SeiStripAccounting() else null")
        )
        assertTrue(
            "and it is handed to the strip, which is what fills it in",
            source.contains("accounting = accounting")
        )
    }

    // ── the DV-hardware reroute out of the mutating strip ─────────────
    //
    // The mutating strip (VPS rewrite + RPU/EL removal) is field-dead on
    // DV-decoder hardware: the decoder stalls with zero output frames. P4/P8
    // must take the proven relabel path there instead — the same relabel the
    // RELABEL_ONLY branch uses — while boxes WITHOUT a DV decoder keep the
    // tested mutating strip untouched.

    @Test
    fun `DV hardware in the strip branch is rerouted before the VPS rewrite`() {
        val start = source.indexOf("if (hdr10BaseLayerProfile && nativeDvSupported) {")
        assertTrue("the DV-hardware reroute gate not found", start >= 0)
        val end = source.indexOf("HDR10 base layer: ", start)
        assertTrue("the mutating-strip log not found after the gate", end > start)
        val reroute = source.substring(start, end)
        assertTrue(
            "the reroute must use the shared relabel construction",
            relabelCall.containsMatchIn(reroute)
        )
        assertTrue(
            "and engage Mode.STRIP_NALS_ONLY (stripDv=true, stripHdr10Plus=true)",
            reroute.contains("mode = Mode.STRIP_NALS_ONLY")
        )
        assertTrue(
            "the reroute's relabel must carry the SPS-derived codec string",
            reroute.contains("relabelDvFormat(relabel.format, relabelCodec)")
        )
        assertTrue(
            "and be built through the same gated relabelTargetFor as the Auto branch",
            reroute.contains(RELABEL_CALL)
        )
        assertTrue(
            "with the same removed no-relabel test as the Auto branch — the relabel runs " +
                "unconditionally",
            reroute.contains("val skipRelabel = false")
        )
        assertTrue(
            "and, unlike the Auto branch, forcing the config VPS rewrite instead of " +
                "skipping it: this is where Strip All lands, its samples are the Kotlin " +
                "surgery's, and a skipped rewrite leaves the DV VPS on a track whose RPUs " +
                "are gone (the 2026-10-15 declared-DV stall)",
            reroute.contains("val skipVpsRewrite = false")
        )
        assertTrue(
            "and report the VPS rewrite, so both relabel logs can be compared",
            reroute.contains("vpsRewritten=\${relabel.vpsRewritten}")
        )
        assertFalse(
            "the mutating strip's rewrite (which also takes the SPS) stays out of the reroute",
            reroute.contains("rewriteInitData(")
        )
    }

    @Test
    fun `the relabel construction is shared by both call sites`() {
        val calls = relabelCall.findAll(source).count()
        assertEquals(
            "the RELABEL_ONLY branch and the DV-hardware reroute must share it",
            2,
            calls
        )
    }

    // ── the 2026-10-10 construction failure hardening ─────────────────
    //
    // A field capture had the relabel construction throw, and the branch had
    // already logged "re-advertising as hvc1" on entry — so it announced a
    // relabel and then played native DV, which black-screened with per-frame
    // "Resolution change" spam. The construction is now stepwise (full, then
    // minimal) and the success log is emitted only after the build succeeds.

    @Test
    fun `the relabel success log is emitted only after the build`() {
        val branch = relabelBranch()
        val build = relabelCall.find(branch)?.range?.first ?: -1
        val log = branch.indexOf("re-advertising as hvc1")
        assertTrue("the branch must build the relabel", build >= 0)
        assertTrue("the branch must log the relabel", log >= 0)
        assertTrue(
            "the success log must come after the build — it may never announce a " +
                "relabel that did not happen",
            log > build
        )
    }

    @Test
    fun `both relabel logs report whether the container's hdrStaticInfo survived`() {
        // The 2026-10-11 field run reads this field off the relabel log:
        // "preserved" with picture closes the DV stall (the HDR10 static
        // metadata was the missing piece), "absent" means this encode carries
        // none and the hypothesis is untestable on it, and "preserved" with no
        // picture means the difference is not in the format. Both call sites
        // must report it, and the value is read off the format that is actually
        // handed to the decoder rather than assumed from the source — which,
        // under the 2026-10-14 no-relabel trial, is the source format itself.
        assertEquals(
            "the RELABEL_ONLY branch and the DV-hardware reroute must both report it",
            2,
            count(source, " codecSrc=\$codecSrc hdrStaticInfo=\$hdrStatic")
        )
        assertTrue(
            "the outcome must be read off the format that goes to the decoder, not assumed",
            source.contains("presented.colorInfo?.hdrStaticInfo != null")
        )
        assertTrue(
            "and it must distinguish exactly the two field outcomes",
            source.contains("else \"absent\"")
        )
    }

    @Test
    fun `every relabel failure log names the exception message, not just the class`() {
        assertTrue(
            "the full attempt must be logged as failed before the fallback",
            source.contains("full construction failed")
        )
        assertTrue(
            "the give-up path must be logged with the cause",
            source.contains("minimal construction failed")
        )
        assertTrue(
            "the 2026-10-10 capture showed only the class — a null message with no " +
                "cause chain is not diagnosable",
            source.contains("javaClass.simpleName") && source.contains(".message")
        )
    }

    @Test
    fun `the reroute is gated on native DV so non-DV boxes keep the mutating strip`() {
        // The gate is the whole safety property: on a box with no DV decoder the
        // old strip is the tested working path and must stay byte-for-byte as it
        // was.
        assertTrue(
            "the reroute must be gated on nativeDvSupported, not on convertAllProfiles",
            source.contains("if (hdr10BaseLayerProfile && nativeDvSupported) {")
        )
        val start = source.indexOf("HDR10 base layer: ")
        assertTrue("the mutating-strip log not found", start >= 0)
        val end = source.indexOf("mode = when {", start)
        assertTrue("the end of the mutating strip not found", end > start)
        val mutatingStrip = source.substring(start, end)
        assertTrue(
            "a box without a DV decoder still rewrites the VPS/SPS",
            mutatingStrip.contains("rewriteInitData")
        )
        assertTrue(
            "and still strips the RPU/EL",
            mutatingStrip.contains("mode = Mode.STRIPPING")
        )
        // ...and that rewrite is deliberately NOT gated on libdovi. These
        // samples are stripped by the Kotlin parser — transformAnnexB and
        // transformLengthDelimited never consult the bridge — so the config
        // rewrite is exactly the arm it was written for: it closes that
        // stripper's zero-output stall. (The two relabel sites, whose samples
        // ARE libdovi's, are the ones that skip it.)
        assertFalse(
            "the Kotlin-stripped path must keep its VPS rewrite",
            mutatingStrip.contains("skipVpsRewrite")
        )
    }

    // ── the 2026-10-13 green-screen arm (Nuvio parity), retired ───────
    //
    // Both field-green paths — Auto (the RELABEL_ONLY branch) and Strip All
    // (the DV-hardware reroute) — stripped with libdovi and then rewrote the
    // codec config's VPS, which is where they differed from Nuvio. Neither
    // takes that skip now: the samples on both arms are the Kotlin surgery's,
    // where a skipped rewrite is a stall rather than a green screen (the
    // 2026-10-15 report on a declared dvhe.08.06 track). The parameter stays —
    // the same shape as skipRelabel — and both sites still report the same log
    // field, so a capture can still say which of the two paths the VPS took.

    @Test
    fun `both relabel sites force the config VPS rewrite`() {
        assertEquals(
            "both relabel sites — Auto's RELABEL_ONLY branch and the DV-hardware reroute — " +
                "must still build through the shared call",
            2,
            count(source, RELABEL_CALL)
        )
        assertEquals(
            "no site may read the VPS skip off the bridge any more: the strip on both arms " +
                "is the Kotlin surgery's, where a DV VPS on stripped samples is the stall",
            0,
            count(source, "val skipVpsRewrite = DoviBridge.available")
        )
        assertEquals(
            "both must force the rewrite, like the mutating strip below always has",
            2,
            count(source, "val skipVpsRewrite = false")
        )
        assertEquals(
            "so a field log says which of the two paths the VPS took",
            2,
            count(source, "vpsRewrite=\${vpsRewriteOutcome(skipVpsRewrite, relabel.vpsRewritten)}")
        )
        assertEquals(
            "and both sites report the 2026-10-16 stall's own field: whether the config SPS " +
                "the decoder is configured with was rewritten, beside the VPS verdict",
            2,
            count(source, "spsRewrite=\${spsRewriteOutcome(skipVpsRewrite, relabel.spsOutcome)}")
        )
        assertTrue(
            "which is why the relabel target has to carry the rewrite's SPS verdict at all",
            source.contains("val spsOutcome: DolbyVisionCompat.SpsRewriteOutcome? = null")
        )
    }

    // ── the 2026-10-14 no-relabel test, removed ─────────────────────────
    //
    // The test ran once, on the night of 2026-10-14 (Fire TV Stick 4K Plus, P8
    // MKV, mode=3 Strip All), and field-failed: with the RPU NALs stripped but
    // the track still declared as video/dolby-vision, the MediaTek DV decoder
    // stayed in DV mode waiting for RPUs that never arrive and produced no
    // frame at all, and the surface reset + TextureView retry stalled the same
    // way. Strip-RPU + keep-DV-label is the poisoned combination, so both sites
    // now force the relabel (`skipRelabel = false`) and the log can no longer
    // report the skip; the library strip has to survive it, because the strip
    // is the half that works.

    @Test
    fun `every relabel site forces the relabel instead of reading a gate off the bridge`() {
        assertEquals(
            "both sites must force the relabel — the 2026-10-14 no-relabel arm is removed",
            2,
            count(source, "val skipRelabel = false")
        )
        assertEquals(
            "and neither may read it off the bridge, which is the gate that field-failed",
            0,
            count(source, "val skipRelabel = DoviBridge.available")
        )
        assertEquals(
            "the skipped arm may not build a relabel: the skip is decided before the attempt",
            2,
            count(
                source,
                "if (relabel.relabelSkipped) null else relabelDvFormat(relabel.format, relabelCodec)"
            )
        )
        assertEquals(
            "so a field log says which of the two paths the codec string took",
            2,
            count(source, "relabel=\${relabelOutcome(relabel.relabelSkipped)}")
        )
        assertTrue(
            "and the relabel this build ships is the one the field log names applied",
            source.contains("\"applied\"")
        )
    }

    @Test
    fun `a skipped relabel still strips with the library and hands the track on declared`() {
        // Both sites, because the trial is only meaningful if the two paths stay
        // comparable: the skipped arm must hand over the un-relabeled format and
        // still ask for the metadata strip.
        assertEquals(
            "both sites must take the strip path on the skipped arm too",
            2,
            count(source, "if (relabeled != null || relabel.relabelSkipped) {")
        )
        assertEquals(
            "and hand the decoder the un-relabeled format when there is no relabel",
            2,
            count(source, "val presented = relabeled?.format ?: relabel.format")
        )
        assertEquals(
            "which is what reaches the decoder",
            2,
            count(source, "delegate.format(presented)")
        )
        assertTrue(
            "while the samples are still libdovi's: Mode.STRIP_NALS_ONLY on both sites",
            source.contains("mode = Mode.STRIP_NALS_ONLY")
        )
        assertTrue(
            "and the log stops claiming a relabel that did not happen",
            source.contains("keeping the declared codec \${format.codecs ?: \"?\"} (no relabel)")
        )
    }

    private fun count(text: String, needle: String): Int {
        var from = 0
        var n = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }

    private companion object {
        const val EXTRACTOR =
            "com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt"

        /** The shared gated relabel construction, as it must read at both sites. */
        const val RELABEL_CALL =
            "relabelTargetFor(format, skipVpsRewrite = skipVpsRewrite, skipRelabel = skipRelabel)"
    }
}
