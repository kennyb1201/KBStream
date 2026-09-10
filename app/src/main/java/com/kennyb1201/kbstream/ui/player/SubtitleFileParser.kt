package com.kennyb1201.kbstream.ui.player

/**
 * Minimal SRT / WebVTT text parser used for external subtitle files.
 *
 * Media3 has no subtitle-offset API (the feature request has been open
 * since 2015), and its text renderer only hands cues to listeners at
 * their authored time — so a negative offset ("show subs earlier") is
 * impossible through the player alone. When the user loads an external
 * subtitle file, KBStream parses it with this parser and drives the
 * display from the playback position instead, which supports offsets in
 * both directions. Embedded streams keep the clamp-at-zero behavior.
 */
object SubtitleFileParser {

    data class TimedCue(val startMs: Long, val endMs: Long, val text: String)

    // Matches both SRT (comma millis) and WebVTT (dot millis) timing lines:
    // 00:01:15,300 --> 00:01:18,100
    // 00:01:15.300 --> 00:01:18.100
    private val timingRegex = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )

    fun parse(content: String): List<TimedCue> {
        val cues = mutableListOf<TimedCue>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val match = timingRegex.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val start = toMs(
                match.groupValues[1], match.groupValues[2],
                match.groupValues[3], match.groupValues[4]
            )
            val end = toMs(
                match.groupValues[5], match.groupValues[6],
                match.groupValues[7], match.groupValues[8]
            )
            i++
            val text = buildList {
                while (i < lines.size && lines[i].isNotBlank()) {
                    add(lines[i])
                    i++
                }
            }.joinToString("\n")
            if (text.isNotBlank() && end > start) {
                cues.add(TimedCue(start, end, text))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun toMs(h: String, m: String, s: String, fraction: String): Long {
        val millis = fraction.padEnd(3, '0').take(3).toLong()
        return h.toLong() * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + millis
    }
}
