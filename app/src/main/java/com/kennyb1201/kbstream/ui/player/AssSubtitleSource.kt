package com.kennyb1201.kbstream.ui.player

import java.io.File
import kotlin.math.roundToInt

/**
 * Pure preparation for an ASS/SSA sidecar: deciding whether a file is one,
 * repairing the header sections libass insists on, listing the fonts to
 * attach, and choosing the size libass lays out against.
 *
 * Kept free of Android and JNI types on purpose. Everything that can be
 * wrong in this feature without a device to test on is a text or geometry
 * decision, and those are the parts covered by AssSubtitleSourceTest.
 */
internal object AssSubtitleSource {

    /** Extensions libass can be handed directly. */
    private val ASS_EXTENSIONS = setOf("ass", "ssa")

    /** Font containers Android and fontconfig both understand. */
    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    /**
     * libass refuses a script with no `[Script Info]` section, and a sidecar
     * stripped down by a download service or a converter often is one. The
     * minimum libass needs before it will parse events at all.
     */
    private const val DEFAULT_SCRIPT_TYPE = "ScriptType: v4.00+"

    /**
     * The default style, so a file whose styling block was dropped still
     * renders as plain readable subtitles instead of nothing. Colours are
     * ASS's `&HAABBGGRR`: opaque white on a translucent black outline.
     */
    private val DEFAULT_STYLES = listOf(
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, " +
            "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, " +
            "ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
            "Alignment, MarginL, MarginR, MarginV, Encoding",
        "Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000," +
            "0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1"
    )

    /**
     * Column order for an `[Events]` section that lost its `Format:` line.
     * This is the v4+ order, which is what every `.ass` in the wild uses; a
     * file that has `Dialogue:` lines but no format line has no order at all,
     * so the standard one is the only sane insertion.
     */
    private const val EVENTS_FORMAT =
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"

    /**
     * True when [text] looks like an ASS/SSA script rather than an SRT or a
     * WebVTT file. Sidecars are frequently served from URLs with no useful
     * extension (an OpenSubtitles download is a numeric file id), so the
     * decision cannot be made from the URI alone.
     */
    fun isAssContent(text: String): Boolean {
        if (text.isBlank()) return false
        val probe = text.lineSequence().take(PROBE_LINES).map { it.trim() }
        return probe.any { line ->
            line.equals("[Script Info]", ignoreCase = true) ||
                line.equals("[V4+ Styles]", ignoreCase = true) ||
                line.equals("[V4 Styles]", ignoreCase = true) ||
                (line.startsWith("Dialogue:", ignoreCase = true) && ASS_TIME.containsMatchIn(line))
        }
    }

    /**
     * True when a file's own name says it is an ASS/SSA sidecar. The
     * content sniff in [isAssContent] is the authority; this only avoids
     * sniffing a file the name already settled.
     */
    fun isAssFileName(name: String?): Boolean =
        name?.substringAfterLast('.', "")?.lowercase() in ASS_EXTENSIONS

    /**
     * Returns a script libass can load: BOM stripped, line endings
     * normalised, and whichever of `[Script Info]`, `[V4+ Styles]` and
     * `[Events]` the file is missing filled in with sane defaults.
     *
     * A file that is already complete comes back with only the line-ending
     * and BOM normalisation applied, so this is safe to run over every
     * sidecar.
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n').toMutableList()

        if (sectionRange(lines, SCRIPT_INFO) == null) {
            lines.add(0, DEFAULT_SCRIPT_TYPE)
            lines.add(0, SCRIPT_INFO)
        }
        val info = sectionRange(lines, SCRIPT_INFO)
        if (info != null && !hasEntry(lines, info, "ScriptType:")) {
            lines.add(info.first + 1, DEFAULT_SCRIPT_TYPE)
        }

        if (sectionRange(lines, "[V4+ Styles]") == null && sectionRange(lines, "[V4 Styles]") == null) {
            lines += DEFAULT_STYLES
        }

        val events = sectionRange(lines, "[Events]")
        when {
            events == null -> lines += listOf("[Events]", EVENTS_FORMAT)
            !hasEntry(lines, events, "Format:") -> lines.add(events.first + 1, EVENTS_FORMAT)
        }

        return lines.joinToString("\n").trimEnd('\n') + "\n"
    }

    /**
     * Font files directly inside [dirs], in name order, capped at [limit].
     *
     * Only the top level is read: the directories that matter here (the
     * app's own drop-in folder, and the ones a user points at) are flat, and
     * a recursive walk of a system font tree would read tens of megabytes
     * into memory for fonts fontconfig can already reach.
     *
     * These are attached to libass by hand because fontconfig cannot see
     * them - it only knows the directories its config names, and an app's
     * private storage is not one of them.
     */
    fun collectFonts(dirs: List<File>, limit: Int = 32): List<File> =
        dirs.asSequence()
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().asSequence() }
            .filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
            .sortedBy { it.name }
            .take(limit)
            .toList()

    /**
     * The rectangle libass lays out against, given the video's own size.
     *
     * ASS positions are fractions of this rectangle, so it has to carry the
     * video's aspect ratio or every sign ends up misplaced - but rendering a
     * 4K frame of nothing but text costs real time on a TV box, so the long
     * edge is capped and the view scales the result back up.
     */
    fun viewport(
        videoWidth: Int,
        videoHeight: Int,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        maxHeight: Int = DEFAULT_MAX_HEIGHT
    ): IntArray {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return intArrayOf(maxWidth, maxHeight)
        }
        val scale = minOf(
            1.0,
            maxWidth.toDouble() / videoWidth,
            maxHeight.toDouble() / videoHeight
        )
        val width = (videoWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (videoHeight * scale).roundToInt().coerceAtLeast(1)
        return intArrayOf(width, height)
    }

    private const val SCRIPT_INFO = "[Script Info]"
    private const val DEFAULT_MAX_WIDTH = 1920
    private const val DEFAULT_MAX_HEIGHT = 1080

    /** How far into a file to look before concluding it is not an ASS script. */
    private const val PROBE_LINES = 200

    /** `0:00:01.23`, the second-based timestamp a `Dialogue:` line carries. */
    private val ASS_TIME = Regex("""\d+:\d{2}:\d{2}[.,]\d+""")

    /**
     * The `start..end` line range of a `[Section]` body, or null when the
     * section header is absent. [IntRange.last] is the last line before the
     * next header (or the end of the file).
     */
    private fun sectionRange(lines: List<String>, header: String): IntRange? {
        val start = lines.indexOfFirst { it.trim().equals(header, ignoreCase = true) }
        if (start < 0) return null
        var last = lines.size - 1
        for (i in start + 1 until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                last = i - 1
                break
            }
        }
        // An empty body is `start until start`, which rangeOf cannot express.
        if (last < start + 1) last = start
        return start..last
    }

    /** Whether the section body carries an entry starting with [prefix]. */
    private fun hasEntry(lines: List<String>, section: IntRange, prefix: String): Boolean =
        lines.subList(section.first + 1, section.last + 1)
            .any { it.trim().startsWith(prefix, ignoreCase = true) }
}
