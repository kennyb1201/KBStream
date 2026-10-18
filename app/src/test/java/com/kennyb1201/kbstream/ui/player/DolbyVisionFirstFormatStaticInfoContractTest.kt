package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RPU's HDR10 static metadata has to be in the FIRST format the decoder is
 * configured with — not attached to a later one.
 *
 * The 2026-10-14 fix read the Level 6 block off the first sample and re-emitted
 * the format with it, and the field log then said `hdrStaticInfo=present`. But
 * the decoder had already been configured from the format emitted in `format()`
 * (which had no static metadata), and the picture stalled at bitrate 0: the
 * re-emit was a decoder restart the renderer did not pick up. The 2026-10-15
 * report is the whole reason this shape exists — the values are the same as
 * before, and only their POSITION changed.
 *
 * So a stripping track whose container declared no static metadata has its
 * format withheld ([deferHdr10StaticFormat]) and emitted once, on the first
 * sample, with the metadata already on it ([provideHdr10StaticMetadata]). Three
 * things about that are worth a test of their own, because each has a silent
 * failure mode:
 *
 *  - the withholding must be limited to formats that can actually gain
 *    metadata, or a passthrough track's format would be withheld and never
 *    emitted at all (its samples never reach the code that emits it);
 *  - the withheld format must NOT be emitted by a non-MAIN sample part (that
 *    was the 2026-10-14 defeat), only by the metadata-only zero-length guard,
 *    so a track cannot strand its format mid-stream;
 *  - `format()` must supersede anything it withheld, or a seek/re-init would
 *    emit a stale format later.
 *
 * The wiring lives inside a private `TrackOutput`, so this reads the source and
 * pins it — the same pattern the other source-contract tests use.
 */
class DolbyVisionFirstFormatStaticInfoContractTest {

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

    /** The `format()` override body: the call sites that withhold a format. */
    private fun formatBody(): String {
        val start = source.indexOf("override fun format(format: Format) {")
        assertTrue("format() not found", start >= 0)
        val end = source.indexOf("override fun sampleData(", start)
        assertTrue("format() end not found", end > start)
        return source.substring(start, end)
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

    @Test
    fun `every stripping emit site can withhold the format`() {
        // The 8.1 rewrite, both relabel sites (Auto non-DV display and the
        // DV-hardware reroute), the general strip, the declared-DV strip, and the
        // keep-DV strip (`Hdr10BaseHandling.STRIP_RPU_KEEP_DV`, which leaves the
        // track declared as Dolby Vision but still drops the RPU): every path
        // whose samples are stripped and whose format reached the decoder as
        // `delegate.format(...)`. One of them not going through the guard is one
        // path still configuring the decoder from a metadata-less format.
        assertEquals(
            "the stripping emit sites that withhold the format; a new strip path " +
                "that emits bare would keep the 2026-10-15 stall",
            6,
            count(source, "if (!deferHdr10StaticFormat(")
        )
        // And they are in format(), which the extractor calls before any sample.
        assertEquals(6, count(formatBody(), "if (!deferHdr10StaticFormat("))
    }

    @Test
    fun `a withheld format is emitted, once, with the metadata on the first sample`() {
        assertTrue(
            "the withheld format has to be the thing the metadata is built on",
            source.contains("val deferred = deferredFormat")
        )
        assertTrue(
            "attached the same way the mid-stream path attaches it, so the bytes are identical",
            source.contains(
                "deferred.buildUpon().setColorInfo(dvHdr10ColorInfoWith(static)).build()"
            )
        )
        assertTrue(
            "the field log has to distinguish the first format from the mid-stream change, " +
                "or a capture cannot tell which one this build is doing",
            source.contains("\"(first format, hdrStaticInfo=present")
        )
        assertTrue(
            "and it is emitted to the decoder, not just built",
            source.contains("delegate.format(out)")
        )
        assertTrue(
            "the deferred slot is cleared on use, so a second sample cannot re-emit it",
            count(source, "deferredFormat = null") >= 3
        )
    }

    @Test
    fun `only a format that can actually gain metadata is withheld`() {
        assertTrue(
            "without libdovi there is no reader for a Level 6 block, so nothing is withheld",
            source.contains("if (!DoviBridge.available) return false")
        )
        assertTrue(
            "the samples must be stripped: a passthrough track's samples never reach the " +
                "code that emits the withheld format, so withholding one would strand it",
            source.contains("if (mode != Mode.STRIPPING &&") &&
                source.contains("mode != Mode.STRIP_NALS_ONLY &&") &&
                source.contains("mode != Mode.STRIP_RPU_KEEP_DV")
        )
        assertTrue(
            "a format that already carries the container's static metadata has nothing to gain",
            source.contains("if (format.colorInfo?.hdrStaticInfo != null) return false")
        )
        assertTrue(
            "and it has to be a mime the DV rewrite paths configure the decoder with, the " +
                "same pair the mid-stream path checks",
            source.contains("format.sampleMimeType != MimeTypes.VIDEO_H265") &&
                source.contains("format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION")
        )
    }

    @Test
    fun `a withheld format can never be stranded`() {
        // The 2026-10-14 flush fix: a non-MAIN sample part must NOT flush a
        // withheld format. Doing so emitted the format as `hdrStaticInfo=absent`
        // (field: flushed at 29.901, the first MAIN sample's RPU read at 29.907),
        // so the metadata landed as a mid-stream format change instead of in the
        // first format. SampleQueue associates the format at sampleMetadata
        // time, not sampleData time, so holding it is safe — and the
        // metadata-only (zero-length) sample guard is the one path that still
        // emits it, because then no MAIN data arrived to read an RPU from. The
        // definition is the remaining mention of the name.
        assertEquals(
            "both non-MAIN sampleData branches must gate the flush on an empty deferral, so " +
                "a non-MAIN part cannot defeat it",
            2,
            count(source, "if (deferredFormat == null) {")
        )
        assertEquals(
            "and the flush's three call sites are the two gated ones plus the zero-length " +
                "guard; the definition makes the fourth mention",
            3,
            count(source, "flushDeferredFormatUnmodified()") - 1
        )
        assertTrue(
            "the zero-length sample guard still emits a withheld format, which is the one " +
                "path that must (no MAIN data arrived, so no RPU to read)",
            source.contains(
                "flushDeferredFormatUnmodified()\n            delegate.sampleMetadata"
            )
        )
        assertTrue(
            "and the flush emits (format then sample bytes), so the delegate's order holds",
            source.contains("logDecoderFormat(deferred)\n        delegate.format(deferred)")
        )
    }

    @Test
    fun `format supersedes anything the track withheld before it arrived`() {
        val body = formatBody()
        assertTrue(
            "a seek / re-init that emits a new format has to drop a withheld one, or the " +
                "old format could be emitted later, after the new one the decoder already has",
            body.contains("deferredFormat = null")
        )
    }

    private companion object {
        const val EXTRACTOR =
            "com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt"
    }
}
