package com.kennyb1201.kbstream.ui.player

import java.io.File
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

    private companion object {
        const val EXTRACTOR =
            "com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt"
    }
}
