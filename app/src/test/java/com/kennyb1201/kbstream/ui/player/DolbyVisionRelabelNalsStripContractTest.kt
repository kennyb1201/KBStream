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
            "the handler must drop the HDR10+ SEI (the stream is presented as static HDR10)",
            handler.contains("stripHdr10Plus = true")
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
            reroute.contains("relabelDvFormat(format, rewriteCodec)")
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
        val calls = Regex("relabelDvFormat\\(format, rewriteCodec\\)")
            .findAll(source)
            .count()
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
        val build = branch.indexOf("relabelDvFormat(format, rewriteCodec)")
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
