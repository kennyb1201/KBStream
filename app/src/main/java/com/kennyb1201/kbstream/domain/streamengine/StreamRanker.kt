package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream

object StreamRanker {
    fun rank(streams: List<Stream>): List<Stream> =
        streams.sortedByDescending { score(it) }

    private fun score(stream: Stream): Int {
        val text = (stream.title ?: stream.description ?: stream.name ?: "").lowercase()
        var score = 0

        when {
            "2160p" in text || "4k" in text -> score += 400
            "1080p" in text -> score += 300
            "720p" in text -> score += 200
            "480p" in text -> score += 100
        }

        if ("hdr" in text || "dolby vision" in text) score += 50
        if ("remux" in text) score += 30
        if ("cached" in text || "instant" in text || "⚡" in text) score += 80

        if (Regex("\\b(cam|hdcam|ts|telesync|hdts)\\b").containsMatchIn(text)) score -= 500

        Regex("([0-9]+(?:\\.[0-9]+)?)\\s?gb").find(text)?.let {
            val gb = it.groupValues[1].toDoubleOrNull() ?: 0.0
            score += (gb.coerceAtMost(20.0) * 2).toInt()
        }

        // torrent-only results (no direct URL) aren't playable yet -- sink to bottom, not removed
        if (stream.url == null) score -= 1000

        return score
    }
}
