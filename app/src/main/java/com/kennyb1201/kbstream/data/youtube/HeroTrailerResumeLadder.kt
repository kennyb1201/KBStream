package com.kennyb1201.kbstream.data.youtube

/*
 * The bounded client-exclusion ladder a refused TV hero trailer walks, as a pure
 * decision - no Compose, no Android, no network - so its arithmetic is pinned by
 * a plain JVM test.
 *
 * Why a ladder at all: the 2026-10-28 capture (Onn 4K Plus, `android_vr`-signed
 * adaptive source, dead at 36,840 ms of 140,849 ms every play) is the boundary
 * death, and its cause is the URL CLASS one client signs - every offset read from
 * that class is refused, while the same identity serves the first probe fine. So
 * one exclusion answers one URL class, and a video whose refusal survives that
 * exclusion needs the NEXT class excluded, not another attempt with the same one.
 *
 * The ladder is bounded twice, because both bounds are real:
 *
 *  - by the clients themselves: `YouTubeClients.all` is what can sign a source,
 *    so a set that covers every key has nothing left to resolve with; and
 *  - by [MAX_HERO_TRAILER_RESUMES], which also bounds the case the first bound
 *    cannot: a death whose signing client is UNKNOWN (a NewPipe/Piped source, an
 *    evicted cache entry). Those grow no exclusion, so without the cap they
 *    could re-resolve forever.
 *
 * Poison - marking the video blocked for the session - is therefore a verdict
 * here, not a refusal counter: the video is blocked when the ladder is exhausted,
 * never because a second death merely happened.
 */

/** How many identity resumes the hero may spend on one trailer, in total. */
internal const val MAX_HERO_TRAILER_RESUMES = 4

/**
 * One requested resume: what to re-resolve for, excluding whom, and which
 * request it is.
 *
 * Held by the hero owner until the resolve it feeds COMPLETES. [requestId] is
 * what makes a re-issued run the same request rather than a new one: a run that
 * is cancelled leaves the record in place, so the next run resolves the same
 * exclusion set from the same position instead of starting over.
 *
 * [deadClient] is the client the death line names (display, with the trace's
 * fallback); [excludedClients] is the STRICT, cache-derived set the resolve is
 * told to skip. The two are deliberately not the same value: see
 * `TrailerPlayerLauncher.cachedSigningClient`.
 */
internal data class HeroTrailerResume(
    val positionMs: Long,
    val durationMs: Long,
    val deadClient: String?,
    val excludedClients: Set<String>,
    val requestId: Long
)

/** Whether a refusal may be answered with another resume, or ends the video. */
internal enum class HeroTrailerResumeVerdict {

    /** Spend one attempt on another client. */
    RESUME,

    /** Nothing left to try: mark the video blocked for this session. */
    POISON
}

/** A verdict plus the exclusion set a RESUME must carry. */
internal data class HeroTrailerResumeDecision(
    val verdict: HeroTrailerResumeVerdict,
    val nextExcludedClients: Set<String>
)

/**
 * What a refusal means for the ladder.
 *
 * [deadClient] is the client whose URL class just died, as the CACHE answers it
 * (null when unknown - a NewPipe/Piped source, or an entry already evicted).
 * [excludedClients] is what every earlier step of this ladder already excluded,
 * [resumeAttempts] how many steps have been spent, and [allClientKeys] the
 * clients that can sign a source at all.
 *
 * POISON when the cap is reached, or when growing the set would leave no client,
 * or when the set already leaves none. A null [deadClient] grows nothing and so
 * relies on the cap alone - the one case the set cannot bound.
 */
internal fun heroTrailerResumeDecision(
    deadClient: String?,
    excludedClients: Set<String>,
    resumeAttempts: Int,
    allClientKeys: Set<String> = YouTubeClients.all.map { it.key }.toSet(),
    cap: Int = MAX_HERO_TRAILER_RESUMES
): HeroTrailerResumeDecision {
    val grown = if (deadClient.isNullOrBlank()) excludedClients else excludedClients + deadClient
    val exhausted = grown.containsAll(allClientKeys)
    val verdict = if (resumeAttempts >= cap || exhausted) {
        HeroTrailerResumeVerdict.POISON
    } else {
        HeroTrailerResumeVerdict.RESUME
    }
    return HeroTrailerResumeDecision(verdict = verdict, nextExcludedClients = grown)
}

/**
 * Whether a replacement resolve that found NO source ends the video.
 *
 * Only when the ladder was actually walked: a first-attempt resolve failure with
 * an empty exclusion is an ordinary failure (a blocked video, a resolver outage)
 * and must leave the ordinary retry its chance, exactly as before. With a
 * non-empty exclusion, "no source from any remaining client" IS the ladder
 * running out, so the video is blocked rather than re-armable.
 */
internal fun heroTrailerResolveFailurePoisons(excludedClients: Set<String>): Boolean =
    excludedClients.isNotEmpty()
