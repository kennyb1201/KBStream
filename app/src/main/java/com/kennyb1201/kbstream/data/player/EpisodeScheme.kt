package com.kennyb1201.kbstream.data.player

import kotlin.math.roundToInt

/*
 * One file is not one episode.
 *
 * TMDB numbers a show the way it was BROADCAST and the way a viewer thinks of
 * it: Paw Patrol season 1 is 47 eleven-minute segments, CatDog's are 20
 * twenty-two-minute episodes that each hold two eleven-minute halves. The
 * addons that actually carry the files number them the way they were SHIPPED:
 * Paw Patrol ships 26 files that hold two segments each, CatDog ships 11
 * doubles, so its two TMDB episodes live in one file apiece.
 *
 * The app advanced its TMDB episode counter by one per played file - and that
 * arithmetic is internally consistent (labels, watched writes, autoplay
 * handoff and the source matcher all agree), which is exactly why it looked
 * correct while it drifted: file 2 of a segmented show is labelled "E2",
 * filed as E2, and next offers E3 - so a binge on Paw Patrol played files 1, 2,
 * 3 while claiming E1, E2, E3, and every second segment was never marked.
 * By episode 20 the labels named an episode two seasons away.
 *
 * This is the mapping between the two numbers. It is DETECTED rather than
 * configured: when a played file's duration is a multiple of the episode TMDB
 * says it is, the file covers that many TMDB episodes, and the other way round
 * when the file is a fraction of it. No setting, no per-show list to keep.
 *
 * [ONE_TO_ONE] is what every unknown answers, so a session with no duration, no
 * runtime, a movie or a live channel behaves exactly as it did before.
 */

/** Which way one file and TMDB's episodes divide. */
enum class SchemeKind { ONE_TO_ONE, SEGMENTS_PER_FILE, FILES_PER_EPISODE }

/**
 * How this show's files line up with TMDB's episodes, as detected from the file
 * the viewer actually played.
 *
 * [factor] is how many TMDB episodes a file covers for
 * [SchemeKind.SEGMENTS_PER_FILE] (Paw Patrol at 2), and how many files cover one
 * TMDB episode for [SchemeKind.FILES_PER_EPISODE] (CatDog at 2). It is
 * deliberately capped at 4: past that the "duration" is a bundle, a season
 * dump or a bad metadata row, and following it would be worse than doing
 * nothing.
 */
data class EpisodeScheme(val kind: SchemeKind, val factor: Int) {

    companion object {
        val ONE_TO_ONE = EpisodeScheme(SchemeKind.ONE_TO_ONE, 1)

        /** Same cap the detector applies, so a hand-edited prefs value is bounded too. */
        private const val MAX_DECODED_FACTOR = 4

        /**
         * The fraction of a TMDB runtime a file has to fall below, or above,
         * before the two are read as different episodes at all.
         *
         * The gap is the point. A 22-minute file against a 24-minute TMDB entry
         * is a normal title the app must NOT renumber - a scheme invented there
         * would silently skip every second episode of an ordinary show. Real
         * halves land near 0.5 and real doubles near 2.0, so the two bands are
         * far outside anything this leaves alone.
         */
        private const val UPPER_BOUND = 1.5
        private const val LOWER_BOUND = 0.67
        private const val MAX_FACTOR = 4

        /**
         * Pure detection. Unknown or non-positive durations answer
         * [ONE_TO_ONE], which is today's behavior.
         */
        fun detect(fileDurationMs: Long, tmdbRuntimeMs: Long): EpisodeScheme {
            if (fileDurationMs <= 0 || tmdbRuntimeMs <= 0) return ONE_TO_ONE
            val ratio = fileDurationMs.toDouble() / tmdbRuntimeMs.toDouble()
            return when {
                ratio >= UPPER_BOUND ->
                    EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, ratio.roundToInt().coerceIn(2, MAX_FACTOR))

                ratio <= LOWER_BOUND ->
                    EpisodeScheme(
                        SchemeKind.FILES_PER_EPISODE,
                        (1.0 / ratio).roundToInt().coerceIn(2, MAX_FACTOR)
                    )

                else -> ONE_TO_ONE
            }
        }

        /** [encode]'s inverse. Anything unreadable answers [ONE_TO_ONE]. */
        fun decode(raw: String?): EpisodeScheme {
            val value = raw?.trim()?.takeIf { it.length >= 3 } ?: return ONE_TO_ONE
            val factor = value.drop(2).toIntOrNull()?.takeIf { it >= 2 } ?: return ONE_TO_ONE
            return when (value.take(2).lowercase()) {
                "sp" -> EpisodeScheme(
                    SchemeKind.SEGMENTS_PER_FILE,
                    factor.coerceAtMost(MAX_DECODED_FACTOR)
                )

                "fe" -> EpisodeScheme(
                    SchemeKind.FILES_PER_EPISODE,
                    factor.coerceAtMost(MAX_DECODED_FACTOR)
                )

                else -> ONE_TO_ONE
            }
        }
    }

    /** 1-based file index holding TMDB episode [tmdbEp] (1-based). */
    fun fileForTmdbEpisode(tmdbEp: Int): Int = when (kind) {
        SchemeKind.ONE_TO_ONE -> tmdbEp
        SchemeKind.SEGMENTS_PER_FILE -> (tmdbEp - 1) / factor + 1
        SchemeKind.FILES_PER_EPISODE -> (tmdbEp - 1) * factor + 1
    }

    /**
     * The cursor after a file finished: the next file, and the next TMDB
     * episode to label it with.
     *
     * A degenerate session (no episode number at all) advances both by one,
     * which is the arithmetic that was here before.
     */
    fun advance(fileEp: Int, tmdbEp: Int): Pair<Int, Int> {
        if (fileEp < 1 || tmdbEp < 1) return (fileEp + 1) to (tmdbEp + 1)
        return when (kind) {
            SchemeKind.ONE_TO_ONE -> (fileEp + 1) to (tmdbEp + 1)

            // One file holds `factor` TMDB episodes, so the next file starts
            // `factor` episodes further along.
            SchemeKind.SEGMENTS_PER_FILE -> (fileEp + 1) to (tmdbEp + factor)

            // `factor` files share one TMDB episode: the number only ticks when
            // the next file is the first of its group.
            SchemeKind.FILES_PER_EPISODE -> {
                val nextFile = fileEp + 1
                val nextTmdb = if (nextFile % factor == 1) tmdbEp + 1 else tmdbEp
                nextFile to nextTmdb
            }
        }
    }

    /** The value stored in prefs: "sp2" / "fe3", or null for [ONE_TO_ONE]. */
    fun encode(): String? = when (kind) {
        SchemeKind.ONE_TO_ONE -> null
        SchemeKind.SEGMENTS_PER_FILE -> "sp$factor"
        SchemeKind.FILES_PER_EPISODE -> "fe$factor"
    }
}
