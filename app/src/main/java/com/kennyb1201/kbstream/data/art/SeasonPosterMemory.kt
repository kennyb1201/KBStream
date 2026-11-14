package com.kennyb1201.kbstream.data.art

/**
 * What one art source answered - and, crucially, whether it answered at all
 * (spec 2026-11-14).
 *
 * The Detail page's season poster vanished on scroll-back because a re-lookup
 * that came back empty was written over a good URL, and the two very different
 * outcomes - "this season has no poster in either source" and "the TVDB-id
 * resolve (or the call behind it) did not answer" - both arrived as `null` from
 * [com.kennyb1201.kbstream.data.fanart.FanartArtRepository.seasonPoster]. Only
 * the first is allowed to clear a poster.
 */
enum class ArtSourceAnswer {
    /** The source answered with art. */
    FOUND,

    /** The source answered, and has no art for this request. */
    NONE,

    /** The request could not be made at all (no id, no key in this build). */
    NO_KEY,

    /** The source was asked and did not answer (offline, a failed call, a 4xx). */
    UNANSWERED;

    val token: String
        get() = when (this) {
            FOUND -> "found"
            NONE -> "none"
            NO_KEY -> "no-key"
            UNANSWERED -> "unanswered"
        }
}

/** One source's answer: the URL it would draw, and how it got there. */
data class ArtAnswer(val url: String?, val answer: ArtSourceAnswer)

/** A season-poster lookup's full answer, source by source. */
data class SeasonPosterAnswer(
    val url: String?,
    val tvdbId: Int?,
    val fanart: ArtSourceAnswer,
    val tmdb: ArtSourceAnswer
)

/**
 * What the Detail page does with a season-poster answer.
 *
 * The old code wrote the answer straight into the state, so a re-run that
 * answered nothing erased the poster that was already on screen - and nothing
 * re-triggered the effect afterwards, so it stayed erased (Kenny's "shows, then
 * disappears when I scroll back, and stays gone").
 *
 * The rule now is the one the season-change path always claimed: the previous
 * poster stays up while a lookup is in flight, and it is replaced only when the
 * lookup ANSWERS with a URL. It is cleared only when BOTH sources answered that
 * they have nothing for this season - the answered-none case the old comment
 * describes - and never on a lookup that did not answer.
 *
 * Pure, so the three outcomes are unit tested rather than observed on a phone.
 */
object SeasonPosterMemory {

    enum class Decision { KEEP, REPLACE, CLEAR }

    /**
     * [current] is the poster on screen now (null when there is none), [answer]
     * is the lookup's answer (null when nothing was looked up), [forSeries] and
     * [season] are the state the answer belongs to, and [tvdbProvenAbsent] says
     * TMDB has proven this show has no TVDB mapping - without that proof a
     * missing TVDB id means the resolve failed, not that fanart has nothing.
     */
    fun decide(
        current: String?,
        answer: SeasonPosterAnswer?,
        forSeries: Boolean,
        season: Int?,
        tvdbProvenAbsent: Boolean = false
    ): Decision {
        // A transient null season (a seasons re-emission) is not "no poster":
        // keep what is on screen.
        if (!forSeries || season == null) return Decision.KEEP
        if (answer == null) return Decision.KEEP
        if (!answer.url.isNullOrBlank()) return Decision.REPLACE
        val fanartAnsweredNone = answer.fanart == ArtSourceAnswer.NONE ||
            (answer.fanart == ArtSourceAnswer.NO_KEY && tvdbProvenAbsent)
        return if (fanartAnsweredNone && answer.tmdb == ArtSourceAnswer.NONE) {
            Decision.CLEAR
        } else {
            Decision.KEEP
        }
    }

    /** `set` / `cleared` / `kept`, for the diagnostic line. */
    fun describe(decision: Decision): String = when (decision) {
        Decision.REPLACE -> "set"
        Decision.CLEAR -> "cleared"
        Decision.KEEP -> "kept"
    }
}
