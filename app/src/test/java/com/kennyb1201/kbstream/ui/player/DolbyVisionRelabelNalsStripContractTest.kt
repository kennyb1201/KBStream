package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relabel-only experiment (DV-decoder box, non-DV display, Auto) must strip
 * the HDR10+ SEI in place while NEVER rewriting the VPS/SPS/PPS — neither the
 * init data nor in-sample — and while KEEPING the DV RPU. That isolation is the
 * experiment's load-bearing property: `transformAnnexB` rewrites the VPS
 * in-sample and would void the result, and dropping the RPU from a DV-flagged
 * track is what stalled the decoder (keep-RPU/strip-HDR10+ is the surviving
 * combination).
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
     * The sites have grown a named color-info argument (the temporary bisect arm
     * B), so the call is now written across lines — the pin is on the call and
     * on the arguments that must be identical at both sites, not on one line's
     * spelling. A site that stopped going through `relabelDvFormat` still fails,
     * and both sites are still counted independently below.
     */
    private val relabelCall = Regex("relabelDvFormat\\(\\s*format,\\s*rewriteCodec\\b")

    /** The `RELABEL_ONLY` branch body, up to the strip path's log line. */
    private fun relabelBranch(): String {
        val start = source.indexOf("if (handling == Hdr10BaseHandling.RELABEL_ONLY) {")
        assertTrue("the RELABEL_ONLY branch not found", start >= 0)
        val end = source.indexOf("HDR10 base layer: ", start)
        assertTrue("the RELABEL_ONLY branch end not found", end > start)
        return source.substring(start, end)
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
    fun `the relabel branch never rewrites the parameter sets`() {
        val branch = relabelBranch()
        assertFalse(
            "the relabel branch must not touch init data",
            branch.contains("setInitializationData")
        )
        assertFalse(
            "the relabel branch must not rewrite the VPS/SPS init data",
            branch.contains("rewriteInitData")
        )
    }

    @Test
    fun `the new mode strips metadata NALs without transforming the stream`() {
        val start = source.indexOf("if (mode == Mode.STRIP_NALS_ONLY) {")
        assertTrue("the STRIP_NALS_ONLY handler not found", start >= 0)
        val end = source.indexOf("val stats = DolbyVisionCompat.StripStats()", start)
        assertTrue("the STRIP_NALS_ONLY handler end not found", end > start)
        val handler = source.substring(start, end)
        assertTrue(
            "the handler must KEEP the DV RPU so the decoder does not stall",
            handler.contains("stripDv = false")
        )
        assertFalse(
            "the handler must not drop the DV metadata",
            handler.contains("stripDv = true")
        )
        assertTrue(
            "the handler must drop the HDR10+ SEI by default (the stream is presented " +
                "as static HDR10) — arm E is the only thing that flips it, with " +
                "stripHdr10Plus = !diagVimuConfig",
            handler.contains("stripHdr10Plus = !diagVimuConfig")
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
            "and engage Mode.STRIP_NALS_ONLY (stripDv=false, stripHdr10Plus=true)",
            reroute.contains("mode = Mode.STRIP_NALS_ONLY")
        )
        assertFalse(
            "the reroute must never rewrite the parameter sets",
            reroute.contains("setInitializationData") || reroute.contains("rewriteInitData")
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
    }

    private companion object {
        const val EXTRACTOR =
            "com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt"
    }
}
