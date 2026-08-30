package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream

object StreamRanker {

    /**
     * Rank streams by playability and quality.
     *
     * Priority:
     * 1. Filter out completely unplayable streams (no URL, no torrent hash)
     * 2. Prefer direct-play URLs over torrent-only streams
     * 3. Use actual stream properties (DRM, separate audio) as quality signals
     * 4. Fall back to text-based heuristics for tie-breaking
     */
    fun rank(streams: List<Stream>): List<Stream> {
        // Filter: keep streams that are either playable (have URL) or are torrents (have infoHash)
        val playable = streams.filter { stream ->
            val url = stream.url
            val hasUrl = !url.isNullOrBlank()
            val hasHash = !stream.infoHash.isNullOrBlank()

            if (hasUrl) {
                // Validate URL format — must be http(s) or a known protocol
                val lower = url!!.lowercase()
                lower.startsWith("http://") || lower.startsWith("https://") ||
                    lower.startsWith("file://") || lower.startsWith("rtmp://")
            } else {
                // Torrent-only: keep if it has an infoHash
                hasHash
            }
        }
        return playable.sortedByDescending { score(it) }
    }

    private fun score(stream: Stream): Int {
        val text = (stream.title ?: stream.description ?: stream.name ?: "").lowercase()
        var score = 0

        // --- Playability (highest priority) ---

        // Direct URL = actually playable right now
        val hasDirectUrl = !stream.url.isNullOrBlank()
        if (hasDirectUrl) score += 500

        // Torrent-only (infoHash but no URL) — playable only if torrent support is built in
        val hasTorrentHash = !stream.infoHash.isNullOrBlank()
        if (hasTorrentHash && !hasDirectUrl) score += 100

        // --- Quality signals from actual properties ---

        // DRM = usually premium/high-quality source
        if (stream.drm != null) score += 60

        // Separate audio track = often higher quality or proper muxing
        if (!stream.audioUrl.isNullOrBlank()) score += 40

        // --- Text-based quality hints (tie-breakers) ---

        when {
            "2160p" in text || "4k" in text -> score += 200
            "1080p" in text -> score += 150
            "720p" in text -> score += 100
            "480p" in text -> score += 50
        }

        if ("hdr" in text || "dolby vision" in text || "dv" in text) score += 30
        if ("remux" in text) score += 20
        if ("cached" in text || "instant" in text || "\u26a1" in text) score += 40

        // Penalize low-quality sources
        if (Regex("\\b(cam|hdcam|ts|telesync|hdts|webrip)\\b").containsMatchIn(text)) score -= 300

        // File size heuristic — bigger often means less compressed (but cap the bonus)
        Regex("([0-9]+(?:\\.[0-9]+)?)\\s?gb").find(text)?.let {
            val gb = it.groupValues[1].toDoubleOrNull() ?: 0.0
            score += (gb.coerceAtMost(20.0) * 1.5).toInt()
        }

        // Prefer streams that have a name (more metadata = more reliable source)
        if (!stream.name.isNullOrBlank()) score += 10
        if (!stream.title.isNullOrBlank()) score += 10

        return score
    }
}
