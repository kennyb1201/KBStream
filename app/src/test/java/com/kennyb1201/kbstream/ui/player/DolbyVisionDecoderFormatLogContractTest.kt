package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder-format capture (2026-10-14) is the whole point of this round: the
 * byte-diff proved our stripper output is bit-identical to the reference
 * player's, yet Strip All still stalls on this chipset, so the remaining
 * difference has to be a Format field rather than a sample. `PLAYER_DV`'s
 * `decoder format:` line is what tells the two apart in a field capture, which
 * makes it instrumentation the result depends on — so it is pinned here rather
 * than left to a comment.
 *
 * Three things can go wrong with such a log and only the first is obvious:
 *  - the fields are wrong (Media3 carries HDR static metadata on [ColorInfo],
 *    not on `Format`, so a `format.hdrStaticInfo` spelling would not compile and
 *    a `format.colorInfo`-less one would silently read the wrong thing);
 *  - it is emitted somewhere that is not format time, i.e. after samples have
 *    already reached the decoder, which is the one position that cannot answer
 *    the question;
 *  - it covers the stripping branches but not the passthrough ones, so the
 *    capture cannot show the case that matters most for a stall — the path that
 *    was supposed to strip and did not.
 *
 * The log lives inside a private `TrackOutput`, so this reads the source and
 * pins the wiring, exactly as the other source-contract tests do.
 */
class DolbyVisionDecoderFormatLogContractTest {

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

    /** The body of the `format()` override, which ends where sample handling begins. */
    private fun formatBody(): String {
        val start = source.indexOf("override fun format(format: Format) {")
        assertTrue("format() not found", start >= 0)
        val end = source.indexOf("override fun sampleData(", start)
        assertTrue("format() end not found", end > start)
        return source.substring(start, end)
    }

    /** Every line that hands a Format to the decoder, in source order. */
    private fun formatEmitLines(): List<Int> =
        source.lines().withIndex()
            .filter { (_, line) -> line.contains("delegate.format(") }
            .map { (index, _) -> index }

    @Test
    fun `the log carries every field the field test compares`() {
        val body = formatBody()
        assertTrue(
            "the decoder-format line must be in format(), which the extractor calls " +
                "before any sample — emitted anywhere later it cannot precede the decoder's " +
                "own first-input-frame line",
            body.contains("logDecoderFormat(")
        )
        assertTrue(
            "the greppable prefix the field capture looks for",
            source.contains("decoder format: mime=")
        )
        assertTrue(
            "the MIME the decoder is queried with is the first thing to compare",
            source.contains("mime=\${format.sampleMimeType}")
        )
        assertTrue(
            "the codec string is what decides the decoder in the first place",
            source.contains("codecs=\${format.codecs ?: \"?\"}")
        )
        assertTrue(
            "HDR static metadata presence must be reported",
            source.contains("hdrStaticInfo=\${if (format.colorInfo?.hdrStaticInfo != null)")
        )
        assertTrue(
            "and the whole ColorInfo, so the actual values are in the capture",
            source.contains("colorInfo=\${format.colorInfo}")
        )
        assertTrue(
            "csd-0 is the codec config the decoder is initialised from",
            source.contains("csd0size=\${format.initializationData.getOrNull(0)?.size}")
        )
        assertTrue(
            "the tag must match the rest of the pipeline's",
            source.contains("\"PLAYER_DV\",\n            \"decoder format: mime=")
        )
    }

    @Test
    fun `hdr static metadata is read through ColorInfo, which is where Media3 keeps it`() {
        // Format has no hdrStaticInfo property: reading it anywhere else would
        // either not compile or report nothing.
        assertTrue(
            "the presence flag must read ColorInfo.hdrStaticInfo",
            source.contains("format.colorInfo?.hdrStaticInfo != null")
        )
        assertTrue(
            "both verdicts must be spelled out, because the capture greps for them",
            source.contains("\"present\"") && source.contains("\"absent\"")
        )
    }

    @Test
    fun `every format handed to the decoder is logged immediately before it is emitted`() {
        val lines = source.lines()
        val emits = formatEmitLines()
        assertTrue(
            "the extractor's emit sites moved; this pin is stale and must be re-read",
            emits.size >= 6
        )
        val unlogged = emits.filterNot { index ->
            lines.subList(maxOf(0, index - 1), index).any { it.contains("logDecoderFormat(") }
        }
        assertEquals(
            "these emit sites hand a Format to the decoder with no decoder-format log " +
                "before them, so the capture cannot say what the decoder received at them: " +
                unlogged.joinToString { "line ${it + 1}" },
            emptyList<Int>(),
            unlogged
        )
    }

    @Test
    fun `the log is called at more sites than it is defined, so it is not dead code`() {
        val definition = "private fun logDecoderFormat(format: Format) {"
        assertTrue("the helper must exist", source.contains(definition))
        val calls = count(source, "logDecoderFormat(") - 1
        assertEquals(
            "one call per emit site (the subtraction is the definition itself)",
            formatEmitLines().size,
            calls
        )
        assertEquals(
            "a second definition would shadow this pin",
            1,
            count(source, definition)
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
    }
}
